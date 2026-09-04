package dev.codexwatch.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureTokenStore {
    static final class Tokens {
        final String accessToken;
        final String refreshToken;
        final long expiresAt;
        final String accountId;
        final String email;
        final String plan;

        Tokens(String accessToken, String refreshToken, long expiresAt,
               String accountId, String email, String plan) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresAt = expiresAt;
            this.accountId = accountId;
            this.email = email;
            this.plan = plan;
        }
    }

    private static final String PREFERENCES_NAME = "openai_credentials";
    private static final String TOKENS_KEY = "chatgpt_oauth";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "dev.codexwatch.demo.chatgpt_oauth";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SharedPreferences preferences;

    SecureTokenStore(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    synchronized Tokens load() {
        String encoded = preferences.getString(TOKENS_KEY, null);
        if (encoded == null) return null;
        try {
            JSONObject envelope = new JSONObject(encoded);
            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(envelope.getString("data"), Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            JSONObject payload = new JSONObject(
                new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8)
            );
            return new Tokens(
                payload.getString("access_token"),
                payload.getString("refresh_token"),
                payload.getLong("expires_at"),
                payload.getString("account_id"),
                nullableString(payload, "email"),
                nullableString(payload, "plan")
            );
        } catch (Throwable error) {
            clear();
            throw new IllegalStateException("No se pudieron descifrar las credenciales guardadas", error);
        }
    }

    synchronized void save(Tokens tokens) {
        try {
            JSONObject payload = new JSONObject()
                .put("access_token", tokens.accessToken)
                .put("refresh_token", tokens.refreshToken)
                .put("expires_at", tokens.expiresAt)
                .put("account_id", tokens.accountId)
                .put("email", tokens.email == null ? JSONObject.NULL : tokens.email)
                .put("plan", tokens.plan == null ? JSONObject.NULL : tokens.plan);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] encrypted = cipher.doFinal(payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            JSONObject envelope = new JSONObject()
                .put("version", 1)
                .put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
            if (!preferences.edit().putString(TOKENS_KEY, envelope.toString()).commit()) {
                throw new IllegalStateException("No se pudieron guardar las credenciales");
            }
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("No se pudieron cifrar las credenciales", error);
        }
    }

    synchronized void clear() {
        preferences.edit().remove(TOKENS_KEY).apply();
    }

    private SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build());
        return generator.generateKey();
    }

    private static String nullableString(JSONObject value, String name) {
        if (value.isNull(name)) return null;
        String result = value.optString(name, "");
        return result.isEmpty() ? null : result;
    }
}
