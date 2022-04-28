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

import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.RoutingSessionInfo;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.A2dpProfile;
import com.android.settingslib.bluetooth.BluetoothCallback;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.CachedBluetoothDeviceManager;
import com.android.settingslib.bluetooth.HearingAidProfile;
import com.android.settingslib.bluetooth.LeAudioProfile;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.bluetooth.LocalBluetoothProfile;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * LocalMediaManager provide interface to get MediaDevice list and transfer media to MediaDevice.
 */
@RequiresApi(Build.VERSION_CODES.R)
public class LocalMediaManager implements BluetoothCallback {
    private static final Comparator<MediaDevice> COMPARATOR = Comparator.naturalOrder();
    private static final String TAG = "LocalMediaManager";
    private static final int MAX_DISCONNECTED_DEVICE_NUM = 5;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({MediaDeviceState.STATE_CONNECTED,
            MediaDeviceState.STATE_CONNECTING,
            MediaDeviceState.STATE_DISCONNECTED,
            MediaDeviceState.STATE_CONNECTING_FAILED,
            MediaDeviceState.STATE_SELECTED})
    public @interface MediaDeviceState {
        int STATE_CONNECTED = 0;
        int STATE_CONNECTING = 1;
        int STATE_DISCONNECTED = 2;
        int STATE_CONNECTING_FAILED = 3;
        int STATE_SELECTED = 4;
    }

    private final Collection<DeviceCallback> mCallbacks = new CopyOnWriteArrayList<>();
    private final Object mMediaDevicesLock = new Object();
    @VisibleForTesting
    final MediaDeviceCallback mMediaDeviceCallback = new MediaDeviceCallback();

    private Context mContext;
    private LocalBluetoothManager mLocalBluetoothManager;
    private InfoMediaManager mInfoMediaManager;
    private String mPackageName;
    private MediaDevice mOnTransferBluetoothDevice;

    @VisibleForTesting
    List<MediaDevice> mMediaDevices = new CopyOnWriteArrayList<>();
    @VisibleForTesting
    List<MediaDevice> mDisconnectedMediaDevices = new CopyOnWriteArrayList<>();
    @VisibleForTesting
    MediaDevice mPhoneDevice;
    @VisibleForTesting
    MediaDevice mCurrentConnectedDevice;
    @VisibleForTesting
    DeviceAttributeChangeCallback mDeviceAttributeChangeCallback =
            new DeviceAttributeChangeCallback();
    @VisibleForTesting
    BluetoothAdapter mBluetoothAdapter;

    /**
     * Register to start receiving callbacks for MediaDevice events.
     */
    public void registerCallback(DeviceCallback callback) {
        mCallbacks.add(callback);
    }

    /**
     * Unregister to stop receiving callbacks for MediaDevice events
     */
    public void unregisterCallback(DeviceCallback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Creates a LocalMediaManager with references to given managers.
     *
     * It will obtain a {@link LocalBluetoothManager} by calling
     * {@link LocalBluetoothManager#getInstance} and create an {@link InfoMediaManager} passing
     * that bluetooth manager.
     *
     * It will use {@link BluetoothAdapter#getDefaultAdapter()] for setting the bluetooth adapter.
     */
    public LocalMediaManager(Context context, String packageName, Notification notification) {
        mContext = context;
        mPackageName = packageName;
        mLocalBluetoothManager =
                LocalBluetoothManager.getInstance(context, /* onInitCallback= */ null);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mLocalBluetoothManager == null) {
            Log.e(TAG, "Bluetooth is not supported on this device");
            return;
        }

        mInfoMediaManager =
                new InfoMediaManager(context, packageName, notification, mLocalBluetoothManager);
    }

    /**
     * Creates a LocalMediaManager with references to given managers.
     *
     * It will use {@link BluetoothAdapter#getDefaultAdapter()] for setting the bluetooth adapter.
     */
    public LocalMediaManager(Context context, LocalBluetoothManager localBluetoothManager,
            InfoMediaManager infoMediaManager, String packageName) {
        mContext = context;
        mLocalBluetoothManager = localBluetoothManager;
        mInfoMediaManager = infoMediaManager;
        mPackageName = packageName;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    /**
     * Connect the MediaDevice to transfer media
     * @param connectDevice the MediaDevice
     * @return {@code true} if successfully call, otherwise return {@code false}
     */
    public boolean connectDevice(MediaDevice connectDevice) {
        MediaDevice device = null;
        synchronized (mMediaDevicesLock) {
            device = getMediaDeviceById(mMediaDevices, connectDevice.getId());
        }
        if (device == null) {
            Log.w(TAG, "connectDevice() connectDevice not in the list!");
            return false;
        }
        if (device instanceof BluetoothMediaDevice) {
            final CachedBluetoothDevice cachedDevice =
                    ((BluetoothMediaDevice) device).getCachedDevice();
            if (!cachedDevice.isConnected() && !cachedDevice.isBusy()) {
                mOnTransferBluetoothDevice = connectDevice;
                device.setState(MediaDeviceState.STATE_CONNECTING);
                cachedDevice.connect();
                return true;
            }
        }

        if (device.equals(mCurrentConnectedDevice)) {
            Log.d(TAG, "connectDevice() this device is already connected! : " + device.getName());
            return false;
        }

        if (mCurrentConnectedDevice != null) {
            mCurrentConnectedDevice.disconnect();
        }

        device.setState(MediaDeviceState.STATE_CONNECTING);
        if (TextUtils.isEmpty(mPackageName)) {
            mInfoMediaManager.connectDeviceWithoutPackageName(device);
        } else {
            device.connect();
        }
        return true;
    }

    void dispatchSelectedDeviceStateChanged(MediaDevice device, @MediaDeviceState int state) {
        for (DeviceCallback callback : getCallbacks()) {
            callback.onSelectedDeviceStateChanged(device, state);
        }
    }

    /**
     * Returns if the media session is available for volume control.
     * @return True if this media session is available for colume control, false otherwise.
     */
    public boolean isMediaSessionAvailableForVolumeControl() {
        return mInfoMediaManager.isRoutingSessionAvailableForVolumeControl();
    }

    /**
     * Start scan connected MediaDevice
     */
    public void startScan() {
        synchronized (mMediaDevicesLock) {
            mMediaDevices.clear();
        }
        mInfoMediaManager.registerCallback(mMediaDeviceCallback);
        mInfoMediaManager.startScan();
    }

    void dispatchDeviceListUpdate() {
        final List<MediaDevice> mediaDevices = new ArrayList<>(mMediaDevices);
        for (DeviceCallback callback : getCallbacks()) {
            callback.onDeviceListUpdate(mediaDevices);
        }
    }

    void dispatchDeviceAttributesChanged() {
        for (DeviceCallback callback : getCallbacks()) {
            callback.onDeviceAttributesChanged();
        }
    }

    void dispatchOnRequestFailed(int reason) {
        for (DeviceCallback callback : getCallbacks()) {
            callback.onRequestFailed(reason);
        }
    }

    /**
     * Dispatch a change in the about-to-connect device. See
     * {@link DeviceCallback#onAboutToConnectDeviceAdded} for more information.
     */
    public void dispatchAboutToConnectDeviceAdded(
            @NonNull String deviceAddress,
            @NonNull String deviceName,
            @Nullable Drawable deviceIcon) {
        for (DeviceCallback callback : getCallbacks()) {
            callback.onAboutToConnectDeviceAdded(deviceAddress, deviceName, deviceIcon);
        }
    }

    /**
     * Dispatch a change in the about-to-connect device. See
     * {@link DeviceCallback#onAboutToConnectDeviceRemoved} for more information.
     */
    public void dispatchAboutToConnectDeviceRemoved() {
        for (DeviceCallback callback : getCallbacks()) {
            callback.onAboutToConnectDeviceRemoved();
        }
    }

    /**
     * Stop scan MediaDevice
     */
    public void stopScan() {
        mInfoMediaManager.unregisterCallback(mMediaDeviceCallback);
        mInfoMediaManager.stopScan();
        unRegisterDeviceAttributeChangeCallback();
    }

    /**
     * Find the MediaDevice through id.
     *
     * @param devices the list of MediaDevice
     * @param id the unique id of MediaDevice
     * @return MediaDevice
     */
    public MediaDevice getMediaDeviceById(List<MediaDevice> devices, String id) {
        for (MediaDevice mediaDevice : devices) {
            if (TextUtils.equals(mediaDevice.getId(), id)) {
                return mediaDevice;
            }
        }
        Log.i(TAG, "getMediaDeviceById() can't found device");
        return null;
    }

    /**
     * Find the MediaDevice from all media devices by id.
     *
     * @param id the unique id of MediaDevice
     * @return MediaDevice
     */
    public MediaDevice getMediaDeviceById(String id) {
        synchronized (mMediaDevicesLock) {
            for (MediaDevice mediaDevice : mMediaDevices) {
                if (TextUtils.equals(mediaDevice.getId(), id)) {
                    return mediaDevice;
                }
            }
        }
        Log.i(TAG, "Unable to find device " + id);
        return null;
    }

    /**
     * Find the current connected MediaDevice.
     *
     * @return MediaDevice
     */
    @Nullable
    public MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    /**
     * Add a MediaDevice to let it play current media.
     *
     * @param device MediaDevice
     * @return If add device successful return {@code true}, otherwise return {@code false}
     */
    public boolean addDeviceToPlayMedia(MediaDevice device) {
        return mInfoMediaManager.addDeviceToPlayMedia(device);
    }

    /**
     * Remove a {@code device} from current media.
     *
     * @param device MediaDevice
     * @return If device stop successful return {@code true}, otherwise return {@code false}
     */
    public boolean removeDeviceFromPlayMedia(MediaDevice device) {
        return mInfoMediaManager.removeDeviceFromPlayMedia(device);
    }

    /**
     * Get the MediaDevice list that can be added to current media.
     *
     * @return list of MediaDevice
     */
    public List<MediaDevice> getSelectableMediaDevice() {
        return mInfoMediaManager.getSelectableMediaDevice();
    }

    /**
     * Get the MediaDevice list that can be removed from current media session.
     *
     * @return list of MediaDevice
     */
    public List<MediaDevice> getDeselectableMediaDevice() {
        return mInfoMediaManager.getDeselectableMediaDevice();
    }

    /**
     * Release session to stop playing media on MediaDevice.
     */
    public boolean releaseSession() {
        return mInfoMediaManager.releaseSession();
    }

    /**
     * Get the MediaDevice list that has been selected to current media.
     *
     * @return list of MediaDevice
     */
    public List<MediaDevice> getSelectedMediaDevice() {
        return mInfoMediaManager.getSelectedMediaDevice();
    }

    /**
     * Adjust the volume of session.
     *
     * @param sessionId the value of media session id
     * @param volume the value of volume
     */
    public void adjustSessionVolume(String sessionId, int volume) {
        final List<RoutingSessionInfo> infos = getActiveMediaSession();
        for (RoutingSessionInfo info : infos) {
            if (TextUtils.equals(sessionId, info.getId())) {
                mInfoMediaManager.adjustSessionVolume(info, volume);
                return;
            }
        }
        Log.w(TAG, "adjustSessionVolume: Unable to find session: " + sessionId);
    }

    /**
     * Adjust the volume of session.
     *
     * @param volume the value of volume
     */
    public void adjustSessionVolume(int volume) {
        mInfoMediaManager.adjustSessionVolume(volume);
    }

    /**
     * Gets the maximum volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return  maximum volume of the session, and return -1 if not found.
     */
    public int getSessionVolumeMax() {
        return mInfoMediaManager.getSessionVolumeMax();
    }

    /**
     * Gets the current volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return current volume of the session, and return -1 if not found.
     */
    public int getSessionVolume() {
        return mInfoMediaManager.getSessionVolume();
    }

    /**
     * Gets the user-visible name of the {@link android.media.RoutingSessionInfo}.
     *
     * @return current name of the session, and return {@code null} if not found.
     */
    public CharSequence getSessionName() {
        return mInfoMediaManager.getSessionName();
    }

    /**
     * Gets the current active session.
     *
     * @return current active session list{@link android.media.RoutingSessionInfo}
     */
    public List<RoutingSessionInfo> getActiveMediaSession() {
        return mInfoMediaManager.getActiveMediaSession();
    }

    /**
     * Gets the current package name.
     *
     * @return current package name
     */
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Returns {@code true} if needed to disable media output, otherwise returns {@code false}.
     */
    public boolean shouldDisableMediaOutput(String packageName) {
        return mInfoMediaManager.shouldDisableMediaOutput(packageName);
    }

    /**
     * Returns {@code true} if needed to enable volume seekbar, otherwise returns {@code false}.
     */
    public boolean shouldEnableVolumeSeekBar(RoutingSessionInfo sessionInfo) {
        return mInfoMediaManager.shouldEnableVolumeSeekBar(sessionInfo);
    }

    @VisibleForTesting
    MediaDevice updateCurrentConnectedDevice() {
        MediaDevice connectedDevice = null;
        synchronized (mMediaDevicesLock) {
            for (MediaDevice device : mMediaDevices) {
                if (device instanceof BluetoothMediaDevice) {
                    if (isActiveDevice(((BluetoothMediaDevice) device).getCachedDevice())
                            && device.isConnected()) {
                        return device;
                    }
                } else if (device instanceof PhoneMediaDevice) {
                    connectedDevice = device;
                }
            }
        }

        return connectedDevice;
    }

    private boolean isActiveDevice(CachedBluetoothDevice device) {
        boolean isActiveDeviceA2dp = false;
        boolean isActiveDeviceHearingAid = false;
        boolean isActiveLeAudio = false;
        final A2dpProfile a2dpProfile = mLocalBluetoothManager.getProfileManager().getA2dpProfile();
        if (a2dpProfile != null) {
            isActiveDeviceA2dp = device.getDevice().equals(a2dpProfile.getActiveDevice());
        }
        if (!isActiveDeviceA2dp) {
            final HearingAidProfile hearingAidProfile = mLocalBluetoothManager.getProfileManager()
                    .getHearingAidProfile();
            if (hearingAidProfile != null) {
                isActiveDeviceHearingAid =
                        hearingAidProfile.getActiveDevices().contains(device.getDevice());
            }
        }

        if (!isActiveDeviceA2dp && !isActiveDeviceHearingAid) {
            final LeAudioProfile leAudioProfile = mLocalBluetoothManager.getProfileManager()
                    .getLeAudioProfile();
            if (leAudioProfile != null) {
                isActiveLeAudio = leAudioProfile.getActiveDevices().contains(device.getDevice());
            }
        }

        return isActiveDeviceA2dp || isActiveDeviceHearingAid || isActiveLeAudio;
    }

    private Collection<DeviceCallback> getCallbacks() {
        return new CopyOnWriteArrayList<>(mCallbacks);
    }

    class MediaDeviceCallback implements MediaManager.MediaDeviceCallback {
        @Override
        public void onDeviceAdded(MediaDevice device) {
            boolean isAdded = false;
            synchronized (mMediaDevicesLock) {
                if (!mMediaDevices.contains(device)) {
                    mMediaDevices.add(device);
                    isAdded = true;
                }
            }

            if (isAdded) {
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListAdded(List<MediaDevice> devices) {
            synchronized (mMediaDevicesLock) {
                Collections.sort(devices, COMPARATOR);
                mMediaDevices.clear();
                mMediaDevices.addAll(devices);
                // Add disconnected bluetooth devices only when phone output device is available.
                for (MediaDevice device : devices) {
                    final int type = device.getDeviceType();
                    if (type == MediaDevice.MediaDeviceType.TYPE_USB_C_AUDIO_DEVICE
                            || type == MediaDevice.MediaDeviceType.TYPE_3POINT5_MM_AUDIO_DEVICE
                            || type == MediaDevice.MediaDeviceType.TYPE_PHONE_DEVICE) {
                        mMediaDevices.addAll(buildDisconnectedBluetoothDevice());
                        break;
                    }
                }
            }

            final MediaDevice infoMediaDevice = mInfoMediaManager.getCurrentConnectedDevice();
            mCurrentConnectedDevice = infoMediaDevice != null
                    ? infoMediaDevice : updateCurrentConnectedDevice();
            dispatchDeviceListUpdate();
            if (mOnTransferBluetoothDevice != null && mOnTransferBluetoothDevice.isConnected()) {
                connectDevice(mOnTransferBluetoothDevice);
                mOnTransferBluetoothDevice.setState(MediaDeviceState.STATE_CONNECTED);
                dispatchSelectedDeviceStateChanged(mOnTransferBluetoothDevice,
                        MediaDeviceState.STATE_CONNECTED);
                mOnTransferBluetoothDevice = null;
            }
        }

        private List<MediaDevice> buildDisconnectedBluetoothDevice() {
            if (mBluetoothAdapter == null) {
                Log.w(TAG, "buildDisconnectedBluetoothDevice() BluetoothAdapter is null");
                return new ArrayList<>();
            }

            final List<BluetoothDevice> bluetoothDevices =
                    mBluetoothAdapter.getMostRecentlyConnectedDevices();
            final CachedBluetoothDeviceManager cachedDeviceManager =
                    mLocalBluetoothManager.getCachedDeviceManager();

            final List<CachedBluetoothDevice> cachedBluetoothDeviceList = new ArrayList<>();
            int deviceCount = 0;
            for (BluetoothDevice device : bluetoothDevices) {
                final CachedBluetoothDevice cachedDevice =
                        cachedDeviceManager.findDevice(device);
                if (cachedDevice != null) {
                    if (cachedDevice.getBondState() == BluetoothDevice.BOND_BONDED
                            && !cachedDevice.isConnected()
                            && isMediaDevice(cachedDevice)) {
                        deviceCount++;
                        cachedBluetoothDeviceList.add(cachedDevice);
                        if (deviceCount >= MAX_DISCONNECTED_DEVICE_NUM) {
                            break;
                        }
                    }
                }
            }

            unRegisterDeviceAttributeChangeCallback();
            mDisconnectedMediaDevices.clear();
            for (CachedBluetoothDevice cachedDevice : cachedBluetoothDeviceList) {
                final MediaDevice mediaDevice = new BluetoothMediaDevice(mContext,
                        cachedDevice,
                        null, null, mPackageName);
                if (!mMediaDevices.contains(mediaDevice)) {
                    cachedDevice.registerCallback(mDeviceAttributeChangeCallback);
                    mDisconnectedMediaDevices.add(mediaDevice);
                }
            }
            return new ArrayList<>(mDisconnectedMediaDevices);
        }

        private boolean isMediaDevice(CachedBluetoothDevice device) {
            for (LocalBluetoothProfile profile : device.getConnectableProfiles()) {
                if (profile instanceof A2dpProfile || profile instanceof HearingAidProfile ||
                        profile instanceof LeAudioProfile) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void onDeviceRemoved(MediaDevice device) {
            boolean isRemoved = false;
            synchronized (mMediaDevicesLock) {
                if (mMediaDevices.contains(device)) {
                    mMediaDevices.remove(device);
                    isRemoved = true;
                }
            }
            if (isRemoved) {
                dispatchDeviceListUpdate();
            }
        }

        @Override
        public void onDeviceListRemoved(List<MediaDevice> devices) {
            synchronized (mMediaDevicesLock) {
                mMediaDevices.removeAll(devices);
            }
            dispatchDeviceListUpdate();
        }

        @Override
        public void onConnectedDeviceChanged(String id) {
            MediaDevice connectDevice = null;
            synchronized (mMediaDevicesLock) {
                connectDevice = getMediaDeviceById(mMediaDevices, id);
            }
            connectDevice = connectDevice != null
                    ? connectDevice : updateCurrentConnectedDevice();

            mCurrentConnectedDevice = connectDevice;
            if (connectDevice != null) {
                connectDevice.setState(MediaDeviceState.STATE_CONNECTED);

                dispatchSelectedDeviceStateChanged(mCurrentConnectedDevice,
                        MediaDeviceState.STATE_CONNECTED);
            }
        }

        @Override
        public void onDeviceAttributesChanged() {
            dispatchDeviceAttributesChanged();
        }

        @Override
        public void onRequestFailed(int reason) {
            synchronized (mMediaDevicesLock) {
                for (MediaDevice device : mMediaDevices) {
                    if (device.getState() == MediaDeviceState.STATE_CONNECTING) {
                        device.setState(MediaDeviceState.STATE_CONNECTING_FAILED);
                    }
                }
            }
            dispatchOnRequestFailed(reason);
        }
    }

    private void unRegisterDeviceAttributeChangeCallback() {
        for (MediaDevice device : mDisconnectedMediaDevices) {
            ((BluetoothMediaDevice) device).getCachedDevice()
                    .unregisterCallback(mDeviceAttributeChangeCallback);
        }
    }

    /**
     * Callback for notifying device information updating
     */
    public interface DeviceCallback {
        /**
         * Callback for notifying device list updated.
         *
         * @param devices MediaDevice list
         */
        default void onDeviceListUpdate(List<MediaDevice> devices) {};

        /**
         * Callback for notifying the connected device is changed.
         *
         * @param device the changed connected MediaDevice
         * @param state the current MediaDevice state, the possible values are:
         * {@link MediaDeviceState#STATE_CONNECTED},
         * {@link MediaDeviceState#STATE_CONNECTING},
         * {@link MediaDeviceState#STATE_DISCONNECTED}
         */
        default void onSelectedDeviceStateChanged(MediaDevice device,
                @MediaDeviceState int state) {};

        /**
         * Callback for notifying the device attributes is changed.
         */
        default void onDeviceAttributesChanged() {};

        /**
         * Callback for notifying that transferring is failed.
         *
         * @param reason the reason that the request has failed. Can be one of followings:
         * {@link android.media.MediaRoute2ProviderService#REASON_UNKNOWN_ERROR},
         * {@link android.media.MediaRoute2ProviderService#REASON_REJECTED},
         * {@link android.media.MediaRoute2ProviderService#REASON_NETWORK_ERROR},
         * {@link android.media.MediaRoute2ProviderService#REASON_ROUTE_NOT_AVAILABLE},
         * {@link android.media.MediaRoute2ProviderService#REASON_INVALID_COMMAND},
         */
        default void onRequestFailed(int reason){};

        /**
         * Callback for notifying that we have a new about-to-connect device.
         *
         * An about-to-connect device is a device that is not yet connected but is expected to
         * connect imminently and should be displayed as the current device in the media player.
         * See [AudioManager.muteAwaitConnection] for more details.
         *
         * The information in the most recent callback should override information from any previous
         * callbacks.
         *
         * @param deviceAddress the address of the device. {@see AudioDeviceAttributes.address}.
         *                      If present, we'll use this address to fetch the full information
         *                      about the device (if we can find that information).
         * @param deviceName the name of the device (displayed to the user). Used as a backup in
         *                   case using deviceAddress doesn't work.
         * @param deviceIcon the icon that should be used with the device. Used as a backup in case
         *                   using deviceAddress doesn't work.
         */
        default void onAboutToConnectDeviceAdded(
                @NonNull String deviceAddress,
                @NonNull String deviceName,
                @Nullable Drawable deviceIcon
        ) {}

        /**
         * Callback for notifying that we no longer have an about-to-connect device.
         */
        default void onAboutToConnectDeviceRemoved() {}
    }

    /**
     * This callback is for update {@link BluetoothMediaDevice} summary when
     * {@link CachedBluetoothDevice} connection state is changed.
     */
    @VisibleForTesting
    class DeviceAttributeChangeCallback implements CachedBluetoothDevice.Callback {

        @Override
        public void onDeviceAttributesChanged() {
            if (mOnTransferBluetoothDevice != null
                    && !((BluetoothMediaDevice) mOnTransferBluetoothDevice).getCachedDevice()
                    .isBusy()
                    && !mOnTransferBluetoothDevice.isConnected()) {
                // Failed to connect
                mOnTransferBluetoothDevice.setState(MediaDeviceState.STATE_CONNECTING_FAILED);
                mOnTransferBluetoothDevice = null;
                dispatchOnRequestFailed(REASON_UNKNOWN_ERROR);
            }
            dispatchDeviceAttributesChanged();
        }
    }
}
