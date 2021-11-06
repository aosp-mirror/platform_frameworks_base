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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.tv.interactive.ITvIAppClient;
import android.media.tv.interactive.ITvIAppManager;
import android.media.tv.interactive.ITvIAppService;
import android.media.tv.interactive.ITvIAppServiceCallback;
import android.media.tv.interactive.ITvIAppSession;
import android.media.tv.interactive.ITvIAppSessionCallback;
import android.media.tv.interactive.TvIAppService;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import android.view.Surface;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;
import com.android.server.utils.Slogf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
    }

    @Override
    public void onStart() {
        if (DEBUG) {
            Slogf.d(TAG, "onStart");
        }
        publishBinderService(Context.TV_IAPP_SERVICE, new BinderService());
    }

    private SessionState getSessionState(IBinder sessionToken) {
        // TODO: implement user state and get session from it.
        return null;
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
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, seq);
                        return;
                    }
                    UserState userState = getOrCreateUserStateLocked(resolvedUserId);
                    TvIAppState iAppState = userState.mIAppMap.get(iAppServiceId);
                    if (iAppState == null) {
                        Slogf.w(TAG, "Failed to find state for iAppServiceId=" + iAppServiceId);
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, seq);
                        return;
                    }
                    ServiceState serviceState =
                            userState.mServiceStateMap.get(iAppState.mComponentName);
                    if (serviceState == null) {
                        int tiasUid = PackageManager.getApplicationInfoAsUserCached(
                                iAppState.mComponentName.getPackageName(), 0, resolvedUserId).uid;
                        serviceState = new ServiceState(iAppState.mComponentName, resolvedUserId);
                        userState.mServiceStateMap.put(iAppState.mComponentName, serviceState);
                    }
                    // Send a null token immediately while reconnecting.
                    if (serviceState.mReconnecting) {
                        sendSessionTokenToClientLocked(client, iAppServiceId, null, seq);
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
        public void startIApp(IBinder sessionToken, int userId) {
            if (DEBUG) {
                Slogf.d(TAG, "BinderService#start(userId=%d)", userId);
            }
            try {
                SessionState sessionState = getSessionState(sessionToken);
                if (sessionState != null && sessionState.mSession != null) {
                    sessionState.mSession.startIApp();
                }
            } catch (RemoteException e) {
                Slogf.e(TAG, "error in start", e);
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
    }

    @GuardedBy("mLock")
    private void sendSessionTokenToClientLocked(ITvIAppClient client, String iAppServiceId,
            IBinder sessionToken, int seq) {
        try {
            client.onSessionCreated(iAppServiceId, sessionToken, seq);
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

        // Set up a callback to send the session token.
        ITvIAppSessionCallback callback = new SessionCallback(sessionState);

        boolean created = true;
        // Create a session. When failed, send a null token immediately.
        try {
            service.createSession(callback, sessionState.mIAppServiceId, sessionState.mType);
        } catch (RemoteException e) {
            Slogf.e(TAG, "error in createSession", e);
            sendSessionTokenToClientLocked(sessionState.mClient, sessionState.mIAppServiceId, null,
                    sessionState.mSeq);
            created = false;
        }
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
                    sessionState.mIAppServiceId, null, sessionState.mSeq);
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

        private UserState(int userId) {
            mUserId = userId;
        }
    }

    private static final class TvIAppState {
        private final String mIAppServiceId;
        private final ComponentName mComponentName;

        TvIAppState(String id, ComponentName componentName) {
            mIAppServiceId = id;
            mComponentName = componentName;
        }
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

        private ITvIAppService mService;
        private ServiceCallback mCallback;
        private boolean mBound;
        private boolean mReconnecting;

        private ServiceState(ComponentName component, int userId) {
            mComponent = component;
            mConnection = new IAppServiceConnection(component, userId);
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
    }

    private final class SessionCallback extends ITvIAppSessionCallback.Stub {
        private final SessionState mSessionState;

        SessionCallback(SessionState sessionState) {
            mSessionState = sessionState;
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
                            mSessionState.mSeq);
                } else {
                    removeSessionStateLocked(mSessionState.mSessionToken, mSessionState.mUserId);
                    sendSessionTokenToClientLocked(mSessionState.mClient,
                            mSessionState.mIAppServiceId, null, mSessionState.mSeq);
                }
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
