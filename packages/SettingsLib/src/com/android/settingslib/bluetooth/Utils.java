package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.drawable.Drawable;
import androidx.annotation.DrawableRes;
import android.util.Pair;

import com.android.settingslib.R;
import com.android.settingslib.graph.BluetoothDeviceLayerDrawable;

import java.util.List;

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

    public static Pair<Drawable, String> getBtClassDrawableWithDescription(Context context,
            CachedBluetoothDevice cachedDevice) {
        return getBtClassDrawableWithDescription(context, cachedDevice, 1 /* iconScale */);
    }

    public static Pair<Drawable, String> getBtClassDrawableWithDescription(Context context,
            CachedBluetoothDevice cachedDevice, float iconScale) {
        BluetoothClass btClass = cachedDevice.getBtClass();
        final int level = cachedDevice.getBatteryLevel();
        if (btClass != null) {
            switch (btClass.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.COMPUTER:
                    return new Pair<>(getBluetoothDrawable(context, R.drawable.ic_bt_laptop, level,
                            iconScale),
                            context.getString(R.string.bluetooth_talkback_computer));

                case BluetoothClass.Device.Major.PHONE:
                    return new Pair<>(
                            getBluetoothDrawable(context, R.drawable.ic_bt_cellphone, level,
                                    iconScale),
                            context.getString(R.string.bluetooth_talkback_phone));

                case BluetoothClass.Device.Major.PERIPHERAL:
                    return new Pair<>(
                            getBluetoothDrawable(context, HidProfile.getHidClassDrawable(btClass),
                                    level, iconScale),
                            context.getString(R.string.bluetooth_talkback_input_peripheral));

                case BluetoothClass.Device.Major.IMAGING:
                    return new Pair<>(
                            getBluetoothDrawable(context, R.drawable.ic_settings_print, level,
                                    iconScale),
                            context.getString(R.string.bluetooth_talkback_imaging));

                default:
                    // unrecognized device class; continue
            }
        }

        List<LocalBluetoothProfile> profiles = cachedDevice.getProfiles();
        for (LocalBluetoothProfile profile : profiles) {
            int resId = profile.getDrawableResource(btClass);
            if (resId != 0) {
                return new Pair<>(getBluetoothDrawable(context, resId, level, iconScale), null);
            }
        }
        if (btClass != null) {
            if (btClass.doesClassMatch(BluetoothClass.PROFILE_HEADSET)) {
                return new Pair<>(
                        getBluetoothDrawable(context, R.drawable.ic_bt_headset_hfp, level,
                                iconScale),
                        context.getString(R.string.bluetooth_talkback_headset));
            }
            if (btClass.doesClassMatch(BluetoothClass.PROFILE_A2DP)) {
                return new Pair<>(
                        getBluetoothDrawable(context, R.drawable.ic_bt_headphones_a2dp, level,
                                iconScale),
                        context.getString(R.string.bluetooth_talkback_headphone));
            }
        }
        return new Pair<>(
                getBluetoothDrawable(context, R.drawable.ic_settings_bluetooth, level, iconScale),
                context.getString(R.string.bluetooth_talkback_bluetooth));
    }

    public static Drawable getBluetoothDrawable(Context context, @DrawableRes int resId,
            int batteryLevel, float iconScale) {
        if (batteryLevel != BluetoothDevice.BATTERY_LEVEL_UNKNOWN) {
            return BluetoothDeviceLayerDrawable.createLayerDrawable(context, resId, batteryLevel,
                    iconScale);
        } else {
            return context.getDrawable(resId);
        }
    }
}
