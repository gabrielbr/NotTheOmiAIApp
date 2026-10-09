package br.gabriel.sentient.plugin;

/**
 * The source can't be reached right now (app not installed, permission missing, offline).
 * Its message is shown to the user as the source's status, so it must never contain
 * content from the source. Other exceptions are shown by class name only.
 */
public final class SourceUnavailableException extends Exception {
    private static final long serialVersionUID = 1L;

    public SourceUnavailableException(String userVisibleReason) { super(userVisibleReason); }
}
