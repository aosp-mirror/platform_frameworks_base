package com.android.settingslib;

import android.content.Context;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.os.UserManager;

import com.android.internal.util.UserIcons;
import com.android.settingslib.drawable.CircleFramedDrawable;

public final class Utils {

    /**
     * Return string resource that best describes combination of tethering
     * options available on this device.
     */
    public static int getTetheringLabel(ConnectivityManager cm) {
        String[] usbRegexs = cm.getTetherableUsbRegexs();
        String[] wifiRegexs = cm.getTetherableWifiRegexs();
        String[] bluetoothRegexs = cm.getTetherableBluetoothRegexs();

        boolean usbAvailable = usbRegexs.length != 0;
        boolean wifiAvailable = wifiRegexs.length != 0;
        boolean bluetoothAvailable = bluetoothRegexs.length != 0;

        if (wifiAvailable && usbAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable && usbAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_all;
        } else if (wifiAvailable) {
            return R.string.tether_settings_title_wifi;
        } else if (usbAvailable && bluetoothAvailable) {
            return R.string.tether_settings_title_usb_bluetooth;
        } else if (usbAvailable) {
            return R.string.tether_settings_title_usb;
        } else {
            return R.string.tether_settings_title_bluetooth;
        }
    }

    /**
     * Returns a label for the user, in the form of "User: user name" or "Work profile".
     */
    public static String getUserLabel(Context context, UserInfo info) {
        String name = info != null ? info.name : null;
        if (info.isManagedProfile()) {
            // We use predefined values for managed profiles
            return context.getString(R.string.managed_user_title);
        } else if (info.isGuest()) {
            name = context.getString(R.string.user_guest);
        }
        if (name == null && info != null) {
            name = Integer.toString(info.id);
        } else if (info == null) {
            name = context.getString(R.string.unknown);
        }
        return context.getResources().getString(R.string.running_process_item_user_label, name);
    }

    /**
     * Returns a circular icon for a user.
     */
    public static Drawable getUserIcon(Context context, UserManager um, UserInfo user) {
        if (user.isManagedProfile()) {
            // We use predefined values for managed profiles
            Bitmap b = BitmapFactory.decodeResource(context.getResources(),
                    com.android.internal.R.drawable.ic_corp_icon);
            return CircleFramedDrawable.getInstance(context, b);
        }
        if (user.iconPath != null) {
            Bitmap icon = um.getUserIcon(user.id);
            if (icon != null) {
                return CircleFramedDrawable.getInstance(context, icon);
            }
        }
        return CircleFramedDrawable.getInstance(context, UserIcons.convertToBitmap(
                UserIcons.getDefaultUserIcon(user.id, /* light= */ false)));
    }
}
