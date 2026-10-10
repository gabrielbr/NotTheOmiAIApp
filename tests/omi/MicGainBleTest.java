package app.nottheomi.ai;

import android.bluetooth.*;
import android.os.Handler;
import java.util.*;
import org.concentus.*;
import static app.nottheomi.ai.ButtonBleTest.*;

/** Actual production client: the chosen mic gain is written once per connection, after the
 * button CCC, and never disturbs audio. Not Android/wearable acceptance. */
public final class MicGainBleTest {
    static final UUID SETTINGS = UUID.fromString("19b10010-e8f2-537e-4f6c-d104768a1214");
    static final UUID MIC = UUID.fromString("19b10012-e8f2-537e-4f6c-d104768a1214");
    static BluetoothGattCharacteristic addMic(Fixture f, int properties) {
        BluetoothGattService service = f.remote.services.computeIfAbsent(SETTINGS, BluetoothGattService::new);
        BluetoothGattCharacteristic c = characteristic(MIC, false);
        c.properties = properties; service.add(c); return c;
    }
    static void writeAck(Fixture f, BluetoothGattCharacteristic c, int status) {
        f.remote.pending = null; f.remote.callback.onCharacteristicWrite(f.remote, c, status); Handler.drain();
    }
    public static void main(String[] args) throws Exception {
        OpusEncoder encoder = new OpusEncoder(16000, 1, OpusApplication.OPUS_APPLICATION_VOIP);
        short[] samples = new short[320];
        for (int i = 0; i < samples.length; i++) samples[i] = (short)(10000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        byte[] encoded = new byte[1275]; int size = encoder.encode(samples, 0, samples.length, encoded, 0, encoded.length);
        check(size > 0, "real Opus encoded fixture"); ButtonBleTest.opus = Arrays.copyOf(encoded, size);
        scenario("no choice: nothing written, the device keeps its own gain");
        Fixture f = new Fixture(); addMic(f, 2 | 8); f.ready();
        check(f.remote.commands.isEmpty(), "no write without a chosen level");
        f.checkAudio("unset"); f.ble.stop(); Handler.drain();

        scenario("chosen level written once, after the button CCC, then audio flows");
        f = new Fixture(); BluetoothGattCharacteristic mic = addMic(f, 2 | 8); f.ble.setMicGain(7);
        f.connect(); f.toAudioCcc(false); f.ack(f.audioCcc, 0);
        check(f.remote.commands.isEmpty() && f.remote.pending == f.buttonCcc, "button CCC first, gain write waits");
        f.ack(f.buttonCcc, 0);
        check(f.remote.commands.size() == 1 && Arrays.equals(f.remote.commands.get(0), new byte[]{7})
                && f.remote.commandTargets.get(0) == mic, "one byte, the chosen level, to 19B10012");
        f.checkAudio("while writing");
        writeAck(f, mic, 0);
        check(f.sink.status().contains("mic gain set"), "success visible");
        check(!f.remote.closed && f.remote.commands.size() == 1, "connection kept, written once");
        f.checkAudio("after write"); f.ble.stop(); Handler.drain();

        scenario("out-of-range choices are ignored");
        for (int bad : new int[]{-2, 9, 255, Integer.MAX_VALUE}) {
            f = new Fixture(); addMic(f, 2 | 8); f.ble.setMicGain(bad); f.ready();
            check(f.remote.commands.isEmpty(), "never written: " + bad); f.ble.stop(); Handler.drain();
        }

        scenario("unsupported firmware: no characteristic or no write property");
        for (int properties : new int[]{-1, 2, 8}) {
            f = new Fixture(); if (properties >= 0) addMic(f, properties); f.ble.setMicGain(6); f.ready();
            check(f.remote.commands.isEmpty() && f.sink.status().contains("mic gain not supported"), "reported, not written");
            f.checkAudio("unsupported"); f.ble.stop(); Handler.drain();
        }

        scenario("rejected or failed write leaves audio and the connection alone");
        f = new Fixture(); mic = addMic(f, 2 | 8); f.ble.setMicGain(5); f.ready();
        int gaps = f.sink.gaps; writeAck(f, mic, 133);
        check(f.sink.status().contains("mic gain not set") && !f.remote.closed && f.sink.gaps == gaps, "failure is optional");
        f.checkAudio("after failure"); f.ble.stop(); Handler.drain();
        f = new Fixture(); addMic(f, 2 | 8); f.remote.rejectCommand = true; f.ble.setMicGain(5); f.ready();
        check(f.sink.status().contains("mic gain not set") && !f.remote.closed, "rejected submission reported");
        f.checkAudio("after rejection"); f.ble.stop(); Handler.drain();
        f = new Fixture(); addMic(f, 2 | 8); f.remote.commandFailure = new IllegalStateException("native"); f.ble.setMicGain(5); f.ready();
        check(f.sink.status().contains("mic gain unavailable") && !f.remote.closed, "thrown submission quarantines optional lane only");
        f.checkAudio("after throw"); f.ble.stop(); Handler.drain();

        scenario("a late write ack from an earlier connection is ignored");
        f = new Fixture(); mic = addMic(f, 2 | 8); f.ble.setMicGain(4); f.ready();
        BluetoothGatt old = f.remote; f.ble.stop(); Handler.drain();
        old.callback.onCharacteristicWrite(old, mic, 0); Handler.drain();
        check(!f.sink.status().contains("mic gain set"), "stale ack cannot report success");
        System.out.println("PASS: " + assertions + " assertions across " + scenarios + " mic gain host scenarios");
    }
}
