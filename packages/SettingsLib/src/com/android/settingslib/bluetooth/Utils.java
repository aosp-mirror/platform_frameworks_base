package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.android.settingslib.R;

public class Utils {
    public static final boolean V = false; // verbose logging
    public static final boolean D = true;  // regular logging

    private static ErrorListener sErrorListener;

    public static int getConnectionStateSummary(int connectionState) {
        switch (connectionState) {
        case BluetoothProfile.STATE_CONNECTED:
            return R.string.bluetooth_connected;
        case BluetoothProfile.STATE_CONNECTING:
            return R.string.bluetooth_connecting;
        case BluetoothProfile.STATE_DISCONNECTED:
            return R.string.bluetooth_disconnected;
        case BluetoothProfile.STATE_DISCONNECTING:
            return R.string.bluetooth_disconnecting;
        default:
            return 0;
        }
    }

    static void showError(Context context, String name, int messageResId) {
        if (sErrorListener != null) {
            sErrorListener.onShowError(context, name, messageResId);
        }
    }

    public static void setErrorListener(ErrorListener listener) {
        sErrorListener = listener;
    }

    public interface ErrorListener {
        void onShowError(Context context, String name, int messageResId);
    }

}
