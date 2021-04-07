package com.android.settingslib.bluetooth;

import static com.android.settingslib.widget.AdaptiveOutlineDrawable.AdaptiveOutlineIconType.TYPE_ADVANCED;

import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.DrawableRes;
import androidx.core.graphics.drawable.IconCompat;

import com.android.settingslib.R;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import java.io.IOException;
import java.util.List;

public class BluetoothUtils {
    private static final String TAG = "BluetoothUtils";

    public static final boolean V = false; // verbose logging
    public static final boolean D = true;  // regular logging

    public static final int META_INT_ERROR = -1;

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
                        com.android.internal.R.drawable.ic_settings_bluetooth).mutate(),
                context.getString(R.string.bluetooth_talkback_bluetooth));
    }

    /**
     * Get bluetooth drawable by {@code resId}
     */
    public static Drawable getBluetoothDrawable(Context context, @DrawableRes int resId) {
        return context.getDrawable(resId);
    }

    /**
     * Get colorful bluetooth icon with description
     */
    public static Pair<Drawable, String> getBtRainbowDrawableWithDescription(Context context,
            CachedBluetoothDevice cachedDevice) {
        final Resources resources = context.getResources();
        final Pair<Drawable, String> pair = BluetoothUtils.getBtDrawableWithDescription(context,
                cachedDevice);

        if (pair.first instanceof BitmapDrawable) {
            return new Pair<>(new AdaptiveOutlineDrawable(
                    resources, ((BitmapDrawable) pair.first).getBitmap()), pair.second);
        }

        return new Pair<>(buildBtRainbowDrawable(context,
                pair.first, cachedDevice.getAddress().hashCode()), pair.second);
    }

    /**
     * Build Bluetooth device icon with rainbow
     */
    public static Drawable buildBtRainbowDrawable(Context context, Drawable drawable,
            int hashCode) {
        final Resources resources = context.getResources();

        // Deal with normal headset
        final int[] iconFgColors = resources.getIntArray(R.array.bt_icon_fg_colors);
        final int[] iconBgColors = resources.getIntArray(R.array.bt_icon_bg_colors);

        // get color index based on mac address
        final int index = Math.abs(hashCode % iconBgColors.length);
        drawable.setTint(iconFgColors[index]);
        final Drawable adaptiveIcon = new AdaptiveIcon(context, drawable);
        ((AdaptiveIcon) adaptiveIcon).setBackgroundColor(iconBgColors[index]);

        return adaptiveIcon;
    }

    /**
     * Get bluetooth icon with description
     */
    public static Pair<Drawable, String> getBtDrawableWithDescription(Context context,
            CachedBluetoothDevice cachedDevice) {
        final Pair<Drawable, String> pair = BluetoothUtils.getBtClassDrawableWithDescription(
                context, cachedDevice);
        final BluetoothDevice bluetoothDevice = cachedDevice.getDevice();
        final boolean untetheredHeadset = getBooleanMetaData(
                bluetoothDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET);
        final int iconSize = context.getResources().getDimensionPixelSize(
                R.dimen.bt_nearby_icon_size);
        final Resources resources = context.getResources();

        // Deal with untethered headset
        if (untetheredHeadset) {
            final Uri iconUri = getUriMetaData(bluetoothDevice,
                    BluetoothDevice.METADATA_MAIN_ICON);
            if (iconUri != null) {
                try {
                    context.getContentResolver().takePersistableUriPermission(iconUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable permission for: " + iconUri, e);
                }
                try {
                    final Bitmap bitmap = MediaStore.Images.Media.getBitmap(
                            context.getContentResolver(), iconUri);
                    if (bitmap != null) {
                        final Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, iconSize,
                                iconSize, false);
                        bitmap.recycle();
                        return new Pair<>(new BitmapDrawable(resources,
                                resizedBitmap), pair.second);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to get drawable for: " + iconUri, e);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to get permission for: " + iconUri, e);
                }
            }
        }

        return new Pair<>(pair.first, pair.second);
    }

    /**
     * Create an Icon pointing to a drawable.
     */
    public static IconCompat createIconWithDrawable(Drawable drawable) {
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            final int width = drawable.getIntrinsicWidth();
            final int height = drawable.getIntrinsicHeight();
            bitmap = createBitmap(drawable,
                    width > 0 ? width : 1,
                    height > 0 ? height : 1);
        }
        return IconCompat.createWithBitmap(bitmap);
    }

    /**
     * Build device icon with advanced outline
     */
    public static Drawable buildAdvancedDrawable(Context context, Drawable drawable) {
        final int iconSize = context.getResources().getDimensionPixelSize(
                R.dimen.advanced_icon_size);
        final Resources resources = context.getResources();

        Bitmap bitmap = null;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            final int width = drawable.getIntrinsicWidth();
            final int height = drawable.getIntrinsicHeight();
            bitmap = createBitmap(drawable,
                    width > 0 ? width : 1,
                    height > 0 ? height : 1);
        }

        if (bitmap != null) {
            final Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, iconSize,
                    iconSize, false);
            bitmap.recycle();
            return new AdaptiveOutlineDrawable(resources, resizedBitmap, TYPE_ADVANCED);
        }

        return drawable;
    }

    /**
     * Creates a drawable with specified width and height.
     */
    public static Bitmap createBitmap(Drawable drawable, int width, int height) {
        final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Get boolean Bluetooth metadata
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @param key key value within the list of BluetoothDevice.METADATA_*
     * @return the boolean metdata
     */
    public static boolean getBooleanMetaData(BluetoothDevice bluetoothDevice, int key) {
        if (bluetoothDevice == null) {
            return false;
        }
        final byte[] data = bluetoothDevice.getMetadata(key);
        if (data == null) {
            return false;
        }
        return Boolean.parseBoolean(new String(data));
    }

    /**
     * Get String Bluetooth metadata
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @param key key value within the list of BluetoothDevice.METADATA_*
     * @return the String metdata
     */
    public static String getStringMetaData(BluetoothDevice bluetoothDevice, int key) {
        if (bluetoothDevice == null) {
            return null;
        }
        final byte[] data = bluetoothDevice.getMetadata(key);
        if (data == null) {
            return null;
        }
        return new String(data);
    }

    /**
     * Get integer Bluetooth metadata
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @param key key value within the list of BluetoothDevice.METADATA_*
     * @return the int metdata
     */
    public static int getIntMetaData(BluetoothDevice bluetoothDevice, int key) {
        if (bluetoothDevice == null) {
            return META_INT_ERROR;
        }
        final byte[] data = bluetoothDevice.getMetadata(key);
        if (data == null) {
            return META_INT_ERROR;
        }
        try {
            return Integer.parseInt(new String(data));
        } catch (NumberFormatException e) {
            return META_INT_ERROR;
        }
    }

    /**
     * Get URI Bluetooth metadata
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @param key key value within the list of BluetoothDevice.METADATA_*
     * @return the URI metdata
     */
    public static Uri getUriMetaData(BluetoothDevice bluetoothDevice, int key) {
        String data = getStringMetaData(bluetoothDevice, key);
        if (data == null) {
            return null;
        }
        return Uri.parse(data);
    }
}
