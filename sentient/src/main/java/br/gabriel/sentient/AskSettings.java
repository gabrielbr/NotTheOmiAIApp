package br.gabriel.sentient;

import android.content.Context;
import android.content.SharedPreferences;
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
 * Who answers questions, and the Claude API key. The key is kept only encrypted, by a
 * non-exportable Android Keystore key, in no-backup storage; it is never logged or shown again.
 */
final class AskSettings {
    static final String CLAUDE = "claude", LOCAL = "local";
    static final String HAIKU = "claude-haiku-5-5", SONNET = "claude-sonnet-5-5", OPUS = "claude-opus-5-5";
    static final String[] MODELS = {HAIKU, SONNET, OPUS};
    private static final String PREFS = "ask", BACKEND = "backend", MODEL = "model";
    private static final String KEYSTORE = "AndroidKeyStore", ALIAS = "gmind_claude_key_v1", FILE = "claude.key";
    private static final int IV_BYTES = 12, TAG_BITS = 128;

    private AskSettings() {}

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String backend(Context c) { return prefs(c).getString(BACKEND, CLAUDE); }
    static void setBackend(Context c, String backend) { prefs(c).edit().putString(BACKEND, backend).apply(); }

    static String model(Context c) {
        String m = prefs(c).getString(MODEL, HAIKU);
        for (String known : MODELS) if (known.equals(m)) return m;
        return HAIKU;
    }
    static void setModel(Context c, String model) { prefs(c).edit().putString(MODEL, model).apply(); }

    static String modelName(String model) {
        switch (model) {
            case OPUS: return "Claude Opus 5.5";
            case SONNET: return "Claude Sonnet 5.5";
            default: return "Claude Haiku 5.5";
        }
    }

    static boolean hasKey(Context c) { return keyFile(c).exists(); }

    /** The saved key's last four characters, for "Key saved · …abcd"; null if none. */
    static String keyHint(Context c) {
        String key = apiKey(c);
        return key == null || key.length() < 4 ? null : key.substring(key.length() - 4);
    }

    static void saveKey(Context c, String key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
        cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
        byte[] sealed = cipher.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
        File file = keyFile(c), temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(cipher.getIV());
            out.write(sealed);
            out.getFD().sync();
        }
        if (!temp.renameTo(file)) throw new IllegalStateException("Could not save the key");
    }

    static void deleteKey(Context c) {
        File file = keyFile(c);
        if (file.exists() && !file.delete()) file.deleteOnExit();
    }

    /** Null when no key is saved or it can no longer be decrypted (e.g. after a Keystore reset). */
    static String apiKey(Context c) {
        File file = keyFile(c);
        if (!file.exists()) return null;
        try {
            byte[] blob = Files.readAllBytes(file.toPath());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            cipher.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return null;
        }
    }

    private static File keyFile(Context c) { return new File(c.getNoBackupFilesDir(), FILE); }

    private static SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
