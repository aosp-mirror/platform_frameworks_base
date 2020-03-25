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

import static android.media.MediaRoute2Info.TYPE_BLUETOOTH_A2DP;
import static android.media.MediaRoute2Info.TYPE_BUILTIN_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_GROUP;
import static android.media.MediaRoute2Info.TYPE_HEARING_AID;
import static android.media.MediaRoute2Info.TYPE_REMOTE_SPEAKER;
import static android.media.MediaRoute2Info.TYPE_REMOTE_TV;
import static android.media.MediaRoute2Info.TYPE_UNKNOWN;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADPHONES;
import static android.media.MediaRoute2Info.TYPE_WIRED_HEADSET;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;

import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2Manager;
import android.media.RoutingSessionInfo;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.settingslib.bluetooth.LocalBluetoothManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * InfoMediaManager provide interface to get InfoMediaDevice list.
 */
public class InfoMediaManager extends MediaManager {

    private static final String TAG = "InfoMediaManager";
    private static final boolean DEBUG = false;
    @VisibleForTesting
    final RouterManagerCallback mMediaRouterCallback = new RouterManagerCallback();
    @VisibleForTesting
    final Executor mExecutor = Executors.newSingleThreadExecutor();
    @VisibleForTesting
    MediaRouter2Manager mRouterManager;
    @VisibleForTesting
    String mPackageName;

    private MediaDevice mCurrentConnectedDevice;
    private LocalBluetoothManager mBluetoothManager;

    public InfoMediaManager(Context context, String packageName, Notification notification,
            LocalBluetoothManager localBluetoothManager) {
        super(context, notification);

        mRouterManager = MediaRouter2Manager.getInstance(context);
        mBluetoothManager = localBluetoothManager;
        if (!TextUtils.isEmpty(packageName)) {
            mPackageName = packageName;
        }
    }

    @Override
    public void startScan() {
        mMediaDevices.clear();
        mRouterManager.registerCallback(mExecutor, mMediaRouterCallback);
        refreshDevices();
    }

    @Override
    public void stopScan() {
        mRouterManager.unregisterCallback(mMediaRouterCallback);
    }

    /**
     * Get current device that played media.
     * @return MediaDevice
     */
    MediaDevice getCurrentConnectedDevice() {
        return mCurrentConnectedDevice;
    }

    /**
     * Transfer MediaDevice for media without package name.
     */
    boolean connectDeviceWithoutPackageName(MediaDevice device) {
        boolean isConnected = false;
        final List<RoutingSessionInfo> infos = mRouterManager.getActiveSessions();
        if (infos.size() > 0) {
            final RoutingSessionInfo info = infos.get(0);
            final MediaRouter2Manager.RoutingController controller =
                    mRouterManager.getControllerForSession(info);

            controller.transferToRoute(device.mRouteInfo);
            isConnected = true;
        }
        return isConnected;
    }

    /**
     * Add a MediaDevice to let it play current media.
     *
     * @param device MediaDevice
     * @return If add device successful return {@code true}, otherwise return {@code false}
     */
    boolean addDeviceToPlayMedia(MediaDevice device) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "addDeviceToPlayMedia() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null && info.getSelectableRoutes().contains(device.mRouteInfo.getId())) {
            mRouterManager.getControllerForSession(info).selectRoute(device.mRouteInfo);
            return true;
        }

        Log.w(TAG, "addDeviceToPlayMedia() Ignoring selecting a non-selectable device : "
                + device.getName());

        return false;
    }

    private RoutingSessionInfo getRoutingSessionInfo() {
        final List<RoutingSessionInfo> sessionInfos =
                mRouterManager.getRoutingSessions(mPackageName);

        return sessionInfos.get(sessionInfos.size() - 1);
    }

    /**
     * Remove a {@code device} from current media.
     *
     * @param device MediaDevice
     * @return If device stop successful return {@code true}, otherwise return {@code false}
     */
    boolean removeDeviceFromPlayMedia(MediaDevice device) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "removeDeviceFromMedia() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null && info.getSelectedRoutes().contains(device.mRouteInfo.getId())) {
            mRouterManager.getControllerForSession(info).deselectRoute(device.mRouteInfo);
            return true;
        }

        Log.w(TAG, "removeDeviceFromMedia() Ignoring deselecting a non-deselectable device : "
                + device.getName());

        return false;
    }

    /**
     * Release session to stop playing media on MediaDevice.
     */
    boolean releaseSession() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "releaseSession() package name is null or empty!");
            return false;
        }

        final RoutingSessionInfo sessionInfo = getRoutingSessionInfo();

        if (sessionInfo != null) {
            mRouterManager.releaseSession(sessionInfo);
            return true;
        }

        Log.w(TAG, "releaseSession() Ignoring release session : " + mPackageName);

        return false;
    }

    /**
     * Get the MediaDevice list that can be added to current media.
     *
     * @return list of MediaDevice
     */
    List<MediaDevice> getSelectableMediaDevice() {
        final List<MediaDevice> deviceList = new ArrayList<>();
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSelectableMediaDevice() package name is null or empty!");
            return deviceList;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            for (MediaRoute2Info route : mRouterManager.getControllerForSession(info)
                    .getSelectableRoutes()) {
                deviceList.add(new InfoMediaDevice(mContext, mRouterManager,
                        route, mPackageName));
            }
            return deviceList;
        }

        Log.w(TAG, "getSelectableMediaDevice() cannot found selectable MediaDevice from : "
                + mPackageName);

        return deviceList;
    }

    /**
     * Get the MediaDevice list that has been selected to current media.
     *
     * @return list of MediaDevice
     */
    List<MediaDevice> getSelectedMediaDevice() {
        final List<MediaDevice> deviceList = new ArrayList<>();
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSelectedMediaDevice() package name is null or empty!");
            return deviceList;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            for (MediaRoute2Info route : mRouterManager.getControllerForSession(info)
                    .getSelectedRoutes()) {
                deviceList.add(new InfoMediaDevice(mContext, mRouterManager,
                        route, mPackageName));
            }
            return deviceList;
        }

        Log.w(TAG, "getSelectedMediaDevice() cannot found selectable MediaDevice from : "
                + mPackageName);

        return deviceList;
    }

    /**
     * Adjust the volume of {@link android.media.RoutingSessionInfo}.
     *
     * @param volume the value of volume
     */
    void adjustSessionVolume(int volume) {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "adjustSessionVolume() package name is null or empty!");
            return;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            Log.d(TAG, "adjustSessionVolume() adjust volume : " + volume + ", with : "
                    + mPackageName);
            mRouterManager.setSessionVolume(info, volume);
            return;
        }

        Log.w(TAG, "adjustSessionVolume() can't found corresponding RoutingSession with : "
                + mPackageName);
    }

    /**
     * Gets the maximum volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return  maximum volume of the session, and return -1 if not found.
     */
    public int getSessionVolumeMax() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSessionVolumeMax() package name is null or empty!");
            return -1;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getVolumeMax();
        }

        Log.w(TAG, "getSessionVolumeMax() can't found corresponding RoutingSession with : "
                + mPackageName);
        return -1;
    }

    /**
     * Gets the current volume of the {@link android.media.RoutingSessionInfo}.
     *
     * @return current volume of the session, and return -1 if not found.
     */
    public int getSessionVolume() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "getSessionVolume() package name is null or empty!");
            return -1;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getVolume();
        }

        Log.w(TAG, "getSessionVolume() can't found corresponding RoutingSession with : "
                + mPackageName);
        return -1;
    }

    CharSequence getSessionName() {
        if (TextUtils.isEmpty(mPackageName)) {
            Log.w(TAG, "Unable to get session name. The package name is null or empty!");
            return null;
        }

        final RoutingSessionInfo info = getRoutingSessionInfo();
        if (info != null) {
            return info.getName();
        }

        Log.w(TAG, "Unable to get session name for package: " + mPackageName);
        return null;
    }

    private void refreshDevices() {
        mMediaDevices.clear();
        mCurrentConnectedDevice = null;
        if (TextUtils.isEmpty(mPackageName)) {
            buildAllRoutes();
        } else {
            buildAvailableRoutes();
        }
        dispatchDeviceListAdded();
    }

    private void buildAllRoutes() {
        for (MediaRoute2Info route : mRouterManager.getAllRoutes()) {
            if (DEBUG) {
                Log.d(TAG, "buildAllRoutes() route : " + route.getName() + ", volume : "
                        + route.getVolume());
            }
            if (route.isSystemRoute()) {
                addMediaDevice(route);
            }
        }
    }

    private void buildAvailableRoutes() {
        for (MediaRoute2Info route : mRouterManager.getAvailableRoutes(mPackageName)) {
            if (DEBUG) {
                Log.d(TAG, "buildAvailableRoutes() route : " + route.getName());
            }
            addMediaDevice(route);
        }
    }

    private void addMediaDevice(MediaRoute2Info route) {
        final int deviceType = route.getType();
        MediaDevice mediaDevice = null;
        switch (deviceType) {
            case TYPE_UNKNOWN:
            case TYPE_REMOTE_TV:
            case TYPE_REMOTE_SPEAKER:
            case TYPE_GROUP:
                //TODO(b/148765806): use correct device type once api is ready.
                mediaDevice = new InfoMediaDevice(mContext, mRouterManager, route,
                        mPackageName);
                if (!TextUtils.isEmpty(mPackageName)
                        && getRoutingSessionInfo().getSelectedRoutes().contains(route.getId())
                        && mCurrentConnectedDevice == null) {
                    mCurrentConnectedDevice = mediaDevice;
                }
                break;
            case TYPE_BUILTIN_SPEAKER:
            case TYPE_WIRED_HEADSET:
            case TYPE_WIRED_HEADPHONES:
                mediaDevice =
                        new PhoneMediaDevice(mContext, mRouterManager, route, mPackageName);
                break;
            case TYPE_HEARING_AID:
            case TYPE_BLUETOOTH_A2DP:
                final BluetoothDevice device =
                        BluetoothAdapter.getDefaultAdapter().getRemoteDevice(route.getOriginalId());
                final CachedBluetoothDevice cachedDevice =
                        mBluetoothManager.getCachedDeviceManager().findDevice(device);
                mediaDevice = new BluetoothMediaDevice(mContext, cachedDevice, mRouterManager,
                        route, mPackageName);
                break;
            default:
                Log.w(TAG, "addMediaDevice() unknown device type : " + deviceType);
                break;

        }

        if (mediaDevice != null) {
            mMediaDevices.add(mediaDevice);
        }
    }

    class RouterManagerCallback extends MediaRouter2Manager.Callback {

        @Override
        public void onRoutesAdded(List<MediaRoute2Info> routes) {
            refreshDevices();
        }

        @Override
        public void onControlCategoriesChanged(String packageName, List<String> controlCategories) {
            if (TextUtils.equals(mPackageName, packageName)) {
                refreshDevices();
            }
        }

        @Override
        public void onRoutesChanged(List<MediaRoute2Info> routes) {
            refreshDevices();
        }

        @Override
        public void onRoutesRemoved(List<MediaRoute2Info> routes) {
            refreshDevices();
        }

        @Override
        public void onTransferred(RoutingSessionInfo oldSession, RoutingSessionInfo newSession) {
            if (DEBUG) {
                Log.d(TAG, "onTransferred() oldSession : " + oldSession.getName()
                        + ", newSession : " + newSession.getName());
            }
            mMediaDevices.clear();
            mCurrentConnectedDevice = null;
            if (TextUtils.isEmpty(mPackageName)) {
                buildAllRoutes();
            } else {
                buildAvailableRoutes();
            }

            final String id = mCurrentConnectedDevice != null
                    ? mCurrentConnectedDevice.getId()
                    : null;
            dispatchConnectedDeviceChanged(id);
        }

        @Override
        public void onTransferFailed(RoutingSessionInfo session, MediaRoute2Info route) {
            dispatchOnRequestFailed(REASON_UNKNOWN_ERROR);
        }

        @Override
        public void onRequestFailed(int reason) {
            dispatchOnRequestFailed(reason);
        }
    }
}
