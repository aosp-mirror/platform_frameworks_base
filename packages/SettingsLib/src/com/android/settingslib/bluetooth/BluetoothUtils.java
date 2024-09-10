package com.android.settingslib.bluetooth;

import static com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast.UNKNOWN_VALUE_PLACEHOLDER;
import static com.android.settingslib.widget.AdaptiveOutlineDrawable.ICON_TYPE_ADVANCED;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothCsipSetCoordinator;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeBroadcastReceiveState;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.DeviceConfig;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.core.graphics.drawable.IconCompat;

import com.android.settingslib.R;
import com.android.settingslib.flags.Flags;
import com.android.settingslib.widget.AdaptiveIcon;
import com.android.settingslib.widget.AdaptiveOutlineDrawable;

import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BluetoothUtils {
    private static final String TAG = "BluetoothUtils";

    public static final boolean V = false; // verbose logging
    public static final boolean D = true; // regular logging

    public static final int META_INT_ERROR = -1;
    public static final String BT_ADVANCED_HEADER_ENABLED = "bt_advanced_header_enabled";
    private static final int METADATA_FAST_PAIR_CUSTOMIZED_FIELDS = 25;
    private static final String KEY_HEARABLE_CONTROL_SLICE = "HEARABLE_CONTROL_SLICE_WITH_WIDTH";
    private static final Set<Integer> SA_PROFILES =
            ImmutableSet.of(
                    BluetoothProfile.A2DP, BluetoothProfile.LE_AUDIO, BluetoothProfile.HEARING_AID);

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
     * @return pair containing the drawable and the description of the type of the device. The type
     *     could either derived from metadata or CoD.
     */
    public static Pair<Drawable, String> getDerivedBtClassDrawableWithDescription(
            Context context, CachedBluetoothDevice cachedDevice) {
        return BluetoothUtils.isAdvancedUntetheredDevice(cachedDevice.getDevice())
                ? new Pair<>(
                        getBluetoothDrawable(
                                context, com.android.internal.R.drawable.ic_bt_headphones_a2dp),
                        context.getString(R.string.bluetooth_talkback_headphone))
                : BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice);
    }

    /**
     * @param context to access resources from
     * @param cachedDevice to get class from
     * @return pair containing the drawable and the description of the Bluetooth class of the
     *     device.
     */
    public static Pair<Drawable, String> getBtClassDrawableWithDescription(
            Context context, CachedBluetoothDevice cachedDevice) {
        BluetoothClass btClass = cachedDevice.getBtClass();
        if (btClass != null) {
            switch (btClass.getMajorDeviceClass()) {
                case BluetoothClass.Device.Major.COMPUTER:
                    return new Pair<>(
                            getBluetoothDrawable(
                                    context, com.android.internal.R.drawable.ic_bt_laptop),
                            context.getString(R.string.bluetooth_talkback_computer));

                case BluetoothClass.Device.Major.PHONE:
                    return new Pair<>(
                            getBluetoothDrawable(context, com.android.internal.R.drawable.ic_phone),
                            context.getString(R.string.bluetooth_talkback_phone));

                case BluetoothClass.Device.Major.PERIPHERAL:
                    return new Pair<>(
                            getBluetoothDrawable(context, HidProfile.getHidClassDrawable(btClass)),
                            context.getString(R.string.bluetooth_talkback_input_peripheral));

                case BluetoothClass.Device.Major.IMAGING:
                    return new Pair<>(
                            getBluetoothDrawable(
                                    context, com.android.internal.R.drawable.ic_settings_print),
                            context.getString(R.string.bluetooth_talkback_imaging));

                default:
                    // unrecognized device class; continue
            }
        }

        if (cachedDevice.isHearingAidDevice()) {
            return new Pair<>(
                    getBluetoothDrawable(
                            context, com.android.internal.R.drawable.ic_bt_hearing_aid),
                    context.getString(R.string.bluetooth_talkback_hearing_aids));
        }

        List<LocalBluetoothProfile> profiles = cachedDevice.getProfiles();
        int resId = 0;
        for (LocalBluetoothProfile profile : profiles) {
            int profileResId = profile.getDrawableResource(btClass);
            if (profileResId != 0) {
                // The device should show hearing aid icon if it contains any hearing aid related
                // profiles
                if (profile instanceof HearingAidProfile || profile instanceof HapClientProfile) {
                    return new Pair<>(
                            getBluetoothDrawable(context, profileResId),
                            context.getString(R.string.bluetooth_talkback_hearing_aids));
                }
                if (resId == 0) {
                    resId = profileResId;
                }
            }
        }
        if (resId != 0) {
            return new Pair<>(getBluetoothDrawable(context, resId), null);
        }

        if (btClass != null) {
            if (doesClassMatch(btClass, BluetoothClass.PROFILE_HEADSET)) {
                return new Pair<>(
                        getBluetoothDrawable(
                                context, com.android.internal.R.drawable.ic_bt_headset_hfp),
                        context.getString(R.string.bluetooth_talkback_headset));
            }
            if (doesClassMatch(btClass, BluetoothClass.PROFILE_A2DP)) {
                return new Pair<>(
                        getBluetoothDrawable(
                                context, com.android.internal.R.drawable.ic_bt_headphones_a2dp),
                        context.getString(R.string.bluetooth_talkback_headphone));
            }
        }
        return new Pair<>(
                getBluetoothDrawable(context, com.android.internal.R.drawable.ic_settings_bluetooth)
                        .mutate(),
                context.getString(R.string.bluetooth_talkback_bluetooth));
    }

    /** Get bluetooth drawable by {@code resId} */
    public static Drawable getBluetoothDrawable(Context context, @DrawableRes int resId) {
        return context.getDrawable(resId);
    }

    /** Get colorful bluetooth icon with description */
    public static Pair<Drawable, String> getBtRainbowDrawableWithDescription(
            Context context, CachedBluetoothDevice cachedDevice) {
        final Resources resources = context.getResources();
        final Pair<Drawable, String> pair =
                BluetoothUtils.getBtDrawableWithDescription(context, cachedDevice);

        if (pair.first instanceof BitmapDrawable) {
            return new Pair<>(
                    new AdaptiveOutlineDrawable(
                            resources, ((BitmapDrawable) pair.first).getBitmap()),
                    pair.second);
        }

        int hashCode;
        if ((cachedDevice.getGroupId() != BluetoothCsipSetCoordinator.GROUP_ID_INVALID)) {
            hashCode = new Integer(cachedDevice.getGroupId()).hashCode();
        } else {
            hashCode = cachedDevice.getAddress().hashCode();
        }

        return new Pair<>(buildBtRainbowDrawable(context, pair.first, hashCode), pair.second);
    }

    /** Build Bluetooth device icon with rainbow */
    private static Drawable buildBtRainbowDrawable(
            Context context, Drawable drawable, int hashCode) {
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

    /** Get bluetooth icon with description */
    public static Pair<Drawable, String> getBtDrawableWithDescription(
            Context context, CachedBluetoothDevice cachedDevice) {
        final Pair<Drawable, String> pair =
                BluetoothUtils.getBtClassDrawableWithDescription(context, cachedDevice);
        final BluetoothDevice bluetoothDevice = cachedDevice.getDevice();
        final int iconSize =
                context.getResources().getDimensionPixelSize(R.dimen.bt_nearby_icon_size);
        final Resources resources = context.getResources();

        // Deal with advanced device icon
        if (isAdvancedDetailsHeader(bluetoothDevice)) {
            final Uri iconUri = getUriMetaData(bluetoothDevice, BluetoothDevice.METADATA_MAIN_ICON);
            if (iconUri != null) {
                try {
                    context.getContentResolver()
                            .takePersistableUriPermission(
                                    iconUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException e) {
                    Log.e(TAG, "Failed to take persistable permission for: " + iconUri, e);
                }
                try {
                    final Bitmap bitmap =
                            MediaStore.Images.Media.getBitmap(
                                    context.getContentResolver(), iconUri);
                    if (bitmap != null) {
                        final Bitmap resizedBitmap =
                                Bitmap.createScaledBitmap(bitmap, iconSize, iconSize, false);
                        bitmap.recycle();
                        return new Pair<>(
                                new BitmapDrawable(resources, resizedBitmap), pair.second);
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
        if (!isAdvancedHeaderEnabled()) {
            return false;
        }
        if (isUntetheredHeadset(bluetoothDevice)) {
            return true;
        }
        if (Flags.enableDeterminingAdvancedDetailsHeaderWithMetadata()) {
            // A FastPair device that use advanced details header must have METADATA_MAIN_ICON
            if (getUriMetaData(bluetoothDevice, BluetoothDevice.METADATA_MAIN_ICON) != null) {
                Log.d(TAG, "isAdvancedDetailsHeader is true with main icon uri");
                return true;
            }
            return false;
        }
        // The metadata is for Android S
        String deviceType =
                getStringMetaData(bluetoothDevice, BluetoothDevice.METADATA_DEVICE_TYPE);
        if (TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET)
                || TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_WATCH)
                || TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_DEFAULT)
                || TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_STYLUS)) {
            Log.d(TAG, "isAdvancedDetailsHeader: deviceType is " + deviceType);
            return true;
        }
        return false;
    }

    /**
     * Check if the Bluetooth device is supports advanced metadata and an untethered headset
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @return true if it supports advanced metadata and an untethered headset, false otherwise.
     */
    public static boolean isAdvancedUntetheredDevice(@NonNull BluetoothDevice bluetoothDevice) {
        if (!isAdvancedHeaderEnabled()) {
            return false;
        }
        if (isUntetheredHeadset(bluetoothDevice)) {
            return true;
        }
        if (!Flags.enableDeterminingAdvancedDetailsHeaderWithMetadata()) {
            // The METADATA_IS_UNTETHERED_HEADSET of an untethered FastPair headset is always true,
            // so there's no need to check the device type.
            String deviceType =
                    getStringMetaData(bluetoothDevice, BluetoothDevice.METADATA_DEVICE_TYPE);
            if (TextUtils.equals(deviceType, BluetoothDevice.DEVICE_TYPE_UNTETHERED_HEADSET)) {
                Log.d(TAG, "isAdvancedUntetheredDevice: is untethered device");
                return true;
            }
        }
        return false;
    }

    /**
     * Check if a device class matches with a defined BluetoothClass device.
     *
     * @param device Must be one of the public constants in {@link BluetoothClass.Device}
     * @return true if device class matches, false otherwise.
     */
    public static boolean isDeviceClassMatched(
            @NonNull BluetoothDevice bluetoothDevice, int device) {
        final BluetoothClass bluetoothClass = bluetoothDevice.getBluetoothClass();
        return bluetoothClass != null && bluetoothClass.getDeviceClass() == device;
    }

    private static boolean isAdvancedHeaderEnabled() {
        if (!DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SETTINGS_UI, BT_ADVANCED_HEADER_ENABLED, true)) {
            Log.d(TAG, "isAdvancedDetailsHeader: advancedEnabled is false");
            return false;
        }
        return true;
    }

    private static boolean isUntetheredHeadset(@NonNull BluetoothDevice bluetoothDevice) {
        // The metadata is for Android R
        if (getBooleanMetaData(bluetoothDevice, BluetoothDevice.METADATA_IS_UNTETHERED_HEADSET)) {
            Log.d(TAG, "isAdvancedDetailsHeader: untetheredHeadset is true");
            return true;
        }
        return false;
    }

    /** Create an Icon pointing to a drawable. */
    public static IconCompat createIconWithDrawable(Drawable drawable) {
        Bitmap bitmap;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            final int width = drawable.getIntrinsicWidth();
            final int height = drawable.getIntrinsicHeight();
            bitmap = createBitmap(drawable, width > 0 ? width : 1, height > 0 ? height : 1);
        }
        return IconCompat.createWithBitmap(bitmap);
    }

    /** Build device icon with advanced outline */
    public static Drawable buildAdvancedDrawable(Context context, Drawable drawable) {
        final int iconSize =
                context.getResources().getDimensionPixelSize(R.dimen.advanced_icon_size);
        final Resources resources = context.getResources();

        Bitmap bitmap = null;
        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
        } else {
            final int width = drawable.getIntrinsicWidth();
            final int height = drawable.getIntrinsicHeight();
            bitmap = createBitmap(drawable, width > 0 ? width : 1, height > 0 ? height : 1);
        }

        if (bitmap != null) {
            final Bitmap resizedBitmap =
                    Bitmap.createScaledBitmap(bitmap, iconSize, iconSize, false);
            bitmap.recycle();
            return new AdaptiveOutlineDrawable(resources, resizedBitmap, ICON_TYPE_ADVANCED);
        }

        return drawable;
    }

    /** Creates a drawable with specified width and height. */
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
     * Gets string metadata from Fast Pair customized fields.
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @return the string metadata
     */
    @Nullable
    public static String getFastPairCustomizedField(
            @Nullable BluetoothDevice bluetoothDevice, @NonNull String key) {
        String data = getStringMetaData(bluetoothDevice, METADATA_FAST_PAIR_CUSTOMIZED_FIELDS);
        return extraTagValue(key, data);
    }

    /**
     * Get URI Bluetooth metadata for extra control
     *
     * @param bluetoothDevice the BluetoothDevice to get metadata
     * @return the URI metadata
     */
    public static String getControlUriMetaData(BluetoothDevice bluetoothDevice) {
        return getFastPairCustomizedField(bluetoothDevice, KEY_HEARABLE_CONTROL_SLICE);
    }

    /**
     * Check if the Bluetooth device is an AvailableMediaBluetoothDevice, which means: 1) currently
     * connected 2) is Hearing Aid or LE Audio OR 3) connected profile matches currentAudioProfile
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @param audioManager audio manager to get the current audio profile
     * @return if the device is AvailableMediaBluetoothDevice
     */
    @WorkerThread
    public static boolean isAvailableMediaBluetoothDevice(
            CachedBluetoothDevice cachedDevice, AudioManager audioManager) {
        int audioMode = audioManager.getMode();
        int currentAudioProfile;

        if (audioMode == AudioManager.MODE_RINGTONE
                || audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            // in phone call
            currentAudioProfile = BluetoothProfile.HEADSET;
        } else {
            // without phone call
            currentAudioProfile = BluetoothProfile.A2DP;
        }

        boolean isFilterMatched = false;
        if (isDeviceConnected(cachedDevice)) {
            // If device is Hearing Aid or LE Audio, it is compatible with HFP and A2DP.
            // It would show in Available Devices group.
            if (cachedDevice.isConnectedAshaHearingAidDevice()
                    || cachedDevice.isConnectedLeAudioDevice()) {
                Log.d(
                        TAG,
                        "isFilterMatched() device : "
                                + cachedDevice.getName()
                                + ", the profile is connected.");
                return true;
            }
            // According to the current audio profile type,
            // this page will show the bluetooth device that have corresponding profile.
            // For example:
            // If current audio profile is a2dp, show the bluetooth device that have a2dp profile.
            // If current audio profile is headset,
            // show the bluetooth device that have headset profile.
            switch (currentAudioProfile) {
                case BluetoothProfile.A2DP:
                    isFilterMatched = cachedDevice.isConnectedA2dpDevice();
                    break;
                case BluetoothProfile.HEADSET:
                    isFilterMatched = cachedDevice.isConnectedHfpDevice();
                    break;
            }
        }
        return isFilterMatched;
    }

    /**
     * Checks if a given `CachedBluetoothDevice` is available for audio sharing and being switch as
     * active media device.
     *
     * <p>This method determines if the device meets the following criteria to be available:
     *
     * <ol>
     *   <li>Audio sharing session is off.
     *   <li>The device is one of the two connected devices on the LE Broadcast Assistant profile.
     *   <li>The device is not currently active on the LE Audio profile.
     *   <li>There is exactly one other device that is active on the LE Audio profile.
     * </ol>
     *
     * @param cachedDevice The `CachedBluetoothDevice` to check.
     * @param localBluetoothManager The `LocalBluetoothManager` instance, or null if unavailable.
     * @return `true` if the device is available for audio sharing and settings as active, `false`
     *     otherwise.
     */
    @WorkerThread
    public static boolean isAvailableAudioSharingMediaBluetoothDevice(
            CachedBluetoothDevice cachedDevice,
            @Nullable LocalBluetoothManager localBluetoothManager) {
        LocalBluetoothLeBroadcastAssistant assistantProfile =
                Optional.ofNullable(localBluetoothManager)
                        .map(LocalBluetoothManager::getProfileManager)
                        .map(LocalBluetoothProfileManager::getLeAudioBroadcastAssistantProfile)
                        .orElse(null);
        LeAudioProfile leAudioProfile =
                Optional.ofNullable(localBluetoothManager)
                        .map(LocalBluetoothManager::getProfileManager)
                        .map(LocalBluetoothProfileManager::getLeAudioProfile)
                        .orElse(null);
        CachedBluetoothDeviceManager deviceManager =
                Optional.ofNullable(localBluetoothManager)
                        .map(LocalBluetoothManager::getCachedDeviceManager)
                        .orElse(null);
        // If any of the profiles are null, or broadcast is already on, return false
        if (assistantProfile == null
                || leAudioProfile == null
                || deviceManager == null
                || isBroadcasting(localBluetoothManager)) {
            return false;
        }
        Set<Integer> connectedGroupIds =
                assistantProfile.getAllConnectedDevices().stream()
                        .map(deviceManager::findDevice)
                        .filter(Objects::nonNull)
                        .map(CachedBluetoothDevice::getGroupId)
                        .collect(Collectors.toSet());
        Set<Integer> activeGroupIds =
                leAudioProfile.getActiveDevices().stream()
                        .map(deviceManager::findDevice)
                        .filter(Objects::nonNull)
                        .map(CachedBluetoothDevice::getGroupId)
                        .collect(Collectors.toSet());
        int groupId = cachedDevice.getGroupId();
        return activeGroupIds.size() == 1
                && !activeGroupIds.contains(groupId)
                && connectedGroupIds.size() == 2
                && connectedGroupIds.contains(groupId);
    }

    /** Returns if the le audio sharing is enabled. */
    public static boolean isAudioSharingEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        try {
            return Flags.enableLeAudioSharing()
                    && adapter.isLeAudioBroadcastSourceSupported()
                            == BluetoothStatusCodes.FEATURE_SUPPORTED
                    && adapter.isLeAudioBroadcastAssistantSupported()
                            == BluetoothStatusCodes.FEATURE_SUPPORTED;
        } catch (IllegalStateException e) {
            Log.d(TAG, "LE state is on, but there is no bluetooth service.", e);
            return false;
        }
    }

    /** Returns if the broadcast is on-going. */
    @WorkerThread
    public static boolean isBroadcasting(@Nullable LocalBluetoothManager manager) {
        if (manager == null) return false;
        LocalBluetoothLeBroadcast broadcast =
                manager.getProfileManager().getLeAudioBroadcastProfile();
        return broadcast != null && broadcast.isEnabled(null);
    }

    /**
     * Check if {@link CachedBluetoothDevice} (lead or member) has connected to a broadcast source.
     *
     * @param cachedDevice The cached bluetooth device to check.
     * @param localBtManager The BT manager to provide BT functions.
     * @return Whether the device has connected to a broadcast source.
     */
    @WorkerThread
    public static boolean hasConnectedBroadcastSource(
            @Nullable CachedBluetoothDevice cachedDevice,
            @Nullable LocalBluetoothManager localBtManager) {
        if (cachedDevice == null) return false;
        if (hasConnectedBroadcastSourceForBtDevice(cachedDevice.getDevice(), localBtManager)) {
            Log.d(
                    TAG,
                    "Lead device has connected broadcast source, device = "
                            + cachedDevice.getDevice().getAnonymizedAddress());
            return true;
        }
        // Return true if member device is in broadcast.
        for (CachedBluetoothDevice device : cachedDevice.getMemberDevice()) {
            if (hasConnectedBroadcastSourceForBtDevice(device.getDevice(), localBtManager)) {
                Log.d(
                        TAG,
                        "Member device has connected broadcast source, device = "
                                + device.getDevice().getAnonymizedAddress());
                return true;
            }
        }
        return false;
    }

    /**
     * Check if {@link BluetoothDevice} has connected to a broadcast source.
     *
     * @param device The bluetooth device to check.
     * @param localBtManager The BT manager to provide BT functions.
     * @return Whether the device has connected to a broadcast source.
     */
    @WorkerThread
    public static boolean hasConnectedBroadcastSourceForBtDevice(
            @Nullable BluetoothDevice device, @Nullable LocalBluetoothManager localBtManager) {
        LocalBluetoothLeBroadcastAssistant assistant =
                localBtManager == null
                        ? null
                        : localBtManager.getProfileManager().getLeAudioBroadcastAssistantProfile();
        if (device == null || assistant == null) {
            Log.d(TAG, "Skip check hasConnectedBroadcastSourceForBtDevice due to arg is null");
            return false;
        }
        List<BluetoothLeBroadcastReceiveState> sourceList = assistant.getAllSources(device);
        return !sourceList.isEmpty() && sourceList.stream().anyMatch(BluetoothUtils::isConnected);
    }

    /**
     * Check if {@link BluetoothDevice} has a active local broadcast source.
     *
     * @param device The bluetooth device to check.
     * @param localBtManager The BT manager to provide BT functions.
     * @return Whether the device has a active local broadcast source.
     */
    @WorkerThread
    public static boolean hasActiveLocalBroadcastSourceForBtDevice(
            @Nullable BluetoothDevice device, @Nullable LocalBluetoothManager localBtManager) {
        LocalBluetoothLeBroadcastAssistant assistant =
                localBtManager == null
                        ? null
                        : localBtManager.getProfileManager().getLeAudioBroadcastAssistantProfile();
        LocalBluetoothLeBroadcast broadcast =
                localBtManager == null
                        ? null
                        : localBtManager.getProfileManager().getLeAudioBroadcastProfile();
        if (device == null || assistant == null || broadcast == null) {
            Log.d(TAG, "Skip check hasActiveLocalBroadcastSourceForBtDevice due to arg is null");
            return false;
        }
        List<BluetoothLeBroadcastReceiveState> sourceList = assistant.getAllSources(device);
        int broadcastId = broadcast.getLatestBroadcastId();
        return !sourceList.isEmpty()
                && broadcastId != UNKNOWN_VALUE_PLACEHOLDER
                && sourceList.stream().anyMatch(source -> isSourceMatched(source, broadcastId));
    }

    /** Checks the connectivity status based on the provided broadcast receive state. */
    @WorkerThread
    public static boolean isConnected(BluetoothLeBroadcastReceiveState state) {
        return state.getBisSyncState().stream().anyMatch(bitmap -> bitmap != 0);
    }

    /** Checks if the broadcast id is matched based on the provided broadcast receive state. */
    @WorkerThread
    public static boolean isSourceMatched(
            @Nullable BluetoothLeBroadcastReceiveState state, int broadcastId) {
        return state != null && state.getBroadcastId() == broadcastId;
    }

    /**
     * Checks if the Bluetooth device is an available hearing device, which means: 1) currently
     * connected 2) is Hearing Aid 3) connected profile match hearing aid related profiles (e.g.
     * ASHA, HAP)
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @return if the device is Available hearing device
     */
    @WorkerThread
    public static boolean isAvailableHearingDevice(CachedBluetoothDevice cachedDevice) {
        if (isDeviceConnected(cachedDevice) && cachedDevice.isConnectedHearingAidDevice()) {
            Log.d(
                    TAG,
                    "isFilterMatched() device : "
                            + cachedDevice.getName()
                            + ", the profile is connected.");
            return true;
        }
        return false;
    }

    /**
     * Check if the Bluetooth device is a ConnectedBluetoothDevice, which means: 1) currently
     * connected 2) is not Hearing Aid or LE Audio AND 3) connected profile does not match
     * currentAudioProfile
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @param audioManager audio manager to get the current audio profile
     * @return if the device is AvailableMediaBluetoothDevice
     */
    @WorkerThread
    public static boolean isConnectedBluetoothDevice(
            CachedBluetoothDevice cachedDevice, AudioManager audioManager) {
        int audioMode = audioManager.getMode();
        int currentAudioProfile;

        if (audioMode == AudioManager.MODE_RINGTONE
                || audioMode == AudioManager.MODE_IN_CALL
                || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            // in phone call
            currentAudioProfile = BluetoothProfile.HEADSET;
        } else {
            // without phone call
            currentAudioProfile = BluetoothProfile.A2DP;
        }

        boolean isFilterMatched = false;
        if (isDeviceConnected(cachedDevice)) {
            // If device is Hearing Aid or LE Audio, it is compatible with HFP and A2DP.
            // It would not show in Connected Devices group.
            if (cachedDevice.isConnectedAshaHearingAidDevice()
                    || cachedDevice.isConnectedLeAudioDevice()) {
                return false;
            }
            // According to the current audio profile type,
            // this page will show the bluetooth device that doesn't have corresponding profile.
            // For example:
            // If current audio profile is a2dp,
            // show the bluetooth device that doesn't have a2dp profile.
            // If current audio profile is headset,
            // show the bluetooth device that doesn't have headset profile.
            switch (currentAudioProfile) {
                case BluetoothProfile.A2DP:
                    isFilterMatched = !cachedDevice.isConnectedA2dpDevice();
                    break;
                case BluetoothProfile.HEADSET:
                    isFilterMatched = !cachedDevice.isConnectedHfpDevice();
                    break;
            }
        }
        return isFilterMatched;
    }

    /**
     * Check if the Bluetooth device is an active media device
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @return if the Bluetooth device is an active media device
     */
    public static boolean isActiveMediaDevice(CachedBluetoothDevice cachedDevice) {
        return cachedDevice.isActiveDevice(BluetoothProfile.A2DP)
                || cachedDevice.isActiveDevice(BluetoothProfile.HEADSET)
                || cachedDevice.isActiveDevice(BluetoothProfile.HEARING_AID)
                || cachedDevice.isActiveDevice(BluetoothProfile.LE_AUDIO);
    }

    /**
     * Check if the Bluetooth device is an active LE Audio device
     *
     * @param cachedDevice the CachedBluetoothDevice
     * @return if the Bluetooth device is an active LE Audio device
     */
    public static boolean isActiveLeAudioDevice(CachedBluetoothDevice cachedDevice) {
        return cachedDevice.isActiveDevice(BluetoothProfile.LE_AUDIO);
    }

    private static boolean isDeviceConnected(CachedBluetoothDevice cachedDevice) {
        if (cachedDevice == null) {
            return false;
        }
        final BluetoothDevice device = cachedDevice.getDevice();
        return device.getBondState() == BluetoothDevice.BOND_BONDED && device.isConnected();
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

    /**
     * Returns the BluetoothDevice's exclusive manager ({@link
     * BluetoothDevice.METADATA_EXCLUSIVE_MANAGER} in metadata) if it exists, otherwise null.
     */
    @Nullable
    private static String getExclusiveManager(BluetoothDevice bluetoothDevice) {
        byte[] exclusiveManagerBytes =
                bluetoothDevice.getMetadata(BluetoothDevice.METADATA_EXCLUSIVE_MANAGER);
        if (exclusiveManagerBytes == null) {
            Log.d(
                    TAG,
                    "Bluetooth device "
                            + bluetoothDevice.getName()
                            + " doesn't have exclusive manager");
            return null;
        }
        return new String(exclusiveManagerBytes);
    }

    /** Checks if given package is installed and enabled */
    private static boolean isPackageInstalledAndEnabled(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
            return appInfo.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Package " + packageName + " is not installed/enabled");
        }
        return false;
    }

    /**
     * A BluetoothDevice is exclusively managed if 1) it has field {@link
     * BluetoothDevice.METADATA_EXCLUSIVE_MANAGER} in metadata. 2) the exclusive manager app is
     * installed and enabled.
     */
    public static boolean isExclusivelyManagedBluetoothDevice(
            @NonNull Context context, @NonNull BluetoothDevice bluetoothDevice) {
        String exclusiveManagerName = getExclusiveManager(bluetoothDevice);
        if (exclusiveManagerName == null) {
            return false;
        }

        ComponentName exclusiveManagerComponent =
                ComponentName.unflattenFromString(exclusiveManagerName);
        String exclusiveManagerPackage =
                exclusiveManagerComponent != null
                        ? exclusiveManagerComponent.getPackageName()
                        : exclusiveManagerName;

        if (!isPackageInstalledAndEnabled(context, exclusiveManagerPackage)) {
            return false;
        } else {
            Log.d(TAG, "Found exclusively managed app " + exclusiveManagerPackage);
            return true;
        }
    }

    /**
     * Get CSIP group id for {@link CachedBluetoothDevice}.
     *
     * <p>If CachedBluetoothDevice#getGroupId is invalid, fetch group id from
     * LeAudioProfile#getGroupId.
     */
    public static int getGroupId(@Nullable CachedBluetoothDevice cachedDevice) {
        if (cachedDevice == null) return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
        int groupId = cachedDevice.getGroupId();
        String anonymizedAddress = cachedDevice.getDevice().getAnonymizedAddress();
        if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID) {
            Log.d(TAG, "getGroupId by CSIP profile for device: " + anonymizedAddress);
            return groupId;
        }
        for (LocalBluetoothProfile profile : cachedDevice.getProfiles()) {
            if (profile instanceof LeAudioProfile) {
                Log.d(TAG, "getGroupId by LEA profile for device: " + anonymizedAddress);
                return ((LeAudioProfile) profile).getGroupId(cachedDevice.getDevice());
            }
        }
        Log.d(TAG, "getGroupId return invalid id for device: " + anonymizedAddress);
        return BluetoothCsipSetCoordinator.GROUP_ID_INVALID;
    }

    /** Get primary device Uri in broadcast. */
    @NonNull
    public static String getPrimaryGroupIdUriForBroadcast() {
        return "bluetooth_le_broadcast_fallback_active_group_id";
    }

    /** Get primary device group id in broadcast. */
    @WorkerThread
    public static int getPrimaryGroupIdForBroadcast(@NonNull ContentResolver contentResolver) {
        return Settings.Secure.getInt(
                contentResolver,
                getPrimaryGroupIdUriForBroadcast(),
                BluetoothCsipSetCoordinator.GROUP_ID_INVALID);
    }

    /** Get secondary {@link CachedBluetoothDevice} in broadcast. */
    @Nullable
    @WorkerThread
    public static CachedBluetoothDevice getSecondaryDeviceForBroadcast(
            @NonNull ContentResolver contentResolver,
            @Nullable LocalBluetoothManager localBtManager) {
        if (localBtManager == null) return null;
        LocalBluetoothLeBroadcast broadcast =
                localBtManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null || !broadcast.isEnabled(null)) return null;
        int primaryGroupId = getPrimaryGroupIdForBroadcast(contentResolver);
        if (primaryGroupId == BluetoothCsipSetCoordinator.GROUP_ID_INVALID) return null;
        LocalBluetoothLeBroadcastAssistant assistant =
                localBtManager.getProfileManager().getLeAudioBroadcastAssistantProfile();
        CachedBluetoothDeviceManager deviceManager = localBtManager.getCachedDeviceManager();
        List<BluetoothDevice> devices = assistant.getAllConnectedDevices();
        for (BluetoothDevice device : devices) {
            CachedBluetoothDevice cachedDevice = deviceManager.findDevice(device);
            if (hasConnectedBroadcastSource(cachedDevice, localBtManager)) {
                int groupId = getGroupId(cachedDevice);
                if (groupId != BluetoothCsipSetCoordinator.GROUP_ID_INVALID
                        && groupId != primaryGroupId) {
                    return cachedDevice;
                }
            }
        }
        return null;
    }

    /**
     * Gets {@link AudioDeviceAttributes} of bluetooth device for spatial audio. Returns null if
     * it's not an audio device(no A2DP, LE Audio and Hearing Aid profile).
     */
    @Nullable
    public static AudioDeviceAttributes getAudioDeviceAttributesForSpatialAudio(
            CachedBluetoothDevice cachedDevice,
            @AudioManager.AudioDeviceCategory int audioDeviceCategory) {
        AudioDeviceAttributes saDevice = null;
        for (LocalBluetoothProfile profile : cachedDevice.getProfiles()) {
            // pick first enabled profile that is compatible with spatial audio
            if (SA_PROFILES.contains(profile.getProfileId())
                    && profile.isEnabled(cachedDevice.getDevice())) {
                switch (profile.getProfileId()) {
                    case BluetoothProfile.A2DP:
                        saDevice =
                                new AudioDeviceAttributes(
                                        AudioDeviceAttributes.ROLE_OUTPUT,
                                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                                        cachedDevice.getAddress());
                        break;
                    case BluetoothProfile.LE_AUDIO:
                        if (audioDeviceCategory == AudioManager.AUDIO_DEVICE_CATEGORY_SPEAKER) {
                            saDevice =
                                    new AudioDeviceAttributes(
                                            AudioDeviceAttributes.ROLE_OUTPUT,
                                            AudioDeviceInfo.TYPE_BLE_SPEAKER,
                                            cachedDevice.getAddress());
                        } else {
                            saDevice =
                                    new AudioDeviceAttributes(
                                            AudioDeviceAttributes.ROLE_OUTPUT,
                                            AudioDeviceInfo.TYPE_BLE_HEADSET,
                                            cachedDevice.getAddress());
                        }

                        break;
                    case BluetoothProfile.HEARING_AID:
                        saDevice =
                                new AudioDeviceAttributes(
                                        AudioDeviceAttributes.ROLE_OUTPUT,
                                        AudioDeviceInfo.TYPE_HEARING_AID,
                                        cachedDevice.getAddress());
                        break;
                    default:
                        Log.i(
                                TAG,
                                "unrecognized profile for spatial audio: "
                                        + profile.getProfileId());
                        break;
                }
                break;
            }
        }
        return saDevice;
    }
}
