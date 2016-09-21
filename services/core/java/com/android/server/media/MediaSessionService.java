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
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.IRemoteVolumeController;
import android.media.session.IActiveSessionsListener;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.Watchdog.Monitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService implements Monitor {
    private static final String TAG = "MediaSessionService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // Leave log for media key event always.
    private static final boolean DEBUG_MEDIA_KEY_EVENT = DEBUG || true;

    private static final int WAKELOCK_TIMEOUT = 5000;

    /* package */final IBinder mICallback = new Binder();

    private final SessionManagerImpl mSessionManagerImpl;
    private final MediaSessionStack mPriorityStack;

    private final ArrayList<MediaSessionRecord> mAllSessions = new ArrayList<MediaSessionRecord>();
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<UserRecord>();
    private final ArrayList<SessionsListenerRecord> mSessionsListeners
            = new ArrayList<SessionsListenerRecord>();
    private final Object mLock = new Object();
    private final MessageHandler mHandler = new MessageHandler();
    private final PowerManager.WakeLock mMediaEventWakeLock;

    private KeyguardManager mKeyguardManager;
    private IAudioService mAudioService;
    private AudioManagerInternal mAudioManagerInternal;
    private ContentResolver mContentResolver;
    private SettingsObserver mSettingsObserver;

    // List of user IDs running in the foreground.
    // Multiple users can be in the foreground if the work profile is on.
    private final List<Integer> mCurrentUserIdList = new ArrayList<>();

    // Used to notify system UI when remote volume was changed. TODO find a
    // better way to handle this.
    private IRemoteVolumeController mRvc;

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
        mKeyguardManager =
                (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        mAudioService = getAudioService();
        mAudioManagerInternal = LocalServices.getService(AudioManagerInternal.class);
        mContentResolver = getContext().getContentResolver();
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe();

        updateUser();
    }

    private IAudioService getAudioService() {
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        return IAudioService.Stub.asInterface(b);
    }

    public void updateSession(MediaSessionRecord record) {
        synchronized (mLock) {
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session updated. Ignoring.");
                return;
            }
            mPriorityStack.onSessionStateChange(record);
        }
        mHandler.post(MessageHandler.MSG_SESSIONS_CHANGED, record.getUserId(), 0);
    }

    /**
     * Tells the system UI that volume has changed on a remote session.
     */
    public void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session) {
        if (mRvc == null) {
            return;
        }
        try {
            mRvc.remoteVolumeChanged(session.getControllerBinder(), flags);
        } catch (Exception e) {
            Log.wtf(TAG, "Error sending volume change to system UI.", e);
        }
    }

    public void onSessionPlaystateChange(MediaSessionRecord record, int oldState, int newState) {
        boolean updateSessions = false;
        synchronized (mLock) {
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session changed playback state. Ignoring.");
                return;
            }
            updateSessions = mPriorityStack.onPlaystateChange(record, oldState, newState);
        }
        if (updateSessions) {
            mHandler.post(MessageHandler.MSG_SESSIONS_CHANGED, record.getUserId(), 0);
        }
    }

    public void onSessionPlaybackTypeChanged(MediaSessionRecord record) {
        synchronized (mLock) {
            if (!mAllSessions.contains(record)) {
                Log.d(TAG, "Unknown session changed playback type. Ignoring.");
                return;
            }
            pushRemoteVolumeUpdateLocked(record.getUserId());
        }
    }

    @Override
    public void onStartUser(int userId) {
        if (DEBUG) Log.d(TAG, "onStartUser: " + userId);
        updateUser();
    }

    @Override
    public void onSwitchUser(int userId) {
        if (DEBUG) Log.d(TAG, "onSwitchUser: " + userId);
        updateUser();
    }

    @Override
    public void onStopUser(int userId) {
        if (DEBUG) Log.d(TAG, "onStopUser: " + userId);
        synchronized (mLock) {
            UserRecord user = mUserRecords.get(userId);
            if (user != null) {
                destroyUserLocked(user);
            }
            updateUser();
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
            UserManager manager = (UserManager) getContext().getSystemService(Context.USER_SERVICE);
            int currentUser = ActivityManager.getCurrentUser();
            // Include all profiles even though they aren't yet enabled to handle work profile case.
            int[] userIds = manager.getProfileIdsWithDisabled(currentUser);
            mCurrentUserIdList.clear();
            if (userIds != null && userIds.length > 0) {
                for (int userId : userIds) {
                    mCurrentUserIdList.add(userId);
                }
            } else {
                // This shouldn't happen.
                Log.w(TAG, "Failed to get enabled profiles.");
                mCurrentUserIdList.add(currentUser);
            }
            for (int userId : mCurrentUserIdList) {
                if (mUserRecords.get(userId) == null) {
                    mUserRecords.put(userId, new UserRecord(getContext(), userId));
                }
            }
        }
    }

    private void updateActiveSessionListeners() {
        synchronized (mLock) {
            for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
                SessionsListenerRecord listener = mSessionsListeners.get(i);
                try {
                    enforceMediaPermissions(listener.mComponentName, listener.mPid, listener.mUid,
                            listener.mUserId);
                } catch (SecurityException e) {
                    Log.i(TAG, "ActiveSessionsListener " + listener.mComponentName
                            + " is no longer authorized. Disconnecting.");
                    mSessionsListeners.remove(i);
                    try {
                        listener.mListener
                                .onActiveSessionsChanged(new ArrayList<MediaSession.Token>());
                    } catch (Exception e1) {
                        // ignore
                    }
                }
            }
        }
    }

    /**
     * Stop the user and unbind from everything.
     *
     * @param user The user to dispose of
     */
    private void destroyUserLocked(UserRecord user) {
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
        if (DEBUG) {
            Log.d(TAG, "Destroying " + session);
        }
        int userId = session.getUserId();
        UserRecord user = mUserRecords.get(userId);
        if (user != null) {
            user.removeSessionLocked(session);
        }

        mPriorityStack.removeSession(session);
        mAllSessions.remove(session);

        try {
            session.getCallback().asBinder().unlinkToDeath(session, 0);
        } catch (Exception e) {
            // ignore exceptions while destroying a session.
        }
        session.onDestroy();

        mHandler.post(MessageHandler.MSG_SESSIONS_CHANGED, session.getUserId(), 0);
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
        if (isCurrentVolumeController(uid)) return;
        if (getContext()
                .checkPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                    != PackageManager.PERMISSION_GRANTED
                && !isEnabledNotificationListener(compName, UserHandle.getUserId(uid),
                        resolvedUserId)) {
            throw new SecurityException("Missing permission to control media.");
        }
    }

    private boolean isCurrentVolumeController(int uid) {
        if (mAudioManagerInternal != null) {
            final int vcuid = mAudioManagerInternal.getVolumeControllerUid();
            if (vcuid > 0 && uid == vcuid) {
                return true;
            }
        }
        return false;
    }

    private void enforceSystemUiPermission(String action, int pid, int uid) {
        if (isCurrentVolumeController(uid)) return;
        if (getContext().checkPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                pid, uid) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Only system ui may " + action);
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
        if (DEBUG) {
            Log.d(TAG, "Checking if enabled notification listener " + compName);
        }
        if (compName != null) {
            final String enabledNotifListeners = Settings.Secure.getStringForUser(mContentResolver,
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
                                Log.d(TAG, "ok to get sessions. " + component +
                                        " is authorized notification listener");
                            }
                            return true;
                        }
                    }
                }
            }
            if (DEBUG) {
                Log.d(TAG, "not ok to get sessions. " + compName +
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

        UserRecord user = mUserRecords.get(userId);
        if (user == null) {
            Log.wtf(TAG, "Request from invalid user: " +  userId);
            throw new RuntimeException("Session request from invalid user.");
        }

        final MediaSessionRecord session = new MediaSessionRecord(callerPid, callerUid, userId,
                callerPackageName, cb, tag, this, mHandler);
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }

        mAllSessions.add(session);
        mPriorityStack.addSession(session, mCurrentUserIdList.contains(userId));
        user.addSessionLocked(session);

        mHandler.post(MessageHandler.MSG_SESSIONS_CHANGED, userId, 0);

        if (DEBUG) {
            Log.d(TAG, "Created session for " + callerPackageName + " with tag " + tag);
        }
        return session;
    }

    private int findIndexOfSessionsListenerLocked(IActiveSessionsListener listener) {
        for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
            if (mSessionsListeners.get(i).mListener.asBinder() == listener.asBinder()) {
                return i;
            }
        }
        return -1;
    }

    private void pushSessionsChanged(int userId) {
        synchronized (mLock) {
            List<MediaSessionRecord> records = mPriorityStack.getActiveSessions(userId);
            int size = records.size();
            if (size > 0 && records.get(0).isPlaybackActive(false)) {
                rememberMediaButtonReceiverLocked(records.get(0));
            }
            ArrayList<MediaSession.Token> tokens = new ArrayList<MediaSession.Token>();
            for (int i = 0; i < size; i++) {
                tokens.add(new MediaSession.Token(records.get(i).getControllerBinder()));
            }
            pushRemoteVolumeUpdateLocked(userId);
            for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
                SessionsListenerRecord record = mSessionsListeners.get(i);
                if (record.mUserId == UserHandle.USER_ALL || record.mUserId == userId) {
                    try {
                        record.mListener.onActiveSessionsChanged(tokens);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Dead ActiveSessionsListener in pushSessionsChanged, removing",
                                e);
                        mSessionsListeners.remove(i);
                    }
                }
            }
        }
    }

    private void pushRemoteVolumeUpdateLocked(int userId) {
        if (mRvc != null) {
            try {
                MediaSessionRecord record = mPriorityStack.getDefaultRemoteSession(userId);
                mRvc.updateRemoteController(record == null ? null : record.getControllerBinder());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error sending default remote volume to sys ui.", e);
            }
        }
    }

    private void rememberMediaButtonReceiverLocked(MediaSessionRecord record) {
        PendingIntent receiver = record.getMediaButtonReceiver();
        UserRecord user = mUserRecords.get(record.getUserId());
        if (receiver != null && user != null) {
            user.mLastMediaButtonReceiver = receiver;
            ComponentName component = receiver.getIntent().getComponent();
            if (component != null && record.getPackageName().equals(component.getPackageName())) {
                Settings.Secure.putStringForUser(mContentResolver,
                        Settings.System.MEDIA_BUTTON_RECEIVER, component.flattenToString(),
                        record.getUserId());
            }
        }
    }

    /**
     * Information about a particular user. The contents of this object is
     * guarded by mLock.
     */
    final class UserRecord {
        private final int mUserId;
        private final ArrayList<MediaSessionRecord> mSessions = new ArrayList<MediaSessionRecord>();
        private final Context mContext;
        private PendingIntent mLastMediaButtonReceiver;
        private ComponentName mRestoredMediaButtonReceiver;

        public UserRecord(Context context, int userId) {
            mContext = context;
            mUserId = userId;
            restoreMediaButtonReceiver();
        }

        public void destroyLocked() {
            for (int i = mSessions.size() - 1; i >= 0; i--) {
                MediaSessionRecord session = mSessions.get(i);
                MediaSessionService.this.destroySessionLocked(session);
            }
        }

        public ArrayList<MediaSessionRecord> getSessionsLocked() {
            return mSessions;
        }

        public void addSessionLocked(MediaSessionRecord session) {
            mSessions.add(session);
        }

        public void removeSessionLocked(MediaSessionRecord session) {
            mSessions.remove(session);
        }

        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.println(prefix + "Record for user " + mUserId);
            String indent = prefix + "  ";
            pw.println(indent + "MediaButtonReceiver:" + mLastMediaButtonReceiver);
            pw.println(indent + "Restored ButtonReceiver:" + mRestoredMediaButtonReceiver);
            int size = mSessions.size();
            pw.println(indent + size + " Sessions:");
            for (int i = 0; i < size; i++) {
                // Just print the short version, the full session dump will
                // already be in the list of all sessions.
                pw.println(indent + mSessions.get(i).toString());
            }
        }

        private void restoreMediaButtonReceiver() {
            String receiverName = Settings.Secure.getStringForUser(mContentResolver,
                    Settings.System.MEDIA_BUTTON_RECEIVER, mUserId);
            if (!TextUtils.isEmpty(receiverName)) {
                ComponentName eventReceiver = ComponentName.unflattenFromString(receiverName);
                if (eventReceiver == null) {
                    // an invalid name was persisted
                    return;
                }
                mRestoredMediaButtonReceiver = eventReceiver;
            }
        }
    }

    final class SessionsListenerRecord implements IBinder.DeathRecipient {
        private final IActiveSessionsListener mListener;
        private final ComponentName mComponentName;
        private final int mUserId;
        private final int mPid;
        private final int mUid;

        public SessionsListenerRecord(IActiveSessionsListener listener,
                ComponentName componentName,
                int userId, int pid, int uid) {
            mListener = listener;
            mComponentName = componentName;
            mUserId = userId;
            mPid = pid;
            mUid = uid;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSessionsListeners.remove(this);
            }
        }
    }

    final class SettingsObserver extends ContentObserver {
        private final Uri mSecureSettingsUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_NOTIFICATION_LISTENERS);

        private SettingsObserver() {
            super(null);
        }

        private void observe() {
            mContentResolver.registerContentObserver(mSecureSettingsUri,
                    false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateActiveSessionListeners();
        }
    }

    class SessionManagerImpl extends ISessionManager.Stub {
        private static final String EXTRA_WAKELOCK_ACQUIRED =
                "android.media.AudioService.WAKELOCK_ACQUIRED";
        private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980; // magic number

        private boolean mVoiceButtonDown = false;
        private boolean mVoiceButtonHandled = false;

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
                int resolvedUserId = verifySessionsRequest(componentName, userId, pid, uid);
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
        public void addSessionsListener(IActiveSessionsListener listener,
                ComponentName componentName, int userId) throws RemoteException {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                int resolvedUserId = verifySessionsRequest(componentName, userId, pid, uid);
                synchronized (mLock) {
                    int index = findIndexOfSessionsListenerLocked(listener);
                    if (index != -1) {
                        Log.w(TAG, "ActiveSessionsListener is already added, ignoring");
                        return;
                    }
                    SessionsListenerRecord record = new SessionsListenerRecord(listener,
                            componentName, resolvedUserId, pid, uid);
                    try {
                        listener.asBinder().linkToDeath(record, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "ActiveSessionsListener is dead, ignoring it", e);
                        return;
                    }
                    mSessionsListeners.add(record);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeSessionsListener(IActiveSessionsListener listener)
                throws RemoteException {
            synchronized (mLock) {
                int index = findIndexOfSessionsListenerLocked(listener);
                if (index != -1) {
                    SessionsListenerRecord record = mSessionsListeners.remove(index);
                    try {
                        record.mListener.asBinder().unlinkToDeath(record, 0);
                    } catch (Exception e) {
                        // ignore exceptions, the record is being removed
                    }
                }
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
                if (DEBUG) {
                    Log.d(TAG, "dispatchMediaKeyEvent, pid=" + pid + ", uid=" + uid + ", event="
                            + keyEvent);
                }
                if (!isUserSetupComplete()) {
                    // Global media key handling can have the side-effect of starting new
                    // activities which is undesirable while setup is in progress.
                    Slog.i(TAG, "Not dispatching media key event because user "
                            + "setup is in progress.");
                    return;
                }
                if (isGlobalPriorityActive() && uid != Process.SYSTEM_UID) {
                    // Prevent dispatching key event through reflection while the global priority
                    // session is active.
                    Slog.i(TAG, "Only the system can dispatch media key event "
                            + "to the global priority session.");
                    return;
                }

                synchronized (mLock) {
                    // If we don't have a media button receiver to fall back on
                    // include non-playing sessions for dispatching
                    boolean useNotPlayingSessions = true;
                    for (int userId : mCurrentUserIdList) {
                        UserRecord ur = mUserRecords.get(userId);
                        if (ur.mLastMediaButtonReceiver != null
                                || ur.mRestoredMediaButtonReceiver != null) {
                            useNotPlayingSessions = false;
                            break;
                        }
                    }

                    if (DEBUG) {
                        Log.d(TAG, "dispatchMediaKeyEvent, useNotPlayingSessions="
                                + useNotPlayingSessions);
                    }
                    MediaSessionRecord session = mPriorityStack.getDefaultMediaButtonSession(
                            mCurrentUserIdList, useNotPlayingSessions);
                    if (isVoiceKey(keyEvent.getKeyCode())) {
                        handleVoiceKeyEventLocked(keyEvent, needWakeLock, session);
                    } else {
                        dispatchMediaKeyEventLocked(keyEvent, needWakeLock, session);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void dispatchAdjustVolume(int suggestedStream, int delta, int flags) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    MediaSessionRecord session = mPriorityStack
                            .getDefaultVolumeSession(mCurrentUserIdList);
                    dispatchAdjustVolumeLocked(suggestedStream, delta, flags, session);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setRemoteVolumeController(IRemoteVolumeController rvc) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                enforceSystemUiPermission("listen for volume changes", pid, uid);
                mRvc = rvc;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean isGlobalPriorityActive() {
            return mPriorityStack.isGlobalPriorityActive();
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
                pw.println(mSessionsListeners.size() + " sessions listeners.");
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
                    UserRecord user = mUserRecords.get(mUserRecords.keyAt(i));
                    user.dumpLocked(pw, "");
                }
            }
        }

        private int verifySessionsRequest(ComponentName componentName, int userId, final int pid,
                final int uid) {
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
            return resolvedUserId;
        }

        private void dispatchAdjustVolumeLocked(int suggestedStream, int direction, int flags,
                MediaSessionRecord session) {
            boolean preferSuggestedStream = false;
            if (isValidLocalStreamType(suggestedStream)
                    && AudioSystem.isStreamActive(suggestedStream, 0)) {
                preferSuggestedStream = true;
            }
            if (DEBUG) {
                Log.d(TAG, "Adjusting " + session + " by " + direction + ". flags="
                        + flags + ", suggestedStream=" + suggestedStream
                        + ", preferSuggestedStream=" + preferSuggestedStream);
            }
            if (session == null || preferSuggestedStream) {
                if ((flags & AudioManager.FLAG_ACTIVE_MEDIA_ONLY) != 0
                        && !AudioSystem.isStreamActive(AudioManager.STREAM_MUSIC, 0)) {
                    if (DEBUG) {
                        Log.d(TAG, "No active session to adjust, skipping media only volume event");
                    }
                    return;
                }
                try {
                    String packageName = getContext().getOpPackageName();
                    mAudioService.adjustSuggestedStreamVolume(direction, suggestedStream,
                            flags, packageName, TAG);
                } catch (RemoteException e) {
                    Log.e(TAG, "Error adjusting default volume.", e);
                }
            } else {
                session.adjustVolume(direction, flags, getContext().getPackageName(),
                        Process.SYSTEM_UID, true);
            }
        }

        private void handleVoiceKeyEventLocked(KeyEvent keyEvent, boolean needWakeLock,
                MediaSessionRecord session) {
            if (session != null && session.hasFlag(MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY)) {
                // If the phone app has priority just give it the event
                dispatchMediaKeyEventLocked(keyEvent, needWakeLock, session);
                return;
            }
            int action = keyEvent.getAction();
            boolean isLongPress = (keyEvent.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyEvent.getRepeatCount() == 0) {
                    mVoiceButtonDown = true;
                    mVoiceButtonHandled = false;
                } else if (mVoiceButtonDown && !mVoiceButtonHandled && isLongPress) {
                    mVoiceButtonHandled = true;
                    startVoiceInput(needWakeLock);
                }
            } else if (action == KeyEvent.ACTION_UP) {
                if (mVoiceButtonDown) {
                    mVoiceButtonDown = false;
                    if (!mVoiceButtonHandled && !keyEvent.isCanceled()) {
                        // Resend the down then send this event through
                        KeyEvent downEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_DOWN);
                        dispatchMediaKeyEventLocked(downEvent, needWakeLock, session);
                        dispatchMediaKeyEventLocked(keyEvent, needWakeLock, session);
                    }
                }
            }
        }

        private void dispatchMediaKeyEventLocked(KeyEvent keyEvent, boolean needWakeLock,
                MediaSessionRecord session) {
            if (session != null) {
                if (DEBUG_MEDIA_KEY_EVENT) {
                    Log.d(TAG, "Sending " + keyEvent + " to " + session);
                }
                if (needWakeLock) {
                    mKeyEventReceiver.aquireWakeLockLocked();
                }
                // If we don't need a wakelock use -1 as the id so we
                // won't release it later
                session.sendMediaButton(keyEvent,
                        needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1,
                        mKeyEventReceiver, Process.SYSTEM_UID,
                        getContext().getPackageName());
            } else {
                // Launch the last PendingIntent we had with priority
                for (int userId : mCurrentUserIdList) {
                    UserRecord user = mUserRecords.get(userId);
                    if (user.mLastMediaButtonReceiver == null
                            && user.mRestoredMediaButtonReceiver == null) {
                        continue;
                    }
                    if (needWakeLock) {
                        mKeyEventReceiver.aquireWakeLockLocked();
                    }
                    Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                    mediaButtonIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                    mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
                    try {
                        if (user.mLastMediaButtonReceiver != null) {
                            if (DEBUG_MEDIA_KEY_EVENT) {
                                Log.d(TAG, "Sending " + keyEvent
                                        + " to the last known pendingIntent "
                                        + user.mLastMediaButtonReceiver);
                            }
                            user.mLastMediaButtonReceiver.send(getContext(),
                                    needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1,
                                    mediaButtonIntent, mKeyEventReceiver, mHandler);
                        } else {
                            if (DEBUG_MEDIA_KEY_EVENT) {
                                Log.d(TAG, "Sending " + keyEvent + " to the restored intent "
                                        + user.mRestoredMediaButtonReceiver);
                            }
                            mediaButtonIntent.setComponent(user.mRestoredMediaButtonReceiver);
                            getContext().sendBroadcastAsUser(mediaButtonIntent,
                                    UserHandle.of(userId));
                        }
                    } catch (CanceledException e) {
                        Log.i(TAG, "Error sending key event to media button receiver "
                                + user.mLastMediaButtonReceiver, e);
                    }
                    return;
                }
                if (DEBUG) {
                    Log.d(TAG, "Sending media key ordered broadcast");
                }
                if (needWakeLock) {
                    mMediaEventWakeLock.acquire();
                }
                // Fallback to legacy behavior
                Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
                keyIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
                if (needWakeLock) {
                    keyIntent.putExtra(EXTRA_WAKELOCK_ACQUIRED, WAKELOCK_RELEASE_ON_FINISHED);
                }
                // Send broadcast only to the full user.
                getContext().sendOrderedBroadcastAsUser(keyIntent, UserHandle.CURRENT,
                        null, mKeyEventDone, mHandler, Activity.RESULT_OK, null, null);
            }
        }

        private void startVoiceInput(boolean needWakeLock) {
            Intent voiceIntent = null;
            // select which type of search to launch:
            // - screen on and device unlocked: action is ACTION_WEB_SEARCH
            // - device locked or screen off: action is
            // ACTION_VOICE_SEARCH_HANDS_FREE
            // with EXTRA_SECURE set to true if the device is securely locked
            PowerManager pm = (PowerManager) getContext().getSystemService(Context.POWER_SERVICE);
            boolean isLocked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
            if (!isLocked && pm.isScreenOn()) {
                voiceIntent = new Intent(android.speech.RecognizerIntent.ACTION_WEB_SEARCH);
                Log.i(TAG, "voice-based interactions: about to use ACTION_WEB_SEARCH");
            } else {
                voiceIntent = new Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE);
                voiceIntent.putExtra(RecognizerIntent.EXTRA_SECURE,
                        isLocked && mKeyguardManager.isKeyguardSecure());
                Log.i(TAG, "voice-based interactions: about to use ACTION_VOICE_SEARCH_HANDS_FREE");
            }
            // start the search activity
            if (needWakeLock) {
                mMediaEventWakeLock.acquire();
            }
            try {
                if (voiceIntent != null) {
                    voiceIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    if (DEBUG) Log.d(TAG, "voiceIntent: " + voiceIntent);
                    getContext().startActivityAsUser(voiceIntent, UserHandle.CURRENT);
                }
            } catch (ActivityNotFoundException e) {
                Log.w(TAG, "No activity for search: " + e);
            } finally {
                if (needWakeLock) {
                    mMediaEventWakeLock.release();
                }
            }
        }

        private boolean isVoiceKey(int keyCode) {
            return keyCode == KeyEvent.KEYCODE_HEADSETHOOK;
        }

        private boolean isUserSetupComplete() {
            return Settings.Secure.getIntForUser(getContext().getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0, UserHandle.USER_CURRENT) != 0;
        }

        // we only handle public stream types, which are 0-5
        private boolean isValidLocalStreamType(int streamType) {
            return streamType >= AudioManager.STREAM_VOICE_CALL
                    && streamType <= AudioManager.STREAM_NOTIFICATION;
        }

        private KeyEventWakeLockReceiver mKeyEventReceiver = new KeyEventWakeLockReceiver(mHandler);

        class KeyEventWakeLockReceiver extends ResultReceiver implements Runnable,
                PendingIntent.OnFinished {
            private final Handler mHandler;
            private int mRefCount = 0;
            private int mLastTimeoutId = 0;

            public KeyEventWakeLockReceiver(Handler handler) {
                super(handler);
                mHandler = handler;
            }

            public void onTimeout() {
                synchronized (mLock) {
                    if (mRefCount == 0) {
                        // We've already released it, so just return
                        return;
                    }
                    mLastTimeoutId++;
                    mRefCount = 0;
                    releaseWakeLockLocked();
                }
            }

            public void aquireWakeLockLocked() {
                if (mRefCount == 0) {
                    mMediaEventWakeLock.acquire();
                }
                mRefCount++;
                mHandler.removeCallbacks(this);
                mHandler.postDelayed(this, WAKELOCK_TIMEOUT);

            }

            @Override
            public void run() {
                onTimeout();
            }

            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode < mLastTimeoutId) {
                    // Ignore results from calls that were before the last
                    // timeout, just in case.
                    return;
                } else {
                    synchronized (mLock) {
                        if (mRefCount > 0) {
                            mRefCount--;
                            if (mRefCount == 0) {
                                releaseWakeLockLocked();
                            }
                        }
                    }
                }
            }

            private void releaseWakeLockLocked() {
                mMediaEventWakeLock.release();
                mHandler.removeCallbacks(this);
            }

            @Override
            public void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode,
                    String resultData, Bundle resultExtras) {
                onReceiveResult(resultCode, null);
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

    final class MessageHandler extends Handler {
        private static final int MSG_SESSIONS_CHANGED = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SESSIONS_CHANGED:
                    pushSessionsChanged(msg.arg1);
                    break;
            }
        }

        public void post(int what, int arg1, int arg2) {
            obtainMessage(what, arg1, arg2).sendToTarget();
        }
    }
}
