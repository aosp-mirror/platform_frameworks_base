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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.content.Intent.ACTION_SCREEN_OFF;
import static android.content.Intent.ACTION_SCREEN_ON;
import static android.media.MediaRoute2ProviderService.REASON_UNKNOWN_ERROR;
import static android.media.MediaRouter2Utils.getOriginalId;
import static android.media.MediaRouter2Utils.getProviderId;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.media.MediaFeatureFlagManager.FEATURE_SCANNING_MINIMUM_PACKAGE_IMPORTANCE;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.PermissionChecker;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2;
import android.media.IMediaRouter2Manager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.MediaRoute2ProviderService;
import android.media.MediaRouter2Manager;
import android.media.RouteDiscoveryPreference;
import android.media.RouteListingPreference;
import android.media.RoutingSessionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.media.flags.Flags;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    private static int sPackageImportanceForScanning =
            MediaFeatureFlagManager.getInstance()
                    .getInt(
                            FEATURE_SCANNING_MINIMUM_PACKAGE_IMPORTANCE,
                            IMPORTANCE_FOREGROUND_SERVICE);

    /**
     * Contains the list of bluetooth permissions that are required to do system routing.
     *
     * <p>Alternatively, apps that hold {@link android.Manifest.permission#MODIFY_AUDIO_ROUTING} are
     * also allowed to do system routing.
     */
    private static final String[] BLUETOOTH_PERMISSIONS_FOR_SYSTEM_ROUTING =
            new String[] {
                Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN
            };

    private final Context mContext;
    private final UserManagerInternal mUserManagerInternal;
    private final Object mLock = new Object();
    final AtomicInteger mNextRouterOrManagerId = new AtomicInteger(1);
    final ActivityManager mActivityManager;
    final PowerManager mPowerManager;

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, RouterRecord> mAllRouterRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentActiveUserId = -1;

    private final ActivityManager.OnUidImportanceListener mOnUidImportanceListener =
            (uid, importance) -> {
                synchronized (mLock) {
                    final int count = mUserRecords.size();
                    for (int i = 0; i < count; i++) {
                        mUserRecords.valueAt(i).mHandler.maybeUpdateDiscoveryPreferenceForUid(uid);
                    }
                }
            };

    private final BroadcastReceiver mScreenOnOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            synchronized (mLock) {
                final int count = mUserRecords.size();
                for (int i = 0; i < count; i++) {
                    UserHandler userHandler = mUserRecords.valueAt(i).mHandler;
                    userHandler.sendMessage(PooledLambda.obtainMessage(
                            UserHandler::updateDiscoveryPreferenceOnHandler, userHandler));
                }
            }
        }
    };

    @RequiresPermission(Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS)
    /* package */ MediaRouter2ServiceImpl(Context context) {
        mContext = context;
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mActivityManager.addOnUidImportanceListener(mOnUidImportanceListener,
                sPackageImportanceForScanning);
        mPowerManager = mContext.getSystemService(PowerManager.class);
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);

        if (!Flags.disableScreenOffBroadcastReceiver()) {
            IntentFilter screenOnOffIntentFilter = new IntentFilter();
            screenOnOffIntentFilter.addAction(ACTION_SCREEN_ON);
            screenOnOffIntentFilter.addAction(ACTION_SCREEN_OFF);
            mContext.registerReceiver(mScreenOnOffReceiver, screenOnOffIntentFilter);
        }

        mContext.getPackageManager().addOnPermissionsChangeListener(this::onPermissionsChanged);

        MediaFeatureFlagManager.getInstance()
                .addOnPropertiesChangedListener(this::onDeviceConfigChange);
    }

    /**
     * Called when there's a change in the permissions of an app.
     *
     * @param uid The uid of the app whose permissions changed.
     */
    private void onPermissionsChanged(int uid) {
        synchronized (mLock) {
            Optional<RouterRecord> affectedRouter =
                    mAllRouterRecords.values().stream().filter(it -> it.mUid == uid).findFirst();
            if (affectedRouter.isPresent()) {
                affectedRouter.get().maybeUpdateSystemRoutingPermissionLocked();
            }
        }
    }

    // Start of methods that implement MediaRouter2 operations.

    @NonNull
    public List<MediaRoute2Info> getSystemRoutes() {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasSystemRoutingPermission = checkCallerHasSystemRoutingPermissions(pid, uid);

        final long token = Binder.clearCallingIdentity();
        try {
            Collection<MediaRoute2Info> systemRoutes;
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                if (hasSystemRoutingPermission) {
                    MediaRoute2ProviderInfo providerInfo =
                            userRecord.mHandler.mSystemProvider.getProviderInfo();
                    if (providerInfo != null) {
                        systemRoutes = providerInfo.getRoutes();
                    } else {
                        systemRoutes = Collections.emptyList();
                    }
                } else {
                    systemRoutes = new ArrayList<>();
                    systemRoutes.add(userRecord.mHandler.mSystemProvider.getDefaultRoute());
                }
            }
            return new ArrayList<>(systemRoutes);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerRouter2(@NonNull IMediaRouter2 router, @NonNull String packageName) {
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
        final boolean hasModifyAudioRoutingPermission =
                checkCallerHasModifyAudioRoutingPermission(pid, uid);

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

    public void unregisterRouter2(@NonNull IMediaRouter2 router) {
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

    public void setDiscoveryRequestWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull RouteDiscoveryPreference preference) {
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

    public void setRouteListingPreference(
            @NonNull IMediaRouter2 router,
            @Nullable RouteListingPreference routeListingPreference) {
        ComponentName linkedItemLandingComponent =
                routeListingPreference != null
                        ? routeListingPreference.getLinkedItemComponentName()
                        : null;
        if (linkedItemLandingComponent != null) {
            int callingUid = Binder.getCallingUid();
            MediaServerUtils.enforcePackageName(
                    mContext, linkedItemLandingComponent.getPackageName(), callingUid);
            if (!MediaServerUtils.isValidActivityComponentName(
                    mContext,
                    linkedItemLandingComponent,
                    RouteListingPreference.ACTION_TRANSFER_MEDIA,
                    Binder.getCallingUserHandle())) {
                throw new IllegalArgumentException(
                        "Unable to resolve "
                                + linkedItemLandingComponent
                                + " to a valid activity for "
                                + RouteListingPreference.ACTION_TRANSFER_MEDIA);
            }
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                RouterRecord routerRecord = mAllRouterRecords.get(router.asBinder());
                if (routerRecord == null) {
                    Slog.w(TAG, "Ignoring updating route listing of null routerRecord.");
                    return;
                }
                setRouteListingPreferenceLocked(routerRecord, routeListingPreference);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull MediaRoute2Info route, int volume) {
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

    public void requestCreateSessionWithRouter2(
            @NonNull IMediaRouter2 router,
            int requestId,
            long managerRequestId,
            @NonNull RoutingSessionInfo oldSession,
            @NonNull MediaRoute2Info route,
            Bundle sessionHints,
            @Nullable UserHandle transferInitiatorUserHandle,
            @Nullable String transferInitiatorPackageName) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(oldSession, "oldSession must not be null");
        Objects.requireNonNull(route, "route must not be null");

        synchronized (mLock) {
            if (managerRequestId == MediaRoute2ProviderService.REQUEST_ID_NONE
                    || transferInitiatorUserHandle == null
                    || transferInitiatorPackageName == null) {
                final IBinder binder = router.asBinder();
                final RouterRecord routerRecord = mAllRouterRecords.get(binder);

                transferInitiatorUserHandle = Binder.getCallingUserHandle();
                if (routerRecord != null) {
                    transferInitiatorPackageName = routerRecord.mPackageName;
                } else {
                    transferInitiatorPackageName = mContext.getPackageName();
                }
            }
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithRouter2Locked(
                        requestId,
                        managerRequestId,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName,
                        router,
                        oldSession,
                        route,
                        sessionHints);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
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

    public void deselectRouteWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
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

    public void transferToRouteWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        UserHandle userHandle = Binder.getCallingUserHandle();
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithRouter2Locked(router, userHandle, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, int volume) {
        Objects.requireNonNull(router, "router must not be null");
        Objects.requireNonNull(uniqueSessionId, "uniqueSessionId must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setSessionVolumeWithRouter2Locked(router, uniqueSessionId, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void releaseSessionWithRouter2(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId) {
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

    // End of methods that implement MediaRouter2 operations.

    // Start of methods that implement MediaRouter2Manager operations.

    @NonNull
    public List<RoutingSessionInfo> getRemoteSessions(@NonNull IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                return getRemoteSessionsLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    public void registerManager(@NonNull IMediaRouter2Manager manager,
            @NonNull String callerPackageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(callerPackageName)) {
            throw new IllegalArgumentException("callerPackageName must not be empty");
        }

        final int callerUid = Binder.getCallingUid();
        final int callerPid = Binder.getCallingPid();
        final UserHandle callerUser = Binder.getCallingUserHandle();

        // TODO (b/305919655) - Handle revoking of MEDIA_ROUTING_CONTROL at runtime.
        enforcePrivilegedRoutingPermissions(callerUid, callerPid, callerPackageName);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(
                        manager,
                        callerUid,
                        callerPid,
                        callerPackageName,
                        /* targetPackageName */ null,
                        callerUser);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_CONTENT_CONTROL,
                Manifest.permission.MEDIA_ROUTING_CONTROL
            })
    public void registerProxyRouter(
            @NonNull IMediaRouter2Manager manager,
            @NonNull String callerPackageName,
            @NonNull String targetPackageName,
            @NonNull UserHandle targetUser) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(targetUser, "targetUser must not be null");

        if (TextUtils.isEmpty(targetPackageName)) {
            throw new IllegalArgumentException("targetPackageName must not be empty");
        }

        int callerUid = Binder.getCallingUid();
        int callerPid = Binder.getCallingPid();
        final long token = Binder.clearCallingIdentity();

        try {
            // TODO (b/305919655) - Handle revoking of MEDIA_ROUTING_CONTROL at runtime.
            enforcePrivilegedRoutingPermissions(callerUid, callerPid, callerPackageName);
            enforceCrossUserPermissions(callerUid, callerPid, targetUser);
            if (!verifyPackageExistsForUser(targetPackageName, targetUser)) {
                throw new IllegalArgumentException(
                        "targetPackageName does not exist: " + targetPackageName);
            }

            synchronized (mLock) {
                registerManagerLocked(
                        manager,
                        callerUid,
                        callerPid,
                        callerPackageName,
                        targetPackageName,
                        targetUser);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterManager(@NonNull IMediaRouter2Manager manager) {
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

    public void startScan(@NonNull IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                startScanLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void stopScan(@NonNull IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                stopScanLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRouteVolumeWithManager(@NonNull IMediaRouter2Manager manager, int requestId,
            @NonNull MediaRoute2Info route, int volume) {
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

    public void requestCreateSessionWithManager(
            @NonNull IMediaRouter2Manager manager,
            int requestId,
            @NonNull RoutingSessionInfo oldSession,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(oldSession, "oldSession must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(transferInitiatorUserHandle);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestCreateSessionWithManagerLocked(
                        requestId,
                        manager,
                        oldSession,
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRouteWithManager(@NonNull IMediaRouter2Manager manager, int requestId,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
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

    public void deselectRouteWithManager(@NonNull IMediaRouter2Manager manager, int requestId,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
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

    public void transferToRouteWithManager(
            @NonNull IMediaRouter2Manager manager,
            int requestId,
            @NonNull String uniqueSessionId,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        if (TextUtils.isEmpty(uniqueSessionId)) {
            throw new IllegalArgumentException("uniqueSessionId must not be empty");
        }
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(transferInitiatorUserHandle);
        Objects.requireNonNull(transferInitiatorPackageName);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteWithManagerLocked(
                        requestId,
                        manager,
                        uniqueSessionId,
                        route,
                        RoutingSessionInfo.TRANSFER_REASON_SYSTEM_REQUEST,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setSessionVolumeWithManager(@NonNull IMediaRouter2Manager manager, int requestId,
            @NonNull String uniqueSessionId, int volume) {
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

    public void releaseSessionWithManager(@NonNull IMediaRouter2Manager manager, int requestId,
            @NonNull String uniqueSessionId) {
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

    // End of methods that implement MediaRouter2Manager operations.

    // Start of methods that implements operations for both MediaRouter2 and MediaRouter2Manager.

    @Nullable
    public RoutingSessionInfo getSystemSessionInfo(
            @Nullable String packageName, boolean setDeviceRouteSelected) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
        final boolean hasSystemRoutingPermissions =
                checkCallerHasSystemRoutingPermissions(pid, uid);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                UserRecord userRecord = getOrCreateUserRecordLocked(userId);
                List<RoutingSessionInfo> sessionInfos;
                if (hasSystemRoutingPermissions) {
                    if (setDeviceRouteSelected) {
                        // Return a fake system session that shows the device route as selected and
                        // available bluetooth routes as transferable.
                        return userRecord.mHandler.mSystemProvider
                                .generateDeviceRouteSelectedSessionInfo(packageName);
                    } else {
                        sessionInfos = userRecord.mHandler.mSystemProvider.getSessionInfos();
                        if (sessionInfos != null && !sessionInfos.isEmpty()) {
                            // Return a copy of the current system session with no modification,
                            // except setting the client package name.
                            return new RoutingSessionInfo.Builder(sessionInfos.get(0))
                                    .setClientPackageName(packageName)
                                    .build();
                        } else {
                            Slog.w(TAG, "System provider does not have any session info.");
                        }
                    }
                } else {
                    return new RoutingSessionInfo.Builder(
                                    userRecord.mHandler.mSystemProvider.getDefaultSessionInfo())
                            .setClientPackageName(packageName)
                            .build();
                }
            }
            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean checkCallerHasSystemRoutingPermissions(int pid, int uid) {
        return checkCallerHasModifyAudioRoutingPermission(pid, uid)
                || checkCallerHasBluetoothPermissions(pid, uid);
    }

    private boolean checkCallerHasModifyAudioRoutingPermission(int pid, int uid) {
        return mContext.checkPermission(Manifest.permission.MODIFY_AUDIO_ROUTING, pid, uid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean checkCallerHasBluetoothPermissions(int pid, int uid) {
        boolean hasBluetoothRoutingPermission = true;
        for (String permission : BLUETOOTH_PERMISSIONS_FOR_SYSTEM_ROUTING) {
            hasBluetoothRoutingPermission &=
                    mContext.checkPermission(permission, pid, uid)
                            == PackageManager.PERMISSION_GRANTED;
        }
        return hasBluetoothRoutingPermission;
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MEDIA_ROUTING_CONTROL,
                Manifest.permission.MEDIA_CONTENT_CONTROL
            })
    private void enforcePrivilegedRoutingPermissions(
            int callerUid, int callerPid, @Nullable String callerPackageName) {
        if (mContext.checkPermission(
                        Manifest.permission.MEDIA_CONTENT_CONTROL, callerPid, callerUid)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        if (!Flags.enablePrivilegedRoutingForMediaRoutingControl()) {
            throw new SecurityException("Must hold MEDIA_CONTENT_CONTROL");
        }

        if (PermissionChecker.checkPermissionForDataDelivery(
                        mContext,
                        Manifest.permission.MEDIA_ROUTING_CONTROL,
                        callerPid,
                        callerUid,
                        callerPackageName,
                        /* attributionTag */ null,
                        /* message */ "Checking permissions for registering manager in"
                                          + " MediaRouter2ServiceImpl.")
                != PermissionChecker.PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Must hold MEDIA_CONTENT_CONTROL or MEDIA_ROUTING_CONTROL permissions.");
        }
    }

    @RequiresPermission(value = Manifest.permission.INTERACT_ACROSS_USERS)
    private boolean verifyPackageExistsForUser(
            @NonNull String clientPackageName, @NonNull UserHandle user) {
        try {
            PackageManager pm = mContext.getPackageManager();
            pm.getPackageInfoAsUser(
                    clientPackageName, PackageManager.PackageInfoFlags.of(0), user.getIdentifier());
            return true;
        } catch (PackageManager.NameNotFoundException ex) {
            return false;
        }
    }

    /**
     * Enforces the caller has {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL} if the
     * caller's user is different from the target user.
     */
    private void enforceCrossUserPermissions(
            int callerUid, int callerPid, @NonNull UserHandle targetUser) {
        int callerUserId = UserHandle.getUserId(callerUid);

        if (targetUser.getIdentifier() != callerUserId) {
            mContext.enforcePermission(
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    callerPid,
                    callerUid,
                    "Must hold INTERACT_ACROSS_USERS_FULL to control an app in a different"
                            + " userId.");
        }
    }

    // End of methods that implements operations for both MediaRouter2 and MediaRouter2Manager.

    public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
        pw.println(prefix + "MediaRouter2ServiceImpl");

        String indent = prefix + "  ";

        synchronized (mLock) {
            pw.println(indent + "mNextRouterOrManagerId=" + mNextRouterOrManagerId.get());
            pw.println(indent + "mCurrentActiveUserId=" + mCurrentActiveUserId);

            pw.println(indent + "UserRecords:");
            if (mUserRecords.size() > 0) {
                for (int i = 0; i < mUserRecords.size(); i++) {
                    mUserRecords.valueAt(i).dump(pw, indent + "  ");
                }
            } else {
                pw.println(indent + "  <no user records>");
            }
        }
    }

    /* package */ void updateRunningUserAndProfiles(int newActiveUserId) {
        synchronized (mLock) {
            if (mCurrentActiveUserId != newActiveUserId) {
                Slog.i(TAG, TextUtils.formatSimple(
                        "switchUser | user: %d", newActiveUserId));

                mCurrentActiveUserId = newActiveUserId;
                // disposeUserIfNeededLocked might modify the collection, hence clone
                final var userRecords = mUserRecords.clone();
                for (int i = 0; i < userRecords.size(); i++) {
                    int userId = userRecords.keyAt(i);
                    UserRecord userRecord = userRecords.valueAt(i);
                    if (isUserActiveLocked(userId)) {
                        // userId corresponds to the active user, or one of its profiles. We
                        // ensure the associated structures are initialized.
                        userRecord.mHandler.sendMessage(
                                obtainMessage(UserHandler::start, userRecord.mHandler));
                    } else {
                        userRecord.mHandler.sendMessage(
                                obtainMessage(UserHandler::stop, userRecord.mHandler));
                        disposeUserIfNeededLocked(userRecord);
                    }
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

    /**
     * Returns {@code true} if the given {@code userId} corresponds to the active user or a profile
     * of the active user, returns {@code false} otherwise.
     */
    @GuardedBy("mLock")
    private boolean isUserActiveLocked(int userId) {
        return mUserManagerInternal.getProfileParentId(userId) == mCurrentActiveUserId;
    }

    // Start of locked methods that are used by MediaRouter2.

    @GuardedBy("mLock")
    private void registerRouter2Locked(@NonNull IMediaRouter2 router, int uid, int pid,
            @NonNull String packageName, int userId, boolean hasConfigureWifiDisplayPermission,
            boolean hasModifyAudioRoutingPermission) {
        final IBinder binder = router.asBinder();
        if (mAllRouterRecords.get(binder) != null) {
            Slog.w(TAG, "registerRouter2Locked: Same router already exists. packageName="
                    + packageName);
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
                obtainMessage(UserHandler::notifyRouterRegistered,
                        userRecord.mHandler, routerRecord));

        Slog.i(TAG, TextUtils.formatSimple(
                "registerRouter2 | package: %s, uid: %d, pid: %d, router id: %d",
                packageName, uid, pid, routerRecord.mRouterId));
    }

    @GuardedBy("mLock")
    private void unregisterRouter2Locked(@NonNull IMediaRouter2 router, boolean died) {
        RouterRecord routerRecord = mAllRouterRecords.remove(router.asBinder());
        if (routerRecord == null) {
            Slog.w(
                    TAG,
                    TextUtils.formatSimple(
                            "Ignoring unregistering unknown router: %s, died: %b", router, died));
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "unregisterRouter2 | package: %s, router id: %d, died: %b",
                        routerRecord.mPackageName, routerRecord.mRouterId, died));

        UserRecord userRecord = routerRecord.mUserRecord;
        userRecord.mRouterRecords.remove(routerRecord);
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyDiscoveryPreferenceChangedToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName, null));
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::notifyRouteListingPreferenceChangeToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName,
                        /* routeListingPreference= */ null));
        userRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        userRecord.mHandler));
        routerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since router removed from user
    }

    @GuardedBy("mLock")
    private void setDiscoveryRequestWithRouter2Locked(@NonNull RouterRecord routerRecord,
            @NonNull RouteDiscoveryPreference discoveryRequest) {
        if (routerRecord.mDiscoveryPreference.equals(discoveryRequest)) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "setDiscoveryRequestWithRouter2 | router: %s(id: %d), discovery request:"
                            + " %s",
                        routerRecord.mPackageName,
                        routerRecord.mRouterId,
                        discoveryRequest.toString()));

        routerRecord.mDiscoveryPreference = discoveryRequest;
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::notifyDiscoveryPreferenceChangedToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName,
                        routerRecord.mDiscoveryPreference));
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::updateDiscoveryPreferenceOnHandler,
                        routerRecord.mUserRecord.mHandler));
    }

    @GuardedBy("mLock")
    private void setRouteListingPreferenceLocked(
            RouterRecord routerRecord, @Nullable RouteListingPreference routeListingPreference) {
        routerRecord.mRouteListingPreference = routeListingPreference;
        String routeListingAsString =
                routeListingPreference != null
                        ? routeListingPreference.getItems().stream()
                                .map(RouteListingPreference.Item::getRouteId)
                                .collect(Collectors.joining(","))
                        : null;

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "setRouteListingPreference | router: %s(id: %d), route listing preference:"
                            + " [%s]",
                        routerRecord.mPackageName, routerRecord.mRouterId, routeListingAsString));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::notifyRouteListingPreferenceChangeToManagers,
                        routerRecord.mUserRecord.mHandler,
                        routerRecord.mPackageName,
                        routeListingPreference));
    }

    @GuardedBy("mLock")
    private void setRouteVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord != null) {
            Slog.i(
                    TAG,
                    TextUtils.formatSimple(
                            "setRouteVolumeWithRouter2 | router: %s(id: %d), volume: %d",
                            routerRecord.mPackageName, routerRecord.mRouterId, volume));

            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::setRouteVolumeOnHandler,
                            routerRecord.mUserRecord.mHandler,
                            DUMMY_REQUEST_ID, route, volume));
        }
    }

    @GuardedBy("mLock")
    private void requestCreateSessionWithRouter2Locked(
            int requestId,
            long managerRequestId,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName,
            @NonNull IMediaRouter2 router,
            @NonNull RoutingSessionInfo oldSession,
            @NonNull MediaRoute2Info route,
            @Nullable Bundle sessionHints) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "requestCreateSessionWithRouter2 | router: %s(id: %d), old session id: %s,"
                            + " new session's route id: %s, request id: %d",
                        routerRecord.mPackageName,
                        routerRecord.mRouterId,
                        oldSession.getId(),
                        route.getId(),
                        requestId));

        if (managerRequestId != MediaRoute2ProviderService.REQUEST_ID_NONE) {
            ManagerRecord manager = routerRecord.mUserRecord.mHandler.findManagerWithId(
                    toRequesterId(managerRequestId));
            if (manager == null || manager.mLastSessionCreationRequest == null) {
                Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                        + "Ignoring unknown request.");
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
            if (!TextUtils.equals(manager.mLastSessionCreationRequest.mOldSession.getId(),
                    oldSession.getId())) {
                Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                        + "Ignoring unmatched routing session.");
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
            if (!TextUtils.equals(manager.mLastSessionCreationRequest.mRoute.getId(),
                    route.getId())) {
                // When media router has no permission
                if (!routerRecord.hasSystemRoutingPermission()
                        && manager.mLastSessionCreationRequest.mRoute.isSystemRoute()
                        && route.isSystemRoute()) {
                    route = manager.mLastSessionCreationRequest.mRoute;
                } else {
                    Slog.w(TAG, "requestCreateSessionWithRouter2Locked: "
                            + "Ignoring unmatched route.");
                    routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                            routerRecord, requestId);
                    return;
                }
            }
            manager.mLastSessionCreationRequest = null;
        } else {
            if (route.isSystemRoute()
                    && !routerRecord.hasSystemRoutingPermission()
                    && !TextUtils.equals(
                            route.getId(),
                            routerRecord
                                    .mUserRecord
                                    .mHandler
                                    .mSystemProvider
                                    .getDefaultRoute()
                                    .getId())) {
                Slog.w(TAG, "MODIFY_AUDIO_ROUTING permission is required to transfer to"
                        + route);
                routerRecord.mUserRecord.mHandler.notifySessionCreationFailedToRouter(
                        routerRecord, requestId);
                return;
            }
        }

        long uniqueRequestId = toUniqueRequestId(routerRecord.mRouterId, requestId);
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::requestCreateSessionWithRouter2OnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId,
                        managerRequestId,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName,
                        routerRecord,
                        oldSession,
                        route,
                        sessionHints));
    }

    @GuardedBy("mLock")
    private void selectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "selectRouteWithRouter2 | router: %s(id: %d), route: %s",
                        routerRecord.mPackageName, routerRecord.mRouterId, route.getId()));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    @GuardedBy("mLock")
    private void deselectRouteWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "deselectRouteWithRouter2 | router: %s(id: %d), route: %s",
                        routerRecord.mPackageName, routerRecord.mRouterId, route.getId()));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId, route));
    }

    @GuardedBy("mLock")
    private void transferToRouteWithRouter2Locked(
            @NonNull IMediaRouter2 router,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String uniqueSessionId,
            @NonNull MediaRoute2Info route) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "transferToRouteWithRouter2 | router: %s(id: %d), route: %s",
                        routerRecord.mPackageName, routerRecord.mRouterId, route.getId()));

        String defaultRouteId =
                routerRecord.mUserRecord.mHandler.mSystemProvider.getDefaultRoute().getId();
        if (route.isSystemRoute()
                && !routerRecord.hasSystemRoutingPermission()
                && !TextUtils.equals(route.getId(), defaultRouteId)) {
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifySessionCreationFailedToRouter,
                            routerRecord.mUserRecord.mHandler,
                            routerRecord, toOriginalRequestId(DUMMY_REQUEST_ID)));
        } else {
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(
                            UserHandler::transferToRouteOnHandler,
                            routerRecord.mUserRecord.mHandler,
                            DUMMY_REQUEST_ID,
                            transferInitiatorUserHandle,
                            routerRecord.mPackageName,
                            routerRecord,
                            uniqueSessionId,
                            route,
                            RoutingSessionInfo.TRANSFER_REASON_APP));
        }
    }

    @GuardedBy("mLock")
    private void setSessionVolumeWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = router.asBinder();
        RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "setSessionVolumeWithRouter2 | router: %s(id: %d), session: %s, volume: %d",
                        routerRecord.mPackageName,
                        routerRecord.mRouterId,
                        uniqueSessionId,
                        volume));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, uniqueSessionId, volume));
    }

    @GuardedBy("mLock")
    private void releaseSessionWithRouter2Locked(@NonNull IMediaRouter2 router,
            @NonNull String uniqueSessionId) {
        final IBinder binder = router.asBinder();
        final RouterRecord routerRecord = mAllRouterRecords.get(binder);

        if (routerRecord == null) {
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "releaseSessionWithRouter2 | router: %s(id: %d), session: %s",
                        routerRecord.mPackageName, routerRecord.mRouterId, uniqueSessionId));

        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        DUMMY_REQUEST_ID, routerRecord, uniqueSessionId));
    }

    // End of locked methods that are used by MediaRouter2.

    // Start of locked methods that are used by MediaRouter2Manager.

    @GuardedBy("mLock")
    private List<RoutingSessionInfo> getRemoteSessionsLocked(
            @NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            Slog.w(TAG, "getRemoteSessionLocked: Ignoring unknown manager");
            return Collections.emptyList();
        }

        List<RoutingSessionInfo> sessionInfos = new ArrayList<>();
        for (MediaRoute2Provider provider : managerRecord.mUserRecord.mHandler.mRouteProviders) {
            if (!provider.mIsSystemRouteProvider) {
                sessionInfos.addAll(provider.getSessionInfos());
            }
        }
        return sessionInfos;
    }

    @RequiresPermission(Manifest.permission.MEDIA_CONTENT_CONTROL)
    @GuardedBy("mLock")
    private void registerManagerLocked(
            @NonNull IMediaRouter2Manager manager,
            int callerUid,
            int callerPid,
            @NonNull String callerPackageName,
            @Nullable String targetPackageName,
            @NonNull UserHandle targetUser) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            Slog.w(TAG, "registerManagerLocked: Same manager already exists. callerPackageName="
                    + callerPackageName);
            return;
        }

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "registerManager | callerUid: %d, callerPid: %d, callerPackage: %s,"
                                + "targetPackageName: %s, targetUserId: %d",
                        callerUid, callerPid, callerPackageName, targetPackageName, targetUser));

        UserRecord userRecord = getOrCreateUserRecordLocked(targetUser.getIdentifier());
        managerRecord =
                new ManagerRecord(
                        userRecord,
                        manager,
                        callerUid,
                        callerPid,
                        callerPackageName,
                        targetPackageName);
        try {
            binder.linkToDeath(managerRecord, 0);
        } catch (RemoteException ex) {
            throw new RuntimeException("Media router manager died prematurely.", ex);
        }

        userRecord.mManagerRecords.add(managerRecord);
        mAllManagerRecords.put(binder, managerRecord);

        // Note: Features should be sent first before the routes. If not, the
        // RouteCallback#onRoutesAdded() for system MR2 will never be called with initial routes
        // due to the lack of features.
        for (RouterRecord routerRecord : userRecord.mRouterRecords) {
            // Send route listing preferences before discovery preferences and routes to avoid an
            // inconsistent state where there are routes to show, but the manager thinks
            // the app has not expressed a preference for listing.
            userRecord.mHandler.sendMessage(
                    obtainMessage(
                            UserHandler::notifyRouteListingPreferenceChangeToManagers,
                            routerRecord.mUserRecord.mHandler,
                            routerRecord.mPackageName,
                            routerRecord.mRouteListingPreference));
            // TODO: UserRecord <-> routerRecord, why do they reference each other?
            // How about removing mUserRecord from routerRecord?
            routerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(
                            UserHandler::notifyDiscoveryPreferenceChangedToManager,
                            routerRecord.mUserRecord.mHandler,
                            routerRecord,
                            manager));
        }

        userRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::notifyInitialRoutesToManager, userRecord.mHandler, manager));
    }

    @GuardedBy("mLock")
    private void unregisterManagerLocked(@NonNull IMediaRouter2Manager manager, boolean died) {
        ManagerRecord managerRecord = mAllManagerRecords.remove(manager.asBinder());
        if (managerRecord == null) {
            Slog.w(
                    TAG,
                    TextUtils.formatSimple(
                            "Ignoring unregistering unknown manager: %s, died: %b", manager, died));
            return;
        }
        UserRecord userRecord = managerRecord.mUserRecord;

        Slog.i(
                TAG,
                TextUtils.formatSimple(
                        "unregisterManager | package: %s, user: %d, manager: %d, died: %b",
                        managerRecord.mOwnerPackageName,
                        userRecord.mUserId,
                        managerRecord.mManagerId,
                        died));

        userRecord.mManagerRecords.remove(managerRecord);
        managerRecord.dispose();
        disposeUserIfNeededLocked(userRecord); // since manager removed from user
    }

    @GuardedBy("mLock")
    private void startScanLocked(@NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "startScan | manager: %d", managerRecord.mManagerId));

        managerRecord.startScan();
    }

    @GuardedBy("mLock")
    private void stopScanLocked(@NonNull IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "stopScan | manager: %d", managerRecord.mManagerId));

        managerRecord.stopScan();
    }

    @GuardedBy("mLock")
    private void setRouteVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull MediaRoute2Info route, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setRouteVolumeWithManager | manager: %d, route: %s, volume: %d",
                managerRecord.mManagerId, route.getId(), volume));

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setRouteVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, route, volume));
    }

    @GuardedBy("mLock")
    private void requestCreateSessionWithManagerLocked(
            int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull RoutingSessionInfo oldSession,
            @NonNull MediaRoute2Info route,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "requestCreateSessionWithManager | manager: %d, route: %s",
                managerRecord.mManagerId, route.getId()));

        String packageName = oldSession.getClientPackageName();

        RouterRecord routerRecord = managerRecord.mUserRecord.findRouterRecordLocked(packageName);
        if (routerRecord == null) {
            Slog.w(TAG, "requestCreateSessionWithManagerLocked: Ignoring session creation for "
                    + "unknown router.");
            try {
                managerRecord.mManager.notifyRequestFailed(requestId, REASON_UNKNOWN_ERROR);
            } catch (RemoteException ex) {
                Slog.w(TAG, "requestCreateSessionWithManagerLocked: Failed to notify failure. "
                        + "Manager probably died.");
            }
            return;
        }

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        SessionCreationRequest lastRequest = managerRecord.mLastSessionCreationRequest;
        if (lastRequest != null) {
            Slog.i(
                    TAG,
                    TextUtils.formatSimple(
                            "requestCreateSessionWithManagerLocked: Notifying failure for pending"
                                + " session creation request - oldSession: %s, route: %s",
                            lastRequest.mOldSession, lastRequest.mRoute));
            managerRecord.mUserRecord.mHandler.notifyRequestFailedToManager(
                    managerRecord.mManager,
                    toOriginalRequestId(lastRequest.mManagerRequestId),
                    REASON_UNKNOWN_ERROR);
        }
        managerRecord.mLastSessionCreationRequest = new SessionCreationRequest(routerRecord,
                MediaRoute2ProviderService.REQUEST_ID_NONE, uniqueRequestId,
                oldSession, route);

        // Before requesting to the provider, get session hints from the media router.
        // As a return, media router will request to create a session.
        routerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::requestRouterCreateSessionOnHandler,
                        routerRecord.mUserRecord.mHandler,
                        uniqueRequestId,
                        routerRecord,
                        managerRecord,
                        oldSession,
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName));
    }

    @GuardedBy("mLock")
    private void selectRouteWithManagerLocked(int requestId, @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "selectRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::selectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    @GuardedBy("mLock")
    private void deselectRouteWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "deselectRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::deselectRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId, route));
    }

    @GuardedBy("mLock")
    private void transferToRouteWithManagerLocked(
            int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId,
            @NonNull MediaRoute2Info route,
            @RoutingSessionInfo.TransferReason int transferReason,
            @NonNull UserHandle transferInitiatorUserHandle,
            @NonNull String transferInitiatorPackageName) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "transferToRouteWithManager | manager: %d, session: %s, route: %s",
                managerRecord.mManagerId, uniqueSessionId, route.getId()));

        // Can be null if the session is system's or RCN.
        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(
                        UserHandler::transferToRouteOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName,
                        routerRecord,
                        uniqueSessionId,
                        route,
                        transferReason));
    }

    @GuardedBy("mLock")
    private void setSessionVolumeWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager,
            @NonNull String uniqueSessionId, int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "setSessionVolumeWithManager | manager: %d, session: %s, volume: %d",
                managerRecord.mManagerId, uniqueSessionId, volume));

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::setSessionVolumeOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, uniqueSessionId, volume));
    }

    @GuardedBy("mLock")
    private void releaseSessionWithManagerLocked(int requestId,
            @NonNull IMediaRouter2Manager manager, @NonNull String uniqueSessionId) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return;
        }

        Slog.i(TAG, TextUtils.formatSimple(
                "releaseSessionWithManager | manager: %d, session: %s",
                managerRecord.mManagerId, uniqueSessionId));

        RouterRecord routerRecord = managerRecord.mUserRecord.mHandler
                .findRouterWithSessionLocked(uniqueSessionId);

        long uniqueRequestId = toUniqueRequestId(managerRecord.mManagerId, requestId);
        managerRecord.mUserRecord.mHandler.sendMessage(
                obtainMessage(UserHandler::releaseSessionOnHandler,
                        managerRecord.mUserRecord.mHandler,
                        uniqueRequestId, routerRecord, uniqueSessionId));
    }

    // End of locked methods that are used by MediaRouter2Manager.

    // Start of locked methods that are used by both MediaRouter2 and MediaRouter2Manager.

    @GuardedBy("mLock")
    private UserRecord getOrCreateUserRecordLocked(int userId) {
        UserRecord userRecord = mUserRecords.get(userId);
        if (userRecord == null) {
            userRecord = new UserRecord(userId);
            mUserRecords.put(userId, userRecord);
            userRecord.init();
            if (isUserActiveLocked(userId)) {
                userRecord.mHandler.sendMessage(
                        obtainMessage(UserHandler::start, userRecord.mHandler));
            }
        }
        return userRecord;
    }

    @GuardedBy("mLock")
    private void disposeUserIfNeededLocked(@NonNull UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (!isUserActiveLocked(userRecord.mUserId)
                && userRecord.mRouterRecords.isEmpty()
                && userRecord.mManagerRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::stop, userRecord.mHandler));
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    // End of locked methods that are used by both MediaRouter2 and MediaRouter2Manager.

    private void onDeviceConfigChange(@NonNull DeviceConfig.Properties properties) {
        sPackageImportanceForScanning =
                properties.getInt(
                        /* name */ FEATURE_SCANNING_MINIMUM_PACKAGE_IMPORTANCE,
                        /* defaultValue */ IMPORTANCE_FOREGROUND_SERVICE);
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
        Set<String> mActivelyScanningPackages = Set.of();
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }

        void init() {
            mHandler.init();
        }

        // TODO: This assumes that only one router exists in a package.
        //       Do this in Android S or later.
        @GuardedBy("mLock")
        RouterRecord findRouterRecordLocked(String packageName) {
            for (RouterRecord routerRecord : mRouterRecords) {
                if (TextUtils.equals(routerRecord.mPackageName, packageName)) {
                    return routerRecord;
                }
            }
            return null;
        }

        public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            pw.println(prefix + "UserRecord");

            String indent = prefix + "  ";

            pw.println(indent + "mUserId=" + mUserId);

            pw.println(indent + "Router Records:");
            if (!mRouterRecords.isEmpty()) {
                for (RouterRecord routerRecord : mRouterRecords) {
                    routerRecord.dump(pw, indent + "  ");
                }
            } else {
                pw.println(indent + "<no router records>");
            }

            pw.println(indent + "Manager Records:");
            if (!mManagerRecords.isEmpty()) {
                for (ManagerRecord managerRecord : mManagerRecords) {
                    managerRecord.dump(pw, indent + "  ");
                }
            } else {
                pw.println(indent + "<no manager records>");
            }

            pw.println(indent + "Composite discovery preference:");
            mCompositeDiscoveryPreference.dump(pw, indent + "  ");
            pw.println(
                    indent
                            + "Packages actively scanning: "
                            + String.join(", ", mActivelyScanningPackages));

            if (!mHandler.runWithScissors(() -> mHandler.dump(pw, indent), 1000)) {
                pw.println(indent + "<could not dump handler state>");
            }
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
        public final AtomicBoolean mHasBluetoothRoutingPermission;
        public final int mRouterId;

        public RouteDiscoveryPreference mDiscoveryPreference;
        @Nullable public RouteListingPreference mRouteListingPreference;

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
            mHasBluetoothRoutingPermission =
                    new AtomicBoolean(checkCallerHasBluetoothPermissions(mPid, mUid));
            mRouterId = mNextRouterOrManagerId.getAndIncrement();
        }

        /**
         * Returns whether the corresponding router has permission to query and control system
         * routes.
         */
        public boolean hasSystemRoutingPermission() {
            return mHasModifyAudioRoutingPermission || mHasBluetoothRoutingPermission.get();
        }

        @GuardedBy("mLock")
        public void maybeUpdateSystemRoutingPermissionLocked() {
            boolean oldSystemRoutingPermissionValue = hasSystemRoutingPermission();
            mHasBluetoothRoutingPermission.set(checkCallerHasBluetoothPermissions(mPid, mUid));
            boolean newSystemRoutingPermissionValue = hasSystemRoutingPermission();
            if (oldSystemRoutingPermissionValue != newSystemRoutingPermissionValue) {
                Map<String, MediaRoute2Info> routesToReport =
                        newSystemRoutingPermissionValue
                                ? mUserRecord.mHandler.mLastNotifiedRoutesToPrivilegedRouters
                                : mUserRecord.mHandler.mLastNotifiedRoutesToNonPrivilegedRouters;
                notifyRoutesUpdated(routesToReport.values().stream().toList());

                List<RoutingSessionInfo> sessionInfos =
                        mUserRecord.mHandler.mSystemProvider.getSessionInfos();
                RoutingSessionInfo systemSessionToReport =
                        newSystemRoutingPermissionValue && !sessionInfos.isEmpty()
                                ? sessionInfos.get(0)
                                : mUserRecord.mHandler.mSystemProvider.getDefaultSessionInfo();
                notifySessionInfoChanged(systemSessionToReport);
            }
        }

        public void dispose() {
            mRouter.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            routerDied(this);
        }

        public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            pw.println(prefix + "RouterRecord");

            String indent = prefix + "  ";

            pw.println(indent + "mPackageName=" + mPackageName);
            pw.println(indent + "mSelectRouteSequenceNumbers=" + mSelectRouteSequenceNumbers);
            pw.println(indent + "mUid=" + mUid);
            pw.println(indent + "mPid=" + mPid);
            pw.println(indent + "mHasConfigureWifiDisplayPermission="
                    + mHasConfigureWifiDisplayPermission);
            pw.println(
                    indent
                            + "mHasModifyAudioRoutingPermission="
                            + mHasModifyAudioRoutingPermission);
            pw.println(
                    indent
                            + "mHasBluetoothRoutingPermission="
                            + mHasBluetoothRoutingPermission.get());
            pw.println(indent + "hasSystemRoutingPermission=" + hasSystemRoutingPermission());
            pw.println(indent + "mRouterId=" + mRouterId);

            mDiscoveryPreference.dump(pw, indent);
        }

        /**
         * Notifies the corresponding router that it was successfully registered.
         *
         * <p>The message sent to the router includes a snapshot of the initial state, including
         * known routes and the system {@link RoutingSessionInfo}.
         *
         * @param currentRoutes All currently known routes, which are filtered according to package
         *     visibility before being sent to the router.
         * @param currentSystemSessionInfo The current system {@link RoutingSessionInfo}.
         */
        public void notifyRegistered(
                List<MediaRoute2Info> currentRoutes, RoutingSessionInfo currentSystemSessionInfo) {
            try {
                mRouter.notifyRouterRegistered(
                        getVisibleRoutes(currentRoutes), currentSystemSessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router registered. Router probably died.", ex);
            }
        }

        /**
         * Sends the corresponding router an {@link
         * android.media.MediaRouter2.RouteCallback#onRoutesUpdated update} for the given {@code
         * routes}.
         *
         * <p>Only the routes that are visible to the router are sent as part of the update.
         */
        public void notifyRoutesUpdated(List<MediaRoute2Info> routes) {
            try {
                mRouter.notifyRoutesUpdated(getVisibleRoutes(routes));
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify routes updated. Router probably died.", ex);
            }
        }

        public void notifySessionCreated(int requestId, @NonNull RoutingSessionInfo sessionInfo) {
            try {
                mRouter.notifySessionCreated(
                        requestId, maybeClearTransferInitiatorIdentity(sessionInfo));
            } catch (RemoteException ex) {
                Slog.w(
                        TAG,
                        "Failed to notify router of the session creation."
                                + " Router probably died.",
                        ex);
            }
        }

        /**
         * Sends the corresponding router an update for the given session.
         *
         * <p>Note: These updates are not directly visible to the app.
         */
        public void notifySessionInfoChanged(RoutingSessionInfo sessionInfo) {
            try {
                mRouter.notifySessionInfoChanged(maybeClearTransferInitiatorIdentity(sessionInfo));
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify session info changed. Router probably died.", ex);
            }
        }

        private RoutingSessionInfo maybeClearTransferInitiatorIdentity(
                @NonNull RoutingSessionInfo sessionInfo) {
            UserHandle transferInitiatorUserHandle = sessionInfo.getTransferInitiatorUserHandle();
            String transferInitiatorPackageName = sessionInfo.getTransferInitiatorPackageName();

            if (!Objects.equals(UserHandle.of(mUserRecord.mUserId), transferInitiatorUserHandle)
                    || !Objects.equals(mPackageName, transferInitiatorPackageName)) {
                return new RoutingSessionInfo.Builder(sessionInfo)
                        .setTransferInitiator(null, null)
                        .build();
            }

            return sessionInfo;
        }

        /**
         * Returns a filtered copy of {@code routes} that contains only the routes that are {@link
         * MediaRoute2Info#isVisibleTo visible} to the router corresponding to this record.
         */
        private List<MediaRoute2Info> getVisibleRoutes(@NonNull List<MediaRoute2Info> routes) {
            List<MediaRoute2Info> filteredRoutes = new ArrayList<>();
            for (MediaRoute2Info route : routes) {
                if (route.isVisibleTo(mPackageName)) {
                    filteredRoutes.add(route);
                }
            }
            return filteredRoutes;
        }
    }

    final class ManagerRecord implements IBinder.DeathRecipient {
        @NonNull public final UserRecord mUserRecord;
        @NonNull public final IMediaRouter2Manager mManager;
        public final int mOwnerUid;
        public final int mOwnerPid;
        @NonNull public final String mOwnerPackageName;
        public final int mManagerId;
        // TODO (b/281072508): Document behaviour around nullability for mTargetPackageName.
        @Nullable public final String mTargetPackageName;
        @Nullable public SessionCreationRequest mLastSessionCreationRequest;
        public boolean mIsScanning;

        ManagerRecord(
                @NonNull UserRecord userRecord,
                @NonNull IMediaRouter2Manager manager,
                int ownerUid,
                int ownerPid,
                @NonNull String ownerPackageName,
                @Nullable String targetPackageName) {
            mUserRecord = userRecord;
            mManager = manager;
            mOwnerUid = ownerUid;
            mOwnerPid = ownerPid;
            mOwnerPackageName = ownerPackageName;
            mTargetPackageName = targetPackageName;
            mManagerId = mNextRouterOrManagerId.getAndIncrement();
        }

        public void dispose() {
            mManager.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            managerDied(this);
        }

        public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            pw.println(prefix + "ManagerRecord");

            String indent = prefix + "  ";

            pw.println(indent + "mOwnerPackageName=" + mOwnerPackageName);
            pw.println(indent + "mTargetPackageName=" + mTargetPackageName);
            pw.println(indent + "mManagerId=" + mManagerId);
            pw.println(indent + "mOwnerUid=" + mOwnerUid);
            pw.println(indent + "mOwnerPid=" + mOwnerPid);
            pw.println(indent + "mIsScanning=" + mIsScanning);

            if (mLastSessionCreationRequest != null) {
                mLastSessionCreationRequest.dump(pw, indent);
            }
        }

        public void startScan() {
            if (mIsScanning) {
                return;
            }
            mIsScanning = true;
            mUserRecord.mHandler.sendMessage(PooledLambda.obtainMessage(
                    UserHandler::updateDiscoveryPreferenceOnHandler, mUserRecord.mHandler));
        }

        public void stopScan() {
            if (!mIsScanning) {
                return;
            }
            mIsScanning = false;
            mUserRecord.mHandler.sendMessage(PooledLambda.obtainMessage(
                    UserHandler::updateDiscoveryPreferenceOnHandler, mUserRecord.mHandler));
        }

        @Override
        public String toString() {
            return "Manager " + mOwnerPackageName + " (pid " + mOwnerPid + ")";
        }
    }

    static final class UserHandler extends Handler implements
            MediaRoute2ProviderWatcher.Callback,
            MediaRoute2Provider.Callback {

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        private final SystemMediaRoute2Provider mSystemProvider;
        private final ArrayList<MediaRoute2Provider> mRouteProviders =
                new ArrayList<>();

        private final List<MediaRoute2ProviderInfo> mLastProviderInfos = new ArrayList<>();
        private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
                new CopyOnWriteArrayList<>();
        private final Map<String, RouterRecord> mSessionToRouterMap = new ArrayMap<>();

        /**
         * Latest list of routes sent to privileged {@link android.media.MediaRouter2 routers} and
         * {@link android.media.MediaRouter2Manager managers}.
         *
         * <p>Privileged routers are instances of {@link android.media.MediaRouter2 MediaRouter2}
         * that have {@code MODIFY_AUDIO_ROUTING} permission.
         *
         * <p>This list contains all routes exposed by route providers. This includes routes from
         * both system route providers and user route providers.
         *
         * <p>See {@link #getRouterRecords(boolean hasModifyAudioRoutingPermission)}.
         */
        private final Map<String, MediaRoute2Info> mLastNotifiedRoutesToPrivilegedRouters =
                new ArrayMap<>();

        /**
         * Latest list of routes sent to non-privileged {@link android.media.MediaRouter2 routers}.
         *
         * <p>Non-privileged routers are instances of {@link android.media.MediaRouter2
         * MediaRouter2} that do <i><b>not</b></i> have {@code MODIFY_AUDIO_ROUTING} permission.
         *
         * <p>This list contains all routes exposed by user route providers. It might also include
         * the current default route from {@link #mSystemProvider} to expose local route updates
         * (e.g. volume changes) to non-privileged routers.
         *
         * <p>See {@link SystemMediaRoute2Provider#mDefaultRoute}.
         */
        private final Map<String, MediaRoute2Info> mLastNotifiedRoutesToNonPrivilegedRouters =
                new ArrayMap<>();

        private boolean mRunning;

        // TODO: (In Android S+) Pull out SystemMediaRoute2Provider out of UserHandler.
        UserHandler(@NonNull MediaRouter2ServiceImpl service, @NonNull UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mSystemProvider = new SystemMediaRoute2Provider(service.mContext,
                    UserHandle.of(userRecord.mUserId));
            mRouteProviders.add(mSystemProvider);
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        void init() {
            mSystemProvider.setCallback(this);
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mSystemProvider.start();
                mWatcher.start();
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                mWatcher.stop(); // also stops all providers
                mSystemProvider.stop();
            }
        }

        @Override
        public void onAddProviderService(@NonNull MediaRoute2ProviderServiceProxy proxy) {
            proxy.setCallback(this);
            mRouteProviders.add(proxy);
            proxy.updateDiscoveryPreference(
                    mUserRecord.mActivelyScanningPackages,
                    mUserRecord.mCompositeDiscoveryPreference);
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

        @GuardedBy("mLock")
        @Nullable
        public RouterRecord findRouterWithSessionLocked(@NonNull String uniqueSessionId) {
            return mSessionToRouterMap.get(uniqueSessionId);
        }

        @Nullable
        public ManagerRecord findManagerWithId(int managerId) {
            for (ManagerRecord manager : getManagerRecords()) {
                if (manager.mManagerId == managerId) {
                    return manager;
                }
            }
            return null;
        }

        public void maybeUpdateDiscoveryPreferenceForUid(int uid) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            boolean isUidRelevant;
            synchronized (service.mLock) {
                isUidRelevant =
                        mUserRecord.mRouterRecords.stream().anyMatch(router -> router.mUid == uid)
                                | mUserRecord.mManagerRecords.stream()
                                        .anyMatch(manager -> manager.mOwnerUid == uid);
            }
            if (isUidRelevant) {
                sendMessage(PooledLambda.obtainMessage(
                        UserHandler::updateDiscoveryPreferenceOnHandler, this));
            }
        }

        public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            pw.println(prefix + "UserHandler");

            String indent = prefix + "  ";
            pw.println(indent + "mRunning=" + mRunning);

            mSystemProvider.dump(pw, prefix);
            mWatcher.dump(pw, prefix);
        }

        private void onProviderStateChangedOnHandler(@NonNull MediaRoute2Provider provider) {
            MediaRoute2ProviderInfo newInfo = provider.getProviderInfo();
            int providerInfoIndex =
                    indexOfRouteProviderInfoByUniqueId(provider.getUniqueId(), mLastProviderInfos);
            MediaRoute2ProviderInfo oldInfo =
                    providerInfoIndex == -1 ? null : mLastProviderInfos.get(providerInfoIndex);

            if (oldInfo == newInfo) {
                // Nothing to do.
                return;
            }

            Collection<MediaRoute2Info> newRoutes;
            Set<String> newRouteIds;
            if (newInfo != null) {
                // Adding or updating a provider.
                newRoutes = newInfo.getRoutes();
                newRouteIds =
                        newRoutes.stream().map(MediaRoute2Info::getId).collect(Collectors.toSet());
                if (providerInfoIndex >= 0) {
                    mLastProviderInfos.set(providerInfoIndex, newInfo);
                } else {
                    mLastProviderInfos.add(newInfo);
                }
            } else /* newInfo == null */ {
                // Removing a provider.
                mLastProviderInfos.remove(oldInfo);
                newRouteIds = Collections.emptySet();
                newRoutes = Collections.emptySet();
            }

            // Add new routes to the maps.
            ArrayList<MediaRoute2Info> addedRoutes = new ArrayList<>();
            boolean hasAddedOrModifiedRoutes = false;
            for (MediaRoute2Info newRouteInfo : newRoutes) {
                if (!newRouteInfo.isValid()) {
                    Slog.w(TAG, "onProviderStateChangedOnHandler: Ignoring invalid route : "
                            + newRouteInfo);
                    continue;
                }
                if (!provider.mIsSystemRouteProvider) {
                    mLastNotifiedRoutesToNonPrivilegedRouters.put(
                            newRouteInfo.getId(), newRouteInfo);
                }
                MediaRoute2Info oldRouteInfo =
                        mLastNotifiedRoutesToPrivilegedRouters.put(
                                newRouteInfo.getId(), newRouteInfo);
                hasAddedOrModifiedRoutes |= !newRouteInfo.equals(oldRouteInfo);
                if (oldRouteInfo == null) {
                    addedRoutes.add(newRouteInfo);
                }
            }

            // Remove stale routes from the maps.
            ArrayList<MediaRoute2Info> removedRoutes = new ArrayList<>();
            Collection<MediaRoute2Info> oldRoutes =
                    oldInfo == null ? Collections.emptyList() : oldInfo.getRoutes();
            boolean hasRemovedRoutes = false;
            for (MediaRoute2Info oldRoute : oldRoutes) {
                String oldRouteId = oldRoute.getId();
                if (!newRouteIds.contains(oldRouteId)) {
                    hasRemovedRoutes = true;
                    mLastNotifiedRoutesToPrivilegedRouters.remove(oldRouteId);
                    mLastNotifiedRoutesToNonPrivilegedRouters.remove(oldRouteId);
                    removedRoutes.add(oldRoute);
                }
            }

            if (!addedRoutes.isEmpty()) {
                // If routes were added, newInfo cannot be null.
                Slog.i(TAG,
                        toLoggingMessage(
                                /* source= */ "addProviderRoutes",
                                newInfo.getUniqueId(),
                                addedRoutes));
            }
            if (!removedRoutes.isEmpty()) {
                // If routes were removed, oldInfo cannot be null.
                Slog.i(TAG,
                        toLoggingMessage(
                                /* source= */ "removeProviderRoutes",
                                oldInfo.getUniqueId(),
                                removedRoutes));
            }

            dispatchUpdates(
                    hasAddedOrModifiedRoutes,
                    hasRemovedRoutes,
                    provider.mIsSystemRouteProvider,
                    mSystemProvider.getDefaultRoute());
        }

        private static String getPackageNameFromNullableRecord(
                @Nullable RouterRecord routerRecord) {
            return routerRecord != null ? routerRecord.mPackageName : "<null router record>";
        }

        private static String toLoggingMessage(
                String source, String providerId, ArrayList<MediaRoute2Info> routes) {
            String routesString =
                    routes.stream()
                            .map(it -> String.format("%s | %s", it.getOriginalId(), it.getName()))
                            .collect(Collectors.joining(/* delimiter= */ ", "));
            return TextUtils.formatSimple("%s | provider: %s, routes: [%s]",
                    source, providerId, routesString);
        }

        /**
         * Dispatches the latest route updates in {@link #mLastNotifiedRoutesToPrivilegedRouters}
         * and {@link #mLastNotifiedRoutesToNonPrivilegedRouters} to registered {@link
         * android.media.MediaRouter2 routers} and {@link MediaRouter2Manager managers} after a call
         * to {@link #onProviderStateChangedOnHandler(MediaRoute2Provider)}. Ignores if no changes
         * were made.
         *
         * @param hasAddedOrModifiedRoutes whether routes were added or modified.
         * @param hasRemovedRoutes whether routes were removed.
         * @param isSystemProvider whether the latest update was caused by a system provider.
         * @param defaultRoute the current default route in {@link #mSystemProvider}.
         */
        private void dispatchUpdates(
                boolean hasAddedOrModifiedRoutes,
                boolean hasRemovedRoutes,
                boolean isSystemProvider,
                MediaRoute2Info defaultRoute) {

            // Ignore if no changes.
            if (!hasAddedOrModifiedRoutes && !hasRemovedRoutes) {
                return;
            }
            List<RouterRecord> routerRecordsWithSystemRoutingPermission =
                    getRouterRecords(/* hasSystemRoutingPermission= */ true);
            List<RouterRecord> routerRecordsWithoutSystemRoutingPermission =
                    getRouterRecords(/* hasSystemRoutingPermission= */ false);
            List<IMediaRouter2Manager> managers = getManagers();

            // Managers receive all provider updates with all routes.
            notifyRoutesUpdatedToManagers(
                    managers, new ArrayList<>(mLastNotifiedRoutesToPrivilegedRouters.values()));

            // Routers with system routing access (either via {@link MODIFY_AUDIO_ROUTING} or
            // {@link BLUETOOTH_CONNECT} + {@link BLUETOOTH_SCAN}) receive all provider updates
            // with all routes.
            notifyRoutesUpdatedToRouterRecords(
                    routerRecordsWithSystemRoutingPermission,
                    new ArrayList<>(mLastNotifiedRoutesToPrivilegedRouters.values()));

            if (!isSystemProvider) {
                // Regular routers receive updates from all non-system providers with all non-system
                // routes.
                notifyRoutesUpdatedToRouterRecords(
                        routerRecordsWithoutSystemRoutingPermission,
                        new ArrayList<>(mLastNotifiedRoutesToNonPrivilegedRouters.values()));
            } else if (hasAddedOrModifiedRoutes) {
                // On system provider updates, routers without system routing access
                // receive the updated default route. This is the only system route they should
                // receive.
                mLastNotifiedRoutesToNonPrivilegedRouters.put(defaultRoute.getId(), defaultRoute);
                notifyRoutesUpdatedToRouterRecords(
                        routerRecordsWithoutSystemRoutingPermission,
                        new ArrayList<>(mLastNotifiedRoutesToNonPrivilegedRouters.values()));
            }
        }

        /**
         * Returns the index of the first element in {@code lastProviderInfos} that matches the
         * specified unique id.
         *
         * @param uniqueId unique id of {@link MediaRoute2ProviderInfo} to be found.
         * @param lastProviderInfos list of {@link MediaRoute2ProviderInfo}.
         * @return index of found element, or -1 if not found.
         */
        private static int indexOfRouteProviderInfoByUniqueId(
                @NonNull String uniqueId,
                @NonNull List<MediaRoute2ProviderInfo> lastProviderInfos) {
            for (int i = 0; i < lastProviderInfos.size(); i++) {
                MediaRoute2ProviderInfo providerInfo = lastProviderInfos.get(i);
                if (TextUtils.equals(providerInfo.getUniqueId(), uniqueId)) {
                    return i;
                }
            }
            return -1;
        }

        private void requestRouterCreateSessionOnHandler(
                long uniqueRequestId,
                @NonNull RouterRecord routerRecord,
                @NonNull ManagerRecord managerRecord,
                @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName) {
            try {
                if (route.isSystemRoute() && !routerRecord.hasSystemRoutingPermission()) {
                    // The router lacks permission to modify system routing, so we hide system
                    // route info from them.
                    route = mSystemProvider.getDefaultRoute();
                }
                routerRecord.mRouter.requestCreateSessionByManager(
                        uniqueRequestId,
                        oldSession,
                        route,
                        transferInitiatorUserHandle,
                        transferInitiatorPackageName);
            } catch (RemoteException ex) {
                Slog.w(TAG, "getSessionHintsForCreatingSessionOnHandler: "
                        + "Failed to request. Router probably died.", ex);
                notifyRequestFailedToManager(managerRecord.mManager,
                        toOriginalRequestId(uniqueRequestId), REASON_UNKNOWN_ERROR);
            }
        }

        private void requestCreateSessionWithRouter2OnHandler(
                long uniqueRequestId,
                long managerRequestId,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName,
                @NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route,
                @Nullable Bundle sessionHints) {

            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "requestCreateSessionWithRouter2OnHandler: Ignoring session "
                        + "creation request since no provider found for given route=" + route);
                notifySessionCreationFailedToRouter(routerRecord,
                        toOriginalRequestId(uniqueRequestId));
                return;
            }

            SessionCreationRequest request =
                    new SessionCreationRequest(routerRecord, uniqueRequestId,
                            managerRequestId, oldSession, route);
            mSessionCreationRequests.add(request);

            int transferReason = RoutingSessionInfo.TRANSFER_REASON_APP;
            if (managerRequestId != MediaRoute2ProviderService.REQUEST_ID_NONE) {
                transferReason = RoutingSessionInfo.TRANSFER_REASON_SYSTEM_REQUEST;
            }

            provider.requestCreateSession(
                    uniqueRequestId,
                    routerRecord.mPackageName,
                    route.getOriginalId(),
                    sessionHints,
                    transferReason,
                    transferInitiatorUserHandle,
                    transferInitiatorPackageName);
        }

        // routerRecord can be null if the session is system's or RCN.
        private void selectRouteOnHandler(long uniqueRequestId, @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "selecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }
            provider.selectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's or RCN.
        private void deselectRouteOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId, @NonNull MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "deselecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }

            provider.deselectRoute(uniqueRequestId, getOriginalId(uniqueSessionId),
                    route.getOriginalId());
        }

        // routerRecord can be null if the session is system's or RCN.
        private void transferToRouteOnHandler(
                long uniqueRequestId,
                @NonNull UserHandle transferInitiatorUserHandle,
                @NonNull String transferInitiatorPackageName,
                @Nullable RouterRecord routerRecord,
                @NonNull String uniqueSessionId,
                @NonNull MediaRoute2Info route,
                @RoutingSessionInfo.TransferReason int transferReason) {
            if (!checkArgumentsForSessionControl(routerRecord, uniqueSessionId, route,
                    "transferring to")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                return;
            }
            provider.transferToRoute(
                    uniqueRequestId,
                    transferInitiatorUserHandle,
                    transferInitiatorPackageName,
                    getOriginalId(uniqueSessionId),
                    route.getOriginalId(),
                    transferReason);
        }

        // routerRecord is null if and only if the session is created without the request, which
        // includes the system's session and RCN cases.
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

            RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(
                        TAG,
                        "Ignoring "
                                + description
                                + " route from non-matching router."
                                + " routerRecordPackageName="
                                + getPackageNameFromNullableRecord(routerRecord)
                                + " matchingRecordPackageName="
                                + getPackageNameFromNullableRecord(matchingRecord)
                                + " route="
                                + route);
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
                Slog.w(TAG, "setRouteVolumeOnHandler: Couldn't find provider for route=" + route);
                return;
            }
            provider.setRouteVolume(uniqueRequestId, route.getOriginalId(), volume);
        }

        private void setSessionVolumeOnHandler(long uniqueRequestId,
                @NonNull String uniqueSessionId, int volume) {
            final MediaRoute2Provider provider = findProvider(getProviderId(uniqueSessionId));
            if (provider == null) {
                Slog.w(TAG, "setSessionVolumeOnHandler: Couldn't find provider for session id="
                        + uniqueSessionId);
                return;
            }
            provider.setSessionVolume(uniqueRequestId, getOriginalId(uniqueSessionId), volume);
        }

        private void releaseSessionOnHandler(long uniqueRequestId,
                @Nullable RouterRecord routerRecord, @NonNull String uniqueSessionId) {
            final RouterRecord matchingRecord = mSessionToRouterMap.get(uniqueSessionId);
            if (matchingRecord != routerRecord) {
                Slog.w(
                        TAG,
                        "Ignoring releasing session from non-matching router."
                                + " routerRecordPackageName="
                                + getPackageNameFromNullableRecord(routerRecord)
                                + " matchingRecordPackageName="
                                + getPackageNameFromNullableRecord(matchingRecord)
                                + " uniqueSessionId="
                                + uniqueSessionId);
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
            SessionCreationRequest matchingRequest = null;

            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mUniqueRequestId == uniqueRequestId
                        && TextUtils.equals(
                        request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            long managerRequestId = (matchingRequest == null)
                    ? MediaRoute2ProviderService.REQUEST_ID_NONE
                    : matchingRequest.mManagerRequestId;
            notifySessionCreatedToManagers(managerRequestId, sessionInfo);

            if (matchingRequest == null) {
                Slog.w(TAG, "Ignoring session creation result for unknown request. "
                        + "uniqueRequestId=" + uniqueRequestId + ", sessionInfo=" + sessionInfo);
                return;
            }

            mSessionCreationRequests.remove(matchingRequest);
            // Not to show old session
            MediaRoute2Provider oldProvider =
                    findProvider(matchingRequest.mOldSession.getProviderId());
            if (oldProvider != null) {
                oldProvider.prepareReleaseSession(matchingRequest.mOldSession.getId());
            } else {
                Slog.w(TAG, "onSessionCreatedOnHandler: Can't find provider for an old session. "
                        + "session=" + matchingRequest.mOldSession);
            }

            mSessionToRouterMap.put(sessionInfo.getId(), matchingRequest.mRouterRecord);
            if (sessionInfo.isSystemSession()
                    && !matchingRequest.mRouterRecord.hasSystemRoutingPermission()) {
                // The router lacks permission to modify system routing, so we hide system routing
                // session info from them.
                sessionInfo = mSystemProvider.getDefaultSessionInfo();
            }
            matchingRequest.mRouterRecord.notifySessionCreated(
                    toOriginalRequestId(uniqueRequestId), sessionInfo);
        }

        private void onSessionInfoChangedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionUpdatedToManagers(managers, sessionInfo);

            // For system provider, notify all routers.
            if (provider == mSystemProvider) {
                if (mServiceRef.get() == null) {
                    return;
                }
                notifySessionInfoChangedToRouters(getRouterRecords(true), sessionInfo);
                notifySessionInfoChangedToRouters(
                        getRouterRecords(false), mSystemProvider.getDefaultSessionInfo());
                return;
            }

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "onSessionInfoChangedOnHandler: No matching router found for session="
                        + sessionInfo);
                return;
            }
            notifySessionInfoChangedToRouters(Arrays.asList(routerRecord), sessionInfo);
        }

        private void onSessionReleasedOnHandler(@NonNull MediaRoute2Provider provider,
                @NonNull RoutingSessionInfo sessionInfo) {
            List<IMediaRouter2Manager> managers = getManagers();
            notifySessionReleasedToManagers(managers, sessionInfo);

            RouterRecord routerRecord = mSessionToRouterMap.get(sessionInfo.getId());
            if (routerRecord == null) {
                Slog.w(TAG, "onSessionReleasedOnHandler: No matching router found for session="
                        + sessionInfo);
                return;
            }
            notifySessionReleasedToRouter(routerRecord, sessionInfo);
        }

        private void onRequestFailedOnHandler(@NonNull MediaRoute2Provider provider,
                long uniqueRequestId, int reason) {
            if (handleSessionCreationRequestFailed(provider, uniqueRequestId, reason)) {
                Slog.w(
                        TAG,
                        TextUtils.formatSimple(
                                "onRequestFailedOnHandler | Finished handling session creation"
                                    + " request failed for provider: %s, uniqueRequestId: %d,"
                                    + " reason: %d",
                                provider.getUniqueId(), uniqueRequestId, reason));
                return;
            }

            final int requesterId = toRequesterId(uniqueRequestId);
            ManagerRecord manager = findManagerWithId(requesterId);
            if (manager != null) {
                notifyRequestFailedToManager(
                        manager.mManager, toOriginalRequestId(uniqueRequestId), reason);
                return;
            }

            // Currently, only the manager can get notified of failures.
            // TODO: Notify router too when the related callback is introduced.
        }

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
                Slog.w(
                        TAG,
                        TextUtils.formatSimple(
                                "handleSessionCreationRequestFailed | No matching request found for"
                                    + " provider: %s, uniqueRequestId: %d, reason: %d",
                                provider.getUniqueId(), uniqueRequestId, reason));
                return false;
            }

            mSessionCreationRequests.remove(matchingRequest);

            // Notify the requester about the failure.
            // The call should be made by either MediaRouter2 or MediaRouter2Manager.
            if (matchingRequest.mManagerRequestId == MediaRouter2Manager.REQUEST_ID_NONE) {
                notifySessionCreationFailedToRouter(
                        matchingRequest.mRouterRecord, toOriginalRequestId(uniqueRequestId));
            } else {
                final int requesterId = toRequesterId(matchingRequest.mManagerRequestId);
                ManagerRecord manager = findManagerWithId(requesterId);
                if (manager != null) {
                    notifyRequestFailedToManager(manager.mManager,
                            toOriginalRequestId(matchingRequest.mManagerRequestId), reason);
                }
            }
            return true;
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

        private void notifySessionReleasedToRouter(@NonNull RouterRecord routerRecord,
                @NonNull RoutingSessionInfo sessionInfo) {
            try {
                routerRecord.mRouter.notifySessionReleased(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify router of the session release."
                        + " Router probably died.", ex);
            }
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

        private List<RouterRecord> getRouterRecords() {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return Collections.emptyList();
            }
            synchronized (service.mLock) {
                return new ArrayList<>(mUserRecord.mRouterRecords);
            }
        }

        private List<RouterRecord> getRouterRecords(boolean hasSystemRoutingPermission) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            List<RouterRecord> routerRecords = new ArrayList<>();
            if (service == null) {
                return routerRecords;
            }
            synchronized (service.mLock) {
                for (RouterRecord routerRecord : mUserRecord.mRouterRecords) {
                    if (hasSystemRoutingPermission
                            == routerRecord.hasSystemRoutingPermission()) {
                        routerRecords.add(routerRecord);
                    }
                }
                return routerRecords;
            }
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

        private void notifyRouterRegistered(@NonNull RouterRecord routerRecord) {
            List<MediaRoute2Info> currentRoutes = new ArrayList<>();

            MediaRoute2ProviderInfo systemProviderInfo = null;
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                // TODO: Create MediaRoute2ProviderInfo#isSystemProvider()
                if (TextUtils.equals(providerInfo.getUniqueId(), mSystemProvider.getUniqueId())) {
                    // Adding routes from system provider will be handled below, so skip it here.
                    systemProviderInfo = providerInfo;
                    continue;
                }
                currentRoutes.addAll(providerInfo.getRoutes());
            }

            RoutingSessionInfo currentSystemSessionInfo;
            if (routerRecord.hasSystemRoutingPermission()) {
                if (systemProviderInfo != null) {
                    currentRoutes.addAll(systemProviderInfo.getRoutes());
                } else {
                    // This shouldn't happen.
                    Slog.wtf(TAG, "System route provider not found.");
                }
                currentSystemSessionInfo = mSystemProvider.getSessionInfos().get(0);
            } else {
                currentRoutes.add(mSystemProvider.getDefaultRoute());
                currentSystemSessionInfo = mSystemProvider.getDefaultSessionInfo();
            }

            if (currentRoutes.size() == 0) {
                return;
            }

            routerRecord.notifyRegistered(currentRoutes, currentSystemSessionInfo);
        }

        private static void notifyRoutesUpdatedToRouterRecords(
                @NonNull List<RouterRecord> routerRecords,
                @NonNull List<MediaRoute2Info> routes) {
            for (RouterRecord routerRecord : routerRecords) {
                routerRecord.notifyRoutesUpdated(routes);
            }
        }

        private void notifySessionInfoChangedToRouters(
                @NonNull List<RouterRecord> routerRecords,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (RouterRecord routerRecord : routerRecords) {
                routerRecord.notifySessionInfoChanged(sessionInfo);
            }
        }

        /**
         * Notifies {@code manager} with all known routes. This only happens once after {@code
         * manager} is registered through {@link #registerManager(IMediaRouter2Manager, String)
         * registerManager()}.
         *
         * @param manager {@link IMediaRouter2Manager} to be notified.
         */
        private void notifyInitialRoutesToManager(@NonNull IMediaRouter2Manager manager) {
            if (mLastNotifiedRoutesToPrivilegedRouters.isEmpty()) {
                return;
            }
            try {
                manager.notifyRoutesUpdated(
                        new ArrayList<>(mLastNotifiedRoutesToPrivilegedRouters.values()));
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Manager probably died.", ex);
            }
        }

        private void notifyRoutesUpdatedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesUpdated(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionCreatedToManagers(long managerRequestId,
                @NonNull RoutingSessionInfo session) {
            int requesterId = toRequesterId(managerRequestId);
            int originalRequestId = toOriginalRequestId(managerRequestId);

            for (ManagerRecord manager : getManagerRecords()) {
                try {
                    manager.mManager.notifySessionCreated(
                            ((manager.mManagerId == requesterId) ? originalRequestId :
                                    MediaRouter2Manager.REQUEST_ID_NONE), session);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionCreatedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionUpdatedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionUpdated(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionUpdatedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifySessionReleasedToManagers(
                @NonNull List<IMediaRouter2Manager> managers,
                @NonNull RoutingSessionInfo sessionInfo) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifySessionReleased(sessionInfo);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "notifySessionReleasedToManagers: "
                            + "Failed to notify. Manager probably died.", ex);
                }
            }
        }

        private void notifyDiscoveryPreferenceChangedToManager(@NonNull RouterRecord routerRecord,
                @NonNull IMediaRouter2Manager manager) {
            try {
                manager.notifyDiscoveryPreferenceChanged(routerRecord.mPackageName,
                        routerRecord.mDiscoveryPreference);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify preferred features changed."
                        + " Manager probably died.", ex);
            }
        }

        private void notifyDiscoveryPreferenceChangedToManagers(@NonNull String routerPackageName,
                @Nullable RouteDiscoveryPreference discoveryPreference) {
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
                    manager.notifyDiscoveryPreferenceChanged(routerPackageName,
                            discoveryPreference);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify preferred features changed."
                            + " Manager probably died.", ex);
                }
            }
        }

        private void notifyRouteListingPreferenceChangeToManagers(
                String routerPackageName, @Nullable RouteListingPreference routeListingPreference) {
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
                    manager.notifyRouteListingPreferenceChange(
                            routerPackageName, routeListingPreference);
                } catch (RemoteException ex) {
                    Slog.w(
                            TAG,
                            "Failed to notify preferred features changed."
                                    + " Manager probably died.",
                            ex);
                }
            }
            // TODO(b/238178508): In order to support privileged media router instances, we also
            //    need to update routers other than the one making the update.
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
            List<RouterRecord> activeRouterRecords;
            List<RouterRecord> allRouterRecords = getRouterRecords();

            boolean areManagersScanning = areManagersScanning(service, getManagerRecords());

            if (areManagersScanning) {
                activeRouterRecords = allRouterRecords;
            } else {
                activeRouterRecords = getIndividuallyActiveRouters(service, allRouterRecords);
            }

            updateManagerScanningForProviders(areManagersScanning);

            Set<String> activelyScanningPackages = new HashSet<>();
            RouteDiscoveryPreference newPreference =
                    buildCompositeDiscoveryPreference(
                            activeRouterRecords, areManagersScanning, activelyScanningPackages);

            if (updateScanningOnUserRecord(service, activelyScanningPackages, newPreference)) {
                updateDiscoveryPreferenceForProviders(activelyScanningPackages);
            }
        }

        private void updateDiscoveryPreferenceForProviders(Set<String> activelyScanningPackages) {
            for (MediaRoute2Provider provider : mRouteProviders) {
                provider.updateDiscoveryPreference(
                        activelyScanningPackages, mUserRecord.mCompositeDiscoveryPreference);
            }
        }

        private boolean updateScanningOnUserRecord(
                MediaRouter2ServiceImpl service,
                Set<String> activelyScanningPackages,
                RouteDiscoveryPreference newPreference) {
            synchronized (service.mLock) {
                if (newPreference.equals(mUserRecord.mCompositeDiscoveryPreference)
                        && activelyScanningPackages.equals(mUserRecord.mActivelyScanningPackages)) {
                    return false;
                }
                mUserRecord.mCompositeDiscoveryPreference = newPreference;
                mUserRecord.mActivelyScanningPackages = activelyScanningPackages;
            }
            return true;
        }

        /**
         * Returns a composite {@link RouteDiscoveryPreference} that aggregates every router
         * record's individual discovery preference.
         *
         * <p>The {@link RouteDiscoveryPreference#shouldPerformActiveScan() active scan value} of
         * the composite discovery preference is true if one of the router records is actively
         * scanning or if {@code shouldForceActiveScan} is true.
         *
         * <p>The composite RouteDiscoveryPreference is used to query route providers once to obtain
         * all the routes of interest, which can be subsequently filtered for the individual
         * discovery preferences.
         */
        @NonNull
        private static RouteDiscoveryPreference buildCompositeDiscoveryPreference(
                List<RouterRecord> activeRouterRecords,
                boolean shouldForceActiveScan,
                Set<String> activelyScanningPackages) {
            Set<String> preferredFeatures = new HashSet<>();
            boolean activeScan = false;
            for (RouterRecord activeRouterRecord : activeRouterRecords) {
                RouteDiscoveryPreference preference = activeRouterRecord.mDiscoveryPreference;
                preferredFeatures.addAll(preference.getPreferredFeatures());
                if (preference.shouldPerformActiveScan()) {
                    activeScan = true;
                    activelyScanningPackages.add(activeRouterRecord.mPackageName);
                }
            }
            return new RouteDiscoveryPreference.Builder(
                            List.copyOf(preferredFeatures), activeScan || shouldForceActiveScan)
                    .build();
        }

        private void updateManagerScanningForProviders(boolean isManagerScanning) {
            for (MediaRoute2Provider provider : mRouteProviders) {
                if (provider instanceof MediaRoute2ProviderServiceProxy) {
                    ((MediaRoute2ProviderServiceProxy) provider)
                            .setManagerScanning(isManagerScanning);
                }
            }
        }

        @NonNull
        private static List<RouterRecord> getIndividuallyActiveRouters(
                MediaRouter2ServiceImpl service, List<RouterRecord> allRouterRecords) {
            if (!Flags.disableScreenOffBroadcastReceiver()
                    && !service.mPowerManager.isInteractive()) {
                return Collections.emptyList();
            }

            return allRouterRecords.stream()
                    .filter(
                            record ->
                                    service.mActivityManager.getPackageImportance(
                                                    record.mPackageName)
                                            <= sPackageImportanceForScanning)
                    .collect(Collectors.toList());
        }

        private static boolean areManagersScanning(
                MediaRouter2ServiceImpl service, List<ManagerRecord> managerRecords) {
            if (!Flags.disableScreenOffBroadcastReceiver()
                    && !service.mPowerManager.isInteractive()) {
                return false;
            }

            return managerRecords.stream()
                    .anyMatch(
                            manager ->
                                    manager.mIsScanning
                                            && service.mActivityManager.getPackageImportance(
                                                            manager.mOwnerPackageName)
                                                    <= sPackageImportanceForScanning);
        }

        private MediaRoute2Provider findProvider(@Nullable String providerId) {
            for (MediaRoute2Provider provider : mRouteProviders) {
                if (TextUtils.equals(provider.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }
    }

    static final class SessionCreationRequest {
        public final RouterRecord mRouterRecord;
        public final long mUniqueRequestId;
        public final long mManagerRequestId;
        public final RoutingSessionInfo mOldSession;
        public final MediaRoute2Info mRoute;

        SessionCreationRequest(@NonNull RouterRecord routerRecord, long uniqueRequestId,
                long managerRequestId, @NonNull RoutingSessionInfo oldSession,
                @NonNull MediaRoute2Info route) {
            mRouterRecord = routerRecord;
            mUniqueRequestId = uniqueRequestId;
            mManagerRequestId = managerRequestId;
            mOldSession = oldSession;
            mRoute = route;
        }

        public void dump(@NonNull PrintWriter pw, @NonNull String prefix) {
            pw.println(prefix + "SessionCreationRequest");

            String indent = prefix + "  ";

            pw.println(indent + "mUniqueRequestId=" + mUniqueRequestId);
            pw.println(indent + "mManagerRequestId=" + mManagerRequestId);
            mOldSession.dump(pw, indent);
            mRoute.dump(pw, prefix);
        }
    }
}
