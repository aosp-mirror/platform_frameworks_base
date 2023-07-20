/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.Manifest;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.app.ActivityThread;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.WifiDisplay;
import android.hardware.display.WifiDisplayStatus;
import android.media.session.MediaSession;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * This API is not recommended for new applications. Use the
 * <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/mediarouter/media/package-summary.html">Media Router
 * Library</a> for consistent behavior across all devices.
 *
 * <p>MediaRouter allows applications to control the routing of media channels
 * and streams from the current device to external speakers and destination devices.
 *
 * <p>A MediaRouter is retrieved through {@link Context#getSystemService(String)
 * Context.getSystemService()} of a {@link Context#MEDIA_ROUTER_SERVICE
 * Context.MEDIA_ROUTER_SERVICE}.
 *
 * <p>This API is not thread-safe; all interactions with it must be done from the main thread of the
 * process.
 */
@SystemService(Context.MEDIA_ROUTER_SERVICE)
public class MediaRouter {
    private static final String TAG = "MediaRouter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final boolean DEBUG_RESTORE_ROUTE = true;

    static class Static implements DisplayManager.DisplayListener {
        final String mPackageName;
        final Resources mResources;
        final IAudioService mAudioService;
        final DisplayManager mDisplayService;
        final IMediaRouterService mMediaRouterService;
        final Handler mHandler;
        final CopyOnWriteArrayList<CallbackInfo> mCallbacks =
                new CopyOnWriteArrayList<CallbackInfo>();

        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        final ArrayList<RouteCategory> mCategories = new ArrayList<RouteCategory>();

        final RouteCategory mSystemCategory;

        final AudioRoutesInfo mCurAudioRoutesInfo = new AudioRoutesInfo();

        RouteInfo mDefaultAudioVideo;
        RouteInfo mBluetoothA2dpRoute;
        boolean mIsBluetoothA2dpOn;

        RouteInfo mSelectedRoute;

        final boolean mCanConfigureWifiDisplays;
        boolean mActivelyScanningWifiDisplays;
        String mPreviousActiveWifiDisplayAddress;

        int mDiscoveryRequestRouteTypes;
        boolean mDiscoverRequestActiveScan;

        int mCurrentUserId = -1;
        IMediaRouterClient mClient;
        MediaRouterClientState mClientState;

        SparseIntArray mStreamVolume = new SparseIntArray();

        final IAudioRoutesObserver.Stub mAudioRoutesObserver = new IAudioRoutesObserver.Stub() {
            @Override
            public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
                try {
                    mIsBluetoothA2dpOn = mAudioService.isBluetoothA2dpOn();
                } catch (RemoteException e) {
                    Log.e(TAG, "Error querying Bluetooth A2DP state", e);
                    //TODO: When we reach here, mIsBluetoothA2dpOn may not be synced with
                    // mBluetoothA2dpRoute.
                }
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        updateAudioRoutes(newRoutes);
                    }
                });
            }
        };

        Static(Context appContext) {
            mPackageName = appContext.getPackageName();
            mResources = appContext.getResources();
            mHandler = new Handler(appContext.getMainLooper());

            IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
            mAudioService = IAudioService.Stub.asInterface(b);

            mDisplayService = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);

            mMediaRouterService = IMediaRouterService.Stub.asInterface(
                    ServiceManager.getService(Context.MEDIA_ROUTER_SERVICE));

            mSystemCategory = new RouteCategory(
                    R.string.default_audio_route_category_name,
                    ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO, false);
            mSystemCategory.mIsSystem = true;

            // Only the system can configure wifi displays.  The display manager
            // enforces this with a permission check.  Set a flag here so that we
            // know whether this process is actually allowed to scan and connect.
            mCanConfigureWifiDisplays = appContext.checkPermission(
                    Manifest.permission.CONFIGURE_WIFI_DISPLAY,
                    Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED;
        }

        // Called after sStatic is initialized
        void startMonitoringRoutes(Context appContext) {
            mDefaultAudioVideo = new RouteInfo(mSystemCategory);
            mDefaultAudioVideo.mNameResId = R.string.default_audio_route_name;
            mDefaultAudioVideo.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO;
            mDefaultAudioVideo.updatePresentationDisplay();
            if (((AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE))
                    .isVolumeFixed()) {
                mDefaultAudioVideo.mVolumeHandling = RouteInfo.PLAYBACK_VOLUME_FIXED;
            }
            addRouteStatic(mDefaultAudioVideo);

            // This will select the active wifi display route if there is one.
            updateWifiDisplayStatus(mDisplayService.getWifiDisplayStatus());

            appContext.registerReceiver(new WifiDisplayStatusChangedReceiver(),
                    new IntentFilter(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED));
            appContext.registerReceiver(new VolumeChangeReceiver(),
                    new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION));

            mDisplayService.registerDisplayListener(this, mHandler);

            AudioRoutesInfo newAudioRoutes = null;
            try {
                mIsBluetoothA2dpOn = mAudioService.isBluetoothA2dpOn();
                newAudioRoutes = mAudioService.startWatchingRoutes(mAudioRoutesObserver);
            } catch (RemoteException e) {
            }
            if (newAudioRoutes != null) {
                // This will select the active BT route if there is one and the current
                // selected route is the default system route, or if there is no selected
                // route yet.
                updateAudioRoutes(newAudioRoutes);
            }

            // Bind to the media router service.
            rebindAsUser(UserHandle.myUserId());

            // Select the default route if the above didn't sync us up
            // appropriately with relevant system state.
            if (mSelectedRoute == null) {
                selectDefaultRouteStatic();
            }
        }

        void updateAudioRoutes(AudioRoutesInfo newRoutes) {
            boolean audioRoutesChanged = false;
            boolean forceUseDefaultRoute = false;

            if (newRoutes.mainType != mCurAudioRoutesInfo.mainType) {
                mCurAudioRoutesInfo.mainType = newRoutes.mainType;
                int name;
                if ((newRoutes.mainType & AudioRoutesInfo.MAIN_HEADPHONES) != 0
                        || (newRoutes.mainType & AudioRoutesInfo.MAIN_HEADSET) != 0) {
                    name = R.string.default_audio_route_name_headphones;
                } else if ((newRoutes.mainType & AudioRoutesInfo.MAIN_DOCK_SPEAKERS) != 0) {
                    name = R.string.default_audio_route_name_dock_speakers;
                } else if ((newRoutes.mainType&AudioRoutesInfo.MAIN_HDMI) != 0) {
                    name = R.string.default_audio_route_name_hdmi;
                } else if ((newRoutes.mainType&AudioRoutesInfo.MAIN_USB) != 0) {
                    name = R.string.default_audio_route_name_usb;
                } else {
                    name = R.string.default_audio_route_name;
                }
                mDefaultAudioVideo.mNameResId = name;
                dispatchRouteChanged(mDefaultAudioVideo);

                if ((newRoutes.mainType & (AudioRoutesInfo.MAIN_HEADSET
                        | AudioRoutesInfo.MAIN_HEADPHONES | AudioRoutesInfo.MAIN_USB)) != 0) {
                    forceUseDefaultRoute = true;
                }
                audioRoutesChanged = true;
            }

            if (!TextUtils.equals(newRoutes.bluetoothName, mCurAudioRoutesInfo.bluetoothName)) {
                forceUseDefaultRoute = false;
                if (newRoutes.bluetoothName != null) {
                    if (mBluetoothA2dpRoute == null) {
                        // BT connected
                        final RouteInfo info = new RouteInfo(mSystemCategory);
                        info.mName = newRoutes.bluetoothName;
                        info.mDescription = mResources.getText(
                                R.string.bluetooth_a2dp_audio_route_name);
                        info.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO;
                        info.mDeviceType = RouteInfo.DEVICE_TYPE_BLUETOOTH;
                        info.mGlobalRouteId = sStatic.mResources.getString(
                                R.string.bluetooth_a2dp_audio_route_id);

                        mBluetoothA2dpRoute = info;
                        addRouteStatic(mBluetoothA2dpRoute);
                    } else {
                        mBluetoothA2dpRoute.mName = newRoutes.bluetoothName;
                        dispatchRouteChanged(mBluetoothA2dpRoute);
                    }
                } else if (mBluetoothA2dpRoute != null) {
                    // BT disconnected
                    RouteInfo btRoute = mBluetoothA2dpRoute;
                    mBluetoothA2dpRoute = null;
                    removeRouteStatic(btRoute);
                }
                audioRoutesChanged = true;
            }

            if (audioRoutesChanged) {
                Log.v(TAG, "Audio routes updated: " + newRoutes + ", a2dp=" + isBluetoothA2dpOn());
                if (mSelectedRoute == null || mSelectedRoute.isDefault()
                        || mSelectedRoute.isBluetooth()) {
                    if (forceUseDefaultRoute || mBluetoothA2dpRoute == null) {
                        selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO, mDefaultAudioVideo, false);
                    } else {
                        selectRouteStatic(ROUTE_TYPE_LIVE_AUDIO, mBluetoothA2dpRoute, false);
                    }
                }
            }
            mCurAudioRoutesInfo.bluetoothName = newRoutes.bluetoothName;
        }

        int getStreamVolume(int streamType) {
            int idx = mStreamVolume.indexOfKey(streamType);
            if (idx < 0) {
                int volume = 0;
                try {
                    volume = mAudioService.getStreamVolume(streamType);
                    mStreamVolume.put(streamType, volume);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error getting local stream volume", e);
                } finally {
                    return volume;
                }
            }
            return mStreamVolume.valueAt(idx);
        }

        boolean isBluetoothA2dpOn() {
            return mBluetoothA2dpRoute != null && mIsBluetoothA2dpOn;
        }

        void updateDiscoveryRequest() {
            // What are we looking for today?
            int routeTypes = 0;
            int passiveRouteTypes = 0;
            boolean activeScan = false;
            boolean activeScanWifiDisplay = false;
            final int count = mCallbacks.size();
            for (int i = 0; i < count; i++) {
                CallbackInfo cbi = mCallbacks.get(i);
                if ((cbi.flags & (CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
                        | CALLBACK_FLAG_REQUEST_DISCOVERY)) != 0) {
                    // Discovery explicitly requested.
                    routeTypes |= cbi.type;
                } else if ((cbi.flags & CALLBACK_FLAG_PASSIVE_DISCOVERY) != 0) {
                    // Discovery only passively requested.
                    passiveRouteTypes |= cbi.type;
                } else {
                    // Legacy case since applications don't specify the discovery flag.
                    // Unfortunately we just have to assume they always need discovery
                    // whenever they have a callback registered.
                    routeTypes |= cbi.type;
                }
                if ((cbi.flags & CALLBACK_FLAG_PERFORM_ACTIVE_SCAN) != 0) {
                    activeScan = true;
                    if ((cbi.type & ROUTE_TYPE_REMOTE_DISPLAY) != 0) {
                        activeScanWifiDisplay = true;
                    }
                }
            }
            if (routeTypes != 0 || activeScan) {
                // If someone else requests discovery then enable the passive listeners.
                // This is used by the MediaRouteButton and MediaRouteActionProvider since
                // they don't receive lifecycle callbacks from the Activity.
                routeTypes |= passiveRouteTypes;
            }

            // Update wifi display scanning.
            // TODO: All of this should be managed by the media router service.
            if (mCanConfigureWifiDisplays) {
                if (mSelectedRoute != null
                        && mSelectedRoute.matchesTypes(ROUTE_TYPE_REMOTE_DISPLAY)) {
                    // Don't scan while already connected to a remote display since
                    // it may interfere with the ongoing transmission.
                    activeScanWifiDisplay = false;
                }
                if (activeScanWifiDisplay) {
                    if (!mActivelyScanningWifiDisplays) {
                        mActivelyScanningWifiDisplays = true;
                        mDisplayService.startWifiDisplayScan();
                    }
                } else {
                    if (mActivelyScanningWifiDisplays) {
                        mActivelyScanningWifiDisplays = false;
                        mDisplayService.stopWifiDisplayScan();
                    }
                }
            }

            // Tell the media router service all about it.
            if (routeTypes != mDiscoveryRequestRouteTypes
                    || activeScan != mDiscoverRequestActiveScan) {
                mDiscoveryRequestRouteTypes = routeTypes;
                mDiscoverRequestActiveScan = activeScan;
                publishClientDiscoveryRequest();
            }
        }

        @Override
        public void onDisplayAdded(int displayId) {
            updatePresentationDisplays(displayId);
        }

        @Override
        public void onDisplayChanged(int displayId) {
            updatePresentationDisplays(displayId);
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            updatePresentationDisplays(displayId);
        }

        public void setRouterGroupId(String groupId) {
            if (mClient != null) {
                try {
                    mMediaRouterService.registerClientGroupId(mClient, groupId);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register group ID of the client.", ex);
                }
            }
        }

        public Display[] getAllPresentationDisplays() {
            try {
                return mDisplayService.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
            } catch (RuntimeException ex) {
                Log.e(TAG, "Unable to get displays.", ex);
                return null;
            }
        }

        private void updatePresentationDisplays(int changedDisplayId) {
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo route = mRoutes.get(i);
                if (route.updatePresentationDisplay() || (route.mPresentationDisplay != null
                        && route.mPresentationDisplay.getDisplayId() == changedDisplayId)) {
                    dispatchRoutePresentationDisplayChanged(route);
                }
            }
        }

        void handleGroupRouteSelected(String routeId) {
            RouteInfo routeToSelect = isBluetoothA2dpOn()
                    ? mBluetoothA2dpRoute : mDefaultAudioVideo;
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo route = mRoutes.get(i);
                if (TextUtils.equals(route.mGlobalRouteId, routeId)) {
                    routeToSelect = route;
                }
            }
            if (routeToSelect != mSelectedRoute) {
                selectRouteStatic(routeToSelect.mSupportedTypes, routeToSelect, /*explicit=*/false);
            }
        }

        void setSelectedRoute(RouteInfo info, boolean explicit) {
            // Must be non-reentrant.
            mSelectedRoute = info;
            publishClientSelectedRoute(explicit);
        }

        void rebindAsUser(int userId) {
            if (mCurrentUserId != userId || userId < 0 || mClient == null) {
                if (mClient != null) {
                    try {
                        mMediaRouterService.unregisterClient(mClient);
                    } catch (RemoteException ex) {
                        Log.e(TAG, "Unable to unregister media router client.", ex);
                    }
                    mClient = null;
                }

                mCurrentUserId = userId;

                try {
                    Client client = new Client();
                    mMediaRouterService.registerClientAsUser(client, mPackageName, userId);
                    mClient = client;
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to register media router client.", ex);
                }

                publishClientDiscoveryRequest();
                publishClientSelectedRoute(false);
                updateClientState();
            }
        }

        void publishClientDiscoveryRequest() {
            if (mClient != null) {
                try {
                    mMediaRouterService.setDiscoveryRequest(mClient,
                            mDiscoveryRequestRouteTypes, mDiscoverRequestActiveScan);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to publish media router client discovery request.", ex);
                }
            }
        }

        void publishClientSelectedRoute(boolean explicit) {
            if (mClient != null) {
                try {
                    mMediaRouterService.setSelectedRoute(mClient,
                            mSelectedRoute != null ? mSelectedRoute.mGlobalRouteId : null,
                            explicit);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to publish media router client selected route.", ex);
                }
            }
        }

        void updateClientState() {
            // Update the client state.
            mClientState = null;
            if (mClient != null) {
                try {
                    mClientState = mMediaRouterService.getState(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to retrieve media router client state.", ex);
                }
            }
            final ArrayList<MediaRouterClientState.RouteInfo> globalRoutes =
                    mClientState != null ? mClientState.routes : null;

            // Add or update routes.
            final int globalRouteCount = globalRoutes != null ? globalRoutes.size() : 0;
            for (int i = 0; i < globalRouteCount; i++) {
                final MediaRouterClientState.RouteInfo globalRoute = globalRoutes.get(i);
                RouteInfo route = findGlobalRoute(globalRoute.id);
                if (route == null) {
                    route = makeGlobalRoute(globalRoute);
                    addRouteStatic(route);
                } else {
                    updateGlobalRoute(route, globalRoute);
                }
            }

            // Remove defunct routes.
            outer: for (int i = mRoutes.size(); i-- > 0; ) {
                final RouteInfo route = mRoutes.get(i);
                final String globalRouteId = route.mGlobalRouteId;
                if (route.isDefault() || route.isBluetooth()) {
                    continue;
                }
                if (globalRouteId != null) {
                    for (int j = 0; j < globalRouteCount; j++) {
                        MediaRouterClientState.RouteInfo globalRoute = globalRoutes.get(j);
                        if (globalRouteId.equals(globalRoute.id)) {
                            continue outer; // found
                        }
                    }
                    // not found
                    removeRouteStatic(route);
                }
            }
        }

        void requestSetVolume(RouteInfo route, int volume) {
            if (route.mGlobalRouteId != null && mClient != null) {
                try {
                    mMediaRouterService.requestSetVolume(mClient,
                            route.mGlobalRouteId, volume);
                } catch (RemoteException ex) {
                    Log.w(TAG, "Unable to request volume change.", ex);
                }
            }
        }

        void requestUpdateVolume(RouteInfo route, int direction) {
            if (route.mGlobalRouteId != null && mClient != null) {
                try {
                    mMediaRouterService.requestUpdateVolume(mClient,
                            route.mGlobalRouteId, direction);
                } catch (RemoteException ex) {
                    Log.w(TAG, "Unable to request volume change.", ex);
                }
            }
        }

        RouteInfo makeGlobalRoute(MediaRouterClientState.RouteInfo globalRoute) {
            RouteInfo route = new RouteInfo(mSystemCategory);
            route.mGlobalRouteId = globalRoute.id;
            route.mName = globalRoute.name;
            route.mDescription = globalRoute.description;
            route.mSupportedTypes = globalRoute.supportedTypes;
            route.mDeviceType = globalRoute.deviceType;
            route.mEnabled = globalRoute.enabled;
            route.setRealStatusCode(globalRoute.statusCode);
            route.mPlaybackType = globalRoute.playbackType;
            route.mPlaybackStream = globalRoute.playbackStream;
            route.mVolume = globalRoute.volume;
            route.mVolumeMax = globalRoute.volumeMax;
            route.mVolumeHandling = globalRoute.volumeHandling;
            route.mPresentationDisplayId = globalRoute.presentationDisplayId;
            route.updatePresentationDisplay();
            return route;
        }

        void updateGlobalRoute(RouteInfo route, MediaRouterClientState.RouteInfo globalRoute) {
            boolean changed = false;
            boolean volumeChanged = false;
            boolean presentationDisplayChanged = false;

            if (!Objects.equals(route.mName, globalRoute.name)) {
                route.mName = globalRoute.name;
                changed = true;
            }
            if (!Objects.equals(route.mDescription, globalRoute.description)) {
                route.mDescription = globalRoute.description;
                changed = true;
            }
            final int oldSupportedTypes = route.mSupportedTypes;
            if (oldSupportedTypes != globalRoute.supportedTypes) {
                route.mSupportedTypes = globalRoute.supportedTypes;
                changed = true;
            }
            if (route.mEnabled != globalRoute.enabled) {
                route.mEnabled = globalRoute.enabled;
                changed = true;
            }
            if (route.mRealStatusCode != globalRoute.statusCode) {
                route.setRealStatusCode(globalRoute.statusCode);
                changed = true;
            }
            if (route.mPlaybackType != globalRoute.playbackType) {
                route.mPlaybackType = globalRoute.playbackType;
                changed = true;
            }
            if (route.mPlaybackStream != globalRoute.playbackStream) {
                route.mPlaybackStream = globalRoute.playbackStream;
                changed = true;
            }
            if (route.mVolume != globalRoute.volume) {
                route.mVolume = globalRoute.volume;
                changed = true;
                volumeChanged = true;
            }
            if (route.mVolumeMax != globalRoute.volumeMax) {
                route.mVolumeMax = globalRoute.volumeMax;
                changed = true;
                volumeChanged = true;
            }
            if (route.mVolumeHandling != globalRoute.volumeHandling) {
                route.mVolumeHandling = globalRoute.volumeHandling;
                changed = true;
                volumeChanged = true;
            }
            if (route.mPresentationDisplayId != globalRoute.presentationDisplayId) {
                route.mPresentationDisplayId = globalRoute.presentationDisplayId;
                route.updatePresentationDisplay();
                changed = true;
                presentationDisplayChanged = true;
            }

            if (changed) {
                dispatchRouteChanged(route, oldSupportedTypes);
            }
            if (volumeChanged) {
                dispatchRouteVolumeChanged(route);
            }
            if (presentationDisplayChanged) {
                dispatchRoutePresentationDisplayChanged(route);
            }
        }

        RouteInfo findGlobalRoute(String globalRouteId) {
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo route = mRoutes.get(i);
                if (globalRouteId.equals(route.mGlobalRouteId)) {
                    return route;
                }
            }
            return null;
        }

        boolean isPlaybackActive() {
            if (mClient != null) {
                try {
                    return mMediaRouterService.isPlaybackActive(mClient);
                } catch (RemoteException ex) {
                    Log.e(TAG, "Unable to retrieve playback active state.", ex);
                }
            }
            return false;
        }

        final class Client extends IMediaRouterClient.Stub {
            @Override
            public void onStateChanged() {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (Client.this == mClient) {
                            updateClientState();
                        }
                    }
                });
            }

            @Override
            public void onRestoreRoute() {
                mHandler.post(() -> {
                    // Skip restoring route if the selected route is not a system audio route,
                    // MediaRouter is initializing, or mClient was changed.
                    if (Client.this != mClient || mSelectedRoute == null
                            || (!mSelectedRoute.isDefault() && !mSelectedRoute.isBluetooth())) {
                        return;
                    }
                    if (DEBUG_RESTORE_ROUTE) {
                        if (mSelectedRoute.isDefault() && mBluetoothA2dpRoute != null) {
                            Log.d(TAG, "onRestoreRoute() : selectedRoute=" + mSelectedRoute
                                    + ", a2dpRoute=" + mBluetoothA2dpRoute);
                        } else {
                            Log.d(TAG, "onRestoreRoute() : route=" + mSelectedRoute);
                        }
                    }
                    mSelectedRoute.select();
                });
            }

            @Override
            public void onGroupRouteSelected(String groupRouteId) {
                mHandler.post(() -> {
                    if (Client.this == mClient) {
                        handleGroupRouteSelected(groupRouteId);
                    }
                });
            }
        }
    }

    static Static sStatic;

    /**
     * Route type flag for live audio.
     *
     * <p>A device that supports live audio routing will allow the media audio stream
     * to be routed to supported destinations. This can include internal speakers or
     * audio jacks on the device itself, A2DP devices, and more.</p>
     *
     * <p>Once initiated this routing is transparent to the application. All audio
     * played on the media stream will be routed to the selected destination.</p>
     */
    public static final int ROUTE_TYPE_LIVE_AUDIO = 1 << 0;

    /**
     * Route type flag for live video.
     *
     * <p>A device that supports live video routing will allow a mirrored version
     * of the device's primary display or a customized
     * {@link android.app.Presentation Presentation} to be routed to supported destinations.</p>
     *
     * <p>Once initiated, display mirroring is transparent to the application.
     * While remote routing is active the application may use a
     * {@link android.app.Presentation Presentation} to replace the mirrored view
     * on the external display with different content.</p>
     *
     * @see RouteInfo#getPresentationDisplay()
     * @see android.app.Presentation
     */
    public static final int ROUTE_TYPE_LIVE_VIDEO = 1 << 1;

    /**
     * Temporary interop constant to identify remote displays.
     * @hide To be removed when media router API is updated.
     */
    public static final int ROUTE_TYPE_REMOTE_DISPLAY = 1 << 2;

    /**
     * Route type flag for application-specific usage.
     *
     * <p>Unlike other media route types, user routes are managed by the application.
     * The MediaRouter will manage and dispatch events for user routes, but the application
     * is expected to interpret the meaning of these events and perform the requested
     * routing tasks.</p>
     */
    public static final int ROUTE_TYPE_USER = 1 << 23;

    static final int ROUTE_TYPE_ANY = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO
            | ROUTE_TYPE_REMOTE_DISPLAY | ROUTE_TYPE_USER;

    /**
     * Flag for {@link #addCallback}: Actively scan for routes while this callback
     * is registered.
     * <p>
     * When this flag is specified, the media router will actively scan for new
     * routes.  Certain routes, such as wifi display routes, may not be discoverable
     * except when actively scanning.  This flag is typically used when the route picker
     * dialog has been opened by the user to ensure that the route information is
     * up to date.
     * </p><p>
     * Active scanning may consume a significant amount of power and may have intrusive
     * effects on wireless connectivity.  Therefore it is important that active scanning
     * only be requested when it is actually needed to satisfy a user request to
     * discover and select a new route.
     * </p>
     */
    public static final int CALLBACK_FLAG_PERFORM_ACTIVE_SCAN = 1 << 0;

    /**
     * Flag for {@link #addCallback}: Do not filter route events.
     * <p>
     * When this flag is specified, the callback will be invoked for event that affect any
     * route even if they do not match the callback's filter.
     * </p>
     */
    public static final int CALLBACK_FLAG_UNFILTERED_EVENTS = 1 << 1;

    /**
     * Explicitly requests discovery.
     *
     * @hide Future API ported from support library.  Revisit this later.
     */
    public static final int CALLBACK_FLAG_REQUEST_DISCOVERY = 1 << 2;

    /**
     * Requests that discovery be performed but only if there is some other active
     * callback already registered.
     *
     * @hide Compatibility workaround for the fact that applications do not currently
     * request discovery explicitly (except when using the support library API).
     */
    public static final int CALLBACK_FLAG_PASSIVE_DISCOVERY = 1 << 3;

    /**
     * Flag for {@link #isRouteAvailable}: Ignore the default route.
     * <p>
     * This flag is used to determine whether a matching non-default route is available.
     * This constraint may be used to decide whether to offer the route chooser dialog
     * to the user.  There is no point offering the chooser if there are no
     * non-default choices.
     * </p>
     *
     * @hide Future API ported from support library.  Revisit this later.
     */
    public static final int AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE = 1 << 0;

    /**
     * The route group id used for sharing the selected mirroring device.
     * System UI and Settings use this to synchronize their mirroring status.
     * @hide
     */
    public static final String MIRRORING_GROUP_ID = "android.media.mirroring_group";

    // Maps application contexts
    static final HashMap<Context, MediaRouter> sRouters = new HashMap<Context, MediaRouter>();

    static String typesToString(int types) {
        final StringBuilder result = new StringBuilder();
        if ((types & ROUTE_TYPE_LIVE_AUDIO) != 0) {
            result.append("ROUTE_TYPE_LIVE_AUDIO ");
        }
        if ((types & ROUTE_TYPE_LIVE_VIDEO) != 0) {
            result.append("ROUTE_TYPE_LIVE_VIDEO ");
        }
        if ((types & ROUTE_TYPE_REMOTE_DISPLAY) != 0) {
            result.append("ROUTE_TYPE_REMOTE_DISPLAY ");
        }
        if ((types & ROUTE_TYPE_USER) != 0) {
            result.append("ROUTE_TYPE_USER ");
        }
        return result.toString();
    }

    /** @hide */
    public MediaRouter(Context context) {
        synchronized (Static.class) {
            if (sStatic == null) {
                final Context appContext = context.getApplicationContext();
                sStatic = new Static(appContext);
                sStatic.startMonitoringRoutes(appContext);
            }
        }
    }

    /**
     * Gets the default route for playing media content on the system.
     * <p>
     * The system always provides a default route.
     * </p>
     *
     * @return The default route, which is guaranteed to never be null.
     */
    public RouteInfo getDefaultRoute() {
        return sStatic.mDefaultAudioVideo;
    }

    /**
     * Returns a Bluetooth route if available, otherwise the default route.
     * @hide
     */
    public RouteInfo getFallbackRoute() {
        return (sStatic.mBluetoothA2dpRoute != null)
                ? sStatic.mBluetoothA2dpRoute : sStatic.mDefaultAudioVideo;
    }

    /**
     * @hide for use by framework routing UI
     */
    public RouteCategory getSystemCategory() {
        return sStatic.mSystemCategory;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public RouteInfo getSelectedRoute() {
        return getSelectedRoute(ROUTE_TYPE_ANY);
    }

    /**
     * Return the currently selected route for any of the given types
     *
     * @param type route types
     * @return the selected route
     */
    public RouteInfo getSelectedRoute(int type) {
        if (sStatic.mSelectedRoute != null &&
                (sStatic.mSelectedRoute.mSupportedTypes & type) != 0) {
            // If the selected route supports any of the types supplied, it's still considered
            // 'selected' for that type.
            return sStatic.mSelectedRoute;
        } else if (type == ROUTE_TYPE_USER) {
            // The caller specifically asked for a user route and the currently selected route
            // doesn't qualify.
            return null;
        }
        // If the above didn't match and we're not specifically asking for a user route,
        // consider the default selected.
        return sStatic.mDefaultAudioVideo;
    }

    /**
     * Returns true if there is a route that matches the specified types.
     * <p>
     * This method returns true if there are any available routes that match the types
     * regardless of whether they are enabled or disabled.  If the
     * {@link #AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE} flag is specified, then
     * the method will only consider non-default routes.
     * </p>
     *
     * @param types The types to match.
     * @param flags Flags to control the determination of whether a route may be available.
     * May be zero or {@link #AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE}.
     * @return True if a matching route may be available.
     *
     * @hide Future API ported from support library.  Revisit this later.
     */
    public boolean isRouteAvailable(int types, int flags) {
        final int count = sStatic.mRoutes.size();
        for (int i = 0; i < count; i++) {
            RouteInfo route = sStatic.mRoutes.get(i);
            if (route.matchesTypes(types)) {
                if ((flags & AVAILABILITY_FLAG_IGNORE_DEFAULT_ROUTE) == 0
                        || route != sStatic.mDefaultAudioVideo) {
                    return true;
                }
            }
        }

        // It doesn't look like we can find a matching route right now.
        return false;
    }

    /**
     * Sets the group ID of the router.
     * Media routers with the same ID acts as if they were a single media router.
     * For example, if a media router selects a route, the selected route of routers
     * with the same group ID will be changed automatically.
     *
     * Two routers in a group are supposed to use the same route types.
     *
     * System UI and Settings use this to synchronize their mirroring status.
     * Do not set the router group id unless it's necessary.
     *
     * {@link android.Manifest.permission#CONFIGURE_WIFI_DISPLAY} permission is required to
     * call this method.
     * @hide
     */
    public void setRouterGroupId(@Nullable String groupId) {
        sStatic.setRouterGroupId(groupId);
    }

    /**
     * Add a callback to listen to events about specific kinds of media routes.
     * If the specified callback is already registered, its registration will be updated for any
     * additional route types specified.
     * <p>
     * This is a convenience method that has the same effect as calling
     * {@link #addCallback(int, Callback, int)} without flags.
     * </p>
     *
     * @param types Types of routes this callback is interested in
     * @param cb Callback to add
     */
    public void addCallback(int types, Callback cb) {
        addCallback(types, cb, 0);
    }

    /**
     * Add a callback to listen to events about specific kinds of media routes.
     * If the specified callback is already registered, its registration will be updated for any
     * additional route types specified.
     * <p>
     * By default, the callback will only be invoked for events that affect routes
     * that match the specified selector.  The filtering may be disabled by specifying
     * the {@link #CALLBACK_FLAG_UNFILTERED_EVENTS} flag.
     * </p>
     *
     * @param types Types of routes this callback is interested in
     * @param cb Callback to add
     * @param flags Flags to control the behavior of the callback.
     * May be zero or a combination of {@link #CALLBACK_FLAG_PERFORM_ACTIVE_SCAN} and
     * {@link #CALLBACK_FLAG_UNFILTERED_EVENTS}.
     */
    public void addCallback(int types, Callback cb, int flags) {
        CallbackInfo info;
        int index = findCallbackInfo(cb);
        if (index >= 0) {
            info = sStatic.mCallbacks.get(index);
            info.type |= types;
            info.flags |= flags;
        } else {
            info = new CallbackInfo(cb, types, flags, this);
            sStatic.mCallbacks.add(info);
        }
        sStatic.updateDiscoveryRequest();
    }

    /**
     * Remove the specified callback. It will no longer receive events about media routing.
     *
     * @param cb Callback to remove
     */
    public void removeCallback(Callback cb) {
        int index = findCallbackInfo(cb);
        if (index >= 0) {
            sStatic.mCallbacks.remove(index);
            sStatic.updateDiscoveryRequest();
        } else {
            Log.w(TAG, "removeCallback(" + cb + "): callback not registered");
        }
    }

    private int findCallbackInfo(Callback cb) {
        final int count = sStatic.mCallbacks.size();
        for (int i = 0; i < count; i++) {
            final CallbackInfo info = sStatic.mCallbacks.get(i);
            if (info.cb == cb) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Select the specified route to use for output of the given media types.
     * <p class="note">
     * As API version 18, this function may be used to select any route.
     * In prior versions, this function could only be used to select user
     * routes and would ignore any attempt to select a system route.
     * </p>
     *
     * @param types type flags indicating which types this route should be used for.
     *              The route must support at least a subset.
     * @param route Route to select
     * @throws IllegalArgumentException if the given route is {@code null}
     */
    public void selectRoute(int types, @NonNull RouteInfo route) {
        if (route == null) {
            throw new IllegalArgumentException("Route cannot be null.");
        }
        selectRouteStatic(types, route, true);
    }

    /**
     * @hide internal use
     */
    @UnsupportedAppUsage
    public void selectRouteInt(int types, RouteInfo route, boolean explicit) {
        selectRouteStatic(types, route, explicit);
    }

    static void selectRouteStatic(int types, @NonNull RouteInfo route, boolean explicit) {
        Log.v(TAG, "Selecting route: " + route);
        assert(route != null);
        final RouteInfo oldRoute = sStatic.mSelectedRoute;
        final RouteInfo currentSystemRoute = sStatic.isBluetoothA2dpOn()
                ? sStatic.mBluetoothA2dpRoute : sStatic.mDefaultAudioVideo;
        boolean wasDefaultOrBluetoothRoute = (oldRoute != null)
                && (oldRoute.isDefault() || oldRoute.isBluetooth());
        if (oldRoute == route
                && (!wasDefaultOrBluetoothRoute || route == currentSystemRoute)) {
            return;
        }
        if (!route.matchesTypes(types)) {
            Log.w(TAG, "selectRoute ignored; cannot select route with supported types " +
                    typesToString(route.getSupportedTypes()) + " into route types " +
                    typesToString(types));
            return;
        }

        if (sStatic.isPlaybackActive() && sStatic.mBluetoothA2dpRoute != null
                && (types & ROUTE_TYPE_LIVE_AUDIO) != 0
                && (route.isBluetooth() || route.isDefault())) {
            try {
                sStatic.mMediaRouterService.setBluetoothA2dpOn(sStatic.mClient,
                        route.isBluetooth());
            } catch (RemoteException e) {
                Log.e(TAG, "Error changing Bluetooth A2DP state", e);
            }
        } else if (DEBUG_RESTORE_ROUTE) {
            Log.i(TAG, "Skip setBluetoothA2dpOn(): types=" + types + ", isPlaybackActive()="
                    + sStatic.isPlaybackActive() + ", BT route=" + sStatic.mBluetoothA2dpRoute);
        }

        final WifiDisplay activeDisplay =
                sStatic.mDisplayService.getWifiDisplayStatus().getActiveDisplay();
        final boolean oldRouteHasAddress = oldRoute != null && oldRoute.mDeviceAddress != null;
        final boolean newRouteHasAddress = route.mDeviceAddress != null;
        if (activeDisplay != null || oldRouteHasAddress || newRouteHasAddress) {
            if (newRouteHasAddress && !matchesDeviceAddress(activeDisplay, route)) {
                if (sStatic.mCanConfigureWifiDisplays) {
                    sStatic.mDisplayService.connectWifiDisplay(route.mDeviceAddress);
                } else {
                    Log.e(TAG, "Cannot connect to wifi displays because this process "
                            + "is not allowed to do so.");
                }
            } else if (activeDisplay != null && !newRouteHasAddress) {
                sStatic.mDisplayService.disconnectWifiDisplay();
            }
        }

        sStatic.setSelectedRoute(route, explicit);

        if (oldRoute != null) {
            dispatchRouteUnselected(types & oldRoute.getSupportedTypes(), oldRoute);
            if (oldRoute.resolveStatusCode()) {
                dispatchRouteChanged(oldRoute);
            }
        }
        if (route != null) {
            if (route.resolveStatusCode()) {
                dispatchRouteChanged(route);
            }
            dispatchRouteSelected(types & route.getSupportedTypes(), route);
        }

        // The behavior of active scans may depend on the currently selected route.
        sStatic.updateDiscoveryRequest();
    }

    static void selectDefaultRouteStatic() {
        // TODO: Be smarter about the route types here; this selects for all valid.
        if (sStatic.isBluetoothA2dpOn() && sStatic.mSelectedRoute != null
                && !sStatic.mSelectedRoute.isBluetooth()) {
            selectRouteStatic(ROUTE_TYPE_ANY, sStatic.mBluetoothA2dpRoute, false);
        } else {
            selectRouteStatic(ROUTE_TYPE_ANY, sStatic.mDefaultAudioVideo, false);
        }
    }

    /**
     * Compare the device address of a display and a route.
     * Nulls/no device address will match another null/no address.
     */
    static boolean matchesDeviceAddress(WifiDisplay display, RouteInfo info) {
        final boolean routeHasAddress = info != null && info.mDeviceAddress != null;
        if (display == null && !routeHasAddress) {
            return true;
        }

        if (display != null && routeHasAddress) {
            return display.getDeviceAddress().equals(info.mDeviceAddress);
        }
        return false;
    }

    /**
     * Add an app-specified route for media to the MediaRouter.
     * App-specified route definitions are created using {@link #createUserRoute(RouteCategory)}
     *
     * @param info Definition of the route to add
     * @see #createUserRoute(RouteCategory)
     * @see #removeUserRoute(UserRouteInfo)
     */
    public void addUserRoute(UserRouteInfo info) {
        addRouteStatic(info);
    }

    /**
     * @hide Framework use only
     */
    public void addRouteInt(RouteInfo info) {
        addRouteStatic(info);
    }

    static void addRouteStatic(RouteInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Adding route: " + info);
        }
        final RouteCategory cat = info.getCategory();
        if (!sStatic.mCategories.contains(cat)) {
            sStatic.mCategories.add(cat);
        }
        if (cat.isGroupable() && !(info instanceof RouteGroup)) {
            // Enforce that any added route in a groupable category must be in a group.
            final RouteGroup group = new RouteGroup(info.getCategory());
            group.mSupportedTypes = info.mSupportedTypes;
            sStatic.mRoutes.add(group);
            dispatchRouteAdded(group);
            group.addRoute(info);

            info = group;
        } else {
            sStatic.mRoutes.add(info);
            dispatchRouteAdded(info);
        }
    }

    /**
     * Remove an app-specified route for media from the MediaRouter.
     *
     * @param info Definition of the route to remove
     * @see #addUserRoute(UserRouteInfo)
     */
    public void removeUserRoute(UserRouteInfo info) {
        removeRouteStatic(info);
    }

    /**
     * Remove all app-specified routes from the MediaRouter.
     *
     * @see #removeUserRoute(UserRouteInfo)
     */
    public void clearUserRoutes() {
        for (int i = 0; i < sStatic.mRoutes.size(); i++) {
            final RouteInfo info = sStatic.mRoutes.get(i);
            // TODO Right now, RouteGroups only ever contain user routes.
            // The code below will need to change if this assumption does.
            if (info instanceof UserRouteInfo || info instanceof RouteGroup) {
                removeRouteStatic(info);
                i--;
            }
        }
    }

    /**
     * @hide internal use only
     */
    public void removeRouteInt(RouteInfo info) {
        removeRouteStatic(info);
    }

    static void removeRouteStatic(RouteInfo info) {
        if (DEBUG) {
            Log.d(TAG, "Removing route: " + info);
        }
        if (sStatic.mRoutes.remove(info)) {
            final RouteCategory removingCat = info.getCategory();
            final int count = sStatic.mRoutes.size();
            boolean found = false;
            for (int i = 0; i < count; i++) {
                final RouteCategory cat = sStatic.mRoutes.get(i).getCategory();
                if (removingCat == cat) {
                    found = true;
                    break;
                }
            }
            if (info.isSelected()) {
                // Removing the currently selected route? Select the default before we remove it.
                selectDefaultRouteStatic();
            }
            if (!found) {
                sStatic.mCategories.remove(removingCat);
            }
            dispatchRouteRemoved(info);
        }
    }

    /**
     * Return the number of {@link MediaRouter.RouteCategory categories} currently
     * represented by routes known to this MediaRouter.
     *
     * @return the number of unique categories represented by this MediaRouter's known routes
     */
    public int getCategoryCount() {
        return sStatic.mCategories.size();
    }

    /**
     * Return the {@link MediaRouter.RouteCategory category} at the given index.
     * Valid indices are in the range [0-getCategoryCount).
     *
     * @param index which category to return
     * @return the category at index
     */
    public RouteCategory getCategoryAt(int index) {
        return sStatic.mCategories.get(index);
    }

    /**
     * Return the number of {@link MediaRouter.RouteInfo routes} currently known
     * to this MediaRouter.
     *
     * @return the number of routes tracked by this router
     */
    public int getRouteCount() {
        return sStatic.mRoutes.size();
    }

    /**
     * Return the route at the specified index.
     *
     * @param index index of the route to return
     * @return the route at index
     */
    public RouteInfo getRouteAt(int index) {
        return sStatic.mRoutes.get(index);
    }

    static int getRouteCountStatic() {
        return sStatic.mRoutes.size();
    }

    static RouteInfo getRouteAtStatic(int index) {
        return sStatic.mRoutes.get(index);
    }

    /**
     * Create a new user route that may be modified and registered for use by the application.
     *
     * @param category The category the new route will belong to
     * @return A new UserRouteInfo for use by the application
     *
     * @see #addUserRoute(UserRouteInfo)
     * @see #removeUserRoute(UserRouteInfo)
     * @see #createRouteCategory(CharSequence, boolean)
     */
    public UserRouteInfo createUserRoute(RouteCategory category) {
        return new UserRouteInfo(category);
    }

    /**
     * Create a new route category. Each route must belong to a category.
     *
     * @param name Name of the new category
     * @param isGroupable true if routes in this category may be grouped with one another
     * @return the new RouteCategory
     */
    public RouteCategory createRouteCategory(CharSequence name, boolean isGroupable) {
        return new RouteCategory(name, ROUTE_TYPE_USER, isGroupable);
    }

    /**
     * Create a new route category. Each route must belong to a category.
     *
     * @param nameResId Resource ID of the name of the new category
     * @param isGroupable true if routes in this category may be grouped with one another
     * @return the new RouteCategory
     */
    public RouteCategory createRouteCategory(int nameResId, boolean isGroupable) {
        return new RouteCategory(nameResId, ROUTE_TYPE_USER, isGroupable);
    }

    /**
     * Rebinds the media router to handle routes that belong to the specified user.
     * Requires the interact across users permission to access the routes of another user.
     * <p>
     * This method is a complete hack to work around the singleton nature of the
     * media router when running inside of singleton processes like QuickSettings.
     * This mechanism should be burned to the ground when MediaRouter is redesigned.
     * Ideally the current user would be pulled from the Context but we need to break
     * down MediaRouter.Static before we can get there.
     * </p>
     *
     * @hide
     */
    public void rebindAsUser(int userId) {
        sStatic.rebindAsUser(userId);
    }

    static void updateRoute(final RouteInfo info) {
        dispatchRouteChanged(info);
    }

    static void dispatchRouteSelected(int type, RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRouteSelected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteUnselected(int type, RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRouteUnselected(cbi.router, type, info);
            }
        }
    }

    static void dispatchRouteChanged(RouteInfo info) {
        dispatchRouteChanged(info, info.mSupportedTypes);
    }

    static void dispatchRouteChanged(RouteInfo info, int oldSupportedTypes) {
        if (DEBUG) {
            Log.d(TAG, "Dispatching route change: " + info);
        }
        final int newSupportedTypes = info.mSupportedTypes;
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            // Reconstruct some of the history for callbacks that may not have observed
            // all of the events needed to correctly interpret the current state.
            // FIXME: This is a strong signal that we should deprecate route type filtering
            // completely in the future because it can lead to inconsistencies in
            // applications.
            final boolean oldVisibility = cbi.filterRouteEvent(oldSupportedTypes);
            final boolean newVisibility = cbi.filterRouteEvent(newSupportedTypes);
            if (!oldVisibility && newVisibility) {
                cbi.cb.onRouteAdded(cbi.router, info);
                if (info.isSelected()) {
                    cbi.cb.onRouteSelected(cbi.router, newSupportedTypes, info);
                }
            }
            if (oldVisibility || newVisibility) {
                cbi.cb.onRouteChanged(cbi.router, info);
            }
            if (oldVisibility && !newVisibility) {
                if (info.isSelected()) {
                    cbi.cb.onRouteUnselected(cbi.router, oldSupportedTypes, info);
                }
                cbi.cb.onRouteRemoved(cbi.router, info);
            }
        }
    }

    static void dispatchRouteAdded(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRouteAdded(cbi.router, info);
            }
        }
    }

    static void dispatchRouteRemoved(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRouteRemoved(cbi.router, info);
            }
        }
    }

    static void dispatchRouteGrouped(RouteInfo info, RouteGroup group, int index) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(group)) {
                cbi.cb.onRouteGrouped(cbi.router, info, group, index);
            }
        }
    }

    static void dispatchRouteUngrouped(RouteInfo info, RouteGroup group) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(group)) {
                cbi.cb.onRouteUngrouped(cbi.router, info, group);
            }
        }
    }

    static void dispatchRouteVolumeChanged(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRouteVolumeChanged(cbi.router, info);
            }
        }
    }

    static void dispatchRoutePresentationDisplayChanged(RouteInfo info) {
        for (CallbackInfo cbi : sStatic.mCallbacks) {
            if (cbi.filterRouteEvent(info)) {
                cbi.cb.onRoutePresentationDisplayChanged(cbi.router, info);
            }
        }
    }

    static void systemVolumeChanged(int newValue) {
        final RouteInfo selectedRoute = sStatic.mSelectedRoute;
        if (selectedRoute == null) return;

        if (selectedRoute.isBluetooth() || selectedRoute.isDefault()) {
            dispatchRouteVolumeChanged(selectedRoute);
        } else if (sStatic.mBluetoothA2dpRoute != null) {
            dispatchRouteVolumeChanged(sStatic.mIsBluetoothA2dpOn
                    ? sStatic.mBluetoothA2dpRoute : sStatic.mDefaultAudioVideo);
        } else {
            dispatchRouteVolumeChanged(sStatic.mDefaultAudioVideo);
        }
    }

    static void updateWifiDisplayStatus(WifiDisplayStatus status) {
        WifiDisplay[] displays;
        WifiDisplay activeDisplay;
        if (status.getFeatureState() == WifiDisplayStatus.FEATURE_STATE_ON) {
            displays = status.getDisplays();
            activeDisplay = status.getActiveDisplay();

            // Only the system is able to connect to wifi display routes.
            // The display manager will enforce this with a permission check but it
            // still publishes information about all available displays.
            // Filter the list down to just the active display.
            if (!sStatic.mCanConfigureWifiDisplays) {
                if (activeDisplay != null) {
                    displays = new WifiDisplay[] { activeDisplay };
                } else {
                    displays = WifiDisplay.EMPTY_ARRAY;
                }
            }
        } else {
            displays = WifiDisplay.EMPTY_ARRAY;
            activeDisplay = null;
        }
        String activeDisplayAddress = activeDisplay != null ?
                activeDisplay.getDeviceAddress() : null;

        // Add or update routes.
        for (int i = 0; i < displays.length; i++) {
            final WifiDisplay d = displays[i];
            if (shouldShowWifiDisplay(d, activeDisplay)) {
                RouteInfo route = findWifiDisplayRoute(d);
                if (route == null) {
                    route = makeWifiDisplayRoute(d, status);
                    addRouteStatic(route);
                } else {
                    String address = d.getDeviceAddress();
                    boolean disconnected = !address.equals(activeDisplayAddress)
                            && address.equals(sStatic.mPreviousActiveWifiDisplayAddress);
                    updateWifiDisplayRoute(route, d, status, disconnected);
                }
                if (d.equals(activeDisplay)) {
                    selectRouteStatic(route.getSupportedTypes(), route, false);
                }
            }
        }

        // Remove stale routes.
        for (int i = sStatic.mRoutes.size(); i-- > 0; ) {
            RouteInfo route = sStatic.mRoutes.get(i);
            if (route.mDeviceAddress != null) {
                WifiDisplay d = findWifiDisplay(displays, route.mDeviceAddress);
                if (d == null || !shouldShowWifiDisplay(d, activeDisplay)) {
                    removeRouteStatic(route);
                }
            }
        }

        // Remember the current active wifi display address so that we can infer disconnections.
        // TODO: This hack will go away once all of this is moved into the media router service.
        sStatic.mPreviousActiveWifiDisplayAddress = activeDisplayAddress;
    }

    private static boolean shouldShowWifiDisplay(WifiDisplay d, WifiDisplay activeDisplay) {
        return d.isRemembered() || d.equals(activeDisplay);
    }

    static int getWifiDisplayStatusCode(WifiDisplay d, WifiDisplayStatus wfdStatus) {
        int newStatus;
        if (wfdStatus.getScanState() == WifiDisplayStatus.SCAN_STATE_SCANNING) {
            newStatus = RouteInfo.STATUS_SCANNING;
        } else if (d.isAvailable()) {
            newStatus = d.canConnect() ?
                    RouteInfo.STATUS_AVAILABLE: RouteInfo.STATUS_IN_USE;
        } else {
            newStatus = RouteInfo.STATUS_NOT_AVAILABLE;
        }

        if (d.equals(wfdStatus.getActiveDisplay())) {
            final int activeState = wfdStatus.getActiveDisplayState();
            switch (activeState) {
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTED:
                    newStatus = RouteInfo.STATUS_CONNECTED;
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_CONNECTING:
                    newStatus = RouteInfo.STATUS_CONNECTING;
                    break;
                case WifiDisplayStatus.DISPLAY_STATE_NOT_CONNECTED:
                    Log.e(TAG, "Active display is not connected!");
                    break;
            }
        }

        return newStatus;
    }

    static boolean isWifiDisplayEnabled(WifiDisplay d, WifiDisplayStatus wfdStatus) {
        return d.isAvailable() && (d.canConnect() || d.equals(wfdStatus.getActiveDisplay()));
    }

    static RouteInfo makeWifiDisplayRoute(WifiDisplay display, WifiDisplayStatus wfdStatus) {
        final RouteInfo newRoute = new RouteInfo(sStatic.mSystemCategory);
        newRoute.mDeviceAddress = display.getDeviceAddress();
        newRoute.mSupportedTypes = ROUTE_TYPE_LIVE_AUDIO | ROUTE_TYPE_LIVE_VIDEO
                | ROUTE_TYPE_REMOTE_DISPLAY;
        newRoute.mVolumeHandling = RouteInfo.PLAYBACK_VOLUME_FIXED;
        newRoute.mPlaybackType = RouteInfo.PLAYBACK_TYPE_REMOTE;

        newRoute.setRealStatusCode(getWifiDisplayStatusCode(display, wfdStatus));
        newRoute.mEnabled = isWifiDisplayEnabled(display, wfdStatus);
        newRoute.mName = display.getFriendlyDisplayName();
        newRoute.mDescription = sStatic.mResources.getText(
                R.string.wireless_display_route_description);
        newRoute.updatePresentationDisplay();
        newRoute.mDeviceType = RouteInfo.DEVICE_TYPE_TV;
        return newRoute;
    }

    private static void updateWifiDisplayRoute(
            RouteInfo route, WifiDisplay display, WifiDisplayStatus wfdStatus,
            boolean disconnected) {
        boolean changed = false;
        final String newName = display.getFriendlyDisplayName();
        if (!route.getName().equals(newName)) {
            route.mName = newName;
            changed = true;
        }

        boolean enabled = isWifiDisplayEnabled(display, wfdStatus);
        changed |= route.mEnabled != enabled;
        route.mEnabled = enabled;

        changed |= route.setRealStatusCode(getWifiDisplayStatusCode(display, wfdStatus));

        if (changed) {
            dispatchRouteChanged(route);
        }

        if ((!enabled || disconnected) && route.isSelected()) {
            // Oops, no longer available. Reselect the default.
            selectDefaultRouteStatic();
        }
    }

    private static WifiDisplay findWifiDisplay(WifiDisplay[] displays, String deviceAddress) {
        for (int i = 0; i < displays.length; i++) {
            final WifiDisplay d = displays[i];
            if (d.getDeviceAddress().equals(deviceAddress)) {
                return d;
            }
        }
        return null;
    }

    private static RouteInfo findWifiDisplayRoute(WifiDisplay d) {
        final int count = sStatic.mRoutes.size();
        for (int i = 0; i < count; i++) {
            final RouteInfo info = sStatic.mRoutes.get(i);
            if (d.getDeviceAddress().equals(info.mDeviceAddress)) {
                return info;
            }
        }
        return null;
    }

    /**
     * Information about a media route.
     */
    public static class RouteInfo {
        CharSequence mName;
        @UnsupportedAppUsage
        int mNameResId;
        CharSequence mDescription;
        private CharSequence mStatus;
        int mSupportedTypes;
        int mDeviceType;
        RouteGroup mGroup;
        final RouteCategory mCategory;
        Drawable mIcon;
        // playback information
        int mPlaybackType = PLAYBACK_TYPE_LOCAL;
        int mVolumeMax = DEFAULT_PLAYBACK_MAX_VOLUME;
        int mVolume = DEFAULT_PLAYBACK_VOLUME;
        int mVolumeHandling = PLAYBACK_VOLUME_VARIABLE;
        int mPlaybackStream = AudioManager.STREAM_MUSIC;
        VolumeCallbackInfo mVcb;
        Display mPresentationDisplay;
        int mPresentationDisplayId = -1;

        String mDeviceAddress;
        boolean mEnabled = true;

        // An id by which the route is known to the media router service.
        // Null if this route only exists as an artifact within this process.
        String mGlobalRouteId;

        // A predetermined connection status that can override mStatus
        private int mRealStatusCode;
        private int mResolvedStatusCode;

        /** @hide */ public static final int STATUS_NONE = 0;
        /** @hide */ public static final int STATUS_SCANNING = 1;
        /** @hide */
        @UnsupportedAppUsage
        public static final int STATUS_CONNECTING = 2;
        /** @hide */ public static final int STATUS_AVAILABLE = 3;
        /** @hide */ public static final int STATUS_NOT_AVAILABLE = 4;
        /** @hide */ public static final int STATUS_IN_USE = 5;
        /** @hide */ public static final int STATUS_CONNECTED = 6;

        /** @hide */
        @IntDef({DEVICE_TYPE_UNKNOWN, DEVICE_TYPE_TV, DEVICE_TYPE_SPEAKER, DEVICE_TYPE_BLUETOOTH})
        @Retention(RetentionPolicy.SOURCE)
        public @interface DeviceType {}

        /**
         * The default receiver device type of the route indicating the type is unknown.
         *
         * @see #getDeviceType
         */
        public static final int DEVICE_TYPE_UNKNOWN = 0;

        /**
         * A receiver device type of the route indicating the presentation of the media is happening
         * on a TV.
         *
         * @see #getDeviceType
         */
        public static final int DEVICE_TYPE_TV = 1;

        /**
         * A receiver device type of the route indicating the presentation of the media is happening
         * on a speaker.
         *
         * @see #getDeviceType
         */
        public static final int DEVICE_TYPE_SPEAKER = 2;

        /**
         * A receiver device type of the route indicating the presentation of the media is happening
         * on a bluetooth device such as a bluetooth speaker.
         *
         * @see #getDeviceType
         */
        public static final int DEVICE_TYPE_BLUETOOTH = 3;

        private Object mTag;

        /** @hide */
        @IntDef({PLAYBACK_TYPE_LOCAL, PLAYBACK_TYPE_REMOTE})
        @Retention(RetentionPolicy.SOURCE)
        public @interface PlaybackType {}

        /**
         * The default playback type, "local", indicating the presentation of the media is happening
         * on the same device (e&#46;g&#46; a phone, a tablet) as where it is controlled from.
         * @see #getPlaybackType()
         */
        public final static int PLAYBACK_TYPE_LOCAL = 0;

        /**
         * A playback type indicating the presentation of the media is happening on
         * a different device (i&#46;e&#46; the remote device) than where it is controlled from.
         * @see #getPlaybackType()
         */
        public final static int PLAYBACK_TYPE_REMOTE = 1;

        /** @hide */
         @IntDef({PLAYBACK_VOLUME_FIXED,PLAYBACK_VOLUME_VARIABLE})
         @Retention(RetentionPolicy.SOURCE)
         private @interface PlaybackVolume {}

        /**
         * Playback information indicating the playback volume is fixed, i&#46;e&#46; it cannot be
         * controlled from this object. An example of fixed playback volume is a remote player,
         * playing over HDMI where the user prefers to control the volume on the HDMI sink, rather
         * than attenuate at the source.
         * @see #getVolumeHandling()
         */
        public final static int PLAYBACK_VOLUME_FIXED = 0;
        /**
         * Playback information indicating the playback volume is variable and can be controlled
         * from this object.
         * @see #getVolumeHandling()
         */
        public final static int PLAYBACK_VOLUME_VARIABLE = 1;

        /**
         * Default playback max volume if not set.
         * Hard-coded to the same number of steps as AudioService.MAX_STREAM_VOLUME[STREAM_MUSIC]
         *
         * @see #getVolumeMax()
         */
        private static final int DEFAULT_PLAYBACK_MAX_VOLUME = 15;

        /**
         * Default playback volume if not set.
         *
         * @see #getVolume()
         */
        private static final int DEFAULT_PLAYBACK_VOLUME = DEFAULT_PLAYBACK_MAX_VOLUME;

        /** @hide */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public RouteInfo(RouteCategory category) {
            mCategory = category;
            mDeviceType = DEVICE_TYPE_UNKNOWN;
        }

        /**
         * Gets the user-visible name of the route.
         * <p>
         * The route name identifies the destination represented by the route.
         * It may be a user-supplied name, an alias, or device serial number.
         * </p>
         *
         * @return The user-visible name of a media route.  This is the string presented
         * to users who may select this as the active route.
         */
        public CharSequence getName() {
            return getName(sStatic.mResources);
        }

        /**
         * Return the properly localized/resource user-visible name of this route.
         * <p>
         * The route name identifies the destination represented by the route.
         * It may be a user-supplied name, an alias, or device serial number.
         * </p>
         *
         * @param context Context used to resolve the correct configuration to load
         * @return The user-visible name of a media route.  This is the string presented
         * to users who may select this as the active route.
         */
        public CharSequence getName(Context context) {
            return getName(context.getResources());
        }

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        CharSequence getName(Resources res) {
            if (mNameResId != 0) {
                return res.getText(mNameResId);
            }
            return mName;
        }

        /**
         * Gets the user-visible description of the route.
         * <p>
         * The route description describes the kind of destination represented by the route.
         * It may be a user-supplied string, a model number or brand of device.
         * </p>
         *
         * @return The description of the route, or null if none.
         */
        public CharSequence getDescription() {
            return mDescription;
        }

        /**
         * @return The user-visible status for a media route. This may include a description
         * of the currently playing media, if available.
         */
        public CharSequence getStatus() {
            return mStatus;
        }

        /**
         * Set this route's status by predetermined status code. If the caller
         * should dispatch a route changed event this call will return true;
         */
        boolean setRealStatusCode(int statusCode) {
            if (mRealStatusCode != statusCode) {
                mRealStatusCode = statusCode;
                return resolveStatusCode();
            }
            return false;
        }

        /**
         * Resolves the status code whenever the real status code or selection state
         * changes.
         */
        boolean resolveStatusCode() {
            int statusCode = mRealStatusCode;
            if (isSelected()) {
                switch (statusCode) {
                    // If the route is selected and its status appears to be between states
                    // then report it as connecting even though it has not yet had a chance
                    // to officially move into the CONNECTING state.  Note that routes in
                    // the NONE state are assumed to not require an explicit connection
                    // lifecycle whereas those that are AVAILABLE are assumed to have
                    // to eventually proceed to CONNECTED.
                    case STATUS_AVAILABLE:
                    case STATUS_SCANNING:
                        statusCode = STATUS_CONNECTING;
                        break;
                }
            }
            if (mResolvedStatusCode == statusCode) {
                return false;
            }

            mResolvedStatusCode = statusCode;
            int resId;
            switch (statusCode) {
                case STATUS_SCANNING:
                    resId = R.string.media_route_status_scanning;
                    break;
                case STATUS_CONNECTING:
                    resId = R.string.media_route_status_connecting;
                    break;
                case STATUS_AVAILABLE:
                    resId = R.string.media_route_status_available;
                    break;
                case STATUS_NOT_AVAILABLE:
                    resId = R.string.media_route_status_not_available;
                    break;
                case STATUS_IN_USE:
                    resId = R.string.media_route_status_in_use;
                    break;
                case STATUS_CONNECTED:
                case STATUS_NONE:
                default:
                    resId = 0;
                    break;
            }
            mStatus = resId != 0 ? sStatic.mResources.getText(resId) : null;
            return true;
        }

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public int getStatusCode() {
            return mResolvedStatusCode;
        }

        /**
         * @return A media type flag set describing which types this route supports.
         */
        public int getSupportedTypes() {
            return mSupportedTypes;
        }

        /**
         * Gets the type of the receiver device associated with this route.
         *
         * @return The type of the receiver device associated with this route:
         * {@link #DEVICE_TYPE_BLUETOOTH}, {@link #DEVICE_TYPE_TV}, {@link #DEVICE_TYPE_SPEAKER},
         * or {@link #DEVICE_TYPE_UNKNOWN}.
         */
        @DeviceType
        public int getDeviceType() {
            return mDeviceType;
        }

        /** @hide */
        @UnsupportedAppUsage
        public boolean matchesTypes(int types) {
            return (mSupportedTypes & types) != 0;
        }

        /**
         * @return The group that this route belongs to.
         */
        public RouteGroup getGroup() {
            return mGroup;
        }

        /**
         * @return the category this route belongs to.
         */
        public RouteCategory getCategory() {
            return mCategory;
        }

        /**
         * Get the icon representing this route.
         * This icon will be used in picker UIs if available.
         *
         * @return the icon representing this route or null if no icon is available
         */
        public Drawable getIconDrawable() {
            return mIcon;
        }

        /**
         * Set an application-specific tag object for this route.
         * The application may use this to store arbitrary data associated with the
         * route for internal tracking.
         *
         * <p>Note that the lifespan of a route may be well past the lifespan of
         * an Activity or other Context; take care that objects you store here
         * will not keep more data in memory alive than you intend.</p>
         *
         * @param tag Arbitrary, app-specific data for this route to hold for later use
         */
        public void setTag(Object tag) {
            mTag = tag;
            routeUpdated();
        }

        /**
         * @return The tag object previously set by the application
         * @see #setTag(Object)
         */
        public Object getTag() {
            return mTag;
        }

        /**
         * @return the type of playback associated with this route
         * @see UserRouteInfo#setPlaybackType(int)
         */
        @PlaybackType
        public int getPlaybackType() {
            return mPlaybackType;
        }

        /**
         * @return the stream over which the playback associated with this route is performed
         * @see UserRouteInfo#setPlaybackStream(int)
         */
        public int getPlaybackStream() {
            return mPlaybackStream;
        }

        /**
         * Return the current volume for this route. Depending on the route, this may only
         * be valid if the route is currently selected.
         *
         * @return the volume at which the playback associated with this route is performed
         * @see UserRouteInfo#setVolume(int)
         */
        public int getVolume() {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                return sStatic.getStreamVolume(mPlaybackStream);
            } else {
                return mVolume;
            }
        }

        /**
         * Request a volume change for this route.
         * @param volume value between 0 and getVolumeMax
         */
        public void requestSetVolume(int volume) {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                try {
                    sStatic.mAudioService.setStreamVolumeWithAttribution(mPlaybackStream, volume, 0,
                            ActivityThread.currentPackageName(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error setting local stream volume", e);
                }
            } else {
                sStatic.requestSetVolume(this, volume);
            }
        }

        /**
         * Request an incremental volume update for this route.
         * @param direction Delta to apply to the current volume
         */
        public void requestUpdateVolume(int direction) {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                try {
                    final int volume =
                            Math.max(0, Math.min(getVolume() + direction, getVolumeMax()));
                    sStatic.mAudioService.setStreamVolumeWithAttribution(mPlaybackStream, volume, 0,
                            ActivityThread.currentPackageName(), null);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error setting local stream volume", e);
                }
            } else {
                sStatic.requestUpdateVolume(this, direction);
            }
        }

        /**
         * @return the maximum volume at which the playback associated with this route is performed
         * @see UserRouteInfo#setVolumeMax(int)
         */
        public int getVolumeMax() {
            if (mPlaybackType == PLAYBACK_TYPE_LOCAL) {
                int volMax = 0;
                try {
                    volMax = sStatic.mAudioService.getStreamMaxVolume(mPlaybackStream);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error getting local stream volume", e);
                }
                return volMax;
            } else {
                return mVolumeMax;
            }
        }

        /**
         * @return how volume is handling on the route
         * @see UserRouteInfo#setVolumeHandling(int)
         */
        @PlaybackVolume
        public int getVolumeHandling() {
            return mVolumeHandling;
        }

        /**
         * Gets the {@link Display} that should be used by the application to show
         * a {@link android.app.Presentation} on an external display when this route is selected.
         * Depending on the route, this may only be valid if the route is currently
         * selected.
         * <p>
         * The preferred presentation display may change independently of the route
         * being selected or unselected.  For example, the presentation display
         * of the default system route may change when an external HDMI display is connected
         * or disconnected even though the route itself has not changed.
         * </p><p>
         * This method may return null if there is no external display associated with
         * the route or if the display is not ready to show UI yet.
         * </p><p>
         * The application should listen for changes to the presentation display
         * using the {@link Callback#onRoutePresentationDisplayChanged} callback and
         * show or dismiss its {@link android.app.Presentation} accordingly when the display
         * becomes available or is removed.
         * </p><p>
         * This method only makes sense for {@link #ROUTE_TYPE_LIVE_VIDEO live video} routes.
         * </p>
         *
         * @return The preferred presentation display to use when this route is
         * selected or null if none.
         *
         * @see #ROUTE_TYPE_LIVE_VIDEO
         * @see android.app.Presentation
         */
        public Display getPresentationDisplay() {
            return mPresentationDisplay;
        }

        /** @hide */
        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public boolean updatePresentationDisplay() {
            Display display = choosePresentationDisplay();
            if (mPresentationDisplay != display) {
                mPresentationDisplay = display;
                return true;
            }
            return false;
        }

        private Display choosePresentationDisplay() {
            if ((getSupportedTypes() & ROUTE_TYPE_LIVE_VIDEO) == 0) {
                return null;
            }
            final Display[] displays = getAllPresentationDisplays();
            if (displays == null || displays.length == 0) {
                return null;
            }

            // Ensure that the specified display is valid for presentations.
            // This check will normally disallow the default display unless it was
            // configured as a presentation display for some reason.
            if (mPresentationDisplayId >= 0) {
                for (Display display : displays) {
                    if (display.getDisplayId() == mPresentationDisplayId) {
                        return display;
                    }
                }
                return null;
            }

            // Find the indicated Wifi display by its address.
            if (getDeviceAddress() != null) {
                for (Display display : displays) {
                    if (display.getType() == Display.TYPE_WIFI
                            && displayAddressEquals(display)) {
                        return display;
                    }
                }
            }

            // Returns the first hard-wired display.
            for (Display display : displays) {
                if (display.getType() == Display.TYPE_EXTERNAL) {
                    return display;
                }
            }

            // Returns the first non-default built-in display.
            for (Display display : displays) {
                if (display.getType() == Display.TYPE_INTERNAL) {
                    return display;
                }
            }

            // For the default route, choose the first presentation display from the list.
            if (this == getDefaultAudioVideo()) {
                return displays[0];
            }
            return null;
        }

        /** @hide */
        @VisibleForTesting
        public Display[] getAllPresentationDisplays() {
            return sStatic.getAllPresentationDisplays();
        }

        /** @hide */
        @VisibleForTesting
        public RouteInfo getDefaultAudioVideo() {
            return sStatic.mDefaultAudioVideo;
        }

        private boolean displayAddressEquals(Display display) {
            final DisplayAddress displayAddress = display.getAddress();
            // mDeviceAddress recorded mac address. If displayAddress is not a kind of Network,
            // return false early.
            if (!(displayAddress instanceof DisplayAddress.Network)) {
                return false;
            }
            final DisplayAddress.Network networkAddress = (DisplayAddress.Network) displayAddress;
            return getDeviceAddress().equals(networkAddress.toString());
        }

        /** @hide */
        @UnsupportedAppUsage
        public String getDeviceAddress() {
            return mDeviceAddress;
        }

        /**
         * Returns true if this route is enabled and may be selected.
         *
         * @return True if this route is enabled.
         */
        public boolean isEnabled() {
            return mEnabled;
        }

        /**
         * Returns true if the route is in the process of connecting and is not
         * yet ready for use.
         *
         * @return True if this route is in the process of connecting.
         */
        public boolean isConnecting() {
            return mResolvedStatusCode == STATUS_CONNECTING;
        }

        /** @hide */
        @UnsupportedAppUsage
        public boolean isSelected() {
            return this == sStatic.mSelectedRoute;
        }

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public boolean isDefault() {
            return this == sStatic.mDefaultAudioVideo;
        }

        /** @hide */
        public boolean isBluetooth() {
            return mDeviceType == RouteInfo.DEVICE_TYPE_BLUETOOTH;
        }

        /** @hide */
        @UnsupportedAppUsage
        public void select() {
            selectRouteStatic(mSupportedTypes, this, true);
        }

        void setStatusInt(CharSequence status) {
            if (!status.equals(mStatus)) {
                mStatus = status;
                if (mGroup != null) {
                    mGroup.memberStatusChanged(this, status);
                }
                routeUpdated();
            }
        }

        final IRemoteVolumeObserver.Stub mRemoteVolObserver = new IRemoteVolumeObserver.Stub() {
            @Override
            public void dispatchRemoteVolumeUpdate(final int direction, final int value) {
                sStatic.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mVcb != null) {
                            if (direction != 0) {
                                mVcb.vcb.onVolumeUpdateRequest(mVcb.route, direction);
                            } else {
                                mVcb.vcb.onVolumeSetRequest(mVcb.route, value);
                            }
                        }
                    }
                });
            }
        };

        void routeUpdated() {
            updateRoute(this);
        }

        @Override
        public String toString() {
            String supportedTypes = typesToString(getSupportedTypes());
            return getClass().getSimpleName() + "{ name=" + getName() +
                    ", description=" + getDescription() +
                    ", status=" + getStatus() +
                    ", category=" + getCategory() +
                    ", supportedTypes=" + supportedTypes +
                    ", presentationDisplay=" + mPresentationDisplay + " }";
        }
    }

    /**
     * Information about a route that the application may define and modify.
     * A user route defaults to {@link RouteInfo#PLAYBACK_TYPE_REMOTE} and
     * {@link RouteInfo#PLAYBACK_VOLUME_FIXED}.
     *
     * @see MediaRouter.RouteInfo
     */
    public static class UserRouteInfo extends RouteInfo {
        RemoteControlClient mRcc;
        SessionVolumeProvider mSvp;

        UserRouteInfo(RouteCategory category) {
            super(category);
            mSupportedTypes = ROUTE_TYPE_USER;
            mPlaybackType = PLAYBACK_TYPE_REMOTE;
            mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        }

        /**
         * Set the user-visible name of this route.
         * @param name Name to display to the user to describe this route
         */
        public void setName(CharSequence name) {
            mNameResId = 0;
            mName = name;
            routeUpdated();
        }

        /**
         * Set the user-visible name of this route.
         * <p>
         * The route name identifies the destination represented by the route.
         * It may be a user-supplied name, an alias, or device serial number.
         * </p>
         *
         * @param resId Resource ID of the name to display to the user to describe this route
         */
        public void setName(int resId) {
            mNameResId = resId;
            mName = null;
            routeUpdated();
        }

        /**
         * Set the user-visible description of this route.
         * <p>
         * The route description describes the kind of destination represented by the route.
         * It may be a user-supplied string, a model number or brand of device.
         * </p>
         *
         * @param description The description of the route, or null if none.
         */
        public void setDescription(CharSequence description) {
            mDescription = description;
            routeUpdated();
        }

        /**
         * Set the current user-visible status for this route.
         * @param status Status to display to the user to describe what the endpoint
         * of this route is currently doing
         */
        public void setStatus(CharSequence status) {
            setStatusInt(status);
        }

        /**
         * Set the RemoteControlClient responsible for reporting playback info for this
         * user route.
         *
         * <p>If this route manages remote playback, the data exposed by this
         * RemoteControlClient will be used to reflect and update information
         * such as route volume info in related UIs.</p>
         *
         * <p>The RemoteControlClient must have been previously registered with
         * {@link AudioManager#registerRemoteControlClient(RemoteControlClient)}.</p>
         *
         * @param rcc RemoteControlClient associated with this route
         */
        public void setRemoteControlClient(RemoteControlClient rcc) {
            mRcc = rcc;
            updatePlaybackInfoOnRcc();
        }

        /**
         * Retrieve the RemoteControlClient associated with this route, if one has been set.
         *
         * @return the RemoteControlClient associated with this route
         * @see #setRemoteControlClient(RemoteControlClient)
         */
        public RemoteControlClient getRemoteControlClient() {
            return mRcc;
        }

        /**
         * Set an icon that will be used to represent this route.
         * The system may use this icon in picker UIs or similar.
         *
         * @param icon icon drawable to use to represent this route
         */
        public void setIconDrawable(Drawable icon) {
            mIcon = icon;
        }

        /**
         * Set an icon that will be used to represent this route.
         * The system may use this icon in picker UIs or similar.
         *
         * @param resId Resource ID of an icon drawable to use to represent this route
         */
        public void setIconResource(@DrawableRes int resId) {
            setIconDrawable(sStatic.mResources.getDrawable(resId));
        }

        /**
         * Set a callback to be notified of volume update requests
         * @param vcb
         */
        public void setVolumeCallback(VolumeCallback vcb) {
            mVcb = new VolumeCallbackInfo(vcb, this);
        }

        /**
         * Defines whether playback associated with this route is "local"
         *    ({@link RouteInfo#PLAYBACK_TYPE_LOCAL}) or "remote"
         *    ({@link RouteInfo#PLAYBACK_TYPE_REMOTE}).
         * @param type
         */
        public void setPlaybackType(@RouteInfo.PlaybackType int type) {
            if (mPlaybackType != type) {
                mPlaybackType = type;
                configureSessionVolume();
            }
        }

        /**
         * Defines whether volume for the playback associated with this route is fixed
         * ({@link RouteInfo#PLAYBACK_VOLUME_FIXED}) or can modified
         * ({@link RouteInfo#PLAYBACK_VOLUME_VARIABLE}).
         * @param volumeHandling
         */
        public void setVolumeHandling(@RouteInfo.PlaybackVolume int volumeHandling) {
            if (mVolumeHandling != volumeHandling) {
                mVolumeHandling = volumeHandling;
                configureSessionVolume();
            }
        }

        /**
         * Defines at what volume the playback associated with this route is performed (for user
         * feedback purposes). This information is only used when the playback is not local.
         * @param volume
         */
        public void setVolume(int volume) {
            volume = Math.max(0, Math.min(volume, getVolumeMax()));
            if (mVolume != volume) {
                mVolume = volume;
                if (mSvp != null) {
                    mSvp.setCurrentVolume(mVolume);
                }
                dispatchRouteVolumeChanged(this);
                if (mGroup != null) {
                    mGroup.memberVolumeChanged(this);
                }
            }
        }

        @Override
        public void requestSetVolume(int volume) {
            if (mVolumeHandling == PLAYBACK_VOLUME_VARIABLE) {
                if (mVcb == null) {
                    Log.e(TAG, "Cannot requestSetVolume on user route - no volume callback set");
                    return;
                }
                mVcb.vcb.onVolumeSetRequest(this, volume);
            }
        }

        @Override
        public void requestUpdateVolume(int direction) {
            if (mVolumeHandling == PLAYBACK_VOLUME_VARIABLE) {
                if (mVcb == null) {
                    Log.e(TAG, "Cannot requestChangeVolume on user route - no volumec callback set");
                    return;
                }
                mVcb.vcb.onVolumeUpdateRequest(this, direction);
            }
        }

        /**
         * Defines the maximum volume at which the playback associated with this route is performed
         * (for user feedback purposes). This information is only used when the playback is not
         * local.
         * @param volumeMax
         */
        public void setVolumeMax(int volumeMax) {
            if (mVolumeMax != volumeMax) {
                mVolumeMax = volumeMax;
                configureSessionVolume();
            }
        }

        /**
         * Defines over what stream type the media is presented.
         * @param stream
         */
        public void setPlaybackStream(int stream) {
            if (mPlaybackStream != stream) {
                mPlaybackStream = stream;
                configureSessionVolume();
            }
        }

        private void updatePlaybackInfoOnRcc() {
            configureSessionVolume();
        }

        private void configureSessionVolume() {
            if (mRcc == null) {
                if (DEBUG) {
                    Log.d(TAG, "No Rcc to configure volume for route " + getName());
                }
                return;
            }
            MediaSession session = mRcc.getMediaSession();
            if (session == null) {
                if (DEBUG) {
                    Log.d(TAG, "Rcc has no session to configure volume");
                }
                return;
            }
            if (mPlaybackType == PLAYBACK_TYPE_REMOTE) {
                int volumeControl = VolumeProvider.VOLUME_CONTROL_FIXED;
                switch (mVolumeHandling) {
                    case PLAYBACK_VOLUME_VARIABLE:
                        volumeControl = VolumeProvider.VOLUME_CONTROL_ABSOLUTE;
                        break;
                    case PLAYBACK_VOLUME_FIXED:
                    default:
                        break;
                }
                // Only register a new listener if necessary
                if (mSvp == null || mSvp.getVolumeControl() != volumeControl
                        || mSvp.getMaxVolume() != mVolumeMax) {
                    mSvp = new SessionVolumeProvider(volumeControl, mVolumeMax, mVolume);
                    session.setPlaybackToRemote(mSvp);
                }
            } else {
                // We only know how to handle local and remote, fall back to local if not remote.
                AudioAttributes.Builder bob = new AudioAttributes.Builder();
                bob.setLegacyStreamType(mPlaybackStream);
                session.setPlaybackToLocal(bob.build());
                mSvp = null;
            }
        }

        class SessionVolumeProvider extends VolumeProvider {

            SessionVolumeProvider(int volumeControl, int maxVolume, int currentVolume) {
                super(volumeControl, maxVolume, currentVolume);
            }

            @Override
            public void onSetVolumeTo(final int volume) {
                sStatic.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mVcb != null) {
                            mVcb.vcb.onVolumeSetRequest(mVcb.route, volume);
                        }
                    }
                });
            }

            @Override
            public void onAdjustVolume(final int direction) {
                sStatic.mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mVcb != null) {
                            mVcb.vcb.onVolumeUpdateRequest(mVcb.route, direction);
                        }
                    }
                });
            }
        }
    }

    /**
     * Information about a route that consists of multiple other routes in a group.
     */
    public static class RouteGroup extends RouteInfo {
        final ArrayList<RouteInfo> mRoutes = new ArrayList<RouteInfo>();
        private boolean mUpdateName;

        RouteGroup(RouteCategory category) {
            super(category);
            mGroup = this;
            mVolumeHandling = PLAYBACK_VOLUME_FIXED;
        }

        @Override
        CharSequence getName(Resources res) {
            if (mUpdateName) updateName();
            return super.getName(res);
        }

        /**
         * Add a route to this group. The route must not currently belong to another group.
         *
         * @param route route to add to this group
         */
        public void addRoute(RouteInfo route) {
            if (route.getGroup() != null) {
                throw new IllegalStateException("Route " + route + " is already part of a group.");
            }
            if (route.getCategory() != mCategory) {
                throw new IllegalArgumentException(
                        "Route cannot be added to a group with a different category. " +
                            "(Route category=" + route.getCategory() +
                            " group category=" + mCategory + ")");
            }
            final int at = mRoutes.size();
            mRoutes.add(route);
            route.mGroup = this;
            mUpdateName = true;
            updateVolume();
            routeUpdated();
            dispatchRouteGrouped(route, this, at);
        }

        /**
         * Add a route to this group before the specified index.
         *
         * @param route route to add
         * @param insertAt insert the new route before this index
         */
        public void addRoute(RouteInfo route, int insertAt) {
            if (route.getGroup() != null) {
                throw new IllegalStateException("Route " + route + " is already part of a group.");
            }
            if (route.getCategory() != mCategory) {
                throw new IllegalArgumentException(
                        "Route cannot be added to a group with a different category. " +
                            "(Route category=" + route.getCategory() +
                            " group category=" + mCategory + ")");
            }
            mRoutes.add(insertAt, route);
            route.mGroup = this;
            mUpdateName = true;
            updateVolume();
            routeUpdated();
            dispatchRouteGrouped(route, this, insertAt);
        }

        /**
         * Remove a route from this group.
         *
         * @param route route to remove
         */
        public void removeRoute(RouteInfo route) {
            if (route.getGroup() != this) {
                throw new IllegalArgumentException("Route " + route +
                        " is not a member of this group.");
            }
            mRoutes.remove(route);
            route.mGroup = null;
            mUpdateName = true;
            updateVolume();
            dispatchRouteUngrouped(route, this);
            routeUpdated();
        }

        /**
         * Remove the route at the specified index from this group.
         *
         * @param index index of the route to remove
         */
        public void removeRoute(int index) {
            RouteInfo route = mRoutes.remove(index);
            route.mGroup = null;
            mUpdateName = true;
            updateVolume();
            dispatchRouteUngrouped(route, this);
            routeUpdated();
        }

        /**
         * @return The number of routes in this group
         */
        public int getRouteCount() {
            return mRoutes.size();
        }

        /**
         * Return the route in this group at the specified index
         *
         * @param index Index to fetch
         * @return The route at index
         */
        public RouteInfo getRouteAt(int index) {
            return mRoutes.get(index);
        }

        /**
         * Set an icon that will be used to represent this group.
         * The system may use this icon in picker UIs or similar.
         *
         * @param icon icon drawable to use to represent this group
         */
        public void setIconDrawable(Drawable icon) {
            mIcon = icon;
        }

        /**
         * Set an icon that will be used to represent this group.
         * The system may use this icon in picker UIs or similar.
         *
         * @param resId Resource ID of an icon drawable to use to represent this group
         */
        public void setIconResource(@DrawableRes int resId) {
            setIconDrawable(sStatic.mResources.getDrawable(resId));
        }

        @Override
        public void requestSetVolume(int volume) {
            final int maxVol = getVolumeMax();
            if (maxVol == 0) {
                return;
            }

            final float scaledVolume = (float) volume / maxVol;
            final int routeCount = getRouteCount();
            for (int i = 0; i < routeCount; i++) {
                final RouteInfo route = getRouteAt(i);
                final int routeVol = (int) (scaledVolume * route.getVolumeMax());
                route.requestSetVolume(routeVol);
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        @Override
        public void requestUpdateVolume(int direction) {
            final int maxVol = getVolumeMax();
            if (maxVol == 0) {
                return;
            }

            final int routeCount = getRouteCount();
            int volume = 0;
            for (int i = 0; i < routeCount; i++) {
                final RouteInfo route = getRouteAt(i);
                route.requestUpdateVolume(direction);
                final int routeVol = route.getVolume();
                if (routeVol > volume) {
                    volume = routeVol;
                }
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        void memberNameChanged(RouteInfo info, CharSequence name) {
            mUpdateName = true;
            routeUpdated();
        }

        void memberStatusChanged(RouteInfo info, CharSequence status) {
            setStatusInt(status);
        }

        void memberVolumeChanged(RouteInfo info) {
            updateVolume();
        }

        void updateVolume() {
            // A group always represents the highest component volume value.
            final int routeCount = getRouteCount();
            int volume = 0;
            for (int i = 0; i < routeCount; i++) {
                final int routeVol = getRouteAt(i).getVolume();
                if (routeVol > volume) {
                    volume = routeVol;
                }
            }
            if (volume != mVolume) {
                mVolume = volume;
                dispatchRouteVolumeChanged(this);
            }
        }

        @Override
        void routeUpdated() {
            int types = 0;
            final int count = mRoutes.size();
            if (count == 0) {
                // Don't keep empty groups in the router.
                MediaRouter.removeRouteStatic(this);
                return;
            }

            int maxVolume = 0;
            boolean isLocal = true;
            boolean isFixedVolume = true;
            for (int i = 0; i < count; i++) {
                final RouteInfo route = mRoutes.get(i);
                types |= route.mSupportedTypes;
                final int routeMaxVolume = route.getVolumeMax();
                if (routeMaxVolume > maxVolume) {
                    maxVolume = routeMaxVolume;
                }
                isLocal &= route.getPlaybackType() == PLAYBACK_TYPE_LOCAL;
                isFixedVolume &= route.getVolumeHandling() == PLAYBACK_VOLUME_FIXED;
            }
            mPlaybackType = isLocal ? PLAYBACK_TYPE_LOCAL : PLAYBACK_TYPE_REMOTE;
            mVolumeHandling = isFixedVolume ? PLAYBACK_VOLUME_FIXED : PLAYBACK_VOLUME_VARIABLE;
            mSupportedTypes = types;
            mVolumeMax = maxVolume;
            mIcon = count == 1 ? mRoutes.get(0).getIconDrawable() : null;
            super.routeUpdated();
        }

        void updateName() {
            final StringBuilder sb = new StringBuilder();
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                final RouteInfo info = mRoutes.get(i);
                // TODO: There's probably a much more correct way to localize this.
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(info.getName());
            }
            mName = sb.toString();
            mUpdateName = false;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(super.toString());
            sb.append('[');
            final int count = mRoutes.size();
            for (int i = 0; i < count; i++) {
                if (i > 0) sb.append(", ");
                sb.append(mRoutes.get(i));
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /**
     * Definition of a category of routes. All routes belong to a category.
     */
    public static class RouteCategory {
        CharSequence mName;
        int mNameResId;
        int mTypes;
        final boolean mGroupable;
        boolean mIsSystem;

        RouteCategory(CharSequence name, int types, boolean groupable) {
            mName = name;
            mTypes = types;
            mGroupable = groupable;
        }

        RouteCategory(int nameResId, int types, boolean groupable) {
            mNameResId = nameResId;
            mTypes = types;
            mGroupable = groupable;
        }

        /**
         * @return the name of this route category
         */
        public CharSequence getName() {
            return getName(sStatic.mResources);
        }

        /**
         * Return the properly localized/configuration dependent name of this RouteCategory.
         *
         * @param context Context to resolve name resources
         * @return the name of this route category
         */
        public CharSequence getName(Context context) {
            return getName(context.getResources());
        }

        CharSequence getName(Resources res) {
            if (mNameResId != 0) {
                return res.getText(mNameResId);
            }
            return mName;
        }

        /**
         * Return the current list of routes in this category that have been added
         * to the MediaRouter.
         *
         * <p>This list will not include routes that are nested within RouteGroups.
         * A RouteGroup is treated as a single route within its category.</p>
         *
         * @param out a List to fill with the routes in this category. If this parameter is
         *            non-null, it will be cleared, filled with the current routes with this
         *            category, and returned. If this parameter is null, a new List will be
         *            allocated to report the category's current routes.
         * @return A list with the routes in this category that have been added to the MediaRouter.
         */
        public List<RouteInfo> getRoutes(List<RouteInfo> out) {
            if (out == null) {
                out = new ArrayList<RouteInfo>();
            } else {
                out.clear();
            }

            final int count = getRouteCountStatic();
            for (int i = 0; i < count; i++) {
                final RouteInfo route = getRouteAtStatic(i);
                if (route.mCategory == this) {
                    out.add(route);
                }
            }
            return out;
        }

        /**
         * @return Flag set describing the route types supported by this category
         */
        public int getSupportedTypes() {
            return mTypes;
        }

        /**
         * Return whether or not this category supports grouping.
         *
         * <p>If this method returns true, all routes obtained from this category
         * via calls to {@link #getRouteAt(int)} will be {@link MediaRouter.RouteGroup}s.</p>
         *
         * @return true if this category supports
         */
        public boolean isGroupable() {
            return mGroupable;
        }

        /**
         * @return true if this is the category reserved for system routes.
         * @hide
         */
        public boolean isSystem() {
            return mIsSystem;
        }

        @Override
        public String toString() {
            return "RouteCategory{ name=" + getName() + " types=" + typesToString(mTypes) +
                    " groupable=" + mGroupable + " }";
        }
    }

    static class CallbackInfo {
        public int type;
        public int flags;
        public final Callback cb;
        public final MediaRouter router;

        public CallbackInfo(Callback cb, int type, int flags, MediaRouter router) {
            this.cb = cb;
            this.type = type;
            this.flags = flags;
            this.router = router;
        }

        public boolean filterRouteEvent(RouteInfo route) {
            return filterRouteEvent(route.mSupportedTypes);
        }

        public boolean filterRouteEvent(int supportedTypes) {
            return (flags & CALLBACK_FLAG_UNFILTERED_EVENTS) != 0
                    || (type & supportedTypes) != 0;
        }
    }

    /**
     * Interface for receiving events about media routing changes.
     * All methods of this interface will be called from the application's main thread.
     * <p>
     * A Callback will only receive events relevant to routes that the callback
     * was registered for unless the {@link MediaRouter#CALLBACK_FLAG_UNFILTERED_EVENTS}
     * flag was specified in {@link MediaRouter#addCallback(int, Callback, int)}.
     * </p>
     *
     * @see MediaRouter#addCallback(int, Callback, int)
     * @see MediaRouter#removeCallback(Callback)
     */
    public static abstract class Callback {
        /**
         * Called when the supplied route becomes selected as the active route
         * for the given route type.
         *
         * @param router the MediaRouter reporting the event
         * @param type Type flag set indicating the routes that have been selected
         * @param info Route that has been selected for the given route types
         */
        public abstract void onRouteSelected(MediaRouter router, int type, RouteInfo info);

        /**
         * Called when the supplied route becomes unselected as the active route
         * for the given route type.
         *
         * @param router the MediaRouter reporting the event
         * @param type Type flag set indicating the routes that have been unselected
         * @param info Route that has been unselected for the given route types
         */
        public abstract void onRouteUnselected(MediaRouter router, int type, RouteInfo info);

        /**
         * Called when a route for the specified type was added.
         *
         * @param router the MediaRouter reporting the event
         * @param info Route that has become available for use
         */
        public abstract void onRouteAdded(MediaRouter router, RouteInfo info);

        /**
         * Called when a route for the specified type was removed.
         *
         * @param router the MediaRouter reporting the event
         * @param info Route that has been removed from availability
         */
        public abstract void onRouteRemoved(MediaRouter router, RouteInfo info);

        /**
         * Called when an aspect of the indicated route has changed.
         *
         * <p>This will not indicate that the types supported by this route have
         * changed, only that cosmetic info such as name or status have been updated.</p>
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was changed
         */
        public abstract void onRouteChanged(MediaRouter router, RouteInfo info);

        /**
         * Called when a route is added to a group.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was added
         * @param group The group the route was added to
         * @param index The route index within group that info was added at
         */
        public abstract void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index);

        /**
         * Called when a route is removed from a group.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route that was removed
         * @param group The group the route was removed from
         */
        public abstract void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group);

        /**
         * Called when a route's volume changes.
         *
         * @param router the MediaRouter reporting the event
         * @param info The route with altered volume
         */
        public abstract void onRouteVolumeChanged(MediaRouter router, RouteInfo info);

        /**
         * Called when a route's presentation display changes.
         * <p>
         * This method is called whenever the route's presentation display becomes
         * available, is removes or has changes to some of its properties (such as its size).
         * </p>
         *
         * @param router the MediaRouter reporting the event
         * @param info The route whose presentation display changed
         *
         * @see RouteInfo#getPresentationDisplay()
         */
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
        }
    }

    /**
     * Stub implementation of {@link MediaRouter.Callback}.
     * Each abstract method is defined as a no-op. Override just the ones
     * you need.
     */
    public static class SimpleCallback extends Callback {

        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
        }

        @Override
        public void onRouteAdded(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteRemoved(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteChanged(MediaRouter router, RouteInfo info) {
        }

        @Override
        public void onRouteGrouped(MediaRouter router, RouteInfo info, RouteGroup group,
                int index) {
        }

        @Override
        public void onRouteUngrouped(MediaRouter router, RouteInfo info, RouteGroup group) {
        }

        @Override
        public void onRouteVolumeChanged(MediaRouter router, RouteInfo info) {
        }
    }

    static class VolumeCallbackInfo {
        public final VolumeCallback vcb;
        public final RouteInfo route;

        public VolumeCallbackInfo(VolumeCallback vcb, RouteInfo route) {
            this.vcb = vcb;
            this.route = route;
        }
    }

    /**
     * Interface for receiving events about volume changes.
     * All methods of this interface will be called from the application's main thread.
     *
     * <p>A VolumeCallback will only receive events relevant to routes that the callback
     * was registered for.</p>
     *
     * @see UserRouteInfo#setVolumeCallback(VolumeCallback)
     */
    public static abstract class VolumeCallback {
        /**
         * Called when the volume for the route should be increased or decreased.
         * @param info the route affected by this event
         * @param direction an integer indicating whether the volume is to be increased
         *     (positive value) or decreased (negative value).
         *     For bundled changes, the absolute value indicates the number of changes
         *     in the same direction, e.g. +3 corresponds to three "volume up" changes.
         */
        public abstract void onVolumeUpdateRequest(RouteInfo info, int direction);
        /**
         * Called when the volume for the route should be set to the given value
         * @param info the route affected by this event
         * @param volume an integer indicating the new volume value that should be used, always
         *     between 0 and the value set by {@link UserRouteInfo#setVolumeMax(int)}.
         */
        public abstract void onVolumeSetRequest(RouteInfo info, int volume);
    }

    static class VolumeChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                final int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE,
                        -1);
                final int newVolume = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);
                sStatic.mStreamVolume.put(streamType, newVolume);
                if (streamType != AudioManager.STREAM_MUSIC) {
                    return;
                }

                final int oldVolume = intent.getIntExtra(
                        AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, 0);
                if (newVolume != oldVolume) {
                    systemVolumeChanged(newVolume);
                }
            }
        }
    }

    static class WifiDisplayStatusChangedReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED)) {
                updateWifiDisplayStatus((WifiDisplayStatus) intent.getParcelableExtra(
                        DisplayManager.EXTRA_WIFI_DISPLAY_STATUS));
            }
        }
    }
}
