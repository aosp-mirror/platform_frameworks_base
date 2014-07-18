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
import android.database.Cursor;
import android.graphics.Rect;
import android.hardware.hdmi.HdmiCecDeviceInfo;
import android.media.tv.ITvInputClient;
import android.media.tv.ITvInputHardware;
import android.media.tv.ITvInputHardwareCallback;
import android.media.tv.ITvInputManager;
import android.media.tv.ITvInputManagerCallback;
import android.media.tv.ITvInputService;
import android.media.tv.ITvInputServiceCallback;
import android.media.tv.ITvInputSession;
import android.media.tv.ITvInputSessionCallback;
import android.media.tv.TvContract;
import android.media.tv.TvInputHardwareInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputService;
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

    private final Handler mLogHandler;

    public TvInputManagerService(Context context) {
        super(context);

        mContext = context;
        mContentResolver = context.getContentResolver();
        mLogHandler = new LogHandler(IoThread.get().getLooper());

        mTvInputHardwareManager = new TvInputHardwareManager(context, new HardwareListener());

        synchronized (mLock) {
            mUserStates.put(mCurrentUserId, new UserState());
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

    private static boolean hasHardwarePermission(PackageManager pm, ComponentName name) {
        return pm.checkPermission(android.Manifest.permission.TV_INPUT_HARDWARE,
                name.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private void buildTvInputListLocked(int userId) {
        UserState userState = getUserStateLocked(userId);

        Map<String, TvInputState> inputMap = new HashMap<String, TvInputState>();
        userState.packageSet.clear();

        if (DEBUG) Slog.d(TAG, "buildTvInputList");
        PackageManager pm = mContext.getPackageManager();
        List<ResolveInfo> services = pm.queryIntentServices(
                new Intent(TvInputService.SERVICE_INTERFACE),
                PackageManager.GET_SERVICES | PackageManager.GET_META_DATA);
        List<TvInputInfo> infoList = new ArrayList<TvInputInfo>();
        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (!android.Manifest.permission.BIND_TV_INPUT.equals(si.permission)) {
                Slog.w(TAG, "Skipping TV input " + si.name + ": it does not require the permission "
                        + android.Manifest.permission.BIND_TV_INPUT);
                continue;
            }
            try {
                infoList.clear();
                ComponentName service = new ComponentName(si.packageName, si.name);
                if (hasHardwarePermission(pm, service)) {
                    ServiceState serviceState = userState.serviceStateMap.get(service);
                    if (serviceState == null) {
                        // We see this hardware TV input service for the first time; we need to
                        // prepare the ServiceState object so that we can connect to the service and
                        // let it add TvInputInfo objects to mInputList if there's any.
                        serviceState = new ServiceState(service, userId);
                        userState.serviceStateMap.put(service, serviceState);
                    } else {
                        infoList.addAll(serviceState.mInputList);
                    }
                } else {
                    infoList.add(TvInputInfo.createTvInputInfo(mContext, ri));
                }

                for (TvInputInfo info : infoList) {
                    if (DEBUG) Slog.d(TAG, "add " + info.getId());
                    TvInputState state = userState.inputMap.get(info.getId());
                    if (state == null) {
                        state = new TvInputState();
                    }
                    state.mInfo = info;
                    inputMap.put(info.getId(), state);
                }

                // Reconnect the service if existing input is updated.
                updateServiceConnectionLocked(service, userId);

                userState.packageSet.add(si.packageName);
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Can't load TV input " + si.name, e);
            }
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
                userState = new UserState();
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
                serviceState.mClientTokens.clear();
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

    private ServiceState getServiceStateLocked(ComponentName name, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(name);
        if (serviceState == null) {
            throw new IllegalStateException("Service state not found for " + name + " (userId="
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
        SessionState sessionState = getSessionStateLocked(sessionToken, callingUid, userId);
        ITvInputSession session = sessionState.mSession;
        if (session == null) {
            throw new IllegalStateException("Session not yet created for token " + sessionToken);
        }
        return session;
    }

    private int resolveCallingUserId(int callingPid, int callingUid, int requestedUserId,
            String methodName) {
        return ActivityManager.handleIncomingUser(callingPid, callingUid, requestedUserId, false,
                false, methodName, null);
    }

    private static boolean shouldMaintainConnection(ServiceState serviceState) {
        return !serviceState.mClientTokens.isEmpty()
                || !serviceState.mSessionTokens.isEmpty()
                || serviceState.mIsHardware;
        // TODO: Find a way to maintain connection only when necessary.
    }

    private void updateServiceConnectionLocked(ComponentName service, int userId) {
        UserState userState = getUserStateLocked(userId);
        ServiceState serviceState = userState.serviceStateMap.get(service);
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
                Slog.d(TAG, "bindServiceAsUser(service=" + service + ", userId=" + userId + ")");
            }

            Intent i = new Intent(TvInputService.SERVICE_INTERFACE).setComponent(service);
            // Binding service may fail if the service is updating.
            // In that case, the connection will be revived in buildTvInputListLocked called by
            // onSomePackagesChanged.
            serviceState.mBound = mContext.bindServiceAsUser(
                    i, serviceState.mConnection, Context.BIND_AUTO_CREATE, new UserHandle(userId));
        } else if (serviceState.mService != null && !maintainConnection) {
            // This means that the service is already connected but its state indicates that we have
            // nothing to do with it. Then, disconnect the service.
            if (DEBUG) {
                Slog.d(TAG, "unbindService(service=" + service + ")");
            }
            mContext.unbindService(serviceState.mConnection);
            userState.serviceStateMap.remove(service);
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
            public void onSessionCreated(ITvInputSession session) {
                if (DEBUG) {
                    Slog.d(TAG, "onSessionCreated(inputId=" + sessionState.mInfo.getId() + ")");
                }
                synchronized (mLock) {
                    sessionState.mSession = session;
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
            public void onTrackInfoChanged(List<TvTrackInfo> tracks) {
                synchronized (mLock) {
                    if (DEBUG) {
                        Slog.d(TAG, "onTrackInfoChanged(" + tracks + ")");
                    }
                    if (sessionState.mSession == null || sessionState.mClient == null) {
                        return;
                    }
                    try {
                        sessionState.mClient.onTrackInfoChanged(tracks,
                                sessionState.mSeq);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in onTrackInfoChanged");
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
        // Remove the session state from the global session state map of the current user.
        UserState userState = getUserStateLocked(userId);
        SessionState sessionState = userState.sessionStateMap.remove(sessionToken);

        // Close the open log entry, if any.
        if (sessionState.mLogUri != null) {
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = sessionState.mLogUri;
            args.arg2 = System.currentTimeMillis();
            mLogHandler.obtainMessage(LogHandler.MSG_CLOSE_ENTRY, args).sendToTarget();
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
    }

    private void unregisterClientInternalLocked(IBinder clientToken, String inputId,
            int userId) {
        UserState userState = getUserStateLocked(userId);
        ClientState clientState = userState.clientStateMap.get(clientToken);
        if (clientState != null) {
            clientState.mInputIds.remove(inputId);
            if (clientState.isEmpty()) {
                userState.clientStateMap.remove(clientToken);
            }
        }

        TvInputInfo info = userState.inputMap.get(inputId).mInfo;
        if (info == null) {
            return;
        }
        ServiceState serviceState = userState.serviceStateMap.get(info.getComponent());
        if (serviceState == null) {
            return;
        }

        // Remove this client from the client list and unregister the callback.
        serviceState.mClientTokens.remove(clientToken);
        if (!serviceState.mClientTokens.isEmpty()) {
            // We have other clients who want to keep the callback. Do this later.
            return;
        }
        if (serviceState.mService == null || serviceState.mCallback == null) {
            return;
        }
        try {
            serviceState.mService.unregisterCallback(serviceState.mCallback);
        } catch (RemoteException e) {
            Slog.e(TAG, "error in unregisterCallback", e);
        } finally {
            serviceState.mCallback = null;
            updateServiceConnectionLocked(info.getComponent(), userId);
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
        ServiceState serviceState = userState.serviceStateMap.get(inputId);
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
        public void setSurface(IBinder sessionToken, Surface surface, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setSurface");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).setSurface(
                                surface);
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
                        getSessionLocked(sessionToken, callingUid, resolvedUserId)
                                .dispatchSurfaceChanged(format, width, height);
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
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "setVolume");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).setVolume(
                                volume);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in setVolume", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void tune(IBinder sessionToken, final Uri channelUri, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "tune");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).tune(channelUri);

                        long currentTime = System.currentTimeMillis();
                        long channelId = ContentUris.parseId(channelUri);

                        // Close the open log entry first, if any.
                        UserState userState = getUserStateLocked(resolvedUserId);
                        SessionState sessionState = userState.sessionStateMap.get(sessionToken);
                        if (sessionState.mLogUri != null) {
                            SomeArgs args = SomeArgs.obtain();
                            args.arg1 = sessionState.mLogUri;
                            args.arg2 = currentTime;
                            mLogHandler.obtainMessage(LogHandler.MSG_CLOSE_ENTRY, args)
                                    .sendToTarget();
                        }

                        // Create a log entry and fill it later.
                        String packageName = sessionState.mInfo.getServiceInfo().packageName;
                        ContentValues values = new ContentValues();
                        values.put(TvContract.WatchedPrograms.COLUMN_PACKAGE_NAME, packageName);
                        values.put(TvContract.WatchedPrograms.COLUMN_WATCH_START_TIME_UTC_MILLIS,
                                currentTime);
                        values.put(TvContract.WatchedPrograms.COLUMN_WATCH_END_TIME_UTC_MILLIS, 0);
                        values.put(TvContract.WatchedPrograms.COLUMN_CHANNEL_ID, channelId);

                        sessionState.mLogUri = mContentResolver.insert(
                                TvContract.WatchedPrograms.CONTENT_URI, values);
                        SomeArgs args = SomeArgs.obtain();
                        args.arg1 = sessionState.mLogUri;
                        args.arg2 = ContentUris.parseId(channelUri);
                        args.arg3 = currentTime;
                        mLogHandler.obtainMessage(LogHandler.MSG_OPEN_ENTRY, args).sendToTarget();
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
        public void selectTrack(IBinder sessionToken, TvTrackInfo track, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "selectTrack");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).selectTrack(
                                track);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in selectTrack", e);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }

        @Override
        public void unselectTrack(IBinder sessionToken, TvTrackInfo track, int userId) {
            final int callingUid = Binder.getCallingUid();
            final int resolvedUserId = resolveCallingUserId(Binder.getCallingPid(), callingUid,
                    userId, "unselectTrack");
            final long identity = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    try {
                        getSessionLocked(sessionToken, callingUid, resolvedUserId).unselectTrack(
                                track);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in unselectTrack", e);
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
        public void registerTvInputInfo(TvInputInfo info, int deviceId) {
            // TODO: Revisit to sort out deviceId ownership.
            if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                mTvInputHardwareManager.registerTvInputInfo(info, deviceId);
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

                        pw.println("mInputIds:");
                        pw.increaseIndent();
                        for (String inputId : client.mInputIds) {
                            pw.println(inputId);
                        }
                        pw.decreaseIndent();

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

                        pw.println("mClientTokens:");
                        pw.increaseIndent();
                        for (IBinder token : service.mClientTokens) {
                            pw.println("" + token);
                        }
                        pw.decreaseIndent();

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
                        pw.decreaseIndent();
                    }
                    pw.decreaseIndent();

                    pw.println("callbackSet:");
                    pw.increaseIndent();
                    for (ITvInputManagerCallback callback : userState.callbackSet) {
                        pw.println(callback.toString());
                    }
                    pw.decreaseIndent();

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
    }

    private final class ClientState implements IBinder.DeathRecipient {
        private final List<String> mInputIds = new ArrayList<String>();
        private final List<IBinder> mSessionTokens = new ArrayList<IBinder>();

        private IBinder mClientToken;
        private final int mUserId;

        ClientState(IBinder clientToken, int userId) {
            mClientToken = clientToken;
            mUserId = userId;
        }

        public boolean isEmpty() {
            return mInputIds.isEmpty() && mSessionTokens.isEmpty();
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mUserId);
                // DO NOT remove the client state of clientStateMap in this method. It will be
                // removed in releaseSessionLocked() or unregisterClientInternalLocked().
                ClientState clientState = userState.clientStateMap.get(mClientToken);
                if (clientState != null) {
                    while (clientState.mSessionTokens.size() > 0) {
                        releaseSessionLocked(
                                clientState.mSessionTokens.get(0), Process.SYSTEM_UID, mUserId);
                    }
                    while (clientState.mInputIds.size() > 0) {
                        unregisterClientInternalLocked(
                                mClientToken, clientState.mInputIds.get(0), mUserId);
                    }
                }
                mClientToken = null;
            }
        }
    }

    private final class ServiceState {
        private final List<IBinder> mClientTokens = new ArrayList<IBinder>();
        private final List<IBinder> mSessionTokens = new ArrayList<IBinder>();
        private final ServiceConnection mConnection;
        private final ComponentName mName;
        private final boolean mIsHardware;
        private final List<TvInputInfo> mInputList = new ArrayList<TvInputInfo>();

        private ITvInputService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private ServiceState(ComponentName name, int userId) {
            mName = name;
            mConnection = new InputServiceConnection(name, userId);
            mIsHardware = hasHardwarePermission(mContext.getPackageManager(), mName);
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

        private SessionState(IBinder sessionToken, TvInputInfo info, ITvInputClient client, int seq,
                int callingUid, int userId) {
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
                removeSessionStateLocked(mSessionToken, mUserId);
            }
        }
    }

    private final class InputServiceConnection implements ServiceConnection {
        private final ComponentName mName;
        private final int mUserId;

        private InputServiceConnection(ComponentName name, int userId) {
            mName = name;
            mUserId = userId;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceConnected(name=" + name + ")");
            }
            synchronized (mLock) {
                List<TvInputHardwareInfo> hardwareInfoList =
                        mTvInputHardwareManager.getHardwareList();
                UserState userState = getUserStateLocked(mUserId);
                ServiceState serviceState = userState.serviceStateMap.get(mName);
                serviceState.mService = ITvInputService.Stub.asInterface(service);

                // Register a callback, if we need to.
                if (serviceState.mIsHardware && serviceState.mCallback == null) {
                    serviceState.mCallback = new ServiceCallback(mName, mUserId);
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
                    if (inputState.mInfo.getComponent().equals(name)
                            && inputState.mState != INPUT_STATE_DISCONNECTED) {
                        notifyInputStateChangedLocked(userState, inputState.mInfo.getId(),
                                inputState.mState, null);
                    }
                }

                if (serviceState.mIsHardware) {
                    for (TvInputHardwareInfo hardwareInfo : hardwareInfoList) {
                        try {
                            serviceState.mService.notifyHardwareAdded(hardwareInfo);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "error in notifyHardwareAdded", e);
                        }
                    }

                    // TODO: Grab CEC devices and notify them to the service.
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) {
                Slog.d(TAG, "onServiceDisconnected(name=" + name + ")");
            }
            if (!mName.equals(name)) {
                throw new IllegalArgumentException("Mismatched ComponentName: "
                        + mName + " (expected), " + name + " (actual).");
            }
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mUserId);
                ServiceState serviceState = userState.serviceStateMap.get(mName);
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
                        if (inputState.mInfo.getComponent().equals(name)) {
                            notifyInputStateChangedLocked(userState, inputState.mInfo.getId(),
                                    INPUT_STATE_DISCONNECTED, null);
                        }
                    }
                    updateServiceConnectionLocked(mName, mUserId);
                }
            }
        }
    }

    private final class ServiceCallback extends ITvInputServiceCallback.Stub {
        private final ComponentName mName;
        private final int mUserId;

        ServiceCallback(ComponentName name, int userId) {
            mName = name;
            mUserId = userId;
        }

        @Override
        public void addTvInput(TvInputInfo inputInfo) {
            synchronized (mLock) {
                if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                        != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "The caller does not have permission to add a TV input ("
                            + inputInfo + ").");
                    return;
                }

                ServiceState serviceState = getServiceStateLocked(mName, mUserId);
                serviceState.mInputList.add(inputInfo);
                buildTvInputListLocked(mUserId);
            }
        }

        @Override
        public void removeTvInput(String inputId) {
            synchronized (mLock) {
                if (mContext.checkCallingPermission(android.Manifest.permission.TV_INPUT_HARDWARE)
                        != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "The caller does not have permission to remove a TV input ("
                            + inputId + ").");
                    return;
                }

                ServiceState serviceState = getServiceStateLocked(mName, mUserId);
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
                } else {
                    Slog.e(TAG, "TvInputInfo with inputId=" + inputId + " not found.");
                }
            }
        }
    }

    private final class LogHandler extends Handler {
        private static final int MSG_OPEN_ENTRY = 1;
        private static final int MSG_UPDATE_ENTRY = 2;
        private static final int MSG_CLOSE_ENTRY = 3;

        public LogHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OPEN_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long channelId = (long) args.arg2;
                    long time = (long) args.arg3;
                    onOpenEntry(uri, channelId, time);
                    args.recycle();
                    return;
                }
                case MSG_UPDATE_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long channelId = (long) args.arg2;
                    long time = (long) args.arg3;
                    onUpdateEntry(uri, channelId, time);
                    args.recycle();
                    return;
                }
                case MSG_CLOSE_ENTRY: {
                    SomeArgs args = (SomeArgs) msg.obj;
                    Uri uri = (Uri) args.arg1;
                    long time = (long) args.arg2;
                    onCloseEntry(uri, time);
                    args.recycle();
                    return;
                }
                default: {
                    Slog.w(TAG, "Unhandled message code: " + msg.what);
                    return;
                }
            }
        }

        private void onOpenEntry(Uri uri, long channelId, long watchStarttime) {
            String[] projection = {
                    TvContract.Programs.COLUMN_TITLE,
                    TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS,
                    TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS,
                    TvContract.Programs.COLUMN_SHORT_DESCRIPTION
            };
            String selection = TvContract.Programs.COLUMN_CHANNEL_ID + "=? AND "
                    + TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS + "<=? AND "
                    + TvContract.Programs.COLUMN_END_TIME_UTC_MILLIS + ">?";
            String[] selectionArgs = {
                    String.valueOf(channelId),
                    String.valueOf(watchStarttime),
                    String.valueOf(watchStarttime)
            };
            String sortOrder = TvContract.Programs.COLUMN_START_TIME_UTC_MILLIS + " ASC";
            Cursor cursor = null;
            try {
                cursor = mContentResolver.query(TvContract.Programs.CONTENT_URI, projection,
                        selection, selectionArgs, sortOrder);
                if (cursor != null && cursor.moveToNext()) {
                    ContentValues values = new ContentValues();
                    values.put(TvContract.WatchedPrograms.COLUMN_TITLE, cursor.getString(0));
                    values.put(TvContract.WatchedPrograms.COLUMN_START_TIME_UTC_MILLIS,
                            cursor.getLong(1));
                    long endTime = cursor.getLong(2);
                    values.put(TvContract.WatchedPrograms.COLUMN_END_TIME_UTC_MILLIS, endTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_DESCRIPTION, cursor.getString(3));
                    mContentResolver.update(uri, values, null, null);

                    // Schedule an update when the current program ends.
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = uri;
                    args.arg2 = channelId;
                    args.arg3 = endTime;
                    Message msg = obtainMessage(LogHandler.MSG_UPDATE_ENTRY, args);
                    sendMessageDelayed(msg, endTime - System.currentTimeMillis());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        private void onUpdateEntry(Uri uri, long channelId, long time) {
            String[] projection = {
                    TvContract.WatchedPrograms.COLUMN_WATCH_START_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.COLUMN_WATCH_END_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.COLUMN_TITLE,
                    TvContract.WatchedPrograms.COLUMN_START_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.COLUMN_END_TIME_UTC_MILLIS,
                    TvContract.WatchedPrograms.COLUMN_DESCRIPTION
            };
            Cursor cursor = null;
            try {
                cursor = mContentResolver.query(uri, projection, null, null, null);
                if (cursor != null && cursor.moveToNext()) {
                    long watchStartTime = cursor.getLong(0);
                    long watchEndTime = cursor.getLong(1);
                    String title = cursor.getString(2);
                    long startTime = cursor.getLong(3);
                    long endTime = cursor.getLong(4);
                    String description = cursor.getString(5);

                    // Do nothing if the current log entry is already closed.
                    if (watchEndTime > 0) {
                        return;
                    }

                    // The current program has just ended. Create a (complete) log entry off the
                    // current entry.
                    ContentValues values = new ContentValues();
                    values.put(TvContract.WatchedPrograms.COLUMN_WATCH_START_TIME_UTC_MILLIS,
                            watchStartTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_WATCH_END_TIME_UTC_MILLIS, time);
                    values.put(TvContract.WatchedPrograms.COLUMN_CHANNEL_ID, channelId);
                    values.put(TvContract.WatchedPrograms.COLUMN_TITLE, title);
                    values.put(TvContract.WatchedPrograms.COLUMN_START_TIME_UTC_MILLIS, startTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_END_TIME_UTC_MILLIS, endTime);
                    values.put(TvContract.WatchedPrograms.COLUMN_DESCRIPTION, description);
                    mContentResolver.insert(TvContract.WatchedPrograms.CONTENT_URI, values);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            // Re-open the current log entry with the next program information.
            onOpenEntry(uri, channelId, time);
        }

        private void onCloseEntry(Uri uri, long watchEndTime) {
            ContentValues values = new ContentValues();
            values.put(TvContract.WatchedPrograms.COLUMN_WATCH_END_TIME_UTC_MILLIS, watchEndTime);
            mContentResolver.update(uri, values, null, null);
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
        public void onHardwareDeviceRemoved(int deviceId) {
            synchronized (mLock) {
                UserState userState = getUserStateLocked(mCurrentUserId);
                // Broadcast the event to all hardware inputs.
                for (ServiceState serviceState : userState.serviceStateMap.values()) {
                    if (!serviceState.mIsHardware || serviceState.mService == null) continue;
                    try {
                        serviceState.mService.notifyHardwareRemoved(deviceId);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "error in notifyHardwareRemoved", e);
                    }
                }
            }
        }

        @Override
        public void onHdmiCecDeviceAdded(HdmiCecDeviceInfo cecDevice) {
            synchronized (mLock) {
                // TODO
            }
        }

        @Override
        public void onHdmiCecDeviceRemoved(HdmiCecDeviceInfo cecDevice) {
            synchronized (mLock) {
                // TODO
            }
        }
    }
}
