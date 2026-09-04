package dev.codexwatch.demo;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

public final class WatchBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
            && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        boolean enabled = context.getSharedPreferences(
            WatchConnectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(WatchConnectionService.PREF_AUTO_CONNECT, false);
        if (!enabled || !hasBluetoothPermissions(context)) return;

        try {
            WatchConnectionService.start(context);
        } catch (RuntimeException ignored) {
            // CompanionDeviceService volverá a arrancarlo cuando detecte el reloj.
        }
    }

    private boolean hasBluetoothPermissions(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        }
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }
}
