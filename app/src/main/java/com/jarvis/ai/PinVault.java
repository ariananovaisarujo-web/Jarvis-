package com.jarvis.ai;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps the phone unlock PIN encrypted with a key that never leaves the Android Keystore. */
final class PinVault {
    private static final String ALIAS = "jarvis_pin_key";
    private static final String KEY = "pinEnc";

    private PinVault() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return g.generateKey();
    }

    static boolean has(Context c) {
        return !new Prefs(c).raw(KEY).isEmpty();
    }

    static void save(Context c, String pin) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
        ci.init(Cipher.ENCRYPT_MODE, key());
        byte[] enc = ci.doFinal(pin.getBytes(StandardCharsets.UTF_8));
        byte[] iv = ci.getIV();
        byte[] all = new byte[1 + iv.length + enc.length];
        all[0] = (byte) iv.length;
        System.arraycopy(iv, 0, all, 1, iv.length);
        System.arraycopy(enc, 0, all, 1 + iv.length, enc.length);
        new Prefs(c).setString(KEY, Base64.encodeToString(all, Base64.NO_WRAP));
    }

    static String load(Context c) {
        try {
            String s = new Prefs(c).raw(KEY);
            if (s.isEmpty()) return null;
            byte[] all = Base64.decode(s, Base64.NO_WRAP);
            int ivLen = all[0];
            Cipher ci = Cipher.getInstance("AES/GCM/NoPadding");
            ci.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 1, ivLen));
            return new String(ci.doFinal(all, 1 + ivLen, all.length - 1 - ivLen), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context c) {
        new Prefs(c).setString(KEY, "");
    }
}
