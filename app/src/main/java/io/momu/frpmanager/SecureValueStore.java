package io.momu.frpmanager;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM wrapper for the small set of credential values stored by the app. */
final class SecureValueStore {

    private static final String TAG = "SecureValueStore";
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "io.momu.frpmanager.credentials.v1";
    private static final String PREFIX = "v1:";
    private final SharedPreferences preferences;

    SecureValueStore(Context context, SharedPreferences preferences) {
        this.preferences = preferences;
    }

    void putString(String key, String value) {
        if (value == null) {
            preferences.edit().remove(key).apply();
            return;
        }
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
			// MyApplication installs Bouncy Castle for PEM parsing. Android Keystore keys have
			// no encoded material and must be initialized by the platform Keystore provider,
			// never by the globally preferred BC provider.
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", ANDROID_KEY_STORE);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String encoded = PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            preferences.edit().putString(key, encoded).apply();
        } catch (GeneralSecurityException | RuntimeException exception) {
            // Some devices, work profiles, and restored backups do not expose a usable
            // Android Keystore. Connection settings must remain persistable in that case.
            Log.w(TAG, "Android Keystore unavailable; using app-private preferences", exception);
            preferences.edit().putString(key, value).apply();
        }
    }

    String getString(String key, String defaultValue) {
        String encoded = preferences.getString(key, null);
        if (encoded == null) {
            return defaultValue;
        }
        if (!encoded.startsWith(PREFIX)) {
            // One-time migration from the pre-1.3.5 plaintext preference format.
            putString(key, encoded);
            return encoded;
        }
        try {
            String[] parts = encoded.split(":", 3);
            if (parts.length != 3) {
                throw new GeneralSecurityException("invalid encrypted preference format");
            }
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding", ANDROID_KEY_STORE);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128,
                Base64.decode(parts[1], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[2], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException exception) {
            // A restored encrypted preference may no longer have its device-bound key.
            // Do not make the settings screen unusable; a subsequent save replaces it.
            Log.w(TAG, "Saved encrypted value cannot be read", exception);
            return defaultValue;
        }
    }

    void remove(String key) {
        preferences.edit().remove(key).apply();
    }

	private SecretKey getOrCreateKey() throws GeneralSecurityException {
		KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
		try {
			keyStore.load(null);
		} catch (java.io.IOException exception) {
			throw new GeneralSecurityException("无法加载 Android Keystore", exception);
		}
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build());
        return generator.generateKey();
    }
}
