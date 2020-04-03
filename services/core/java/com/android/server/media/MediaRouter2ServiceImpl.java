/*
 * Copyright 2019 The Android Open Source Project
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

import static android.media.MediaRoute2ProviderService.REQUEST_ID_NONE;
import static android.media.MediaRouter2Utils.getOriginalId;
import static android.media.MediaRouter2Utils.getProviderId;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteDiscoveryPreference;
import android.media.RoutingSessionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implements features related to {@link android.media.MediaRouter2} and
 * {@link android.media.MediaRouter2Manager}.
 */
class MediaRouter2ServiceImpl {
    private static final String TAG = "MR2ServiceImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // TODO: (In Android S or later) if we add callback methods for generic failures
    //       in MediaRouter2, remove this constant and replace the usages with the real request IDs.
    private static final long DUMMY_REQUEST_ID = -1;

    private final Context mContext;
    private final Object mLock = new Object();
    final AtomicInteger mNextRouterOrManagerId = new AtomicInteger(1);

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, RouterRecord> mAllRouterRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentUserId = -1;

    MediaRouter2ServiceImpl(Context context) {
        mContext = context;
    }

    ////////////////////////////////////////////////////////////////
    ////  Calls from MediaRouter2
    ////   - Should not have @NonNull/@Nullable on any arguments
    ////////////////////////////////////////////////////////////////

    @NonNull
    public List<MediaRoute2Info> getSystemRoutes() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();

        final long token = Binder.clearCallingIdentity();
        try {
            Collection<MediaRoute2Info> systemRoutes;
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                MediaRoute2ProviderInfo providerInfo =
                        userRecord.mHandler.mSystemProvider.getProviderInfo();
                if (providerInfo != null) {
                    systemRoutes = providerInfo.getRoutes();
                } else {
                    systemRoutes = Collections.emptyList();
                }
            }
            return new ArrayList<>(systemRoutes);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    public RoutingSessionInfo getSystemSessionInfo() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();

        final long token = Binder.clearCallingIdentity();
        try {
            RoutingSessionInfo systemSessionInfo = null;
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                List<RoutingSessionInfo> sessionInfos =
                        userRecord.mHandler.mSystemProvider.getSessionInfos();
                if (sessionInfos != null && !sessionInfos.isEmpty()) {
                    systemSessionInfo = sessionInfos.get(0);
                } else {
                    Slog.w(TAG, "System provider does not have any session info.");
                }
            }
            return systemSessionInfo;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerRouter2(IMediaRouter2 router, String packageName) {
        Objects.requireNonNull(router, "router must not be null");
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName must not be empty");
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasConfigureWifiDisplayPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                == PackageManager.PERMISSION_GRANTED;
        final boolean hasModifyAudioRoutingPermission = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MODIFY_AUDIO_ROUTING)
                == PackageManager.PERMISSION_GRANTED;

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerRouter2Locked(router, uid, pid, packageName, userId,
                        hasConfigureWifiDisplayPermission, hasModifyAudioRoutingPermission);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterRouter2(IMediaRouter2 router) {
        Objects.requireNonNull(router, "router must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterRouter2Locked(router, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setDiscoveryRequestWithRouter2(IMediaRouter2 router,
            RouteDiscoveryPreference preference) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(preference, "preference must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                RouterRecord routerRecord = mAllRouterRecords.get(router.asBinder());
                if (routerRecord == null) {
                    Slog.w(TAG, "Ignoring updating discoveryRequest of null routerRecord.");
                    return;
                }
                setDiscoveryRequestWithRouter2Locked(routerRecord, preference);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithRouter2(IMediaRouter2 router,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setRouteVolumeWithRouter2Locked(router, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateSessionWithRouter2(IMediaRouter2 router, int requestId,
            MediaRoute2Info route, Bundle sessionHints) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithRouter2Locked(requestId, router, route, sessionHints);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }


    public void deselectRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                deselectRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void transferToRouteWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithRouter2Locked(router, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithRouter2(IMediaRouter2 router, String uniqueSessionId,
            int volume) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(uniqueSessionId, "uniqueSessionId must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSessionVolumeWithRouter2Locked(router, uniqueSessionId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void releaseSessionWithRouter2(IMediaRouter2 router, String uniqueSessionId) {
        Objects.requireNonNull(router, "router must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                releaseSessionWithRouter2Locked(router, uniqueSessionId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    ////////////////////////////////////////////////////////////////
    ////  Calls from MediaRouter2Manager
    ////   - Should not have @NonNull/@Nullable on any arguments
    ////////////////////////////////////////////////////////////////

    @NonNull
    public List<RoutingSessionInfo> getActiveSessions(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                return getActiveSessionsLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerManager(IMediaRouter2Manager manager, String packageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName must not be empty");
        }

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(manager, uid, pid, packageName, userId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterManager(IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterManagerLocked(manager, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setRouteVolumeWithManagerLocked(requestId, manager, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateSessionWithManager(IMediaRouter2Manager manager, int requestId,
            String packageName, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithManagerLocked(requestId, manager, packageName, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void deselectRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                deselectRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void transferToRouteWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, MediaRoute2Info route) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithManagerLocked(requestId, manager, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSessionVolumeWithManagerLocked(requestId, manager, uniqueSessionId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void releaseSessionWithManager(IMediaRouter2Manager manager, int requestId,
            String uniqueSessionId) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                releaseSessionWithManagerLocked(requestId, manager, uniqueSessionId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    //TODO(b/136703681): Review this is handling multi-user properly.
    void switchUser() {
        synchronized (mLock) {
            int userId = ActivityManager.getCurrentUser();
            if (mCurrentUserId != userId) {
                final int oldUserId = mCurrentUserId;
                mCurrentUserId = userId; // do this first

                UserRecord oldUser = mUserRecords.get(oldUserId);
                if (oldUser != null) {
                    oldUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::stop, oldUser.mHandler));
                    disposeUserIfNeededLocked(oldUser); // since no longer current user
                }

                UserRecord newUser = mUserRecords.get(userId);
                if (newUser != null) {
                    newUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::start, newUser.mHandler));
                }
            }
        }
    }

    void routerDied(@NonNull RouterRecord routerRecord) {
        synchronized (mLock) {
            unregisterRouter2Locked(routerRecord.mRouter, true);
        }
    }

    void managerDied(@NonNull ManagerRecord managerRecord) {
        synchronized (mLock) {
            unregisterManagerLocked(managerRecord.mManager, true);
        }
    }

    ////////////////////////////////////////////////////////////////
    ////  ***Locked methods related to MediaRouter2
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////////

    private void registerRouter2Locked(@NonNull IMediaRouter2 router, int uid, int pid,
            @NonNull String packageName, int userId, boolean hasConfigureWifiDisplayPermission,
            boolean hasModifyAudioRoutingPermission) {
        final IBinder binder = router.asBinder();
        if (mAllRouterRecords.get(binder) != null) {
            Slog.w(TAG, "Same router already exists. packageName=" + packageName);
            return;
        }

        UserRecord userRecord = getOrCreateUserRecordLocked(userId);
        RouterRecord routerRecord = new RouterRecord(userRecord, router, uid, pid, packageName,
                hasConfigureWifiDisplayPermission, hasModifyAudioRoutingPermission);
        try {
            binder.linkToDeath(routerRecord, 0);
        } catch (RemoteException ex) {
            throw new RuntimeException("MediaRouter2 died prematurely.", ex);
        }

        userRecord.mRouterRecords.add(routerRecord);
        mAllRouterRecords.put(binder, routerRecord);

        userRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyRoutesToRouter, userRecord.mHandler, router));
    }

    private void unregisterRouter2Locked(@NonNull IMediaRouter2 router, boolean died) {
        RouterRecord routerRecord = mAllRouterRecords.remove(router.asBinder());
        if (routerRecord == null) {
            Slog.w(TAG, "Ignoring unregistering unknown router2");
            return;
        }

        UserRecord userRecord = routerRecord.mUserRecord;
        userRecord.mRouterRecords.remove(routerRecord);
        userRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        userRecord.mHandler));
        routerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since router removed from user
    }

    private void setDiscoveryRequestWithRouter2Locked(@NonNull RouterRecord routerRecord,
            @NonNull RouteDiscoveryPreference discoveryRequest) {
        if (routerRecord.mDiscoveryPreference.equals(discoveryRequest)) {
            return;
        }
        routerRecord.mDiscoveryPreference = discoveryRequest;
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyPreferredFeaturesChangedToManagers,
                        routerRecord.mUserRecord.mHandler, routerRecord));
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        routerRecord.mUserRecord.mHandler));
    }

    private void setRouteVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord != null) {
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::setRouteVolumeOnHandler,
                            routerRecord.mUserRecord.mHandler,
                            DUMMY_REQUEST_ID, route, volume));
        }
    }

    private void requestCreateSessionWithRouter2Locked(int requestId, @NonNull IMediaRouter2 router,
            @NonNull MediaRoute2Info route, @Nullable Bundle sessionHints) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        long uniqueRequestId = toUniqueRequestId(routerRecord.mRouterId, requestId);
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::requestCreateSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, /* managerRecord= */ null, route,
                        sessionHints));
    }

    private void selectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    private void deselectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    private void transferToRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::transferToRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    private void setSessionVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, uniqueSessionId, volume));
    }

    private void releaseSessionWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId));
    }

    ////////////////////////////////////////////////////////////
    ////  ***Locked methods related to MediaRouter2Manager
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////

    private List<RoutingSessionInfo> getActiveSessionsLocked(
            @NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            Slog.w(TAG, "getActiveSessionLocked: Ignoring unknown manager");
            return Collections.emptyList();
        }

        List<RoutingSessionInfo> sessionInfos = new ArrayList<>();
        for (MediaRoute2Provider provider : managerRecord.mUserRecord.mHandler.mRouteProviders) {
            sessionInfos.addAll(provider.getSessionInfos());
        }
        return sessionInfos;
    }

    private void registerManagerLocked(@NonNull IMediaRouter2Manager manager,
            int uid, int pid, @NonNull String packageName, int userId) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            Slog.w(TAG, "Same manager already exists. packageName=" + packageName);
            return;
        }

        UserRecord userRecord = getOrCreateUserRecordLocked(userId);
        managerRecord = new ManagerRecord(userRecord, manager, uid, pid, packageName);
        try {
            binder.linkToDeath(managerRecord, 0);
        } catch (RemoteException ex) {
            throw new RuntimeException("Media router manager died prematurely.", ex);
        }

        userRecord.mManagerRecords.add(managerRecord);
        mAllManagerRecords.put(binder, managerRecord);

        userRecord.mHandler.sendMessage(obtainMessage(UserHandler::notifyRoutesToManager,
                userRecord.mHandler, manager));

        for (RouterRecord routerRecord : userRecord.mRouterRecords) {
            // TODO: Do not use notifyPreferredFeaturesChangedToManagers since it updates all
            // managers. Instead, Notify only to the manager that is currently being registered.

            // TODO: UserRecord <-> routerRecord, why do they reference each other?
            // How about removing mUserRecord from routerRecord?
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyPreferredFeaturesChangedToManagers,
                        routerRecord.mUserRecord.mHandler, routerRecord));
        }
    }

    private void unregisterManagerLocked(@NonNull IMediaRouter2Manager manager, boolean died) {
        ManagerRecord managerRecord = mAllManagerRecords.remove(manager.asBinder());
        if (managerRecord == null) {
            return;
        }
        UserRecord userRecord = managerRecord.mUserRecord;
        userRecord.mManagerRecords.remove(managerRecord);
        managerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since manager removed from user
    }

    private void setRouteVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setRouteVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, route, volume));
    }

    private void requestCreateSessionWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String packageName, @NonNull MediaRoute2Info route) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord == null) {
            return;
        }

        RouterRecord routerRecord = managerRecord.mUserRecord.findRouterRecordLocked(packageName);
        if (routerRecord == null) {
            Slog.w(TAG, "Ignoring session creation for unknown router.");
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        //TODO(b/152851868): Use MediaRouter2's OnCreateSessionListener to send session hints.
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::requestCreateSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, managerRecord, route,
                        /* sessionHints= */ null));
    }

    private void selectRouteWithManagerLocked(int requestId, @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        // Can be null if the session is system's.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterforSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void deselectRouteWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        // Can be null if the session is system's.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterforSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void transferToRouteWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        // Can be null if the session is system's.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterforSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::transferToRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    private void setSessionVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, uniqueSessionId, volume));
    }

    private void releaseSessionWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterforSessionLocked(uniqueSessionId);
        if (routerRecord == null) {
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId));
    }

    ////////////////////////////////////////////////////////////
    ////  ***Locked methods used by both router2 and manager
    ////   - Should have @NonNull/@Nullable on all arguments
    ////////////////////////////////////////////////////////////

    private UserRecord getOrCreateUserRecordLocked(int userId) {
        UserRecord userRecord = mUserRecords.get(userId);
        if (userRecord == null) {
            userRecord = new UserRecord(userId);
            mUserRecords.put(userId, userRecord);
            if (userId == mCurrentUserId) {
                userRecord.mHandler.sendMessage(
                        obtainMessage(UserHandler::start, userRecord.mHandler));
            }
        }
        return userRecord;
    }

    private void disposeUserIfNeededLocked(@NonNull UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (userRecord.mUserId != mCurrentUserId
                && userRecord.mRouterRecords.isEmpty()
                && userRecord.mManagerRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    static long toUniqueRequestId(int requesterId, int originalRequestId) {
        return ((long) requesterId << 32) | originalRequestId;
    }

    static int toRequesterId(long uniqueRequestId) {
        return (int) (uniqueRequestId >> 32);
    }

    static int toOriginalRequestId(long uniqueRequestId) {
        return (int) uniqueRequestId;
    }

    final class UserRecord {
        public final int mUserId;
        //TODO: make records private for thread-safety
        final ArrayList<RouterRecord> mRouterRecords = new ArrayList<>();
        final ArrayList<ManagerRecord> mManagerRecords = new ArrayList<>();
        RouteDiscoveryPreference mCompositeDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }

        // TODO: This assumes that only one router exists in a package. Is it true?
        RouterRecord findRouterRecordLocked(String packageName) {
            for (RouterRecord routerRecord : mRouterRecords) {
                if (TextUtils.equals(routerRecord.mPackageName, packageName)) {
                    return routerRecord;
                }
            }
            return null;
        }
    }

    final class RouterRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final String mPackageName;
        public final List<Integer> mSelectRouteSequenceNumbers;
        public final IMediaRouter2 mRouter;
        public final int mUid;
        public final int mPid;
        public final boolean mHasConfigureWifiDisplayPermission;
        public final boolean mHasModifyAudioRoutingPermission;
        public final int mRouterId;

        public RouteDiscoveryPreference mDiscoveryPreference;
        public MediaRoute2Info mSelectedRoute;

        RouterRecord(UserRecord userRecord, IMediaRouter2 router, int uid, int pid,
                String packageName, boolean hasConfigureWifiDisplayPermission,
                boolean hasModifyAudioRoutingPermission) {
            mUserRecord = userRecord;
            mPackageName = packageName;
            mSelectRouteSequenceNumbers = new ArrayList<>();
            mDiscoveryPreference = RouteDiscoveryPreference.EMPTY;
            mRouter = router;
            mUid = uid;
            mPid = pid;
            mHasConfigureWifiDisplayPermission = hasConfigureWifiDisplayPermission;
            mHasModifyAudioRoutingPermission = hasModifyAudioRoutingPermission;
            mRouterId = mNextRouterOrManagerId.getAndIncrement();
        }

        public void dispose() {
            mRouter.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            routerDied(this);
        }
    }

    final class ManagerRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouter2Manager mManager;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final int mManagerId;

        ManagerRecord(UserRecord userRecord, IMediaRouter2Manager manager,
                int uid, int pid, String packageName) {
            mUserRecord = userRecord;
            mManager = manager;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mManagerId = mNextRouterOrManagerId.getAndIncrement();
        }

        public void dispose() {
            mManager.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            managerDied(this);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);
        }

        @Override
        public String toString() {
            return "Manager " + mPackageName + " (pid " + mPid + ")";
        }
    }

    static final class UserHandler extends Handler implements
            MediaRoute2ProviderWatcher.Callback,
            MediaRoute2Provider.Callback {

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        //TODO: Make this thread-safe.
        private final SystemMediaRoute2Provider mSystemProvider;
        private final ArrayList<MediaRoute2Provider> mRouteProviders =
                new ArrayList<>();

        private final List<MediaRoute2ProviderInfo> mLastProviderInfos = new ArrayList<>();
        private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
                new CopyOnWriteArrayList<>();
        private final Map<String, RouterRecord> mSessionToRouterMap = new ArrayMap<>();

        private boolean mRunning;

        UserHandler(@NonNull MediaRouter2ServiceImpl service, @NonNull UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mSystemProvider = new SystemMediaRoute2Provider(service.mContext, this);
            mRouteProviders.add(mSystemProvider);
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mWatcher.start();
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                //TODO: may unselect routes
                mWatcher.stop(); // also stops all providers
            }
        }

        @Override
        public void onAddProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy) {
            proxy.setCallback(this);
            mRouteProviders.add(proxy);
            proxy.updateDiscoveryPreference(mUserRecord.mCompositeDiscoveryPreference);
        }

        @Override
        public void onRemoveProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy) {
            mRouteProviders.remove(proxy);
        }

        @Override
        public void onProviderStateChanged(@NonNull MediaRoute2Provider provider) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onProviderStateChangedOnHandler,
                    this, provider));
        }

        @Override
        public void onSessionCreated(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionCreatedOnHandler,
                    this, provider, uniqueRequestId, sessionInfo));
        }


        @Override
        public void onSessionUpdated(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionInfoChangedOnHandler,
                    this, provider, sessionInfo));
        }

        @Override
        public void onSessionReleased(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onSessionReleasedOnHandler,
                    this, provider, sessionInfo));
        }

        @Override
        public void onRequestFailed(@NonNull MediaRoute2Provider provider, long uniqueRequestId,
                int reason) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::onRequestFailedOnHandler,
                    this, provider, uniqueRequestId, reason));
        }

        @Nullable
        public RouterRecord findRouterforSessionLocked(@NonNull String uniqueSessionId) {
            return mSessionToRouterMap.get(uniqueSessionId);
        }

        private void onProviderStateChangedOnHandler(@NonNull MediaRoute2Provider provider) {
            int providerInfoIndex = getLastProviderInfoIndex(provider.getUniqueId());
            MediaRoute2ProviderInfo providerInfo = provider.getProviderInfo();
            MediaRoute2ProviderInfo prevInfo =
                    (providerInfoIndex < 0) ? null : mLastProviderInfos.get(providerInfoIndex);

            if (Objects.equals(prevInfo, providerInfo)) return;

            if (prevInfo == null) {
                mLastProviderInfos.add(providerInfo);
                Collection<MediaRoute2Info> addedRoutes = providerInfo.getRoutes();
                if (addedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(UserHandler::notifyRoutesAddedToRouters,
                            this, getRouters(), new ArrayList<>(addedRoutes)));
                }
            } else if (providerInfo == null) {
                mLastProviderInfos.remove(prevInfo);
                Collection<MediaRoute2Info> removedRoutes = prevInfo.getRoutes();
                if (removedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(
                            UserHandler::notifyRoutesRemovedToRouters,
                            this, getRouters(), new ArrayList<>(removedRoutes)));
                }
            } else {
                mLastProviderInfos.set(providerInfoIndex, providerInfo);
                List<MediaRoute2Info> addedRoutes = new ArrayList<>();
                List<MediaRoute2Info> removedRoutes = new ArrayList<>();
                List<MediaRoute2Info> changedRoutes = new ArrayList<>();

                final Collection<MediaRoute2Info> currentRoutes = providerInfo.getRoutes();
                final Set<String> updatedRouteIds = new HashSet<>();

                for (MediaRoute2Info route : currentRoutes) {
                    if (!route.isValid()) {
                        Slog.w(TAG, "Ignoring invalid route : " + route);
                        continue;
                    }
                    MediaRoute2Info prevRoute = prevInfo.getRoute(route.getOriginalId());

                    if (prevRoute != null) {
                        if (!Objects.equals(prevRoute, route)) {
                            changedRoutes.add(route);
                        }
                        updatedRouteIds.add(route.getId());
                    } else {
                        addedRoutes.add(route);
                    }
                }

                for (MediaRoute2Info prevRoute : prevInfo.getRoutes()) {
                    if (!updatedRouteIds.contains(prevRoute.getId())) {
                        removedRoutes.add(prevRoute);
                    }
                }

                List<IMediaRouter2> routers = getRouters();
                List<IMediaRouter2Manager> managers = getManagers();
                if (addedRoutes.size() > 0) {
                    notifyRoutesAddedToRouters(routers, addedRoutes);
                    notifyRoutesAddedToManagers(managers, addedRoutes);
                }
                if (removedRoutes.size() > 0) {
                    notifyRoutesRemovedToRouters(routers, removedRoutes);
                    notifyRoutesRemovedToManagers(managers, removedRoutes);
                }
                if (changedRoutes.size() > 0) {
                    notifyRoutesChangedToRouters(routers, changedRoutes);
                    notifyRoutesChangedToManagers(managers, changedRoutes);
                }
            }
        }

        private int getLastProviderInfoIndex(@NonNull String providerId) {
            for (int i = 0; i < mLastProviderInfos.size(); i++) {
                MediaRoute2ProviderInfo providerInfo = mLastProviderInfos.get(i);
                if (TextUtils.equals(providerInfo.getUniqueId(), providerId)) {
                    return i;
                }
            }
            return -1;
        }

        private void requestCreateSessionOnHandler(long uniqueRequestId,
                @NonNull RouterRecord routerRecord, @Nullable ManagerRecord managerRecord,
                @NonNull MediaRoute2Info route, @Nullable Bundle sessionHints) {

            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "Ignoring session creation request since no provider found for"
                        + " given route=" + route);
                notifySessionCreationFailedToRouter(routerRecord,
                        toOriginalRequestId(uniqueRequestId));
                return;
            }

            SessionCreationRequest request =
                    new SessionCreationRequest(routerRecord, uniqueRequestId, route, managerRecord);
            mSessionCreationRequests.add(request);

            provider.requestCreateSession(uniqueRequestId, routerRecord.mPackageName,
                    route.getOriginalId(), sessionHints);
        }

        // routerRecord can be null if the session is system's.
        private void selectRouteOnHandler(long uniqueRequestId, @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "selecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }
            provider.selectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's.
        private void deselectRouteOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "deselecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }

            provider.deselectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's.
        private void transferToRouteOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "transferring to")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }
            provider.transferToRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        private boolean checkArgumentsForSessionControl(@Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route,
                @NonNull String description) {
            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                Slog.w(TAG, "Ignoring " + description + " route since no provider found for "
                        + "given route=" + route);
                return false;
            }

            // Bypass checking router if it's the system session (routerRecord should be null)
            if (TextUtils.equals(getProviderId(uniqueSessionId), mSystemProvider.getUniqueId())) {
                return true;
            }

            //TODO(b/152950479): Handle RCN case.
            if (routerRecord == null) {
                Slog.w(TAG, "Ignoring " + description + " route from unknown router.");
                return false;
            }

            RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(TAG, "Ignoring " + description + " route from non-matching router. "
                        + "packageName=" + routerRecord.mPackageName + " route=" + route);
                return false;
            }

            final String sessionId = getOriginalId(uniqueSessionId);
            if (sessionId == null) {
                Slog.w(TAG, "Failed to get original session id from unique session id. "
                        + "uniqueSessionId=" + uniqueSessionId);
                return false;
            }

            return true;
        }

        private void setRouteVolumeOnHandler(long uniqueRequestId, @NonNull MediaRoute2Info route,
                int volume) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "setRouteVolume: couldn't find provider for route=" + route);
                return;
            }
            provider.setRouteVolume(uniqueRequestId, route.getOriginalId(), volume);
        }

        private void setSessionVolumeOnHandler(long uniqueRequestId,
                @NonNull String uniqueSessionId, int volume) {
            final MediaRoute2Provider provider = findProvider(getProviderId(uniqueSessionId));
            if (provider == null) {
                Slog.w(TAG, "setSessionVolume: couldn't find provider for session "
                        + "id=" + uniqueSessionId);
                return;
            }
            provider.setSessionVolume(uniqueRequestId, getOriginalId(uniqueSessionId), volume);
        }

        private void releaseSessionOnHandler(long uniqueRequestId,
                @NonNull RouterRecord routerRecord, @NonNull String uniqueSessionId) {
            final RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(TAG, "Ignoring releasing session from non-matching router."
                        + " packageName=" + routerRecord.mPackageName
                        + " uniqueSessionId=" + uniqueSessionId);
                return;
            }

            final String providerId = getProviderId(uniqueSessionId);
            if (providerId == null) {
                Slog.w(TAG, "Ignoring releasing session with invalid unique session ID. "
                        + "uniqueSessionId=" + uniqueSessionId);
                return;
            }

            final String sessionId = getOriginalId(uniqueSessionId);
            if (sessionId == null) {
                Slog.w(TAG, "Ignoring releasing session with invalid unique session ID. "
                        + "uniqueSessionId=" + uniqueSessionId + " providerId=" + providerId);
                return;
            }

            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                Slog.w(TAG, "Ignoring releasing session since no provider found for given "
                        + "providerId=" + providerId);
                return;
            }

            provider.releaseSession(uniqueRequestId, sessionId);
        }

        private void onSessionCreatedOnHandler(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, @NonNull RoutingSessionInfo sessionInfo) {
            notifySessionCreatedToManagers(getManagers(),
                    toOriginalRequestId(uniqueRequestId), sessionInfo);

            if (uniqueRequestId == REQUEST_ID_NONE) {
                // The session is created without any matching request.
                return;
            }

            SessionCreationRequest matchingRequest = null;

            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mUniqueRequestId == uniqueRequestId
                        && TextUtils.equals(
                                request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest == null) {
                Slog.w(TAG, "Ignoring session creation result for unknown request. "
                        + "uniqueRequestId=" + uniqueRequestId + ", sessionInfo=" + sessionInfo);
                return;
            }

            mSessionCreationRequests.remove(matchingRequest);

            if (sessionInfo == null) {
                // Failed
                notifySessionCreationFailedToRouter(matchingRequest.mRouterRecord,
                        toOriginalRequestId(uniqueRequestId));
                return;
            }

            String originalRouteId = matchingRequest.mRoute.getId();
            RouterRecord routerRecord = matchingRequest.mRouterRecord;

            if (!sessionInfo.getSelectedRoutes().contains(originalRouteId)) {
                Slog.w(TAG, "Created session doesn't match the original request."
                        + " originalRouteId=" + originalRouteId
                        + ", uniqueRequestId=" + uniqueRequestId + ", sessionInfo=" + sessionInfo);
                notifySessionCreationFailedToRouter(matchingRequest.mRouterRecord,
                        toOriginalRequestId(uniqueRequestId));
                return;
            }

            // Succeeded
            notifySessionCreatedToRouter(matchingRequest.mRouterRecord,
                    toOriginalRequestId(uniqueRequestId), sessionInfo);
            mSessionToRouterMap.put(sessionInfo.getId(), routerRecord);
        }

        private void onSessionInfoChangedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionInfoChangedToManagers(managers, sessionInfo);

            // For system provider, notify all routers.
            if (provider == mSystemProvider) {
                MediaRouter2ServiceImpl service = mServiceRef.get();
                if (service == null) {
                    return;
                }
                notifySessionInfoChangedToRouters(getRouters(), sessionInfo);
                return;
            }

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "No matching router found for session=" + sessionInfo);
                return;
            }
            notifySessionInfoChangedToRouter(routerRecord, sessionInfo);
        }

        private void onSessionReleasedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionInfoChangedToManagers(managers, sessionInfo);

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "No matching router found for session=" + sessionInfo);
                return;
            }
            notifySessionReleasedToRouter(routerRecord, sessionInfo);
        }

        private void onRequestFailedOnHandler(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, int reason) {
            if (handleSessionCreationRequestFailed(provider, uniqueRequestId, reason)) {
                return;
            }

            final int requesterId = toRequesterId(uniqueRequestId);
            for (ManagerRecord manager : getManagerRecords()) {
                if (manager.mManagerId == requesterId) {
                    notifyRequestFailedToManager(
                            manager.mManager, toOriginalRequestId(uniqueRequestId), reason);
                    return;
                }
            }

            // Currently, only the manager can get notified of failures.
            // TODO: Notify router too when the related callback is introduced.
        }

        // TODO: Find a way to prevent providers from notifying error on random uniqueRequestId.
        //       Solutions can be:
        //       1) Record the other type of requests too (not only session creation request)
        //       2) Throw exception on providers when they try to notify error on
        //          random uniqueRequestId.
        private boolean handleSessionCreationRequestFailed(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, int reason) {
            // Check whether the failure is about creating a session
            SessionCreationRequest matchingRequest = null;
            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mUniqueRequestId == uniqueRequestId && TextUtils.equals(
                        request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest == null) {
                // The failure is not about creating a session.
                return false;
            }

            mSessionCreationRequests.remove(matchingRequest);

            // Notify the requester about the failure.
            // The call should be made by either MediaRouter2 or MediaRouter2Manager.
            if (matchingRequest.mRequestedManagerRecord == null) {
                notifySessionCreationFailedToRouter(
                        matchingRequest.mRouterRecord, toOriginalRequestId(uniqueRequestId));
            } else {
                notifyRequestFailedToManager(matchingRequest.mRequestedManagerRecord.mManager,
                        toOriginalRequestId(uniqueRequestId), reason);
            }
            return true;
        }

        private void notifySessionCreatedToRouter(@NonNull RouterRecord routerRecord,
                int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionCreated(requestId, sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session creation."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionCreationFailedToRouter(@NonNull RouterRecord routerRecord,
                int requestId) {
            try {
                routerRecord.mRouter.notifySessionCreated(requestId,
                        /* sessionInfo= */ null);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session creation failure."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionInfoChangedToRouter(@NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionInfoChanged(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session info change."
                        + " Router probably died.", ex);
            }
        }

        private void notifySessionReleasedToRouter(@NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionReleased(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session release."
                        + " Router probably died.", ex);
            }
        }

        private List<IMediaRouter2> getRouters() {
            final List<IMediaRouter2> routers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return routers;
            }
            synchronized (service.mLock) {
                for (RouterRecord routerRecord : mUserRecord.mRouterRecords) {
                    routers.add(routerRecord.mRouter);
                }
            }
            return routers;
        }

        private List<IMediaRouter2Manager> getManagers() {
            final List<IMediaRouter2Manager> managers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return managers;
            }
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            return managers;
        }

        private List<ManagerRecord> getManagerRecords() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return Collections.emptyList();
            }
            synchronized (service.mLock) {
                return new ArrayList<>(mUserRecord.mManagerRecords);
            }
        }

        private void notifyRoutesToRouter(@NonNull IMediaRouter2 router) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                router.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Router probably died.", ex);
            }
        }

        private void notifyRoutesAddedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Router probably died.", ex);
                }
            }
        }

        private void notifySessionInfoChangedToRouters(@NonNull List<IMediaRouter2> routers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2 router : routers) {
                try {
                    router.notifySessionInfoChanged(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify session info changed. Router probably died.", ex);
                }
            }
        }

        private void notifyRoutesToManager(@NonNull IMediaRouter2Manager manager) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                manager.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Manager probably died.", ex);
            }
        }

        private void notifyRoutesAddedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionCreatedToManagers(@NonNull List<IMediaRouter2Manager> managers,
                int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionCreated(requestId, sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionCreatedToManagers: "
                            + "failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionInfoChangedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionUpdated(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionInfosChangedToManagers: "
                            + "failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifyPreferredFeaturesChangedToManagers(@NonNull RouterRecord routerRecord) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<IMediaRouter2Manager> managers = new ArrayList<>();
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyPreferredFeaturesChanged(routerRecord.mPackageName,
                            routerRecord.mDiscoveryPreference.getPreferredFeatures());
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify preferred features changed."
                            + " Manager probably died.", ex);
                }
            }
        }

        private void notifyRequestFailedToManager(@NonNull IMediaRouter2Manager manager,
                int requestId, int reason) {
            try {
                manager.notifyRequestFailed(requestId, reason);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify manager of the request failure."
                        + " Manager probably died.", ex);
            }
        }

        private void updateDiscoveryPreferenceOnHandler() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<RouteDiscoveryPreference> discoveryPreferences = new ArrayList<>();
            synchronized (service.mLock) {
                for (RouterRecord routerRecord : mUserRecord.mRouterRecords) {
                    discoveryPreferences.add(routerRecord.mDiscoveryPreference);
                }
                mUserRecord.mCompositeDiscoveryPreference =
                        new RouteDiscoveryPreference.Builder(discoveryPreferences)
                        .build();
            }
            for (MediaRoute2Provider provider : mRouteProviders) {
                provider.updateDiscoveryPreference(mUserRecord.mCompositeDiscoveryPreference);
            }
        }

        private MediaRoute2Provider findProvider(@Nullable String providerId) {
            for (MediaRoute2Provider provider : mRouteProviders) {
                if (TextUtils.equals(provider.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }

        final class SessionCreationRequest {
            public final RouterRecord mRouterRecord;
            public final long mUniqueRequestId;
            public final MediaRoute2Info mRoute;
            public final ManagerRecord mRequestedManagerRecord;

            // requestedManagerRecord is not null only when the request is made by manager.
            SessionCreationRequest(@NonNull RouterRecord routerRecord, long uniqueRequestId,
                    @NonNull MediaRoute2Info route,
                    @Nullable ManagerRecord requestedManagerRecord) {
                mRouterRecord = routerRecord;
                mUniqueRequestId = uniqueRequestId;
                mRoute = route;
                mRequestedManagerRecord = requestedManagerRecord;
            }
        }
    }
}
