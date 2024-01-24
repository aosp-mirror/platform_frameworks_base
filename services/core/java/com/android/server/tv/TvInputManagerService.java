/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.tv;

import static android.media.AudioManager.DEVICE_NONE;
import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.content.AttributionSource;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.graphics.Rect;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.AudioPresentation;
import android.media.PlaybackParams;
import android.media.tv.AdBuffer;
import android.media.tv.AdRequest;
import android.media.tv.AdResponse;
import android.media.tv.AitInfo;
import android.media.tv.BroadcastInfoRequest;
import android.media.tv.BroadcastInfoResponse;
import android.media.tv.DvbDeviceInfo;
import android.media.tv.ITvInputClient;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.ITvInputManager;
import android.media.tv.ITvInputManagerCallback;
import android.media.tv.ITvInputService;
import android.media.tv.ITvInputServiceCallback;
import android.media.tv.ITvInputSession;
import android.media.tv.ITvInputSessionCallback;
import android.media.tv.TunedInfo;
import android.media.tv.TvContentRating;
import android.media.tv.TvContentRatingSystemInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvStreamConfig;
import android.media.tv.TvTrackInfo;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.Surface;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.PackageMonitor;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;
import com.android.server.SystemService;

import dalvik.annotation.optimization.NeverCompile;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** This class provides a system service that manages television inputs. */
public final class TvInputManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "TvInputManagerService";
    private static final String DVB_DIRECTORY = "/dev/dvb";
    private static final int APP_TAG_SELF = TunedInfo.APP_TAG_SELF;
    private static final String PERMISSION_ACCESS_WATCHED_PROGRAMS =
            "com.android.providers.tv.permission.ACCESS_WATCHED_PROGRAMS";
    private static final long UPDATE_HARDWARE_TIS_BINDING_DELAY_IN_MILLIS = 10 * 1000; // 10 seconds

    // There are two different formats of DVB frontend devices. One is /dev/dvb%d.frontend%d,
    // another one is /dev/dvb/adapter%d/frontend%d. Followings are the patterns for selecting the
    // DVB frontend devices from the list of files in the /dev and /dev/dvb/adapter%d directory.
    private static final Pattern sFrontEndDevicePattern =
            Pattern.compile("^dvb([0-9]+)\\.frontend([0-9]+)$");
    private static final Pattern sAdapterDirPattern =
            Pattern.compile("^adapter([0-9]+)$");
    private static final Pattern sFrontEndInAdapterDirPattern =
            Pattern.compile("^frontend([0-9]+)$");

    private final Context mContext;
    private final TvInputHardwareManager mTvInputHardwareManager;
    private final UserManager mUserManager;

    // A global lock.
    private final Object mLock = new Object();

    // ID of the current user.
    @GuardedBy("mLock")
    private int mCurrentUserId = UserHandle.USER_SYSTEM;
    // ID of the current on-screen input.
    @GuardedBy("mLock")
    private String mOnScreenInputId = null;
    // SessionState of the currently active on-screen TIS session.
    @GuardedBy("mLock")
    private SessionState mOnScreenSessionState = null;
    // IDs of the running profiles. Their parent user ID should be mCurrentUserId.
    @GuardedBy("mLock")
    private final Set<Integer> mRunningProfiles = new HashSet<>();

    // A map from user id to UserState.
    @GuardedBy("mLock")
    private final SparseArray<UserState> mUserStates = new SparseArray<>();

    // A map from session id to session state saved in userstate
    @GuardedBy("mLock")
    private final Map<String, SessionState> mSessionIdToSessionStateMap = new HashMap<>();

    private final MessageHandler mMessageHandler;

    private final ActivityManager mActivityManager;

    private boolean mExternalInputLoggingDisplayNameFilterEnabled = false;
    private final HashSet<String> mExternalInputLoggingDeviceOnScreenDisplayNames =
            new HashSet<String>();
    private final List<String> mExternalInputLoggingDeviceBrandNames = new ArrayList<String>();

    public TvInputManagerService(Context context) {
        super(context);

        mContext = context;
        mMessageHandler =
                new MessageHandler(mContext.getContentResolver(), IoThread.get().getLooper());
        mTvInputHardwareManager = new TvInputHardwareManager(context, new HardwareListener());

        mActivityManager =
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        mUserManager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);

        synchronized (mLock) {
            getOrCreateUserStateLocked(mCurrentUserId);
        }

        initExternalInputLoggingConfigs();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TV_INPUT_SERVICE, new BinderService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            registerBroadcastReceivers();
        } else if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            synchronized (mLock) {
                buildTvInputListLocked(mCurrentUserId, null);
                buildTvContentRatingSystemListLocked(mCurrentUserId);
            }
        }
        mTvInputHardwareManager.onBootPhase(phase);
    }

    @Override
    public void onUserUnlocking(@NonNull TargetUser user) {
        if (DEBUG) Slog.d(TAG, "onUnlockUser(user=" + user + ")");
        synchronized (mLock) {
            if (mCurrentUserId != user.getUserIdentifier()) {
                return;
            }
            buildTvInputListLocked(mCurrentUserId, null);
            buildTvContentRatingSystemListLocked(mCurrentUserId);
        }
    }

    private void initExternalInputLoggingConfigs() {
        mExternalInputLoggingDisplayNameFilterEnabled = mContext.getResources().getBoolean(
                R.bool.config_tvExternalInputLoggingDisplayNameFilterEnabled);
        if (!mExternalInputLoggingDisplayNameFilterEnabled) {
            return;
        }
        final String[] deviceOnScreenDisplayNames = mContext.getResources().getStringArray(
                R.array.config_tvExternalInputLoggingDeviceOnScreenDisplayNames);
        final String[] deviceBrandNames = mContext.getResources().getStringArray(
                R.array.config_tvExternalInputLoggingDeviceBrandNames);
        mExternalInputLoggingDeviceOnScreenDisplayNames.addAll(
                Arrays.asList(deviceOnScreenDisplayNames));
        mExternalInputLoggingDeviceBrandNames.addAll(Arrays.asList(deviceBrandNames));
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            private void buildTvInputList(String[] packages) {
                int userId = getChangingUserId();
                synchronized (mLock) {
                    if (mCurrentUserId == userId || mRunningProfiles.contains(userId)) {
                        buildTvInputListLocked(userId, packages);
                        buildTvContentRatingSystemListLocked(userId);
                    }
                }
            }

            @Override
            public void onPackageUpdateFinished(String packageName, int uid) {
                if (DEBUG) Slog.d(TAG, "onPackageUpdateFinished(packageName=" + packageName + ")");
                // This callback is invoked when the TV input is reinstalled.
                // In this case, isReplacing() always returns true.
                buildTvInputList(new String[] { packageName });
            }

            @Override
            public void onPackagesAvailable(String[] packages) {
                if (DEBUG) {
                    Slog.d(TAG, "onPackagesAvailable(packages=" + Arrays.toString(packages) + ")");
                }
                // This callback is invoked when the media on which some packages exist become
                // available.
                if (isReplacing()) {
                    buildTvInputList(packages);
                }
            }

            @Override
            public void onPackagesUnavailable(String[] packages) {
                // This callback is invoked when the media on which some packages exist become
                // unavailable.
                if (DEBUG)  {
                    Slog.d(TAG, "onPackagesUnavailable(packages=" + Arrays.toString(packages)
                            + ")");
                }
                if (isReplacing()) {
                    buildTvInputList(packages);
                }
            }

            @Override
            public void onSomePackagesChanged() {
                // TODO: Use finer-grained methods(e.g. onPackageAdded, onPackageRemoved) to manage
                // the TV inputs.
                if (DEBUG) Slog.d(TAG, "onSomePackagesChanged()");
                if (isReplacing()) {
                    if (DEBUG) Slog.d(TAG, "Skipped building TV input list due to replacing");
                    // When the package is updated, buildTvInputListLocked is called in other
                    // methods instead.
                    return;
                }
                buildTvInputList(null);
            }

            @Override
            public boolean onPackageChanged(String packageName, int uid, String[] components) {
                // The input list needs to be updated in any cases, regardless of whether
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

    private static boolean hasHardwarePermission(PackageManager pm, ComponentName component) {
        return pm.checkPermission(android.Manifest.permission.TV_INPUT_HARDWARE,
                component.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }
    @GuardedBy("mLock")
    private void buildTvInputListLocked(int userId, String[] updatedPackages) {
        UserState userState = getOrCreateUserStateLocked(userId);
        userState.packageSet.clear();

        if (DEBUG) Slog.d(TAG, "buildTvInputList");
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServicesAsUser(
                new Intent(TvInputService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                userId);
        List<TvInputInfo> inputList = new ArrayList<>();
        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (!android.Manifest.permission.BIND_TV_INPUT.equals(si.permission)) {
                Slog.w(TAG, "Skipping TV input " + si.name + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TV_INPUT);
                continue;
            }

            ComponentName component = new ComponentName(si.packageName, si.name);
            if (hasHardwarePermission(pm, component)) {
                ServiceState serviceState = userState.serviceStateMap.get(component);
                if (serviceState == null) {
                    // New hardware input found. Create a new ServiceState and connect to the
                    // service to populate the hardware list.
                    serviceState = new ServiceState(component, userId);
                    userState.serviceStateMap.put(component, serviceState);
                    updateServiceConnectionLocked(component, userId);
                } else {
                    inputList.addAll(serviceState.hardwareInputMap.values());
                }
            } else {
                try {
                    TvInputInfo info = new TvInputInfo.Builder(mContext, ri).build();
                    inputList.add(info);
                } catch (Exception e) {
                    Slog.e(TAG, "failed to load TV input " + si.name, e);
                    continue;
                }
            }
            userState.packageSet.add(si.packageName);
        }

        // sort the input list by input id so that TvInputState.inputNumber is stable.
        Collections.sort(inputList, Comparator.comparing(TvInputInfo::getId));
        Map<String, TvInputState> inputMap = new HashMap<>();
        ArrayMap<String, Integer> tisInputCount = new ArrayMap<>(inputMap.size());
        for (TvInputInfo info : inputList) {
            String inputId = info.getId();
            if (DEBUG) {
                Slog.d(TAG, "add " + inputId);
            }
            // Running count of input for each input service
            Integer count = tisInputCount.get(inputId);
            count = count == null ? Integer.valueOf(1) : count + 1;
            tisInputCount.put(inputId, count);
            TvInputState inputState = userState.inputMap.get(inputId);
            if (inputState == null) {
                inputState = new TvInputState();
            }
            inputState.info = info;
            inputState.uid = getInputUid(info);
            inputMap.put(inputId, inputState);
            inputState.inputNumber = count;
        }

        for (String inputId : inputMap.keySet()) {
            if (!userState.inputMap.containsKey(inputId)) {
                notifyInputAddedLocked(userState, inputId);
            } else if (updatedPackages != null) {
                // Notify the package updates
                ComponentName component = inputMap.get(inputId).info.getComponent();
                for (String updatedPackage : updatedPackages) {
                    if (component.getPackageName().equals(updatedPackage)) {
                        updateServiceConnectionLocked(component, userId);
                        notifyInputUpdatedLocked(userState, inputId);
                        break;
                    }
                }
            }
        }

        for (String inputId : userState.inputMap.keySet()) {
            if (!inputMap.containsKey(inputId)) {
                TvInputInfo info = userState.inputMap.get(inputId).info;
                ServiceState serviceState = userState.serviceStateMap.get(info.getComponent());
                if (serviceState != null) {
                    abortPendingCreateSessionRequestsLocked(serviceState, inputId, userId);
                }
                notifyInputRemovedLocked(userState, inputId);
            }
        }

        userState.inputMap.clear();
        userState.inputMap = inputMap;
    }

    private int getInputUid(TvInputInfo info) {
        try {
            return getContext().getPackageManager().getApplicationInfo(
                    info.getServiceInfo().packageName, 0).uid;
        } catch (NameNotFoundException e) {
            Slog.w(TAG, "Unable to get UID for  " + info, e);
            return Process.INVALID_UID;
        }
    }

    @GuardedBy("mLock")
    private void buildTvContentRatingSystemListLocked(int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        userState.contentRatingSystemList.clear();

        final PackageManager pm = mContext.getPackageManager();
        Intent intent = new Intent(TvInputManager.ACTION_QUERY_CONTENT_RATING_SYSTEMS);
        for (ResolveInfo resolveInfo :
                pm.queryBroadcastReceivers(intent, PackageManager.GET_META_DATA)) {
            ActivityInfo receiver = resolveInfo.activityInfo;
            Bundle metaData = receiver.metaData;
            if (metaData == null) {
                continue;
            }

            int xmlResId = metaData.getInt(TvInputManager.META_DATA_CONTENT_RATING_SYSTEMS);
            if (xmlResId == 0) {
                Slog.w(TAG, "Missing meta-data '"
                        + TvInputManager.META_DATA_CONTENT_RATING_SYSTEMS + "' on receiver "
                        + receiver.packageName + "/" + receiver.name);
                continue;
            }
            userState.contentRatingSystemList.add(
                    TvContentRatingSystemInfo.createTvContentRatingSystemInfo(xmlResId,
                            receiver.applicationInfo));
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
        buildTvInputListLocked(userId, null);
        buildTvContentRatingSystemListLocked(userId);
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
            buildTvInputListLocked(userId, null);
            buildTvContentRatingSystemListLocked(userId);
            mMessageHandler
                    .obtainMessage(MessageHandler.MSG_SWITCH_CONTENT_RESOLVER,
                            getContentResolverForUser(userId))
                    .sendToTarget();
        }
    }

    @GuardedBy("mLock")
    private void releaseSessionOfUserLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            return;
        }
        List<SessionState> sessionStatesToRelease = new ArrayList<>();
        for (SessionState sessionState : userState.sessionStateMap.values()) {
            if (sessionState.session != null && !sessionState.isRecordingSession) {
                sessionStatesToRelease.add(sessionState);
            }
        }
        boolean notifyInfoUpdated = false;
        for (SessionState sessionState : sessionStatesToRelease) {
            try {
                sessionState.session.release();
                sessionState.currentChannel = null;
                if (sessionState.isCurrent) {
                    sessionState.isCurrent = false;
                    notifyInfoUpdated = true;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "error in release", e);
            } finally {
                if (notifyInfoUpdated) {
                    notifyCurrentChannelInfosUpdatedLocked(userState);
                }
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
        for (Iterator<ComponentName> it = userState.serviceStateMap.keySet().iterator();
                it.hasNext(); ) {
            ComponentName component = it.next();
            ServiceState serviceState = userState.serviceStateMap.get(component);
            if (serviceState != null && serviceState.sessionTokens.isEmpty()) {
                if (serviceState.callback != null) {
                    try {
                        serviceState.service.unregisterCallback(serviceState.callback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unregisterCallback", e);
                    }
                }
                unbindService(serviceState);
                it.remove();
            }
        }
    }

    @GuardedBy("mLock")
    private void clearSessionAndNotifyClientLocked(SessionState state) {
        if (state.client != null) {
            try {
                state.client.onSessionReleased(state.seq);
            } catch(RemoteException e) {
                Slog.e(TAG, "error in onSessionReleased", e);
            }
        }
        // If there are any other sessions based on this session, they should be released.
        UserState userState = getOrCreateUserStateLocked(state.userId);
        for (SessionState sessionState : userState.sessionStateMap.values()) {
            if (state.sessionToken == sessionState.hardwareSessionToken) {
                releaseSessionLocked(sessionState.sessionToken, Process.SYSTEM_UID, state.userId);
                try {
                    sessionState.client.onSessionReleased(sessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onSessionReleased", e);
                }
            }
        }
        removeSessionStateLocked(state.sessionToken, state.userId);
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState == null) {
                return;
            }
            // Release all created sessions.
            boolean notifyInfoUpdated = false;
            for (SessionState state : userState.sessionStateMap.values()) {
                if (state.session != null) {
                    try {
                        state.session.release();
                        state.currentChannel = null;
                        if (state.isCurrent) {
                            state.isCurrent = false;
                            notifyInfoUpdated = true;
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in release", e);
                    } finally {
                        if (notifyInfoUpdated) {
                            notifyCurrentChannelInfosUpdatedLocked(userState);
                        }
                    }
                }
            }
            userState.sessionStateMap.clear();

            // Unregister all callbacks and unbind all services.
            for (ServiceState serviceState : userState.serviceStateMap.values()) {
                if (serviceState.service != null) {
                    if (serviceState.callback != null) {
                        try {
                            serviceState.service.unregisterCallback(serviceState.callback);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in unregisterCallback", e);
                        }
                    }
                    unbindService(serviceState);
                }
            }
            userState.serviceStateMap.clear();

            // Clear everything else.
            userState.inputMap.clear();
            userState.packageSet.clear();
            userState.contentRatingSystemList.clear();
            userState.clientStateMap.clear();
            userState.mCallbacks.kill();
            userState.mainSessionToken = null;

            mRunningProfiles.remove(userId);
            mUserStates.remove(userId);

            if (userId == mCurrentUserId) {
                switchUser(UserHandle.USER_SYSTEM);
            }
        }
    }

    private ContentResolver getContentResolverForUser(int userId) {
        UserHandle user = new UserHandle(userId);
        Context context;
        try {
            context = mContext.createPackageContextAsUser("android", 0, user);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "failed to create package context as user " + user);
            context = mContext;
        }
        return context.getContentResolver();
    }

    @GuardedBy("mLock")
    private UserState getOrCreateUserStateLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        if (userState == null) {
            userState = new UserState(mContext, userId);
            mUserStates.put(userId, userState);
        }
        return userState;
    }

    @GuardedBy("mLock")
    private ServiceState getServiceStateLocked(ComponentName component, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(component);
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
    private SessionState getSessionStateLocked(IBinder sessionToken,
            int callingUid, UserState userState) {
        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
        if (sessionState == null) {
            throw new SessionNotFoundException("Session state not found for token " + sessionToken);
        }
        // Only the application that requested this session or the system can access it.
        if (callingUid != Process.SYSTEM_UID && callingUid != sessionState.callingUid) {
            throw new SecurityException("Illegal access to the session with token " + sessionToken
                    + " from uid " + callingUid);
        }
        return sessionState;
    }

    @GuardedBy("mLock")
    private ITvInputSession getSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        return getSessionLocked(getSessionStateLocked(sessionToken, callingUid, userId));
    }

    @GuardedBy("mLock")
    private ITvInputSession getSessionLocked(SessionState sessionState) {
        ITvInputSession session = sessionState.session;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token "
                    + sessionState.sessionToken);
        }
        return session;
    }

    private int resolveCallingUserId(int callingPid, int callingUid, int requestedUserId,
            String methodName) {
        return ActivityManager.handleIncomingUser(callingPid, callingUid, requestedUserId, false,
                false, methodName, null);
    }

    @GuardedBy("mLock")
    private void updateServiceConnectionLocked(ComponentName component, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(component);
        if (serviceState == null) {
            return;
        }
        if (serviceState.reconnecting) {
            if (!serviceState.sessionTokens.isEmpty()) {
                // wait until all the sessions are removed.
                return;
            }
            serviceState.reconnecting = false;
        }

        boolean shouldBind;
        if (userId == mCurrentUserId || mRunningProfiles.contains(userId)) {
            shouldBind = !serviceState.sessionTokens.isEmpty()
                    || (serviceState.isHardware && serviceState.neverConnected);
        } else {
            // For a non-current user,
            // if sessionTokens is not empty, it contains recording sessions only
            // because other sessions must have been removed while switching user
            // and non-recording sessions are not created by createSession().
            shouldBind = !serviceState.sessionTokens.isEmpty();
        }

        // only bind/unbind when necessary.
        if (shouldBind && !serviceState.bound) {
            bindService(serviceState, userId);
        } else if (!shouldBind && serviceState.bound) {
            unbindService(serviceState);
            if (!serviceState.isHardware) {
                userState.serviceStateMap.remove(component);
            }
        }
    }

    @GuardedBy("mLock")
    private void abortPendingCreateSessionRequestsLocked(ServiceState serviceState,
            String inputId, int userId) {
        // Let clients know the create session requests are failed.
        UserState userState = getOrCreateUserStateLocked(userId);
        List<SessionState> sessionsToAbort = new ArrayList<>();
        for (IBinder sessionToken : serviceState.sessionTokens) {
            SessionState sessionState = userState.sessionStateMap.get(sessionToken);
            if (sessionState.session == null && (inputId == null
                    || sessionState.inputId.equals(inputId))) {
                sessionsToAbort.add(sessionState);
            }
        }
        for (SessionState sessionState : sessionsToAbort) {
            removeSessionStateLocked(sessionState.sessionToken, sessionState.userId);
            sendSessionTokenToClientLocked(sessionState.client,
                    sessionState.inputId, null, null, sessionState.seq);
        }
        if (!serviceState.isHardware) {
            updateServiceConnectionLocked(serviceState.component, userId);
        } else {
            updateHardwareServiceConnectionDelayed(userId);
        }
    }

    @GuardedBy("mLock")
    private boolean createSessionInternalLocked(
            ITvInputService service, IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slog.d(TAG, "createSessionInternalLocked(inputId="
                    + sessionState.inputId + ", sessionId=" + sessionState.sessionId + ")");
        }
        InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvInputSessionCallback callback = new SessionCallback(sessionState, channels);

        boolean created = true;
        // Create a session. When failed, send a null token immediately.
        try {
            if (sessionState.isRecordingSession) {
                service.createRecordingSession(
                        callback, sessionState.inputId, sessionState.sessionId);
            } else {
                service.createSession(channels[1], callback, sessionState.inputId,
                        sessionState.sessionId, sessionState.tvAppAttributionSource);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "error in createSession", e);
            sendSessionTokenToClientLocked(sessionState.client, sessionState.inputId, null,
                    null, sessionState.seq);
            created = false;
        }
        channels[1].dispose();
        return created;
    }

    @GuardedBy("mLock")
    private void sendSessionTokenToClientLocked(ITvInputClient client, String inputId,
            IBinder sessionToken, InputChannel channel, int seq) {
        try {
            client.onSessionCreated(inputId, sessionToken, channel, seq);
        } catch (RemoteException e) {
            Slog.e(TAG, "error in onSessionCreated", e);
        }
    }

    @GuardedBy("mLock")
    @Nullable
    private SessionState releaseSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        SessionState sessionState = null;
        try {
            sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
            UserState userState = getOrCreateUserStateLocked(userId);
            if (sessionState.session != null) {
                if (sessionToken == userState.mainSessionToken) {
                    setMainLocked(sessionToken, false, callingUid, userId);
                }
                sessionState.session.asBinder().unlinkToDeath(sessionState, 0);
                sessionState.session.release();
            }
            sessionState.currentChannel = null;
            if (sessionState.isCurrent) {
                sessionState.isCurrent = false;
                notifyCurrentChannelInfosUpdatedLocked(userState);
            }
        } catch (RemoteException | SessionNotFoundException e) {
            Slog.e(TAG, "error in releaseSession", e);
        } finally {
            if (sessionState != null) {
                sessionState.session = null;
            }
        }
        if (mOnScreenSessionState == sessionState) {
            // only log when releasing the current on-screen session
            logExternalInputEvent(FrameworkStatsLog.EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__RELEASED,
                    mOnScreenInputId, sessionState);
            mOnScreenInputId = null;
            mOnScreenSessionState = null;
        }
        removeSessionStateLocked(sessionToken, userId);
        return sessionState;
    }

    @GuardedBy("mLock")
    private void removeSessionStateLocked(IBinder sessionToken, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        if (sessionToken == userState.mainSessionToken) {
            if (DEBUG) {
                Slog.d(TAG, "mainSessionToken=null");
            }
            userState.mainSessionToken = null;
        }

        // Remove the session state from the global session state map of the current user.
        SessionState sessionState = userState.sessionStateMap.remove(sessionToken);

        if (sessionState == null) {
            Slog.e(TAG, "sessionState null, no more remove session action!");
            return;
        }

        // Also remove the session token from the session token list of the current client and
        // service.
        ClientState clientState = userState.clientStateMap.get(sessionState.client.asBinder());
        if (clientState != null) {
            clientState.sessionTokens.remove(sessionToken);
            if (clientState.isEmpty()) {
                userState.clientStateMap.remove(sessionState.client.asBinder());
                sessionState.client.asBinder().unlinkToDeath(clientState, 0);
            }
        }

        mSessionIdToSessionStateMap.remove(sessionState.sessionId);

        ServiceState serviceState = userState.serviceStateMap.get(sessionState.componentName);
        if (serviceState != null) {
            serviceState.sessionTokens.remove(sessionToken);
        }
        if (!serviceState.isHardware) {
            updateServiceConnectionLocked(sessionState.componentName, userId);
        } else {
            updateHardwareServiceConnectionDelayed(userId);
        }

        // Log the end of watch.
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = sessionToken;
        args.arg2 = System.currentTimeMillis();
        mMessageHandler.obtainMessage(MessageHandler.MSG_LOG_WATCH_END, args).sendToTarget();
    }

    @GuardedBy("mLock")
    private void setMainLocked(IBinder sessionToken, boolean isMain, int callingUid, int userId) {
        try {
            SessionState sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
            if (sessionState.hardwareSessionToken != null) {
                sessionState = getSessionStateLocked(sessionState.hardwareSessionToken,
                        Process.SYSTEM_UID, userId);
            }
            ServiceState serviceState = getServiceStateLocked(sessionState.componentName, userId);
            if (!serviceState.isHardware) {
                return;
            }
            ITvInputSession session = getSessionLocked(sessionState);
            session.setMain(isMain);
            if (sessionState.isMainSession != isMain) {
                UserState userState = getUserStateLocked(userId);
                sessionState.isMainSession = isMain;
                notifyCurrentChannelInfosUpdatedLocked(userState);
            }
        } catch (RemoteException | SessionNotFoundException e) {
            Slog.e(TAG, "error in setMain", e);
        }
    }

    @GuardedBy("mLock")
    private void notifyInputAddedLocked(UserState userState, String inputId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputAddedLocked(inputId=" + inputId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onInputAdded(inputId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report added input to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyInputRemovedLocked(UserState userState, String inputId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputRemovedLocked(inputId=" + inputId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onInputRemoved(inputId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report removed input to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyInputUpdatedLocked(UserState userState, String inputId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputUpdatedLocked(inputId=" + inputId + ")");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onInputUpdated(inputId);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report updated input to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void notifyInputStateChangedLocked(UserState userState, String inputId,
            int state, ITvInputManagerCallback targetCallback) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputStateChangedLocked(inputId=" + inputId
                    + ", state=" + state + ")");
        }
        if (targetCallback == null) {
            int n = userState.mCallbacks.beginBroadcast();
            for (int i = 0; i < n; ++i) {
                try {
                    userState.mCallbacks.getBroadcastItem(i).onInputStateChanged(inputId, state);
                } catch (RemoteException e) {
                    Slog.e(TAG, "failed to report state change to callback", e);
                }
            }
            userState.mCallbacks.finishBroadcast();
        } else {
            try {
                targetCallback.onInputStateChanged(inputId, state);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report state change to callback", e);
            }
        }
    }

    @GuardedBy("mLock")
    private void notifyCurrentChannelInfosUpdatedLocked(UserState userState) {
        if (DEBUG) {
            Slog.d(TAG, "notifyCurrentChannelInfosUpdatedLocked");
        }
        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                ITvInputManagerCallback callback = userState.mCallbacks.getBroadcastItem(i);
                Pair<Integer, Integer> pidUid = userState.callbackPidUidMap.get(callback);
                if (mContext.checkPermission(android.Manifest.permission.ACCESS_TUNED_INFO,
                        pidUid.first, pidUid.second) != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                List<TunedInfo> infos = getCurrentTunedInfosInternalLocked(
                        userState, pidUid.first, pidUid.second);
                callback.onCurrentTunedInfosUpdated(infos);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report updated current channel infos to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private void updateTvInputInfoLocked(UserState userState, TvInputInfo inputInfo) {
        if (DEBUG) {
            Slog.d(TAG, "updateTvInputInfoLocked(inputInfo=" + inputInfo + ")");
        }
        String inputId = inputInfo.getId();
        TvInputState inputState = userState.inputMap.get(inputId);
        if (inputState == null) {
            Slog.e(TAG, "failed to set input info - unknown input id " + inputId);
            return;
        }
        boolean currentCecTvInputInfoUpdated = isCurrentCecTvInputInfoUpdate(userState, inputInfo);
        inputState.info = inputInfo;
        inputState.uid = getInputUid(inputInfo);
        ServiceState serviceState = userState.serviceStateMap.get(inputInfo.getComponent());
        if (serviceState != null && serviceState.isHardware) {
            serviceState.hardwareInputMap.put(inputInfo.getId(), inputInfo);
            mTvInputHardwareManager.updateInputInfo(inputInfo);
        }

        if (currentCecTvInputInfoUpdated) {
            logExternalInputEvent(
                    FrameworkStatsLog.EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__DEVICE_INFO_UPDATED,
                    mOnScreenInputId, mOnScreenSessionState);
        }

        int n = userState.mCallbacks.beginBroadcast();
        for (int i = 0; i < n; ++i) {
            try {
                userState.mCallbacks.getBroadcastItem(i).onTvInputInfoUpdated(inputInfo);
            } catch (RemoteException e) {
                Slog.e(TAG, "failed to report updated input info to callback", e);
            }
        }
        userState.mCallbacks.finishBroadcast();
    }

    @GuardedBy("mLock")
    private boolean isCurrentCecTvInputInfoUpdate(UserState userState, TvInputInfo newInputInfo) {
        if (newInputInfo == null || newInputInfo.getId() == null
                || !newInputInfo.getId().equals(mOnScreenInputId)) {
            return false;
        }
        if (newInputInfo.getHdmiDeviceInfo() == null
                || !newInputInfo.getHdmiDeviceInfo().isCecDevice()) {
            return false;
        }
        TvInputState inputState = userState.inputMap.get(mOnScreenInputId);
        if (inputState == null || inputState.info == null) {
            return false;
        }
        if (inputState.info.getHdmiDeviceInfo() == null
                || !inputState.info.getHdmiDeviceInfo().isCecDevice()) {
            return false;
        }
        String newDisplayName = newInputInfo.getHdmiDeviceInfo().getDisplayName(),
               currentDisplayName = inputState.info.getHdmiDeviceInfo().getDisplayName();
        int newVendorId = newInputInfo.getHdmiDeviceInfo().getVendorId(),
            currentVendorId = inputState.info.getHdmiDeviceInfo().getVendorId();
        return !TextUtils.equals(newDisplayName, currentDisplayName)
                || newVendorId != currentVendorId;
    }

    @GuardedBy("mLock")
    private void setStateLocked(String inputId, int state, int userId) {
        UserState userState = getOrCreateUserStateLocked(userId);
        TvInputState inputState = userState.inputMap.get(inputId);
        if (inputState == null) {
            Slog.e(TAG, "failed to setStateLocked - unknown input id " + inputId);
            return;
        }
        ServiceState serviceState = userState.serviceStateMap.get(inputState.info.getComponent());
        int oldState = inputState.state;
        inputState.state = state;
        if (serviceState != null && serviceState.reconnecting) {
            // We don't notify state change while reconnecting. It should remain disconnected.
            return;
        }
        if (oldState != state) {
            if (inputId.equals(mOnScreenInputId)) {
                logExternalInputEvent(
                        FrameworkStatsLog
                                .EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__CONNECTION_STATE_CHANGED,
                        mOnScreenInputId, mOnScreenSessionState);
            } else if (mOnScreenInputId != null) {
                TvInputState currentInputState = userState.inputMap.get(mOnScreenInputId);
                TvInputInfo currentInputInfo = null;
                if (currentInputState != null) {
                    currentInputInfo = currentInputState.info;
                }
                if (currentInputInfo != null && currentInputInfo.getHdmiDeviceInfo() != null
                        && inputId.equals(currentInputInfo.getParentId())) {
                    logExternalInputEvent(
                            FrameworkStatsLog
                                    .EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__CONNECTION_STATE_CHANGED,
                            inputId, mOnScreenSessionState);
                    if (state == INPUT_STATE_CONNECTED_STANDBY) {
                        mOnScreenInputId = currentInputInfo.getParentId();
                    }
                }
            }
            notifyInputStateChangedLocked(userState, inputId, state, null);
        }
    }

    private final class BinderService extends ITvInputManager.Stub {
        @Override
        public List<TvInputInfo> getTvInputList(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvInputList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    List<TvInputInfo> inputList = new ArrayList<>();
                    for (TvInputState state : userState.inputMap.values()) {
                        inputList.add(state.info);
                    }
                    return inputList;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public TvInputInfo getTvInputInfo(String inputId, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvInputInfo");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInputState state = userState.inputMap.get(inputId);
                    return state == null ? null : state.info;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void updateTvInputInfo(TvInputInfo inputInfo, int userId) {
            String inputInfoPackageName = inputInfo.getServiceInfo().packageName;
            String callingPackageName = getCallingPackageName();
            if (!TextUtils.equals(inputInfoPackageName, callingPackageName)
                    && mContext.checkCallingPermission(
                            android.Manifest.permission.WRITE_SECURE_SETTINGS)
                                    != PackageManager.PERMISSION_GRANTED) {
                // Only the app owning the input and system settings are allowed to update info.
                throw new IllegalArgumentException("calling package " + callingPackageName
                        + " is not allowed to change TvInputInfo for " + inputInfoPackageName);
            }

            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "updateTvInputInfo");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    updateTvInputInfoLocked(userState, inputInfo);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private String getCallingPackageName() {
            final String[] packages = mContext.getPackageManager().getPackagesForUid(
                    Binder.getCallingUid());
            if (packages != null && packages.length > 0) {
                return packages[0];
            }
            return "unknown";
        }

        @Override
        public int getTvInputState(String inputId, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvInputState");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInputState state = userState.inputMap.get(inputId);
                    return state == null ? INPUT_STATE_CONNECTED : state.state;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<String> getAvailableExtensionInterfaceNames(String inputId, int userId) {
            ensureTisExtensionInterfacePermission();
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "getAvailableExtensionInterfaceNames");
            final long identity = Binder.clearCallingIdentity();
            try {
                ITvInputService service = null;
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInputState inputState = userState.inputMap.get(inputId);
                    if (inputState != null) {
                        ServiceState serviceState =
                                userState.serviceStateMap.get(inputState.info.getComponent());
                        if (serviceState != null && serviceState.isHardware
                                && serviceState.service != null) {
                            service = serviceState.service;
                        }
                    }
                }
                try {
                    if (service != null) {
                        List<String> interfaces = new ArrayList<>();
                        for (final String name : CollectionUtils.emptyIfNull(
                                service.getAvailableExtensionInterfaceNames())) {
                            String permission = service.getExtensionInterfacePermission(name);
                            if (permission == null
                                    || mContext.checkPermission(permission, callingPid, callingUid)
                                    == PackageManager.PERMISSION_GRANTED) {
                                interfaces.add(name);
                            }
                        }
                        return interfaces;
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in getAvailableExtensionInterfaceNames "
                            + "or getExtensionInterfacePermission", e);
                }
                return new ArrayList<>();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public IBinder getExtensionInterface(String inputId, String name, int userId) {
            ensureTisExtensionInterfacePermission();
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "getExtensionInterface");
            final long identity = Binder.clearCallingIdentity();
            try {
                ITvInputService service = null;
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInputState inputState = userState.inputMap.get(inputId);
                    if (inputState != null) {
                        ServiceState serviceState =
                                userState.serviceStateMap.get(inputState.info.getComponent());
                        if (serviceState != null && serviceState.isHardware
                                && serviceState.service != null) {
                            service = serviceState.service;
                        }
                    }
                }
                try {
                    if (service != null) {
                        String permission = service.getExtensionInterfacePermission(name);
                        if (permission == null
                                || mContext.checkPermission(permission, callingPid, callingUid)
                                == PackageManager.PERMISSION_GRANTED) {
                            return service.getExtensionInterface(name);
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in getExtensionInterfacePermission "
                            + "or getExtensionInterface", e);
                }
                return null;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<TvContentRatingSystemInfo> getTvContentRatingSystemList(int userId) {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.READ_CONTENT_RATING_SYSTEMS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "The caller does not have permission to read content rating systems");
            }
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvContentRatingSystemList");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    return userState.contentRatingSystemList;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendTvInputNotifyIntent(Intent intent, int userId) {
            if (mContext.checkCallingPermission(android.Manifest.permission.NOTIFY_TV_INPUTS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("The caller: " + getCallingPackageName()
                        + " doesn't have permission: "
                        + android.Manifest.permission.NOTIFY_TV_INPUTS);
            }
            if (TextUtils.isEmpty(intent.getPackage())) {
                throw new IllegalArgumentException("Must specify package name to notify.");
            }
            switch (intent.getAction()) {
                case TvContract.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED:
                    if (intent.getLongExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, -1) < 0) {
                        throw new IllegalArgumentException("Invalid preview program ID.");
                    }
                    break;
                case TvContract.ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED:
                    if (intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1) < 0) {
                        throw new IllegalArgumentException("Invalid watch next program ID.");
                    }
                    break;
                case TvContract.ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT:
                    if (intent.getLongExtra(TvContract.EXTRA_PREVIEW_PROGRAM_ID, -1) < 0) {
                        throw new IllegalArgumentException("Invalid preview program ID.");
                    }
                    if (intent.getLongExtra(TvContract.EXTRA_WATCH_NEXT_PROGRAM_ID, -1) < 0) {
                        throw new IllegalArgumentException("Invalid watch next program ID.");
                    }
                    break;
                default:
                    throw new IllegalArgumentException("Invalid TV input notifying action: "
                            + intent.getAction());
            }
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "sendTvInputNotifyIntent");
            final long identity = Binder.clearCallingIdentity();
            try {
                getContext().sendBroadcastAsUser(intent, new UserHandle(resolvedUserId));
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerCallback(final ITvInputManagerCallback callback, int userId) {
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
                    } else {
                        userState.callbackPidUidMap.put(
                                callback, Pair.create(callingPid, callingUid));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unregisterCallback(ITvInputManagerCallback callback, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "unregisterCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    userState.mCallbacks.unregister(callback);
                    userState.callbackPidUidMap.remove(callback);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean isParentalControlsEnabled(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "isParentalControlsEnabled");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    return userState.persistentDataStore.isParentalControlsEnabled();
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setParentalControlsEnabled(boolean enabled, int userId) {
            ensureParentalControlsPermission();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "setParentalControlsEnabled");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    userState.persistentDataStore.setParentalControlsEnabled(enabled);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean isRatingBlocked(String rating, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "isRatingBlocked");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    return userState.persistentDataStore.isRatingBlocked(
                            TvContentRating.unflattenFromString(rating));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<String> getBlockedRatings(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getBlockedRatings");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    List<String> ratings = new ArrayList<>();
                    for (TvContentRating rating
                            : userState.persistentDataStore.getBlockedRatings()) {
                        ratings.add(rating.flattenToString());
                    }
                    return ratings;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void addBlockedRating(String rating, int userId) {
            ensureParentalControlsPermission();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "addBlockedRating");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    userState.persistentDataStore.addBlockedRating(
                            TvContentRating.unflattenFromString(rating));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removeBlockedRating(String rating, int userId) {
            ensureParentalControlsPermission();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "removeBlockedRating");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    userState.persistentDataStore.removeBlockedRating(
                            TvContentRating.unflattenFromString(rating));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void ensureParentalControlsPermission() {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "The caller does not have parental controls permission");
            }
        }

        @Override
        public void createSession(final ITvInputClient client, final String inputId,
                AttributionSource tvAppAttributionSource, boolean isRecordingSession, int seq,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "createSession");
            final long identity = Binder.clearCallingIdentity();
            /**
             * A randomly generated id for this this session.
             *
             * <p>This field contains no user or device reference and is large enough to be
             * effectively globally unique.
             *
             * <p><b>WARNING</b> Any changes to this field should be carefully reviewed for privacy.
             * Inspect the code at:
             *
             * <ul>
             *   <li>framework/base/cmds/statsd/src/atoms.proto#TifTuneState
             *   <li>{@link #logTuneStateChanged}
             *   <li>{@link TvInputManagerService.BinderService#createSession}
             *   <li>{@link SessionState#sessionId}
             * </ul>
             */
            String uniqueSessionId = UUID.randomUUID().toString();
            try {
                synchronized (mLock) {
                    if (userId != mCurrentUserId && !mRunningProfiles.contains(userId)
                            && !isRecordingSession) {
                        // Only current user and its running profiles can create
                        // non-recording sessions.
                        // Let the client get onConnectionFailed callback for this case.
                        sendSessionTokenToClientLocked(client, inputId, null, null, seq);
                        return;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvInputState inputState = userState.inputMap.get(inputId);
                    if (inputState == null) {
                        Slog.w(TAG, "Failed to find input state for inputId=" + inputId);
                        sendSessionTokenToClientLocked(client, inputId, null, null, seq);
                        return;
                    }
                    TvInputInfo info = inputState.info;
                    ServiceState serviceState = userState.serviceStateMap.get(info.getComponent());
                    if (serviceState == null) {
                        int tisUid = PackageManager.getApplicationInfoAsUserCached(
                                info.getComponent().getPackageName(), 0, resolvedUserId).uid;
                        serviceState = new ServiceState(info.getComponent(), resolvedUserId);
                        userState.serviceStateMap.put(info.getComponent(), serviceState);
                    }
                    // Send a null token immediately while reconnecting.
                    if (serviceState.reconnecting) {
                        sendSessionTokenToClientLocked(client, inputId, null, null, seq);
                        return;
                    }

                    // Create a new session token and a session state.
                    IBinder sessionToken = new Binder();
                    SessionState sessionState = new SessionState(sessionToken, info.getId(),
                            info.getComponent(), isRecordingSession, client, seq, callingUid,
                            callingPid, resolvedUserId, uniqueSessionId, tvAppAttributionSource);

                    // Add them to the global session state map of the current user.
                    userState.sessionStateMap.put(sessionToken, sessionState);

                    // Map the session id to the sessionStateMap in the user state
                    mSessionIdToSessionStateMap.put(uniqueSessionId, sessionState);

                    // Also, add them to the session state map of the current service.
                    serviceState.sessionTokens.add(sessionToken);

                    if (serviceState.service != null) {
                        if (!createSessionInternalLocked(
                                    serviceState.service, sessionToken, resolvedUserId)) {
                            removeSessionStateLocked(sessionToken, resolvedUserId);
                        }
                    } else {
                        updateServiceConnectionLocked(info.getComponent(), resolvedUserId);
                    }
                    logTuneStateChanged(FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__CREATED,
                            sessionState, inputState);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void releaseSession(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "releaseSession(sessionToken=" + sessionToken + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "releaseSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                SessionState sessionState = null;
                UserState userState = null;
                synchronized (mLock) {
                    sessionState = releaseSessionLocked(sessionToken, callingUid, resolvedUserId);
                    userState = getUserStateLocked(userId);
                }
                if (sessionState != null) {
                    TvInputState tvInputState = TvInputManagerService.getTvInputState(sessionState,
                            userState);
                    logTuneStateChanged(FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__RELEASED,
                            sessionState, tvInputState);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setMainSession(IBinder sessionToken, int userId) {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.CHANGE_HDMI_CEC_ACTIVE_SOURCE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "The caller does not have CHANGE_HDMI_CEC_ACTIVE_SOURCE permission");
            }
            if (DEBUG) {
                Slog.d(TAG, "setMainSession(sessionToken=" + sessionToken + ")");
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setMainSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    if (userState.mainSessionToken == sessionToken) {
                        return;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "mainSessionToken=" + sessionToken);
                    }
                    IBinder oldMainSessionToken = userState.mainSessionToken;
                    userState.mainSessionToken = sessionToken;

                    // Inform the new main session first.
                    // See {@link TvInputService.Session#onSetMain}.
                    if (sessionToken != null) {
                        setMainLocked(sessionToken, true, callingUid, userId);
                    }
                    if (oldMainSessionToken != null) {
                        setMainLocked(oldMainSessionToken, false, Process.SYSTEM_UID, userId);
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
            final long identity = Binder.clearCallingIdentity();
            SessionState sessionState = null;
            UserState userState = null;
            try {
                synchronized (mLock) {
                    try {
                        userState = getUserStateLocked(userId);
                        sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        if (sessionState.hardwareSessionToken == null) {
                            getSessionLocked(sessionState).setSurface(surface);
                        } else {
                            getSessionLocked(sessionState.hardwareSessionToken,
                                    Process.SYSTEM_UID, resolvedUserId).setSurface(surface);
                        }
                        boolean isVisible = (surface == null);
                        if (sessionState.isVisible != isVisible) {
                            sessionState.isVisible = isVisible;
                            notifyCurrentChannelInfosUpdatedLocked(userState);
                        }
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setSurface", e);
                    }
                }
            } finally {
                if (surface != null) {
                    // surface is not used in TvInputManagerService.
                    surface.release();
                }
                if (sessionState != null) {
                    int state = surface == null
                            ?
                            FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__SURFACE_DETACHED
                            : FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__SURFACE_ATTACHED;
                    logTuneStateChanged(state, sessionState,
                            TvInputManagerService.getTvInputState(sessionState, userState));
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
                        if (sessionState.hardwareSessionToken != null) {
                            getSessionLocked(sessionState.hardwareSessionToken, Process.SYSTEM_UID,
                                    resolvedUserId).dispatchSurfaceChanged(format, width, height);
                        }
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in dispatchSurfaceChanged", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setVolume(IBinder sessionToken, float volume, int userId) {
            final float REMOTE_VOLUME_ON = 1.0f;
            final float REMOTE_VOLUME_OFF = 0f;
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setVolume");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).setVolume(volume);
                        if (sessionState.hardwareSessionToken != null) {
                            // Here, we let the hardware session know only whether volume is on or
                            // off to prevent that the volume is controlled in the both side.
                            getSessionLocked(sessionState.hardwareSessionToken,
                                    Process.SYSTEM_UID, resolvedUserId).setVolume((volume > 0.0f)
                                            ? REMOTE_VOLUME_ON : REMOTE_VOLUME_OFF);
                        }
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setVolume", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void tune(IBinder sessionToken, final Uri channelUri, Bundle params, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId, "tune");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).tune(
                                channelUri, params);
                        UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                        SessionState sessionState =
                                getSessionStateLocked(sessionToken, callingUid, userState);
                        if (!sessionState.isCurrent
                                || !Objects.equals(sessionState.currentChannel, channelUri)) {
                            sessionState.isCurrent = true;
                            sessionState.currentChannel = channelUri;
                            notifyCurrentChannelInfosUpdatedLocked(userState);
                            if (!sessionState.isRecordingSession) {
                                String sessionActualInputId = getSessionActualInputId(sessionState);
                                if (!TextUtils.equals(mOnScreenInputId, sessionActualInputId)) {
                                    logExternalInputEvent(
                                            FrameworkStatsLog
                                                    .EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__TUNED,
                                            sessionActualInputId, sessionState);
                                }
                                mOnScreenInputId = sessionActualInputId;
                                mOnScreenSessionState = sessionState;
                            }
                        }
                        if (TvContract.isChannelUriForPassthroughInput(channelUri)) {
                            // Do not log the watch history for passthrough inputs.
                            return;
                        }

                        if (sessionState.isRecordingSession) {
                            return;
                        }

                        logTuneStateChanged(
                                FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__TUNE_STARTED,
                                sessionState,
                                TvInputManagerService.getTvInputState(sessionState, userState));
                        // Log the start of watch.
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = sessionState.componentName.getPackageName();
                        args.arg2 = System.currentTimeMillis();
                        args.arg3 = ContentUris.parseId(channelUri);
                        args.arg4 = params;
                        args.arg5 = sessionToken;
                        mMessageHandler.obtainMessage(MessageHandler.MSG_LOG_WATCH_START, args)
                                .sendToTarget();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in tune", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unblockContent(
                IBinder sessionToken, String unblockedRating, int userId) {
            ensureParentalControlsPermission();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "unblockContent");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .unblockContent(unblockedRating);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in unblockContent", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setCaptionEnabled(IBinder sessionToken, boolean enabled, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setCaptionEnabled");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .setCaptionEnabled(enabled);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setCaptionEnabled", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void selectAudioPresentation(IBinder sessionToken, int presentationId,
                                            int programId, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "selectAudioPresentation");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid,
                                        resolvedUserId).selectAudioPresentation(
                                        presentationId, programId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in selectAudioPresentation", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

       @Override
        public void selectTrack(IBinder sessionToken, int type, String trackId, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "selectTrack");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).selectTrack(
                                type, trackId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in selectTrack", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setInteractiveAppNotificationEnabled(
                IBinder sessionToken, boolean enabled, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setInteractiveAppNotificationEnabled");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .setInteractiveAppNotificationEnabled(enabled);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setInteractiveAppNotificationEnabled", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void sendAppPrivateCommand(IBinder sessionToken, String command, Bundle data,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "sendAppPrivateCommand");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .appPrivateCommand(command, data);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in appPrivateCommand", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void createOverlayView(IBinder sessionToken, IBinder windowToken, Rect frame,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .createOverlayView(windowToken, frame);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in createOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void relayoutOverlayView(IBinder sessionToken, Rect frame, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "relayoutOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .relayoutOverlayView(frame);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in relayoutOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removeOverlayView(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "removeOverlayView");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .removeOverlayView();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in removeOverlayView", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void stopPlayback(IBinder sessionToken, int mode, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "stopPlayback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).stopPlayback(
                                mode);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in stopPlayback(mode)", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startPlayback(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "stopPlayback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).startPlayback();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in startPlayback()", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftPlay(IBinder sessionToken, final Uri recordedProgramUri, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftPlay");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).timeShiftPlay(
                                recordedProgramUri);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftPlay", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftPause(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftPause");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).timeShiftPause();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftPause", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftResume(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftResume");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .timeShiftResume();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftResume", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftSeekTo(IBinder sessionToken, long timeMs, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftSeekTo");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .timeShiftSeekTo(timeMs);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftSeekTo", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftSetPlaybackParams(IBinder sessionToken, PlaybackParams params,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftSetPlaybackParams");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .timeShiftSetPlaybackParams(params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftSetPlaybackParams", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftSetMode(IBinder sessionToken, int mode, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftSetMode");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .timeShiftSetMode(mode);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftSetMode", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void timeShiftEnablePositionTracking(IBinder sessionToken, boolean enable,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "timeShiftEnablePositionTracking");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .timeShiftEnablePositionTracking(enable);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in timeShiftEnablePositionTracking", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyTvMessage(IBinder sessionToken, int type, Bundle data, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "notifyTvmessage");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .notifyTvMessage(type, data);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in notifyTvMessage", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setVideoFrozen(IBinder sessionToken, boolean isFrozen, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setVideoFrozen");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .setVideoFrozen(isFrozen);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setVideoFrozen", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void setTvMessageEnabled(IBinder sessionToken, int type, boolean enabled,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setTvMessageEnabled");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        final String inputId =
                                getSessionStateLocked(sessionToken, callingUid, userId).inputId;
                        mTvInputHardwareManager.setTvMessageEnabled(inputId, type, enabled);
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .setTvMessageEnabled(type, enabled);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in setTvMessageEnabled", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void startRecording(IBinder sessionToken, @Nullable Uri programUri,
                @Nullable Bundle params, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "startRecording");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).startRecording(
                                programUri, params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in startRecording", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void stopRecording(IBinder sessionToken, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "stopRecording");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).stopRecording();
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in stopRecording", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void pauseRecording(IBinder sessionToken, @NonNull Bundle params, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "pauseRecording");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .pauseRecording(params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in pauseRecording", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void resumeRecording(IBinder sessionToken, @NonNull Bundle params, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "resumeRecording");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .resumeRecording(params);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in resumeRecording", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<TvInputHardwareInfo> getHardwareList() throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                return mTvInputHardwareManager.getHardwareList();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public ITvInputHardware acquireTvInputHardware(int deviceId,
                ITvInputHardwareCallback callback, TvInputInfo info, int userId,
                String tvInputSessionId,
                @TvInputService.PriorityHintUseCaseType int priorityHint) throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "acquireTvInputHardware");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mTvInputHardwareManager.acquireHardware(
                        deviceId, callback, info, callingUid, resolvedUserId,
                        tvInputSessionId, priorityHint);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void releaseTvInputHardware(int deviceId, ITvInputHardware hardware, int userId)
                throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "releaseTvInputHardware");
            final long identity = Binder.clearCallingIdentity();
            try {
                mTvInputHardwareManager.releaseHardware(
                        deviceId, hardware, callingUid, resolvedUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<DvbDeviceInfo> getDvbDeviceList() throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.DVB_DEVICE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires DVB_DEVICE permission");
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                // Pattern1: /dev/dvb%d.frontend%d
                ArrayList<DvbDeviceInfo> deviceInfosFromPattern1 = new ArrayList<>();
                File devDirectory = new File("/dev");
                boolean dvbDirectoryFound = false;
                for (String fileName : devDirectory.list()) {
                    Matcher matcher = sFrontEndDevicePattern.matcher(fileName);
                    if (matcher.find()) {
                        int adapterId = Integer.parseInt(matcher.group(1));
                        int deviceId = Integer.parseInt(matcher.group(2));
                        deviceInfosFromPattern1.add(new DvbDeviceInfo(adapterId, deviceId));
                    }
                    if (TextUtils.equals("dvb", fileName)) {
                        dvbDirectoryFound = true;
                    }
                }
                if (!dvbDirectoryFound) {
                    return Collections.unmodifiableList(deviceInfosFromPattern1);
                }
                File dvbDirectory = new File(DVB_DIRECTORY);
                // Pattern2: /dev/dvb/adapter%d/frontend%d
                ArrayList<DvbDeviceInfo> deviceInfosFromPattern2 = new ArrayList<>();
                for (String fileNameInDvb : dvbDirectory.list()) {
                    Matcher adapterMatcher = sAdapterDirPattern.matcher(fileNameInDvb);
                    if (adapterMatcher.find()) {
                        int adapterId = Integer.parseInt(adapterMatcher.group(1));
                        File adapterDirectory = new File(DVB_DIRECTORY + "/" + fileNameInDvb);
                        for (String fileNameInAdapter : adapterDirectory.list()) {
                            Matcher frontendMatcher = sFrontEndInAdapterDirPattern.matcher(
                                    fileNameInAdapter);
                            if (frontendMatcher.find()) {
                                int deviceId = Integer.parseInt(frontendMatcher.group(1));
                                deviceInfosFromPattern2.add(
                                        new DvbDeviceInfo(adapterId, deviceId));
                            }
                        }
                    }
                }
                return deviceInfosFromPattern2.isEmpty()
                        ? Collections.unmodifiableList(deviceInfosFromPattern1)
                        : Collections.unmodifiableList(deviceInfosFromPattern2);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public ParcelFileDescriptor openDvbDevice(DvbDeviceInfo info,
                @TvInputManager.DvbDeviceType int deviceType)  throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.DVB_DEVICE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires DVB_DEVICE permission");
            }

            File devDirectory = new File("/dev");
            boolean dvbDeviceFound = false;
            for (String fileName : devDirectory.list()) {
                if (TextUtils.equals("dvb", fileName)) {
                    File dvbDirectory = new File(DVB_DIRECTORY);
                    for (String fileNameInDvb : dvbDirectory.list()) {
                        Matcher adapterMatcher = sAdapterDirPattern.matcher(fileNameInDvb);
                        if (adapterMatcher.find()) {
                            File adapterDirectory = new File(DVB_DIRECTORY + "/" + fileNameInDvb);
                            for (String fileNameInAdapter : adapterDirectory.list()) {
                                Matcher frontendMatcher = sFrontEndInAdapterDirPattern.matcher(
                                        fileNameInAdapter);
                                if (frontendMatcher.find()) {
                                    dvbDeviceFound = true;
                                    break;
                                }
                            }
                        }
                        if (dvbDeviceFound) {
                            break;
                        }
                    }
                }
                if (dvbDeviceFound) {
                    break;
                }
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                String deviceFileName;
                switch (deviceType) {
                    case TvInputManager.DVB_DEVICE_DEMUX:
                        deviceFileName = String.format(dvbDeviceFound
                                ? "/dev/dvb/adapter%d/demux%d" : "/dev/dvb%d.demux%d",
                                info.getAdapterId(), info.getDeviceId());
                        break;
                    case TvInputManager.DVB_DEVICE_DVR:
                        deviceFileName = String.format(dvbDeviceFound
                                ? "/dev/dvb/adapter%d/dvr%d" : "/dev/dvb%d.dvr%d",
                                info.getAdapterId(), info.getDeviceId());
                        break;
                    case TvInputManager.DVB_DEVICE_FRONTEND:
                        deviceFileName = String.format(dvbDeviceFound
                                ? "/dev/dvb/adapter%d/frontend%d" : "/dev/dvb%d.frontend%d",
                                info.getAdapterId(), info.getDeviceId());
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid DVB device: " + deviceType);
                }
                try {
                    // The DVB frontend device only needs to be opened in read/write mode, which
                    // allows performing tuning operations. The DVB demux and DVR device are enough
                    // to be opened in read only mode.
                    return ParcelFileDescriptor.open(new File(deviceFileName),
                            TvInputManager.DVB_DEVICE_FRONTEND == deviceType
                                    ? ParcelFileDescriptor.MODE_READ_WRITE
                                    : ParcelFileDescriptor.MODE_READ_ONLY);
                } catch (FileNotFoundException e) {
                    return null;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<TvStreamConfig> getAvailableTvStreamConfigList(String inputId, int userId)
                throws RemoteException {
            ensureCaptureTvInputPermission();

            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "getAvailableTvStreamConfigList");
            final long identity = Binder.clearCallingIdentity();
            try {
                return mTvInputHardwareManager.getAvailableTvStreamConfigList(
                        inputId, callingUid, resolvedUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean captureFrame(String inputId, Surface surface, TvStreamConfig config,
                int userId)
                throws RemoteException {
            ensureCaptureTvInputPermission();

            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "captureFrame");
            final long identity = Binder.clearCallingIdentity();
            try {
                String hardwareInputId = null;
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    if (userState.inputMap.get(inputId) == null) {
                        Slog.e(TAG, "input not found for " + inputId);
                        return false;
                    }
                    for (SessionState sessionState : userState.sessionStateMap.values()) {
                        if (sessionState.inputId.equals(inputId)
                                && sessionState.hardwareSessionToken != null) {
                            hardwareInputId = userState.sessionStateMap.get(
                                    sessionState.hardwareSessionToken).inputId;
                            break;
                        }
                    }
                }
                return mTvInputHardwareManager.captureFrame(
                        (hardwareInputId != null) ? hardwareInputId : inputId,
                        surface, config, callingUid, resolvedUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public boolean isSingleSessionActive(int userId) throws RemoteException {
            ensureCaptureTvInputPermission();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "isSingleSessionActive");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    if (userState.sessionStateMap.size() == 1) {
                        return true;
                    } else if (userState.sessionStateMap.size() == 2) {
                        SessionState[] sessionStates = userState.sessionStateMap.values().toArray(
                                new SessionState[2]);
                        // Check if there is a wrapper input.
                        return sessionStates[0].hardwareSessionToken != null
                                || sessionStates[1].hardwareSessionToken != null;
                    }
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void ensureCaptureTvInputPermission() {
            if (mContext.checkCallingPermission(
                android.Manifest.permission.CAPTURE_TV_INPUT)
                != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CAPTURE_TV_INPUT permission");
            }
        }

        @Override
        public void requestChannelBrowsable(Uri channelUri, int userId)
                throws RemoteException {
            final String callingPackageName = getCallingPackageName();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "requestChannelBrowsable");
            final long identity = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(TvContract.ACTION_CHANNEL_BROWSABLE_REQUESTED);
                List<ResolveInfo> list = getContext().getPackageManager()
                    .queryBroadcastReceivers(intent, 0);
                if (list != null) {
                    for (ResolveInfo info : list) {
                        String receiverPackageName = info.activityInfo.packageName;
                        intent.putExtra(TvContract.EXTRA_CHANNEL_ID, ContentUris.parseId(
                                channelUri));
                        intent.putExtra(TvContract.EXTRA_PACKAGE_NAME, callingPackageName);
                        intent.setPackage(receiverPackageName);
                        getContext().sendBroadcastAsUser(intent, new UserHandle(resolvedUserId));
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestBroadcastInfo(IBinder sessionToken, BroadcastInfoRequest request,
                int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "requestBroadcastInfo");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).requestBroadcastInfo(request);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in requestBroadcastInfo", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void removeBroadcastInfo(IBinder sessionToken, int requestId, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "removeBroadcastInfo");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).removeBroadcastInfo(requestId);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in removeBroadcastInfo", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestAd(IBinder sessionToken, AdRequest request, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "requestAd");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).requestAd(request);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in requestAd", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void notifyAdBufferReady(
                IBinder sessionToken, AdBuffer buffer, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid,
                    userId, "notifyAdBuffer");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        getSessionLocked(sessionState).notifyAdBufferReady(buffer);
                    } catch (RemoteException | SessionNotFoundException e) {
                        Slog.e(TAG, "error in notifyAdBuffer", e);
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
        public int getClientPid(String sessionId) {
            ensureTunerResourceAccessPermission();
            final long identity = Binder.clearCallingIdentity();

            int clientPid = TvInputManager.UNKNOWN_CLIENT_PID;
            try {
                synchronized (mLock) {
                    try {
                        clientPid = getClientPidLocked(sessionId);
                    } catch (ClientPidNotFoundException e) {
                        Slog.e(TAG, "error in getClientPid", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return clientPid;
        }

        @Override
        public int getClientPriority(int useCase, String sessionId) {
            ensureTunerResourceAccessPermission();
            final int callingPid = Binder.getCallingPid();
            final long identity = Binder.clearCallingIdentity();
            try {
                int clientPid = TvInputManager.UNKNOWN_CLIENT_PID;
                if (sessionId != null) {
                    synchronized (mLock) {
                        try {
                            clientPid = getClientPidLocked(sessionId);
                        } catch (ClientPidNotFoundException e) {
                            Slog.e(TAG, "error in getClientPriority", e);
                        }
                    }
                } else {
                    clientPid = callingPid;
                }
                TunerResourceManager trm = (TunerResourceManager)
                        mContext.getSystemService(Context.TV_TUNER_RESOURCE_MGR_SERVICE);
                return trm.getClientPriority(useCase, clientPid);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<TunedInfo> getCurrentTunedInfos(@UserIdInt int userId) {
            if (mContext.checkCallingPermission(android.Manifest.permission.ACCESS_TUNED_INFO)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(
                        "The caller does not have access tuned info permission");
            }
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(callingPid, callingUid, userId,
                    "getTvCurrentChannelInfos");
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                return getCurrentTunedInfosInternalLocked(userState, callingPid, callingUid);
            }
        }

        /**
         * Add a hardware device in the TvInputHardwareManager for CTS testing
         * purpose.
         *
         * @param deviceId  the id of the adding hardware device.
         */
        @Override
        public void addHardwareDevice(int deviceId) {
            TvInputHardwareInfo info = new TvInputHardwareInfo.Builder()
                        .deviceId(deviceId)
                        .type(TvInputHardwareInfo.TV_INPUT_TYPE_HDMI)
                        .audioType(DEVICE_NONE)
                        .audioAddress("0")
                        .hdmiPortId(0)
                        .build();
            TvStreamConfig[] configs = {
                    new TvStreamConfig.Builder().streamId(19001)
                            .generation(1).maxHeight(600).maxWidth(800).type(1).build()};
            mTvInputHardwareManager.onDeviceAvailable(info, configs);
        }

        /**
         * Remove a hardware device in the TvInputHardwareManager for CTS testing
         * purpose.
         *
         * @param deviceId the id of the removing hardware device.
         */
        @Override
        public void removeHardwareDevice(int deviceId) {
            mTvInputHardwareManager.onDeviceUnavailable(deviceId);
        }

        @GuardedBy("mLock")
        private int getClientPidLocked(String sessionId)
                throws ClientPidNotFoundException {
            if (mSessionIdToSessionStateMap.get(sessionId) == null) {
                throw new ClientPidNotFoundException("Client Pid not found with sessionId "
                        + sessionId);
            }
            return mSessionIdToSessionStateMap.get(sessionId).callingPid;
        }

        private void ensureTunerResourceAccessPermission() {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.TUNER_RESOURCE_ACCESS)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires TUNER_RESOURCE_ACCESS permission");
            }
        }

        private void ensureTisExtensionInterfacePermission() {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.TIS_EXTENSION_INTERFACE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires TIS_EXTENSION_INTERFACE permission");
            }
        }

        @NeverCompile // Avoid size overhead of debugging code.
        @Override
        @SuppressWarnings("resource")
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

            synchronized (mLock) {
                pw.println("User Ids (Current user: " + mCurrentUserId + "):");
                pw.increaseIndent();
                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    pw.println(Integer.valueOf(userId));
                }
                pw.decreaseIndent();

                for (int i = 0; i < mUserStates.size(); i++) {
                    int userId = mUserStates.keyAt(i);
                    UserState userState = getOrCreateUserStateLocked(userId);
                    pw.println("UserState (" + userId + "):");
                    pw.increaseIndent();

                    pw.println("inputMap: inputId -> TvInputState");
                    pw.increaseIndent();
                    for (Map.Entry<String, TvInputState> entry: userState.inputMap.entrySet()) {
                        pw.println(entry.getKey() + ": " + entry.getValue());
                    }
                    pw.decreaseIndent();

                    pw.println("packageSet:");
                    pw.increaseIndent();
                    for (String packageName : userState.packageSet) {
                        pw.println(packageName);
                    }
                    pw.decreaseIndent();

                    pw.println("clientStateMap: ITvInputClient -> ClientState");
                    pw.increaseIndent();
                    for (Map.Entry<IBinder, ClientState> entry :
                            userState.clientStateMap.entrySet()) {
                        ClientState client = entry.getValue();
                        pw.println(entry.getKey() + ": " + client);

                        pw.increaseIndent();

                        pw.println("sessionTokens:");
                        pw.increaseIndent();
                        for (IBinder token : client.sessionTokens) {
                            pw.println("" + token);
                        }
                        pw.decreaseIndent();

                        pw.println("clientTokens: " + client.clientToken);
                        pw.println("userId: " + client.userId);

                        pw.decreaseIndent();
                    }
                    pw.decreaseIndent();

                    pw.println("serviceStateMap: ComponentName -> ServiceState");
                    pw.increaseIndent();
                    for (Map.Entry<ComponentName, ServiceState> entry :
                            userState.serviceStateMap.entrySet()) {
                        ServiceState service = entry.getValue();
                        pw.println(entry.getKey() + ": " + service);

                        pw.increaseIndent();

                        pw.println("sessionTokens:");
                        pw.increaseIndent();
                        for (IBinder token : service.sessionTokens) {
                            pw.println("" + token);
                        }
                        pw.decreaseIndent();

                        pw.println("service: " + service.service);
                        pw.println("callback: " + service.callback);
                        pw.println("bound: " + service.bound);
                        pw.println("reconnecting: " + service.reconnecting);

                        pw.decreaseIndent();
                    }
                    pw.decreaseIndent();

                    pw.println("sessionStateMap: ITvInputSession -> SessionState");
                    pw.increaseIndent();
                    for (Map.Entry<IBinder, SessionState> entry :
                            userState.sessionStateMap.entrySet()) {
                        SessionState session = entry.getValue();
                        pw.println(entry.getKey() + ": " + session);

                        pw.increaseIndent();
                        pw.println("inputId: " + session.inputId);
                        pw.println("sessionId: " + session.sessionId);
                        pw.println("client: " + session.client);
                        pw.println("seq: " + session.seq);
                        pw.println("callingUid: " + session.callingUid);
                        pw.println("callingPid: " + session.callingPid);
                        pw.println("userId: " + session.userId);
                        pw.println("sessionToken: " + session.sessionToken);
                        pw.println("session: " + session.session);
                        pw.println("hardwareSessionToken: " + session.hardwareSessionToken);
                        pw.decreaseIndent();
                    }
                    pw.decreaseIndent();

                    pw.println("mCallbacks:");
                    pw.increaseIndent();
                    int n = userState.mCallbacks.beginBroadcast();
                    for (int j = 0; j < n; ++j) {
                        pw.println(userState.mCallbacks.getRegisteredCallbackItem(j));
                    }
                    userState.mCallbacks.finishBroadcast();
                    pw.decreaseIndent();

                    pw.println("mainSessionToken: " + userState.mainSessionToken);
                    pw.decreaseIndent();
                }
            }
            mTvInputHardwareManager.dump(fd, writer, args);
        }
    }

    // get the actual input id of the specific sessionState.
    // e.g. if an HDMI port has a CEC device plugged in, the actual input id of the HDMI
    // session should be the input id of CEC device instead of the default HDMI input id.
    @GuardedBy("mLock")
    private String getSessionActualInputId(SessionState sessionState) {
        UserState userState = getOrCreateUserStateLocked(sessionState.userId);
        TvInputState tvInputState = userState.inputMap.get(sessionState.inputId);
        if (tvInputState == null) {
            Slog.w(TAG, "No TvInputState for sessionState.inputId " + sessionState.inputId);
            return sessionState.inputId;
        }
        TvInputInfo tvInputInfo = tvInputState.info;
        if (tvInputInfo == null) {
            Slog.w(TAG, "TvInputInfo is null for input id " + sessionState.inputId);
            return sessionState.inputId;
        }

        String sessionActualInputId = sessionState.inputId;
        switch (tvInputInfo.getType()) {
            case TvInputInfo.TYPE_HDMI:
                // TODO: find a better approach towards active CEC device in future
                Map<String, List<String>> hdmiParentInputMap =
                        mTvInputHardwareManager.getHdmiParentInputMap();
                if (hdmiParentInputMap.containsKey(sessionState.inputId)) {
                    List<String> parentInputList = hdmiParentInputMap.get(sessionState.inputId);
                    sessionActualInputId = parentInputList.get(0);
                }
                break;
            default:
                break;
        }
        return sessionActualInputId;
    }

    @Nullable
    private static TvInputState getTvInputState(
            SessionState sessionState,
            @Nullable UserState userState) {
        if (userState != null) {
            return userState.inputMap.get(sessionState.inputId);
        }
        return null;
    }

    @GuardedBy("mLock")
    private List<TunedInfo> getCurrentTunedInfosInternalLocked(
            UserState userState, int callingPid, int callingUid) {
        List<TunedInfo> channelInfos = new ArrayList<>();
        boolean watchedProgramsAccess = hasAccessWatchedProgramsPermission(callingPid, callingUid);
        for (SessionState state : userState.sessionStateMap.values()) {
            if (state.isCurrent) {
                Integer appTag;
                int appType;
                if (state.callingUid == callingUid) {
                    appTag = APP_TAG_SELF;
                    appType = TunedInfo.APP_TYPE_SELF;
                } else {
                    appTag = userState.mAppTagMap.get(state.callingUid);
                    if (appTag == null) {
                        appTag = userState.mNextAppTag++;
                        userState.mAppTagMap.put(state.callingUid, appTag);
                    }
                    appType = isSystemApp(state.componentName.getPackageName())
                            ? TunedInfo.APP_TYPE_SYSTEM
                            : TunedInfo.APP_TYPE_NON_SYSTEM;
                }
                channelInfos.add(new TunedInfo(
                        state.inputId,
                        watchedProgramsAccess ? state.currentChannel : null,
                        state.isRecordingSession,
                        state.isVisible,
                        state.isMainSession,
                        appType,
                        appTag));
            }
        }
        return channelInfos;
    }

    private boolean hasAccessWatchedProgramsPermission(int callingPid, int callingUid) {
        return mContext.checkPermission(PERMISSION_ACCESS_WATCHED_PROGRAMS, callingPid, callingUid)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isSystemApp(String pkg) {
        try {
            return (mContext.getPackageManager().getApplicationInfo(pkg, 0).flags
                    & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Log Tune state changes to {@link FrameworkStatsLog}.
     *
     * <p><b>WARNING</b> Any changes to this field should be carefully reviewed for privacy.
     * Inspect the code at:
     *
     * <ul>
     *   <li>framework/base/cmds/statsd/src/atoms.proto#TifTuneState
     *   <li>{@link #logTuneStateChanged}
     *   <li>{@link TvInputManagerService.BinderService#createSession}
     *   <li>{@link SessionState#sessionId}
     * </ul>
     */
    private void logTuneStateChanged(int state, SessionState sessionState,
            @Nullable TvInputState inputState) {
        int tisUid = Process.INVALID_UID;
        int inputType = FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__TYPE__TIF_INPUT_TYPE_UNKNOWN;
        int inputId = 0;
        int hdmiPort = 0;
        if (inputState != null) {
            tisUid = inputState.uid;
            inputType = inputState.info.getType();
            if (inputType == TvInputInfo.TYPE_TUNER) {
                inputType = FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__TYPE__TUNER;
            }
            inputId = inputState.inputNumber;
            HdmiDeviceInfo hdmiDeviceInfo = inputState.info.getHdmiDeviceInfo();
            if (hdmiDeviceInfo != null) {
                hdmiPort = hdmiDeviceInfo.getPortId();
            }
        }
        FrameworkStatsLog.write(FrameworkStatsLog.TIF_TUNE_CHANGED,
                new int[]{sessionState.callingUid,
                        tisUid},
                new String[]{"tif_player", "tv_input_service"},
                state,
                sessionState.sessionId,
                inputType,
                inputId,
                hdmiPort);
    }

    @GuardedBy("mLock")
    private void logExternalInputEvent(int eventType, String inputId, SessionState sessionState) {
        UserState userState = getOrCreateUserStateLocked(sessionState.userId);
        TvInputState tvInputState = userState.inputMap.get(inputId);
        if (tvInputState == null) {
            Slog.w(TAG, "Cannot find input state for input id " + inputId);
            // If input id is not found, try to find the input id of this sessionState.
            inputId = sessionState.inputId;
            tvInputState = userState.inputMap.get(inputId);
        }
        if (tvInputState == null) {
            Slog.w(TAG, "Cannot find input state for sessionState.inputId " + inputId);
            return;
        }
        TvInputInfo tvInputInfo = tvInputState.info;
        if (tvInputInfo == null) {
            Slog.w(TAG, "TvInputInfo is null for input id " + inputId);
            return;
        }
        int inputState = tvInputState.state;
        int inputType = tvInputInfo.getType();
        String displayName = tvInputInfo.loadLabel(mContext).toString();
        // For non-CEC input, the value of vendorId is 0xFFFFFF (16777215 in decimal).
        int vendorId = 16777215;
        // For non-HDMI input, the value of hdmiPort is -1.
        int hdmiPort = -1;
        String tifSessionId = sessionState.sessionId;

        if (tvInputInfo.getType() == TvInputInfo.TYPE_HDMI) {
            HdmiDeviceInfo hdmiDeviceInfo = tvInputInfo.getHdmiDeviceInfo();
            if (hdmiDeviceInfo != null) {
                hdmiPort = hdmiDeviceInfo.getPortId();
                if (hdmiDeviceInfo.isCecDevice()) {
                    displayName = hdmiDeviceInfo.getDisplayName();
                    if (mExternalInputLoggingDisplayNameFilterEnabled) {
                        displayName = filterExternalInputLoggingDisplayName(displayName);
                    }
                    vendorId = hdmiDeviceInfo.getVendorId();
                }
            }
        }

        FrameworkStatsLog.write(FrameworkStatsLog.EXTERNAL_TV_INPUT_EVENT, eventType, inputState,
                inputType, vendorId, hdmiPort, tifSessionId, displayName);
    }

    private String filterExternalInputLoggingDisplayName(String displayName) {
        String nullDisplayName = "NULL_DISPLAY_NAME", filteredDisplayName = "FILTERED_DISPLAY_NAME";
        if (displayName == null) {
            return nullDisplayName;
        }
        if (mExternalInputLoggingDeviceOnScreenDisplayNames.contains(displayName)) {
            return displayName;
        }
        for (String brandName : mExternalInputLoggingDeviceBrandNames) {
            if (displayName.toUpperCase().contains(brandName.toUpperCase())) {
                return brandName;
            }
        }
        return filteredDisplayName;
    }

    private static final class UserState {
        // A mapping from the TV input id to its TvInputState.
        private Map<String, TvInputState> inputMap = new HashMap<>();

        // A set of all TV input packages.
        private final Set<String> packageSet = new HashSet<>();

        // A list of all TV content rating systems defined.
        private final List<TvContentRatingSystemInfo>
                contentRatingSystemList = new ArrayList<>();

        // A mapping from the token of a client to its state.
        private final Map<IBinder, ClientState> clientStateMap = new HashMap<>();

        // A mapping from the name of a TV input service to its state.
        private final Map<ComponentName, ServiceState> serviceStateMap = new HashMap<>();

        // A mapping from the token of a TV input session to its state.
        private final Map<IBinder, SessionState> sessionStateMap = new HashMap<>();

        // A list of callbacks.
        private final RemoteCallbackList<ITvInputManagerCallback> mCallbacks =
                new RemoteCallbackList<>();

        private final Map<ITvInputManagerCallback, Pair<Integer, Integer>> callbackPidUidMap =
                new HashMap<>();

        // The token of a "main" TV input session.
        private IBinder mainSessionToken = null;

        // Persistent data store for all internal settings maintained by the TV input manager
        // service.
        private final PersistentDataStore persistentDataStore;

        @GuardedBy("TvInputManagerService.this.mLock")
        private final Map<Integer, Integer> mAppTagMap = new HashMap<>();
        @GuardedBy("TvInputManagerService.this.mLock")
        private int mNextAppTag = 1;

        private UserState(Context context, int userId) {
            persistentDataStore = new PersistentDataStore(context, userId);
        }
    }

    private final class ClientState implements IBinder.DeathRecipient {
        private final List<IBinder> sessionTokens = new ArrayList<>();

        private IBinder clientToken;
        private final int userId;

        ClientState(IBinder clientToken, int userId) {
            this.clientToken = clientToken;
            this.userId = userId;
        }

        public boolean isEmpty() {
            return sessionTokens.isEmpty();
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(userId);
                // DO NOT remove the client state of clientStateMap in this method. It will be
                // removed in releaseSessionLocked().
                ClientState clientState = userState.clientStateMap.get(clientToken);
                if (clientState != null) {
                    while (clientState.sessionTokens.size() > 0) {
                        IBinder sessionToken = clientState.sessionTokens.get(0);
                        releaseSessionLocked(
                                sessionToken, Process.SYSTEM_UID, userId);
                        // the releaseSessionLocked function may return before the sessionToken
                        // is removed if the related sessionState is null. So need to check again
                        // to avoid death curculation.
                        if (clientState.sessionTokens.contains(sessionToken)) {
                            Slog.d(TAG, "remove sessionToken " + sessionToken + " for " + clientToken);
                            clientState.sessionTokens.remove(sessionToken);
                        }
                    }
                }
                clientToken = null;
            }
        }
    }

    private final class ServiceState {
        private final List<IBinder> sessionTokens = new ArrayList<>();
        private final ServiceConnection connection;
        private final ComponentName component;
        private final boolean isHardware;
        private final Map<String, TvInputInfo> hardwareInputMap = new HashMap<>();
        private final List<TvInputHardwareInfo> hardwareDeviceRemovedBuffer = new ArrayList<>();
        private final List<HdmiDeviceInfo> hdmiDeviceRemovedBuffer = new ArrayList<>();
        private final List<HdmiDeviceInfo> hdmiDeviceUpdatedBuffer = new ArrayList<>();

        private ITvInputService service;
        private ServiceCallback callback;
        private boolean bound;
        private boolean reconnecting;
        private boolean neverConnected;

        private ServiceState(ComponentName component, int userId) {
            this.component = component;
            this.connection = new InputServiceConnection(component, userId);
            this.isHardware = hasHardwarePermission(mContext.getPackageManager(), component);
            this.neverConnected = true;
        }
    }

    private static final class TvInputState {

        /** A TvInputInfo object which represents the TV input. */
        private TvInputInfo info;

        /**
         * ID unique to a specific TvInputService.
         */
        private int inputNumber;

        /**
         * The kernel user-ID that has been assigned to the application the TvInput is a part of.
         *
         * <p>
         * Currently this is not a unique ID (multiple applications can have
         * the same uid).
         */
        private int uid;

        /**
         * The state of TV input.
         *
         * <p>
         * Connected by default
         */
        private int state = INPUT_STATE_CONNECTED;

        @Override
        public String toString() {
            return "info: " + info + "; state: " + state;
        }
    }

    private final class SessionState implements IBinder.DeathRecipient {
        private final String inputId;

        /**
         * A randomly generated id for this this session.
         *
         * <p>This field contains no user or device reference and is large enough to be
         * effectively globally unique.
         *
         * <p><b>WARNING</b> Any changes to this field should be carefully reviewed for privacy.
         * Inspect the code at:
         *
         * <ul>
         *   <li>framework/base/cmds/statsd/src/atoms.proto#TifTuneState
         *   <li>{@link #logTuneStateChanged}
         *   <li>{@link TvInputManagerService.BinderService#createSession}
         *   <li>{@link SessionState#sessionId}
         * </ul>
         */
        private final String sessionId;
        private final ComponentName componentName;
        private final boolean isRecordingSession;
        private final ITvInputClient client;
        private final AttributionSource tvAppAttributionSource;
        private final int seq;
        /**
         * The {code UID} of the application that created the session.
         *
         * <p>
         * The application is usually the TIF Player.
         */
        private final int callingUid;
        /**
         * The  {@code PID} of the application that created the session.
         *
         * <p>
         * The application is usually the TIF Player.
         */
        private final int callingPid;
        private final int userId;
        private final IBinder sessionToken;
        private ITvInputSession session;
        // Not null if this session represents an external device connected to a hardware TV input.
        private IBinder hardwareSessionToken;

        private boolean isCurrent = false;
        private Uri currentChannel = null;
        private boolean isVisible = false;
        private boolean isMainSession = false;

        private SessionState(IBinder sessionToken, String inputId, ComponentName componentName,
                boolean isRecordingSession, ITvInputClient client, int seq, int callingUid,
                int callingPid, int userId, String sessionId,
                AttributionSource tvAppAttributionSource) {
            this.sessionToken = sessionToken;
            this.inputId = inputId;
            this.componentName = componentName;
            this.isRecordingSession = isRecordingSession;
            this.client = client;
            this.seq = seq;
            this.callingUid = callingUid;
            this.callingPid = callingPid;
            this.userId = userId;
            this.sessionId = sessionId;
            this.tvAppAttributionSource = tvAppAttributionSource;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                session = null;
                clearSessionAndNotifyClientLocked(this);
            }
        }
    }

    @GuardedBy("mLock")
    private void bindService(ServiceState serviceState, int userId) {
        if (serviceState.bound) {
            // We have already bound to the service so we don't try to bind again until after we
            // unbind later on.
            // For hardware services, call updateHardwareServiceConnectionDelayed() to delay the
            // possible unbinding.
            if (serviceState.isHardware) {
                updateHardwareServiceConnectionDelayed(userId);
            }
            return;
        }
        if (DEBUG) {
            Slog.d(TAG,
                    "bindServiceAsUser(service=" + serviceState.component + ", userId=" + userId
                            + ")");
        }
        Intent i =
                new Intent(TvInputService.SERVICE_INTERFACE).setComponent(serviceState.component);
        serviceState.bound = mContext.bindServiceAsUser(i, serviceState.connection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE_WHILE_AWAKE,
                new UserHandle(userId));
        if (!serviceState.bound) {
            Slog.e(TAG, "failed to bind " + serviceState.component + " for userId " + userId);
            mContext.unbindService(serviceState.connection);
        }
    }

    @GuardedBy("mLock")
    private void unbindService(ServiceState serviceState) {
        if (!serviceState.bound) {
            return;
        }
        if (DEBUG) {
            Slog.d(TAG, "unbindService(service=" + serviceState.component + ")");
        }
        mContext.unbindService(serviceState.connection);
        serviceState.bound = false;
        serviceState.service = null;
        serviceState.callback = null;
    }

    @GuardedBy("mLock")
    private void updateHardwareTvInputServiceBindingLocked(int userId) {
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services =
                pm.queryIntentServicesAsUser(new Intent(TvInputService.SERVICE_INTERFACE),
                        PackageManager.GET_SERVICES | PackageManager.GET_META_DATA, userId);
        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (!android.Manifest.permission.BIND_TV_INPUT.equals(si.permission)) {
                continue;
            }
            ComponentName component = new ComponentName(si.packageName, si.name);
            if (hasHardwarePermission(pm, component)) {
                updateServiceConnectionLocked(component, userId);
            }
        }
    }

    private void updateHardwareServiceConnectionDelayed(int userId) {
        mMessageHandler.removeMessages(MessageHandler.MSG_UPDATE_HARDWARE_TIS_BINDING);
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = userId;
        Message msg =
                mMessageHandler.obtainMessage(MessageHandler.MSG_UPDATE_HARDWARE_TIS_BINDING, args);
        mMessageHandler.sendMessageDelayed(msg, UPDATE_HARDWARE_TIS_BINDING_DELAY_IN_MILLIS);
    }

    @GuardedBy("mLock")
    private void addHardwareInputLocked(
            TvInputInfo inputInfo, ComponentName component, int userId) {
        ServiceState serviceState = getServiceStateLocked(component, userId);
        serviceState.hardwareInputMap.put(inputInfo.getId(), inputInfo);
        buildTvInputListLocked(userId, null);
    }

    @GuardedBy("mLock")
    private void removeHardwareInputLocked(String inputId, int userId) {
        if (!mTvInputHardwareManager.getInputMap().containsKey(inputId)) {
            return;
        }
        ComponentName component = mTvInputHardwareManager.getInputMap().get(inputId).getComponent();
        ServiceState serviceState = getServiceStateLocked(component, userId);
        boolean removed = serviceState.hardwareInputMap.remove(inputId) != null;
        if (removed) {
            buildTvInputListLocked(userId, null);
            mTvInputHardwareManager.removeHardwareInput(inputId);
        }
    }

    private final class InputServiceConnection implements ServiceConnection {
        private final ComponentName mComponent;
        private final int mUserId;

        private InputServiceConnection(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName component, IBinder service) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnected(component=" + component + ")");
            }
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mUserId);
                if (userState == null) {
                    // The user was removed while connecting.
                    mContext.unbindService(this);
                    return;
                }
                ServiceState serviceState = userState.serviceStateMap.get(mComponent);
                serviceState.service = ITvInputService.Stub.asInterface(service);
                serviceState.neverConnected = false;

                // Register a callback, if we need to.
                if (serviceState.isHardware && serviceState.callback == null) {
                    serviceState.callback = new ServiceCallback(mComponent, mUserId);
                    try {
                        serviceState.service.registerCallback(serviceState.callback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in registerCallback", e);
                    }
                }

                for (TvInputState inputState : userState.inputMap.values()) {
                    if (inputState.info.getComponent().equals(component)
                            && inputState.state != INPUT_STATE_CONNECTED) {
                        notifyInputStateChangedLocked(userState, inputState.info.getId(),
                                inputState.state, null);
                    }
                }

                if (serviceState.isHardware) {
                    for (TvInputHardwareInfo hardwareToBeRemoved :
                            serviceState.hardwareDeviceRemovedBuffer) {
                        try {
                            serviceState.service.notifyHardwareRemoved(hardwareToBeRemoved);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in hardwareDeviceRemovedBuffer", e);
                        }
                    }
                    serviceState.hardwareDeviceRemovedBuffer.clear();
                    for (HdmiDeviceInfo hdmiDeviceToBeRemoved :
                            serviceState.hdmiDeviceRemovedBuffer) {
                        try {
                            serviceState.service.notifyHdmiDeviceRemoved(hdmiDeviceToBeRemoved);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in hdmiDeviceRemovedBuffer", e);
                        }
                    }
                    serviceState.hdmiDeviceRemovedBuffer.clear();
                    for (TvInputHardwareInfo hardware : mTvInputHardwareManager.getHardwareList()) {
                        try {
                            serviceState.service.notifyHardwareAdded(hardware);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in notifyHardwareAdded", e);
                        }
                    }
                    for (HdmiDeviceInfo device : mTvInputHardwareManager.getHdmiDeviceList()) {
                        try {
                            serviceState.service.notifyHdmiDeviceAdded(device);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in notifyHdmiDeviceAdded", e);
                        }
                    }
                    for (HdmiDeviceInfo hdmiDeviceToBeUpdated :
                            serviceState.hdmiDeviceUpdatedBuffer) {
                        try {
                            serviceState.service.notifyHdmiDeviceUpdated(hdmiDeviceToBeUpdated);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in hdmiDeviceUpdatedBuffer", e);
                        }
                    }
                    serviceState.hdmiDeviceUpdatedBuffer.clear();
                }

                List<IBinder> tokensToBeRemoved = new ArrayList<>();

                // And create sessions, if any.
                for (IBinder sessionToken : serviceState.sessionTokens) {
                    if (!createSessionInternalLocked(serviceState.service, sessionToken, mUserId)) {
                        tokensToBeRemoved.add(sessionToken);
                    }
                }

                for (IBinder sessionToken : tokensToBeRemoved) {
                    removeSessionStateLocked(sessionToken, mUserId);
                }

                if (serviceState.isHardware) {
                    updateHardwareServiceConnectionDelayed(mUserId);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceDisconnected(component=" + component + ")");
            }
            if (!mComponent.equals(component)) {
                throw new IllegalArgumentException("Mismatched ComponentName: "
                        + mComponent + " (expected), " + component + " (actual).");
            }
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mUserId);
                ServiceState serviceState = userState.serviceStateMap.get(mComponent);
                if (serviceState != null) {
                    serviceState.reconnecting = true;
                    serviceState.bound = false;
                    serviceState.service = null;
                    serviceState.callback = null;

                    abortPendingCreateSessionRequestsLocked(serviceState, null, mUserId);
                }
            }
        }
    }

    private final class ServiceCallback extends ITvInputServiceCallback.Stub {
        private final ComponentName mComponent;
        private final int mUserId;

        ServiceCallback(ComponentName component, int userId) {
            mComponent = component;
            mUserId = userId;
        }

        private void ensureHardwarePermission() {
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("The caller does not have hardware permission");
            }
        }

        private void ensureValidInput(TvInputInfo inputInfo) {
            if (inputInfo.getId() == null || !mComponent.equals(inputInfo.getComponent())) {
                throw new IllegalArgumentException("Invalid TvInputInfo");
            }
        }

        public void addHardwareInput(int deviceId, TvInputInfo inputInfo) {
            ensureHardwarePermission();
            ensureValidInput(inputInfo);
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
                    if (serviceState.hardwareInputMap.containsKey(inputInfo.getId())) {
                        return;
                    }
                    Slog.d("ServiceCallback",
                            "addHardwareInput: device id " + deviceId + ", "
                                    + inputInfo.toString());
                    mTvInputHardwareManager.addHardwareInput(deviceId, inputInfo);
                    addHardwareInputLocked(inputInfo, mComponent, mUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void addHdmiInput(int id, TvInputInfo inputInfo) {
            ensureHardwarePermission();
            ensureValidInput(inputInfo);
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
                    if (serviceState.hardwareInputMap.containsKey(inputInfo.getId())) {
                        return;
                    }
                    mTvInputHardwareManager.addHdmiInput(id, inputInfo);
                    addHardwareInputLocked(inputInfo, mComponent, mUserId);
                    if (mOnScreenInputId != null && mOnScreenSessionState != null) {
                        if (TextUtils.equals(mOnScreenInputId, inputInfo.getParentId())) {
                            // catch the use case when a CEC device is plugged in an HDMI port,
                            // and TV app does not explicitly call tune() to the added CEC input.
                            logExternalInputEvent(
                                    FrameworkStatsLog.EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__TUNED,
                                    inputInfo.getId(), mOnScreenSessionState);
                            mOnScreenInputId = inputInfo.getId();
                        } else if (TextUtils.equals(mOnScreenInputId, inputInfo.getId())) {
                            // catch the use case when a CEC device disconnects itself
                            // and reconnects to update info.
                            logExternalInputEvent(
                                    FrameworkStatsLog
                                        .EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__DEVICE_INFO_UPDATED,
                                    mOnScreenInputId, mOnScreenSessionState);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        public void removeHardwareInput(String inputId) {
            ensureHardwarePermission();
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    Slog.d("ServiceCallback",
                            "removeHardwareInput " + inputId + " by " + mComponent);
                    removeHardwareInputLocked(inputId, mUserId);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private final class SessionCallback extends ITvInputSessionCallback.Stub {
        private final SessionState mSessionState;
        private final InputChannel[] mChannels;

        SessionCallback(SessionState sessionState, InputChannel[] channels) {
            mSessionState = sessionState;
            mChannels = channels;
        }

        @Override
        public void onSessionCreated(ITvInputSession session, IBinder hardwareSessionToken) {
            if (DEBUG) {
                Slog.d(TAG, "onSessionCreated(inputId=" + mSessionState.inputId + ")");
            }
            synchronized (mLock) {
                mSessionState.session = session;
                mSessionState.hardwareSessionToken = hardwareSessionToken;
                if (session != null && addSessionTokenToClientStateLocked(session)) {
                    sendSessionTokenToClientLocked(mSessionState.client,
                            mSessionState.inputId, mSessionState.sessionToken, mChannels[0],
                            mSessionState.seq);
                } else {
                    removeSessionStateLocked(mSessionState.sessionToken, mSessionState.userId);
                    sendSessionTokenToClientLocked(mSessionState.client,
                            mSessionState.inputId, null, null, mSessionState.seq);
                }
                mChannels[0].dispose();
            }
        }

        @GuardedBy("mLock")
        private boolean addSessionTokenToClientStateLocked(ITvInputSession session) {
            try {
                session.asBinder().linkToDeath(mSessionState, 0);
            } catch (RemoteException e) {
                Slog.e(TAG, "session process has already died", e);
                return false;
            }

            IBinder clientToken = mSessionState.client.asBinder();
            UserState userState = getOrCreateUserStateLocked(mSessionState.userId);
            ClientState clientState = userState.clientStateMap.get(clientToken);
            if (clientState == null) {
                clientState = new ClientState(clientToken, mSessionState.userId);
                try {
                    clientToken.linkToDeath(clientState, 0);
                } catch (RemoteException e) {
                    Slog.e(TAG, "client process has already died", e);
                    return false;
                }
                userState.clientStateMap.put(clientToken, clientState);
            }
            clientState.sessionTokens.add(mSessionState.sessionToken);
            return true;
        }

        @Override
        public void onChannelRetuned(Uri channelUri) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onChannelRetuned(" + channelUri + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    // TODO: Consider adding this channel change in the watch log. When we do
                    // that, how we can protect the watch log from malicious tv inputs should
                    // be addressed. e.g. add a field which represents where the channel change
                    // originated from.
                    mSessionState.client.onChannelRetuned(channelUri, mSessionState.seq);
                    if (!mSessionState.isCurrent
                            || !Objects.equals(mSessionState.currentChannel, channelUri)) {
                        UserState userState = getOrCreateUserStateLocked(mSessionState.userId);
                        mSessionState.isCurrent = true;
                        mSessionState.currentChannel = channelUri;
                        notifyCurrentChannelInfosUpdatedLocked(userState);
                        if (!mSessionState.isRecordingSession) {
                            String sessionActualInputId = getSessionActualInputId(mSessionState);
                            if (!TextUtils.equals(mOnScreenInputId, sessionActualInputId)) {
                                logExternalInputEvent(
                                        FrameworkStatsLog
                                                .EXTERNAL_TV_INPUT_EVENT__EVENT_TYPE__TUNED,
                                        sessionActualInputId, mSessionState);
                            }
                            mOnScreenInputId = sessionActualInputId;
                            mOnScreenSessionState = mSessionState;
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onChannelRetuned", e);
                }
            }
        }

        @Override
        public void onAudioPresentationsChanged(List<AudioPresentation> audioPresentations) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAudioPresentationChanged(" + audioPresentations + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAudioPresentationsChanged(audioPresentations,
                                                                     mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAudioPresentationsChanged", e);
                }
            }
        }

        @Override
        public void onAudioPresentationSelected(int presentationId, int programId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAudioPresentationSelected(presentationId=" + presentationId
                                                            + ", programId=" + programId + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAudioPresentationSelected(presentationId, programId,
                                                                     mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAudioPresentationSelected", e);
                }
            }
        }

        @Override
        public void onTracksChanged(List<TvTrackInfo> tracks) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTracksChanged(" + tracks + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTracksChanged(tracks, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTracksChanged", e);
                }
            }
        }

        @Override
        public void onTrackSelected(int type, String trackId) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTrackSelected(type=" + type + ", trackId=" + trackId + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTrackSelected(type, trackId, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTrackSelected", e);
                }
            }
        }

        @Override
        public void onVideoAvailable() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onVideoAvailable()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                TvInputState tvInputState = getTvInputState(mSessionState,
                        getUserStateLocked(mCurrentUserId));
                try {
                    mSessionState.client.onVideoAvailable(mSessionState.seq);
                    logTuneStateChanged(
                            FrameworkStatsLog.TIF_TUNE_STATE_CHANGED__STATE__VIDEO_AVAILABLE,
                            mSessionState, tvInputState);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onVideoAvailable", e);
                }
            }
        }

        @Override
        public void onVideoUnavailable(int reason) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onVideoUnavailable(" + reason + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                TvInputState tvInputState = getTvInputState(mSessionState,
                        getUserStateLocked(mCurrentUserId));
                try {
                    mSessionState.client.onVideoUnavailable(reason, mSessionState.seq);
                    int loggedReason = getVideoUnavailableReasonForStatsd(reason);
                    logTuneStateChanged(loggedReason, mSessionState, tvInputState);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onVideoUnavailable", e);
                }
            }
        }

        @Override
        public void onVideoFreezeUpdated(boolean isFrozen) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onVideoFreezeUpdated(" + isFrozen + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onVideoFreezeUpdated(isFrozen, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onVideoFreezeUpdated", e);
                }
            }
        }

        @Override
        public void onContentAllowed() {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onContentAllowed()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onContentAllowed(mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onContentAllowed", e);
                }
            }
        }

        @Override
        public void onContentBlocked(String rating) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onContentBlocked()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onContentBlocked(rating, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onContentBlocked", e);
                }
            }
        }

        @Override
        public void onLayoutSurface(int left, int top, int right, int bottom) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onLayoutSurface (left=" + left + ", top=" + top
                            + ", right=" + right + ", bottom=" + bottom + ",)");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onLayoutSurface(left, top, right, bottom,
                            mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onLayoutSurface", e);
                }
            }
        }

        @Override
        public void onSessionEvent(String eventType, Bundle eventArgs) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onEvent(eventType=" + eventType + ", eventArgs=" + eventArgs
                            + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onSessionEvent(eventType, eventArgs, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onSessionEvent", e);
                }
            }
        }

        @Override
        public void onTimeShiftStatusChanged(int status) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTimeShiftStatusChanged(status=" + status + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTimeShiftStatusChanged(status, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTimeShiftStatusChanged", e);
                }
            }
        }

        @Override
        public void onTimeShiftStartPositionChanged(long timeMs) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTimeShiftStartPositionChanged(timeMs=" + timeMs + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTimeShiftStartPositionChanged(timeMs, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTimeShiftStartPositionChanged", e);
                }
            }
        }

        @Override
        public void onTimeShiftCurrentPositionChanged(long timeMs) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTimeShiftCurrentPositionChanged(timeMs=" + timeMs + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTimeShiftCurrentPositionChanged(timeMs,
                            mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTimeShiftCurrentPositionChanged", e);
                }
            }
        }

        @Override
        public void onAitInfoUpdated(AitInfo aitInfo) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAitInfoUpdated(" + aitInfo + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAitInfoUpdated(aitInfo, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAitInfoUpdated", e);
                }
            }
        }

        @Override
        public void onSignalStrength(int strength) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onSignalStrength(" + strength + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onSignalStrength(strength, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onSignalStrength", e);
                }
            }
        }

        @Override
        public void onCueingMessageAvailability(boolean available) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onCueingMessageAvailability(" + available + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onCueingMessageAvailability(available, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onCueingMessageAvailability", e);
                }
            }
        }

        @Override
        public void onTimeShiftMode(int mode) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTimeShiftMode(" + mode + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTimeShiftMode(mode, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTimeShiftMode", e);
                }
            }
        }

        @Override
        public void onAvailableSpeeds(float[] speeds) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAvailableSpeeds(" + Arrays.toString(speeds) + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAvailableSpeeds(speeds, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAvailableSpeeds", e);
                }
            }
        }

        @Override
        public void onTuned(Uri channelUri) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTuned()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTuned(channelUri, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTuned", e);
                }
            }
        }

        @Override
        public void onTvMessage(int type, Bundle data) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onTvMessage(type=" + type + ", data=" + data + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onTvMessage(type, data, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onTvMessage", e);
                }
            }
        }

        // For the recording session only
        @Override
        public void onRecordingStopped(Uri recordedProgramUri) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onRecordingStopped(recordedProgramUri=" + recordedProgramUri
                            + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onRecordingStopped(recordedProgramUri, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onRecordingStopped", e);
                }
            }
        }

        // For the recording session only
        @Override
        public void onError(int error) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onError(error=" + error + ")");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onError(error, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onError", e);
                }
            }
        }

        @Override
        public void onBroadcastInfoResponse(BroadcastInfoResponse response) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onBroadcastInfoResponse()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onBroadcastInfoResponse(response, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onBroadcastInfoResponse", e);
                }
            }
        }

        @Override
        public void onAdResponse(AdResponse response) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAdResponse()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAdResponse(response, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAdResponse", e);
                }
            }
        }

        @Override
        public void onAdBufferConsumed(AdBuffer buffer) {
            synchronized (mLock) {
                if (DEBUG) {
                    Slog.d(TAG, "onAdBufferConsumed()");
                }
                if (mSessionState.session == null || mSessionState.client == null) {
                    return;
                }
                try {
                    mSessionState.client.onAdBufferConsumed(buffer, mSessionState.seq);
                } catch (RemoteException e) {
                    Slog.e(TAG, "error in onAdBufferConsumed", e);
                } finally {
                    if (buffer != null) {
                        buffer.getSharedMemory().close();
                    }
                }
            }
        }
    }

    @VisibleForTesting
    static int getVideoUnavailableReasonForStatsd(
            @TvInputManager.VideoUnavailableReason int reason) {
        int loggedReason = reason + FrameworkStatsLog
                .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN;
        if (loggedReason < FrameworkStatsLog
                .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN
                || loggedReason > FrameworkStatsLog
                .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_CAS_UNKNOWN) {
            loggedReason = FrameworkStatsLog
                    .TIF_TUNE_STATE_CHANGED__STATE__VIDEO_UNAVAILABLE_REASON_UNKNOWN;
        }
        return loggedReason;
    }

    @GuardedBy("mLock")
    private UserState getUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    private final class MessageHandler extends Handler {
        // There are only two kinds of watch events that can happen on the system:
        // 1. The current TV input session is tuned to a new channel.
        // 2. The session is released for some reason.
        // The former indicates the end of the previous log entry, if any, followed by the start of
        // a new entry. The latter indicates the end of the most recent entry for the given session.
        // Here the system supplies the database the smallest set of information only that is
        // sufficient to consolidate the log entries while minimizing database operations in the
        // system service.
        static final int MSG_LOG_WATCH_START = 1;
        static final int MSG_LOG_WATCH_END = 2;
        static final int MSG_SWITCH_CONTENT_RESOLVER = 3;
        static final int MSG_UPDATE_HARDWARE_TIS_BINDING = 4;

        private ContentResolver mContentResolver;

        MessageHandler(ContentResolver contentResolver, Looper looper) {
            super(looper);
            mContentResolver = contentResolver;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOG_WATCH_START: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    String packageName = (String) args.arg1;
                    long watchStartTime = (long) args.arg2;
                    long channelId = (long) args.arg3;
                    Bundle tuneParams = (Bundle) args.arg4;
                    IBinder sessionToken = (IBinder) args.arg5;

                    ContentValues values = new ContentValues();
                    values.put(TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME, packageName);
                    values.put(TvContract.WatchedPrograms.COLUMN_WATCH_START_TIME_UTC_MILLIS,
                            watchStartTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_CHANNEL_ID, channelId);
                    if (tuneParams != null) {
                        values.put(TvContract.WatchedPrograms.COLUMN_INTERNAL_TUNE_PARAMS,
                                encodeTuneParams(tuneParams));
                    }
                    values.put(TvContract.WatchedPrograms.COLUMN_INTERNAL_SESSION_TOKEN,
                            sessionToken.toString());

                    try{
                        mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                    }catch(IllegalArgumentException ex){
                        Slog.w(TAG, "error in insert db for MSG_LOG_WATCH_START", ex);
                    }
                    args.recycle();
                    break;
                }
                case MSG_LOG_WATCH_END: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    IBinder sessionToken = (IBinder) args.arg1;
                    long watchEndTime = (long) args.arg2;

                    ContentValues values = new ContentValues();
                    values.put(TvContract.WatchedPrograms.COLUMN_WATCH_END_TIME_UTC_MILLIS,
                            watchEndTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_INTERNAL_SESSION_TOKEN,
                            sessionToken.toString());

                    try{
                        mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                    }catch(IllegalArgumentException ex){
                        Slog.w(TAG, "error in insert db for MSG_LOG_WATCH_END", ex);
                    }
                    args.recycle();
                    break;
                }
                case MSG_SWITCH_CONTENT_RESOLVER: {
                    mContentResolver = (ContentResolver) msg.obj;
                    break;
                }
                case MSG_UPDATE_HARDWARE_TIS_BINDING:
                    SomeArgs args = (SomeArgs) msg.obj;
                    int userId = (int) args.arg1;
                    synchronized (mLock) {
                        updateHardwareTvInputServiceBindingLocked(userId);
                    }
                    args.recycle();
                    break;
                default: {
                    Slog.w(TAG, "unhandled message code: " + msg.what);
                    break;
                }
            }
        }

        private String encodeTuneParams(Bundle tuneParams) {
            StringBuilder builder = new StringBuilder();
            Set<String> keySet = tuneParams.keySet();
            Iterator<String> it = keySet.iterator();
            while (it.hasNext()) {
                String key = it.next();
                Object value = tuneParams.get(key);
                if (value == null) {
                    continue;
                }
                builder.append(replaceEscapeCharacters(key));
                builder.append("=");
                builder.append(replaceEscapeCharacters(value.toString()));
                if (it.hasNext()) {
                    builder.append(", ");
                }
            }
            return builder.toString();
        }

        private String replaceEscapeCharacters(String src) {
            final char ESCAPE_CHARACTER = '%';
            final String ENCODING_TARGET_CHARACTERS = "%=,";
            StringBuilder builder = new StringBuilder();
            for (char ch : src.toCharArray()) {
                if (ENCODING_TARGET_CHARACTERS.indexOf(ch) >= 0) {
                    builder.append(ESCAPE_CHARACTER);
                }
                builder.append(ch);
            }
            return builder.toString();
        }
    }

    private final class HardwareListener implements TvInputHardwareManager.Listener {
        @Override
        public void onStateChanged(String inputId, int state) {
            synchronized (mLock) {
                setStateLocked(inputId, state, mCurrentUserId);
            }
        }

        @Override
        public void onHardwareDeviceAdded(TvInputHardwareInfo info) {
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.isHardware) {
                        continue;
                    }
                    try {
                        bindService(serviceState, mCurrentUserId);
                        if (serviceState.service != null) {
                            serviceState.service.notifyHardwareAdded(info);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHardwareAdded", e);
                    }
                }
                updateHardwareServiceConnectionDelayed(mCurrentUserId);
            }
        }

        @Override
        public void onHardwareDeviceRemoved(TvInputHardwareInfo info) {
            synchronized (mLock) {
                String relatedInputId =
                        mTvInputHardwareManager.getHardwareInputIdMap().get(info.getDeviceId());
                removeHardwareInputLocked(relatedInputId, mCurrentUserId);
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.isHardware) {
                        continue;
                    }
                    try {
                        bindService(serviceState, mCurrentUserId);
                        if (serviceState.service != null) {
                            serviceState.service.notifyHardwareRemoved(info);
                        } else {
                            serviceState.hardwareDeviceRemovedBuffer.add(info);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHardwareRemoved", e);
                    }
                }
                updateHardwareServiceConnectionDelayed(mCurrentUserId);
            }
        }

        @Override
        public void onHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.isHardware) {
                        continue;
                    }
                    try {
                        bindService(serviceState, mCurrentUserId);
                        if (serviceState.service != null) {
                            serviceState.service.notifyHdmiDeviceAdded(deviceInfo);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHdmiDeviceAdded", e);
                    }
                }
                updateHardwareServiceConnectionDelayed(mCurrentUserId);
            }
        }

        @Override
        public void onHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                String relatedInputId =
                        mTvInputHardwareManager.getHdmiInputIdMap().get(deviceInfo.getId());
                removeHardwareInputLocked(relatedInputId, mCurrentUserId);
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.isHardware) {
                        continue;
                    }
                    try {
                        bindService(serviceState, mCurrentUserId);
                        if (serviceState.service != null) {
                            serviceState.service.notifyHdmiDeviceRemoved(deviceInfo);
                        } else {
                            serviceState.hdmiDeviceRemovedBuffer.add(deviceInfo);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHdmiDeviceRemoved", e);
                    }
                }
                updateHardwareServiceConnectionDelayed(mCurrentUserId);
            }
        }

        @Override
        public void onHdmiDeviceUpdated(String inputId, HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                Integer state;
                switch (deviceInfo.getDevicePowerStatus()) {
                    case HdmiControlManager.POWER_STATUS_ON:
                        state = INPUT_STATE_CONNECTED;
                        break;
                    case HdmiControlManager.POWER_STATUS_STANDBY:
                    case HdmiControlManager.POWER_STATUS_TRANSIENT_TO_ON:
                    case HdmiControlManager.POWER_STATUS_TRANSIENT_TO_STANDBY:
                        state = INPUT_STATE_CONNECTED_STANDBY;
                        break;
                    case HdmiControlManager.POWER_STATUS_UNKNOWN:
                    default:
                        state = null;
                        break;
                }
                if (state != null) {
                    setStateLocked(inputId, state, mCurrentUserId);
                }
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.isHardware) {
                        continue;
                    }
                    try {
                        bindService(serviceState, mCurrentUserId);
                        if (serviceState.service != null) {
                            serviceState.service.notifyHdmiDeviceUpdated(deviceInfo);
                        } else {
                            serviceState.hdmiDeviceUpdatedBuffer.add(deviceInfo);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHdmiDeviceUpdated", e);
                    }
                }
                updateHardwareServiceConnectionDelayed(mCurrentUserId);
            }
        }

        @Override
        public void onTvMessage(String inputId, int type, Bundle data) {
            synchronized (mLock) {
                UserState userState = getOrCreateUserStateLocked(mCurrentUserId);
                TvInputState inputState = userState.inputMap.get(inputId);
                if (inputState == null) {
                    Slog.e(TAG, "failed to send TV message - unknown input id " + inputId);
                    return;
                }
                ServiceState serviceState = userState.serviceStateMap.get(inputState.info
                        .getComponent());
                for (IBinder token : serviceState.sessionTokens) {
                    try {
                        SessionState sessionState = getSessionStateLocked(token,
                                Binder.getCallingUid(), mCurrentUserId);
                        if (!sessionState.isRecordingSession
                                && sessionState.hardwareSessionToken != null) {
                            sessionState.session.notifyTvMessage(type, data);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onTvMessage", e);
                    }
                }
            }
        }
    }

    private static class SessionNotFoundException extends IllegalArgumentException {
        public SessionNotFoundException(String name) {
            super(name);
        }
    }

    private static class ClientPidNotFoundException extends IllegalArgumentException {
        public ClientPidNotFoundException(String name) {
            super(name);
        }
    }
}
