/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.tv.interactive;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.interactive.ITvIAppClient;
import android.media.tv.interactive.ITvIAppManager;
import android.media.tv.interactive.ITvIAppManagerCallback;
import android.media.tv.interactive.ITvIAppService;
import android.media.tv.interactive.ITvIAppServiceCallback;
import android.media.tv.interactive.ITvIAppSession;
import android.media.tv.interactive.ITvIAppSessionCallback;
import android.media.tv.interactive.TvIAppInfo;
import android.media.tv.interactive.TvIAppService;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * This class provides a system service that manages interactive TV applications.
 */
public class TvIAppManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvIAppManagerService";
    // A global lock.
    private final Object mLock = new Object();
    private final Context mContext;
    // ID of the current user.
    @GuardedBy("mLock")
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    // IDs of the running profiles. Their parent user ID should be mCurrentUserId.
    @GuardedBy("mLock")
    private final Set<Integer> mRunningProfiles = new HashSet<>();
    // A map from user id to UserState.
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    private final UserManager mUserManager;

    /**
     * Initializes the system service.
     * <p>
     * Subclasses must define a single argument constructor that accepts the context
     * and passes it to super.
     * </p>
     *
     * @param context The system server context.
     */
    public TvIAppManagerService(Context context) {
        super(context);
        mContext = context;
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
    }

    @GuardedBy("mLock")
    private void buildTvIAppServiceListLocked(int userId, String[] updatedPackages) {
        UserState userState = getOrCreateUserStateLocked(userId);
        userState.mPackageSet.clear();

        if (DEBUG) {
            Slogf.d(TAG, "buildTvIAppServiceListLocked");
        }
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(TvIAppService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                userId);
        List<TvIAppInfo> iAppList = new ArrayList<>();

        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            // TODO: add BIND_TV_IAPP permission and check it here

            ComponentName component = new ComponentName(si.packageName, si.name);
            try {
                TvIAppInfo info = new TvIAppInfo.Builder(mContext, component).build();
                iAppList.add(info);
            } catch (Exception e) {
                Slogf.e(TAG, "failed to load TV IApp service " + si.name, e);
                continue;
            }
            userState.mPackageSet.add(si.packageName);
        }

        // sort the iApp list by iApp service id
        Collections.sort(iAppList, Comparator.comparing(TvIAppInfo::getId));
        Map<String, TvIAppState> iAppMap = new HashMap<>();
        ArrayMap<String, Integer> tiasAppCount = new ArrayMap<>(iAppMap.size());
        for (TvIAppInfo info : iAppList) {
            String iAppServiceId = info.getId();
            if (DEBUG) {
                Slogf.d(TAG, "add " + iAppServiceId);
            }
            // Running count of IApp for each IApp service
            Integer count = tiasAppCount.get(iAppServiceId);
            count = count == null ? 1 : count + 1;
            tiasAppCount.put(iAppServiceId, count);
            TvIAppState iAppState = userState.mIAppMap.get(iAppServiceId);
            if (iAppState == null) {
                iAppState = new TvIAppState();
            }
            iAppState.mInfo = info;
            iAppState.mUid = getIAppUid(info);
            iAppMap.put(iAppServiceId, iAppState);
            iAppState.mIAppNumber = count;
        }

        for (String iAppServiceId : iAppMap.keySet()) {
            if (!userState.mIAppMap.containsKey(iAppServiceId)) {
                notifyIAppServiceAddedLocked(userState, iAppServiceId);
            } else if (updatedPackages != null) {
                // Notify the package updates
                ComponentName component = iAppMap.get(iAppServiceId).mInfo.getComponent();
                for (String updatedPackage : updatedPackages) {
                    if (component.getPackageName().equals(updatedPackage)) {
                        updateServiceConnectionLocked(component, userId);
                        notifyIAppServiceUpdatedLocked(userState, iAppServiceId);
                        break;
                    }
                }
            }
        }

        for (String iAppServiceId : userState.mIAppMap.keySet()) {
            if (!iAppMap.containsKey(iAppServiceId)) {
                TvIAppInfo info = userState.mIAppMap.get(iAppServiceId).mInfo;
                ServiceState serviceState = userState.mServiceStateMap.get(info.getComponent());
                if (serviceState != null) {
                    abortPendingCreateSessionRequestsLocked(serviceState, iAppServiceId, userId);
                }
                notifyIAppServiceRemovedLocked(userState, iAppServiceId);
            }
        }

        userState.mIAppMap.clear();
        userState.mIAppMap = iAppMap;
    }

    @GuardedBy("mLock")
    private void notifyIAppServiceAddedLocked(UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyIAppServiceAddedLocked(iAppServiceId=" + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onIAppServiceAdded(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report added IApp service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyIAppServiceRemovedLocked(UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyIAppServiceRemovedLocked(iAppServiceId=" + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onIAppServiceRemoved(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report removed IApp service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyIAppServiceUpdatedLocked(UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyIAppServiceUpdatedLocked(iAppServiceId=" + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onIAppServiceUpdated(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report updated IApp service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyStateChangedLocked(
            UserState userState, String iAppServiceId, int type, int state) {
        if (DEBUG) {
            Slog.d(TAG, "notifyRteStateChanged(iAppServiceId="
                    + iAppServiceId + ", type=" + type + ", state=" + state + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onStateChanged(iAppServiceId, type, state);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report RTE state changed", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    private int getIAppUid(TvIAppInfo info) {
        try {
            return getContext().getPackageManager().getApplicationInfo(
                    info.getServiceInfo().packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.w(TAG, "Unable to get UID for  " + info, e);
            return Process.INVALID_UID;
        }
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slogf.d(TAG, "onStart");
        }
        publishBinderService(Context.TV_IAPP_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            registerBroadcastReceivers();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                buildTvIAppServiceListLocked(mCurrentUserId, null);
            }
        }
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            private void buildTvIAppServiceList(String[] packages) {
                int userId = getChangingUserId();
                synchronized (mLock) {
                    if (mCurrentUserId == userId || mRunningProfiles.contains(userId)) {
                        buildTvIAppServiceListLocked(userId, packages);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                if (DEBUG) Slogf.d(TAG, "onPackageUpdateFinished(packageName=" + packageName + ")");
                // This callback is invoked when the TV iApp service is reinstalled.
                // In this case, isReplacing() always returns true.
                buildTvIAppServiceList(new String[] { packageName });
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                if (DEBUG) {
                    Slogf.d(TAG, "onPackagesAvailable(packages=" + Arrays.toString(packages) + ")");
                }
                // This callback is invoked when the media on which some packages exist become
                // available.
                if (isReplacing()) {
                    buildTvIAppServiceList(packages);
                }
            }

            @Override
            public void onPackagesUnavailable(String[] packages) {
                // This callback is invoked when the media on which some packages exist become
                // unavailable.
                if (DEBUG)  {
                    Slogf.d(TAG, "onPackagesUnavailable(packages=" + Arrays.toString(packages)
                            + ")");
                }
                if (isReplacing()) {
                    buildTvIAppServiceList(packages);
                }
            }

            @Override
            public void onSomePackagesChanged() {
                if (DEBUG) Slogf.d(TAG, "onSomePackagesChanged()");
                if (isReplacing()) {
                    if (DEBUG) Slogf.d(TAG, "Skipped building TV iApp list due to replacing");
                    // When the package is updated, buildTvIAppServiceListLocked is called in other
                    // methods instead.
                    return;
                }
                buildTvIAppServiceList(null);
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                // The iApp list needs to be updated in any cases, regardless of whether
                // it happened to the whole package or a specific component. Returning true so that
                // the update can be handled in {@link #onSomePackagesChanged}.
                return true;
            }
        };
        monitor.register(mContext, null, UserHandle.ALL, true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        intentFilter.addAction(Intent.ACTION_USER_STARTED);
        intentFilter.addAction(Intent.ACTION_USER_STOPPED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_STARTED.equals(action)) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    startUser(userId);
                } else if (Intent.ACTION_USER_STOPPED.equals(action)) {
                    int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0);
                    stopUser(userId);
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                return;
            }
            UserInfo userInfo = mUserManager.getUserInfo(userId);
            if (userInfo.isProfile()) {
                Slog.w(TAG, "cannot switch to a profile!");
                return;
            }

            for (int runningId : mRunningProfiles) {
                releaseSessionOfUserLocked(runningId);
                unbindServiceOfUserLocked(runningId);
            }
            mRunningProfiles.clear();
            releaseSessionOfUserLocked(mCurrentUserId);
            unbindServiceOfUserLocked(mCurrentUserId);

            mCurrentUserId = userId;
            buildTvIAppServiceListLocked(userId, null);
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState == null) {
                return;
            }
            // Release all created sessions.
            for (SessionState state : userState.mSessionStateMap.values()) {
                if (state.mSession != null) {
                    try {
                        state.mSession.release();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in release", e);
                    }
                }
            }
            userState.mSessionStateMap.clear();

            // Unregister all callbacks and unbind all services.
            for (ServiceState serviceState : userState.mServiceStateMap.values()) {
                if (serviceState.mService != null) {
                    if (serviceState.mCallback != null) {
                        try {
                            serviceState.mService.unregisterCallback(serviceState.mCallback);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in unregisterCallback", e);
                        }
                    }
                    mContext.unbindService(serviceState.mConnection);
                }
            }
            userState.mServiceStateMap.clear();

            // Clear everything else.
            userState.mIAppMap.clear();
            userState.mPackageSet.clear();
            userState.mClientStateMap.clear();
            userState.mCallbacks.kill();

            mRunningProfiles.remove(userId);
            mUserStates.remove(userId);

            if (userId == mCurrentUserId) {
                switchUser(UserHandle.USER_SYSTEM);
            }
        }
    }

    private void startUser(int userId) {
        synchronized (mLock) {
            if (userId == mCurrentUserId || mRunningProfiles.contains(userId)) {
                // user already started
                return;
            }
            UserInfo userInfo = mUserManager.getUserInfo(userId);
            UserInfo parentInfo = mUserManager.getProfileParent(userId);
            if (userInfo.isProfile()
                    && parentInfo != null
                    && parentInfo.id == mCurrentUserId) {
                // only the children of the current user can be started in background
                startProfileLocked(userId);
            }
        }
    }

    private void stopUser(int userId) {
        synchronized (mLock) {
            if (userId == mCurrentUserId) {
                switchUser(ActivityManager.getCurrentUser());
                return;
            }

            releaseSessionOfUserLocked(userId);
            unbindServiceOfUserLocked(userId);
            mRunningProfiles.remove(userId);
        }
    }

    @GuardedBy("mLock")
    private void startProfileLocked(int userId) {
        mRunningProfiles.add(userId);
        buildTvIAppServiceListLocked(userId, null);
    }

    @GuardedBy("mLock")
    private void releaseSessionOfUserLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            return;
        }
        List<SessionState> sessionStatesToRelease = new ArrayList<>();
        for (SessionState sessionState : userState.mSessionStateMap.values()) {
            if (sessionState.mSession != null) {
                sessionStatesToRelease.add(sessionState);
            }
        }
        for (SessionState sessionState : sessionStatesToRelease) {
            try {
                sessionState.mSession.release();
            } catch (RemoteException e) {
                Slog.e(TAG, "error in release", e);
            }
            clearSessionAndNotifyClientLocked(sessionState);
        }
    }

    @GuardedBy("mLock")
    private void unbindServiceOfUserLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            return;
        }
        for (Iterator<ComponentName> it = userState.mServiceStateMap.keySet().iterator();
                it.hasNext(); ) {
            ComponentName component = it.next();
            ServiceState serviceState = userState.mServiceStateMap.get(component);
            if (serviceState != null && serviceState.mSessionTokens.isEmpty()) {
                if (serviceState.mCallback != null) {
                    try {
                        serviceState.mService.unregisterCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unregisterCallback", e);
                    }
                }
                mContext.unbindService(serviceState.mConnection);
                it.remove();
            }
        }
    }

    @GuardedBy("mLock")
    private void clearSessionAndNotifyClientLocked(SessionState state) {
        if (state.mClient != null) {
            try {
                state.mClient.onSessionReleased(state.mSeq);
            } catch (RemoteException e) {
                Slog.e(TAG, "error in onSessionReleased", e);
            }
        }
        removeSessionStateLocked(state.mSessionToken, state.mUserId);
    }

    private int resolveCallingUserId(int callingPid, int callingUid, int requestedUserId,
            String methodName) {
        return ActivityManager.handleIncomingUser(callingPid, callingUid, requestedUserId, false,
                false, methodName, null);
    }

    @GuardedBy("mLock")
    private UserState getOrCreateUserStateLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            userState = new UserState(userId);
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    @GuardedBy("mLock")
    private UserState getUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    @GuardedBy("mLock")
    private ServiceState getServiceStateLocked(ComponentName component, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        ServiceState serviceState = userState.mServiceStateMap.get(component);
        if (serviceState == null) {
            throw new IllegalStateException("Service state not found for " + component + " (userId="
                    + userId + ")");
        }
        return serviceState;
    }

    @GuardedBy("mLock")
    private SessionState getSessionStateLocked(IBinder sessionToken, int callingUid, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        return getSessionStateLocked(sessionToken, callingUid, userState);
    }

    @GuardedBy("mLock")
    private SessionState getSessionStateLocked(IBinder sessionToken, int callingUid,
            UserState userState) {
        SessionState sessionState = userState.mSessionStateMap.get(sessionToken);
        if (sessionState == null) {
            throw new SessionNotFoundException("Session state not found for token " + sessionToken);
        }
        // Only the application that requested this session or the system can access it.
        if (callingUid != Process.SYSTEM_UID && callingUid != sessionState.mCallingUid) {
            throw new SecurityException("Illegal access to the session with token " + sessionToken
                    + " from uid " + callingUid);
        }
        return sessionState;
    }

    @GuardedBy("mLock")
    private ITvIAppSession getSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        return getSessionLocked(getSessionStateLocked(sessionToken, callingUid, userId));
    }

    @GuardedBy("mLock")
    private ITvIAppSession getSessionLocked(SessionState sessionState) {
        ITvIAppSession session = sessionState.mSession;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token "
                    + sessionState.mSessionToken);
        }
        return session;
    }

    private final class BinderService extends ITvIAppManager.Stub {

        @Override
        public List<TvIAppInfo> getTvIAppServiceList(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvIAppServiceList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    List<TvIAppInfo> iAppList = new ArrayList<>();
                    for (TvIAppState state : userState.mIAppMap.values()) {
                        iAppList.add(state.mInfo);
                    }
                    return iAppList;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void prepare(String tiasId, int type, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "prepare");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvIAppState iAppState = userState.mIAppMap.get(tiasId);
                    if (iAppState == null) {
                        Slogf.e(TAG, "failed to prepare TIAS - unknown TIAS id " + tiasId);
                        return;
                    }
                    ComponentName componentName = iAppState.mInfo.getComponent();
                    ServiceState serviceState = userState.mServiceStateMap.get(componentName);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                componentName, tiasId, resolvedUserId, true, type);
                        userState.mServiceStateMap.put(componentName, serviceState);
                    } else if (serviceState.mService != null) {
                        serviceState.mService.prepare(type);
                    }
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in prepare", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createSession(final ITvIAppClient client, final String iAppServiceId, int type,
                int seq, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "createSession");
            final long identity = Binder.clearCallingIdentity();

            try {
                synchronized (mLock) {
                    if (userId != mCurrentUserId && !mRunningProfiles.contains(userId)) {
                        // Only current user and its running profiles can create sessions.
                        // Let the client get onConnectionFailed callback for this case.
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, null, seq);
                        return;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvIAppState iAppState = userState.mIAppMap.get(iAppServiceId);
                    if (iAppState == null) {
                        Slogf.w(TAG, "Failed to find state for iAppServiceId=" + iAppServiceId);
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, null, seq);
                        return;
                    }
                    ServiceState serviceState =
                            userState.mServiceStateMap.get(iAppState.mComponentName);
                    if (serviceState == null) {
                        int tiasUid = PackageManager.getApplicationInfoAsUserCached(
                                iAppState.mComponentName.getPackageName(), 0, resolvedUserId).uid;
                        serviceState = new ServiceState(
                                iAppState.mComponentName, iAppServiceId, resolvedUserId);
                        userState.mServiceStateMap.put(iAppState.mComponentName, serviceState);
                    }
                    // Send a null token immediately while reconnecting.
                    if (serviceState.mReconnecting) {
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, null, seq);
                        return;
                    }

                    // Create a new session token and a session state.
                    IBinder sessionToken = new Binder();
                    SessionState sessionState = new SessionState(sessionToken, iAppServiceId, type,
                            iAppState.mComponentName, client, seq, callingUid,
                            callingPid, resolvedUserId);

                    // Add them to the global session state map of the current user.
                    userState.mSessionStateMap.put(sessionToken, sessionState);

                    // Also, add them to the session state map of the current service.
                    serviceState.mSessionTokens.add(sessionToken);

                    if (serviceState.mService != null) {
                        if (!createSessionInternalLocked(serviceState.mService, sessionToken,
                                resolvedUserId)) {
                            removeSessionStateLocked(sessionToken, resolvedUserId);
                        }
                    } else {
                        updateServiceConnectionLocked(iAppState.mComponentName, resolvedUserId);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void releaseSession(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "releaseSession(sessionToken=" + sessionToken + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "releaseSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    releaseSessionLocked(sessionToken, callingUid, resolvedUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTuned(IBinder sessionToken, Uri channelUri, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTuned(sessionToken=" + sessionToken
                        + ", Uri=" + channelUri + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "notifyTuned");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyTuned(channelUri);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTuned", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startIApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#start(userId=%d)", userId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "notifyTuned");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).startIApp();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in start", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setSurface(IBinder sessionToken, Surface surface, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setSurface");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).setSurface(surface);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in setSurface", e);
                    }
                }
            } finally {
                if (surface != null) {
                    // surface is not used in TvIAppManagerService.
                    surface.release();
                }
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void dispatchSurfaceChanged(IBinder sessionToken, int format, int width,
                int height, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "dispatchSurfaceChanged");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).dispatchSurfaceChanged(format, width,
                                height);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in dispatchSurfaceChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyBroadcastInfoResponse(IBinder sessionToken,
                BroadcastInfoResponse response, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyBroadcastInfoResponse");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyBroadcastInfoResponse(response);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyBroadcastInfoResponse", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerCallback(final ITvIAppManagerCallback callback, int userId) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "registerCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    final UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    if (!userState.mCallbacks.register(callback)) {
                        Slog.e(TAG, "client process has already died");
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unregisterCallback(ITvIAppManagerCallback callback, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "unregisterCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    userState.mCallbacks.unregister(callback);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createMediaView(IBinder sessionToken, IBinder windowToken, Rect frame,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createMediaView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .createMediaView(windowToken, frame);
                    } catch (RemoteException | TvIAppManagerService.SessionNotFoundException e) {
                        Slog.e(TAG, "error in createMediaView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void relayoutMediaView(IBinder sessionToken, Rect frame, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "relayoutMediaView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .relayoutMediaView(frame);
                    } catch (RemoteException | TvIAppManagerService.SessionNotFoundException e) {
                        Slog.e(TAG, "error in relayoutMediaView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removeMediaView(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "removeMediaView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .removeMediaView();
                    } catch (RemoteException | TvIAppManagerService.SessionNotFoundException e) {
                        Slog.e(TAG, "error in removeMediaView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendSessionTokenToClientLocked(ITvIAppClient client, String iAppServiceId,
            IBinder sessionToken, InputChannel channel, int seq) {
        try {
            client.onSessionCreated(iAppServiceId, sessionToken, channel, seq);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in onSessionCreated", e);
        }
    }

    @GuardedBy("mLock")
    private boolean createSessionInternalLocked(ITvIAppService service, IBinder sessionToken,
            int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        SessionState sessionState = userState.mSessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slogf.d(TAG, "createSessionInternalLocked(iAppServiceId="
                    + sessionState.mIAppServiceId + ")");
        }
        InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvIAppSessionCallback callback = new SessionCallback(sessionState, channels);

        boolean created = true;
        // Create a session. When failed, send a null token immediately.
        try {
            service.createSession(
                    channels[1], callback, sessionState.mIAppServiceId, sessionState.mType);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in createSession", e);
            sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mIAppServiceId, null,
                    null, sessionState.mSeq);
            created = false;
        }
        channels[1].dispose();
        return created;
    }

    @GuardedBy("mLock")
    @Nullable
    private SessionState releaseSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        SessionState sessionState = null;
        try {
            sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
            UserState userState = getOrCreateUserStateLocked(userId);
            if (sessionState.mSession != null) {
                sessionState.mSession.asBinder().unlinkToDeath(sessionState, 0);
                sessionState.mSession.release();
            }
        } catch (RemoteException | SessionNotFoundException e) {
            Slogf.e(TAG, "error in releaseSession", e);
        } finally {
            if (sessionState != null) {
                sessionState.mSession = null;
            }
        }
        removeSessionStateLocked(sessionToken, userId);
        return sessionState;
    }

    @GuardedBy("mLock")
    private void removeSessionStateLocked(IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);

        // Remove the session state from the global session state map of the current user.
        SessionState sessionState = userState.mSessionStateMap.remove(sessionToken);

        if (sessionState == null) {
            Slogf.e(TAG, "sessionState null, no more remove session action!");
            return;
        }

        // Also remove the session token from the session token list of the current client and
        // service.
        ClientState clientState = userState.mClientStateMap.get(sessionState.mClient.asBinder());
        if (clientState != null) {
            clientState.mSessionTokens.remove(sessionToken);
            if (clientState.isEmpty()) {
                userState.mClientStateMap.remove(sessionState.mClient.asBinder());
                sessionState.mClient.asBinder().unlinkToDeath(clientState, 0);
            }
        }

        ServiceState serviceState = userState.mServiceStateMap.get(sessionState.mComponent);
        if (serviceState != null) {
            serviceState.mSessionTokens.remove(sessionToken);
        }
        updateServiceConnectionLocked(sessionState.mComponent, userId);
    }

    @GuardedBy("mLock")
    private void abortPendingCreateSessionRequestsLocked(ServiceState serviceState,
            String iAppServiceId, int userId) {
        // Let clients know the create session requests are failed.
        UserState userState = getOrCreateUserStateLocked(userId);
        List<SessionState> sessionsToAbort = new ArrayList<>();
        for (IBinder sessionToken : serviceState.mSessionTokens) {
            SessionState sessionState = userState.mSessionStateMap.get(sessionToken);
            if (sessionState.mSession == null
                    && (iAppServiceId == null
                    || sessionState.mIAppServiceId.equals(iAppServiceId))) {
                sessionsToAbort.add(sessionState);
            }
        }
        for (SessionState sessionState : sessionsToAbort) {
            removeSessionStateLocked(sessionState.mSessionToken, sessionState.mUserId);
            sendSessionTokenToClientLocked(sessionState.mClient,
                    sessionState.mIAppServiceId, null, null, sessionState.mSeq);
        }
        updateServiceConnectionLocked(serviceState.mComponent, userId);
    }

    @GuardedBy("mLock")
    private void updateServiceConnectionLocked(ComponentName component, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        ServiceState serviceState = userState.mServiceStateMap.get(component);
        if (serviceState == null) {
            return;
        }
        if (serviceState.mReconnecting) {
            if (!serviceState.mSessionTokens.isEmpty()) {
                // wait until all the sessions are removed.
                return;
            }
            serviceState.mReconnecting = false;
        }

        boolean shouldBind = !serviceState.mSessionTokens.isEmpty();

        if (serviceState.mService == null && shouldBind) {
            // This means that the service is not yet connected but its state indicates that we
            // have pending requests. Then, connect the service.
            if (serviceState.mBound) {
                // We have already bound to the service so we don't try to bind again until after we
                // unbind later on.
                return;
            }
            if (DEBUG) {
                Slogf.d(TAG, "bindServiceAsUser(service=" + component + ", userId=" + userId + ")");
            }

            Intent i = new Intent(TvIAppService.SERVICE_INTERFACE).setComponent(component);
            serviceState.mBound = mContext.bindServiceAsUser(
                    i, serviceState.mConnection,
                    Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                    new UserHandle(userId));
        } else if (serviceState.mService != null && !shouldBind) {
            // This means that the service is already connected but its state indicates that we have
            // nothing to do with it. Then, disconnect the service.
            if (DEBUG) {
                Slogf.d(TAG, "unbindService(service=" + component + ")");
            }
            mContext.unbindService(serviceState.mConnection);
            userState.mServiceStateMap.remove(component);
        }
    }

    private static final class UserState {
        private final int mUserId;
        // A mapping from the TV IApp ID to its TvIAppState.
        private Map<String, TvIAppState> mIAppMap = new HashMap<>();
        // A mapping from the token of a client to its state.
        private final Map<IBinder, ClientState> mClientStateMap = new HashMap<>();
        // A mapping from the name of a TV IApp service to its state.
        private final Map<ComponentName, ServiceState> mServiceStateMap = new HashMap<>();
        // A mapping from the token of a TV IApp session to its state.
        private final Map<IBinder, SessionState> mSessionStateMap = new HashMap<>();

        // A set of all TV IApp service packages.
        private final Set<String> mPackageSet = new HashSet<>();

        // A list of callbacks.
        private final RemoteCallbackList<ITvIAppManagerCallback> mCallbacks =
                new RemoteCallbackList<>();

        private UserState(int userId) {
            mUserId = userId;
        }
    }

    private static final class TvIAppState {
        private String mIAppServiceId;
        private ComponentName mComponentName;
        private TvIAppInfo mInfo;
        private int mUid;
        private int mIAppNumber;
    }

    private final class SessionState implements IBinder.DeathRecipient {
        private final IBinder mSessionToken;
        private ITvIAppSession mSession;
        private final String mIAppServiceId;
        private final int mType;
        private final ITvIAppClient mClient;
        private final int mSeq;
        private final ComponentName mComponent;

        // The UID of the application that created the session.
        // The application is usually the TV app.
        private final int mCallingUid;

        // The PID of the application that created the session.
        // The application is usually the TV app.
        private final int mCallingPid;

        private final int mUserId;

        private SessionState(IBinder sessionToken, String iAppServiceId, int type,
                ComponentName componentName, ITvIAppClient client, int seq, int callingUid,
                int callingPid, int userId) {
            mSessionToken = sessionToken;
            mIAppServiceId = iAppServiceId;
            mComponent = componentName;
            mType = type;
            mClient = client;
            mSeq = seq;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            mUserId = userId;
        }

        @Override
        public void binderDied() {
        }
    }

    private final class ClientState implements IBinder.DeathRecipient {
        private final List<IBinder> mSessionTokens = new ArrayList<>();

        private IBinder mClientToken;
        private final int mUserId;

        ClientState(IBinder clientToken, int userId) {
            mClientToken = clientToken;
            mUserId = userId;
        }

        public boolean isEmpty() {
            return mSessionTokens.isEmpty();
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mUserId);
                // DO NOT remove the client state of clientStateMap in this method. It will be
                // removed in releaseSessionLocked().
                ClientState clientState = userState.mClientStateMap.get(mClientToken);
                if (clientState != null) {
                    while (clientState.mSessionTokens.size() > 0) {
                        IBinder sessionToken = clientState.mSessionTokens.get(0);
                        releaseSessionLocked(
                                sessionToken, Process.SYSTEM_UID, mUserId);
                        // the releaseSessionLocked function may return before the sessionToken
                        // is removed if the related sessionState is null. So need to check again
                        // to avoid death circulation.
                        if (clientState.mSessionTokens.contains(sessionToken)) {
                            Slogf.d(TAG, "remove sessionToken " + sessionToken + " for "
                                    + mClientToken);
                            clientState.mSessionTokens.remove(sessionToken);
                        }
                    }
                }
                mClientToken = null;
            }
        }
    }

    private final class ServiceState {
        private final List<IBinder> mSessionTokens = new ArrayList<>();
        private final ServiceConnection mConnection;
        private final ComponentName mComponent;
        private final String mIAppSeriviceId;

        private boolean mPendingPrepare = false;
        private Integer mPendingPrepareType = null;
        private ITvIAppService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private ServiceState(ComponentName component, String tias, int userId) {
            this(component, tias, userId, false, null);
        }

        private ServiceState(ComponentName component, String tias, int userId,
                boolean pendingPrepare, Integer prepareType) {
            mComponent = component;
            mPendingPrepare = pendingPrepare;
            mPendingPrepareType = prepareType;
            mConnection = new IAppServiceConnection(component, userId);
            mIAppSeriviceId = tias;
        }
    }

    private final class IAppServiceConnection implements ServiceConnection {
        private final ComponentName mComponent;
        private final int mUserId;

        private IAppServiceConnection(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName component, IBinder service) {
            if (DEBUG) {
                Slogf.d(TAG, "onServiceConnected(component=" + component + ")");
            }
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mUserId);
                if (userState == null) {
                    // The user was removed while connecting.
                    mContext.unbindService(this);
                    return;
                }
                ServiceState serviceState = userState.mServiceStateMap.get(mComponent);
                serviceState.mService = ITvIAppService.Stub.asInterface(service);

                if (serviceState.mPendingPrepare) {
                    final long identity = Binder.clearCallingIdentity();
                    try {
                        serviceState.mService.prepare(serviceState.mPendingPrepareType);
                        serviceState.mPendingPrepare = false;
                        serviceState.mPendingPrepareType = null;
                    } catch (RemoteException e) {
                        Slogf.e(TAG, "error in prepare when onServiceConnected", e);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }

                List<IBinder> tokensToBeRemoved = new ArrayList<>();

                // And create sessions, if any.
                for (IBinder sessionToken : serviceState.mSessionTokens) {
                    if (!createSessionInternalLocked(
                            serviceState.mService, sessionToken, mUserId)) {
                        tokensToBeRemoved.add(sessionToken);
                    }
                }

                for (IBinder sessionToken : tokensToBeRemoved) {
                    removeSessionStateLocked(sessionToken, mUserId);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            if (DEBUG) {
                Slogf.d(TAG, "onServiceDisconnected(component=" + component + ")");
            }
            if (!mComponent.equals(component)) {
                throw new IllegalArgumentException("Mismatched ComponentName: "
                        + mComponent + " (expected), " + component + " (actual).");
            }
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mUserId);
                ServiceState serviceState = userState.mServiceStateMap.get(mComponent);
                if (serviceState != null) {
                    serviceState.mReconnecting = true;
                    serviceState.mBound = false;
                    serviceState.mService = null;
                    serviceState.mCallback = null;

                    abortPendingCreateSessionRequestsLocked(serviceState, null, mUserId);
                }
            }
        }
    }

    private final class ServiceCallback extends ITvIAppServiceCallback.Stub {
        private final ComponentName mComponent;
        private final int mUserId;

        ServiceCallback(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }

        @Override
        public void onStateChanged(int type, int state) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
                    String iAppServiceId = serviceState.mIAppSeriviceId;
                    UserState userState = getUserStateLocked(mUserId);
                    notifyStateChangedLocked(userState, iAppServiceId, type, state);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private final class SessionCallback extends ITvIAppSessionCallback.Stub {
        private final SessionState mSessionState;
        private final InputChannel[] mInputChannels;

        SessionCallback(SessionState sessionState, InputChannel[] channels) {
            mSessionState = sessionState;
            mInputChannels = channels;
        }

        @Override
        public void onSessionCreated(ITvIAppSession session) {
            if (DEBUG) {
                Slogf.d(TAG, "onSessionCreated(iAppServiceId="
                        + mSessionState.mIAppServiceId + ")");
            }
            synchronized (mLock) {
                mSessionState.mSession = session;
                if (session != null && addSessionTokenToClientStateLocked(session)) {
                    sendSessionTokenToClientLocked(
                            mSessionState.mClient,
                            mSessionState.mIAppServiceId,
                            mSessionState.mSessionToken,
                            mInputChannels[0],
                            mSessionState.mSeq);
                } else {
                    removeSessionStateLocked(mSessionState.mSessionToken, mSessionState.mUserId);
                    sendSessionTokenToClientLocked(mSessionState.mClient,
                            mSessionState.mIAppServiceId, null, null, mSessionState.mSeq);
                }
                mInputChannels[0].dispose();
            }
        }

        @Override
        public void onLayoutSurface(int left, int top, int right, int bottom) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onLayoutSurface (left=" + left + ", top=" + top
                            + ", right=" + right + ", bottom=" + bottom + ",)");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onLayoutSurface(left, top, right, bottom,
                            mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onLayoutSurface", e);
                }
            }
        }

        @Override
        public void onBroadcastInfoRequest(BroadcastInfoRequest request) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onBroadcastInfoRequest (requestId="
                            + request.getRequestId() + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onBroadcastInfoRequest(request, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onBroadcastInfoRequest", e);
                }
            }
        }

        @Override
        public void onCommandRequest(@TvIAppService.IAppServiceCommandType String cmdType,
                Bundle parameters) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onCommandRequest (cmdType=" + cmdType + ", parameters="
                            + parameters.toString() + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onCommandRequest(cmdType, parameters, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onCommandRequest", e);
                }
            }
        }

        @Override
        public void onSessionStateChanged(int state) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onSessionStateChanged (state=" + state + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onSessionStateChanged(state, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onSessionStateChanged", e);
                }
            }
        }

        @GuardedBy("mLock")
        private boolean addSessionTokenToClientStateLocked(ITvIAppSession session) {
            try {
                session.asBinder().linkToDeath(mSessionState, 0);
            } catch (RemoteException e) {
                Slogf.e(TAG, "session process has already died", e);
                return false;
            }

            IBinder clientToken = mSessionState.mClient.asBinder();
            UserState userState = getOrCreateUserStateLocked(mSessionState.mUserId);
            ClientState clientState = userState.mClientStateMap.get(clientToken);
            if (clientState == null) {
                clientState = new ClientState(clientToken, mSessionState.mUserId);
                try {
                    clientToken.linkToDeath(clientState, 0);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "client process has already died", e);
                    return false;
                }
                userState.mClientStateMap.put(clientToken, clientState);
            }
            clientState.mSessionTokens.add(mSessionState.mSessionToken);
            return true;
        }
    }

    private static class SessionNotFoundException extends IllegalArgumentException {
        SessionNotFoundException(String name) {
            super(name);
        }
    }

    private static class ClientPidNotFoundException extends IllegalArgumentException {
        ClientPidNotFoundException(String name) {
            super(name);
        }
    }
}
