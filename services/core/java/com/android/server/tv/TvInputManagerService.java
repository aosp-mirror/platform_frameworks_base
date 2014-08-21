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

import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED;
import static android.media.tv.TvInputManager.INPUT_STATE_CONNECTED_STANDBY;
import static android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.OperationApplicationException;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.hdmi.HdmiControlManager;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.media.tv.ITvInputClient;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.ITvInputManager;
import android.media.tv.ITvInputManagerCallback;
import android.media.tv.ITvInputService;
import android.media.tv.ITvInputServiceCallback;
import android.media.tv.ITvInputSession;
import android.media.tv.ITvInputSessionCallback;
import android.media.tv.TvContentRating;
import android.media.tv.TvContract;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputService;
import android.media.tv.TvStreamConfig;
import android.media.tv.TvTrackInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputChannel;
import android.view.Surface;

import com.android.internal.content.PackageMonitor;
import com.android.internal.os.SomeArgs;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.IoThread;
import com.android.server.SystemService;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** This class provides a system service that manages television inputs. */
public final class TvInputManagerService extends SystemService {
    // STOPSHIP: Turn debugging off.
    private static final boolean DEBUG = true;
    private static final String TAG = "TvInputManagerService";

    private final Context mContext;
    private final TvInputHardwareManager mTvInputHardwareManager;

    private final ContentResolver mContentResolver;

    // A global lock.
    private final Object mLock = new Object();

    // ID of the current user.
    private int mCurrentUserId = UserHandle.USER_OWNER;

    // A map from user id to UserState.
    private final SparseArray<UserState> mUserStates = new SparseArray<UserState>();

    private final WatchLogHandler mWatchLogHandler;

    public TvInputManagerService(Context context) {
        super(context);

        mContext = context;
        mContentResolver = context.getContentResolver();
        mWatchLogHandler = new WatchLogHandler(IoThread.get().getLooper());

        mTvInputHardwareManager = new TvInputHardwareManager(context, new HardwareListener());

        synchronized (mLock) {
            mUserStates.put(mCurrentUserId, new UserState(mContext, mCurrentUserId));
        }
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
                buildTvInputListLocked(mCurrentUserId);
            }
        }
        mTvInputHardwareManager.onBootPhase(phase);
    }

    private void registerBroadcastReceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    buildTvInputListLocked(mCurrentUserId);
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(mCurrentUserId);
                    if (!userState.packageSet.contains(packageName)) {
                        // Not a TV input package.
                        return;
                    }
                }

                ArrayList<ContentProviderOperation> operations =
                        new ArrayList<ContentProviderOperation>();

                String selection = TvContract.BaseTvColumns.COLUMN_PACKAGE_NAME + "=?";
                String[] selectionArgs = { packageName };

                operations.add(ContentProviderOperation.newDelete(TvContract.Channels.CONTENT_URI)
                        .withSelection(selection, selectionArgs).build());
                operations.add(ContentProviderOperation.newDelete(TvContract.Programs.CONTENT_URI)
                        .withSelection(selection, selectionArgs).build());
                operations.add(ContentProviderOperation
                        .newDelete(TvContract.WatchedPrograms.CONTENT_URI)
                        .withSelection(selection, selectionArgs).build());

                ContentProviderResult[] results = null;
                try {
                    results = mContentResolver.applyBatch(TvContract.AUTHORITY, operations);
                } catch (RemoteException | OperationApplicationException e) {
                    Slog.e(TAG, "error in applyBatch" + e);
                }

                if (DEBUG) {
                    Slog.d(TAG, "onPackageRemoved(packageName=" + packageName + ", uid=" + uid
                            + ")");
                    Slog.d(TAG, "results=" + results);
                }
            }
        };
        monitor.register(mContext, null, UserHandle.ALL, true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);
        intentFilter.addAction(Intent.ACTION_USER_REMOVED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                } else if (Intent.ACTION_USER_REMOVED.equals(action)) {
                    removeUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    private static boolean hasHardwarePermission(PackageManager pm, ComponentName component) {
        return pm.checkPermission(android.Manifest.permission.TV_INPUT_HARDWARE,
                component.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private void buildTvInputListLocked(int userId) {
        UserState userState = getUserStateLocked(userId);
        userState.packageSet.clear();

        if (DEBUG) Slog.d(TAG, "buildTvInputList");
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(TvInputService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        List<TvInputInfo> inputList = new ArrayList<TvInputInfo>();
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
                    // We see this hardware TV input service for the first time; we need to
                    // prepare the ServiceState object so that we can connect to the service and
                    // let it add TvInputInfo objects to mInputList if there's any.
                    serviceState = new ServiceState(component, userId);
                    userState.serviceStateMap.put(component, serviceState);
                } else {
                    inputList.addAll(serviceState.mInputList);
                }
            } else {
                try {
                    inputList.add(TvInputInfo.createTvInputInfo(mContext, ri));
                } catch (XmlPullParserException | IOException e) {
                    Slog.e(TAG, "Failed to load TV input " + si.name, e);
                    continue;
                }
            }

            // Reconnect the service if existing input is updated.
            updateServiceConnectionLocked(component, userId);
            userState.packageSet.add(si.packageName);
        }

        Map<String, TvInputState> inputMap = new HashMap<String, TvInputState>();
        for (TvInputInfo info : inputList) {
            if (DEBUG) Slog.d(TAG, "add " + info.getId());
            TvInputState state = userState.inputMap.get(info.getId());
            if (state == null) {
                state = new TvInputState();
            }
            state.mInfo = info;
            inputMap.put(info.getId(), state);
        }

        for (String inputId : inputMap.keySet()) {
            if (!userState.inputMap.containsKey(inputId)) {
                notifyInputAddedLocked(userState, inputId);
            }
        }

        for (String inputId : userState.inputMap.keySet()) {
            if (!inputMap.containsKey(inputId)) {
                notifyInputRemovedLocked(userState, inputId);
            }
        }

        userState.inputMap.clear();
        userState.inputMap = inputMap;

        userState.ratingSystemXmlUriSet.clear();
        for (TvInputState state : userState.inputMap.values()) {
            Uri ratingSystemXmlUri = state.mInfo.getRatingSystemXmlUri();
            if (ratingSystemXmlUri != null) {
                // TODO: need to check the validation of xml format and the duplication of rating
                // systems.
                userState.ratingSystemXmlUriSet.add(state.mInfo.getRatingSystemXmlUri());
            }
        }
    }

    private void switchUser(int userId) {
        synchronized (mLock) {
            if (mCurrentUserId == userId) {
                return;
            }
            // final int oldUserId = mCurrentUserId;
            // TODO: Release services and sessions in the old user state, if needed.
            mCurrentUserId = userId;

            UserState userState = mUserStates.get(userId);
            if (userState == null) {
                userState = new UserState(mContext, userId);
            }
            mUserStates.put(userId, userState);
            buildTvInputListLocked(userId);
        }
    }

    private void removeUser(int userId) {
        synchronized (mLock) {
            UserState userState = mUserStates.get(userId);
            if (userState == null) {
                return;
            }
            // Release created sessions.
            for (SessionState state : userState.sessionStateMap.values()) {
                if (state.mSession != null) {
                    try {
                        state.mSession.release();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in release", e);
                    }
                }
            }
            userState.sessionStateMap.clear();

            // Unregister all callbacks and unbind all services.
            for (ServiceState serviceState : userState.serviceStateMap.values()) {
                if (serviceState.mCallback != null) {
                    try {
                        serviceState.mService.unregisterCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unregisterCallback", e);
                    }
                }
                mContext.unbindService(serviceState.mConnection);
            }
            userState.serviceStateMap.clear();

            userState.clientStateMap.clear();

            mUserStates.remove(userId);
        }
    }

    private UserState getUserStateLocked(int userId) {
        UserState userState = mUserStates.get(userId);
        if (userState == null) {
            throw new IllegalStateException("User state not found for user ID " + userId);
        }
        return userState;
    }

    private ServiceState getServiceStateLocked(ComponentName component, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(component);
        if (serviceState == null) {
            throw new IllegalStateException("Service state not found for " + component + " (userId="
                    + userId + ")");
        }
        return serviceState;
    }

    private SessionState getSessionStateLocked(IBinder sessionToken, int callingUid, int userId) {
        UserState userState = getUserStateLocked(userId);
        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
        if (sessionState == null) {
            throw new IllegalArgumentException("Session state not found for token " + sessionToken);
        }
        // Only the application that requested this session or the system can access it.
        if (callingUid != Process.SYSTEM_UID && callingUid != sessionState.mCallingUid) {
            throw new SecurityException("Illegal access to the session with token " + sessionToken
                    + " from uid " + callingUid);
        }
        return sessionState;
    }

    private ITvInputSession getSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        return getSessionLocked(getSessionStateLocked(sessionToken, callingUid, userId));
    }

    private ITvInputSession getSessionLocked(SessionState sessionState) {
        ITvInputSession session = sessionState.mSession;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token "
                    + sessionState.mSessionToken);
        }
        return session;
    }

    private int resolveCallingUserId(int callingPid, int callingUid, int requestedUserId,
            String methodName) {
        return ActivityManager.handleIncomingUser(callingPid, callingUid, requestedUserId, false,
                false, methodName, null);
    }

    private static boolean shouldMaintainConnection(ServiceState serviceState) {
        return !serviceState.mSessionTokens.isEmpty() || serviceState.mIsHardware;
        // TODO: Find a way to maintain connection only when necessary.
    }

    private void updateServiceConnectionLocked(ComponentName component, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(component);
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
        boolean maintainConnection = shouldMaintainConnection(serviceState);
        if (serviceState.mService == null && maintainConnection && userId == mCurrentUserId) {
            // This means that the service is not yet connected but its state indicates that we
            // have pending requests. Then, connect the service.
            if (serviceState.mBound) {
                // We have already bound to the service so we don't try to bind again until after we
                // unbind later on.
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "bindServiceAsUser(service=" + component + ", userId=" + userId + ")");
            }

            Intent i = new Intent(TvInputService.SERVICE_INTERFACE).setComponent(component);
            // Binding service may fail if the service is updating.
            // In that case, the connection will be revived in buildTvInputListLocked called by
            // onSomePackagesChanged.
            serviceState.mBound = mContext.bindServiceAsUser(
                    i, serviceState.mConnection, Context.BIND_AUTO_CREATE, new UserHandle(userId));
        } else if (serviceState.mService != null && !maintainConnection) {
            // This means that the service is already connected but its state indicates that we have
            // nothing to do with it. Then, disconnect the service.
            if (DEBUG) {
                Slog.d(TAG, "unbindService(service=" + component + ")");
            }
            mContext.unbindService(serviceState.mConnection);
            userState.serviceStateMap.remove(component);
        }
    }

    private ClientState createClientStateLocked(IBinder clientToken, int userId) {
        UserState userState = getUserStateLocked(userId);
        ClientState clientState = new ClientState(clientToken, userId);
        try {
            clientToken.linkToDeath(clientState, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Client is already died.");
        }
        userState.clientStateMap.put(clientToken, clientState);
        return clientState;
    }

    private void createSessionInternalLocked(ITvInputService service, final IBinder sessionToken,
            final int userId) {
        final UserState userState = getUserStateLocked(userId);
        final SessionState sessionState = userState.sessionStateMap.get(sessionToken);
        if (DEBUG) {
            Slog.d(TAG, "createSessionInternalLocked(inputId=" + sessionState.mInfo.getId() + ")");
        }

        final InputChannel[] channels = InputChannel.openInputChannelPair(sessionToken.toString());

        // Set up a callback to send the session token.
        ITvInputSessionCallback callback = new ITvInputSessionCallback.Stub() {
            @Override
            public void onSessionCreated(ITvInputSession session, IBinder harewareSessionToken) {
                if (DEBUG) {
                    Slog.d(TAG, "onSessionCreated(inputId=" + sessionState.mInfo.getId() + ")");
                }
                synchronized (mLock) {
                    sessionState.mSession = session;
                    sessionState.mHardwareSessionToken = harewareSessionToken;
                    if (session == null) {
                        removeSessionStateLocked(sessionToken, userId);
                        sendSessionTokenToClientLocked(sessionState.mClient,
                                sessionState.mInfo.getId(), null, null, sessionState.mSeq);
                    } else {
                        try {
                            session.asBinder().linkToDeath(sessionState, 0);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "Session is already died.");
                        }

                        IBinder clientToken = sessionState.mClient.asBinder();
                        ClientState clientState = userState.clientStateMap.get(clientToken);
                        if (clientState == null) {
                            clientState = createClientStateLocked(clientToken, userId);
                        }
                        clientState.mSessionTokens.add(sessionState.mSessionToken);

                        sendSessionTokenToClientLocked(sessionState.mClient,
                                sessionState.mInfo.getId(), sessionToken, channels[0],
                                sessionState.mSeq);
                    }
                    channels[0].dispose();
                }
            }

            @Override
            public void onChannelRetuned(Uri channelUri) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onChannelRetuned(" + channelUri + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        // TODO: Consider adding this channel change in the watch log. When we do
                        // that, how we can protect the watch log from malicious tv inputs should
                        // be addressed. e.g. add a field which represents where the channel change
                        // originated from.
                        sessionState.mClient.onChannelRetuned(channelUri, sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onChannelRetuned");
                    }
                }
            }

            @Override
            public void onTracksChanged(List<TvTrackInfo> tracks) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onTracksChanged(" + tracks + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onTracksChanged(tracks, sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onTracksChanged");
                    }
                }
            }

            @Override
            public void onTrackSelected(int type, String trackId) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onTrackSelected(type=" + type + ", trackId=" + trackId + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onTrackSelected(type, trackId, sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onTrackSelected");
                    }
                }
            }

            @Override
            public void onVideoAvailable() {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onVideoAvailable()");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onVideoAvailable(sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onVideoAvailable");
                    }
                }
            }

            @Override
            public void onVideoUnavailable(int reason) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onVideoUnavailable(" + reason + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onVideoUnavailable(reason, sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onVideoUnavailable");
                    }
                }
            }

            @Override
            public void onContentAllowed() {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onContentAllowed()");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onContentAllowed(sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onContentAllowed");
                    }
                }
            }

            @Override
            public void onContentBlocked(String rating) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onContentBlocked()");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onContentBlocked(rating, sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onContentBlocked");
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
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onLayoutSurface(left, top, right, bottom,
                                sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onLayoutSurface");
                    }
                }
            }

            @Override
            public void onSessionEvent(String eventType, Bundle eventArgs) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onEvent(what=" + eventType + ", data=" + eventArgs + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onSessionEvent(eventType, eventArgs,
                                sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onSessionEvent");
                    }
                }
            }
        };

        // Create a session. When failed, send a null token immediately.
        try {
            service.createSession(channels[1], callback, sessionState.mInfo.getId());
        } catch (RemoteException e) {
            Slog.e(TAG, "error in createSession", e);
            removeSessionStateLocked(sessionToken, userId);
            sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mInfo.getId(), null,
                    null, sessionState.mSeq);
        }
        channels[1].dispose();
    }

    private void sendSessionTokenToClientLocked(ITvInputClient client, String inputId,
            IBinder sessionToken, InputChannel channel, int seq) {
        try {
            client.onSessionCreated(inputId, sessionToken, channel, seq);
        } catch (RemoteException exception) {
            Slog.e(TAG, "error in onSessionCreated", exception);
        }
    }

    private void releaseSessionLocked(IBinder sessionToken, int callingUid, int userId) {
        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
        if (sessionState.mSession != null) {
            UserState userState = getUserStateLocked(userId);
            if (sessionToken == userState.mainSessionToken) {
                setMainLocked(sessionToken, false, callingUid, userId);
            }
            try {
                sessionState.mSession.release();
            } catch (RemoteException e) {
                Slog.w(TAG, "session is already disapeared", e);
            }
            sessionState.mSession = null;
        }
        removeSessionStateLocked(sessionToken, userId);
    }

    private void removeSessionStateLocked(IBinder sessionToken, int userId) {
        UserState userState = getUserStateLocked(userId);
        if (sessionToken == userState.mainSessionToken) {
            if (DEBUG) {
                Slog.d(TAG, "mainSessionToken=null");
            }
            userState.mainSessionToken = null;
        }

        // Remove the session state from the global session state map of the current user.
        SessionState sessionState = userState.sessionStateMap.remove(sessionToken);

        if (sessionState == null) {
            return;
        }

        // Also remove the session token from the session token list of the current client and
        // service.
        ClientState clientState = userState.clientStateMap.get(sessionState.mClient.asBinder());
        if (clientState != null) {
            clientState.mSessionTokens.remove(sessionToken);
            if (clientState.isEmpty()) {
                userState.clientStateMap.remove(sessionState.mClient.asBinder());
            }
        }

        TvInputInfo info = sessionState.mInfo;
        if (info != null) {
            ServiceState serviceState = userState.serviceStateMap.get(info.getComponent());
            if (serviceState != null) {
                serviceState.mSessionTokens.remove(sessionToken);
            }
        }
        updateServiceConnectionLocked(sessionState.mInfo.getComponent(), userId);

        // Log the end of watch.
        SomeArgs args = SomeArgs.obtain();
        args.arg1 = sessionToken;
        args.arg2 = System.currentTimeMillis();
        mWatchLogHandler.obtainMessage(WatchLogHandler.MSG_LOG_WATCH_END, args).sendToTarget();
    }

    private void setMainLocked(IBinder sessionToken, boolean isMain, int callingUid, int userId) {
        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
        if (sessionState.mHardwareSessionToken != null) {
            sessionState = getSessionStateLocked(sessionState.mHardwareSessionToken,
                    Process.SYSTEM_UID, userId);
        }
        ServiceState serviceState = getServiceStateLocked(sessionState.mInfo.getComponent(),
                userId);
        if (!serviceState.mIsHardware) {
            return;
        }
        ITvInputSession session = getSessionLocked(sessionState);
        try {
            session.setMain(isMain);
        } catch (RemoteException e) {
            Slog.e(TAG, "error in setMain", e);
        }
    }

    private void notifyInputAddedLocked(UserState userState, String inputId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputAdded: inputId = " + inputId);
        }
        for (ITvInputManagerCallback callback : userState.callbackSet) {
            try {
                callback.onInputAdded(inputId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to report added input to callback.");
            }
        }
    }

    private void notifyInputRemovedLocked(UserState userState, String inputId) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputRemovedLocked: inputId = " + inputId);
        }
        for (ITvInputManagerCallback callback : userState.callbackSet) {
            try {
                callback.onInputRemoved(inputId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to report removed input to callback.");
            }
        }
    }

    private void notifyInputStateChangedLocked(UserState userState, String inputId,
            int state, ITvInputManagerCallback targetCallback) {
        if (DEBUG) {
            Slog.d(TAG, "notifyInputStateChangedLocked: inputId = " + inputId
                    + "; state = " + state);
        }
        if (targetCallback == null) {
            for (ITvInputManagerCallback callback : userState.callbackSet) {
                try {
                    callback.onInputStateChanged(inputId, state);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to report state change to callback.");
                }
            }
        } else {
            try {
                targetCallback.onInputStateChanged(inputId, state);
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to report state change to callback.");
            }
        }
    }

    private void setStateLocked(String inputId, int state, int userId) {
        UserState userState = getUserStateLocked(userId);
        TvInputState inputState = userState.inputMap.get(inputId);
        ServiceState serviceState = userState.serviceStateMap.get(inputState.mInfo.getComponent());
        int oldState = inputState.mState;
        inputState.mState = state;
        if (serviceState != null && serviceState.mService == null
                && shouldMaintainConnection(serviceState)) {
            // We don't notify state change while reconnecting. It should remain disconnected.
            return;
        }
        if (oldState != state) {
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
                    UserState userState = getUserStateLocked(resolvedUserId);
                    List<TvInputInfo> inputList = new ArrayList<TvInputInfo>();
                    for (TvInputState state : userState.inputMap.values()) {
                        inputList.add(state.mInfo);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
                    TvInputState state = userState.inputMap.get(inputId);
                    return state == null ? null : state.mInfo;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<Uri> getTvContentRatingSystemXmls(int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "getTvContentRatingSystemXmls");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    List<Uri> ratingSystemXmlUriList = new ArrayList<Uri>();
                    ratingSystemXmlUriList.addAll(userState.ratingSystemXmlUriSet);
                    return ratingSystemXmlUriList;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void registerCallback(final ITvInputManagerCallback callback, int userId) {
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, "registerCallback");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    userState.callbackSet.add(callback);
                    for (TvInputState state : userState.inputMap.values()) {
                        notifyInputStateChangedLocked(userState, state.mInfo.getId(),
                                state.mState, callback);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
                    userState.callbackSet.remove(callback);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
                    List<String> ratings = new ArrayList<String>();
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
                    UserState userState = getUserStateLocked(resolvedUserId);
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
                    UserState userState = getUserStateLocked(resolvedUserId);
                    userState.persistentDataStore.removeBlockedRating(
                            TvContentRating.unflattenFromString(rating));
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        private void ensureParentalControlsPermission() {
            // STOPSHIP: Uncomment when b/16984416 is resolved.
            //if (mContext.checkCallingPermission(
            //        android.Manifest.permission.MODIFY_PARENTAL_CONTROLS)
            //        != PackageManager.PERMISSION_GRANTED) {
            //    throw new SecurityException(
            //            "The caller does not have parental controls permission");
            //}
        }

        @Override
        public void createSession(final ITvInputClient client, final String inputId,
                int seq, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "createSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    TvInputInfo info = userState.inputMap.get(inputId).mInfo;
                    ServiceState serviceState = userState.serviceStateMap.get(info.getComponent());
                    if (serviceState == null) {
                        serviceState = new ServiceState(info.getComponent(), resolvedUserId);
                        userState.serviceStateMap.put(info.getComponent(), serviceState);
                    }
                    // Send a null token immediately while reconnecting.
                    if (serviceState.mReconnecting == true) {
                        sendSessionTokenToClientLocked(client, inputId, null, null, seq);
                        return;
                    }

                    // Create a new session token and a session state.
                    IBinder sessionToken = new Binder();
                    SessionState sessionState = new SessionState(sessionToken, info, client,
                            seq, callingUid, resolvedUserId);

                    // Add them to the global session state map of the current user.
                    userState.sessionStateMap.put(sessionToken, sessionState);

                    // Also, add them to the session state map of the current service.
                    serviceState.mSessionTokens.add(sessionToken);

                    if (serviceState.mService != null) {
                        createSessionInternalLocked(serviceState.mService, sessionToken,
                                resolvedUserId);
                    } else {
                        updateServiceConnectionLocked(info.getComponent(), resolvedUserId);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void releaseSession(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "releaseSession(): " + sessionToken);
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
        public void setMainSession(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slog.d(TAG, "setMainSession(): " + sessionToken);
            }
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setMainSession");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
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
            try {
                synchronized (mLock) {
                    try {
                        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid,
                                resolvedUserId);
                        if (sessionState.mHardwareSessionToken == null) {
                            getSessionLocked(sessionState).setSurface(surface);
                        } else {
                            getSessionLocked(sessionState.mHardwareSessionToken,
                                    Process.SYSTEM_UID, resolvedUserId).setSurface(surface);
                        }
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in setSurface", e);
                    }
                }
            } finally {
                if (surface != null) {
                    // surface is not used in TvInputManagerService.
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
                        getSessionLocked(sessionState).dispatchSurfaceChanged(format, width, height);
                        if (sessionState.mHardwareSessionToken != null) {
                            getSessionLocked(sessionState.mHardwareSessionToken, Process.SYSTEM_UID,
                                    resolvedUserId).dispatchSurfaceChanged(format, width, height);
                        }
                    } catch (RemoteException e) {
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
                        if (sessionState.mHardwareSessionToken != null) {
                            // Here, we let the hardware session know only whether volume is on or
                            // off to prevent that the volume is controlled in the both side.
                            getSessionLocked(sessionState.mHardwareSessionToken,
                                    Process.SYSTEM_UID, resolvedUserId).setVolume((volume > 0.0f)
                                            ? REMOTE_VOLUME_ON : REMOTE_VOLUME_OFF);
                        }
                    } catch (RemoteException e) {
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
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "tune");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).tune(
                                channelUri, params);
                        if (TvContract.isChannelUriForPassthroughInput(channelUri)) {
                            // Do not log the watch history for passthrough inputs.
                            return;
                        }

                        UserState userState = getUserStateLocked(resolvedUserId);
                        SessionState sessionState = userState.sessionStateMap.get(sessionToken);

                        // Log the start of watch.
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = sessionState.mInfo.getComponent().getPackageName();
                        args.arg2 = System.currentTimeMillis();
                        args.arg3 = ContentUris.parseId(channelUri);
                        args.arg4 = params;
                        args.arg5 = sessionToken;
                        mWatchLogHandler.obtainMessage(WatchLogHandler.MSG_LOG_WATCH_START, args)
                                .sendToTarget();
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in tune", e);
                        return;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void requestUnblockContent(
                IBinder sessionToken, String unblockedRating, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "unblockContent");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .requestUnblockContent(unblockedRating);
                    } catch (RemoteException e) {
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
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in setCaptionEnabled", e);
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
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in selectTrack", e);
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
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in sendAppPrivateCommand", e);
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
                    } catch (RemoteException e) {
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
                    } catch (RemoteException e) {
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
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in removeOverlayView", e);
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
                ITvInputHardwareCallback callback, TvInputInfo info, int userId)
                throws RemoteException {
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }

            final long identity = Binder.clearCallingIdentity();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "acquireTvInputHardware");
            try {
                return mTvInputHardwareManager.acquireHardware(
                        deviceId, callback, info, callingUid, resolvedUserId);
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

            final long identity = Binder.clearCallingIdentity();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "releaseTvInputHardware");
            try {
                mTvInputHardwareManager.releaseHardware(
                        deviceId, hardware, callingUid, resolvedUserId);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public List<TvStreamConfig> getAvailableTvStreamConfigList(String inputId, int userId)
                throws RemoteException {
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.CAPTURE_TV_INPUT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CAPTURE_TV_INPUT permission");
            }

            final long identity = Binder.clearCallingIdentity();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "getAvailableTvStreamConfigList");
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
            if (mContext.checkCallingPermission(
                    android.Manifest.permission.CAPTURE_TV_INPUT)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires CAPTURE_TV_INPUT permission");
            }

            final long identity = Binder.clearCallingIdentity();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "captureFrame");
            try {
                String hardwareInputId = null;
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    if (userState.inputMap.get(inputId) == null) {
                        Slog.e(TAG, "Input not found for " + inputId);
                        return false;
                    }
                    for (SessionState sessionState : userState.sessionStateMap.values()) {
                        if (sessionState.mInfo.getId().equals(inputId)
                                && sessionState.mHardwareSessionToken != null) {
                            hardwareInputId = userState.sessionStateMap.get(
                                    sessionState.mHardwareSessionToken).mInfo.getId();
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
            final long identity = Binder.clearCallingIdentity();
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "isSingleSessionActive");
            try {
                synchronized (mLock) {
                    UserState userState = getUserStateLocked(resolvedUserId);
                    if (userState.sessionStateMap.size() == 1) {
                        return true;
                    }
                    else if (userState.sessionStateMap.size() == 2) {
                        SessionState[] sessionStates = userState.sessionStateMap.values().toArray(
                                new SessionState[0]);
                        // Check if there is a wrapper input.
                        if (sessionStates[0].mHardwareSessionToken != null
                                || sessionStates[1].mHardwareSessionToken != null) {
                            return true;
                        }
                    }
                    return false;
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        @SuppressWarnings("resource")
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
            if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump TvInputManager from pid="
                        + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid());
                return;
            }

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
                    UserState userState = getUserStateLocked(userId);
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

                        pw.println("mSessionTokens:");
                        pw.increaseIndent();
                        for (IBinder token : client.mSessionTokens) {
                            pw.println("" + token);
                        }
                        pw.decreaseIndent();

                        pw.println("mClientTokens: " + client.mClientToken);
                        pw.println("mUserId: " + client.mUserId);

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

                        pw.println("mSessionTokens:");
                        pw.increaseIndent();
                        for (IBinder token : service.mSessionTokens) {
                            pw.println("" + token);
                        }
                        pw.decreaseIndent();

                        pw.println("mService: " + service.mService);
                        pw.println("mCallback: " + service.mCallback);
                        pw.println("mBound: " + service.mBound);
                        pw.println("mReconnecting: " + service.mReconnecting);

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
                        pw.println("mInfo: " + session.mInfo);
                        pw.println("mClient: " + session.mClient);
                        pw.println("mSeq: " + session.mSeq);
                        pw.println("mCallingUid: " + session.mCallingUid);
                        pw.println("mUserId: " + session.mUserId);
                        pw.println("mSessionToken: " + session.mSessionToken);
                        pw.println("mSession: " + session.mSession);
                        pw.println("mLogUri: " + session.mLogUri);
                        pw.println("mHardwareSessionToken: " + session.mHardwareSessionToken);
                        pw.decreaseIndent();
                    }
                    pw.decreaseIndent();

                    pw.println("callbackSet:");
                    pw.increaseIndent();
                    for (ITvInputManagerCallback callback : userState.callbackSet) {
                        pw.println(callback.toString());
                    }
                    pw.decreaseIndent();

                    pw.println("mainSessionToken: " + userState.mainSessionToken);
                    pw.decreaseIndent();
                }
            }
        }
    }

    private static final class TvInputState {
        // A TvInputInfo object which represents the TV input.
        private TvInputInfo mInfo;

        // The state of TV input. Connected by default.
        private int mState = INPUT_STATE_CONNECTED;

        @Override
        public String toString() {
            return "mInfo: " + mInfo + "; mState: " + mState;
        }
    }

    private static final class UserState {
        // A mapping from the TV input id to its TvInputState.
        private Map<String, TvInputState> inputMap = new HashMap<String, TvInputState>();

        // A set of all TV input packages.
        private final Set<String> packageSet = new HashSet<String>();

        // A set of all TV content rating system xml uris.
        private final Set<Uri> ratingSystemXmlUriSet = new HashSet<Uri>();

        // A mapping from the token of a client to its state.
        private final Map<IBinder, ClientState> clientStateMap =
                new HashMap<IBinder, ClientState>();

        // A mapping from the name of a TV input service to its state.
        private final Map<ComponentName, ServiceState> serviceStateMap =
                new HashMap<ComponentName, ServiceState>();

        // A mapping from the token of a TV input session to its state.
        private final Map<IBinder, SessionState> sessionStateMap =
                new HashMap<IBinder, SessionState>();

        // A set of callbacks.
        private final Set<ITvInputManagerCallback> callbackSet =
                new HashSet<ITvInputManagerCallback>();

        // The token of a "main" TV input session.
        private IBinder mainSessionToken = null;

        // Persistent data store for all internal settings maintained by the TV input manager
        // service.
        private final PersistentDataStore persistentDataStore;

        private UserState(Context context, int userId) {
            persistentDataStore = new PersistentDataStore(context, userId);
        }
    }

    private final class ClientState implements IBinder.DeathRecipient {
        private final List<IBinder> mSessionTokens = new ArrayList<IBinder>();

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
                UserState userState = getUserStateLocked(mUserId);
                // DO NOT remove the client state of clientStateMap in this method. It will be
                // removed in releaseSessionLocked().
                ClientState clientState = userState.clientStateMap.get(mClientToken);
                if (clientState != null) {
                    while (clientState.mSessionTokens.size() > 0) {
                        releaseSessionLocked(
                                clientState.mSessionTokens.get(0), Process.SYSTEM_UID, mUserId);
                    }
                }
                mClientToken = null;
            }
        }
    }

    private final class ServiceState {
        private final List<IBinder> mSessionTokens = new ArrayList<IBinder>();
        private final ServiceConnection mConnection;
        private final ComponentName mComponent;
        private final boolean mIsHardware;
        private final List<TvInputInfo> mInputList = new ArrayList<TvInputInfo>();

        private ITvInputService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private ServiceState(ComponentName component, int userId) {
            mComponent = component;
            mConnection = new InputServiceConnection(component, userId);
            mIsHardware = hasHardwarePermission(mContext.getPackageManager(), mComponent);
        }
    }

    private final class SessionState implements IBinder.DeathRecipient {
        private final TvInputInfo mInfo;
        private final ITvInputClient mClient;
        private final int mSeq;
        private final int mCallingUid;
        private final int mUserId;
        private final IBinder mSessionToken;
        private ITvInputSession mSession;
        private Uri mLogUri;
        // Not null if this session represents an external device connected to a hardware TV input.
        private IBinder mHardwareSessionToken;

        private SessionState(IBinder sessionToken, TvInputInfo info, ITvInputClient client,
                int seq, int callingUid, int userId) {
            mSessionToken = sessionToken;
            mInfo = info;
            mClient = client;
            mSeq = seq;
            mCallingUid = callingUid;
            mUserId = userId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSession = null;
                if (mClient != null) {
                    try {
                        mClient.onSessionReleased(mSeq);
                    } catch(RemoteException e) {
                        Slog.e(TAG, "error in onSessionReleased", e);
                    }
                }
                // If there are any other sessions based on this session, they should be released.
                UserState userState = getUserStateLocked(mUserId);
                for (SessionState sessionState : userState.sessionStateMap.values()) {
                    if (mSessionToken == sessionState.mHardwareSessionToken) {
                        releaseSessionLocked(sessionState.mSessionToken, Process.SYSTEM_UID,
                                mUserId);
                        try {
                            sessionState.mClient.onSessionReleased(sessionState.mSeq);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in onSessionReleased", e);
                        }
                    }
                }
                removeSessionStateLocked(mSessionToken, mUserId);
            }
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
                ServiceState serviceState = userState.serviceStateMap.get(mComponent);
                serviceState.mService = ITvInputService.Stub.asInterface(service);

                // Register a callback, if we need to.
                if (serviceState.mIsHardware && serviceState.mCallback == null) {
                    serviceState.mCallback = new ServiceCallback(mComponent, mUserId);
                    try {
                        serviceState.mService.registerCallback(serviceState.mCallback);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in registerCallback", e);
                    }
                }

                // And create sessions, if any.
                for (IBinder sessionToken : serviceState.mSessionTokens) {
                    createSessionInternalLocked(serviceState.mService, sessionToken, mUserId);
                }

                for (TvInputState inputState : userState.inputMap.values()) {
                    if (inputState.mInfo.getComponent().equals(component)
                            && inputState.mState != INPUT_STATE_DISCONNECTED) {
                        notifyInputStateChangedLocked(userState, inputState.mInfo.getId(),
                                inputState.mState, null);
                    }
                }

                if (serviceState.mIsHardware) {
                    List<TvInputHardwareInfo> hardwareInfoList =
                            mTvInputHardwareManager.getHardwareList();
                    for (TvInputHardwareInfo hardwareInfo : hardwareInfoList) {
                        try {
                            serviceState.mService.notifyHardwareAdded(hardwareInfo);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in notifyHardwareAdded", e);
                        }
                    }

                    List<HdmiDeviceInfo> deviceInfoList =
                            mTvInputHardwareManager.getHdmiDeviceList();
                    for (HdmiDeviceInfo deviceInfo : deviceInfoList) {
                        try {
                            serviceState.mService.notifyHdmiDeviceAdded(deviceInfo);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in notifyHdmiDeviceAdded", e);
                        }
                    }
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
                UserState userState = getUserStateLocked(mUserId);
                ServiceState serviceState = userState.serviceStateMap.get(mComponent);
                if (serviceState != null) {
                    serviceState.mReconnecting = true;
                    serviceState.mBound = false;
                    serviceState.mService = null;
                    serviceState.mCallback = null;

                    // Send null tokens for not finishing create session events.
                    for (IBinder sessionToken : serviceState.mSessionTokens) {
                        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
                        if (sessionState.mSession == null) {
                            removeSessionStateLocked(sessionToken, sessionState.mUserId);
                            sendSessionTokenToClientLocked(sessionState.mClient,
                                    sessionState.mInfo.getId(), null, null, sessionState.mSeq);
                        }
                    }

                    for (TvInputState inputState : userState.inputMap.values()) {
                        if (inputState.mInfo.getComponent().equals(component)) {
                            notifyInputStateChangedLocked(userState, inputState.mInfo.getId(),
                                    INPUT_STATE_DISCONNECTED, null);
                        }
                    }
                    updateServiceConnectionLocked(mComponent, mUserId);
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

        private void addTvInputLocked(TvInputInfo inputInfo) {
            ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
            serviceState.mInputList.add(inputInfo);
            buildTvInputListLocked(mUserId);
        }

        @Override
        public void addHardwareTvInput(int deviceId, TvInputInfo inputInfo) {
            ensureHardwarePermission();
            ensureValidInput(inputInfo);
            synchronized (mLock) {
                mTvInputHardwareManager.addHardwareTvInput(deviceId, inputInfo);
                addTvInputLocked(inputInfo);
            }
        }

        @Override
        public void addHdmiTvInput(int id, TvInputInfo inputInfo) {
            ensureHardwarePermission();
            ensureValidInput(inputInfo);
            synchronized (mLock) {
                mTvInputHardwareManager.addHdmiTvInput(id, inputInfo);
                addTvInputLocked(inputInfo);
            }
        }

        @Override
        public void removeTvInput(String inputId) {
            ensureHardwarePermission();
            synchronized (mLock) {
                ServiceState serviceState = getServiceStateLocked(mComponent, mUserId);
                boolean removed = false;
                for (Iterator<TvInputInfo> it = serviceState.mInputList.iterator();
                        it.hasNext(); ) {
                    if (it.next().getId().equals(inputId)) {
                        it.remove();
                        removed = true;
                        break;
                    }
                }
                if (removed) {
                    buildTvInputListLocked(mUserId);
                    mTvInputHardwareManager.removeTvInput(inputId);
                } else {
                    Slog.e(TAG, "TvInputInfo with inputId=" + inputId + " not found.");
                }
            }
        }
    }

    private final class WatchLogHandler extends Handler {
        // There are only two kinds of watch events that can happen on the system:
        // 1. The current TV input session is tuned to a new channel.
        // 2. The session is released for some reason.
        // The former indicates the end of the previous log entry, if any, followed by the start of
        // a new entry. The latter indicates the end of the most recent entry for the given session.
        // Here the system supplies the database the smallest set of information only that is
        // sufficient to consolidate the log entries while minimizing database operations in the
        // system service.
        private static final int MSG_LOG_WATCH_START = 1;
        private static final int MSG_LOG_WATCH_END = 2;

        public WatchLogHandler(Looper looper) {
            super(looper);
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
                    values.put(TvContract.WatchedPrograms.COLUMN_PACKAGE_NAME, packageName);
                    values.put(TvContract.WatchedPrograms.COLUMN_WATCH_START_TIME_UTC_MILLIS,
                            watchStartTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_CHANNEL_ID, channelId);
                    if (tuneParams != null) {
                        values.put(TvContract.WatchedPrograms.COLUMN_INTERNAL_TUNE_PARAMS,
                                encodeTuneParams(tuneParams));
                    }
                    values.put(TvContract.WatchedPrograms.COLUMN_INTERNAL_SESSION_TOKEN,
                            sessionToken.toString());

                    mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                    args.recycle();
                    return;
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

                    mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                    args.recycle();
                    return;
                }
                default: {
                    Slog.w(TAG, "Unhandled message code: " + msg.what);
                    return;
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

    final class HardwareListener implements TvInputHardwareManager.Listener {
        @Override
        public void onStateChanged(String inputId, int state) {
            synchronized (mLock) {
                setStateLocked(inputId, state, mCurrentUserId);
            }
        }

        @Override
        public void onHardwareDeviceAdded(TvInputHardwareInfo info) {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.mIsHardware || serviceState.mService == null) continue;
                    try {
                        serviceState.mService.notifyHardwareAdded(info);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHardwareAdded", e);
                    }
                }
            }
        }

        @Override
        public void onHardwareDeviceRemoved(TvInputHardwareInfo info) {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.mIsHardware || serviceState.mService == null) continue;
                    try {
                        serviceState.mService.notifyHardwareRemoved(info);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHardwareRemoved", e);
                    }
                }
            }
        }

        @Override
        public void onHdmiDeviceAdded(HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.mIsHardware || serviceState.mService == null) continue;
                    try {
                        serviceState.mService.notifyHdmiDeviceAdded(deviceInfo);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHdmiDeviceAdded", e);
                    }
                }
            }
        }

        @Override
        public void onHdmiDeviceRemoved(HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.mIsHardware || serviceState.mService == null) continue;
                    try {
                        serviceState.mService.notifyHdmiDeviceRemoved(deviceInfo);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHdmiDeviceRemoved", e);
                    }
                }
            }
        }

        @Override
        public void onHdmiDeviceUpdated(String inputId, HdmiDeviceInfo deviceInfo) {
            synchronized (mLock) {
                Integer state = null;
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
                    setStateLocked(inputId, state.intValue(), mCurrentUserId);
                }
            }
        }
    }
}
