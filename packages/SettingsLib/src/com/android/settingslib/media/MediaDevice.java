/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.settingslib.media;

import static android.media.MediaRoute2Info.TYPE_BLE_HEADSET;
import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_DOCK;
import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_HDMI;
import static android.media.MediaRoute2Info.TYPE_HDMI_ARC;
import static android.media.MediaRoute2Info.TYPE_HDMI_EARC;
import static android.media.MediaRoute2Info.TYPE_HEARING_AID;
import static android.media.MediaRoute2Info.TYPE_REMOTE_AUDIO_VIDEO_RECEIVER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_UNKNOWN;
import static android.media.MediaRoute2Info.TYPE_USB_ACCESSORY;
import static android.media.MediaRoute2Info.TYPE_USB_DEVICE;
import static android.media.MediaRoute2Info.TYPE_USB_HEADSET;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;
import static android.media.MediaRoute2Info.TYPE_LINE_DIGITAL;
import static android.media.MediaRoute2Info.TYPE_LINE_ANALOG;
import static android.media.MediaRoute2Info.TYPE_AUX_LINE;
import static android.media.RouteListingPreference.Item.FLAG_ONGOING_SESSION;
import static android.media.RouteListingPreference.Item.FLAG_ONGOING_SESSION_MANAGED;
import static android.media.RouteListingPreference.Item.FLAG_SUGGESTED;
import static android.media.RouteListingPreference.Item.SUBTEXT_AD_ROUTING_DISALLOWED;
import static android.media.RouteListingPreference.Item.SUBTEXT_CUSTOM;
import static android.media.RouteListingPreference.Item.SUBTEXT_DEVICE_LOW_POWER;
import static android.media.RouteListingPreference.Item.SUBTEXT_DOWNLOADED_CONTENT_ROUTING_DISALLOWED;
import static android.media.RouteListingPreference.Item.SUBTEXT_ERROR_UNKNOWN;
import static android.media.RouteListingPreference.Item.SUBTEXT_NONE;
import static android.media.RouteListingPreference.Item.SUBTEXT_SUBSCRIPTION_REQUIRED;
import static android.media.RouteListingPreference.Item.SUBTEXT_TRACK_UNSUPPORTED;
import static android.media.RouteListingPreference.Item.SUBTEXT_UNAUTHORIZED;

import static com.android.settingslib.media.LocalMediaManager.MediaDeviceState.STATE_SELECTED;
import static com.android.settingslib.media.MediaDevice.SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaRoute2Info;
import android.media.NearbyDevice;
import android.media.RouteListingPreference;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.DoNotInline;
import androidx.annotation.IntDef;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.settingslib.R;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * MediaDevice represents a media device(such like Bluetooth device, cast device and phone device).
 */
public abstract class MediaDevice implements Comparable<MediaDevice> {
    private static final String TAG = "MediaDevice";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceType.TYPE_UNKNOWN,
            MediaDeviceType.TYPE_PHONE_DEVICE,
            MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE,
            MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE,
            MediaDeviceType.TYPE_FAST_PAIR_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_BLUETOOTH_DEVICE,
            MediaDeviceType.TYPE_CAST_DEVICE,
            MediaDeviceType.TYPE_CAST_GROUP_DEVICE,
            MediaDeviceType.TYPE_REMOTE_AUDIO_VIDEO_RECEIVER})
    public @interface MediaDeviceType {
        int TYPE_UNKNOWN = 0;
        int TYPE_PHONE_DEVICE = 1;
        int TYPE_USB_C_AUDIO_DEVICE = 2;
        int TYPE_3POINT5_MM_AUDIO_DEVICE = 3;
        int TYPE_FAST_PAIR_BLUETOOTH_DEVICE = 4;
        int TYPE_BLUETOOTH_DEVICE = 5;
        int TYPE_CAST_DEVICE = 6;
        int TYPE_CAST_GROUP_DEVICE = 7;
        int TYPE_REMOTE_AUDIO_VIDEO_RECEIVER = 8;
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SelectionBehavior.SELECTION_BEHAVIOR_NONE,
            SelectionBehavior.SELECTION_BEHAVIOR_TRANSFER,
            SelectionBehavior.SELECTION_BEHAVIOR_GO_TO_APP
    })
    public @interface SelectionBehavior {
        int SELECTION_BEHAVIOR_NONE = 0;
        int SELECTION_BEHAVIOR_TRANSFER = 1;
        int SELECTION_BEHAVIOR_GO_TO_APP = 2;
    }

    @VisibleForTesting
    int mType;

    private int mConnectedRecord;
    private int mState;
    @NearbyDevice.RangeZone
    private int mRangeZone = NearbyDevice.RANGE_UNKNOWN;

    protected final Context mContext;
    protected final MediaRoute2Info mRouteInfo;
    protected final RouteListingPreference.Item mItem;

    MediaDevice(
            @NonNull Context context,
            @Nullable MediaRoute2Info info,
            @Nullable RouteListingPreference.Item item) {
        mContext = context;
        mRouteInfo = info;
        mItem = item;
        setType(info);
    }

    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    private void setType(MediaRoute2Info info) {
        if (info == null) {
            mType = MediaDeviceType.TYPE_BLUETOOTH_DEVICE;
            return;
        }
        switch (info.getType()) {
            case TYPE_GROUP:
                mType = MediaDeviceType.TYPE_CAST_GROUP_DEVICE;
                break;
            case TYPE_BUILTIN_SPEAKER:
                mType = MediaDeviceType.TYPE_PHONE_DEVICE;
                break;
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
            case TYPE_LINE_DIGITAL:
            case TYPE_LINE_ANALOG:
            case TYPE_AUX_LINE:
                mType = MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE;
                break;
            case TYPE_USB_DEVICE:
            case TYPE_USB_HEADSET:
            case TYPE_USB_ACCESSORY:
            case TYPE_DOCK:
            case TYPE_HDMI:
            case TYPE_HDMI_ARC:
            case TYPE_HDMI_EARC:
                mType = MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE;
                break;
            case TYPE_HEARING_AID:
            case TYPE_BLUETOOTH_A2DP:
            case TYPE_BLE_HEADSET:
                mType = MediaDeviceType.TYPE_BLUETOOTH_DEVICE;
                break;
            case TYPE_REMOTE_AUDIO_VIDEO_RECEIVER:
                mType = MediaDeviceType.TYPE_REMOTE_AUDIO_VIDEO_RECEIVER;
                break;
            case TYPE_UNKNOWN:
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            default:
                mType = MediaDeviceType.TYPE_CAST_DEVICE;
                break;
        }
    }

    void initDeviceRecord() {
        ConnectionRecordManager.getInstance().fetchLastSelectedDevice(mContext);
        mConnectedRecord = ConnectionRecordManager.getInstance().fetchConnectionRecord(mContext,
                getId());
    }

    public @NearbyDevice.RangeZone int getRangeZone() {
        return mRangeZone;
    }

    public void setRangeZone(@NearbyDevice.RangeZone int rangeZone) {
        mRangeZone = rangeZone;
    }

    /**
     * Get name from MediaDevice.
     *
     * @return name of MediaDevice.
     */
    public abstract String getName();

    /**
     * Get summary from MediaDevice.
     *
     * @return summary of MediaDevice.
     */
    public abstract String getSummary();

    /**
     * Get summary from MediaDevice for TV with low batter states in a different color if
     * applicable.
     *
     * @param lowBatteryColorRes Color resource for the part of the CharSequence that describes a
     *                           low battery state.
     */
    public CharSequence getSummaryForTv(int lowBatteryColorRes) {
        return getSummary();
    }

    /**
     * Get icon of MediaDevice.
     *
     * @return drawable of icon.
     */
    public abstract Drawable getIcon();

    /**
     * Get icon of MediaDevice without background.
     *
     * @return drawable of icon
     */
    public abstract Drawable getIconWithoutBackground();

    /**
     * Get unique ID that represent MediaDevice
     *
     * @return unique id of MediaDevice
     */
    public abstract String getId();

    /**
     * Get selection behavior of device
     *
     * @return selection behavior of device
     */
    @SelectionBehavior
    public int getSelectionBehavior() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mItem != null
                ? mItem.getSelectionBehavior() : SELECTION_BEHAVIOR_TRANSFER;
    }

    /**
     * Checks if device is has subtext
     *
     * @return true if device has subtext
     */
    public boolean hasSubtext() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && mItem != null
                && mItem.getSubText() != SUBTEXT_NONE;
    }

    /**
     * Get subtext of device
     *
     * @return subtext of device
     */
    @RouteListingPreference.Item.SubText
    public int getSubtext() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mItem != null
                ? mItem.getSubText() : SUBTEXT_NONE;
    }

    /**
     * Returns subtext string for current route.
     *
     * @return subtext string for this route
     */
    public String getSubtextString() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && mItem != null
                ? Api34Impl.composeSubtext(mItem, mContext) : null;
    }

    /**
     * Checks if device has ongoing shared session, which allow user to join
     *
     * @return true if device has ongoing session
     */
    public boolean hasOngoingSession() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && Api34Impl.hasOngoingSession(mItem);
    }

    /**
     * Checks if device is the host for ongoing shared session, which allow user to adjust volume
     *
     * @return true if device is the host for ongoing shared session
     */
    public boolean isHostForOngoingSession() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && Api34Impl.isHostForOngoingSession(mItem);
    }

    /**
     * Checks if device is suggested device from application
     *
     * @return true if device is suggested device
     */
    public boolean isSuggestedDevice() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                && Api34Impl.isSuggestedDevice(mItem);
    }

    void setConnectedRecord() {
        mConnectedRecord++;
        ConnectionRecordManager.getInstance().setConnectionRecord(mContext, getId(),
                mConnectedRecord);
    }

    /**
     * According the MediaDevice type to check whether we are connected to this MediaDevice.
     *
     * @return Whether it is connected.
     */
    public abstract boolean isConnected();

    /**
     * Get max volume from MediaDevice.
     *
     * @return max volume.
     */
    public int getMaxVolume() {
        if (mRouteInfo == null) {
            Log.w(TAG, "Unable to get max volume. RouteInfo is empty");
            return 0;
        }
        return mRouteInfo.getVolumeMax();
    }

    /**
     * Get current volume from MediaDevice.
     *
     * @return current volume.
     */
    public int getCurrentVolume() {
        if (mRouteInfo == null) {
            Log.w(TAG, "Unable to get current volume. RouteInfo is empty");
            return 0;
        }
        return mRouteInfo.getVolume();
    }

    /**
     * Get application package name.
     *
     * @return package name.
     */
    public String getClientPackageName() {
        if (mRouteInfo == null) {
            Log.w(TAG, "Unable to get client package name. RouteInfo is empty");
            return null;
        }
        return mRouteInfo.getClientPackageName();
    }

    /**
     * Check if the device is Bluetooth LE Audio device.
     *
     * @return true if the RouteInfo equals TYPE_BLE_HEADSET.
     */
    // MediaRoute2Info.getType was made public on API 34, but exists since API 30.
    @SuppressWarnings("NewApi")
    public boolean isBLEDevice() {
        return mRouteInfo.getType() == TYPE_BLE_HEADSET;
    }

    /**
     * Get application label from MediaDevice.
     *
     * @return application label.
     */
    public int getDeviceType() {
        return mType;
    }

    /**
     * Checks if route's volume is fixed, if true, we should disable volume control for the device.
     *
     * @return route for this device is fixed.
     */
    @SuppressLint("NewApi")
    public boolean isVolumeFixed() {
        if (mRouteInfo == null) {
            Log.w(TAG, "RouteInfo is empty, regarded as volume fixed.");
            return true;
        }
        return mRouteInfo.getVolumeHandling() == MediaRoute2Info.PLAYBACK_VOLUME_FIXED;
    }

    /**
     * Set current device's state
     */
    public void setState(@LocalMediaManager.MediaDeviceState int state) {
        mState = state;
    }

    /**
     * Get current device's state
     *
     * @return state of device
     */
    public @LocalMediaManager.MediaDeviceState int getState() {
        return mState;
    }

    /**
     * Rules:
     * 1. If there is one of the connected devices identified as a carkit or fast pair device,
     * the fast pair device will be always on the first of the device list and carkit will be
     * second. Rule 2 and Rule 3 can’t overrule this rule.
     * 2. For devices without any usage data yet
     * WiFi device group sorted by alphabetical order + BT device group sorted by alphabetical
     * order + phone speaker
     * 3. For devices with usage record.
     * The most recent used one + device group with usage info sorted by how many times the
     * device has been used.
     * 4. The order is followed below rule:
     *    1. Phone
     *    2. USB-C audio device
     *    3. 3.5 mm audio device
     *    4. Bluetooth device
     *    5. Cast device
     *    6. Cast group device
     *
     * So the device list will look like 5 slots ranked as below.
     * Rule 4 + Rule 1 + the most recently used device + Rule 3 + Rule 2
     * Any slot could be empty. And available device will belong to one of the slots.
     *
     * @return a negative integer, zero, or a positive integer
     * as this object is less than, equal to, or greater than the specified object.
     */
    @Override
    public int compareTo(MediaDevice another) {
        if (another == null) {
            return -1;
        }
        // Check Bluetooth device is have same connection state
        if (isConnected() ^ another.isConnected()) {
            if (isConnected()) {
                return -1;
            } else {
                return 1;
            }
        }

        if (getState() == STATE_SELECTED) {
            return -1;
        } else if (another.getState() == STATE_SELECTED) {
            return 1;
        }

        if (mType == another.mType) {
            // Check device is muting expected device
            if (isMutingExpectedDevice()) {
                return -1;
            } else if (another.isMutingExpectedDevice()) {
                return 1;
            }

            // Check fast pair device
            if (isFastPairDevice()) {
                return -1;
            } else if (another.isFastPairDevice()) {
                return 1;
            }

            // Check carkit
            if (isCarKitDevice()) {
                return -1;
            } else if (another.isCarKitDevice()) {
                return 1;
            }

            // Both devices have same connection status and type, compare the range zone
            if (NearbyDevice.compareRangeZones(getRangeZone(), another.getRangeZone()) != 0) {
                return NearbyDevice.compareRangeZones(getRangeZone(), another.getRangeZone());
            }

            // Set last used device at the first item
            final String lastSelectedDevice = ConnectionRecordManager.getInstance()
                    .getLastSelectedDevice();
            if (TextUtils.equals(lastSelectedDevice, getId())) {
                return -1;
            } else if (TextUtils.equals(lastSelectedDevice, another.getId())) {
                return 1;
            }
            // Sort by how many times the device has been used if there is usage record
            if ((mConnectedRecord != another.mConnectedRecord)
                    && (another.mConnectedRecord > 0 || mConnectedRecord > 0)) {
                return (another.mConnectedRecord - mConnectedRecord);
            }

            // Both devices have never been used
            // To devices with the same type, sort by alphabetical order
            final String s1 = getName();
            final String s2 = another.getName();
            return s1.compareToIgnoreCase(s2);
        } else {
            // Both devices have never been used, the priority is:
            // 1. Phone
            // 2. USB-C audio device
            // 3. 3.5 mm audio device
            // 4. Bluetooth device
            // 5. Cast device
            // 6. Cast group device
            return mType < another.mType ? -1 : 1;
        }
    }

    /**
     * Gets the supported features of the route.
     */
    public List<String> getFeatures() {
        if (mRouteInfo == null) {
            Log.w(TAG, "Unable to get features. RouteInfo is empty");
            return new ArrayList<>();
        }
        return mRouteInfo.getFeatures();
    }

    /**
     * Check if it is CarKit device
     * @return true if it is CarKit device
     */
    protected boolean isCarKitDevice() {
        return false;
    }

    /**
     * Check if it is FastPair device
     * @return {@code true} if it is FastPair device, otherwise return {@code false}
     */
    protected boolean isFastPairDevice() {
        return false;
    }

    /**
     * Check if it is muting expected device
     * @return {@code true} if it is muting expected device, otherwise return {@code false}
     */
    public boolean isMutingExpectedDevice() {
        return false;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof MediaDevice)) {
            return false;
        }
        final MediaDevice otherDevice = (MediaDevice) obj;
        return otherDevice.getId().equals(getId());
    }

    @RequiresApi(34)
    private static class Api34Impl {
        @DoNotInline
        static boolean isHostForOngoingSession(RouteListingPreference.Item item) {
            int flags = item != null ? item.getFlags() : 0;
            return (flags & FLAG_ONGOING_SESSION) != 0
                    && (flags & FLAG_ONGOING_SESSION_MANAGED) != 0;
        }

        @DoNotInline
        static boolean isSuggestedDevice(RouteListingPreference.Item item) {
            return item != null && (item.getFlags() & FLAG_SUGGESTED) != 0;
        }

        @DoNotInline
        static boolean hasOngoingSession(RouteListingPreference.Item item) {
            return item != null && (item.getFlags() & FLAG_ONGOING_SESSION) != 0;
        }

        @DoNotInline
        static String composeSubtext(RouteListingPreference.Item item, Context context) {
            switch (item.getSubText()) {
                case SUBTEXT_ERROR_UNKNOWN:
                    return context.getString(R.string.media_output_status_unknown_error);
                case SUBTEXT_SUBSCRIPTION_REQUIRED:
                    return context.getString(R.string.media_output_status_require_premium);
                case SUBTEXT_DOWNLOADED_CONTENT_ROUTING_DISALLOWED:
                    return context.getString(R.string.media_output_status_not_support_downloads);
                case SUBTEXT_AD_ROUTING_DISALLOWED:
                    return context.getString(R.string.media_output_status_try_after_ad);
                case SUBTEXT_DEVICE_LOW_POWER:
                    return context.getString(R.string.media_output_status_device_in_low_power_mode);
                case SUBTEXT_UNAUTHORIZED:
                    return context.getString(R.string.media_output_status_unauthorized);
                case SUBTEXT_TRACK_UNSUPPORTED:
                    return context.getString(R.string.media_output_status_track_unsupported);
                case SUBTEXT_CUSTOM:
                    return (String) item.getCustomSubtextMessage();
            }
            return "";
        }
    }
}
