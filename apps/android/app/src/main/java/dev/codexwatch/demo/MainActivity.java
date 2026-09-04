package dev.codexwatch.demo;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.ScanFilter;
import android.companion.AssociationInfo;
import android.companion.AssociationRequest;
import android.companion.BluetoothLeDeviceFilter;
import android.companion.CompanionDeviceManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements WatchConnectionService.Listener {
    private static final int PERMISSION_REQUEST = 40;
    private static final int ENABLE_BLUETOOTH_REQUEST = 41;
    private static final int ASSOCIATION_REQUEST = 42;
    private static final String DEVICE_NAME = "Codex Watch";

    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private BluetoothAdapter bluetoothAdapter;
    private CompanionDeviceManager companionManager;
    private WatchConnectionService watchService;
    private boolean serviceBound;
    private boolean serviceBindingRequested;
    private boolean associationInProgress;
    private boolean loginInProgress;
    private ChatGptAuthClient authClient;

    private TextView statusView;
    private TextView detailView;
    private TextView authStatusView;
    private TextView transcriptView;
    private EditText serverUrlView;
    private Button connectButton;
    private Button playAudioButton;
    private Button exportAudioButton;
    private Button transcribeAudioButton;
    private Button authButton;
    private Button logoutButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        authClient = new ChatGptAuthClient(getApplicationContext(), new SecureTokenStore(getApplicationContext()));
        BluetoothManager bluetoothManager = getSystemService(BluetoothManager.class);
        bluetoothAdapter = bluetoothManager == null ? null : bluetoothManager.getAdapter();
        companionManager = getSystemService(CompanionDeviceManager.class);
        buildInterface();
        refreshAuthUi();
        refreshAssociationUi();

        if (hasBluetoothPermissions()) startAndBindService(false);
        else setStatus("Concede los permisos una vez para activar la conexión automática", false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasBluetoothPermissions()) bindWatchService();
    }

    @Override
    protected void onStop() {
        if (serviceBound || serviceBindingRequested) {
            if (watchService != null) watchService.clearListener(this);
            unbindService(serviceConnection);
            serviceBound = false;
            serviceBindingRequested = false;
            watchService = null;
        }
        super.onStop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshAuthUi();
    }

    private void buildInterface() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(9, 11, 9));

        TextView eyebrow = text("CONTROL REMOTO", 12, Color.rgb(183, 243, 75));
        eyebrow.setLetterSpacing(0.15f);
        root.addView(eyebrow);

        TextView title = text("Codex Watch", 30, Color.rgb(243, 245, 239));
        title.setPadding(0, dp(5), 0, dp(8));
        root.addView(title);

        TextView explanation = text(
            "El servicio permanece activo, reconecta el reloj automáticamente y enlaza Codex a través de tu red privada.",
            16, Color.rgb(170, 180, 168));
        explanation.setLineSpacing(0, 1.15f);
        root.addView(explanation);

        LinearLayout authCard = card(root, dp(24), 0);
        TextView authLabel = text("SESIÓN DE OPENAI", 12, Color.rgb(183, 243, 75));
        authLabel.setLetterSpacing(0.12f);
        authCard.addView(authLabel);
        authStatusView = text("Comprobando sesión...", 15, Color.rgb(170, 180, 168));
        authStatusView.setPadding(0, dp(8), 0, dp(10));
        authCard.addView(authStatusView);
        LinearLayout authActions = new LinearLayout(this);
        authActions.setOrientation(LinearLayout.HORIZONTAL);
        authButton = button("Iniciar sesión con OpenAI");
        authButton.setOnClickListener(view -> startOpenAiLogin());
        authActions.addView(authButton, new LinearLayout.LayoutParams(0, dp(56), 1));
        logoutButton = button("Cerrar sesión");
        logoutButton.setOnClickListener(view -> {
            authClient.logout();
            refreshAuthUi();
        });
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        logoutParams.setMargins(dp(8), 0, 0, 0);
        authActions.addView(logoutButton, logoutParams);
        authCard.addView(authActions);

        LinearLayout serverCard = card(root, dp(24), dp(18));
        TextView serverLabel = text("SERVIDOR DEL ORDENADOR", 12, Color.rgb(183, 243, 75));
        serverLabel.setLetterSpacing(0.12f);
        serverCard.addView(serverLabel);
        serverUrlView = new EditText(this);
        serverUrlView.setSingleLine(true);
        serverUrlView.setText(getSharedPreferences(WatchConnectionService.PREFS_NAME, MODE_PRIVATE)
            .getString(WatchConnectionService.PREF_SERVER_URL, "http://192.168.1.100:8787"));
        serverUrlView.setHint("http://dirección-del-ordenador:8787");
        serverUrlView.setTextColor(Color.rgb(243, 245, 239));
        serverUrlView.setHintTextColor(Color.rgb(120, 132, 119));
        serverUrlView.setTextSize(16);
        serverUrlView.setSelectAllOnFocus(true);
        serverUrlView.setImeOptions(EditorInfo.IME_ACTION_DONE);
        serverUrlView.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                saveServerUrlAndReload();
                view.clearFocus();
                return true;
            }
            return false;
        });
        serverUrlView.setOnFocusChangeListener((view, focused) -> {
            if (!focused) saveServerUrlAndReload();
        });
        serverCard.addView(serverUrlView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout statusCard = card(root, dp(6), dp(18));
        statusView = text("Inicializando...", 18, Color.rgb(243, 245, 239));
        statusCard.addView(statusView);
        detailView = text("Reloj: desconectado", 14, Color.rgb(154, 163, 152));
        detailView.setPadding(0, dp(10), 0, 0);
        statusCard.addView(detailView);

        connectButton = button("Asociar reloj y activar conexión automática");
        connectButton.setOnClickListener(view -> ensurePermissionsAndAssociate());
        root.addView(connectButton, buttonParams());

        playAudioButton = button("Reproducir último audio");
        playAudioButton.setEnabled(false);
        playAudioButton.setOnClickListener(view -> {
            if (watchService != null) watchService.playLastAudio();
        });
        root.addView(playAudioButton, buttonParams());

        exportAudioButton = button("Guardar WAV para analizar");
        exportAudioButton.setEnabled(false);
        exportAudioButton.setOnClickListener(view -> {
            if (watchService != null) watchService.exportLastAudio();
        });
        root.addView(exportAudioButton, buttonParams());

        transcribeAudioButton = button("Transcribir último audio");
        transcribeAudioButton.setEnabled(false);
        transcribeAudioButton.setOnClickListener(view -> {
            if (watchService != null) watchService.transcribeLastAudio();
        });
        root.addView(transcribeAudioButton, buttonParams());

        TextView transcriptLabel = text("TRANSCRIPCIÓN", 12, Color.rgb(183, 243, 75));
        transcriptLabel.setLetterSpacing(0.12f);
        transcriptLabel.setPadding(0, dp(10), 0, dp(8));
        root.addView(transcriptLabel);
        transcriptView = text("La transcripción aparecerá aquí.", 16, Color.rgb(243, 245, 239));
        transcriptView.setTextIsSelectable(true);
        transcriptView.setPadding(dp(16), dp(14), dp(16), dp(14));
        transcriptView.setBackgroundColor(Color.rgb(23, 28, 23));
        root.addView(transcriptView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView note = text(
            "La primera asociación requiere esta pantalla. Después Android reconectará el reloj incluso con la aplicación cerrada; la notificación permanente indica que el servicio está activo.",
            14, Color.rgb(154, 163, 152));
        note.setPadding(0, dp(16), 0, 0);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    private LinearLayout card(LinearLayout root, int top, int bottom) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.rgb(23, 28, 23));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, top, 0, bottom);
        root.addView(card, params);
        return card;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(56));
        params.setMargins(0, 0, 0, dp(12));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionsAndAssociate() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        if (bluetoothAdapter == null) {
            setStatus("Bluetooth LE no está disponible", true);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BLUETOOTH_REQUEST);
            return;
        }
        beginAssociationOrReconnect();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) return;
        if (!hasBluetoothPermissions()) {
            setStatus("Los permisos Bluetooth son necesarios para la conexión automática", true);
            return;
        }
        startAndBindService(false);
        beginAssociationOrReconnect();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ENABLE_BLUETOOTH_REQUEST) {
            if (resultCode == RESULT_OK) beginAssociationOrReconnect();
            else setStatus("Activa Bluetooth para continuar", true);
            return;
        }
        if (requestCode != ASSOCIATION_REQUEST) return;
        associationInProgress = false;
        connectButton.setEnabled(true);
        if (resultCode != RESULT_OK || data == null) {
            setStatus("Asociación cancelada", true);
            return;
        }
        String address = extractAssociationAddress(data);
        if (address != null) rememberAssociation(address);
        refreshAssociationUi();
        startAndBindService(true);
    }

    private void beginAssociationOrReconnect() {
        String associatedAddress = findAssociatedAddress();
        if (associatedAddress != null) {
            rememberAssociation(associatedAddress);
            observePresence(associatedAddress);
            startAndBindService(true);
            return;
        }
        if (companionManager == null) {
            setStatus("Este móvil no admite asociación de dispositivos complementarios", true);
            startAndBindService(true);
            return;
        }
        associationInProgress = true;
        connectButton.setEnabled(false);
        setStatus("Buscando el reloj para asociarlo...", false);
        BluetoothLeDeviceFilter filter = new BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("^" + Pattern.quote(DEVICE_NAME) + "$"))
            .build();
        AssociationRequest request = new AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(true)
            .build();
        companionManager.associate(request, new CompanionDeviceManager.Callback() {
            @Override
            public void onAssociationPending(IntentSender chooserLauncher) {
                launchAssociationChooser(chooserLauncher);
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onDeviceFound(IntentSender chooserLauncher) {
                launchAssociationChooser(chooserLauncher);
            }

            @Override
            public void onAssociationCreated(AssociationInfo associationInfo) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && associationInfo.getDeviceMacAddress() != null) {
                    String address = associationInfo.getDeviceMacAddress().toString();
                    runOnUiThread(() -> {
                        associationInProgress = false;
                        connectButton.setEnabled(true);
                        rememberAssociation(address);
                        observePresence(address);
                        refreshAssociationUi();
                        startAndBindService(true);
                    });
                }
            }

            @Override
            public void onFailure(CharSequence error) {
                runOnUiThread(() -> {
                    associationInProgress = false;
                    connectButton.setEnabled(true);
                    setStatus("No se pudo asociar el reloj: " + error, true);
                });
            }
        }, null);
    }

    private void launchAssociationChooser(IntentSender chooserLauncher) {
        runOnUiThread(() -> {
            try {
                startIntentSenderForResult(chooserLauncher, ASSOCIATION_REQUEST,
                    null, 0, 0, 0);
            } catch (IntentSender.SendIntentException error) {
                associationInProgress = false;
                connectButton.setEnabled(true);
                setStatus("No se pudo abrir la asociación: " + error.getMessage(), true);
            }
        });
    }

    private String extractAssociationAddress(Intent data) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            AssociationInfo info = data.getParcelableExtra(
                CompanionDeviceManager.EXTRA_ASSOCIATION, AssociationInfo.class);
            if (info != null && info.getDeviceMacAddress() != null) {
                return info.getDeviceMacAddress().toString();
            }
        }
        @SuppressWarnings("deprecation")
        BluetoothDevice device = data.getParcelableExtra(CompanionDeviceManager.EXTRA_DEVICE);
        return device == null ? null : device.getAddress();
    }

    private String findAssociatedAddress() {
        if (companionManager == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            for (AssociationInfo info : companionManager.getMyAssociations()) {
                if (info.getDeviceMacAddress() != null) return info.getDeviceMacAddress().toString();
            }
            return null;
        }
        @SuppressWarnings("deprecation")
        List<String> associations = companionManager.getAssociations();
        return associations.isEmpty() ? null : associations.get(0);
    }

    private void rememberAssociation(String address) {
        getSharedPreferences(WatchConnectionService.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(WatchConnectionService.PREF_WATCH_ADDRESS, address)
            .putBoolean(WatchConnectionService.PREF_AUTO_CONNECT, true)
            .apply();
    }

    @SuppressWarnings("deprecation")
    private void observePresence(String address) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || companionManager == null) return;
        try {
            companionManager.startObservingDevicePresence(address);
        } catch (IllegalStateException | IllegalArgumentException ignored) {
            // Ya se estaba observando o la asociación todavía está terminando de persistirse.
        }
    }

    private void refreshAssociationUi() {
        String address = findAssociatedAddress();
        connectButton.setText(address == null
            ? "Asociar reloj y activar conexión automática"
            : "Reconectar ahora · " + address);
        if (address != null) observePresence(address);
    }

    private void saveServerUrlAndReload() {
        String value = serverUrlView.getText().toString().trim();
        getSharedPreferences(WatchConnectionService.PREFS_NAME, MODE_PRIVATE).edit()
            .putString(WatchConnectionService.PREF_SERVER_URL, value).apply();
        if (watchService != null) watchService.reloadServer();
    }

    private void startAndBindService(boolean reconnect) {
        saveServerUrlAndReload();
        if (reconnect) WatchConnectionService.reconnect(this);
        else WatchConnectionService.start(this);
        bindWatchService();
    }

    private void bindWatchService() {
        if (serviceBound || serviceBindingRequested) return;
        serviceBindingRequested = bindService(
            new Intent(this, WatchConnectionService.class), serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            watchService = ((WatchConnectionService.LocalBinder) binder).getService();
            serviceBound = true;
            serviceBindingRequested = false;
            watchService.setListener(MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            serviceBindingRequested = false;
            watchService = null;
            setStatus("El servicio de conexión se ha reiniciado", true);
        }
    };

    @Override
    public void onStateChanged(WatchConnectionService.State state) {
        statusView.setText(state.status);
        statusView.setTextColor(state.error ? Color.rgb(255, 135, 135) : Color.rgb(183, 243, 75));
        detailView.setText(state.detail);
        transcriptView.setText(state.transcript);
        playAudioButton.setEnabled(state.audioAvailable);
        exportAudioButton.setEnabled(state.audioAvailable);
        transcribeAudioButton.setEnabled(state.audioAvailable
            && !state.transcriptionInProgress && isLoggedInSafe());
    }

    private void setStatus(String text, boolean error) {
        statusView.setText(text);
        statusView.setTextColor(error ? Color.rgb(255, 135, 135) : Color.rgb(183, 243, 75));
    }

    private void refreshAuthUi() {
        try {
            ChatGptAuthClient.Identity identity = authClient.identity();
            boolean loggedIn = identity != null;
            String label = loggedIn
                ? (identity.email == null ? "Sesión iniciada" : identity.email)
                : "Sin sesión. Se abrirá el navegador para iniciar sesión.";
            if (loggedIn && identity.plan != null) label += " · ChatGPT " + identity.plan;
            authStatusView.setText(label);
            authStatusView.setTextColor(loggedIn
                ? Color.rgb(183, 243, 75) : Color.rgb(170, 180, 168));
            authButton.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
            logoutButton.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
            authButton.setEnabled(!loginInProgress);
            logoutButton.setEnabled(!loginInProgress);
            WatchConnectionService.State state = watchService == null ? null : watchService.getStateSnapshot();
            transcribeAudioButton.setEnabled(loggedIn && state != null
                && state.audioAvailable && !state.transcriptionInProgress);
        } catch (Throwable error) {
            authStatusView.setText("La sesión guardada no es válida: " + friendlyMessage(error));
            authStatusView.setTextColor(Color.rgb(255, 135, 135));
            authButton.setVisibility(View.VISIBLE);
            logoutButton.setVisibility(View.GONE);
        }
    }

    private boolean isLoggedInSafe() {
        try {
            return authClient.isLoggedIn();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void startOpenAiLogin() {
        if (loginInProgress) return;
        loginInProgress = true;
        refreshAuthUi();
        ChatGptAuthClient.LoginSession session;
        try {
            OAuthForegroundService.start(this);
            session = authClient.prepareLogin();
            authStatusView.setText("Completa el inicio de sesión en el navegador...");
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizationUrl)));
        } catch (Throwable error) {
            authClient.cancelPendingLogin();
            OAuthForegroundService.stop(this);
            loginInProgress = false;
            refreshAuthUi();
            authStatusView.setText("No se pudo iniciar el login: " + friendlyMessage(error));
            return;
        }
        authExecutor.execute(() -> {
            try {
                ChatGptAuthClient.Identity identity = authClient.completeLogin(session);
                runOnUiThread(() -> {
                    OAuthForegroundService.stop(this);
                    loginInProgress = false;
                    refreshAuthUi();
                    authStatusView.setText(identity.email == null
                        ? "OpenAI conectado" : "OpenAI conectado: " + identity.email);
                });
            } catch (Throwable error) {
                authClient.cancelPendingLogin();
                runOnUiThread(() -> {
                    OAuthForegroundService.stop(this);
                    loginInProgress = false;
                    refreshAuthUi();
                    authStatusView.setText("Falló el inicio de sesión: " + friendlyMessage(error));
                    authStatusView.setTextColor(Color.rgb(255, 135, 135));
                });
            }
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

    @Override
    protected void onDestroy() {
        if (serviceBound || serviceBindingRequested) {
            if (watchService != null) watchService.clearListener(this);
            unbindService(serviceConnection);
            serviceBound = false;
            serviceBindingRequested = false;
        }
        authExecutor.shutdownNow();
        super.onDestroy();
    }
}
