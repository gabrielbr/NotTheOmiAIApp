package br.gabriel.sentient;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * One secret (an API key or access token), kept only encrypted by its own non-exportable Android
 * Keystore key, in no-backup storage. Never logged, never shown again after saving.
 */
final class SecretStore {
    static final SecretStore CLAUDE = new SecretStore("gmind_claude_key_v1", "claude.key");
    static final SecretStore COMPOSIO = new SecretStore("gmind_composio_key_v1", "composio.key");
    /** JSON {homeserver, user_id, access_token}. */
    static final SecretStore MATRIX = new SecretStore("gmind_matrix_session_v1", "matrix.session");

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int IV_BYTES = 12, TAG_BITS = 128;
    private final String alias, fileName;

    private SecretStore(String alias, String fileName) { this.alias = alias; this.fileName = fileName; }

    /** The store a plugin asks for by name through PluginContext.secret; null if unknown. */
    static SecretStore named(String name) {
        switch (name) {
            case "composio": return COMPOSIO;
            case "matrix": return MATRIX;
            default: return null; // the Claude key is never handed to plugins
        }
    }

    /** Cheap: a file check only, no Keystore access. */
    boolean has(Context c) { return file(c).exists(); }

    void save(Context c, String secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
        cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        File file = file(c), temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(cipher.getIV());
            out.write(sealed);
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) throw new IllegalStateException("Could not save the secret");
    }

    void delete(Context c) {
        File file = file(c);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    /** Null when nothing is saved or it can no longer be decrypted (e.g. after a Keystore reset). */
    String read(Context c) {
        File file = file(c);
        if (!file.exists()) return null;
        try {
            byte[] blob = Files.readAllBytes(file.toPath());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            cipher.updateAAD(alias.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The last four characters, for "Key saved · …abcd"; null if none. */
    String hint(Context c) {
        String secret = read(c);
        return secret == null || secret.length() < 4 ? null : secret.substring(secret.length() - 4);
    }

    private File file(Context c) { return new File(c.getNoBackupFilesDir(), fileName); }

    private SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(alias, null);
    }
}
