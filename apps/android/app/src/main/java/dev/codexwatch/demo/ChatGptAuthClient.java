package dev.codexwatch.demo;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

final class ChatGptAuthClient {
    static final class Identity {
        final String email;
        final String plan;

        Identity(String email, String plan) {
            this.email = email;
            this.plan = plan;
        }
    }

    static final class Credentials {
        final String accessToken;
        final String accountId;

        Credentials(String accessToken, String accountId) {
            this.accessToken = accessToken;
            this.accountId = accountId;
        }
    }

    static final class LoginSession {
        final ServerSocket server;
        final String redirectUri;
        final String verifier;
        final String state;
        final String authorizationUrl;

        LoginSession(ServerSocket server, String redirectUri, String verifier,
                     String state, String authorizationUrl) {
            this.server = server;
            this.redirectUri = redirectUri;
            this.verifier = verifier;
            this.state = state;
            this.authorizationUrl = authorizationUrl;
        }
    }

    private static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    private static final String ISSUER = "https://auth.openai.com";
    private static final String SCOPES =
        "openid profile email offline_access api.connectors.read api.connectors.invoke";
    private static final int[] CALLBACK_PORTS = {1455, 1457};
    private static final int LOGIN_TIMEOUT_MILLIS = 5 * 60 * 1000;
    private static final int NETWORK_TIMEOUT_MILLIS = 60 * 1000;
    private static final int MAX_CALLBACK_BYTES = 16_384;

    private final Context context;
    private final SecureTokenStore tokenStore;
    private final AtomicReference<ServerSocket> activeLogin = new AtomicReference<>();

    ChatGptAuthClient(Context context, SecureTokenStore tokenStore) {
        this.context = context;
        this.tokenStore = tokenStore;
    }

    boolean isLoggedIn() {
        return tokenStore.load() != null;
    }

    Identity identity() {
        SecureTokenStore.Tokens tokens = tokenStore.load();
        return tokens == null ? null : new Identity(tokens.email, tokens.plan);
    }

    LoginSession prepareLogin() throws Exception {
        cancelPendingLogin();
        ServerSocket server = bindCallback();
        activeLogin.set(server);
        String redirectUri = "http://localhost:" + server.getLocalPort() + "/auth/callback";
        String verifier = randomUrlSafe(32);
        String challenge = base64Url(MessageDigest.getInstance("SHA-256")
            .digest(verifier.getBytes(StandardCharsets.UTF_8)));
        String state = randomUrlSafe(32);
        String authorizationUrl = Uri.parse(ISSUER + "/oauth/authorize").buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("id_token_add_organizations", "true")
            .appendQueryParameter("codex_cli_simplified_flow", "true")
            .appendQueryParameter("state", state)
            .appendQueryParameter("originator", "codex-watch")
            .build().toString();
        return new LoginSession(server, redirectUri, verifier, state, authorizationUrl);
    }

    Identity completeLogin(LoginSession session) throws Exception {
        try {
            session.server.setSoTimeout(LOGIN_TIMEOUT_MILLIS);
            try (Socket socket = session.server.accept()) {
                try {
                    String code = readCallback(socket, session.state);
                    JSONObject tokens = exchangeCode(code, session.redirectUri, session.verifier);
                    Identity identity = saveTokenResponse(tokens, null);
                    respondBrowser(socket, true, "La sesión de OpenAI está conectada.");
                    return identity;
                } catch (Throwable error) {
                    try {
                        respondBrowser(socket, false, "No se pudo completar el inicio de sesión.");
                    } catch (Throwable ignored) {
                    }
                    throw error;
                }
            }
        } finally {
            activeLogin.compareAndSet(session.server, null);
            try {
                session.server.close();
            } catch (IOException ignored) {
            }
        }
    }

    void cancelPendingLogin() {
        ServerSocket server = activeLogin.getAndSet(null);
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
    }

    void logout() {
        cancelPendingLogin();
        tokenStore.clear();
    }

    synchronized Credentials credentials(boolean forceRefresh) throws Exception {
        SecureTokenStore.Tokens current = tokenStore.load();
        if (current == null) throw new IllegalStateException("Inicia sesión en OpenAI primero");
        long now = System.currentTimeMillis() / 1000;
        if (!forceRefresh && current.expiresAt > now + 60) {
            return new Credentials(current.accessToken, current.accountId);
        }

        JSONObject refreshed = postForm(ISSUER + "/oauth/token", new String[][]{
            {"grant_type", "refresh_token"},
            {"refresh_token", current.refreshToken},
            {"client_id", CLIENT_ID}
        });
        saveTokenResponse(refreshed, current);
        SecureTokenStore.Tokens saved = tokenStore.load();
        if (saved == null) throw new IllegalStateException("No se pudo guardar la sesión renovada");
        return new Credentials(saved.accessToken, saved.accountId);
    }

    private JSONObject exchangeCode(String code, String redirectUri, String verifier) throws Exception {
        return postForm(ISSUER + "/oauth/token", new String[][]{
            {"grant_type", "authorization_code"},
            {"code", code},
            {"redirect_uri", redirectUri},
            {"client_id", CLIENT_ID},
            {"code_verifier", verifier}
        });
    }

    private Identity saveTokenResponse(JSONObject response, SecureTokenStore.Tokens previous)
        throws Exception {
        String accessToken = requireString(response, "access_token", "Falta el token de acceso");
        String refreshToken = nonBlank(response.optString("refresh_token"));
        if (refreshToken == null && previous != null) refreshToken = previous.refreshToken;
        if (refreshToken == null) throw new IOException("Falta el token de renovación");
        Claims claims = parseClaims(nonBlank(response.optString("id_token")));
        if (claims == null) claims = parseClaims(accessToken);
        String accountId = claims == null ? null : claims.accountId;
        if (accountId == null && previous != null) accountId = previous.accountId;
        if (accountId == null) throw new IOException("La sesión no incluye el identificador de cuenta");
        String email = claims == null ? null : claims.email;
        String plan = claims == null ? null : claims.plan;
        if (email == null && previous != null) email = previous.email;
        if (plan == null && previous != null) plan = previous.plan;
        tokenStore.save(new SecureTokenStore.Tokens(
            accessToken,
            refreshToken,
            System.currentTimeMillis() / 1000 + response.optLong("expires_in", 3600),
            accountId,
            email,
            plan
        ));
        return new Identity(email, plan);
    }

    private JSONObject postForm(String endpoint, String[][] values) throws Exception {
        StringBuilder encoded = new StringBuilder();
        for (String[] pair : values) {
            if (encoded.length() > 0) encoded.append('&');
            encoded.append(formEncode(pair[0])).append('=').append(formEncode(pair[1]));
        }
        byte[] body = encoded.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(NETWORK_TIMEOUT_MILLIS);
        connection.setReadTimeout(NETWORK_TIMEOUT_MILLIS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        connection.setRequestProperty("Accept", "application/json");
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            String text = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("OpenAI rechazó la autenticación (" + status + "): " + compact(text));
            }
            return new JSONObject(text);
        } finally {
            connection.disconnect();
        }
    }

    private ServerSocket bindCallback() throws IOException {
        for (int port : CALLBACK_PORTS) {
            try {
                ServerSocket server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                return server;
            } catch (IOException ignored) {
            }
        }
        throw new IOException("Los puertos OAuth 1455 y 1457 están ocupados");
    }

    private String readCallback(Socket socket, String expectedState) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] marker = {13, 10, 13, 10};
        int matched = 0;
        while (buffer.size() < MAX_CALLBACK_BYTES) {
            int value = socket.getInputStream().read();
            if (value < 0) break;
            buffer.write(value);
            matched = value == (marker[matched] & 0xff) ? matched + 1 : 0;
            if (matched == marker.length) break;
        }
        if (buffer.size() >= MAX_CALLBACK_BYTES) throw new IOException("Callback OAuth demasiado grande");
        String request = buffer.toString(StandardCharsets.UTF_8.name());
        String firstLine = request.split("\\r?\\n", 2)[0];
        String[] parts = firstLine.split(" ");
        if (parts.length < 2) throw new IOException("Callback OAuth inválido");
        Uri uri = Uri.parse("http://localhost" + parts[1]);
        String oauthError = uri.getQueryParameter("error");
        if (oauthError != null) {
            String description = uri.getQueryParameter("error_description");
            throw new IOException(description == null ? oauthError : description);
        }
        if (!expectedState.equals(uri.getQueryParameter("state"))) {
            throw new IOException("El estado OAuth no coincide");
        }
        String code = nonBlank(uri.getQueryParameter("code"));
        if (code == null) throw new IOException("El callback OAuth no incluye código");
        return code;
    }

    private void respondBrowser(Socket socket, boolean success, String message) throws IOException {
        String title = success ? "OpenAI conectado" : "OpenAI no conectado";
        String status = success ? "200 OK" : "500 Internal Server Error";
        String action = success
            ? "<a href=\"codexwatch://auth-complete\">Volver a Codex Watch</a>"
                + "<script>setTimeout(function(){location.href='codexwatch://auth-complete'},500)</script>"
            : "<p>Vuelve a la aplicación e inténtalo de nuevo.</p>";
        String body = "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width\"><title>" + title + "</title></head>"
            + "<body style=\"font:18px system-ui;background:#090b09;color:#f3f5ef;max-width:36rem;"
            + "margin:15vh auto;padding:2rem\"><h1>" + title + "</h1><p>" + message + "</p>"
            + action + "</body></html>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("HTTP/1.1 " + status + "\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(header);
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static final class Claims {
        final String accountId;
        final String email;
        final String plan;

        Claims(String accountId, String email, String plan) {
            this.accountId = accountId;
            this.email = email;
            this.plan = plan;
        }
    }

    private Claims parseClaims(String token) {
        if (token == null) return null;
        String[] parts = token.split("\\.");
        if (parts.length < 2) return null;
        try {
            JSONObject json = new JSONObject(new String(
                Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING),
                StandardCharsets.UTF_8
            ));
            JSONObject auth = json.optJSONObject("https://api.openai.com/auth");
            String accountId = nonBlank(json.optString("chatgpt_account_id"));
            if (accountId == null && auth != null) accountId = nonBlank(auth.optString("chatgpt_account_id"));
            String email = nonBlank(json.optString("email"));
            String plan = auth == null ? null : nonBlank(auth.optString("chatgpt_plan_type"));
            if (plan != null) plan = plan.substring(0, 1).toUpperCase(Locale.ROOT) + plan.substring(1);
            return new Claims(accountId, email, plan);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws IOException {
        java.io.InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) body.append(line);
            return body.toString();
        }
    }

    private static String requireString(JSONObject value, String name, String message) throws IOException {
        String result = nonBlank(value.optString(name));
        if (result == null) throw new IOException(message);
        return result;
    }

    private static String nonBlank(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    private static String randomUrlSafe(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return base64Url(bytes);
    }

    private static String base64Url(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String formEncode(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
    }

    private static String compact(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.isEmpty() ? "sin detalles" : compact.substring(0, Math.min(300, compact.length()));
    }
}
