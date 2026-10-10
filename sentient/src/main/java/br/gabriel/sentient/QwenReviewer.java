package br.gabriel.sentient;

import java.util.function.BooleanSupplier;

/** The overnight review on the phone, with Qwen (private; slower and less sure than Claude). */
final class QwenReviewer implements Review.Reviewer {
    private final LocalBackend model;
    private final BooleanSupplier stop;

    QwenReviewer(LocalBackend model, BooleanSupplier stop) { this.model = model; this.stop = stop; }

    @Override public String name() { return "Qwen"; }
    /** About 1,500 tokens of items: well inside the 4,096-token context, quick to read on a phone. */
    @Override public int batchChars() { return 4_500; }
    @Override public int itemChars() { return 300; }

    @Override public String review(String items) throws Exception {
        return model.complete(Review.qwenPrompt(items), 64, stop);
    }
}
