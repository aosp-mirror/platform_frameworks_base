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
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.AdResponse;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.TvRecordingInfo;
import android.media.tv.TvTrackInfo;
import android.media.tv.ad.ITvAdClient;
import android.media.tv.ad.ITvAdManager;
import android.media.tv.ad.ITvAdService;
import android.media.tv.ad.ITvAdServiceCallback;
import android.media.tv.ad.ITvAdSession;
import android.media.tv.ad.ITvAdSessionCallback;
import android.media.tv.ad.TvAdService;
import android.media.tv.ad.TvAdServiceInfo;
import android.media.tv.interactive.AppLinkInfo;
import android.media.tv.interactive.ITvInteractiveAppClient;
import android.media.tv.interactive.ITvInteractiveAppManager;
import android.media.tv.interactive.ITvInteractiveAppManagerCallback;
import android.media.tv.interactive.ITvInteractiveAppService;
import android.media.tv.interactive.ITvInteractiveAppServiceCallback;
import android.media.tv.interactive.ITvInteractiveAppSession;
import android.media.tv.interactive.ITvInteractiveAppSessionCallback;
import android.media.tv.interactive.TvInteractiveAppService;
import android.media.tv.interactive.TvInteractiveAppServiceInfo;
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
import android.util.Pair;
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
public class TvInteractiveAppManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInteractiveAppManagerService";

    private static final String METADATA_CLASS_NAME =
            "android.media.tv.interactive.AppLinkInfo.ClassName";
    private static final String METADATA_URI =
            "android.media.tv.interactive.AppLinkInfo.Uri";
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

    // TODO: remove mGetServiceListCalled if onBootPhrase work correctly
    @GuardedBy("mLock")
    private boolean mGetServiceListCalled = false;
    @GuardedBy("mLock")
    private boolean mGetAppLinkInfoListCalled = false;

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
    public TvInteractiveAppManagerService(Context context) {
        super(context);
        mContext = context;
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
    }

    @GuardedBy("mLock")
    private void buildAppLinkInfoLocked(int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        if (DEBUG) {
            Slogf.d(TAG, "buildAppLinkInfoLocked");
        }
        PackageManager pm = mContext.getPackageManager();
        List<ApplicationInfo> appInfos = pm.getInstalledApplicationsAsUser(
                PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA), userId);
        List<AppLinkInfo> appLinkInfos = new ArrayList<>();
        for (ApplicationInfo appInfo : appInfos) {
            AppLinkInfo info = buildAppLinkInfoLocked(appInfo);
            if (info != null) {
                appLinkInfos.add(info);
            }
        }
        // sort the list by package name
        Collections.sort(appLinkInfos, Comparator.comparing(AppLinkInfo::getComponentName));
        userState.mAppLinkInfoList.clear();
        userState.mAppLinkInfoList.addAll(appLinkInfos);
    }

    @GuardedBy("mLock")
    private AppLinkInfo buildAppLinkInfoLocked(ApplicationInfo appInfo) {
        if (appInfo.metaData == null || appInfo.packageName == null) {
            return null;
        }
        String className = appInfo.metaData.getString(METADATA_CLASS_NAME, null);
        String uri = appInfo.metaData.getString(METADATA_URI, null);
        if (className == null || uri == null) {
            return null;
        }
        return new AppLinkInfo(appInfo.packageName, className, uri);
    }

    @GuardedBy("mLock")
    private void buildTvInteractiveAppServiceListLocked(int userId, String[] updatedPackages) {
        UserState userState = getOrCreateUserStateLocked(userId);
        userState.mPackageSet.clear();

        if (DEBUG) {
            Slogf.d(TAG, "buildTvInteractiveAppServiceListLocked");
        }
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(TvInteractiveAppService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                userId);
        List<TvInteractiveAppServiceInfo> iAppList = new ArrayList<>();

        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (!android.Manifest.permission.BIND_TV_INTERACTIVE_APP.equals(si.permission)) {
                Slog.w(TAG, "Skipping TV interactiva app service " + si.name
                        + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TV_INTERACTIVE_APP);
                continue;
            }

            ComponentName component = new ComponentName(si.packageName, si.name);
            try {
                TvInteractiveAppServiceInfo info =
                        new TvInteractiveAppServiceInfo(mContext, component);
                iAppList.add(info);
            } catch (Exception e) {
                Slogf.e(TAG, "failed to load TV Interactive App service " + si.name, e);
                continue;
            }
            userState.mPackageSet.add(si.packageName);
        }

        // sort the iApp list by iApp service id
        Collections.sort(iAppList, Comparator.comparing(TvInteractiveAppServiceInfo::getId));
        Map<String, TvInteractiveAppState> iAppMap = new HashMap<>();
        ArrayMap<String, Integer> tiasAppCount = new ArrayMap<>(iAppMap.size());
        for (TvInteractiveAppServiceInfo info : iAppList) {
            String iAppServiceId = info.getId();
            if (DEBUG) {
                Slogf.d(TAG, "add " + iAppServiceId);
            }
            // Running count of Interactive App for each Interactive App service
            Integer count = tiasAppCount.get(iAppServiceId);
            count = count == null ? 1 : count + 1;
            tiasAppCount.put(iAppServiceId, count);
            TvInteractiveAppState iAppState = userState.mIAppMap.get(iAppServiceId);
            if (iAppState == null) {
                iAppState = new TvInteractiveAppState();
            }
            iAppState.mInfo = info;
            iAppState.mUid = getInteractiveAppUid(info);
            iAppState.mComponentName = info.getComponent();
            iAppMap.put(iAppServiceId, iAppState);
            iAppState.mIAppNumber = count;
        }

        for (String iAppServiceId : iAppMap.keySet()) {
            if (!userState.mIAppMap.containsKey(iAppServiceId)) {
                notifyInteractiveAppServiceAddedLocked(userState, iAppServiceId);
            } else if (updatedPackages != null) {
                // Notify the package updates
                ComponentName component = iAppMap.get(iAppServiceId).mInfo.getComponent();
                for (String updatedPackage : updatedPackages) {
                    if (component.getPackageName().equals(updatedPackage)) {
                        updateServiceConnectionLocked(component, userId);
                        notifyInteractiveAppServiceUpdatedLocked(userState, iAppServiceId);
                        break;
                    }
                }
            }
        }

        for (String iAppServiceId : userState.mIAppMap.keySet()) {
            if (!iAppMap.containsKey(iAppServiceId)) {
                TvInteractiveAppServiceInfo info = userState.mIAppMap.get(iAppServiceId).mInfo;
                ServiceState serviceState = userState.mServiceStateMap.get(info.getComponent());
                if (serviceState != null) {
                    abortPendingCreateSessionRequestsLocked(serviceState, iAppServiceId, userId);
                }
                notifyInteractiveAppServiceRemovedLocked(userState, iAppServiceId);
            }
        }

        userState.mIAppMap.clear();
        userState.mIAppMap = iAppMap;
    }

    @GuardedBy("mLock")
    private void notifyInteractiveAppServiceAddedLocked(UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInteractiveAppServiceAddedLocked(iAppServiceId="
                    + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i)
                        .onInteractiveAppServiceAdded(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report added Interactive App service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyInteractiveAppServiceRemovedLocked(
            UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInteractiveAppServiceRemovedLocked(iAppServiceId="
                    + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i)
                        .onInteractiveAppServiceRemoved(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report removed Interactive App service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyInteractiveAppServiceUpdatedLocked(
            UserState userState, String iAppServiceId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInteractiveAppServiceUpdatedLocked(iAppServiceId="
                    + iAppServiceId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i)
                        .onInteractiveAppServiceUpdated(iAppServiceId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report updated Interactive App service to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyStateChangedLocked(
            UserState userState, String iAppServiceId, int type, int state, int err) {
        if (DEBUG) {
            Slog.d(TAG, "notifyRteStateChanged(iAppServiceId="
                    + iAppServiceId + ", type=" + type + ", state=" + state + ", err=" + err + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i)
                        .onStateChanged(iAppServiceId, type, state, err);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report RTE state changed", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    private int getInteractiveAppUid(TvInteractiveAppServiceInfo info) {
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
        publishBinderService(Context.TV_INTERACTIVE_APP_SERVICE, new BinderService());
        publishBinderService(Context.TV_AD_SERVICE, new TvAdBinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            registerBroadcastReceivers();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                buildTvInteractiveAppServiceListLocked(mCurrentUserId, null);
                buildAppLinkInfoLocked(mCurrentUserId);
            }
        }
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            private void buildTvInteractiveAppServiceList(String[] packages) {
                int userId = getChangingUserId();
                synchronized (mLock) {
                    if (mCurrentUserId == userId || mRunningProfiles.contains(userId)) {
                        buildTvInteractiveAppServiceListLocked(userId, packages);
                        buildAppLinkInfoLocked(userId);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                if (DEBUG) Slogf.d(TAG, "onPackageUpdateFinished(packageName=" + packageName + ")");
                // This callback is invoked when the TV interactive App service is reinstalled.
                // In this case, isReplacing() always returns true.
                buildTvInteractiveAppServiceList(new String[] { packageName });
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                if (DEBUG) {
                    Slogf.d(TAG, "onPackagesAvailable(packages=" + Arrays.toString(packages) + ")");
                }
                // This callback is invoked when the media on which some packages exist become
                // available.
                if (isReplacing()) {
                    buildTvInteractiveAppServiceList(packages);
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
                    buildTvInteractiveAppServiceList(packages);
                }
            }

            @Override
            public void onSomePackagesChanged() {
                if (DEBUG) Slogf.d(TAG, "onSomePackagesChanged()");
                if (isReplacing()) {
                    if (DEBUG) {
                        Slogf.d(TAG, "Skipped building TV interactive App list due to replacing");
                    }
                    // When the package is updated, buildTvInteractiveAppServiceListLocked is called
                    // in other methods instead.
                    return;
                }
                buildTvInteractiveAppServiceList(null);
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                // The interactive App list needs to be updated in any cases, regardless of whether
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
            buildTvInteractiveAppServiceListLocked(userId, null);
            buildAppLinkInfoLocked(userId);
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
        buildTvInteractiveAppServiceListLocked(userId, null);
        buildAppLinkInfoLocked(userId);
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
        removeAdSessionStateLocked(state.mSessionToken, state.mUserId);
    }

    @GuardedBy("mLock")
    private void clearAdSessionAndNotifyClientLocked(AdSessionState state) {
        if (state.mClient != null) {
            try {
                state.mClient.onSessionReleased(state.mSeq);
            } catch (RemoteException e) {
                Slog.e(TAG, "error in onSessionReleased", e);
            }
        }
        removeAdSessionStateLocked(state.mSessionToken, state.mUserId);
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
    private AdSessionState getAdSessionStateLocked(
            IBinder sessionToken, int callingUid, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        return getAdSessionStateLocked(sessionToken, callingUid, userState);
    }

    @GuardedBy("mLock")
    private AdSessionState getAdSessionStateLocked(IBinder sessionToken, int callingUid,
            UserState userState) {
        AdSessionState sessionState = userState.mAdSessionStateMap.get(sessionToken);
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
    private ITvInteractiveAppSession getSessionLocked(
            IBinder sessionToken, int callingUid, int userId) {
        return getSessionLocked(getSessionStateLocked(sessionToken, callingUid, userId));
    }

    @GuardedBy("mLock")
    private ITvInteractiveAppSession getSessionLocked(SessionState sessionState) {
        ITvInteractiveAppSession session = sessionState.mSession;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token "
                    + sessionState.mSessionToken);
        }
        return session;
    }
    private final class TvAdBinderService extends ITvAdManager.Stub {

        @Override
        public void createSession(final ITvAdClient client, final String serviceId, String type,
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
                        sendAdSessionTokenToClientLocked(client, serviceId, null, null, seq);
                        return;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvAdState adState = userState.mAdMap.get(serviceId);
                    if (adState == null) {
                        Slogf.w(TAG, "Failed to find state for serviceId=" + serviceId);
                        sendAdSessionTokenToClientLocked(client, serviceId, null, null, seq);
                        return;
                    }
                    AdServiceState serviceState =
                            userState.mAdServiceStateMap.get(adState.mComponentName);
                    if (serviceState == null) {
                        int tasUid = PackageManager.getApplicationInfoAsUserCached(
                                adState.mComponentName.getPackageName(), 0, resolvedUserId).uid;
                        serviceState = new AdServiceState(
                                adState.mComponentName, serviceId, resolvedUserId);
                        userState.mAdServiceStateMap.put(adState.mComponentName, serviceState);
                    }
                    // Send a null token immediately while reconnecting.
                    if (serviceState.mReconnecting) {
                        sendAdSessionTokenToClientLocked(client, serviceId, null, null, seq);
                        return;
                    }

                    // Create a new session token and a session state.
                    IBinder sessionToken = new Binder();
                    AdSessionState sessionState = new AdSessionState(sessionToken, serviceId, type,
                            adState.mComponentName, client, seq, callingUid,
                            callingPid, resolvedUserId);

                    // Add them to the global session state map of the current user.
                    userState.mAdSessionStateMap.put(sessionToken, sessionState);

                    // Also, add them to the session state map of the current service.
                    serviceState.mSessionTokens.add(sessionToken);

                    if (serviceState.mService != null) {
                        if (!createAdSessionInternalLocked(serviceState.mService, sessionToken,
                                resolvedUserId)) {
                            removeAdSessionStateLocked(sessionToken, resolvedUserId);
                        }
                    } else {
                        updateAdServiceConnectionLocked(adState.mComponentName, resolvedUserId);
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
        public void startAdService(IBinder sessionToken, int userId) {
        }

    }

    private final class BinderService extends ITvInteractiveAppManager.Stub {

        @Override
        public List<TvInteractiveAppServiceInfo> getTvInteractiveAppServiceList(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvInteractiveAppServiceList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (!mGetServiceListCalled) {
                        buildTvInteractiveAppServiceListLocked(userId, null);
                        mGetServiceListCalled = true;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    List<TvInteractiveAppServiceInfo> iAppList = new ArrayList<>();
                    for (TvInteractiveAppState state : userState.mIAppMap.values()) {
                        iAppList.add(state.mInfo);
                    }
                    return iAppList;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<AppLinkInfo> getAppLinkInfoList(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getAppLinkInfoList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    if (!mGetAppLinkInfoListCalled) {
                        buildAppLinkInfoLocked(userId);
                        mGetAppLinkInfoListCalled = true;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    List<AppLinkInfo> appLinkInfos = new ArrayList<>(userState.mAppLinkInfoList);
                    return appLinkInfos;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerAppLinkInfo(String tiasId, AppLinkInfo appLinkInfo, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "registerAppLinkInfo: " + appLinkInfo);
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInteractiveAppState iAppState = userState.mIAppMap.get(tiasId);
                    if (iAppState == null) {
                        Slogf.e(TAG, "failed to registerAppLinkInfo - unknown TIAS id "
                                + tiasId);
                        return;
                    }
                    ComponentName componentName = iAppState.mInfo.getComponent();
                    ServiceState serviceState = userState.mServiceStateMap.get(componentName);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                componentName, tiasId, resolvedUserId);
                        serviceState.addPendingAppLink(appLinkInfo, true);
                        userState.mServiceStateMap.put(componentName, serviceState);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    } else if (serviceState.mService != null) {
                        serviceState.mService.registerAppLinkInfo(appLinkInfo);
                    } else {
                        serviceState.addPendingAppLink(appLinkInfo, true);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    }
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in registerAppLinkInfo", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unregisterAppLinkInfo(String tiasId, AppLinkInfo appLinkInfo, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "unregisterAppLinkInfo: " + appLinkInfo);
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInteractiveAppState iAppState = userState.mIAppMap.get(tiasId);
                    if (iAppState == null) {
                        Slogf.e(TAG, "failed to unregisterAppLinkInfo - unknown TIAS id "
                                + tiasId);
                        return;
                    }
                    ComponentName componentName = iAppState.mInfo.getComponent();
                    ServiceState serviceState = userState.mServiceStateMap.get(componentName);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                componentName, tiasId, resolvedUserId);
                        serviceState.addPendingAppLink(appLinkInfo, false);
                        userState.mServiceStateMap.put(componentName, serviceState);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    } else if (serviceState.mService != null) {
                        serviceState.mService.unregisterAppLinkInfo(appLinkInfo);
                    } else {
                        serviceState.addPendingAppLink(appLinkInfo, false);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    }
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in unregisterAppLinkInfo", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendAppLinkCommand(String tiasId, Bundle command, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "sendAppLinkCommand");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInteractiveAppState iAppState = userState.mIAppMap.get(tiasId);
                    if (iAppState == null) {
                        Slogf.e(TAG, "failed to sendAppLinkCommand - unknown TIAS id "
                                + tiasId);
                        return;
                    }
                    ComponentName componentName = iAppState.mInfo.getComponent();
                    ServiceState serviceState = userState.mServiceStateMap.get(componentName);
                    if (serviceState == null) {
                        serviceState = new ServiceState(
                                componentName, tiasId, resolvedUserId);
                        serviceState.addPendingAppLinkCommand(command);
                        userState.mServiceStateMap.put(componentName, serviceState);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    } else if (serviceState.mService != null) {
                        serviceState.mService.sendAppLinkCommand(command);
                    } else {
                        serviceState.addPendingAppLinkCommand(command);
                        updateServiceConnectionLocked(componentName, resolvedUserId);
                    }
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in sendAppLinkCommand", e);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createSession(
                final ITvInteractiveAppClient client, final String iAppServiceId, int type,
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
                    TvInteractiveAppState iAppState = userState.mIAppMap.get(iAppServiceId);
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
                    releaseAdSessionLocked(sessionToken, callingUid, resolvedUserId);
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
        public void notifyTrackSelected(IBinder sessionToken, int type, String trackId,
                int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTrackSelected(sessionToken=" + sessionToken
                        + ", type=" + type + ", trackId=" + trackId + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "notifyTrackSelected");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyTrackSelected(type, trackId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTrackSelected", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTracksChanged(IBinder sessionToken, List<TvTrackInfo> tracks,
                int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTracksChanged(sessionToken=" + sessionToken
                        + ", tracks=" + tracks + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "notifyTracksChanged");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyTracksChanged(tracks);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTracksChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyVideoAvailable(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyVideoAvailable");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyVideoAvailable();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyVideoAvailable", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyVideoUnavailable(IBinder sessionToken, int reason, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyVideoUnavailable");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyVideoUnavailable(reason);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyVideoUnavailable", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyContentAllowed(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyContentAllowed");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyContentAllowed();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyContentAllowed", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyContentBlocked(IBinder sessionToken, String rating, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyContentBlocked");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyContentBlocked(rating);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyContentBlocked", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifySignalStrength(IBinder sessionToken, int strength, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifySignalStrength");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifySignalStrength(strength);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifySignalStrength", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTvMessage(IBinder sessionToken, int type, Bundle data, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyTvMessage");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyTvMessage(type, data);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTvMessage", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }


        @Override
        public void notifyRecordingStarted(IBinder sessionToken, String recordingId,
                String requestId, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyRecordingStarted");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingStarted(
                                recordingId, requestId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingStarted", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingStopped(IBinder sessionToken, String recordingId, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyRecordingStopped");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingStopped(recordingId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingStopped", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startInteractiveApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#start(userId=%d)", userId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "startInteractiveApp");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).startInteractiveApp();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in start", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void stopInteractiveApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#stop(userId=%d)", userId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "stopInteractiveApp");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).stopInteractiveApp();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in stop", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void resetInteractiveApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#reset(userId=%d)", userId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "resetInteractiveApp");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).resetInteractiveApp();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in reset", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createBiInteractiveApp(
                IBinder sessionToken, Uri biIAppUri, Bundle params, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "createBiInteractiveApp(biIAppUri=%s,params=%s)", biIAppUri, params);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createBiInteractiveApp");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).createBiInteractiveApp(
                                biIAppUri, params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in createBiInteractiveApp", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void destroyBiInteractiveApp(IBinder sessionToken, String biIAppId, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "destroyBiInteractiveApp(biIAppId=%s)", biIAppId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "destroyBiInteractiveApp");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).destroyBiInteractiveApp(biIAppId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in destroyBiInteractiveApp", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setTeletextAppEnabled(IBinder sessionToken, boolean enable, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "setTeletextAppEnabled(enable=%d)", enable);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setTeletextAppEnabled");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).setTeletextAppEnabled(enable);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in setTeletextAppEnabled", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendCurrentVideoBounds(IBinder sessionToken, Rect bounds, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendCurrentVideoBounds(bounds=%s)", bounds.toString());
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendCurrentVideoBounds");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendCurrentVideoBounds(bounds);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendCurrentVideoBounds", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendCurrentChannelUri(IBinder sessionToken, Uri channelUri, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendCurrentChannelUri(channelUri=%s)", channelUri.toString());
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendCurrentChannelUri");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendCurrentChannelUri(channelUri);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendCurrentChannelUri", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendCurrentChannelLcn(IBinder sessionToken, int lcn, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendCurrentChannelLcn(lcn=%d)", lcn);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendCurrentChannelLcn");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendCurrentChannelLcn(lcn);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendCurrentChannelLcn", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendStreamVolume(IBinder sessionToken, float volume, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendStreamVolume(volume=%f)", volume);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendStreamVolume");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendStreamVolume(volume);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendStreamVolume", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendTrackInfoList(IBinder sessionToken, List<TvTrackInfo> tracks, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendTrackInfoList(tracks=%s)", tracks.toString());
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendTrackInfoList");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendTrackInfoList(tracks);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendTrackInfoList", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendCurrentTvInputId(IBinder sessionToken, String inputId, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendCurrentTvInputId(inputId=%s)", inputId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendCurrentTvInputId");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendCurrentTvInputId(inputId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendCurrentTvInputId", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendTimeShiftMode(IBinder sessionToken, int mode, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendTimeShiftMode(mode=%d)", mode);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendTimeShiftMode");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendTimeShiftMode(mode);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendTimeShiftMode", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendAvailableSpeeds(IBinder sessionToken, float[] speeds, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendAvailableSpeeds(speeds=%s)", Arrays.toString(speeds));
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendAvailableSpeeds");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendAvailableSpeeds(speeds);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendAvailableSpeeds", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendTvRecordingInfo(IBinder sessionToken, TvRecordingInfo recordingInfo,
                int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendTvRecordingInfo(recordingInfo=%s)", recordingInfo);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendTvRecordingInfo");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendTvRecordingInfo(recordingInfo);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendTvRecordingInfo", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendTvRecordingInfoList(IBinder sessionToken,
                List<TvRecordingInfo> recordingInfoList, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendTvRecordingInfoList(type=%s)", recordingInfoList);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendTvRecordingInfoList");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendTvRecordingInfoList(recordingInfoList);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendTvRecordingInfoList", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendSigningResult(
                IBinder sessionToken, String signingId, byte[] result, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "sendSigningResult(signingId=%s)", signingId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendSigningResult");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).sendSigningResult(signingId, result);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in sendSigningResult", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyError(IBinder sessionToken, String errMsg, Bundle params, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyError(errMsg=%s)", errMsg);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId =
                    resolveCallingUserId(Binder.getCallingPid(), callingUid, userId, "notifyError");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyError(errMsg, params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyError", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTimeShiftPlaybackParams(
                IBinder sessionToken, PlaybackParams params, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTimeShiftPlaybackParams(params=%s)", params);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId, "notifyTimeShiftPlaybackParams");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyTimeShiftPlaybackParams(params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTimeShiftPlaybackParams", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTimeShiftStatusChanged(
                IBinder sessionToken, String inputId, int status, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTimeShiftStatusChanged(inputId=%s, status=%d)",
                        inputId, status);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId, "notifyTimeShiftStatusChanged");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyTimeShiftStatusChanged(
                                inputId, status);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTimeShiftStatusChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTimeShiftStartPositionChanged(
                IBinder sessionToken, String inputId, long timeMs, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTimeShiftStartPositionChanged(inputId=%s, timeMs=%d)",
                        inputId, timeMs);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyTimeShiftStartPositionChanged");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyTimeShiftStartPositionChanged(
                                inputId, timeMs);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTimeShiftStartPositionChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTimeShiftCurrentPositionChanged(
                IBinder sessionToken, String inputId, long timeMs, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyTimeShiftCurrentPositionChanged(inputId=%s, timeMs=%d)",
                        inputId, timeMs);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyTimeShiftCurrentPositionChanged");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyTimeShiftCurrentPositionChanged(
                                inputId, timeMs);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyTimeShiftCurrentPositionChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingConnectionFailed(
                IBinder sessionToken, String recordingId, String inputId, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyRecordingConnectionFailed(recordingId=%s, inputId=%s)",
                        recordingId, inputId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyRecordingConnectionFailed");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingConnectionFailed(
                                recordingId, inputId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingConnectionFailed", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingDisconnected(
                IBinder sessionToken, String recordingId, String inputId, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyRecordingDisconnected(recordingId=%s, inputId=%s)",
                        recordingId, inputId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyRecordingDisconnected");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingDisconnected(
                                recordingId, inputId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingDisconnected", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingTuned(
                IBinder sessionToken, String recordingId, Uri channelUri, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyRecordingTuned(recordingId=%s, channelUri=%s)",
                        recordingId, channelUri.toString());
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyRecordingTuned");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingTuned(
                                recordingId, channelUri);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingTuned", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingError(
                IBinder sessionToken, String recordingId, int err, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyRecordingError(recordingId=%s, err=%d)",
                        recordingId, err);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyRecordingError");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingError(
                                recordingId, err);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingError", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyRecordingScheduled(
                IBinder sessionToken, String recordingId, String requestId, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "notifyRecordingScheduled(recordingId=%s, requestId=%s)",
                        recordingId, requestId);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(
                    Binder.getCallingPid(), callingUid, userId,
                    "notifyRecordingScheduled");
            SessionState sessionState = null;
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        sessionState =
                                getSessionStateLocked(sessionToken, callingUid, resolvedUserId);
                        getSessionLocked(sessionState).notifyRecordingScheduled(
                                recordingId, requestId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyRecordingScheduled", e);
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
                    // surface is not used in TvInteractiveAppManagerService.
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
        public void notifyAdResponse(IBinder sessionToken, AdResponse response, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyAdResponse");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyAdResponse(response);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyAdResponse", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyAdBufferConsumed(
                IBinder sessionToken, AdBuffer buffer, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "notifyAdBufferConsumed");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyAdBufferConsumed(buffer);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slogf.e(TAG, "error in notifyAdBufferConsumed", e);
                    } finally {
                        if (buffer != null) {
                            buffer.getSharedMemory().close();
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerCallback(final ITvInteractiveAppManagerCallback callback, int userId) {
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
        public void unregisterCallback(ITvInteractiveAppManagerCallback callback, int userId) {
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
                    } catch (RemoteException | SessionNotFoundException e) {
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
                    } catch (RemoteException | SessionNotFoundException e) {
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
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in removeMediaView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendSessionTokenToClientLocked(
            ITvInteractiveAppClient client, String iAppServiceId, IBinder sessionToken,
            InputChannel channel, int seq) {
        try {
            client.onSessionCreated(iAppServiceId, sessionToken, channel, seq);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in onSessionCreated", e);
        }
    }

    @GuardedBy("mLock")
    private void sendAdSessionTokenToClientLocked(
            ITvAdClient client, String serviceId, IBinder sessionToken,
            InputChannel channel, int seq) {
        try {
            client.onSessionCreated(serviceId, sessionToken, channel, seq);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in onSessionCreated", e);
        }
    }

    @GuardedBy("mLock")
    private boolean createSessionInternalLocked(
            ITvInteractiveAppService service, IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        SessionState sessionState = userState.mSessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slogf.d(TAG, "createSessionInternalLocked(iAppServiceId="
                    + sessionState.mIAppServiceId + ")");
        }
        InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvInteractiveAppSessionCallback callback = new SessionCallback(sessionState, channels);

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
    private boolean createAdSessionInternalLocked(
            ITvAdService service, IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        AdSessionState sessionState = userState.mAdSessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slogf.d(TAG, "createAdSessionInternalLocked(iAppServiceId="
                    + sessionState.mAdServiceId + ")");
        }
        InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvAdSessionCallback callback = new AdSessionCallback(sessionState, channels);

        boolean created = true;
        // Create a session. When failed, send a null token immediately.
        try {
            service.createSession(
                    channels[1], callback, sessionState.mAdServiceId, sessionState.mType);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in createSession", e);
            sendAdSessionTokenToClientLocked(sessionState.mClient, sessionState.mAdServiceId, null,
                    null, sessionState.mSeq);
            created = false;
        }
        channels[1].dispose();
        return created;
    }

    @GuardedBy("mLock")
    @Nullable
    private AdSessionState releaseAdSessionLocked(
            IBinder sessionToken, int callingUid, int userId) {
        AdSessionState sessionState = null;
        try {
            sessionState = getAdSessionStateLocked(sessionToken, callingUid, userId);
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
        removeAdSessionStateLocked(sessionToken, userId);
        return sessionState;
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
    private void removeAdSessionStateLocked(IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);

        // Remove the session state from the global session state map of the current user.
        AdSessionState sessionState = userState.mAdSessionStateMap.remove(sessionToken);

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

        AdServiceState serviceState = userState.mAdServiceStateMap.get(sessionState.mComponent);
        if (serviceState != null) {
            serviceState.mSessionTokens.remove(sessionToken);
        }
        updateAdServiceConnectionLocked(sessionState.mComponent, userId);
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
    private void abortPendingCreateAdSessionRequestsLocked(AdServiceState serviceState,
            String serviceId, int userId) {
        // Let clients know the create session requests are failed.
        UserState userState = getOrCreateUserStateLocked(userId);
        List<AdSessionState> sessionsToAbort = new ArrayList<>();
        for (IBinder sessionToken : serviceState.mSessionTokens) {
            AdSessionState sessionState = userState.mAdSessionStateMap.get(sessionToken);
            if (sessionState.mSession == null
                    && (serviceState == null
                    || sessionState.mAdServiceId.equals(serviceId))) {
                sessionsToAbort.add(sessionState);
            }
        }
        for (AdSessionState sessionState : sessionsToAbort) {
            removeAdSessionStateLocked(sessionState.mSessionToken, sessionState.mUserId);
            sendAdSessionTokenToClientLocked(sessionState.mClient,
                    sessionState.mAdServiceId, null, null, sessionState.mSeq);
        }
        updateAdServiceConnectionLocked(serviceState.mComponent, userId);
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

        boolean shouldBind = (!serviceState.mSessionTokens.isEmpty())
                || (!serviceState.mPendingAppLinkInfo.isEmpty())
                || (!serviceState.mPendingAppLinkCommand.isEmpty());

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

            Intent i =
                    new Intent(TvInteractiveAppService.SERVICE_INTERFACE).setComponent(component);
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

    @GuardedBy("mLock")
    private void updateAdServiceConnectionLocked(ComponentName component, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        AdServiceState serviceState = userState.mAdServiceStateMap.get(component);
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

        boolean shouldBind = (!serviceState.mSessionTokens.isEmpty())
                || (!serviceState.mPendingAppLinkCommand.isEmpty());

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

            Intent i = new Intent(TvAdService.SERVICE_INTERFACE).setComponent(component);
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
            userState.mAdServiceStateMap.remove(component);
        }
    }

    private static final class UserState {
        private final int mUserId;
        // A mapping from the TV AD service ID to its TvAdState.
        private Map<String, TvAdState> mAdMap = new HashMap<>();
        // A mapping from the name of a TV Interactive App service to its state.
        private final Map<ComponentName, AdServiceState> mAdServiceStateMap = new HashMap<>();
        // A mapping from the token of a TV Interactive App session to its state.
        private final Map<IBinder, AdSessionState> mAdSessionStateMap = new HashMap<>();
        // A mapping from the TV Interactive App ID to its TvInteractiveAppState.
        private Map<String, TvInteractiveAppState> mIAppMap = new HashMap<>();
        // A mapping from the token of a client to its state.
        private final Map<IBinder, ClientState> mClientStateMap = new HashMap<>();
        // A mapping from the name of a TV Interactive App service to its state.
        private final Map<ComponentName, ServiceState> mServiceStateMap = new HashMap<>();
        // A mapping from the token of a TV Interactive App session to its state.
        private final Map<IBinder, SessionState> mSessionStateMap = new HashMap<>();

        // A set of all TV Interactive App service packages.
        private final Set<String> mPackageSet = new HashSet<>();
        // A list of all app link infos.
        private final List<AppLinkInfo> mAppLinkInfoList = new ArrayList<>();

        // A list of callbacks.
        private final RemoteCallbackList<ITvInteractiveAppManagerCallback> mCallbacks =
                new RemoteCallbackList<>();

        private UserState(int userId) {
            mUserId = userId;
        }
    }

    private static final class TvInteractiveAppState {
        private String mIAppServiceId;
        private ComponentName mComponentName;
        private TvInteractiveAppServiceInfo mInfo;
        private int mUid;
        private int mIAppNumber;
    }

    private static final class TvAdState {
        private String mAdServiceId;
        private ComponentName mComponentName;
        private TvAdServiceInfo mInfo;
        private int mUid;
        private int mAdNumber;
    }

    private final class SessionState implements IBinder.DeathRecipient {
        // TODO: rename SessionState and reorganize classes / methods of this file
        private final IBinder mSessionToken;
        private ITvInteractiveAppSession mSession;
        private final String mIAppServiceId;
        private final int mType;
        private final ITvInteractiveAppClient mClient;
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
                ComponentName componentName, ITvInteractiveAppClient client, int seq,
                int callingUid, int callingPid, int userId) {
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
            synchronized (mLock) {
                mSession = null;
                clearSessionAndNotifyClientLocked(this);
            }
        }
    }

    private final class AdSessionState implements IBinder.DeathRecipient {
        private final IBinder mSessionToken;
        private ITvAdSession mSession;
        private final String mAdServiceId;

        private final String mType;
        private final ITvAdClient mClient;
        private final int mSeq;
        private final ComponentName mComponent;

        // The UID of the application that created the session.
        // The application is usually the TV app.
        private final int mCallingUid;

        // The PID of the application that created the session.
        // The application is usually the TV app.
        private final int mCallingPid;

        private final int mUserId;

        private AdSessionState(IBinder sessionToken, String serviceId, String type,
                ComponentName componentName, ITvAdClient client, int seq,
                int callingUid, int callingPid, int userId) {
            mSessionToken = sessionToken;
            mAdServiceId = serviceId;
            mType = type;
            mComponent = componentName;
            mClient = client;
            mSeq = seq;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            mUserId = userId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSession = null;
                clearAdSessionAndNotifyClientLocked(this);
            }
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
        private final String mIAppServiceId;
        private final List<Pair<AppLinkInfo, Boolean>> mPendingAppLinkInfo = new ArrayList<>();
        private final List<Bundle> mPendingAppLinkCommand = new ArrayList<>();

        private ITvInteractiveAppService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private ServiceState(ComponentName component, String tias, int userId) {
            mComponent = component;
            mConnection = new InteractiveAppServiceConnection(component, userId);
            mIAppServiceId = tias;
        }

        private void addPendingAppLink(AppLinkInfo info, boolean register) {
            mPendingAppLinkInfo.add(Pair.create(info, register));
        }

        private void addPendingAppLinkCommand(Bundle command) {
            mPendingAppLinkCommand.add(command);
        }
    }

    private final class AdServiceState {
        private final List<IBinder> mSessionTokens = new ArrayList<>();
        private final ServiceConnection mConnection;
        private final ComponentName mComponent;
        private final String mAdServiceId;
        private final List<Bundle> mPendingAppLinkCommand = new ArrayList<>();

        private ITvAdService mService;
        private AdServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private AdServiceState(ComponentName component, String tasId, int userId) {
            mComponent = component;
            mConnection = new AdServiceConnection(component, userId);
            mAdServiceId = tasId;
        }

        private void addPendingAppLinkCommand(Bundle command) {
            mPendingAppLinkCommand.add(command);
        }
    }

    private final class InteractiveAppServiceConnection implements ServiceConnection {
        private final ComponentName mComponent;
        private final int mUserId;

        private InteractiveAppServiceConnection(ComponentName component, int userId) {
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
                serviceState.mService = ITvInteractiveAppService.Stub.asInterface(service);

                // Register a callback, if we need to.
                if (serviceState.mCallback == null) {
                    serviceState.mCallback = new ServiceCallback(mComponent, mUserId);
                    try {
                        serviceState.mService.registerCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in registerCallback", e);
                    }
                }

                if (!serviceState.mPendingAppLinkInfo.isEmpty()) {
                    for (Iterator<Pair<AppLinkInfo, Boolean>> it =
                            serviceState.mPendingAppLinkInfo.iterator();
                            it.hasNext(); ) {
                        Pair<AppLinkInfo, Boolean> appLinkInfoPair = it.next();
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            if (appLinkInfoPair.second) {
                                serviceState.mService.registerAppLinkInfo(appLinkInfoPair.first);
                            } else {
                                serviceState.mService.unregisterAppLinkInfo(appLinkInfoPair.first);
                            }
                            it.remove();
                        } catch (RemoteException e) {
                            Slogf.e(TAG, "error in notifyAppLinkInfo(" + appLinkInfoPair
                                    + ") when onServiceConnected", e);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                }

                if (!serviceState.mPendingAppLinkCommand.isEmpty()) {
                    for (Iterator<Bundle> it = serviceState.mPendingAppLinkCommand.iterator();
                            it.hasNext(); ) {
                        Bundle command = it.next();
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            serviceState.mService.sendAppLinkCommand(command);
                            it.remove();
                        } catch (RemoteException e) {
                            Slogf.e(TAG, "error in sendAppLinkCommand(" + command
                                    + ") when onServiceConnected", e);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
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

    private final class AdServiceConnection implements ServiceConnection {
        private final ComponentName mComponent;
        private final int mUserId;

        private AdServiceConnection(ComponentName component, int userId) {
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
                AdServiceState serviceState = userState.mAdServiceStateMap.get(mComponent);
                serviceState.mService = ITvAdService.Stub.asInterface(service);

                // Register a callback, if we need to.
                if (serviceState.mCallback == null) {
                    serviceState.mCallback = new AdServiceCallback(mComponent, mUserId);
                    try {
                        serviceState.mService.registerCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in registerCallback", e);
                    }
                }

                if (!serviceState.mPendingAppLinkCommand.isEmpty()) {
                    for (Iterator<Bundle> it = serviceState.mPendingAppLinkCommand.iterator();
                            it.hasNext(); ) {
                        Bundle command = it.next();
                        final long identity = Binder.clearCallingIdentity();
                        try {
                            serviceState.mService.sendAppLinkCommand(command);
                            it.remove();
                        } catch (RemoteException e) {
                            Slogf.e(TAG, "error in sendAppLinkCommand(" + command
                                    + ") when onServiceConnected", e);
                        } finally {
                            Binder.restoreCallingIdentity(identity);
                        }
                    }
                }

                List<IBinder> tokensToBeRemoved = new ArrayList<>();

                // And create sessions, if any.
                for (IBinder sessionToken : serviceState.mSessionTokens) {
                    if (!createAdSessionInternalLocked(
                            serviceState.mService, sessionToken, mUserId)) {
                        tokensToBeRemoved.add(sessionToken);
                    }
                }

                for (IBinder sessionToken : tokensToBeRemoved) {
                    removeAdSessionStateLocked(sessionToken, mUserId);
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
                AdServiceState serviceState = userState.mAdServiceStateMap.get(mComponent);
                if (serviceState != null) {
                    serviceState.mReconnecting = true;
                    serviceState.mBound = false;
                    serviceState.mService = null;
                    serviceState.mCallback = null;

                    abortPendingCreateAdSessionRequestsLocked(serviceState, null, mUserId);
                }
            }
        }
    }


    private final class ServiceCallback extends ITvInteractiveAppServiceCallback.Stub {
        private final ComponentName mComponent;
        private final int mUserId;

        ServiceCallback(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }

        @Override
        public void onStateChanged(int type, int state, int error) {
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
                    String iAppServiceId = serviceState.mIAppServiceId;
                    UserState userState = getUserStateLocked(mUserId);
                    notifyStateChangedLocked(userState, iAppServiceId, type, state, error);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }


    private final class AdServiceCallback extends ITvAdServiceCallback.Stub {
        private final ComponentName mComponent;
        private final int mUserId;

        AdServiceCallback(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }
    }

    private final class SessionCallback extends ITvInteractiveAppSessionCallback.Stub {
        private final SessionState mSessionState;
        private final InputChannel[] mInputChannels;

        SessionCallback(SessionState sessionState, InputChannel[] channels) {
            mSessionState = sessionState;
            mInputChannels = channels;
        }

        @Override
        public void onSessionCreated(ITvInteractiveAppSession session) {
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
        public void onRemoveBroadcastInfo(int requestId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRemoveBroadcastInfo (requestId=" + requestId + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRemoveBroadcastInfo(requestId, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRemoveBroadcastInfo", e);
                }
            }
        }

        @Override
        public void onCommandRequest(
                @TvInteractiveAppService.PlaybackCommandType String cmdType,
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
        public void onTimeShiftCommandRequest(
                @TvInteractiveAppService.TimeShiftCommandType String cmdType,
                Bundle parameters) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onTimeShiftCommandRequest (cmdType=" + cmdType
                            + ", parameters=" + parameters.toString() + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onTimeShiftCommandRequest(
                            cmdType, parameters, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onTimeShiftCommandRequest", e);
                }
            }
        }

        @Override
        public void onSetVideoBounds(Rect rect) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onSetVideoBounds(rect=" + rect + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onSetVideoBounds(rect, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onSetVideoBounds", e);
                }
            }
        }

        @Override
        public void onRequestCurrentVideoBounds() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestCurrentVideoBounds");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestCurrentVideoBounds(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestCurrentVideoBounds", e);
                }
            }
        }

        @Override
        public void onRequestCurrentChannelUri() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestCurrentChannelUri");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestCurrentChannelUri(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestCurrentChannelUri", e);
                }
            }
        }

        @Override
        public void onRequestCurrentChannelLcn() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestCurrentChannelLcn");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestCurrentChannelLcn(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestCurrentChannelLcn", e);
                }
            }
        }

        @Override
        public void onRequestStreamVolume() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestStreamVolume");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestStreamVolume(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestStreamVolume", e);
                }
            }
        }

        @Override
        public void onRequestTrackInfoList() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestTrackInfoList");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestTrackInfoList(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestTrackInfoList", e);
                }
            }
        }

        @Override
        public void onRequestCurrentTvInputId() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestCurrentTvInputId");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestCurrentTvInputId(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestCurrentTvInputId", e);
                }
            }
        }

        @Override
        public void onRequestTimeShiftMode() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestTimeShiftMode");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestTimeShiftMode(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestTimeShiftMode", e);
                }
            }
        }

        @Override
        public void onRequestAvailableSpeeds() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestAvailableSpeeds");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestAvailableSpeeds(mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestAvailableSpeeds", e);
                }
            }
        }

        @Override
        public void onRequestStartRecording(String requestId, Uri programUri) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestStartRecording: " + programUri);
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestStartRecording(
                            requestId, programUri, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestStartRecording", e);
                }
            }
        }

        @Override
        public void onRequestStopRecording(String recordingId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestStopRecording");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestStopRecording(recordingId, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestStopRecording", e);
                }
            }
        }

        @Override
        public void onRequestScheduleRecording(
                String requestId, String inputId, Uri channelUri, Uri programUri, Bundle params) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestScheduleRecording");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestScheduleRecording(
                            requestId, inputId, channelUri, programUri, params, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestScheduleRecording", e);
                }
            }
        }

        @Override
        public void onRequestScheduleRecording2(String requestId, String inputId, Uri channelUri,
                long start, long duration, int repeat, Bundle params) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestScheduleRecording2");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestScheduleRecording2(requestId, inputId,
                            channelUri, start, duration, repeat, params, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestScheduleRecording2", e);
                }
            }
        }

        @Override
        public void onSetTvRecordingInfo(String recordingId, TvRecordingInfo recordingInfo) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onSetTvRecordingInfo");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onSetTvRecordingInfo(recordingId, recordingInfo,
                            mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onSetTvRecordingInfo", e);
                }
            }
        }

        @Override
        public void onRequestTvRecordingInfo(String recordingId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestTvRecordingInfo");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestTvRecordingInfo(recordingId, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestTvRecordingInfo", e);
                }
            }
        }

        @Override
        public void onRequestTvRecordingInfoList(int type) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestTvRecordingInfoList");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestTvRecordingInfoList(type, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestTvRecordingInfoList", e);
                }
            }
        }


        @Override
        public void onRequestSigning(String id, String algorithm, String alias, byte[] data) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onRequestSigning");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onRequestSigning(
                            id, algorithm, alias, data, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onRequestSigning", e);
                }
            }
        }

        @Override
        public void onAdRequest(AdRequest request) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onAdRequest (id=" + request.getId() + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onAdRequest(request, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onAdRequest", e);
                }
            }
        }

        @Override
        public void onSessionStateChanged(int state, int err) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onSessionStateChanged (state=" + state + ", err=" + err + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onSessionStateChanged(state, err, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onSessionStateChanged", e);
                }
            }
        }

        @Override
        public void onBiInteractiveAppCreated(Uri biIAppUri, String biIAppId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onBiInteractiveAppCreated (biIAppUri=" + biIAppUri
                            + ", biIAppId=" + biIAppId + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onBiInteractiveAppCreated(
                            biIAppUri, biIAppId, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onBiInteractiveAppCreated", e);
                }
            }
        }

        @Override
        public void onTeletextAppStateChanged(int state) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onTeletextAppStateChanged (state=" + state + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onTeletextAppStateChanged(state, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onTeletextAppStateChanged", e);
                }
            }
        }

        @Override
        public void onAdBufferReady(AdBuffer buffer) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slogf.d(TAG, "onAdBufferReady(buffer=" + buffer + ")");
                }
                if (mSessionState.mSession == null || mSessionState.mClient == null) {
                    return;
                }
                try {
                    mSessionState.mClient.onAdBufferReady(buffer, mSessionState.mSeq);
                } catch (RemoteException e) {
                    Slogf.e(TAG, "error in onAdBuffer", e);
                } finally {
                    if (buffer != null) {
                        buffer.getSharedMemory().close();
                    }
                }
            }
        }

        @GuardedBy("mLock")
        private boolean addSessionTokenToClientStateLocked(ITvInteractiveAppSession session) {
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

    private final class AdSessionCallback extends ITvAdSessionCallback.Stub {
        private final AdSessionState mSessionState;
        private final InputChannel[] mInputChannels;

        AdSessionCallback(AdSessionState sessionState, InputChannel[] channels) {
            mSessionState = sessionState;
            mInputChannels = channels;
        }

        @Override
        public void onSessionCreated(ITvAdSession session) {
            if (DEBUG) {
                Slogf.d(TAG, "onSessionCreated(adServiceId="
                        + mSessionState.mAdServiceId + ")");
            }
            synchronized (mLock) {
                mSessionState.mSession = session;
                if (session != null && addAdSessionTokenToClientStateLocked(session)) {
                    sendAdSessionTokenToClientLocked(
                            mSessionState.mClient,
                            mSessionState.mAdServiceId,
                            mSessionState.mSessionToken,
                            mInputChannels[0],
                            mSessionState.mSeq);
                } else {
                    removeAdSessionStateLocked(mSessionState.mSessionToken, mSessionState.mUserId);
                    sendAdSessionTokenToClientLocked(mSessionState.mClient,
                            mSessionState.mAdServiceId, null, null, mSessionState.mSeq);
                }
                mInputChannels[0].dispose();
            }
        }

        @GuardedBy("mLock")
        private boolean addAdSessionTokenToClientStateLocked(ITvAdSession session) {
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
