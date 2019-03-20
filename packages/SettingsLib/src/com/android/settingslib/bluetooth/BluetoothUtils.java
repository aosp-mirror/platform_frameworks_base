package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Pair;

import androidx.annotation.DrawableRes;

import com.android.settingslib.R;

import java.util.List;

public class BluetoothUtils {
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

    public static Pair<Drawable, String> getBtClassDrawableWithDescription(Context context,
            CachedBluetoothDevice cachedDevice) {
        BluetoothClass btClass = cachedDevice.getBtClass();
        if (btClass != null) {
            switch (btClass.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.COMPUTER:
                    return new Pair<>(getBluetoothDrawable(context,
                            com.android.internal.R.drawable.ic_bt_laptop),
                            context.getString(R.string.bluetooth_talkback_computer));

                case BluetoothClass.Device.Major.PHONE:
                    return new Pair<>(
                            getBluetoothDrawable(context,
                                    com.android.internal.R.drawable.ic_phone),
                            context.getString(R.string.bluetooth_talkback_phone));

                case BluetoothClass.Device.Major.PERIPHERAL:
                    return new Pair<>(
                            getBluetoothDrawable(context, HidProfile.getHidClassDrawable(btClass)),
                            context.getString(R.string.bluetooth_talkback_input_peripheral));

                case BluetoothClass.Device.Major.IMAGING:
                    return new Pair<>(
                            getBluetoothDrawable(context,
                                    com.android.internal.R.drawable.ic_settings_print),
                            context.getString(R.string.bluetooth_talkback_imaging));

                default:
                    // unrecognized device class; continue
            }
        }

        List<LocalBluetoothProfile> profiles = cachedDevice.getProfiles();
        for (LocalBluetoothProfile profile : profiles) {
            int resId = profile.getDrawableResource(btClass);
            if (resId != 0) {
                return new Pair<>(getBluetoothDrawable(context, resId), null);
            }
        }
        if (btClass != null) {
            if (btClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET)) {
                return new Pair<>(
                        getBluetoothDrawable(context,
                                com.android.internal.R.drawable.ic_bt_headset_hfp),
                        context.getString(R.string.bluetooth_talkback_headset));
            }
            if (btClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)) {
                return new Pair<>(
                        getBluetoothDrawable(context,
                                com.android.internal.R.drawable.ic_bt_headphones_a2dp),
                        context.getString(R.string.bluetooth_talkback_headphone));
            }
        }
        return new Pair<>(
                getBluetoothDrawable(context,
                        com.android.internal.R.drawable.ic_settings_bluetooth),
                context.getString(R.string.bluetooth_talkback_bluetooth));
    }

    /**
     * Get bluetooth drawable by {@code resId}
     */
    public static Drawable getBluetoothDrawable(Context context, @DrawableRes int resId) {
        return context.getDrawable(resId);
    }
}
