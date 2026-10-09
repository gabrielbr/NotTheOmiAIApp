package br.gabriel.sentient;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The knowledge store's random 256-bit SQLCipher key, kept on disk only wrapped by a
 * non-exportable Android Keystore AES-GCM key. Like Omi Tarefas: clearing app data or
 * uninstalling loses the key, and with it the store.
 */
final class KeyVault {
    private static final String KEYSTORE = "AndroidKeyStore", ALIAS = "sentient_store_v1";
    private static final String FILE = "store.key";
    private static final int IV_BYTES = 12, TAG_BITS = 128;

    private KeyVault() {}

    /** SQLCipher raw-key form x'<64 hex>' as bytes, so no passphrase derivation is needed. */
    static byte[] storeKey(Context context) throws Exception {
        File file = new File(context.getNoBackupFilesDir(), FILE);
        byte[] raw;
        if (file.exists()) {
            byte[] blob = Files.readAllBytes(file.toPath());
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), new GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES));
            cipher.updateAAD(ALIAS.getBytes("UTF-8"));
            raw = cipher.doFinal(blob, IV_BYTES, blob.length - IV_BYTES);
        } else {
            raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
            cipher.updateAAD(ALIAS.getBytes("UTF-8"));
            byte[] sealed = cipher.doFinal(raw);
            byte[] iv = cipher.getIV();
            File temp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(iv);
                out.write(sealed);
                out.getFD().sync();
            }
            if (!temp.renameTo(file)) throw new IllegalStateException("Could not save the store key");
        }
        StringBuilder hex = new StringBuilder("x'");
        for (byte b : raw) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        Arrays.fill(raw, (byte) 0);
        return hex.append('\'').toString().getBytes("US-ASCII");
    }

    private static SecretKey wrappingKey() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
