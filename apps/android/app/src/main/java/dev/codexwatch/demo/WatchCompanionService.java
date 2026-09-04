package dev.codexwatch.demo;

import android.companion.AssociationInfo;
import android.companion.CompanionDeviceService;
import android.annotation.TargetApi;
import android.os.Build;

@TargetApi(Build.VERSION_CODES.S)
public final class WatchCompanionService extends CompanionDeviceService {
    @Override
    public void onDeviceAppeared(String address) {
        rememberAndStart(address);
    }

    @Override
    public void onDeviceAppeared(AssociationInfo associationInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && associationInfo.getDeviceMacAddress() != null) {
            rememberAndStart(associationInfo.getDeviceMacAddress().toString());
        } else {
            WatchConnectionService.start(this);
        }
    }

    @Override
    public void onDeviceDisappeared(String address) {
        // El servicio de conexión conserva autoConnect y seguirá esperando al reloj.
    }

    @Override
    public void onDeviceDisappeared(AssociationInfo associationInfo) {
        // El servicio de conexión conserva autoConnect y seguirá esperando al reloj.
    }

    private void rememberAndStart(String address) {
        if (address != null && !address.isBlank()) {
            getSharedPreferences(WatchConnectionService.PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(WatchConnectionService.PREF_WATCH_ADDRESS, address)
                .putBoolean(WatchConnectionService.PREF_AUTO_CONNECT, true)
                .apply();
        }
        WatchConnectionService.start(this);
    }
}
