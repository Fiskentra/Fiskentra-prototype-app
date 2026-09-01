package com.fiskentra.app.backend;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Email/password Supabase Auth and the private Fiskentra profile row. */
public final class SupabaseAuthManager {
    public static final String AUTH_REDIRECT = "com.fiskentra.app://auth/callback";
    public interface Listener {
        void onResult(boolean success, String message);
    }

    public static final class Session {
        public final String userId;
        public final String email;
        public final String displayName;
        final String accessToken;
        final String refreshToken;
        final long expiresAt;

        Session(String userId, String email, String displayName,
                String accessToken, String refreshToken, long expiresAt) {
            this.userId = value(userId);
            this.email = value(email);
            this.displayName = value(displayName);
            this.accessToken = value(accessToken);
            this.refreshToken = value(refreshToken);
            this.expiresAt = expiresAt;
        }

        Session withDisplayName(String name) {
            return new Session(userId, email, name, accessToken, refreshToken, expiresAt);
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SecureSessionStore store;
    private volatile Session session;

    public SupabaseAuthManager(Context context) {
        store = new SecureSessionStore(context.getApplicationContext());
        session = store.read();
    }

    public Session session() {
        return session;
    }

    public boolean isSignedIn() {
        Session current = session;
        return current != null && !current.accessToken.isEmpty() && !current.refreshToken.isEmpty();
    }

    public void restore(Listener listener) {
        Session current = session;
        if (current == null) {
            listener.onResult(false, "Local mode · sign in is optional");
            return;
        }
        executor.execute(() -> {
            try {
                Session valid = validSession(current);
                HttpResult userResult = request("GET", "/auth/v1/user", null, valid.accessToken);
                if (userResult.status == 401) {
                    valid = refresh(valid);
                    userResult = request("GET", "/auth/v1/user", null, valid.accessToken);
                }
                if (!userResult.ok()) throw new AuthException(messageFor(userResult, "Session expired"));
                valid = mergeUser(valid, new JSONObject(userResult.body));
                session = loadOrCreateProfile(valid);
                store.write(session);
                listener.onResult(true, "Account session restored");
            } catch (AuthException error) {
                listener.onResult(isSignedIn(), error.getMessage());
            } catch (Exception error) {
                // Keep a locally encrypted session during a temporary network failure.
                listener.onResult(true, "Profile available offline · account check pending");
            }
        });
    }

    public void signUp(String email, String password, String displayName, Listener listener) {
        executor.execute(() -> {
            try {
                JSONObject metadata = new JSONObject().put("display_name", displayName.trim());
                JSONObject payload = new JSONObject()
                        .put("email", email.trim().toLowerCase(Locale.ROOT))
                        .put("password", password)
                        .put("data", metadata);
                HttpResult result = request("POST", authPath("/auth/v1/signup"), payload, null);
                if (!result.ok()) throw new AuthException(messageFor(result, "Could not create account"));
                JSONObject json = new JSONObject(result.body);
                if (json.optString("access_token", "").isEmpty()) {
                    listener.onResult(true, "Account created · confirm the email, then sign in");
                    return;
                }
                Session created = sessionFrom(json, displayName);
                session = loadOrCreateProfile(created);
                store.write(session);
                listener.onResult(true, "Account created and signed in");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Could not create account · check internet and try again");
            }
        });
    }

    public void signIn(String email, String password, Listener listener) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("email", email.trim().toLowerCase(Locale.ROOT))
                        .put("password", password);
                HttpResult result = request("POST", "/auth/v1/token?grant_type=password", payload, null);
                if (!result.ok()) throw new AuthException(messageFor(result, "Could not sign in"));
                Session signedIn = sessionFrom(new JSONObject(result.body), "");
                session = loadOrCreateProfile(signedIn);
                store.write(session);
                listener.onResult(true, "Signed in to Fiskentra");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Could not sign in · check internet and try again");
            }
        });
    }

    public void requestPasswordReset(String email, Listener listener) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("email", email.trim().toLowerCase(Locale.ROOT));
                HttpResult result = request("POST", authPath("/auth/v1/recover"), payload, null);
                if (!result.ok()) throw new AuthException(messageFor(result, "Could not send reset email"));
                listener.onResult(true, "Password reset email sent · check your inbox");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Could not send reset email · check internet and try again");
            }
        });
    }

    public void resendConfirmation(String email, Listener listener) {
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject()
                        .put("type", "signup")
                        .put("email", email.trim().toLowerCase(Locale.ROOT));
                HttpResult result = request("POST", authPath("/auth/v1/resend"), payload, null);
                if (!result.ok()) throw new AuthException(messageFor(result, "Could not resend confirmation"));
                listener.onResult(true, "Confirmation email sent · check your inbox");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Could not resend confirmation · try again online");
            }
        });
    }

    public void completeAuthRedirect(Uri uri, Listener listener) {
        executor.execute(() -> {
            try {
                String error = linkValue(uri, "error_description");
                if (error.isEmpty()) error = linkValue(uri, "error");
                if (!error.isEmpty()) throw new AuthException("This email link is invalid or expired");
                String accessToken = linkValue(uri, "access_token");
                String refreshToken = linkValue(uri, "refresh_token");
                boolean recovery = "recovery".equalsIgnoreCase(linkValue(uri, "type"));
                if (accessToken.isEmpty() || refreshToken.isEmpty()) {
                    throw new AuthException("This email link is incomplete · request a new one");
                }
                long expiresIn = 3600L;
                try { expiresIn = Math.max(60L, Long.parseLong(linkValue(uri, "expires_in"))); }
                catch (Exception ignored) { }
                HttpResult userResult = request("GET", "/auth/v1/user", null, accessToken);
                if (!userResult.ok()) throw new AuthException(messageFor(userResult, "Could not verify email link"));
                JSONObject user = new JSONObject(userResult.body);
                Session linked = new Session(
                        user.optString("id", ""),
                        user.optString("email", ""),
                        metadataName(user),
                        accessToken,
                        refreshToken,
                        System.currentTimeMillis() + expiresIn * 1000L);
                session = loadOrCreateProfile(linked);
                store.write(session);
                listener.onResult(true, recovery
                        ? "Reset link verified · choose a new password"
                        : "Email verified · signed in securely");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Could not open this email link · try again");
            }
        });
    }

    public void updatePassword(String password, Listener listener) {
        executor.execute(() -> {
            try {
                Session current = session;
                if (current == null) throw new AuthException("Open the newest reset email first");
                current = validSession(current);
                HttpResult result = request("PUT", "/auth/v1/user",
                        new JSONObject().put("password", password), current.accessToken);
                if (!result.ok()) throw new AuthException(messageFor(result, "Password could not be changed"));
                listener.onResult(true, "Password changed successfully");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Password could not be changed · try again online");
            }
        });
    }

    public void updateDisplayName(String displayName, Listener listener) {
        executor.execute(() -> {
            try {
                Session current = session;
                if (current == null) throw new AuthException("Sign in before editing the profile");
                current = validSession(current);
                Session updated = upsertProfile(current.withDisplayName(displayName.trim()));
                session = updated;
                store.write(updated);
                listener.onResult(true, "Profile updated");
            } catch (AuthException error) {
                listener.onResult(false, error.getMessage());
            } catch (Exception error) {
                listener.onResult(false, "Profile update failed · try again online");
            }
        });
    }

    public void signOut(Listener listener) {
        executor.execute(() -> {
            Session current = session;
            boolean serverRevoked = false;
            try {
                if (current != null) {
                    HttpResult result = request("POST", "/auth/v1/logout", new JSONObject(), current.accessToken);
                    serverRevoked = result.ok();
                }
            } catch (Exception ignored) {
                // Local sign-out must still work offline.
            } finally {
                session = null;
                store.clear();
            }
            listener.onResult(true, serverRevoked
                    ? "Signed out"
                    : "Signed out on this phone · remote session will expire");
        });
    }

    public void close() {
        executor.shutdownNow();
    }

    private Session validSession(Session current) throws Exception {
        if (current.expiresAt > System.currentTimeMillis() + 60_000L) return current;
        return refresh(current);
    }

    private Session refresh(Session current) throws Exception {
        JSONObject payload = new JSONObject().put("refresh_token", current.refreshToken);
        HttpResult result = request("POST", "/auth/v1/token?grant_type=refresh_token", payload, null);
        if (!result.ok()) {
            if (result.status == 400 || result.status == 401) {
                session = null;
                store.clear();
            }
            throw new AuthException(messageFor(result, "Session expired · sign in again"));
        }
        Session refreshed = sessionFrom(new JSONObject(result.body), current.displayName);
        session = refreshed;
        store.write(refreshed);
        return refreshed;
    }

    private Session loadOrCreateProfile(Session current) throws Exception {
        String path = "/rest/v1/profiles?select=display_name&id=eq." + current.userId + "&limit=1";
        HttpResult result = request("GET", path, null, current.accessToken);
        if (result.ok()) {
            JSONArray rows = new JSONArray(result.body);
            if (rows.length() > 0) {
                String name = rows.getJSONObject(0).optString("display_name", "").trim();
                if (!name.isEmpty()) return current.withDisplayName(name);
            }
        } else if (result.status == 401) {
            throw new AuthException("Session expired · sign in again");
        }
        String fallback = current.displayName.trim();
        if (fallback.isEmpty()) fallback = defaultName(current.email);
        return upsertProfile(current.withDisplayName(fallback));
    }

    private Session upsertProfile(Session current) throws Exception {
        JSONObject payload = new JSONObject()
                .put("id", current.userId)
                .put("display_name", current.displayName)
                .put("updated_at", isoNow());
        HttpResult result = request("POST", "/rest/v1/profiles?on_conflict=id", payload, current.accessToken,
                "resolution=merge-duplicates,return=minimal");
        if (!result.ok()) throw new AuthException(messageFor(result, "Profile could not be saved"));
        return current;
    }

    private Session sessionFrom(JSONObject json, String fallbackName) throws Exception {
        JSONObject user = json.optJSONObject("user");
        if (user == null) throw new AuthException("Supabase did not return a user session");
        String accessToken = json.optString("access_token", "");
        String refreshToken = json.optString("refresh_token", "");
        if (accessToken.isEmpty() || refreshToken.isEmpty()) {
            throw new AuthException("Email confirmation is required before sign in");
        }
        long expiresIn = Math.max(60L, json.optLong("expires_in", 3600L));
        String name = metadataName(user);
        if (name.isEmpty()) name = fallbackName;
        return new Session(
                user.optString("id", ""),
                user.optString("email", ""),
                name,
                accessToken,
                refreshToken,
                System.currentTimeMillis() + expiresIn * 1000L);
    }

    private Session mergeUser(Session current, JSONObject user) {
        String name = metadataName(user);
        return new Session(
                user.optString("id", current.userId),
                user.optString("email", current.email),
                name.isEmpty() ? current.displayName : name,
                current.accessToken,
                current.refreshToken,
                current.expiresAt);
    }

    private static String metadataName(JSONObject user) {
        JSONObject metadata = user.optJSONObject("user_metadata");
        return metadata == null ? "" : metadata.optString("display_name", "").trim();
    }

    private HttpResult request(String method, String path, JSONObject body, String bearer) throws Exception {
        return request(method, path, body, bearer, "");
    }

    private static String authPath(String endpoint) throws Exception {
        return endpoint + "?redirect_to=" + URLEncoder.encode(AUTH_REDIRECT, "UTF-8");
    }

    private static String linkValue(Uri uri, String key) {
        if (uri == null) return "";
        String query = uri.getQueryParameter(key);
        if (query != null && !query.isEmpty()) return query;
        String fragment = uri.getFragment();
        if (fragment == null || fragment.isEmpty()) return "";
        for (String part : fragment.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(Uri.decode(pair[0]))) return Uri.decode(pair[1]);
        }
        return "";
    }

    private HttpResult request(String method, String path, JSONObject body, String bearer, String prefer)
            throws Exception {
        if (!SupabaseConfig.isConfigured()) throw new AuthException("Supabase is not configured");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(SupabaseConfig.url() + path).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("apikey", SupabaseConfig.publishableKey());
            connection.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearer);
            }
            if (!prefer.isEmpty()) connection.setRequestProperty("Prefer", prefer);
            if (body != null && !("GET".equals(method))) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            return new HttpResult(status, read(input));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String messageFor(HttpResult result, String fallback) {
        try {
            JSONObject json = new JSONObject(result.body);
            String code = json.optString("error_code", json.optString("code", ""));
            if ("invalid_credentials".equals(code)) return "Incorrect email or password";
            if ("email_not_confirmed".equals(code)) return "Confirm your email before signing in";
            if ("user_already_exists".equals(code) || "email_exists".equals(code)) {
                return "An account with this email already exists";
            }
            if ("signup_disabled".equals(code)) return "New account registration is disabled";
            if ("weak_password".equals(code)) return "Use a stronger password with at least 8 characters";
            if (code.contains("rate_limit") || result.status == 429) return "Too many attempts · try again later";
        } catch (Exception ignored) {
            // Never display arbitrary server bodies or tokens.
        }
        if (result.status == 401) return "Session expired · sign in again";
        if (result.status >= 500) return "Fiskentra service is temporarily unavailable · try again";
        return fallback + " · try again";
    }

    private static String defaultName(String email) {
        int at = email.indexOf('@');
        String value = at > 0 ? email.substring(0, at) : "Angler";
        value = value.trim();
        return value.isEmpty() ? "Angler" : value.substring(0, Math.min(50, value.length()));
    }

    private static String isoNow() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static String read(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = stream.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = value(body);
        }

        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static final class AuthException extends Exception {
        AuthException(String message) {
            super(message);
        }
    }

    /** Stores only the session JSON, encrypted by an Android Keystore AES-GCM key. */
    private static final class SecureSessionStore {
        private static final String PREFS = "fiskentra_auth_secure";
        private static final String VALUE = "encrypted_session";
        private static final String KEY_ALIAS = "fiskentra_auth_session_v1";
        private final SharedPreferences prefs;

        SecureSessionStore(Context context) {
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        void write(Session session) {
            if (session == null) {
                clear();
                return;
            }
            try {
                JSONObject json = new JSONObject()
                        .put("user_id", session.userId)
                        .put("email", session.email)
                        .put("display_name", session.displayName)
                        .put("access_token", session.accessToken)
                        .put("refresh_token", session.refreshToken)
                        .put("expires_at", session.expiresAt);
                prefs.edit().putString(VALUE, encrypt(json.toString())).apply();
            } catch (Exception error) {
                clear();
            }
        }

        Session read() {
            String encrypted = prefs.getString(VALUE, "");
            if (encrypted == null || encrypted.isEmpty()) return null;
            try {
                JSONObject json = new JSONObject(decrypt(encrypted));
                return new Session(
                        json.optString("user_id", ""),
                        json.optString("email", ""),
                        json.optString("display_name", ""),
                        json.optString("access_token", ""),
                        json.optString("refresh_token", ""),
                        json.optLong("expires_at", 0L));
            } catch (Exception error) {
                clear();
                return null;
            }
        }

        void clear() {
            prefs.edit().remove(VALUE).apply();
        }

        private String encrypt(String plainText) throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(cipherText, Base64.NO_WRAP);
        }

        private String decrypt(String encrypted) throws Exception {
            String[] parts = encrypted.split("\\.", 2);
            if (parts.length != 2) throw new IllegalArgumentException("Invalid encrypted session");
            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        }

        private SecretKey key() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) {
                return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
            }
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            return generator.generateKey();
        }
    }
}
