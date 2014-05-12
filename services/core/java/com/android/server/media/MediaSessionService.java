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

package com.android.server.media;

import android.Manifest;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.routeprovider.RouteRequest;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionController;
import android.media.session.ISessionManager;
import android.media.session.PlaybackState;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.Watchdog.Monitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService implements Monitor {
    private static final String TAG = "MediaSessionService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SessionManagerImpl mSessionManagerImpl;
    private final MediaRouteProviderWatcher mRouteProviderWatcher;
    private final MediaSessionStack mPriorityStack;

    private final ArrayList<MediaSessionRecord> mRecords = new ArrayList<MediaSessionRecord>();
    private final ArrayList<MediaRouteProviderProxy> mProviders
            = new ArrayList<MediaRouteProviderProxy>();
    private final Object mLock = new Object();
    private final Handler mHandler = new Handler();

    private MediaSessionRecord mPrioritySession;

    // Used to keep track of the current request to show routes for a specific
    // session so we drop late callbacks properly.
    private int mShowRoutesRequestId = 0;

    // TODO refactor to have per user state for providers. See
    // MediaRouterService for an example

    public MediaSessionService(Context context) {
        super(context);
        mSessionManagerImpl = new SessionManagerImpl();
        mRouteProviderWatcher = new MediaRouteProviderWatcher(context, mProviderWatcherCallback,
                mHandler, context.getUserId());
        mPriorityStack = new MediaSessionStack();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
        mRouteProviderWatcher.start();
        Watchdog.getInstance().addMonitor(this);
    }

    /**
     * Should trigger showing the Media route picker dialog. Right now it just
     * kicks off a query to all the providers to get routes.
     *
     * @param record The session to show the picker for.
     */
    public void showRoutePickerForSession(MediaSessionRecord record) {
        // TODO for now just toggle the route to test (we will only have one
        // match for now)
        if (record.getRoute() != null) {
            // For now send null to mean the local route
            record.selectRoute(null);
            return;
        }
        mShowRoutesRequestId++;
        ArrayList<MediaRouteProviderProxy> providers = mRouteProviderWatcher.getProviders();
        for (int i = providers.size() - 1; i >= 0; i--) {
            MediaRouteProviderProxy provider = providers.get(i);
            provider.getRoutes(record, mShowRoutesRequestId);
        }
    }

    /**
     * Connect a session to the given route.
     *
     * @param session The session to connect.
     * @param route The route to connect to.
     * @param options The options to use for the connection.
     */
    public void connectToRoute(MediaSessionRecord session, RouteInfo route,
            RouteOptions options) {
        synchronized (mLock) {
            MediaRouteProviderProxy proxy = getProviderLocked(route.getProvider());
            if (proxy == null) {
                Log.w(TAG, "Provider for route " + route.getName() + " does not exist.");
                return;
            }
            RouteRequest request = new RouteRequest(session.getSessionInfo(), options, true);
            // TODO make connect an async call to a ThreadPoolExecutor
            proxy.connectToRoute(session, route, request);
        }
    }

    public void updateSession(MediaSessionRecord record) {
        synchronized (mLock) {
            mPriorityStack.onSessionStateChange(record);
            if (record.isSystemPriority()) {
                if (record.isActive()) {
                    if (mPrioritySession != null) {
                        Log.w(TAG, "Replacing existing priority session with a new session");
                    }
                    mPrioritySession = record;
                } else {
                    if (mPrioritySession == record) {
                        mPrioritySession = null;
                    }
                }
            }
        }
    }

    public void onSessionPlaystateChange(MediaSessionRecord record, int oldState, int newState) {
        synchronized (mLock) {
            mPriorityStack.onPlaystateChange(record, oldState, newState);
        }
    }

    @Override
    public void monitor() {
        synchronized (mLock) {
            // Check for deadlock
        }
    }

    void sessionDied(MediaSessionRecord session) {
        synchronized (mLock) {
            destroySessionLocked(session);
        }
    }

    void destroySession(MediaSessionRecord session) {
        synchronized (mLock) {
            destroySessionLocked(session);
        }
    }

    private void destroySessionLocked(MediaSessionRecord session) {
        mRecords.remove(session);
        mPriorityStack.removeSession(session);
        if (session == mPrioritySession) {
            mPrioritySession = null;
        }
    }

    private void enforcePackageName(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        final int packageCount = packages.length;
        for (int i = 0; i < packageCount; i++) {
            if (packageName.equals(packages[i])) {
                return;
            }
        }
        throw new IllegalArgumentException("packageName is not owned by the calling process");
    }

    protected void enforcePhoneStatePermission(int pid, int uid) {
        if (getContext().checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, pid, uid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold the MODIFY_PHONE_STATE permission.");
        }
    }

    /**
     * Checks a caller's authorization to register an IRemoteControlDisplay.
     * Authorization is granted if one of the following is true:
     * <ul>
     * <li>the caller has android.Manifest.permission.MEDIA_CONTENT_CONTROL
     * permission</li>
     * <li>the caller's listener is one of the enabled notification listeners
     * for the caller's user</li>
     * </ul>
     */
    private void enforceMediaPermissions(ComponentName compName, int pid, int uid,
            int resolvedUserId) {
        if (getContext()
                .checkPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                    != PackageManager.PERMISSION_GRANTED
                && !isEnabledNotificationListener(compName, UserHandle.getUserId(uid),
                        resolvedUserId)) {
            throw new SecurityException("Missing permission to control media.");
        }
    }

    /**
     * This checks if the component is an enabled notification listener for the
     * specified user. Enabled components may only operate on behalf of the user
     * they're running as.
     *
     * @param compName The component that is enabled.
     * @param userId The user id of the caller.
     * @param forUserId The user id they're making the request on behalf of.
     * @return True if the component is enabled, false otherwise
     */
    private boolean isEnabledNotificationListener(ComponentName compName, int userId,
            int forUserId) {
        if (userId != forUserId) {
            // You may not access another user's content as an enabled listener.
            return false;
        }
        if (compName != null) {
            final String enabledNotifListeners = Settings.Secure.getStringForUser(
                    getContext().getContentResolver(),
                    Settings.Secure.ENABLED_NOTIFICATION_LISTENERS,
                    userId);
            if (enabledNotifListeners != null) {
                final String[] components = enabledNotifListeners.split(":");
                for (int i = 0; i < components.length; i++) {
                    final ComponentName component =
                            ComponentName.unflattenFromString(components[i]);
                    if (component != null) {
                        if (compName.equals(component)) {
                            if (DEBUG) {
                                Log.d(TAG, "ok to get sessions: " + component +
                                        " is authorized notification listener");
                            }
                            return true;
                        }
                    }
                }
            }
            if (DEBUG) {
                Log.d(TAG, "not ok to get sessions, " + compName +
                        " is not in list of ENABLED_NOTIFICATION_LISTENERS for user " + userId);
            }
        }
        return false;
    }

    private MediaSessionRecord createSessionInternal(int callerPid, int callerUid, int userId,
            String callerPackageName, ISessionCallback cb, String tag) {
        synchronized (mLock) {
            return createSessionLocked(callerPid, callerUid, userId, callerPackageName, cb, tag);
        }
    }

    private MediaSessionRecord createSessionLocked(int callerPid, int callerUid, int userId,
            String callerPackageName, ISessionCallback cb, String tag) {
        final MediaSessionRecord session = new MediaSessionRecord(callerPid, callerUid, userId,
                callerPackageName, cb, tag, this, mHandler);
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }
        mRecords.add(session);
        mPriorityStack.addSession(session);
        if (DEBUG) {
            Log.d(TAG, "Created session for package " + callerPackageName + " with tag " + tag);
        }
        return session;
    }

    private int findIndexOfSessionForIdLocked(String sessionId) {
        for (int i = mRecords.size() - 1; i >= 0; i--) {
            MediaSessionRecord session = mRecords.get(i);
            if (TextUtils.equals(session.getSessionInfo().getId(), sessionId)) {
                return i;
            }
        }
        return -1;
    }

    private MediaRouteProviderProxy getProviderLocked(String providerId) {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            MediaRouteProviderProxy provider = mProviders.get(i);
            if (TextUtils.equals(providerId, provider.getId())) {
                return provider;
            }
        }
        return null;
    }

    private boolean isSessionDiscoverable(MediaSessionRecord record) {
        // TODO probably want to check more than if it's published.
        return record.isActive();
    }

    private MediaRouteProviderWatcher.Callback mProviderWatcherCallback
            = new MediaRouteProviderWatcher.Callback() {
        @Override
        public void removeProvider(MediaRouteProviderProxy provider) {
            synchronized (mLock) {
                mProviders.remove(provider);
                provider.setRoutesListener(null);
                provider.setInterested(false);
            }
        }

        @Override
        public void addProvider(MediaRouteProviderProxy provider) {
            synchronized (mLock) {
                mProviders.add(provider);
                provider.setRoutesListener(mRoutesCallback);
                provider.setInterested(true);
            }
        }
    };

    private MediaRouteProviderProxy.RoutesListener mRoutesCallback
            = new MediaRouteProviderProxy.RoutesListener() {
        @Override
        public void onRoutesUpdated(String sessionId, ArrayList<RouteInfo> routes,
                int reqId) {
            // TODO for now select the first route to test, eventually add the
            // new routes to the dialog if it is still open
            synchronized (mLock) {
                int index = findIndexOfSessionForIdLocked(sessionId);
                if (index != -1 && routes != null && routes.size() > 0) {
                    MediaSessionRecord record = mRecords.get(index);
                    record.selectRoute(routes.get(0));
                }
            }
        }

        @Override
        public void onRouteConnected(String sessionId, RouteInfo route,
                RouteRequest options, RouteConnectionRecord connection) {
            synchronized (mLock) {
                int index = findIndexOfSessionForIdLocked(sessionId);
                if (index != -1) {
                    MediaSessionRecord session = mRecords.get(index);
                    session.setRouteConnected(route, options.getConnectionOptions(), connection);
                }
            }
        }
    };

    class SessionManagerImpl extends ISessionManager.Stub {
        // TODO add createSessionAsUser, pass user-id to
        // ActivityManagerNative.handleIncomingUser and stash result for use
        // when starting services on that session's behalf.
        @Override
        public ISession createSession(String packageName, ISessionCallback cb, String tag,
                int userId) throws RemoteException {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                enforcePackageName(packageName, uid);
                int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                        false /* allowAll */, true /* requireFull */, "createSession", packageName);
                if (cb == null) {
                    throw new IllegalArgumentException("Controller callback cannot be null");
                }
                return createSessionInternal(pid, uid, resolvedUserId, packageName, cb, tag)
                        .getSessionBinder();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<IBinder> getSessions(ComponentName componentName, int userId) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                String packageName = null;
                if (componentName != null) {
                    // If they gave us a component name verify they own the
                    // package
                    packageName = componentName.getPackageName();
                    enforcePackageName(packageName, uid);
                }
                // Check that they can make calls on behalf of the user and
                // get the final user id
                int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                        true /* allowAll */, true /* requireFull */, "getSessions", packageName);
                // Check if they have the permissions or their component is
                // enabled for the user they're calling from.
                enforceMediaPermissions(componentName, pid, uid, resolvedUserId);
                ArrayList<IBinder> binders = new ArrayList<IBinder>();
                synchronized (mLock) {
                    ArrayList<MediaSessionRecord> records = mPriorityStack
                            .getActiveSessions(resolvedUserId);
                    int size = records.size();
                    for (int i = 0; i < size; i++) {
                        binders.add(records.get(i).getControllerBinder().asBinder());
                    }
                }
                return binders;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (getContext().checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump MediaSessionService from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            pw.println("MEDIA SESSION SERVICE (dumpsys media_session)");
            pw.println();

            synchronized (mLock) {
                pw.println("Session for calls:" + mPrioritySession);
                if (mPrioritySession != null) {
                    mPrioritySession.dump(pw, "");
                }
                int count = mRecords.size();
                pw.println(count + " Sessions:");
                for (int i = 0; i < count; i++) {
                    mRecords.get(i).dump(pw, "");
                    pw.println();
                }
                mPriorityStack.dump(pw, "");

                pw.println("Providers:");
                count = mProviders.size();
                for (int i = 0; i < count; i++) {
                    MediaRouteProviderProxy provider = mProviders.get(i);
                    provider.dump(pw, "");
                }
            }
        }
    }

}
