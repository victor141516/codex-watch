package dev.codexwatch.demo;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class OpenAiSubscriptionTranscriptionClient {
    private static final String TRANSCRIPTION_URL = "https://chatgpt.com/backend-api/transcribe";
    private final ChatGptAuthClient auth;

    OpenAiSubscriptionTranscriptionClient(ChatGptAuthClient auth) {
        this.auth = auth;
    }

    String transcribe(byte[] pcm16, int sampleRate) throws Exception {
        byte[] wav = encodeWav(pcm16, sampleRate);
        try {
            return send(auth.credentials(false), wav);
        } catch (Unauthorized ignored) {
            return send(auth.credentials(true), wav);
        }
    }

    private String send(ChatGptAuthClient.Credentials credentials, byte[] wav) throws Exception {
        String boundary = "codex-watch-" + UUID.randomUUID();
        HttpURLConnection connection = (HttpURLConnection) new URL(TRANSCRIPTION_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(60_000);
        connection.setReadTimeout(180_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + credentials.accessToken);
        connection.setRequestProperty("ChatGPT-Account-Id", credentials.accountId);
        connection.setRequestProperty("originator", "codex-watch");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("User-Agent", "codex-watch/0.2.0 (Android)");
        connection.setChunkedStreamingMode(16 * 1024);
        try {
            try (OutputStream output = connection.getOutputStream()) {
                writeUtf8(output, "--" + boundary + "\r\n");
                writeUtf8(output, "Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n");
                writeUtf8(output, "Content-Type: audio/wav\r\n\r\n");
                int position = 0;
                while (position < wav.length) {
                    int count = Math.min(16 * 1024, wav.length - position);
                    output.write(wav, position, count);
                    position += count;
                }
                writeUtf8(output, "\r\n--" + boundary + "--\r\n");
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED) throw new Unauthorized();
            String body = readResponse(connection, status);
            if (status < 200 || status >= 300) {
                throw new IOException("OpenAI rechazó la transcripción (" + status + "): " + compact(body));
            }
            String text = new JSONObject(body).optString("text", "").trim();
            if (text.isEmpty()) throw new IOException("OpenAI devolvió una transcripción vacía");
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] encodeWav(byte[] pcm16, int sampleRate) throws IOException {
        if (pcm16 == null || pcm16.length == 0) throw new IOException("No hay audio para transcribir");
        if (sampleRate <= 0) throw new IOException("Frecuencia de audio inválida");
        int dataLength = pcm16.length & ~1;
        ByteArrayOutputStream output = new ByteArrayOutputStream(44 + dataLength);
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataLength);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) 1);
        header.putInt(sampleRate);
        header.putInt(sampleRate * 2);
        header.putShort((short) 2);
        header.putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataLength);
        output.write(header.array());
        output.write(pcm16, 0, dataLength);
        return output.toByteArray();
    }

    private static void writeUtf8(OutputStream output, String value) throws IOException {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream() : connection.getErrorStream();
        if (stream == null) return "";
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder body = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 1_000_000) throw new IOException("Respuesta de OpenAI demasiado grande");
            }
            return body.toString();
        }
    }

    private static String compact(String value) {
        String compact = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return compact.isEmpty() ? "sin detalles" : compact.substring(0, Math.min(400, compact.length()));
    }

    private static final class Unauthorized extends IOException {
    }
}
