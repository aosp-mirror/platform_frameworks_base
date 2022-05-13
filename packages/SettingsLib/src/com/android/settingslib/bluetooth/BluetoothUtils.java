package com.android.settingslib.bluetooth;

import static com.android.settingslib.widget.AdaptiveOutlineDrawable.ICON_TYPE_ADVANCED;

import android.annotation.SuppressLint;
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
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.graphics.drawable.IconCompat;

import com.android.settingslib.R;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BluetoothUtils {
    private static final String TAG = "BluetoothUtils";

    public static final boolean V = false; // verbose logging
    public static final boolean D = true;  // regular logging

    public static final int META_INT_ERROR = -1;
    public static final String BT_ADVANCED_HEADER_ENABLED = "bt_advanced_header_enabled";
    private static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;
    private static final String KEY_HEARABLE_CONTROL_SLICE = "HEARABLE_CONTROL_SLICE_WITH_WIDTH";

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

    /**
     * @param context to access resources from
     * @param cachedDevice to get class from
     * @return pair containing the drawable and the description of the Bluetooth class
     *         of the device.
     */
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
            if (doesClassMatch(btClass, BluetoothClass.PROFILE_HEADSET)) {
                return new Pair<>(
                        getBluetoothDrawable(context,
                                com.android.internal.R.drawable.ic_bt_headset_hfp),
                        context.getString(R.string.bluetooth_talkback_headset));
            }
            if (doesClassMatch(btClass, BluetoothClass.PROFILE_A2DP)) {
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
        final int iconSize = context.getResources().getDimensionPixelSize(
                R.dimen.bt_nearby_icon_size);
        final Resources resources = context.getResources();

        // Deal with advanced device icon
        if (isAdvancedDetailsHeader(bluetoothDevice)) {
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
     * Check if the Bluetooth device supports advanced metadata
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @return true if it supports advanced metadata, false otherwise.
     */
    public static boolean isAdvancedDetailsHeader(@NonNull BluetoothDevice bluetoothDevice) {
        if (!DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SETTINGS_UI, BT_ADVANCED_HEADER_ENABLED,
                true)) {
            Log.d(TAG, "isAdvancedDetailsHeader: advancedEnabled is false");
            return false;
        }
        // The metadata is for Android R
        if (getBooleanMetaData(bluetoothDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            Log.d(TAG, "isAdvancedDetailsHeader: untetheredHeadset is true");
            return true;
        }
        // The metadata is for Android S
        String deviceType = getStringMetaData(bluetoothDevice,
                BluetoothDevice.METADATA_DEVICE_TYPE);
        if (TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET)
                || TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_WATCH)
                || TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_DEFAULT)) {
            Log.d(TAG, "isAdvancedDetailsHeader: deviceType is " + deviceType);
            return true;
        }
        return false;
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
            return new AdaptiveOutlineDrawable(resources, resizedBitmap, ICON_TYPE_ADVANCED);
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

    /**
     * Get URI Bluetooth metadata for extra control
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @return the URI metadata
     */
    public static String getControlUriMetaData(BluetoothDevice bluetoothDevice) {
        String data = getStringMetaData(bluetoothDevice, METADATA_FAST_PAIR_CUSTOMIZED_FIELDS);
        return extraTagValue(KEY_HEARABLE_CONTROL_SLICE, data);
    }

    @SuppressLint("NewApi") // Hidden API made public
    private static boolean doesClassMatch(BluetoothClass btClass, int classId) {
        return btClass.doesClassMatch(classId);
    }

    private static String extraTagValue(String tag, String metaData) {
        if (TextUtils.isEmpty(metaData)) {
            return null;
        }
        Pattern pattern = Pattern.compile(generateExpressionWithTag(tag, "(.*?)"));
        Matcher matcher = pattern.matcher(metaData);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String getTagStart(String tag) {
        return String.format(Locale.ENGLISH, "<%s>", tag);
    }

    private static String getTagEnd(String tag) {
        return String.format(Locale.ENGLISH, "</%s>", tag);
    }

    private static String generateExpressionWithTag(String tag, String value) {
        return getTagStart(tag) + value + getTagEnd(tag);
    }
}
