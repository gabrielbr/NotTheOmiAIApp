package br.gabriel.sentient;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Something that answers questions from the knowledge store: Claude, or a model on the phone. */
public interface LlmBackend {
    /** Progress for the Ask screen. Called from the worker thread. */
    interface Listener {
        void status(String status);
        /** Text so far, for backends that stream; may be ignored. */
        void partial(String textSoFar);
    }

    final class Turn {
        public final String question, answer;
        public Turn(String question, String answer) { this.question = question; this.answer = answer; }
    }

    final class Answer {
        public final String text;
        /** Something the user should know about this answer (cut off, declined…), or null. */
        public final String notice;
        public Answer(String text, String notice) { this.text = text; this.notice = notice; }
    }

    String name();

    Answer answer(List<Turn> history, String question, Listener listener, BooleanSupplier cancelled) throws Exception;
}
