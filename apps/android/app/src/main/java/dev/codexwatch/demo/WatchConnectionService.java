package dev.codexwatch.demo;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("MissingPermission")
public final class WatchConnectionService extends Service {
    static final String PREFS_NAME = "dev.codexwatch.demo_preferences";
    static final String PREF_SERVER_URL = "server_url";
    static final String PREF_WATCH_ADDRESS = "watch_address";
    static final String PREF_AUTO_CONNECT = "auto_connect_enabled";
    static final String ACTION_START = "dev.codexwatch.demo.START_WATCH_SERVICE";
    static final String ACTION_RECONNECT = "dev.codexwatch.demo.RECONNECT_WATCH";

    private static final String DEVICE_NAME = "Codex Watch";
    private static final UUID SERVICE_UUID = UUID.fromString("7e57c001-7f76-4f1a-9b6d-1c2f8a10c001");
    private static final UUID RX_UUID = UUID.fromString("7e57c002-7f76-4f1a-9b6d-1c2f8a10c001");
    private static final UUID TX_UUID = UUID.fromString("7e57c003-7f76-4f1a-9b6d-1c2f8a10c001");
    private static final UUID CCC_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String CHANNEL_ID = "codex_watch_connection";
    private static final int NOTIFICATION_ID = 1801;
    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long RECONNECT_DELAY_MS = 3_000L;

    public interface Listener {
        void onStateChanged(State state);
    }

    public static final class State {
        public final String status;
        public final boolean error;
        public final String detail;
        public final String transcript;
        public final boolean connected;
        public final boolean audioAvailable;
        public final boolean transcriptionInProgress;

        State(String status, boolean error, String detail, String transcript, boolean connected,
              boolean audioAvailable, boolean transcriptionInProgress) {
            this.status = status;
            this.error = error;
            this.detail = detail;
            this.transcript = transcript;
            this.connected = connected;
            this.audioAvailable = audioAvailable;
            this.transcriptionInProgress = transcriptionInProgress;
        }
    }

    public final class LocalBinder extends Binder {
        public WatchConnectionService getService() {
            return WatchConnectionService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private final ByteArrayOutputStream notificationBuffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    private SharedPreferences preferences;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;
    private ChatGptAuthClient authClient;
    private OpenAiSubscriptionTranscriptionClient transcriptionClient;
    private Listener listener;
    private boolean scanning;
    private boolean connecting;
    private boolean connected;
    private boolean writing;
    private boolean notificationsReady;
    private boolean serverLoadInProgress;
    private int negotiatedMtu = 23;
    private int payloadSize;
    private JSONObject pendingDocument;
    private String pendingDocumentSuccessLabel;
    private String pendingSuccessLabel = "Documento enviado al reloj";
    private JSONObject serverDocument;
    private JSONObject selectedThreadDocument;
    private String serverBaseUrl;
    private String selectedThreadId;
    private String audioTargetThreadId;
    private byte[] lastAudio;
    private int lastAudioSampleRate = 16000;
    private int audioPacketBytes = 234;
    private int lastAudioSequence = -1;
    private int audioReceivedPackets;
    private int audioMissingPackets;
    private boolean audioReceiving;
    private boolean transcriptionInProgress;
    private String statusText = "Iniciando servicio...";
    private boolean statusError;
    private String detailText = "Reloj: desconectado";
    private String transcriptText = "La transcripción aparecerá aquí.";

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        authClient = new ChatGptAuthClient(getApplicationContext(), new SecureTokenStore(getApplicationContext()));
        transcriptionClient = new OpenAiSubscriptionTranscriptionClient(authClient);
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        createNotificationChannel();
        promoteToForeground();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        preferences.edit().putBoolean(PREF_AUTO_CONNECT, true).apply();
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_RECONNECT.equals(action)) disconnectGatt();
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.post(this::ensureConnection);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        stopScan();
        disconnectGatt();
        networkExecutor.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        publishState();
    }

    public void clearListener(Listener listener) {
        if (this.listener == listener) this.listener = null;
    }

    public State getStateSnapshot() {
        return new State(statusText, statusError, detailText, transcriptText, connected,
            lastAudio != null && lastAudio.length > 0, transcriptionInProgress);
    }

    public void reconnectNow() {
        disconnectGatt();
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.post(this::ensureConnection);
    }

    public void reloadServer() {
        if (connected && notificationsReady) loadFromServer();
    }

    public void playLastAudio() {
        byte[] audio = lastAudio;
        if (audio == null || audio.length == 0) return;
        updateStatus("Reproduciendo audio...", false);
        networkExecutor.execute(() -> {
            AudioTrack track = null;
            try {
                int minBuffer = AudioTrack.getMinBufferSize(lastAudioSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(AudioManager.STREAM_MUSIC, lastAudioSampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBuffer, 4096), AudioTrack.MODE_STREAM);
                track.play();
                track.write(audio, 0, audio.length);
                track.stop();
                updateStatus("Audio listo", false);
            } catch (Exception error) {
                updateStatus("No se pudo reproducir el audio: " + friendlyMessage(error), true);
            } finally {
                if (track != null) track.release();
            }
        });
    }

    public void exportLastAudio() {
        byte[] audio = lastAudio;
        if (audio == null || audio.length == 0) return;
        updateStatus("Guardando WAV...", false);
        networkExecutor.execute(() -> {
            try {
                String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String fileName = "codex-watch-" + timestamp + "-lost" + audioMissingPackets + ".wav";
                String location = saveWav(fileName, audio, lastAudioSampleRate);
                updateStatus("WAV guardado en " + location, false);
            } catch (Exception error) {
                updateStatus("No se pudo guardar el WAV: " + friendlyMessage(error), true);
            }
        });
    }

    public void transcribeLastAudio() {
        byte[] audio = lastAudio;
        if (audio != null && audio.length > 0) transcribeAudio(audio, lastAudioSampleRate);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Conexión con Codex Watch", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Mantiene la conexión Bluetooth con el reloj");
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle(connected ? "Codex Watch conectado" : "Codex Watch activo")
            .setContentText(statusText)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
    }

    private void promoteToForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void refreshNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensureConnection() {
        if (connected || connecting || scanning) return;
        if (bluetoothAdapter == null) {
            updateStatus("Bluetooth LE no está disponible", true);
            return;
        }
        if (!hasBluetoothPermissions()) {
            updateStatus("Abre la app una vez para conceder los permisos Bluetooth", true);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            updateStatus("Activa Bluetooth para conectar el reloj", true);
            mainHandler.postDelayed(reconnectRunnable, 10_000L);
            return;
        }

        String address = preferences.getString(PREF_WATCH_ADDRESS, null);
        if (address != null && !address.isBlank()) {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                connectDevice(device, true, null);
                return;
            } catch (IllegalArgumentException ignored) {
                preferences.edit().remove(PREF_WATCH_ADDRESS).apply();
            }
        }
        startScan();
    }

    private void startScan() {
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            updateStatus("No se pudo iniciar la búsqueda BLE", true);
            scheduleReconnect();
            return;
        }
        scanning = true;
        updateStatus("Buscando Codex Watch...", false);
        ScanFilter filter = new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build();
        ScanSettings settings = new ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void stopScan() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (scanning && scanner != null && hasBluetoothPermissions()) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
            }
        }
        scanning = false;
    }

    private final Runnable scanTimeoutRunnable = () -> {
        if (!scanning) return;
        stopScan();
        updateStatus("Reloj fuera de alcance; esperando...", false);
        scheduleReconnect();
    };

    private final Runnable reconnectRunnable = this::ensureConnection;

    private void scheduleReconnect() {
        mainHandler.removeCallbacks(reconnectRunnable);
        mainHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = result.getScanRecord() == null ? null : result.getScanRecord().getDeviceName();
            if (!DEVICE_NAME.equals(name)) return;
            stopScan();
            BluetoothDevice device = result.getDevice();
            preferences.edit().putString(PREF_WATCH_ADDRESS, device.getAddress()).apply();
            connectDevice(device, false, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false;
            updateStatus("Fallo de búsqueda BLE: " + errorCode, true);
            scheduleReconnect();
        }
    };

    private void connectDevice(BluetoothDevice device, boolean autoConnect, Integer rssi) {
        if (connecting || connected) return;
        connecting = true;
        String detail = "Dispositivo: " + device.getAddress();
        if (rssi != null) detail += "\nRSSI: " + rssi + " dBm";
        detailText = detail;
        updateStatus(autoConnect ? "Esperando al reloj asociado..." : "Conectando...", false);
        gatt = device.connectGatt(this, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt currentGatt, int status, int newState) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = currentGatt;
                connecting = false;
                connected = true;
                currentGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
                boolean mtuRequested = currentGatt.requestMtu(247);
                if (!mtuRequested) currentGatt.discoverServices();
                updateStatus("Conectado; descubriendo servicio...", false);
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                if (gatt == currentGatt) gatt = null;
                currentGatt.close();
                connecting = false;
                connected = false;
                rxCharacteristic = null;
                txCharacteristic = null;
                notificationsReady = false;
                writing = false;
                writeQueue.clear();
                synchronized (notificationBuffer) {
                    notificationBuffer.reset();
                }
                updateStatus("Reloj desconectado; reconectando...", false);
                scheduleReconnect();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt currentGatt, int mtu, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) negotiatedMtu = mtu;
            currentGatt.discoverServices();
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt currentGatt, int status) {
            BluetoothGattService service = currentGatt.getService(SERVICE_UUID);
            rxCharacteristic = service == null ? null : service.getCharacteristic(RX_UUID);
            txCharacteristic = service == null ? null : service.getCharacteristic(TX_UUID);
            if (txCharacteristic == null) txCharacteristic = rxCharacteristic;
            notificationsReady = false;
            if (status != BluetoothGatt.GATT_SUCCESS || rxCharacteristic == null) {
                updateStatus("El servicio Codex Watch no aparece", true);
                disconnectGatt();
                scheduleReconnect();
                return;
            }
            detailText += "\nMTU: " + negotiatedMtu;
            updateStatus("Reloj preparado", false);
            if (txCharacteristic == null) {
                watchReadyAndLoadServer();
                return;
            }
            currentGatt.setCharacteristicNotification(txCharacteristic, true);
            BluetoothGattDescriptor descriptor = txCharacteristic.getDescriptor(CCC_UUID);
            if (descriptor == null) {
                watchReadyAndLoadServer();
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                int result = currentGatt.writeDescriptor(
                    descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (result != android.bluetooth.BluetoothStatusCodes.SUCCESS) watchReadyAndLoadServer();
            } else {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                if (!currentGatt.writeDescriptor(descriptor)) watchReadyAndLoadServer();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt currentGatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) watchReadyAndLoadServer();
            else updateStatus("No se pudieron activar las notificaciones BLE: " + status, true);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt currentGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                writing = false;
                writeQueue.clear();
                updateStatus("Error enviando por BLE: " + status, true);
                return;
            }
            writeNextChunk();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt,
                                             BluetoothGattCharacteristic characteristic, byte[] value) {
            if (characteristic == txCharacteristic) consumeWatchNotification(value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt currentGatt,
                                             BluetoothGattCharacteristic characteristic) {
            if (characteristic == txCharacteristic) consumeWatchNotification(characteristic.getValue());
        }
    };

    private void disconnectGatt() {
        stopScan();
        BluetoothGatt current = gatt;
        gatt = null;
        connecting = false;
        connected = false;
        notificationsReady = false;
        rxCharacteristic = null;
        txCharacteristic = null;
        if (current != null && hasBluetoothPermissions()) {
            try {
                current.disconnect();
            } catch (RuntimeException ignored) {
            }
            current.close();
        }
    }

    private void watchReadyAndLoadServer() {
        notificationsReady = true;
        updateStatus("Reloj preparado; cargando Codex...", false);
        loadFromServer();
    }

    private void loadFromServer() {
        if (serverLoadInProgress) return;
        String baseUrl = normalizeServerUrl(preferences.getString(PREF_SERVER_URL, "http://192.168.1.100:8787"));
        if (baseUrl == null) {
            updateStatus("Configura una dirección válida del servidor", true);
            return;
        }
        serverBaseUrl = baseUrl;
        serverLoadInProgress = true;
        updateStatus("Cargando tareas de Codex...", false);
        networkExecutor.execute(() -> {
            try {
                JSONObject document = httpGet(baseUrl + "/api/watch");
                JSONArray threads = document.optJSONArray("threads");
                if (threads == null) throw new IOException("La respuesta no contiene threads");
                serverDocument = document;
                detailText = "Servidor: " + baseUrl + "\nTareas recibidas: " + threads.length()
                    + "\nMTU: " + negotiatedMtu;
                mainHandler.post(() -> {
                    serverLoadInProgress = false;
                    if (connected && rxCharacteristic != null && !writing) {
                        sendDocument(document, "Codex del servidor enviado al reloj");
                    } else {
                        updateStatus("Codex cargado; esperando al reloj", false);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    serverLoadInProgress = false;
                    updateStatus("No se pudo conectar al servidor: " + friendlyMessage(error), true);
                });
            }
        });
    }

    private String normalizeServerUrl(String value) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty()) return null;
        if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) {
            candidate = "http://" + candidate;
        }
        while (candidate.endsWith("/")) candidate = candidate.substring(0, candidate.length() - 1);
        try {
            URL parsed = new URL(candidate);
            if (!"http".equals(parsed.getProtocol()) && !"https".equals(parsed.getProtocol())) return null;
            return candidate;
        } catch (Exception error) {
            return null;
        }
    }

    private JSONObject httpGet(String endpoint) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/json");
        return readJsonResponse(connection);
    }

    private JSONObject httpPost(String endpoint, JSONObject payload, int readTimeoutMillis) throws Exception {
        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(readTimeoutMillis);
        connection.setDoOutput(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body);
        }
        return readJsonResponse(connection);
    }

    private JSONObject readJsonResponse(HttpURLConnection connection) throws Exception {
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IOException("HTTP " + status);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                    if (body.length() > 2_000_000) throw new IOException("Respuesta demasiado grande");
                }
            }
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + body);
            return new JSONObject(body.toString());
        } finally {
            connection.disconnect();
        }
    }

    private void consumeWatchNotification(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        JSONObject message = null;
        synchronized (notificationBuffer) {
            notificationBuffer.write(chunk, 0, chunk.length);
            byte[] frame = notificationBuffer.toByteArray();
            if (frame.length < 4) return;
            int expected = ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (expected <= 0 || expected > 64 * 1024) {
                notificationBuffer.reset();
                return;
            }
            if (frame.length < expected + 4) return;
            notificationBuffer.reset();
            byte[] payload = Arrays.copyOfRange(frame, 4, expected + 4);
            if (payload.length > 0 && (payload[0] & 0xff) == 0xa0) {
                handleAudioChunk(Arrays.copyOfRange(payload, 1, payload.length));
                return;
            }
            try {
                message = new JSONObject(new String(payload, StandardCharsets.UTF_8));
            } catch (JSONException ignored) {
                return;
            }
        }
        String type = message.optString("type");
        if ("thread_request".equals(type)) fetchRequestedThread(message.optString("threadId", ""));
        else if ("action_response".equals(type)) handleActionResponse(message);
        else handleAudioControl(message);
    }

    private void handleActionResponse(JSONObject message) {
        if (!"close_codex_desktop".equals(message.optString("action", ""))) return;
        if (!message.optBoolean("accepted", false)) {
            updateStatus("Cierre de Codex cancelado desde el reloj", false);
            return;
        }
        requestDesktopClose();
    }

    private void requestDesktopClose() {
        String baseUrl = serverBaseUrl;
        if (baseUrl == null) {
            sendDesktopCloseResult(false, "No hay servidor de Codex configurado.");
            return;
        }
        updateStatus("Cerrando Codex Desktop...", false);
        networkExecutor.execute(() -> {
            try {
                JSONObject request = new JSONObject()
                    .put("action", "close_codex_desktop").put("confirmed", true);
                JSONObject result = httpPost(baseUrl + "/api/desktop/close", request, 15_000);
                boolean closed = result.optBoolean("closed", false);
                sendDesktopCloseResult(closed, result.optString("message",
                    closed ? "Codex Desktop se ha cerrado." : "No se pudo cerrar Codex Desktop."));
            } catch (Throwable error) {
                sendDesktopCloseResult(false, "No se pudo cerrar Codex Desktop: " + friendlyMessage(error));
            }
        });
    }

    private void sendDesktopCloseResult(boolean success, String message) {
        try {
            JSONObject document = new JSONObject().put("version", 1)
                .put("actionResult", new JSONObject()
                    .put("type", "close_codex_desktop").put("success", success).put("message", message));
            mainHandler.post(() -> {
                updateStatus(message, !success);
                queueOrSendDocument(document,
                    success ? "Codex Desktop cerrado" : "Falló el cierre de Codex Desktop");
            });
        } catch (JSONException error) {
            updateStatus("No se pudo preparar el resultado del cierre", true);
        }
    }

    private void handleAudioChunk(byte[] chunk) {
        if (!audioReceiving || chunk.length < 3) return;
        int sequence = (chunk[0] & 0xff) | ((chunk[1] & 0xff) << 8);
        int pcmLength = chunk.length - 2;
        synchronized (audioBuffer) {
            if (lastAudioSequence >= 0) {
                int expected = (lastAudioSequence + 1) & 0xffff;
                int missing = (sequence - expected + 0x10000) & 0xffff;
                if (missing > 0 && missing < 512) {
                    audioBuffer.write(new byte[missing * audioPacketBytes], 0, missing * audioPacketBytes);
                    audioMissingPackets += missing;
                }
            }
            audioBuffer.write(chunk, 2, pcmLength);
        }
        audioReceivedPackets++;
        lastAudioSequence = sequence;
    }

    private void handleAudioControl(JSONObject message) {
        String type = message.optString("type", "");
        if ("audio_start".equals(type)) {
            synchronized (audioBuffer) {
                audioBuffer.reset();
            }
            lastAudioSampleRate = message.optInt("sampleRate", 16000);
            audioPacketBytes = Math.max(2, message.optInt("packetBytes", 234));
            lastAudioSequence = -1;
            audioReceivedPackets = 0;
            audioMissingPackets = 0;
            audioReceiving = true;
            audioTargetThreadId = selectedThreadId;
            lastAudio = null;
            transcriptText = "Grabando...";
            updateStatus("Recibiendo audio del reloj...", false);
        } else if ("audio_end".equals(type)) {
            synchronized (audioBuffer) {
                lastAudio = audioBuffer.toByteArray();
            }
            audioReceiving = false;
            detailText += "\nAudio PCM: " + lastAudio.length + " bytes"
                + "\nPaquetes BLE: " + audioReceivedPackets + " recibidos, "
                + audioMissingPackets + " perdidos";
            updateStatus("Audio recibido: " + formatDuration(lastAudio.length, lastAudioSampleRate), false);
            if (isLoggedInSafe()) transcribeAudio(lastAudio, lastAudioSampleRate);
            else {
                transcriptText = "Audio listo. Inicia sesión con OpenAI para transcribirlo.";
                publishState();
            }
        } else if ("audio_error".equals(type)) {
            audioReceiving = false;
            updateStatus("Error de audio: " + message.optString("message", "desconocido"), true);
        }
    }

    private String formatDuration(int bytes, int sampleRate) {
        int seconds = sampleRate <= 0 ? 0 : bytes / (sampleRate * 2);
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private boolean isLoggedInSafe() {
        try {
            return authClient.isLoggedIn();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void transcribeAudio(byte[] audio, int sampleRate) {
        if (transcriptionInProgress) return;
        if (!isLoggedInSafe()) {
            updateStatus("Inicia sesión con OpenAI para transcribir", true);
            return;
        }
        transcriptionInProgress = true;
        transcriptText = "Transcribiendo con OpenAI...";
        updateStatus("Enviando audio a OpenAI...", false);
        networkExecutor.execute(() -> {
            String completedTranscript = null;
            try {
                String transcript = transcriptionClient.transcribe(audio, sampleRate);
                completedTranscript = transcript;
                transcriptText = transcript;
                String threadId = audioTargetThreadId;
                String baseUrl = serverBaseUrl;
                if (threadId == null || baseUrl == null) {
                    updateStatus("Transcripción lista; abre una tarea antes de grabar para enviarla", true);
                    return;
                }
                updateStatus("Codex está trabajando...", false);
                JSONObject pending = pendingConversationDocument(selectedThreadDocument, transcript);
                if (pending != null) mainHandler.post(() -> queueOrSendDocument(
                    pending, "Mensaje enviado; Codex está trabajando"));

                String encodedId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
                JSONObject request = new JSONObject().put("text", transcript)
                    .put("clientMessageId", UUID.randomUUID().toString());
                JSONObject result = httpPost(
                    baseUrl + "/api/watch/threads/" + encodedId + "/messages", request, 30 * 60 * 1000);
                JSONArray threads = result.optJSONArray("threads");
                if (threads == null || threads.length() == 0) {
                    throw new IOException("El servidor no devolvió la conversación actualizada");
                }
                String turnStatus = result.optString("turnStatus", "unknown");
                selectedThreadDocument = result;
                serverDocument = result;
                mainHandler.post(() -> queueOrSendDocument(result, "Respuesta de Codex recibida"));
                if (!"completed".equals(turnStatus)) {
                    updateStatus("El turno de Codex terminó como " + turnStatus, true);
                }
            } catch (Throwable error) {
                if (completedTranscript == null) {
                    transcriptText = "No se pudo transcribir: " + friendlyMessage(error);
                    updateStatus("Error de transcripción", true);
                } else {
                    transcriptText = completedTranscript;
                    updateStatus("Transcrito, pero no se pudo enviar a Codex: " + friendlyMessage(error), true);
                }
            } finally {
                transcriptionInProgress = false;
                publishState();
            }
        });
    }

    private JSONObject pendingConversationDocument(JSONObject source, String transcript) {
        if (source == null) return null;
        try {
            JSONObject copy = new JSONObject(source.toString());
            JSONArray threads = copy.optJSONArray("threads");
            JSONObject thread = threads == null ? null : threads.optJSONObject(0);
            if (thread == null) return null;
            JSONArray messages = thread.optJSONArray("messages");
            if (messages == null) {
                messages = new JSONArray();
                thread.put("messages", messages);
            }
            messages.put(new JSONObject().put("role", "user").put("text", transcript));
            messages.put(new JSONObject().put("role", "assistant").put("text", "Trabajando…"));
            while (messages.length() > 12) messages.remove(0);
            return copy;
        } catch (JSONException error) {
            return null;
        }
    }

    private void fetchRequestedThread(String threadId) {
        if (threadId == null || threadId.isEmpty()) return;
        selectedThreadId = threadId;
        String baseUrl = serverBaseUrl;
        if (baseUrl == null) {
            updateStatus("No hay servidor de Codex configurado", true);
            return;
        }
        updateStatus("Cargando conversación...", false);
        networkExecutor.execute(() -> {
            try {
                String encodedId = URLEncoder.encode(threadId, StandardCharsets.UTF_8.name());
                JSONObject document = httpGet(baseUrl + "/api/watch/threads/" + encodedId);
                boolean actionRequired = document.optJSONObject("actionRequired") != null;
                if (actionRequired) {
                    selectedThreadId = null;
                    selectedThreadDocument = null;
                } else {
                    serverDocument = document;
                    selectedThreadDocument = document;
                }
                mainHandler.post(() -> queueOrSendDocument(document,
                    actionRequired ? "Codex Desktop requiere confirmación" : "Conversación enviada al reloj"));
            } catch (Exception error) {
                updateStatus("No se pudo cargar la conversación: " + friendlyMessage(error), true);
            }
        });
    }

    private void queueOrSendDocument(JSONObject document, String successLabel) {
        if (connected && rxCharacteristic != null && !writing && notificationsReady) {
            sendDocument(document, successLabel);
        } else {
            pendingDocument = document;
            pendingDocumentSuccessLabel = successLabel;
        }
    }

    private void sendDocument(JSONObject document, String successLabel) {
        if (gatt == null || rxCharacteristic == null || writing) return;
        if (!notificationsReady) {
            pendingDocument = document;
            pendingDocumentSuccessLabel = successLabel;
            updateStatus("Preparando notificaciones BLE...", false);
            return;
        }
        try {
            byte[] json = document.toString().getBytes(StandardCharsets.UTF_8);
            ByteBuffer frame = ByteBuffer.allocate(4 + json.length).order(ByteOrder.LITTLE_ENDIAN);
            frame.putInt(json.length).put(json);
            byte[] bytes = frame.array();
            payloadSize = bytes.length;
            int chunkSize = Math.max(20, negotiatedMtu - 3);
            writeQueue.clear();
            for (int offset = 0; offset < bytes.length; offset += chunkSize) {
                writeQueue.add(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + chunkSize)));
            }
            writing = true;
            pendingSuccessLabel = successLabel;
            updateStatus("Enviando " + json.length + " bytes...", false);
            writeNextChunk();
        } catch (RuntimeException error) {
            updateStatus("No se pudo preparar el documento", true);
        }
    }

    private void maybeSendPendingDocument() {
        if (gatt == null || rxCharacteristic == null || writing || !notificationsReady) return;
        if (pendingDocument != null) {
            JSONObject document = pendingDocument;
            String label = pendingDocumentSuccessLabel;
            pendingDocument = null;
            pendingDocumentSuccessLabel = null;
            sendDocument(document, label == null ? "Documento enviado al reloj" : label);
        }
    }

    private void writeNextChunk() {
        byte[] chunk = writeQueue.poll();
        if (chunk == null) {
            writing = false;
            detailText += "\nÚltimo envío: " + payloadSize + " bytes";
            updateStatus(pendingSuccessLabel, false);
            maybeSendPendingDocument();
            return;
        }
        boolean queued;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queued = gatt.writeCharacteristic(rxCharacteristic, chunk,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                == android.bluetooth.BluetoothStatusCodes.SUCCESS;
        } else {
            rxCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxCharacteristic.setValue(chunk);
            queued = gatt.writeCharacteristic(rxCharacteristic);
        }
        if (!queued) {
            writing = false;
            writeQueue.clear();
            updateStatus("Android rechazó una escritura BLE", true);
        }
    }

    private String saveWav(String fileName, byte[] pcm, int sampleRate) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Audio.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav");
            values.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/CodexWatch");
            values.put(MediaStore.Audio.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore no creó el archivo");
            try {
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("No se pudo abrir el archivo");
                    writeWav(output, pcm, sampleRate);
                }
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Audio.Media.IS_PENDING, 0);
                getContentResolver().update(uri, ready, null, null);
            } catch (IOException error) {
                getContentResolver().delete(uri, null, null);
                throw error;
            }
            return "Music/CodexWatch/" + fileName;
        }
        File base = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) throw new IOException("Almacenamiento externo no disponible");
        File directory = new File(base, "CodexWatch");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("No se pudo crear la carpeta");
        File file = new File(directory, fileName);
        try (OutputStream output = new FileOutputStream(file)) {
            writeWav(output, pcm, sampleRate);
        }
        return file.getAbsolutePath();
    }

    private void writeWav(OutputStream output, byte[] pcm, int sampleRate) throws IOException {
        int byteRate = sampleRate * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + pcm.length);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16).putShort((short) 1).putShort((short) 1);
        header.putInt(sampleRate).putInt(byteRate).putShort((short) 2).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(pcm.length);
        output.write(header.array());
        output.write(pcm);
    }

    private void updateStatus(String text, boolean error) {
        mainHandler.post(() -> {
            statusText = text;
            statusError = error;
            refreshNotification();
            publishState();
        });
    }

    private void publishState() {
        Listener current = listener;
        if (current == null) return;
        State state = getStateSnapshot();
        mainHandler.post(() -> {
            if (listener == current) current.onStateChanged(state);
        });
    }

    private String friendlyMessage(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().trim().isEmpty()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return error == null ? "error desconocido" : error.getClass().getSimpleName();
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, WatchConnectionService.class).setAction(ACTION_START);
        context.startForegroundService(intent);
    }

    public static void reconnect(Context context) {
        Intent intent = new Intent(context, WatchConnectionService.class).setAction(ACTION_RECONNECT);
        context.startForegroundService(intent);
    }
}
