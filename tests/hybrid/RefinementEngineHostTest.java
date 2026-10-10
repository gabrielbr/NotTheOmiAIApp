package app.nottheomi.ai;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RefinementEngineHostTest {
    private static int scenarios, assertions;
    private static void check(boolean good, String reason) { assertions++; if (!good) throw new AssertionError(reason); }
    private interface Checked { void run() throws Exception; }
    private static void rejected(Checked action, Class<? extends Throwable> expected) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + expected); }
        catch (Exception failure) { check(expected.isInstance(failure), "Wrong exception: " + failure); }
    }
    private static final class Sink implements RefinementEngine.Sink {
        long offset; int complete; List<String> text = new ArrayList<>();
        Sink(long start) { offset = start; }
        public void commit(long before, long after, String value) {
            check(before == offset && after > before, "CAS checkpoint order"); offset = after; text.add(value);
        }
        public void complete() { complete++; }
    }
    private static byte[] pcm(int samples) {
        byte[] result = new byte[samples * 2];
        for (int i = 0; i < samples; i++) { short v = (short) (i % 32000 - 16000); result[i * 2] = (byte) v; result[i * 2 + 1] = (byte) (v >> 8); }
        return result;
    }
    private static RefinementEngine.Source chunks(byte[] data, int chunk) {
        return consumer -> {
            for (int at = 0; at < data.length; at += chunk) {
                byte[] supplied = Arrays.copyOfRange(data, at, Math.min(data.length, at + chunk));
                try { consumer.accept(supplied); }
                finally { for (byte v : supplied) if (v != 0) throw new AssertionError("PCM not wiped on callback exit"); }
            }
        };
    }
    public static void main(String[] args) throws Exception {
        int window = RefinementEngine.WINDOW_SAMPLES;
        byte[] longPcm = pcm(window * 2 + 19); Sink full = new Sink(0); int[] decoded = {0};
        List<short[]> retained = new ArrayList<>();
        RefinementEngine.run(longPcm.length, 0, chunks(longPcm, 6414), samples -> {
            check(samples.length <= window, "Bounded native input");
            for (short value : samples) { check(value == (short) (decoded[0] % 32000 - 16000), "PCM byte order and continuity"); decoded[0]++; }
            retained.add(samples); return " batch-" + decoded[0] + " ";
        }, full, () -> false);
        check(full.complete == 1 && full.offset == longPcm.length && full.text.size() == retained.size() && retained.size() >= 3, "All windows committed before publication");
        for (int i = 0; i + 1 < retained.size(); i++) check(retained.get(i).length >= RefinementEngine.MIN_WINDOW_SAMPLES, "Windows cut no earlier than 18 s");
        for (short[] samples : retained) for (short value : samples) check(value == 0, "Native input wiped"); scenarios++;

        long resume = retained.get(0).length * 2L; Sink resumed = new Sink(resume); int[] n = {(int) resume / 2};
        List<Integer> resumedSizes = new ArrayList<>();
        RefinementEngine.run(longPcm.length, resume, chunks(longPcm, 1002), samples -> {
            resumedSizes.add(samples.length);
            for (short value : samples) { check(value == (short) (n[0] % 32000 - 16000), "Resume prefix not duplicated"); n[0]++; } return "resumed";
        }, resumed, () -> false);
        check(resumed.complete == 1 && resumed.text.size() == retained.size() - 1 && resumed.offset == longPcm.length, "Resume exact offset");
        for (int i = 0; i < resumedSizes.size(); i++) check(resumedSizes.get(i) == retained.get(i + 1).length, "Resumed pass cuts where the first pass did"); scenarios++;

        // Speech-like audio with one silent 100 ms at 25 s: the first window ends in that silence.
        byte[] gap = pcm(window + 16000);
        int quiet = 25 * 16000;
        for (int i = quiet; i < quiet + 1600; i++) { gap[i * 2] = 0; gap[i * 2 + 1] = 0; }
        List<Integer> sizes = new ArrayList<>(); Sink cut = new Sink(0);
        RefinementEngine.run(gap.length, 0, chunks(gap, 3200), samples -> { sizes.add(samples.length); return "x"; }, cut, () -> false);
        check(sizes.size() == 2 && sizes.get(0) == quiet + 800 && sizes.get(0) + sizes.get(1) == window + 16000, "Window ends in the pause");
        check(cut.complete == 1 && cut.offset == gap.length, "Pause-cut windows cover all audio"); scenarios++;

        Sink done = new Sink(longPcm.length);
        RefinementEngine.run(longPcm.length, longPcm.length, chunks(longPcm, 32000), samples -> { throw new AssertionError("Completed checkpoint must not decode again"); }, done, () -> false);
        check(done.complete == 1 && done.text.isEmpty(), "Crash after last commit completes without replay"); scenarios++;

        AtomicBoolean cancel = new AtomicBoolean(); Sink paused = new Sink(0);
        rejected(() -> RefinementEngine.run(longPcm.length, 0, chunks(longPcm, 32000), samples -> { cancel.set(true); return "stale"; }, paused, cancel::get), RefinementEngine.Paused.class);
        check(paused.offset == 0 && paused.complete == 0 && paused.text.isEmpty(), "Cancelled inference never publishes"); scenarios++;

        Sink midway = new Sink(0); AtomicBoolean stopAfterCommit = new AtomicBoolean();
        RefinementEngine.Sink checkpointThenStop = new RefinementEngine.Sink() {
            public void commit(long before, long after, String text) { midway.commit(before, after, text); stopAfterCommit.set(true); }
            public void complete() { throw new AssertionError("Paused stream marked complete"); }
        };
        rejected(() -> RefinementEngine.run(longPcm.length, 0, chunks(longPcm, 32000), samples -> "one", checkpointThenStop, stopAfterCommit::get), RefinementEngine.Paused.class);
        check(midway.offset == retained.get(0).length * 2L && midway.text.size() == 1, "Durable checkpoint retained before pause"); scenarios++;

        byte[] shortPcm = pcm(127); Sink invalid = new Sink(0);
        rejected(() -> RefinementEngine.run(0, 0, chunks(shortPcm, 100), s -> "", invalid, () -> false), IOException.class);
        rejected(() -> RefinementEngine.run(254, 3, chunks(shortPcm, 100), s -> "", invalid, () -> false), IOException.class);
        rejected(() -> RefinementEngine.run(254, 256, chunks(shortPcm, 100), s -> "", invalid, () -> false), IOException.class); scenarios++;

        rejected(() -> RefinementEngine.run(256, 0, chunks(shortPcm, 100), s -> "", invalid, () -> false), IOException.class);
        check(invalid.offset == 0 && invalid.complete == 0, "Truncated PCM cannot publish"); scenarios++;
        rejected(() -> RefinementEngine.run(252, 0, chunks(shortPcm, 100), s -> "", invalid, () -> false), IOException.class);
        check(invalid.offset == 0, "Excess PCM rejected"); scenarios++;
        byte[] odd = {1,2,3}; rejected(() -> RefinementEngine.run(4, 0, c -> c.accept(odd), s -> "", invalid, () -> false), IOException.class);
        check(Arrays.equals(odd, new byte[3]), "Malformed input wiped"); scenarios++;

        List<short[]> failedInput = new ArrayList<>();
        rejected(() -> RefinementEngine.run(254, 0, chunks(shortPcm, 100), s -> {failedInput.add(s); throw new IOException("Decoder unavailable");}, invalid, () -> false), IOException.class);
        for (short value : failedInput.get(0)) check(value == 0, "Failed native input wiped");
        check(invalid.offset == 0 && invalid.complete == 0, "Decoder failure retains draft"); scenarios++;

        Sink silence = new Sink(0); RefinementEngine.run(254, 0, chunks(shortPcm, 100), s -> "  ", silence, () -> false);
        check(silence.offset == 254 && silence.complete == 1 && silence.text.get(0).isEmpty(), "Empty recognizer output still advances audio cursor"); scenarios++;
        rejected(() -> RefinementEngine.run(254, 0, chunks(shortPcm, 100), s -> null, invalid, () -> false), IOException.class);
        check(invalid.complete == 0, "Null output is not success"); scenarios++;

        rejected(() -> RefinementEngine.run(254, 0, chunks(shortPcm, 100), s -> "decoded", new RefinementEngine.Sink() {
            public void commit(long before, long after, String text) throws Exception { throw new IOException("Disk full"); }
            public void complete() { throw new AssertionError("Failed commit published"); }
        }, () -> false), IOException.class); scenarios++;
        rejected(() -> RefinementEngine.run(254, 0, c -> {throw new AssertionError("Cancelled before reading");}, s -> "", invalid, () -> true), RefinementEngine.Paused.class); scenarios++;
        System.out.println("{\"result\":\"PASS_REFINEMENT_ENGINE_HOST\",\"scenarios\":" + scenarios + ",\"assertions\":" + assertions + ",\"scope\":\"production bounded engine with fake decoder/store; not native recognition\"}");
    }
}
