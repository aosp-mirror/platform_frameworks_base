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
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.routeprovider.RouteRequest;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.media.session.MediaSession;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

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
    // private final MediaRouteProviderWatcher mRouteProviderWatcher;
    private final MediaSessionStack mPriorityStack;

    private final ArrayList<MediaSessionRecord> mAllSessions = new ArrayList<MediaSessionRecord>();
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<UserRecord>();
    // private final ArrayList<MediaRouteProviderProxy> mProviders
    // = new ArrayList<MediaRouteProviderProxy>();
    private final Object mLock = new Object();
    private final Handler mHandler = new Handler();
    private final PowerManager.WakeLock mMediaEventWakeLock;

    private MediaSessionRecord mPrioritySession;
    private int mCurrentUserId = -1;

    // Used to keep track of the current request to show routes for a specific
    // session so we drop late callbacks properly.
    private int mShowRoutesRequestId = 0;

    // TODO refactor to have per user state for providers. See
    // MediaRouterService for an example

    public MediaSessionService(Context context) {
        super(context);
        mSessionManagerImpl = new SessionManagerImpl();
        mPriorityStack = new MediaSessionStack();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mMediaEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleMediaEvent");
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
        Watchdog.getInstance().addMonitor(this);
        updateUser();
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
        synchronized (mLock) {
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session tried to show route picker. Ignoring.");
                return;
            }
            RouteInfo current = record.getRoute();
            UserRecord user = mUserRecords.get(record.getUserId());
            if (current != null) {
                // For now send null to mean the local route
                MediaRouteProviderProxy proxy = user.getProviderLocked(current.getProvider());
                if (proxy != null) {
                    proxy.removeSession(record);
                }
                record.selectRoute(null);
                return;
            }
            ArrayList<MediaRouteProviderProxy> providers = user.getProvidersLocked();
            mShowRoutesRequestId++;
            for (int i = providers.size() - 1; i >= 0; i--) {
                MediaRouteProviderProxy provider = providers.get(i);
                provider.getRoutes(record, mShowRoutesRequestId);
            }
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
            if (!mAllSessions.contains(session)) {
                Log.d(TAG, "Unknown session attempting to connect to route. Ignoring");
                return;
            }
            UserRecord user = mUserRecords.get(session.getUserId());
            if (user == null) {
                Log.wtf(TAG, "connectToRoute: User " + session.getUserId() + " does not exist.");
                return;
            }
            MediaRouteProviderProxy proxy = user.getProviderLocked(route.getProvider());
            if (proxy == null) {
                Log.w(TAG, "Provider for route " + route.getName() + " does not exist.");
                return;
            }
            RouteRequest request = new RouteRequest(session.getSessionInfo(), options, true);
            proxy.connectToRoute(session, route, request);
        }
    }

    public void updateSession(MediaSessionRecord record) {
        synchronized (mLock) {
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session updated. Ignoring.");
                return;
            }
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
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session changed playback state. Ignoring.");
                return;
            }
            mPriorityStack.onPlaystateChange(record, oldState, newState);
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        updateUser();
    }

    @Override
    public void onSwitchUser(int userHandle) {
        updateUser();
    }

    @Override
    public void onStopUser(int userHandle) {
        synchronized (mLock) {
            UserRecord user = mUserRecords.get(userHandle);
            if (user != null) {
                destroyUserLocked(user);
            }
        }
    }

    @Override
    public void monitor() {
        synchronized (mLock) {
            // Check for deadlock
        }
    }

    protected void enforcePhoneStatePermission(int pid, int uid) {
        if (getContext().checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, pid, uid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold the MODIFY_PHONE_STATE permission.");
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

    private void updateUser() {
        synchronized (mLock) {
            int userId = ActivityManager.getCurrentUser();
            if (mCurrentUserId != userId) {
                final int oldUserId = mCurrentUserId;
                mCurrentUserId = userId; // do this first

                UserRecord oldUser = mUserRecords.get(oldUserId);
                if (oldUser != null) {
                    oldUser.stopLocked();
                }

                UserRecord newUser = getOrCreateUser(userId);
                newUser.startLocked();
            }
        }
    }

    /**
     * Stop the user and unbind from everything.
     *
     * @param user The user to dispose of
     */
    private void destroyUserLocked(UserRecord user) {
        user.stopLocked();
        user.destroyLocked();
        mUserRecords.remove(user.mUserId);
    }

    /*
     * When a session is removed several things need to happen.
     * 1. We need to remove it from the relevant user.
     * 2. We need to remove it from the priority stack.
     * 3. We need to remove it from all sessions.
     * 4. If this is the system priority session we need to clear it.
     * 5. We need to unlink to death from the cb binder
     * 6. We need to tell the session to do any final cleanup (onDestroy)
     */
    private void destroySessionLocked(MediaSessionRecord session) {
        int userId = session.getUserId();
        UserRecord user = mUserRecords.get(userId);
        if (user != null) {
            user.removeSessionLocked(session);
        }

        mPriorityStack.removeSession(session);
        mAllSessions.remove(session);
        if (session == mPrioritySession) {
            mPrioritySession = null;
        }

        try {
            session.getCallback().asBinder().unlinkToDeath(session, 0);
        } catch (Exception e) {
            // ignore exceptions while destroying a session.
        }
        session.onDestroy();
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
            String callerPackageName, ISessionCallback cb, String tag) throws RemoteException {
        synchronized (mLock) {
            return createSessionLocked(callerPid, callerUid, userId, callerPackageName, cb, tag);
        }
    }

    /*
     * When a session is created the following things need to happen.
     * 1. Its callback binder needs a link to death
     * 2. It needs to be added to all sessions.
     * 3. It needs to be added to the priority stack.
     * 4. It needs to be added to the relevant user record.
     */
    private MediaSessionRecord createSessionLocked(int callerPid, int callerUid, int userId,
            String callerPackageName, ISessionCallback cb, String tag) {

        final MediaSessionRecord session = new MediaSessionRecord(callerPid, callerUid, userId,
                callerPackageName, cb, tag, this, mHandler);
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }

        mAllSessions.add(session);
        mPriorityStack.addSession(session);

        UserRecord user = getOrCreateUser(userId);
        user.addSessionLocked(session);

        if (DEBUG) {
            Log.d(TAG, "Created session for package " + callerPackageName + " with tag " + tag);
        }
        return session;
    }

    private UserRecord getOrCreateUser(int userId) {
        UserRecord user = mUserRecords.get(userId);
        if (user == null) {
            user = new UserRecord(getContext(), userId);
            mUserRecords.put(userId, user);
        }
        return user;
    }

    private int findIndexOfSessionForIdLocked(String sessionId) {
        for (int i = mAllSessions.size() - 1; i >= 0; i--) {
            MediaSessionRecord session = mAllSessions.get(i);
            if (TextUtils.equals(session.getSessionInfo().getId(), sessionId)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isSessionDiscoverable(MediaSessionRecord record) {
        // TODO probably want to check more than if it's active.
        return record.isActive();
    }

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
                    MediaSessionRecord record = mAllSessions.get(index);
                    RouteInfo route = routes.get(0);
                    record.selectRoute(route);
                    UserRecord user = mUserRecords.get(record.getUserId());
                    MediaRouteProviderProxy provider = user.getProviderLocked(route.getProvider());
                    provider.addSession(record);
                }
            }
        }

        @Override
        public void onRouteConnected(String sessionId, RouteInfo route,
                RouteRequest options, RouteConnectionRecord connection) {
            synchronized (mLock) {
                int index = findIndexOfSessionForIdLocked(sessionId);
                if (index != -1) {
                    MediaSessionRecord session = mAllSessions.get(index);
                    session.setRouteConnected(route, options.getConnectionOptions(), connection);
                }
            }
        }
    };

    /**
     * Information about a particular user. The contents of this object is
     * guarded by mLock.
     */
    final class UserRecord {
        private final int mUserId;
        private final MediaRouteProviderWatcher mRouteProviderWatcher;
        private final ArrayList<MediaRouteProviderProxy> mProviders
                = new ArrayList<MediaRouteProviderProxy>();
        private final ArrayList<MediaSessionRecord> mSessions = new ArrayList<MediaSessionRecord>();

        public UserRecord(Context context, int userId) {
            mUserId = userId;
            mRouteProviderWatcher = new MediaRouteProviderWatcher(context,
                    mProviderWatcherCallback, mHandler, userId);
        }

        public void startLocked() {
            mRouteProviderWatcher.start();
        }

        public void stopLocked() {
            mRouteProviderWatcher.stop();
            updateInterestLocked();
        }

        public void destroyLocked() {
            for (int i = mSessions.size() - 1; i >= 0; i--) {
                MediaSessionRecord session = mSessions.get(i);
                MediaSessionService.this.destroySessionLocked(session);
                if (session.isConnected()) {
                    session.disconnect(MediaSession.DISCONNECT_REASON_USER_STOPPING);
                }
            }
        }

        public ArrayList<MediaRouteProviderProxy> getProvidersLocked() {
            return mProviders;
        }

        public ArrayList<MediaSessionRecord> getSessionsLocked() {
            return mSessions;
        }

        public void addSessionLocked(MediaSessionRecord session) {
            mSessions.add(session);
            updateInterestLocked();
        }

        public void removeSessionLocked(MediaSessionRecord session) {
            mSessions.remove(session);
            RouteInfo route = session.getRoute();
            if (route != null) {
                MediaRouteProviderProxy provider = getProviderLocked(route.getProvider());
                if (provider != null) {
                    provider.removeSession(session);
                }
            }
            updateInterestLocked();
        }

        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.println(prefix + "Record for user " + mUserId);
            String indent = prefix + "  ";
            int size = mProviders.size();
            pw.println(indent + size + " Providers:");
            for (int i = 0; i < size; i++) {
                mProviders.get(i).dump(pw, indent);
            }
            pw.println();
            size = mSessions.size();
            pw.println(indent + size + " Sessions:");
            for (int i = 0; i < size; i++) {
                // Just print the session info, the full session dump will
                // already be in the list of all sessions.
                pw.println(indent + mSessions.get(i).getSessionInfo());
            }
        }

        public void updateInterestLocked() {
            // TODO go through the sessions and build up the set of interfaces
            // we're interested in. Update the provider watcher.
            // For now, just express interest in all providers for the current
            // user
            boolean interested = mUserId == mCurrentUserId;
            for (int i = mProviders.size() - 1; i >= 0; i--) {
                mProviders.get(i).setInterested(interested);
            }
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
    }

    class SessionManagerImpl extends ISessionManager.Stub {
        private static final String EXTRA_WAKELOCK_ACQUIRED =
                "android.media.AudioService.WAKELOCK_ACQUIRED";
        private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980; // magic number

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

        /**
         * Handles the dispatching of the media button events to one of the
         * registered listeners, or if there was none, broadcast an
         * ACTION_MEDIA_BUTTON intent to the rest of the system.
         *
         * @param keyEvent a non-null KeyEvent whose key code is one of the
         *            supported media buttons
         * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held
         *            while this key event is dispatched.
         */
        @Override
        public void dispatchMediaKeyEvent(KeyEvent keyEvent, boolean needWakeLock) {
            if (keyEvent == null || !KeyEvent.isMediaKey(keyEvent.getKeyCode())) {
                Log.w(TAG, "Attempted to dispatch null or non-media key event.");
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                if (needWakeLock) {
                    mMediaEventWakeLock.acquire();
                }
                synchronized (mLock) {
                    MediaSessionRecord mbSession = mPriorityStack
                            .getDefaultMediaButtonSession(mCurrentUserId);
                    if (mbSession != null) {
                        if (DEBUG) {
                            Log.d(TAG, "Sending media key to " + mbSession.getSessionInfo());
                        }
                        mbSession.sendMediaButton(keyEvent,
                                needWakeLock ? mKeyEventDoneReceiver : null);
                    } else {
                        if (DEBUG) {
                            Log.d(TAG, "Sending media key ordered broadcast");
                        }
                        // Fallback to legacy behavior
                        Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
                        keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
                        if (needWakeLock) {
                            keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED,
                                    WAKELOCK_RELEASE_ON_FINISHED);
                        }
                        getContext().sendOrderedBroadcastAsUser(keyIntent, UserHandle.ALL,
                                null, mKeyEventDone, mHandler, Activity.RESULT_OK, null, null);
                    }
                }
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
                int count = mAllSessions.size();
                pw.println(count + " Sessions:");
                for (int i = 0; i < count; i++) {
                    mAllSessions.get(i).dump(pw, "");
                    pw.println();
                }
                mPriorityStack.dump(pw, "");

                pw.println("User Records:");
                count = mUserRecords.size();
                for (int i = 0; i < count; i++) {
                    UserRecord user = mUserRecords.get(i);
                    user.dumpLocked(pw, "");
                }
            }
        }

        ResultReceiver mKeyEventDoneReceiver = new ResultReceiver(mHandler) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                synchronized (mLock) {
                    if (mMediaEventWakeLock.isHeld()) {
                        mMediaEventWakeLock.release();
                    }
                }
            }
        };

        BroadcastReceiver mKeyEventDone = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) {
                    return;
                }
                Bundle extras = intent.getExtras();
                if (extras == null) {
                    return;
                }
                synchronized (mLock) {
                    if (extras.containsKey(EXTRA_WAKELOCK_ACQUIRED)
                            && mMediaEventWakeLock.isHeld()) {
                        mMediaEventWakeLock.release();
                    }
                }
            }
        };
    }

}
