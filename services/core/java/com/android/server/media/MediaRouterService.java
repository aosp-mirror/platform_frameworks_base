/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioRoutesInfo;
import android.media.AudioSystem;
import android.media.IAudioRoutesObserver;
import android.media.IAudioService;
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.IMediaRouterService;
import android.media.MediaRoute2Info;
import android.media.MediaRouter;
import android.media.MediaRouterClientState;
import android.media.RemoteDisplayState;
import android.media.RemoteDisplayState.RemoteDisplayInfo;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.IntArray;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;

import com.android.internal.util.DumpUtils;
import com.android.server.Watchdog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Provides a mechanism for discovering media routes and manages media playback
 * behalf of applications.
 * <p>
 * Currently supports discovering remote displays via remote display provider
 * services that have been registered by applications.
 * </p>
 */
public final class MediaRouterService extends IMediaRouterService.Stub
        implements Watchdog.Monitor {
    private static final String TAG = "MediaRouterService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Timeout in milliseconds for a selected route to transition from a
     * disconnected state to a connecting state.  If we don't observe any
     * progress within this interval, then we will give up and unselect the route.
     */
    static final long CONNECTING_TIMEOUT = 5000;

    /**
     * Timeout in milliseconds for a selected route to transition from a
     * connecting state to a connected state.  If we don't observe any
     * progress within this interval, then we will give up and unselect the route.
     */
    static final long CONNECTED_TIMEOUT = 60000;

    private final Context mContext;

    // State guarded by mLock.
    private final Object mLock = new Object();
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    private final ArrayMap<IBinder, ClientRecord> mAllClientRecords = new ArrayMap<>();
    private int mCurrentUserId = -1;
    private final IAudioService mAudioService;
    private final AudioPlayerStateMonitor mAudioPlayerStateMonitor;
    private final Handler mHandler = new Handler();
    private final IntArray mActivePlayerMinPriorityQueue = new IntArray();
    private final IntArray mActivePlayerUidMinPriorityQueue = new IntArray();

    private final BroadcastReceiver mReceiver = new MediaRouterServiceBroadcastReceiver();
    BluetoothDevice mActiveBluetoothDevice;
    int mAudioRouteMainType = AudioRoutesInfo.MAIN_SPEAKER;
    boolean mGlobalBluetoothA2dpOn = false;

    //TODO: remove this when it's finished
    private final MediaRouter2ServiceImpl mService2;

    public MediaRouterService(Context context) {
        mService2 = new MediaRouter2ServiceImpl(context);

        mContext = context;
        Watchdog.getInstance().addMonitor(this);

        mAudioService = IAudioService.Stub.asInterface(
                ServiceManager.getService(Context.AUDIO_SERVICE));
        mAudioPlayerStateMonitor = AudioPlayerStateMonitor.getInstance(context);
        mAudioPlayerStateMonitor.registerListener(
                new AudioPlayerStateMonitor.OnAudioPlayerActiveStateChangedListener() {
            static final long WAIT_MS = 500;
            final Runnable mRestoreBluetoothA2dpRunnable = new Runnable() {
                @Override
                public void run() {
                    restoreBluetoothA2dp();
                }
            };

            @Override
            public void onAudioPlayerActiveStateChanged(
                    @NonNull AudioPlaybackConfiguration config, boolean isRemoved) {
                final boolean active = !isRemoved && config.isActive();
                final int pii = config.getPlayerInterfaceId();
                final int uid = config.getClientUid();

                final int idx = mActivePlayerMinPriorityQueue.indexOf(pii);
                // Keep the latest active player and its uid at the end of the queue.
                if (idx >= 0) {
                    mActivePlayerMinPriorityQueue.remove(idx);
                    mActivePlayerUidMinPriorityQueue.remove(idx);
                }

                int restoreUid = -1;
                if (active) {
                    mActivePlayerMinPriorityQueue.add(config.getPlayerInterfaceId());
                    mActivePlayerUidMinPriorityQueue.add(uid);
                    restoreUid = uid;
                } else if (mActivePlayerUidMinPriorityQueue.size() > 0) {
                    restoreUid = mActivePlayerUidMinPriorityQueue.get(
                            mActivePlayerUidMinPriorityQueue.size() - 1);
                }

                mHandler.removeCallbacks(mRestoreBluetoothA2dpRunnable);
                if (restoreUid >= 0) {
                    restoreRoute(restoreUid);
                    if (DEBUG) {
                        Slog.d(TAG, "onAudioPlayerActiveStateChanged: " + "uid=" + uid
                                + ", active=" + active + ", restoreUid=" + restoreUid);
                    }
                } else {
                    mHandler.postDelayed(mRestoreBluetoothA2dpRunnable, WAIT_MS);
                    if (DEBUG) {
                        Slog.d(TAG, "onAudioPlayerActiveStateChanged: " + "uid=" + uid
                                + ", active=" + active + ", delaying");
                    }
                }
            }
        }, mHandler);

        AudioRoutesInfo audioRoutes = null;
        try {
            audioRoutes = mAudioService.startWatchingRoutes(new IAudioRoutesObserver.Stub() {
                @Override
                public void dispatchAudioRoutesChanged(final AudioRoutesInfo newRoutes) {
                    synchronized (mLock) {
                        if (newRoutes.mainType != mAudioRouteMainType) {
                            if ((newRoutes.mainType & (AudioRoutesInfo.MAIN_HEADSET
                                    | AudioRoutesInfo.MAIN_HEADPHONES
                                    | AudioRoutesInfo.MAIN_USB)) == 0) {
                                // headset was plugged out.
                                mGlobalBluetoothA2dpOn = (newRoutes.bluetoothName != null
                                        || mActiveBluetoothDevice != null);
                            } else {
                                // headset was plugged in.
                                mGlobalBluetoothA2dpOn = false;
                            }
                            mAudioRouteMainType = newRoutes.mainType;
                        }
                        // The new audio routes info could be delivered with several seconds delay.
                        // In order to avoid such delay, Bluetooth device info will be updated
                        // via MediaRouterServiceBroadcastReceiver.
                    }
                }
            });
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException in the audio service.");
        }

        IntentFilter intentFilter = new IntentFilter(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED);
        context.registerReceiverAsUser(mReceiver, UserHandle.ALL, intentFilter, null, null);
    }

    public void systemRunning() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_SWITCHED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_USER_SWITCHED)) {
                    switchUser();
                }
            }
        }, filter);

        switchUser();
    }

    @Override
    public void monitor() {
        synchronized (mLock) { /* check for deadlock */ }
    }

    // Binder call
    @Override
    public void registerClientAsUser(IMediaRouterClient client, String packageName, int userId) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final int uid = Binder.getCallingUid();
        if (!validatePackageName(uid, packageName)) {
            throw new SecurityException("packageName must match the calling uid");
        }

        final int pid = Binder.getCallingPid();
        final int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                false /*allowAll*/, true /*requireFull*/, "registerClientAsUser", packageName);
        final boolean trusted = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY) ==
                PackageManager.PERMISSION_GRANTED;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClientLocked(client, uid, pid, packageName, resolvedUserId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void registerClientGroupId(IMediaRouterClient client, String groupId) {
        if (client == null) {
            throw new NullPointerException("client must not be null");
        }
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Ignoring client group request because "
                    + "the client doesn't have the CONFIGURE_WIFI_DISPLAY permission.");
            return;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClientGroupIdLocked(client, groupId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void unregisterClient(IMediaRouterClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterClientLocked(client, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public MediaRouterClientState getState(IMediaRouterClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                return getStateLocked(client);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public boolean isPlaybackActive(IMediaRouterClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            ClientRecord clientRecord;
            synchronized (mLock) {
                clientRecord = mAllClientRecords.get(client.asBinder());
            }
            if (clientRecord != null) {
                return mAudioPlayerStateMonitor.isPlaybackActive(clientRecord.mUid);
            }
            return false;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void setDiscoveryRequest(IMediaRouterClient client,
            int routeTypes, boolean activeScan) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setDiscoveryRequestLocked(client, routeTypes, activeScan);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    // A null routeId means that the client wants to unselect its current route.
    // The explicit flag indicates whether the change was explicitly requested by the
    // user or the application which may cause changes to propagate out to the rest
    // of the system.  Should be false when the change is in response to a new
    // selected route or a default selection.
    @Override
    public void setSelectedRoute(IMediaRouterClient client, String routeId, boolean explicit) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSelectedRouteLocked(client, routeId, explicit);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void requestSetVolume(IMediaRouterClient client, String routeId, int volume) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (routeId == null) {
            throw new IllegalArgumentException("routeId must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSetVolumeLocked(client, routeId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void requestUpdateVolume(IMediaRouterClient client, String routeId, int direction) {
        if (client == null) {
            throw new IllegalArgumentException("client must not be null");
        }
        if (routeId == null) {
            throw new IllegalArgumentException("routeId must not be null");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestUpdateVolumeLocked(client, routeId, direction);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Binder call
    @Override
    public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        pw.println("MEDIA ROUTER SERVICE (dumpsys media_router)");
        pw.println();
        pw.println("Global state");
        pw.println("  mCurrentUserId=" + mCurrentUserId);

        synchronized (mLock) {
            final int count = mUserRecords.size();
            for (int i = 0; i < count; i++) {
                UserRecord userRecord = mUserRecords.valueAt(i);
                pw.println();
                userRecord.dump(pw, "");
            }
        }
    }

    // Binder call
    @Override
    public void checkModifyAudioRoutingPermission() {
        mService2.checkModifyAudioRoutingPermission();
    }

    // Binder call
    @Override
    public List<MediaRoute2Info> getSystemRoutes() {
        return mService2.getSystemRoutes();
    }

    // Binder call
    @Override
    public RoutingSessionInfo getSystemSessionInfo() {
        return mService2.getSystemSessionInfo();
    }

    // Binder call
    @Override
    public void registerRouter2(IMediaRouter2 router, String packageName) {
        final int uid = Binder.getCallingUid();
        if (!validatePackageName(uid, packageName)) {
            throw new SecurityException("packageName must match the calling uid");
        }
        mService2.registerRouter2(router, packageName);
    }

    // Binder call
    @Override
    public void unregisterRouter2(IMediaRouter2 router) {
        mService2.unregisterRouter2(router);
    }

    // Binder call
    @Override
    public void setDiscoveryRequestWithRouter2(IMediaRouter2 router,
            RouteDiscoveryPreference request) {
        mService2.setDiscoveryRequestWithRouter2(router, request);
    }

    // Binder call
    @Override
    public void setRouteVolumeWithRouter2(IMediaRouter2 router,
            MediaRoute2Info route, int volume) {
        mService2.setRouteVolumeWithRouter2(router, route, volume);
    }

    // Binder call
    @Override
    public void requestCreateSessionWithRouter2(IMediaRouter2 router, int requestId,
            long managerRequestId, RoutingSessionInfo oldSession,
            MediaRoute2Info route, Bundle sessionHints) {
        mService2.requestCreateSessionWithRouter2(router, requestId, managerRequestId,
                oldSession, route, sessionHints);
    }

    // Binder call
    @Override
    public void selectRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route) {
        mService2.selectRouteWithRouter2(router, sessionId, route);
    }

    // Binder call
    @Override
    public void deselectRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route) {
        mService2.deselectRouteWithRouter2(router, sessionId, route);
    }

    // Binder call
    @Override
    public void transferToRouteWithRouter2(IMediaRouter2 router, String sessionId,
            MediaRoute2Info route) {
        mService2.transferToRouteWithRouter2(router, sessionId, route);
    }

    // Binder call
    @Override
    public void setSessionVolumeWithRouter2(IMediaRouter2 router, String sessionId, int volume) {
        mService2.setSessionVolumeWithRouter2(router, sessionId, volume);
    }

    // Binder call
    @Override
    public void releaseSessionWithRouter2(IMediaRouter2 router, String sessionId) {
        mService2.releaseSessionWithRouter2(router, sessionId);
    }

    // Binder call
    @Override
    public List<RoutingSessionInfo> getActiveSessions(IMediaRouter2Manager manager) {
        return mService2.getActiveSessions(manager);
    }

    // Binder call
    @Override
    public void registerManager(IMediaRouter2Manager manager, String packageName) {
        final int uid = Binder.getCallingUid();
        if (!validatePackageName(uid, packageName)) {
            throw new SecurityException("packageName must match the calling uid");
        }
        mService2.registerManager(manager, packageName);
    }

    // Binder call
    @Override
    public void unregisterManager(IMediaRouter2Manager manager) {
        mService2.unregisterManager(manager);
    }

    // Binder call
    @Override
    public void startScan(IMediaRouter2Manager manager) {
        mService2.startScan(manager);
    }

    // Binder call
    @Override
    public void stopScan(IMediaRouter2Manager manager) {
        mService2.stopScan(manager);
    }

    // Binder call
    @Override
    public void setRouteVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            MediaRoute2Info route, int volume) {
        mService2.setRouteVolumeWithManager(manager, requestId, route, volume);
    }

    // Binder call
    @Override
    public void requestCreateSessionWithManager(IMediaRouter2Manager manager,
            int requestId, RoutingSessionInfo oldSession, MediaRoute2Info route) {
        mService2.requestCreateSessionWithManager(manager, requestId, oldSession, route);
    }

    // Binder call
    @Override
    public void selectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route) {
        mService2.selectRouteWithManager(manager, requestId, sessionId, route);
    }

    // Binder call
    @Override
    public void deselectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route) {
        mService2.deselectRouteWithManager(manager, requestId, sessionId, route);
    }

    // Binder call
    @Override
    public void transferToRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, MediaRoute2Info route) {
        mService2.transferToRouteWithManager(manager, requestId, sessionId, route);
    }

    // Binder call
    @Override
    public void setSessionVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId, int volume) {
        mService2.setSessionVolumeWithManager(manager, requestId, sessionId, volume);
    }

    // Binder call
    @Override
    public void releaseSessionWithManager(IMediaRouter2Manager manager, int requestId,
            String sessionId) {
        mService2.releaseSessionWithManager(manager, requestId, sessionId);
    }

    void restoreBluetoothA2dp() {
        try {
            boolean a2dpOn;
            BluetoothDevice btDevice;
            synchronized (mLock) {
                a2dpOn = mGlobalBluetoothA2dpOn;
                btDevice = mActiveBluetoothDevice;
            }
            // We don't need to change a2dp status when bluetooth is not connected.
            if (btDevice != null) {
                if (DEBUG) {
                    Slog.d(TAG, "restoreBluetoothA2dp(" + a2dpOn + ")");
                }
                mAudioService.setBluetoothA2dpOn(a2dpOn);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "RemoteException while calling setBluetoothA2dpOn.");
        }
    }

    void restoreRoute(int uid) {
        ClientRecord clientRecord = null;
        synchronized (mLock) {
            UserRecord userRecord = mUserRecords.get(
                    UserHandle.getUserHandleForUid(uid).getIdentifier());
            if (userRecord != null && userRecord.mClientRecords != null) {
                for (ClientRecord cr : userRecord.mClientRecords) {
                    if (validatePackageName(uid, cr.mPackageName)) {
                        clientRecord = cr;
                        break;
                    }
                }
            }
        }
        if (clientRecord != null) {
            try {
                clientRecord.mClient.onRestoreRoute();
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to call onRestoreRoute. Client probably died.");
            }
        } else {
            restoreBluetoothA2dp();
        }
    }

    void switchUser() {
        synchronized (mLock) {
            int userId = ActivityManager.getCurrentUser();
            if (mCurrentUserId != userId) {
                final int oldUserId = mCurrentUserId;
                mCurrentUserId = userId; // do this first

                UserRecord oldUser = mUserRecords.get(oldUserId);
                if (oldUser != null) {
                    oldUser.mHandler.sendEmptyMessage(UserHandler.MSG_STOP);
                    disposeUserIfNeededLocked(oldUser); // since no longer current user
                }

                UserRecord newUser = mUserRecords.get(userId);
                if (newUser != null) {
                    newUser.mHandler.sendEmptyMessage(UserHandler.MSG_START);
                }
            }
        }
        mService2.switchUser();
    }

    void clientDied(ClientRecord clientRecord) {
        synchronized (mLock) {
            unregisterClientLocked(clientRecord.mClient, true);
        }
    }

    private void registerClientLocked(IMediaRouterClient client,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            clientRecord = new ClientRecord(userRecord, client, uid, pid, packageName, trusted);
            try {
                binder.linkToDeath(clientRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router client died prematurely.", ex);
            }

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);
            initializeClientLocked(clientRecord);
        }
    }

    private void registerClientGroupIdLocked(IMediaRouterClient client, String groupId) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord == null) {
            Log.w(TAG, "Ignoring group id register request of a unregistered client.");
            return;
        }
        if (TextUtils.equals(clientRecord.mGroupId, groupId)) {
            return;
        }
        UserRecord userRecord = clientRecord.mUserRecord;
        if (clientRecord.mGroupId != null) {
            userRecord.removeFromGroup(clientRecord.mGroupId, clientRecord);
        }
        clientRecord.mGroupId = groupId;
        if (groupId != null) {
            userRecord.addToGroup(groupId, clientRecord);
            userRecord.mHandler.obtainMessage(UserHandler.MSG_NOTIFY_GROUP_ROUTE_SELECTED, groupId)
                .sendToTarget();
        }
    }

    private void unregisterClientLocked(IMediaRouterClient client, boolean died) {
        ClientRecord clientRecord = mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            if (clientRecord.mGroupId != null) {
                userRecord.removeFromGroup(clientRecord.mGroupId, clientRecord);
                clientRecord.mGroupId = null;
            }
            disposeClientLocked(clientRecord, died);
            disposeUserIfNeededLocked(userRecord); // since client removed from user
        }
    }

    private MediaRouterClientState getStateLocked(IMediaRouterClient client) {
        ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
        if (clientRecord != null) {
            return clientRecord.getState();
        }
        return null;
    }

    private void setDiscoveryRequestLocked(IMediaRouterClient client,
            int routeTypes, boolean activeScan) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord != null) {
            // Only let the system discover remote display routes for now.
            if (!clientRecord.mTrusted) {
                routeTypes &= ~MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY;
            }

            if (clientRecord.mRouteTypes != routeTypes
                    || clientRecord.mActiveScan != activeScan) {
                if (DEBUG) {
                    Slog.d(TAG, clientRecord + ": Set discovery request, routeTypes=0x"
                            + Integer.toHexString(routeTypes) + ", activeScan=" + activeScan);
                }
                clientRecord.mRouteTypes = routeTypes;
                clientRecord.mActiveScan = activeScan;
                clientRecord.mUserRecord.mHandler.sendEmptyMessage(
                        UserHandler.MSG_UPDATE_DISCOVERY_REQUEST);
            }
        }
    }

    private void setSelectedRouteLocked(IMediaRouterClient client,
            String routeId, boolean explicit) {
        ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
        if (clientRecord != null) {
            final String oldRouteId = clientRecord.mSelectedRouteId;
            if (!Objects.equals(routeId, oldRouteId)) {
                if (DEBUG) {
                    Slog.d(TAG, clientRecord + ": Set selected route, routeId=" + routeId
                            + ", oldRouteId=" + oldRouteId
                            + ", explicit=" + explicit);
                }

                clientRecord.mSelectedRouteId = routeId;
                // Only let the system connect to new global routes for now.
                // A similar check exists in the display manager for wifi display.
                if (explicit && clientRecord.mTrusted) {
                    if (oldRouteId != null) {
                        clientRecord.mUserRecord.mHandler.obtainMessage(
                                UserHandler.MSG_UNSELECT_ROUTE, oldRouteId).sendToTarget();
                    }
                    if (routeId != null) {
                        clientRecord.mUserRecord.mHandler.obtainMessage(
                                UserHandler.MSG_SELECT_ROUTE, routeId).sendToTarget();
                    }
                    if (clientRecord.mGroupId != null) {
                        ClientGroup group =
                                clientRecord.mUserRecord.mClientGroupMap.get(clientRecord.mGroupId);
                        if (group != null) {
                            group.mSelectedRouteId = routeId;
                            clientRecord.mUserRecord.mHandler.obtainMessage(
                                UserHandler.MSG_NOTIFY_GROUP_ROUTE_SELECTED, clientRecord.mGroupId)
                                .sendToTarget();
                        }
                    }
                }
            }
        }
    }

    private void requestSetVolumeLocked(IMediaRouterClient client,
            String routeId, int volume) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.obtainMessage(
                    UserHandler.MSG_REQUEST_SET_VOLUME, volume, 0, routeId).sendToTarget();
        }
    }

    private void requestUpdateVolumeLocked(IMediaRouterClient client,
            String routeId, int direction) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.obtainMessage(
                    UserHandler.MSG_REQUEST_UPDATE_VOLUME, direction, 0, routeId).sendToTarget();
        }
    }

    private void initializeUserLocked(UserRecord userRecord) {
        if (DEBUG) {
            Slog.d(TAG, userRecord + ": Initialized");
        }
        if (userRecord.mUserId == mCurrentUserId) {
            userRecord.mHandler.sendEmptyMessage(UserHandler.MSG_START);
        }
    }

    private void disposeUserIfNeededLocked(UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (userRecord.mUserId != mCurrentUserId
                && userRecord.mClientRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    private void initializeClientLocked(ClientRecord clientRecord) {
        if (DEBUG) {
            Slog.d(TAG, clientRecord + ": Registered");
        }
    }

    private void disposeClientLocked(ClientRecord clientRecord, boolean died) {
        if (DEBUG) {
            if (died) {
                Slog.d(TAG, clientRecord + ": Died!");
            } else {
                Slog.d(TAG, clientRecord + ": Unregistered");
            }
        }
        if (clientRecord.mRouteTypes != 0 || clientRecord.mActiveScan) {
            clientRecord.mUserRecord.mHandler.sendEmptyMessage(
                    UserHandler.MSG_UPDATE_DISCOVERY_REQUEST);
        }
        clientRecord.dispose();
    }

    private boolean validatePackageName(int uid, String packageName) {
        if (packageName != null) {
            String[] packageNames = mContext.getPackageManager().getPackagesForUid(uid);
            if (packageNames != null) {
                for (String n : packageNames) {
                    if (n.equals(packageName)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    final class MediaRouterServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)) {
                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                synchronized (mLock) {
                    boolean wasA2dpOn = mGlobalBluetoothA2dpOn;
                    mActiveBluetoothDevice = btDevice;
                    mGlobalBluetoothA2dpOn = btDevice != null;
                    if (wasA2dpOn != mGlobalBluetoothA2dpOn) {
                        UserRecord userRecord = mUserRecords.get(mCurrentUserId);
                        if (userRecord != null) {
                            for (ClientRecord cr : userRecord.mClientRecords) {
                                // mSelectedRouteId will be null for BT and phone speaker.
                                if (cr.mSelectedRouteId == null) {
                                    try {
                                        cr.mClient.onGlobalA2dpChanged(mGlobalBluetoothA2dpOn);
                                    } catch (RemoteException e) {
                                        // Ignore exception
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Information about a particular client of the media router.
     * The contents of this object is guarded by mLock.
     */
    final class ClientRecord implements DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouterClient mClient;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final boolean mTrusted;
        public List<String> mControlCategories;

        public int mRouteTypes;
        public boolean mActiveScan;
        public String mSelectedRouteId;
        public String mGroupId;

        public ClientRecord(UserRecord userRecord, IMediaRouterClient client,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mClient = client;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mTrusted = trusted;
        }

        public void dispose() {
            mClient.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            clientDied(this);
        }

        MediaRouterClientState getState() {
            return mTrusted ? mUserRecord.mRouterState : null;
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);

            final String indent = prefix + "  ";
            pw.println(indent + "mTrusted=" + mTrusted);
            pw.println(indent + "mRouteTypes=0x" + Integer.toHexString(mRouteTypes));
            pw.println(indent + "mActiveScan=" + mActiveScan);
            pw.println(indent + "mSelectedRouteId=" + mSelectedRouteId);
        }

        @Override
        public String toString() {
            return "Client " + mPackageName + " (pid " + mPid + ")";
        }
    }

    final class ClientGroup {
        public String mSelectedRouteId;
        public final List<ClientRecord> mClientRecords = new ArrayList<>();
    }

    /**
     * Information about a particular user.
     * The contents of this object is guarded by mLock.
     */
    final class UserRecord {
        public final int mUserId;
        public final ArrayList<ClientRecord> mClientRecords = new ArrayList<>();
        public final UserHandler mHandler;
        public MediaRouterClientState mRouterState;
        private final ArrayMap<String, ClientGroup> mClientGroupMap = new ArrayMap<>();

        public UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouterService.this, this);
        }

        public void dump(final PrintWriter pw, String prefix) {
            pw.println(prefix + this);

            final String indent = prefix + "  ";
            final int clientCount = mClientRecords.size();
            if (clientCount != 0) {
                for (int i = 0; i < clientCount; i++) {
                    mClientRecords.get(i).dump(pw, indent);
                }
            } else {
                pw.println(indent + "<no clients>");
            }

            pw.println(indent + "State");
            pw.println(indent + "mRouterState=" + mRouterState);

            if (!mHandler.runWithScissors(new Runnable() {
                @Override
                public void run() {
                    mHandler.dump(pw, indent);
                }
            }, 1000)) {
                pw.println(indent + "<could not dump handler state>");
            }
        }

        public void addToGroup(String groupId, ClientRecord clientRecord) {
            ClientGroup group = mClientGroupMap.get(groupId);
            if (group == null) {
                group = new ClientGroup();
                mClientGroupMap.put(groupId, group);
            }
            group.mClientRecords.add(clientRecord);
        }

        public void removeFromGroup(String groupId, ClientRecord clientRecord) {
            ClientGroup group = mClientGroupMap.get(groupId);
            if (group != null) {
                group.mClientRecords.remove(clientRecord);
                if (group.mClientRecords.size() == 0) {
                    mClientGroupMap.remove(groupId);
                }
            }
        }

        @Override
        public String toString() {
            return "User " + mUserId;
        }
    }

    /**
     * Media router handler
     * <p>
     * Since remote display providers are designed to be single-threaded by nature,
     * this class encapsulates all of the associated functionality and exports state
     * to the service as it evolves.
     * </p><p>
     * This class is currently hardcoded to work with remote display providers but
     * it is intended to be eventually extended to support more general route providers
     * similar to the support library media router.
     * </p>
     */
    static final class UserHandler extends Handler
            implements RemoteDisplayProviderWatcher.Callback,
            RemoteDisplayProviderProxy.Callback {
        public static final int MSG_START = 1;
        public static final int MSG_STOP = 2;
        public static final int MSG_UPDATE_DISCOVERY_REQUEST = 3;
        public static final int MSG_SELECT_ROUTE = 4;
        public static final int MSG_UNSELECT_ROUTE = 5;
        public static final int MSG_REQUEST_SET_VOLUME = 6;
        public static final int MSG_REQUEST_UPDATE_VOLUME = 7;
        private static final int MSG_UPDATE_CLIENT_STATE = 8;
        private static final int MSG_CONNECTION_TIMED_OUT = 9;
        private static final int MSG_NOTIFY_GROUP_ROUTE_SELECTED = 10;

        private static final int TIMEOUT_REASON_NOT_AVAILABLE = 1;
        private static final int TIMEOUT_REASON_CONNECTION_LOST = 2;
        private static final int TIMEOUT_REASON_WAITING_FOR_CONNECTING = 3;
        private static final int TIMEOUT_REASON_WAITING_FOR_CONNECTED = 4;

        // The relative order of these constants is important and expresses progress
        // through the process of connecting to a route.
        private static final int PHASE_NOT_AVAILABLE = -1;
        private static final int PHASE_NOT_CONNECTED = 0;
        private static final int PHASE_CONNECTING = 1;
        private static final int PHASE_CONNECTED = 2;

        private final MediaRouterService mService;
        private final UserRecord mUserRecord;
        private final RemoteDisplayProviderWatcher mWatcher;
        private final ArrayList<ProviderRecord> mProviderRecords =
                new ArrayList<ProviderRecord>();
        private final ArrayList<IMediaRouterClient> mTempClients =
                new ArrayList<IMediaRouterClient>();

        private boolean mRunning;
        private int mDiscoveryMode = RemoteDisplayState.DISCOVERY_MODE_NONE;
        private RouteRecord mSelectedRouteRecord;
        private int mConnectionPhase = PHASE_NOT_AVAILABLE;
        private int mConnectionTimeoutReason;
        private long mConnectionTimeoutStartTime;
        private boolean mClientStateUpdateScheduled;

        public UserHandler(MediaRouterService service, UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mService = service;
            mUserRecord = userRecord;
            mWatcher = new RemoteDisplayProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START: {
                    start();
                    break;
                }
                case MSG_STOP: {
                    stop();
                    break;
                }
                case MSG_UPDATE_DISCOVERY_REQUEST: {
                    updateDiscoveryRequest();
                    break;
                }
                case MSG_SELECT_ROUTE: {
                    selectRoute((String)msg.obj);
                    break;
                }
                case MSG_UNSELECT_ROUTE: {
                    unselectRoute((String)msg.obj);
                    break;
                }
                case MSG_REQUEST_SET_VOLUME: {
                    requestSetVolume((String)msg.obj, msg.arg1);
                    break;
                }
                case MSG_REQUEST_UPDATE_VOLUME: {
                    requestUpdateVolume((String)msg.obj, msg.arg1);
                    break;
                }
                case MSG_UPDATE_CLIENT_STATE: {
                    updateClientState();
                    break;
                }
                case MSG_CONNECTION_TIMED_OUT: {
                    connectionTimedOut();
                    break;
                }
                case MSG_NOTIFY_GROUP_ROUTE_SELECTED: {
                    notifyGroupRouteSelected((String) msg.obj);
                    break;
                }
            }
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + "Handler");

            final String indent = prefix + "  ";
            pw.println(indent + "mRunning=" + mRunning);
            pw.println(indent + "mDiscoveryMode=" + mDiscoveryMode);
            pw.println(indent + "mSelectedRouteRecord=" + mSelectedRouteRecord);
            pw.println(indent + "mConnectionPhase=" + mConnectionPhase);
            pw.println(indent + "mConnectionTimeoutReason=" + mConnectionTimeoutReason);
            pw.println(indent + "mConnectionTimeoutStartTime=" + (mConnectionTimeoutReason != 0 ?
                    TimeUtils.formatUptime(mConnectionTimeoutStartTime) : "<n/a>"));

            mWatcher.dump(pw, prefix);

            final int providerCount = mProviderRecords.size();
            if (providerCount != 0) {
                for (int i = 0; i < providerCount; i++) {
                    mProviderRecords.get(i).dump(pw, prefix);
                }
            } else {
                pw.println(indent + "<no providers>");
            }
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mWatcher.start(); // also starts all providers
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                unselectSelectedRoute();
                mWatcher.stop(); // also stops all providers
            }
        }

        private void updateDiscoveryRequest() {
            int routeTypes = 0;
            boolean activeScan = false;
            synchronized (mService.mLock) {
                final int count = mUserRecord.mClientRecords.size();
                for (int i = 0; i < count; i++) {
                    ClientRecord clientRecord = mUserRecord.mClientRecords.get(i);
                    routeTypes |= clientRecord.mRouteTypes;
                    activeScan |= clientRecord.mActiveScan;
                }
            }

            final int newDiscoveryMode;
            if ((routeTypes & MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY) != 0) {
                if (activeScan) {
                    newDiscoveryMode = RemoteDisplayState.DISCOVERY_MODE_ACTIVE;
                } else {
                    newDiscoveryMode = RemoteDisplayState.DISCOVERY_MODE_PASSIVE;
                }
            } else {
                newDiscoveryMode = RemoteDisplayState.DISCOVERY_MODE_NONE;
            }

            if (mDiscoveryMode != newDiscoveryMode) {
                mDiscoveryMode = newDiscoveryMode;
                final int count = mProviderRecords.size();
                for (int i = 0; i < count; i++) {
                    mProviderRecords.get(i).getProvider().setDiscoveryMode(mDiscoveryMode);
                }
            }
        }

        private void selectRoute(String routeId) {
            if (routeId != null
                    && (mSelectedRouteRecord == null
                            || !routeId.equals(mSelectedRouteRecord.getUniqueId()))) {
                RouteRecord routeRecord = findRouteRecord(routeId);
                if (routeRecord != null) {
                    unselectSelectedRoute();

                    Slog.i(TAG, "Selected route:" + routeRecord);
                    mSelectedRouteRecord = routeRecord;
                    checkSelectedRouteState();
                    routeRecord.getProvider().setSelectedDisplay(routeRecord.getDescriptorId());

                    scheduleUpdateClientState();
                }
            }
        }

        private void unselectRoute(String routeId) {
            if (routeId != null
                    && mSelectedRouteRecord != null
                    && routeId.equals(mSelectedRouteRecord.getUniqueId())) {
                unselectSelectedRoute();
            }
        }

        private void unselectSelectedRoute() {
            if (mSelectedRouteRecord != null) {
                Slog.i(TAG, "Unselected route:" + mSelectedRouteRecord);
                mSelectedRouteRecord.getProvider().setSelectedDisplay(null);
                mSelectedRouteRecord = null;
                checkSelectedRouteState();

                scheduleUpdateClientState();
            }
        }

        private void requestSetVolume(String routeId, int volume) {
            if (mSelectedRouteRecord != null
                    && routeId.equals(mSelectedRouteRecord.getUniqueId())) {
                mSelectedRouteRecord.getProvider().setDisplayVolume(volume);
            }
        }

        private void requestUpdateVolume(String routeId, int direction) {
            if (mSelectedRouteRecord != null
                    && routeId.equals(mSelectedRouteRecord.getUniqueId())) {
                mSelectedRouteRecord.getProvider().adjustDisplayVolume(direction);
            }
        }

        @Override
        public void addProvider(RemoteDisplayProviderProxy provider) {
            provider.setCallback(this);
            provider.setDiscoveryMode(mDiscoveryMode);
            provider.setSelectedDisplay(null); // just to be safe

            ProviderRecord providerRecord = new ProviderRecord(provider);
            mProviderRecords.add(providerRecord);
            providerRecord.updateDescriptor(provider.getDisplayState());

            scheduleUpdateClientState();
        }

        @Override
        public void removeProvider(RemoteDisplayProviderProxy provider) {
            int index = findProviderRecord(provider);
            if (index >= 0) {
                ProviderRecord providerRecord = mProviderRecords.remove(index);
                providerRecord.updateDescriptor(null); // mark routes invalid
                provider.setCallback(null);
                provider.setDiscoveryMode(RemoteDisplayState.DISCOVERY_MODE_NONE);

                checkSelectedRouteState();
                scheduleUpdateClientState();
            }
        }

        @Override
        public void onDisplayStateChanged(RemoteDisplayProviderProxy provider,
                RemoteDisplayState state) {
            updateProvider(provider, state);
        }

        private void updateProvider(RemoteDisplayProviderProxy provider,
                RemoteDisplayState state) {
            int index = findProviderRecord(provider);
            if (index >= 0) {
                ProviderRecord providerRecord = mProviderRecords.get(index);
                if (providerRecord.updateDescriptor(state)) {
                    checkSelectedRouteState();
                    scheduleUpdateClientState();
                }
            }
        }

        /**
         * This function is called whenever the state of the selected route may have changed.
         * It checks the state and updates timeouts or unselects the route as appropriate.
         */
        private void checkSelectedRouteState() {
            // Unschedule timeouts when the route is unselected.
            if (mSelectedRouteRecord == null) {
                mConnectionPhase = PHASE_NOT_AVAILABLE;
                updateConnectionTimeout(0);
                return;
            }

            // Ensure that the route is still present and enabled.
            if (!mSelectedRouteRecord.isValid()
                    || !mSelectedRouteRecord.isEnabled()) {
                updateConnectionTimeout(TIMEOUT_REASON_NOT_AVAILABLE);
                return;
            }

            // Make sure we haven't lost our connection.
            final int oldPhase = mConnectionPhase;
            mConnectionPhase = getConnectionPhase(mSelectedRouteRecord.getStatus());
            if (oldPhase >= PHASE_CONNECTING && mConnectionPhase < PHASE_CONNECTING) {
                updateConnectionTimeout(TIMEOUT_REASON_CONNECTION_LOST);
                return;
            }

            // Check the route status.
            switch (mConnectionPhase) {
                case PHASE_CONNECTED:
                    if (oldPhase != PHASE_CONNECTED) {
                        Slog.i(TAG, "Connected to route: " + mSelectedRouteRecord);
                    }
                    updateConnectionTimeout(0);
                    break;
                case PHASE_CONNECTING:
                    if (oldPhase != PHASE_CONNECTING) {
                        Slog.i(TAG, "Connecting to route: " + mSelectedRouteRecord);
                    }
                    updateConnectionTimeout(TIMEOUT_REASON_WAITING_FOR_CONNECTED);
                    break;
                case PHASE_NOT_CONNECTED:
                    updateConnectionTimeout(TIMEOUT_REASON_WAITING_FOR_CONNECTING);
                    break;
                case PHASE_NOT_AVAILABLE:
                default:
                    updateConnectionTimeout(TIMEOUT_REASON_NOT_AVAILABLE);
                    break;
            }
        }

        private void updateConnectionTimeout(int reason) {
            if (reason != mConnectionTimeoutReason) {
                if (mConnectionTimeoutReason != 0) {
                    removeMessages(MSG_CONNECTION_TIMED_OUT);
                }
                mConnectionTimeoutReason = reason;
                mConnectionTimeoutStartTime = SystemClock.uptimeMillis();
                switch (reason) {
                    case TIMEOUT_REASON_NOT_AVAILABLE:
                    case TIMEOUT_REASON_CONNECTION_LOST:
                        // Route became unavailable or connection lost.
                        // Unselect it immediately.
                        sendEmptyMessage(MSG_CONNECTION_TIMED_OUT);
                        break;
                    case TIMEOUT_REASON_WAITING_FOR_CONNECTING:
                        // Waiting for route to start connecting.
                        sendEmptyMessageDelayed(MSG_CONNECTION_TIMED_OUT, CONNECTING_TIMEOUT);
                        break;
                    case TIMEOUT_REASON_WAITING_FOR_CONNECTED:
                        // Waiting for route to complete connection.
                        sendEmptyMessageDelayed(MSG_CONNECTION_TIMED_OUT, CONNECTED_TIMEOUT);
                        break;
                }
            }
        }

        private void connectionTimedOut() {
            if (mConnectionTimeoutReason == 0 || mSelectedRouteRecord == null) {
                // Shouldn't get here.  There must be a bug somewhere.
                Log.wtf(TAG, "Handled connection timeout for no reason.");
                return;
            }

            switch (mConnectionTimeoutReason) {
                case TIMEOUT_REASON_NOT_AVAILABLE:
                    Slog.i(TAG, "Selected route no longer available: "
                            + mSelectedRouteRecord);
                    break;
                case TIMEOUT_REASON_CONNECTION_LOST:
                    Slog.i(TAG, "Selected route connection lost: "
                            + mSelectedRouteRecord);
                    break;
                case TIMEOUT_REASON_WAITING_FOR_CONNECTING:
                    Slog.i(TAG, "Selected route timed out while waiting for "
                            + "connection attempt to begin after "
                            + (SystemClock.uptimeMillis() - mConnectionTimeoutStartTime)
                            + " ms: " + mSelectedRouteRecord);
                    break;
                case TIMEOUT_REASON_WAITING_FOR_CONNECTED:
                    Slog.i(TAG, "Selected route timed out while connecting after "
                            + (SystemClock.uptimeMillis() - mConnectionTimeoutStartTime)
                            + " ms: " + mSelectedRouteRecord);
                    break;
            }
            mConnectionTimeoutReason = 0;

            unselectSelectedRoute();
        }

        private void scheduleUpdateClientState() {
            if (!mClientStateUpdateScheduled) {
                mClientStateUpdateScheduled = true;
                sendEmptyMessage(MSG_UPDATE_CLIENT_STATE);
            }
        }

        private void updateClientState() {
            mClientStateUpdateScheduled = false;

            // Build a new client state for trusted clients.
            MediaRouterClientState routerState = new MediaRouterClientState();
            final int providerCount = mProviderRecords.size();
            for (int i = 0; i < providerCount; i++) {
                mProviderRecords.get(i).appendClientState(routerState);
            }
            try {
                synchronized (mService.mLock) {
                    // Update the UserRecord.
                    mUserRecord.mRouterState = routerState;

                    // Collect all clients.
                    final int count = mUserRecord.mClientRecords.size();
                    for (int i = 0; i < count; i++) {
                        mTempClients.add(mUserRecord.mClientRecords.get(i).mClient);
                    }
                }

                // Notify all clients (outside of the lock).
                final int count = mTempClients.size();
                for (int i = 0; i < count; i++) {
                    try {
                        mTempClients.get(i).onStateChanged();
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to call onStateChanged. Client probably died.");
                    }
                }
            } finally {
                // Clear the list in preparation for the next time.
                mTempClients.clear();
            }
        }

        private void notifyGroupRouteSelected(String groupId) {
            try {
                String selectedRouteId;
                synchronized (mService.mLock) {
                    ClientGroup group = mUserRecord.mClientGroupMap.get(groupId);
                    if (group == null) {
                        return;
                    }
                    selectedRouteId = group.mSelectedRouteId;
                    final int count = group.mClientRecords.size();
                    for (int i = 0; i < count; i++) {
                        ClientRecord clientRecord = group.mClientRecords.get(i);
                        if (!TextUtils.equals(selectedRouteId, clientRecord.mSelectedRouteId)) {
                            mTempClients.add(clientRecord.mClient);
                        }
                    }
                }

                final int count = mTempClients.size();
                for (int i = 0; i < count; i++) {
                    try {
                        mTempClients.get(i).onGroupRouteSelected(selectedRouteId);
                    } catch (RemoteException ex) {
                        Slog.w(TAG, "Failed to call onSelectedRouteChanged. Client probably died.");
                    }
                }
            } finally {
                mTempClients.clear();
            }
        }

        private int findProviderRecord(RemoteDisplayProviderProxy provider) {
            final int count = mProviderRecords.size();
            for (int i = 0; i < count; i++) {
                ProviderRecord record = mProviderRecords.get(i);
                if (record.getProvider() == provider) {
                    return i;
                }
            }
            return -1;
        }

        private RouteRecord findRouteRecord(String uniqueId) {
            final int count = mProviderRecords.size();
            for (int i = 0; i < count; i++) {
                RouteRecord record = mProviderRecords.get(i).findRouteByUniqueId(uniqueId);
                if (record != null) {
                    return record;
                }
            }
            return null;
        }

        private static int getConnectionPhase(int status) {
            switch (status) {
                case MediaRouter.RouteInfo.STATUS_NONE:
                case MediaRouter.RouteInfo.STATUS_CONNECTED:
                    return PHASE_CONNECTED;
                case MediaRouter.RouteInfo.STATUS_CONNECTING:
                    return PHASE_CONNECTING;
                case MediaRouter.RouteInfo.STATUS_SCANNING:
                case MediaRouter.RouteInfo.STATUS_AVAILABLE:
                    return PHASE_NOT_CONNECTED;
                case MediaRouter.RouteInfo.STATUS_NOT_AVAILABLE:
                case MediaRouter.RouteInfo.STATUS_IN_USE:
                default:
                    return PHASE_NOT_AVAILABLE;
            }
        }

        static final class ProviderRecord {
            private final RemoteDisplayProviderProxy mProvider;
            private final String mUniquePrefix;
            private final ArrayList<RouteRecord> mRoutes = new ArrayList<RouteRecord>();
            private RemoteDisplayState mDescriptor;

            public ProviderRecord(RemoteDisplayProviderProxy provider) {
                mProvider = provider;
                mUniquePrefix = provider.getFlattenedComponentName() + ":";
            }

            public RemoteDisplayProviderProxy getProvider() {
                return mProvider;
            }

            public String getUniquePrefix() {
                return mUniquePrefix;
            }

            public boolean updateDescriptor(RemoteDisplayState descriptor) {
                boolean changed = false;
                if (mDescriptor != descriptor) {
                    mDescriptor = descriptor;

                    // Update all existing routes and reorder them to match
                    // the order of their descriptors.
                    int targetIndex = 0;
                    if (descriptor != null) {
                        if (descriptor.isValid()) {
                            final List<RemoteDisplayInfo> routeDescriptors = descriptor.displays;
                            final int routeCount = routeDescriptors.size();
                            for (int i = 0; i < routeCount; i++) {
                                final RemoteDisplayInfo routeDescriptor =
                                        routeDescriptors.get(i);
                                final String descriptorId = routeDescriptor.id;
                                final int sourceIndex = findRouteByDescriptorId(descriptorId);
                                if (sourceIndex < 0) {
                                    // Add the route to the provider.
                                    String uniqueId = assignRouteUniqueId(descriptorId);
                                    RouteRecord route =
                                            new RouteRecord(this, descriptorId, uniqueId);
                                    mRoutes.add(targetIndex++, route);
                                    route.updateDescriptor(routeDescriptor);
                                    changed = true;
                                } else if (sourceIndex < targetIndex) {
                                    // Ignore route with duplicate id.
                                    Slog.w(TAG, "Ignoring route descriptor with duplicate id: "
                                            + routeDescriptor);
                                } else {
                                    // Reorder existing route within the list.
                                    RouteRecord route = mRoutes.get(sourceIndex);
                                    Collections.swap(mRoutes, sourceIndex, targetIndex++);
                                    changed |= route.updateDescriptor(routeDescriptor);
                                }
                            }
                        } else {
                            Slog.w(TAG, "Ignoring invalid descriptor from media route provider: "
                                    + mProvider.getFlattenedComponentName());
                        }
                    }

                    // Dispose all remaining routes that do not have matching descriptors.
                    for (int i = mRoutes.size() - 1; i >= targetIndex; i--) {
                        RouteRecord route = mRoutes.remove(i);
                        route.updateDescriptor(null); // mark route invalid
                        changed = true;
                    }
                }
                return changed;
            }

            public void appendClientState(MediaRouterClientState state) {
                final int routeCount = mRoutes.size();
                for (int i = 0; i < routeCount; i++) {
                    state.routes.add(mRoutes.get(i).getInfo());
                }
            }

            public RouteRecord findRouteByUniqueId(String uniqueId) {
                final int routeCount = mRoutes.size();
                for (int i = 0; i < routeCount; i++) {
                    RouteRecord route = mRoutes.get(i);
                    if (route.getUniqueId().equals(uniqueId)) {
                        return route;
                    }
                }
                return null;
            }

            private int findRouteByDescriptorId(String descriptorId) {
                final int routeCount = mRoutes.size();
                for (int i = 0; i < routeCount; i++) {
                    RouteRecord route = mRoutes.get(i);
                    if (route.getDescriptorId().equals(descriptorId)) {
                        return i;
                    }
                }
                return -1;
            }

            public void dump(PrintWriter pw, String prefix) {
                pw.println(prefix + this);

                final String indent = prefix + "  ";
                mProvider.dump(pw, indent);

                final int routeCount = mRoutes.size();
                if (routeCount != 0) {
                    for (int i = 0; i < routeCount; i++) {
                        mRoutes.get(i).dump(pw, indent);
                    }
                } else {
                    pw.println(indent + "<no routes>");
                }
            }

            @Override
            public String toString() {
                return "Provider " + mProvider.getFlattenedComponentName();
            }

            private String assignRouteUniqueId(String descriptorId) {
                return mUniquePrefix + descriptorId;
            }
        }

        static final class RouteRecord {
            private final ProviderRecord mProviderRecord;
            private final String mDescriptorId;
            private final MediaRouterClientState.RouteInfo mMutableInfo;
            private MediaRouterClientState.RouteInfo mImmutableInfo;
            private RemoteDisplayInfo mDescriptor;

            public RouteRecord(ProviderRecord providerRecord,
                    String descriptorId, String uniqueId) {
                mProviderRecord = providerRecord;
                mDescriptorId = descriptorId;
                mMutableInfo = new MediaRouterClientState.RouteInfo(uniqueId);
            }

            public RemoteDisplayProviderProxy getProvider() {
                return mProviderRecord.getProvider();
            }

            public ProviderRecord getProviderRecord() {
                return mProviderRecord;
            }

            public String getDescriptorId() {
                return mDescriptorId;
            }

            public String getUniqueId() {
                return mMutableInfo.id;
            }

            public MediaRouterClientState.RouteInfo getInfo() {
                if (mImmutableInfo == null) {
                    mImmutableInfo = new MediaRouterClientState.RouteInfo(mMutableInfo);
                }
                return mImmutableInfo;
            }

            public boolean isValid() {
                return mDescriptor != null;
            }

            public boolean isEnabled() {
                return mMutableInfo.enabled;
            }

            public int getStatus() {
                return mMutableInfo.statusCode;
            }

            public boolean updateDescriptor(RemoteDisplayInfo descriptor) {
                boolean changed = false;
                if (mDescriptor != descriptor) {
                    mDescriptor = descriptor;
                    if (descriptor != null) {
                        final String name = computeName(descriptor);
                        if (!Objects.equals(mMutableInfo.name, name)) {
                            mMutableInfo.name = name;
                            changed = true;
                        }
                        final String description = computeDescription(descriptor);
                        if (!Objects.equals(mMutableInfo.description, description)) {
                            mMutableInfo.description = description;
                            changed = true;
                        }
                        final int supportedTypes = computeSupportedTypes(descriptor);
                        if (mMutableInfo.supportedTypes != supportedTypes) {
                            mMutableInfo.supportedTypes = supportedTypes;
                            changed = true;
                        }
                        final boolean enabled = computeEnabled(descriptor);
                        if (mMutableInfo.enabled != enabled) {
                            mMutableInfo.enabled = enabled;
                            changed = true;
                        }
                        final int statusCode = computeStatusCode(descriptor);
                        if (mMutableInfo.statusCode != statusCode) {
                            mMutableInfo.statusCode = statusCode;
                            changed = true;
                        }
                        final int playbackType = computePlaybackType(descriptor);
                        if (mMutableInfo.playbackType != playbackType) {
                            mMutableInfo.playbackType = playbackType;
                            changed = true;
                        }
                        final int playbackStream = computePlaybackStream(descriptor);
                        if (mMutableInfo.playbackStream != playbackStream) {
                            mMutableInfo.playbackStream = playbackStream;
                            changed = true;
                        }
                        final int volume = computeVolume(descriptor);
                        if (mMutableInfo.volume != volume) {
                            mMutableInfo.volume = volume;
                            changed = true;
                        }
                        final int volumeMax = computeVolumeMax(descriptor);
                        if (mMutableInfo.volumeMax != volumeMax) {
                            mMutableInfo.volumeMax = volumeMax;
                            changed = true;
                        }
                        final int volumeHandling = computeVolumeHandling(descriptor);
                        if (mMutableInfo.volumeHandling != volumeHandling) {
                            mMutableInfo.volumeHandling = volumeHandling;
                            changed = true;
                        }
                        final int presentationDisplayId = computePresentationDisplayId(descriptor);
                        if (mMutableInfo.presentationDisplayId != presentationDisplayId) {
                            mMutableInfo.presentationDisplayId = presentationDisplayId;
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    mImmutableInfo = null;
                }
                return changed;
            }

            public void dump(PrintWriter pw, String prefix) {
                pw.println(prefix + this);

                final String indent = prefix + "  ";
                pw.println(indent + "mMutableInfo=" + mMutableInfo);
                pw.println(indent + "mDescriptorId=" + mDescriptorId);
                pw.println(indent + "mDescriptor=" + mDescriptor);
            }

            @Override
            public String toString() {
                return "Route " + mMutableInfo.name + " (" + mMutableInfo.id + ")";
            }

            private static String computeName(RemoteDisplayInfo descriptor) {
                // Note that isValid() already ensures the name is non-empty.
                return descriptor.name;
            }

            private static String computeDescription(RemoteDisplayInfo descriptor) {
                final String description = descriptor.description;
                return TextUtils.isEmpty(description) ? null : description;
            }

            private static int computeSupportedTypes(RemoteDisplayInfo descriptor) {
                return MediaRouter.ROUTE_TYPE_LIVE_AUDIO
                        | MediaRouter.ROUTE_TYPE_LIVE_VIDEO
                        | MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY;
            }

            private static boolean computeEnabled(RemoteDisplayInfo descriptor) {
                switch (descriptor.status) {
                    case RemoteDisplayInfo.STATUS_CONNECTED:
                    case RemoteDisplayInfo.STATUS_CONNECTING:
                    case RemoteDisplayInfo.STATUS_AVAILABLE:
                        return true;
                    default:
                        return false;
                }
            }

            private static int computeStatusCode(RemoteDisplayInfo descriptor) {
                switch (descriptor.status) {
                    case RemoteDisplayInfo.STATUS_NOT_AVAILABLE:
                        return MediaRouter.RouteInfo.STATUS_NOT_AVAILABLE;
                    case RemoteDisplayInfo.STATUS_AVAILABLE:
                        return MediaRouter.RouteInfo.STATUS_AVAILABLE;
                    case RemoteDisplayInfo.STATUS_IN_USE:
                        return MediaRouter.RouteInfo.STATUS_IN_USE;
                    case RemoteDisplayInfo.STATUS_CONNECTING:
                        return MediaRouter.RouteInfo.STATUS_CONNECTING;
                    case RemoteDisplayInfo.STATUS_CONNECTED:
                        return MediaRouter.RouteInfo.STATUS_CONNECTED;
                    default:
                        return MediaRouter.RouteInfo.STATUS_NONE;
                }
            }

            private static int computePlaybackType(RemoteDisplayInfo descriptor) {
                return MediaRouter.RouteInfo.PLAYBACK_TYPE_REMOTE;
            }

            private static int computePlaybackStream(RemoteDisplayInfo descriptor) {
                return AudioSystem.STREAM_MUSIC;
            }

            private static int computeVolume(RemoteDisplayInfo descriptor) {
                final int volume = descriptor.volume;
                final int volumeMax = descriptor.volumeMax;
                if (volume < 0) {
                    return 0;
                } else if (volume > volumeMax) {
                    return volumeMax;
                }
                return volume;
            }

            private static int computeVolumeMax(RemoteDisplayInfo descriptor) {
                final int volumeMax = descriptor.volumeMax;
                return volumeMax > 0 ? volumeMax : 0;
            }

            private static int computeVolumeHandling(RemoteDisplayInfo descriptor) {
                final int volumeHandling = descriptor.volumeHandling;
                switch (volumeHandling) {
                    case RemoteDisplayInfo.PLAYBACK_VOLUME_VARIABLE:
                        return MediaRouter.RouteInfo.PLAYBACK_VOLUME_VARIABLE;
                    case RemoteDisplayInfo.PLAYBACK_VOLUME_FIXED:
                    default:
                        return MediaRouter.RouteInfo.PLAYBACK_VOLUME_FIXED;
                }
            }

            private static int computePresentationDisplayId(RemoteDisplayInfo descriptor) {
                // The MediaRouter class validates that the id corresponds to an extant
                // presentation display.  So all we do here is canonicalize the null case.
                final int displayId = descriptor.presentationDisplayId;
                return displayId < 0 ? -1 : displayId;
            }
        }
    }
}
