package dev.codexwatch.demo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

public final class OAuthForegroundService extends Service {
    private static final String CHANNEL_ID = "openai_oauth";
    private static final int NOTIFICATION_ID = 1408;

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
            CHANNEL_ID,
            "Inicio de sesión de OpenAI",
            NotificationManager.IMPORTANCE_LOW
        ));
        PendingIntent returnIntent = PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.app_icon)
            .setContentTitle("Conectando OpenAI")
            .setContentText("Completa el inicio de sesión en el navegador")
            .setContentIntent(returnIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, OAuthForegroundService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, OAuthForegroundService.class));
    }
}
