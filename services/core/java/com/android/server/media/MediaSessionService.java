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

import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.os.UserHandle.ALL;
import static android.os.UserHandle.CURRENT;

import static com.android.server.media.MediaKeyDispatcher.KEY_EVENT_LONG_PRESS;
import static com.android.server.media.MediaKeyDispatcher.isDoubleTapOverridden;
import static com.android.server.media.MediaKeyDispatcher.isLongPressOverridden;
import static com.android.server.media.MediaKeyDispatcher.isSingleTapOverridden;
import static com.android.server.media.MediaKeyDispatcher.isTripleTapOverridden;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.IRemoteSessionCallback;
import android.media.MediaCommunicationManager;
import android.media.Session2Token;
import android.media.session.IActiveSessionsListener;
import android.media.session.IOnMediaKeyEventDispatchedListener;
import android.media.session.IOnMediaKeyEventSessionChangedListener;
import android.media.session.IOnMediaKeyListener;
import android.media.session.IOnVolumeKeyLongPressListener;
import android.media.session.ISession;
import android.media.session.ISession2TokensListener;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerExemptionManager;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.speech.RecognizerIntent;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalManagerRegistry;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.Watchdog.Monitor;
import com.android.server.am.ActivityManagerLocal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService implements Monitor {
    private static final String TAG = "MediaSessionService";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // Leave log for key event always.
    static final boolean DEBUG_KEY_EVENT = true;

    private static final int WAKELOCK_TIMEOUT = 5000;
    private static final int MEDIA_KEY_LISTENER_TIMEOUT = 1000;
    private static final int SESSION_CREATION_LIMIT_PER_UID = 100;
    private static final int LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout()
            + /* Buffer for delayed delivery of key event */ 50;
    private static final int MULTI_TAP_TIMEOUT = ViewConfiguration.getMultiPressTimeout();
    /**
     * Copied from Settings.System.MEDIA_BUTTON_RECEIVER
     */
    private static final String MEDIA_BUTTON_RECEIVER = "media_button_receiver";

    private final Context mContext;
    private final SessionManagerImpl mSessionManagerImpl;
    private final MessageHandler mHandler = new MessageHandler();
    private final PowerManager.WakeLock mMediaEventWakeLock;
    private final NotificationManager mNotificationManager;
    private final Object mLock = new Object();
    private final HandlerThread mRecordThread = new HandlerThread("SessionRecordThread");
    // Keeps the full user id for each user.
    @GuardedBy("mLock")
    private final SparseIntArray mFullUserIds = new SparseIntArray();
    @GuardedBy("mLock")
    private final SparseArray<FullUserRecord> mUserRecords = new SparseArray<FullUserRecord>();
    @GuardedBy("mLock")
    private final ArrayList<SessionsListenerRecord> mSessionsListeners =
            new ArrayList<SessionsListenerRecord>();
    @GuardedBy("mLock")
    private final List<Session2TokensListenerRecord> mSession2TokensListenerRecords =
            new ArrayList<>();

    private KeyguardManager mKeyguardManager;
    private AudioManager mAudioManager;
    private boolean mHasFeatureLeanback;
    private ActivityManagerLocal mActivityManagerLocal;

    // The FullUserRecord of the current users. (i.e. The foreground user that isn't a profile)
    // It's always not null after the MediaSessionService is started.
    private FullUserRecord mCurrentFullUserRecord;
    private MediaSessionRecord mGlobalPrioritySession;
    private AudioPlayerStateMonitor mAudioPlayerStateMonitor;

    // Used to notify System UI and Settings when remote volume was changed.
    @GuardedBy("mLock")
    final RemoteCallbackList<IRemoteSessionCallback> mRemoteVolumeControllers =
            new RemoteCallbackList<>();

    private MediaSessionPolicyProvider mCustomMediaSessionPolicyProvider;
    private MediaKeyDispatcher mCustomMediaKeyDispatcher;

    private MediaCommunicationManager mCommunicationManager;
    private final MediaCommunicationManager.SessionCallback mSession2TokenCallback =
            new MediaCommunicationManager.SessionCallback() {
                @Override
                public void onSession2TokenCreated(Session2Token token) {
                    if (DEBUG) {
                        Log.d(TAG, "Session2 is created " + token);
                    }
                    MediaSession2Record record = new MediaSession2Record(token,
                            MediaSessionService.this, mRecordThread.getLooper(), 0);
                    synchronized (mLock) {
                        FullUserRecord user = getFullUserRecordLocked(record.getUserId());
                        if (user != null) {
                            user.mPriorityStack.addSession(record);
                        }
                    }
                }
            };

    public MediaSessionService(Context context) {
        super(context);
        mContext = context;
        mSessionManagerImpl = new SessionManagerImpl();
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        mMediaEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleMediaEvent");
        mNotificationManager = mContext.getSystemService(NotificationManager.class);
        mAudioManager = mContext.getSystemService(AudioManager.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
        Watchdog.getInstance().addMonitor(this);
        mKeyguardManager = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
        mAudioPlayerStateMonitor = AudioPlayerStateMonitor.getInstance(mContext);
        mAudioPlayerStateMonitor.registerListener(
                (config, isRemoved) -> {
                    if (DEBUG) {
                        Log.d(TAG, "Audio playback is changed, config=" + config
                                + ", removed=" + isRemoved);
                    }
                    if (config.getPlayerType()
                            == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                        return;
                    }
                    synchronized (mLock) {
                        FullUserRecord user = getFullUserRecordLocked(
                                UserHandle.getUserHandleForUid(config.getClientUid())
                                        .getIdentifier());
                        if (user != null) {
                            user.mPriorityStack.updateMediaButtonSessionIfNeeded();
                        }
                    }
                }, null /* handler */);
        mHasFeatureLeanback = mContext.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);

        updateUser();

        instantiateCustomProvider(mContext.getResources().getString(
                R.string.config_customMediaSessionPolicyProvider));
        instantiateCustomDispatcher(mContext.getResources().getString(
                R.string.config_customMediaKeyDispatcher));
        mRecordThread.start();

        final IntentFilter filter = new IntentFilter(
                NotificationManager.ACTION_NOTIFICATION_LISTENER_ENABLED_CHANGED);
        mContext.registerReceiver(mNotificationListenerEnabledChangedReceiver, filter);

        mActivityManagerLocal = LocalManagerRegistry.getManager(ActivityManagerLocal.class);
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        switch (phase) {
            // This ensures MediaCommunicationService is started
            case PHASE_BOOT_COMPLETED:
                mCommunicationManager = mContext.getSystemService(MediaCommunicationManager.class);
                mCommunicationManager.registerSessionCallback(new HandlerExecutor(mHandler),
                        mSession2TokenCallback);
                break;
            case PHASE_ACTIVITY_MANAGER_READY:
                MediaSessionDeviceConfig.initialize(mContext);
                break;
        }
    }

    private final BroadcastReceiver mNotificationListenerEnabledChangedReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActiveSessionListeners();
                }
    };

    private boolean isGlobalPriorityActiveLocked() {
        return mGlobalPrioritySession != null && mGlobalPrioritySession.isActive();
    }

    void onSessionActiveStateChanged(MediaSessionRecordImpl record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null) {
                Log.w(TAG, "Unknown session updated. Ignoring.");
                return;
            }
            if (record.isSystemPriority()) {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Global priority session is updated, active=" + record.isActive());
                }
                user.pushAddressedPlayerChangedLocked();
            } else {
                if (!user.mPriorityStack.contains(record)) {
                    Log.w(TAG, "Unknown session updated. Ignoring.");
                    return;
                }
                user.mPriorityStack.onSessionActiveStateChanged(record);
            }

            mHandler.postSessionsChanged(record);
        }
    }

    // Currently only media1 can become global priority session.
    void setGlobalPrioritySession(MediaSessionRecord record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (mGlobalPrioritySession != record) {
                Log.d(TAG, "Global priority session is changed from " + mGlobalPrioritySession
                        + " to " + record);
                mGlobalPrioritySession = record;
                if (user != null && user.mPriorityStack.contains(record)) {
                    // Handle the global priority session separately.
                    // Otherwise, it can be the media button session regardless of the active state
                    // because it or other system components might have been the lastly played media
                    // app.
                    user.mPriorityStack.removeSession(record);
                }
            }
        }
    }

    private List<MediaSessionRecord> getActiveSessionsLocked(int userId) {
        List<MediaSessionRecord> records = new ArrayList<>();
        if (userId == ALL.getIdentifier()) {
            int size = mUserRecords.size();
            for (int i = 0; i < size; i++) {
                records.addAll(mUserRecords.valueAt(i).mPriorityStack.getActiveSessions(userId));
            }
        } else {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "getSessions failed. Unknown user " + userId);
                return records;
            }
            records.addAll(user.mPriorityStack.getActiveSessions(userId));
        }

        // Return global priority session at the first whenever it's asked.
        if (isGlobalPriorityActiveLocked()
                && (userId == ALL.getIdentifier()
                        || userId == mGlobalPrioritySession.getUserId())) {
            records.add(0, mGlobalPrioritySession);
        }
        return records;
    }

    List<Session2Token> getSession2TokensLocked(int userId) {
        List<Session2Token> list = new ArrayList<>();
        if (userId == ALL.getIdentifier()) {
            int size = mUserRecords.size();
            for (int i = 0; i < size; i++) {
                list.addAll(mUserRecords.valueAt(i).mPriorityStack.getSession2Tokens(userId));
            }
        } else {
            FullUserRecord user = getFullUserRecordLocked(userId);
            list.addAll(user.mPriorityStack.getSession2Tokens(userId));
        }
        return list;
    }

    /**
     * Tells the System UI and Settings app that volume has changed on an active remote session.
     */
    public void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session) {
        if (!session.isActive()) {
            return;
        }
        synchronized (mLock) {
            int size = mRemoteVolumeControllers.beginBroadcast();
            MediaSession.Token token = session.getSessionToken();
            for (int i = size - 1; i >= 0; i--) {
                try {
                    IRemoteSessionCallback cb =
                            mRemoteVolumeControllers.getBroadcastItem(i);
                    cb.onVolumeChanged(token, flags);
                } catch (Exception e) {
                    Log.w(TAG, "Error sending volume change.", e);
                }
            }
            mRemoteVolumeControllers.finishBroadcast();
        }
    }

    void onSessionPlaybackStateChanged(MediaSessionRecordImpl record,
            boolean shouldUpdatePriority) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null || !user.mPriorityStack.contains(record)) {
                Log.d(TAG, "Unknown session changed playback state. Ignoring.");
                return;
            }
            user.mPriorityStack.onPlaybackStateChanged(record, shouldUpdatePriority);
        }
    }

    void onSessionPlaybackTypeChanged(MediaSessionRecord record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null || !user.mPriorityStack.contains(record)) {
                Log.d(TAG, "Unknown session changed playback type. Ignoring.");
                return;
            }
            pushRemoteVolumeUpdateLocked(record.getUserId());
        }
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (DEBUG) Log.d(TAG, "onStartUser: " + user);
        updateUser();
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (DEBUG) Log.d(TAG, "onSwitchUser: " + to);
        updateUser();
    }

    @Override
    public void onUserStopped(@NonNull TargetUser targetUser) {
        int userId = targetUser.getUserIdentifier();

        if (DEBUG) Log.d(TAG, "onCleanupUser: " + userId);
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user != null) {
                if (user.mFullUserId == userId) {
                    user.destroySessionsForUserLocked(ALL.getIdentifier());
                    mUserRecords.remove(userId);
                } else {
                    user.destroySessionsForUserLocked(userId);
                }
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
        if (mContext.checkPermission(android.Manifest.permission.MODIFY_PHONE_STATE, pid, uid)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Must hold the MODIFY_PHONE_STATE permission.");
        }
    }

    void onSessionDied(MediaSessionRecordImpl session) {
        synchronized (mLock) {
            destroySessionLocked(session);
        }
    }

    private void updateUser() {
        synchronized (mLock) {
            UserManager manager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            mFullUserIds.clear();
            List<UserHandle> allUsers = manager.getUserHandles(/*excludeDying=*/false);
            if (allUsers != null) {
                for (UserHandle user : allUsers) {
                    UserHandle parent = manager.getProfileParent(user);
                    if (parent != null) {
                        mFullUserIds.put(user.getIdentifier(), parent.getIdentifier());
                    } else {
                        mFullUserIds.put(user.getIdentifier(), user.getIdentifier());
                        if (mUserRecords.get(user.getIdentifier()) == null) {
                            mUserRecords.put(user.getIdentifier(),
                                    new FullUserRecord(user.getIdentifier()));
                        }
                    }
                }
            }
            // Ensure that the current full user exists.
            int currentFullUserId = ActivityManager.getCurrentUser();
            mCurrentFullUserRecord = mUserRecords.get(currentFullUserId);
            if (mCurrentFullUserRecord == null) {
                Log.w(TAG, "Cannot find FullUserInfo for the current user " + currentFullUserId);
                mCurrentFullUserRecord = new FullUserRecord(currentFullUserId);
                mUserRecords.put(currentFullUserId, mCurrentFullUserRecord);
            }
            mFullUserIds.put(currentFullUserId, currentFullUserId);
        }
    }

    private void updateActiveSessionListeners() {
        synchronized (mLock) {
            for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
                SessionsListenerRecord listener = mSessionsListeners.get(i);
                try {
                    enforceMediaPermissions(listener.componentName, listener.pid, listener.uid,
                            listener.userId);
                } catch (SecurityException e) {
                    Log.i(TAG, "ActiveSessionsListener " + listener.componentName
                            + " is no longer authorized. Disconnecting.");
                    mSessionsListeners.remove(i);
                    try {
                        listener.listener
                                .onActiveSessionsChanged(new ArrayList<MediaSession.Token>());
                    } catch (Exception e1) {
                        // ignore
                    }
                }
            }
        }
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
    private void destroySessionLocked(MediaSessionRecordImpl session) {
        if (DEBUG) {
            Log.d(TAG, "Destroying " + session);
        }
        if (session.isClosed()) {
            Log.w(TAG, "Destroying already destroyed session. Ignoring.");
            return;
        }

        FullUserRecord user = getFullUserRecordLocked(session.getUserId());

        if (user != null && session instanceof MediaSessionRecord) {
            final int uid = session.getUid();
            final int sessionCount = user.mUidToSessionCount.get(uid, 0);
            if (sessionCount <= 0) {
                Log.w(TAG, "destroySessionLocked: sessionCount should be positive. "
                        + "sessionCount=" + sessionCount);
            } else {
                user.mUidToSessionCount.put(uid, sessionCount - 1);
            }
        }

        if (mGlobalPrioritySession == session) {
            mGlobalPrioritySession = null;
            if (session.isActive() && user != null) {
                user.pushAddressedPlayerChangedLocked();
            }
        } else {
            if (user != null) {
                user.mPriorityStack.removeSession(session);
            }
        }

        session.close();
        mHandler.postSessionsChanged(session);
    }

    private void enforcePackageName(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        final int packageCount = packages.length;
        for (int i = 0; i < packageCount; i++) {
            if (packageName.equals(packages[i])) {
                return;
            }
        }
        throw new IllegalArgumentException("packageName is not owned by the calling process");
    }

    void tempAllowlistTargetPkgIfPossible(int targetUid, String targetPackage,
            int callingPid, int callingUid, String callingPackage, String reason) {
        final long token = Binder.clearCallingIdentity();
        try {
            enforcePackageName(callingPackage, callingUid);
            if (targetUid != callingUid && mActivityManagerLocal.canStartForegroundService(
                    callingPid, callingUid, callingPackage)) {
                final Context userContext = mContext.createContextAsUser(
                        UserHandle.of(UserHandle.getUserId(targetUid)), /* flags= */ 0);
                final PowerExemptionManager powerExemptionManager = userContext.getSystemService(
                        PowerExemptionManager.class);
                powerExemptionManager.addToTemporaryAllowList(targetPackage,
                        PowerExemptionManager.REASON_MEDIA_SESSION_CALLBACK, reason,
                        MediaSessionDeviceConfig.getMediaSessionCallbackFgsAllowlistDurationMs());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
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
        if (hasStatusBarServicePermission(pid, uid)) return;
        // TODO: Refactor to use hasMediaControlPermission and hasEnabledNotificationListener
        if (mContext
                .checkPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                != PackageManager.PERMISSION_GRANTED
                && !isEnabledNotificationListener(compName,
                UserHandle.getUserHandleForUid(uid), resolvedUserId)) {
            throw new SecurityException("Missing permission to control media.");
        }
    }

    private boolean hasStatusBarServicePermission(int pid, int uid) {
        return mContext.checkPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceStatusBarServicePermission(String action, int pid, int uid) {
        if (!hasStatusBarServicePermission(pid, uid)) {
            throw new SecurityException("Only System UI and Settings may " + action);
        }
    }

    private boolean hasMediaControlPermission(int pid, int uid) {
        // Check if it's system server or has MEDIA_CONTENT_CONTROL.
        // Note that system server doesn't have MEDIA_CONTENT_CONTROL, so we need extra
        // check here.
        if (uid == Process.SYSTEM_UID || mContext.checkPermission(
                android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        } else if (DEBUG) {
            Log.d(TAG, "uid(" + uid + ") hasn't granted MEDIA_CONTENT_CONTROL");
        }
        return false;
    }

    /**
     * This checks if the component is an enabled notification listener for the
     * specified user. Enabled components may only operate on behalf of the user
     * they're running as.
     *
     * @param compName The component that is enabled.
     * @param userHandle The user handle of the caller.
     * @param forUserId The user id they're making the request on behalf of.
     * @return True if the component is enabled, false otherwise
     */
    private boolean isEnabledNotificationListener(ComponentName compName, UserHandle userHandle,
            int forUserId) {
        if (userHandle.getIdentifier() != forUserId) {
            // You may not access another user's content as an enabled listener.
            return false;
        }
        if (DEBUG) {
            Log.d(TAG, "Checking if enabled notification listener " + compName);
        }
        if (compName != null) {
            return mNotificationManager.hasEnabledNotificationListener(compName.getPackageName(),
                    userHandle);
        }
        return false;
    }

    /*
     * When a session is created the following things need to happen.
     * 1. Its callback binder needs a link to death
     * 2. It needs to be added to all sessions.
     * 3. It needs to be added to the priority stack.
     * 4. It needs to be added to the relevant user record.
     */
    private MediaSessionRecord createSessionInternal(int callerPid, int callerUid, int userId,
            String callerPackageName, ISessionCallback cb, String tag, Bundle sessionInfo) {
        synchronized (mLock) {
            int policies = 0;
            if (mCustomMediaSessionPolicyProvider != null) {
                policies = mCustomMediaSessionPolicyProvider.getSessionPoliciesForApplication(
                        callerUid, callerPackageName);
            }

            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "Request from invalid user: " +  userId + ", pkg=" + callerPackageName);
                throw new RuntimeException("Session request from invalid user.");
            }

            final int sessionCount = user.mUidToSessionCount.get(callerUid, 0);
            if (sessionCount >= SESSION_CREATION_LIMIT_PER_UID
                    && !hasMediaControlPermission(callerPid, callerUid)) {
                throw new RuntimeException("Created too many sessions. count="
                        + sessionCount + ")");
            }

            final MediaSessionRecord session;
            try {
                session = new MediaSessionRecord(callerPid, callerUid, userId,
                        callerPackageName, cb, tag, sessionInfo, this,
                        mRecordThread.getLooper(), policies);
            } catch (RemoteException e) {
                throw new RuntimeException("Media Session owner died prematurely.", e);
            }

            user.mUidToSessionCount.put(callerUid, sessionCount + 1);

            user.mPriorityStack.addSession(session);
            mHandler.postSessionsChanged(session);

            if (DEBUG) {
                Log.d(TAG, "Created session for " + callerPackageName + " with tag " + tag);
            }
            return session;
        }
    }

    private int findIndexOfSessionsListenerLocked(IActiveSessionsListener listener) {
        for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
            if (mSessionsListeners.get(i).listener.asBinder() == listener.asBinder()) {
                return i;
            }
        }
        return -1;
    }

    private int findIndexOfSession2TokensListenerLocked(ISession2TokensListener listener) {
        for (int i = mSession2TokensListenerRecords.size() - 1; i >= 0; i--) {
            if (mSession2TokensListenerRecords.get(i).listener.asBinder() == listener.asBinder()) {
                return i;
            }
        }
        return -1;
    }

    private void pushSession1Changed(int userId) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "pushSession1ChangedOnHandler failed. No user with id=" + userId);
                return;
            }
            List<MediaSessionRecord> records = getActiveSessionsLocked(userId);
            int size = records.size();
            ArrayList<MediaSession.Token> tokens = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                tokens.add(records.get(i).getSessionToken());
            }
            pushRemoteVolumeUpdateLocked(userId);
            for (int i = mSessionsListeners.size() - 1; i >= 0; i--) {
                SessionsListenerRecord record = mSessionsListeners.get(i);
                if (record.userId == ALL.getIdentifier() || record.userId == userId) {
                    try {
                        record.listener.onActiveSessionsChanged(tokens);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Dead ActiveSessionsListener in pushSessionsChanged, removing",
                                e);
                        mSessionsListeners.remove(i);
                    }
                }
            }
        }
    }

    void pushSession2Changed(int userId) {
        synchronized (mLock) {
            List<Session2Token> allSession2Tokens = getSession2TokensLocked(ALL.getIdentifier());
            List<Session2Token> session2Tokens = getSession2TokensLocked(userId);

            for (int i = mSession2TokensListenerRecords.size() - 1; i >= 0; i--) {
                Session2TokensListenerRecord listenerRecord = mSession2TokensListenerRecords.get(i);
                try {
                    if (listenerRecord.userId == ALL.getIdentifier()) {
                        listenerRecord.listener.onSession2TokensChanged(allSession2Tokens);
                    } else if (listenerRecord.userId == userId) {
                        listenerRecord.listener.onSession2TokensChanged(session2Tokens);
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to notify Session2Token change. Removing listener.", e);
                    mSession2TokensListenerRecords.remove(i);
                }
            }
        }
    }

    private void pushRemoteVolumeUpdateLocked(int userId) {
        FullUserRecord user = getFullUserRecordLocked(userId);
        if (user == null) {
            Log.w(TAG, "pushRemoteVolumeUpdateLocked failed. No user with id=" + userId);
            return;
        }

        synchronized (mLock) {
            int size = mRemoteVolumeControllers.beginBroadcast();
            MediaSessionRecordImpl record = user.mPriorityStack.getDefaultRemoteSession(userId);
            if (record instanceof MediaSession2Record) {
                // TODO(jaewan): Implement
                return;
            }
            MediaSession.Token token = record == null
                    ? null : ((MediaSessionRecord) record).getSessionToken();

            for (int i = size - 1; i >= 0; i--) {
                try {
                    IRemoteSessionCallback cb =
                            mRemoteVolumeControllers.getBroadcastItem(i);
                    cb.onSessionChanged(token);
                } catch (Exception e) {
                    Log.w(TAG, "Error sending default remote volume.", e);
                }
            }
            mRemoteVolumeControllers.finishBroadcast();
        }
    }

    /**
     * Called when the media button receiver for the {@code record} is changed.
     *
     * @param record the media session whose media button receiver is updated.
     */
    public void onMediaButtonReceiverChanged(MediaSessionRecordImpl record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            MediaSessionRecordImpl mediaButtonSession =
                    user.mPriorityStack.getMediaButtonSession();
            if (record == mediaButtonSession) {
                user.rememberMediaButtonReceiverLocked(mediaButtonSession);
            }
        }
    }

    private String getCallingPackageName(int uid) {
        String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        return "";
    }

    private void dispatchVolumeKeyLongPressLocked(KeyEvent keyEvent) {
        if (mCurrentFullUserRecord.mOnVolumeKeyLongPressListener == null) {
            return;
        }
        try {
            mCurrentFullUserRecord.mOnVolumeKeyLongPressListener.onVolumeKeyLongPress(keyEvent);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to send " + keyEvent + " to volume key long-press listener");
        }
    }

    private FullUserRecord getFullUserRecordLocked(int userId) {
        int fullUserId = mFullUserIds.get(userId, -1);
        if (fullUserId < 0) {
            return null;
        }
        return mUserRecords.get(fullUserId);
    }

    private MediaSessionRecord getMediaSessionRecordLocked(MediaSession.Token sessionToken) {
        FullUserRecord user = getFullUserRecordLocked(
                UserHandle.getUserHandleForUid(sessionToken.getUid()).getIdentifier());
        if (user != null) {
            return user.mPriorityStack.getMediaSessionRecord(sessionToken);
        }
        return null;
    }

    private void instantiateCustomDispatcher(String componentName) {
        synchronized (mLock) {
            mCustomMediaKeyDispatcher = null;

            try {
                if (componentName != null && !TextUtils.isEmpty(componentName)) {
                    Class customDispatcherClass = Class.forName(componentName);
                    Constructor constructor =
                            customDispatcherClass.getDeclaredConstructor(Context.class);
                    mCustomMediaKeyDispatcher =
                            (MediaKeyDispatcher) constructor.newInstance(mContext);
                }
            } catch (ClassNotFoundException | InstantiationException | InvocationTargetException
                    | IllegalAccessException | NoSuchMethodException e) {
                mCustomMediaKeyDispatcher = null;
                Log.w(TAG, "Encountered problem while using reflection", e);
            }
        }
    }

    private void instantiateCustomProvider(String componentName) {
        synchronized (mLock) {
            mCustomMediaSessionPolicyProvider = null;

            try {
                if (componentName != null && !TextUtils.isEmpty(componentName)) {
                    Class customProviderClass = Class.forName(componentName);
                    Constructor constructor =
                            customProviderClass.getDeclaredConstructor(Context.class);
                    mCustomMediaSessionPolicyProvider =
                            (MediaSessionPolicyProvider) constructor.newInstance(mContext);
                }
            } catch (ClassNotFoundException | InstantiationException | InvocationTargetException
                    | IllegalAccessException | NoSuchMethodException e) {
                Log.w(TAG, "Encountered problem while using reflection", e);
            }
        }
    }

    /**
     * Information about a full user and its corresponding managed profiles.
     *
     * <p>Since the full user runs together with its managed profiles, a user wouldn't differentiate
     * them when they press a media/volume button. So keeping media sessions for them in one
     * place makes more sense and increases the readability.</p>
     * <p>The contents of this object is guarded by {@link #mLock}.
     */
    final class FullUserRecord implements MediaSessionStack.OnMediaButtonSessionChangedListener {
        private final int mFullUserId;
        private final ContentResolver mContentResolver;
        private final MediaSessionStack mPriorityStack;
        private final HashMap<IBinder, OnMediaKeyEventDispatchedListenerRecord>
                mOnMediaKeyEventDispatchedListeners = new HashMap<>();
        private final HashMap<IBinder, OnMediaKeyEventSessionChangedListenerRecord>
                mOnMediaKeyEventSessionChangedListeners = new HashMap<>();
        private final SparseIntArray mUidToSessionCount = new SparseIntArray();

        private MediaButtonReceiverHolder mLastMediaButtonReceiverHolder;

        private IOnVolumeKeyLongPressListener mOnVolumeKeyLongPressListener;
        private int mOnVolumeKeyLongPressListenerUid;

        private IOnMediaKeyListener mOnMediaKeyListener;
        private int mOnMediaKeyListenerUid;

        FullUserRecord(int fullUserId) {
            mFullUserId = fullUserId;
            mContentResolver = mContext.createContextAsUser(UserHandle.of(mFullUserId), 0)
                    .getContentResolver();
            mPriorityStack = new MediaSessionStack(mAudioPlayerStateMonitor, this);
            // Restore the remembered media button receiver before the boot.
            String mediaButtonReceiverInfo = Settings.Secure.getString(mContentResolver,
                    MEDIA_BUTTON_RECEIVER);
            mLastMediaButtonReceiverHolder =
                    MediaButtonReceiverHolder.unflattenFromString(
                            mContext, mediaButtonReceiverInfo);
        }

        public void destroySessionsForUserLocked(int userId) {
            List<MediaSessionRecord> sessions = mPriorityStack.getPriorityList(false, userId);
            for (MediaSessionRecord session : sessions) {
                destroySessionLocked(session);
            }
        }

        public void addOnMediaKeyEventDispatchedListenerLocked(
                IOnMediaKeyEventDispatchedListener listener, int uid) {
            IBinder cbBinder = listener.asBinder();
            OnMediaKeyEventDispatchedListenerRecord cr =
                    new OnMediaKeyEventDispatchedListenerRecord(listener, uid);
            mOnMediaKeyEventDispatchedListeners.put(cbBinder, cr);
            try {
                cbBinder.linkToDeath(cr, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to add listener", e);
                mOnMediaKeyEventDispatchedListeners.remove(cbBinder);
            }
        }

        public void removeOnMediaKeyEventDispatchedListenerLocked(
                IOnMediaKeyEventDispatchedListener listener) {
            IBinder cbBinder = listener.asBinder();
            OnMediaKeyEventDispatchedListenerRecord cr =
                    mOnMediaKeyEventDispatchedListeners.remove(cbBinder);
            cbBinder.unlinkToDeath(cr, 0);
        }

        public void addOnMediaKeyEventSessionChangedListenerLocked(
                IOnMediaKeyEventSessionChangedListener listener, int uid) {
            IBinder cbBinder = listener.asBinder();
            OnMediaKeyEventSessionChangedListenerRecord cr =
                    new OnMediaKeyEventSessionChangedListenerRecord(listener, uid);
            mOnMediaKeyEventSessionChangedListeners.put(cbBinder, cr);
            try {
                cbBinder.linkToDeath(cr, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to add listener", e);
                mOnMediaKeyEventSessionChangedListeners.remove(cbBinder);
            }
        }

        public void removeOnMediaKeyEventSessionChangedListener(
                IOnMediaKeyEventSessionChangedListener listener) {
            IBinder cbBinder = listener.asBinder();
            OnMediaKeyEventSessionChangedListenerRecord cr =
                    mOnMediaKeyEventSessionChangedListeners.remove(cbBinder);
            cbBinder.unlinkToDeath(cr, 0);
        }

        public void dumpLocked(PrintWriter pw, String prefix) {
            pw.print(prefix + "Record for full_user=" + mFullUserId);
            // Dump managed profile user ids associated with this user.
            int size = mFullUserIds.size();
            for (int i = 0; i < size; i++) {
                if (mFullUserIds.keyAt(i) != mFullUserIds.valueAt(i)
                        && mFullUserIds.valueAt(i) == mFullUserId) {
                    pw.print(", profile_user=" + mFullUserIds.keyAt(i));
                }
            }
            pw.println();
            String indent = prefix + "  ";
            pw.println(indent + "Volume key long-press listener: " + mOnVolumeKeyLongPressListener);
            pw.println(indent + "Volume key long-press listener package: "
                    + getCallingPackageName(mOnVolumeKeyLongPressListenerUid));
            pw.println(indent + "Media key listener: " + mOnMediaKeyListener);
            pw.println(indent + "Media key listener package: "
                    + getCallingPackageName(mOnMediaKeyListenerUid));
            pw.println(indent + "OnMediaKeyEventDispatchedListener: added "
                    + mOnMediaKeyEventDispatchedListeners.size() + " listener(s)");
            for (OnMediaKeyEventDispatchedListenerRecord cr
                    : mOnMediaKeyEventDispatchedListeners.values()) {
                pw.println(indent + "  from " + getCallingPackageName(cr.uid));
            }
            pw.println(indent + "OnMediaKeyEventSessionChangedListener: added "
                    + mOnMediaKeyEventSessionChangedListeners.size() + " listener(s)");
            for (OnMediaKeyEventSessionChangedListenerRecord cr
                    : mOnMediaKeyEventSessionChangedListeners.values()) {
                pw.println(indent + "  from " + getCallingPackageName(cr.uid));
            }
            pw.println(indent + "Last MediaButtonReceiver: " + mLastMediaButtonReceiverHolder);
            mPriorityStack.dump(pw, indent);
        }

        @Override
        public void onMediaButtonSessionChanged(MediaSessionRecordImpl oldMediaButtonSession,
                MediaSessionRecordImpl newMediaButtonSession) {
            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "Media button session is changed to " + newMediaButtonSession);
            }
            synchronized (mLock) {
                if (oldMediaButtonSession != null) {
                    mHandler.postSessionsChanged(oldMediaButtonSession);
                }
                if (newMediaButtonSession != null) {
                    rememberMediaButtonReceiverLocked(newMediaButtonSession);
                    mHandler.postSessionsChanged(newMediaButtonSession);
                }
                pushAddressedPlayerChangedLocked();
            }
        }

        // Remember media button receiver and keep it in the persistent storage.
        public void rememberMediaButtonReceiverLocked(MediaSessionRecordImpl record) {
            if (record instanceof MediaSession2Record) {
                // TODO(jaewan): Implement
                return;
            }
            MediaSessionRecord sessionRecord = (MediaSessionRecord) record;
            mLastMediaButtonReceiverHolder = sessionRecord.getMediaButtonReceiver();
            String mediaButtonReceiverInfo = (mLastMediaButtonReceiverHolder == null)
                    ? "" : mLastMediaButtonReceiverHolder.flattenToString();
            Settings.Secure.putString(mContentResolver,
                    MEDIA_BUTTON_RECEIVER,
                    mediaButtonReceiverInfo);
        }

        private void pushAddressedPlayerChangedLocked(
                IOnMediaKeyEventSessionChangedListener callback) {
            try {
                MediaSessionRecordImpl mediaButtonSession = getMediaButtonSessionLocked();
                if (mediaButtonSession != null) {
                    if (mediaButtonSession instanceof MediaSessionRecord) {
                        MediaSessionRecord session1 = (MediaSessionRecord) mediaButtonSession;
                        callback.onMediaKeyEventSessionChanged(session1.getPackageName(),
                                session1.getSessionToken());
                    } else {
                        // TODO(jaewan): Implement
                    }
                } else if (mCurrentFullUserRecord.mLastMediaButtonReceiverHolder != null) {
                    String packageName = mLastMediaButtonReceiverHolder.getPackageName();
                    callback.onMediaKeyEventSessionChanged(packageName, null);
                } else {
                    callback.onMediaKeyEventSessionChanged("", null);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to pushAddressedPlayerChangedLocked", e);
            }
        }

        private void pushAddressedPlayerChangedLocked() {
            for (OnMediaKeyEventSessionChangedListenerRecord cr
                    : mOnMediaKeyEventSessionChangedListeners.values()) {
                pushAddressedPlayerChangedLocked(cr.callback);
            }
        }

        private MediaSessionRecordImpl getMediaButtonSessionLocked() {
            return isGlobalPriorityActiveLocked()
                    ? mGlobalPrioritySession : mPriorityStack.getMediaButtonSession();
        }

        final class OnMediaKeyEventDispatchedListenerRecord implements IBinder.DeathRecipient {
            public final IOnMediaKeyEventDispatchedListener callback;
            public final int uid;

            OnMediaKeyEventDispatchedListenerRecord(IOnMediaKeyEventDispatchedListener callback,
                    int uid) {
                this.callback = callback;
                this.uid = uid;
            }

            @Override
            public void binderDied() {
                synchronized (mLock) {
                    mOnMediaKeyEventDispatchedListeners.remove(callback.asBinder());
                }
            }
        }

        final class OnMediaKeyEventSessionChangedListenerRecord implements IBinder.DeathRecipient {
            public final IOnMediaKeyEventSessionChangedListener callback;
            public final int uid;

            OnMediaKeyEventSessionChangedListenerRecord(
                    IOnMediaKeyEventSessionChangedListener callback, int uid) {
                this.callback = callback;
                this.uid = uid;
            }

            @Override
            public void binderDied() {
                synchronized (mLock) {
                    mOnMediaKeyEventSessionChangedListeners.remove(callback.asBinder());
                }
            }
        }
    }

    final class SessionsListenerRecord implements IBinder.DeathRecipient {
        public final IActiveSessionsListener listener;
        public final ComponentName componentName;
        public final int userId;
        public final int pid;
        public final int uid;

        SessionsListenerRecord(IActiveSessionsListener listener,
                ComponentName componentName,
                int userId, int pid, int uid) {
            this.listener = listener;
            this.componentName = componentName;
            this.userId = userId;
            this.pid = pid;
            this.uid = uid;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSessionsListeners.remove(this);
            }
        }
    }

    final class Session2TokensListenerRecord implements IBinder.DeathRecipient {
        public final ISession2TokensListener listener;
        public final int userId;

        Session2TokensListenerRecord(ISession2TokensListener listener,
                int userId) {
            this.listener = listener;
            this.userId = userId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSession2TokensListenerRecords.remove(this);
            }
        }
    }

    class SessionManagerImpl extends ISessionManager.Stub {
        private static final String EXTRA_WAKELOCK_ACQUIRED =
                "android.media.AudioService.WAKELOCK_ACQUIRED";
        private static final int WAKELOCK_RELEASE_ON_FINISHED = 1980; // magic number

        private KeyEventHandler mMediaKeyEventHandler =
                new KeyEventHandler(KeyEventHandler.KEY_TYPE_MEDIA);
        private KeyEventHandler mVolumeKeyEventHandler =
                new KeyEventHandler(KeyEventHandler.KEY_TYPE_VOLUME);

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            (new MediaShellCommand()).exec(this, in, out, err, args, callback,
                    resultReceiver);
        }

        @Override
        public ISession createSession(String packageName, ISessionCallback cb, String tag,
                Bundle sessionInfo, int userId) throws RemoteException {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                enforcePackageName(packageName, uid);
                int resolvedUserId = handleIncomingUser(pid, uid, userId, packageName);
                if (cb == null) {
                    throw new IllegalArgumentException("Controller callback cannot be null");
                }
                MediaSessionRecord session = createSessionInternal(
                        pid, uid, resolvedUserId, packageName, cb, tag, sessionInfo);
                if (session == null) {
                    throw new IllegalStateException("Failed to create a new session record");
                }
                ISession sessionBinder = session.getSessionBinder();
                if (sessionBinder == null) {
                    throw new IllegalStateException("Invalid session record");
                }
                return sessionBinder;
            } catch (Exception e) {
                Log.w(TAG, "Exception in creating a new session", e);
                throw e;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public List<MediaSession.Token> getSessions(ComponentName componentName, int userId) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                int resolvedUserId = verifySessionsRequest(componentName, userId, pid, uid);
                ArrayList<MediaSession.Token> tokens = new ArrayList<>();
                synchronized (mLock) {
                    List<MediaSessionRecord> records = getActiveSessionsLocked(resolvedUserId);
                    for (MediaSessionRecord record : records) {
                        tokens.add(record.getSessionToken());
                    }
                }
                return tokens;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public MediaSession.Token getMediaKeyEventSession() {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + " get the media key event session");
                }
                MediaSessionRecordImpl record;
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null) {
                        Log.w(TAG, "No matching user record to get the media key event session"
                                + ", userId=" + userId);
                        return null;
                    }
                    record = user.getMediaButtonSessionLocked();
                }
                if (record instanceof MediaSessionRecord) {
                    return ((MediaSessionRecord) record).getSessionToken();
                }
                //TODO: Handle media session 2 case
                return null;
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public String getMediaKeyEventSessionPackageName() {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + " get the media key event session package");
                }
                MediaSessionRecordImpl record;
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null) {
                        Log.w(TAG, "No matching user record to get the media key event session"
                                + " package , userId=" + userId);
                        return "";
                    }
                    record = user.getMediaButtonSessionLocked();
                    if (record instanceof MediaSessionRecord) {
                        return record.getPackageName();
                    //TODO: Handle media session 2 case
                    } else if (user.mLastMediaButtonReceiverHolder != null) {
                        return user.mLastMediaButtonReceiverHolder.getPackageName();
                    }
                }
                return "";
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
                        record.listener.asBinder().unlinkToDeath(record, 0);
                    } catch (Exception e) {
                        // ignore exceptions, the record is being removed
                    }
                }
            }
        }

        @Override
        public void addSession2TokensListener(ISession2TokensListener listener,
                int userId) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                // Check that they can make calls on behalf of the user and get the final user id.
                int resolvedUserId = handleIncomingUser(pid, uid, userId, null);
                synchronized (mLock) {
                    int index = findIndexOfSession2TokensListenerLocked(listener);
                    if (index >= 0) {
                        Log.w(TAG, "addSession2TokensListener is already added, ignoring");
                        return;
                    }
                    mSession2TokensListenerRecords.add(
                            new Session2TokensListenerRecord(listener, resolvedUserId));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeSession2TokensListener(ISession2TokensListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                synchronized (mLock) {
                    int index = findIndexOfSession2TokensListenerLocked(listener);
                    if (index >= 0) {
                        Session2TokensListenerRecord listenerRecord =
                                mSession2TokensListenerRecords.remove(index);
                        try {
                            listenerRecord.listener.asBinder().unlinkToDeath(listenerRecord, 0);
                        } catch (Exception e) {
                            // Ignore exception.
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Dispaches media key events. This is called when the foreground activity didn't handled
         * the incoming media key event.
         * <p>
         * Handles the dispatching of the media button events to one of the
         * registered listeners, or if there was none, broadcast an
         * ACTION_MEDIA_BUTTON intent to the rest of the system.
         *
         * @param packageName The caller package
         * @param asSystemService {@code true} if the event sent to the session came from the
         *          service instead of the app process. This helps sessions to distinguish between
         *          the key injection by the app and key events from the hardware devices. Should be
         *          used only when the hardware key events aren't handled by foreground activity.
         *          {@code false} otherwise to tell session about the real caller.
         * @param keyEvent a non-null KeyEvent whose key code is one of the
         *            supported media buttons
         * @param needWakeLock true if a PARTIAL_WAKE_LOCK needs to be held
         *            while this key event is dispatched.
         */
        @Override
        public void dispatchMediaKeyEvent(String packageName, boolean asSystemService,
                KeyEvent keyEvent, boolean needWakeLock) {
            if (keyEvent == null || !KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
                Log.w(TAG, "Attempted to dispatch null or non-media key event.");
                return;
            }

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                if (DEBUG) {
                    Log.d(TAG, "dispatchMediaKeyEvent, pkg=" + packageName + " pid=" + pid
                            + ", uid=" + uid + ", asSystem=" + asSystemService + ", event="
                            + keyEvent);
                }
                if (!isUserSetupComplete()) {
                    // Global media key handling can have the side-effect of starting new
                    // activities which is undesirable while setup is in progress.
                    Log.i(TAG, "Not dispatching media key event because user "
                            + "setup is in progress.");
                    return;
                }

                synchronized (mLock) {
                    boolean isGlobalPriorityActive = isGlobalPriorityActiveLocked();
                    if (isGlobalPriorityActive && uid != Process.SYSTEM_UID) {
                        // Prevent dispatching key event through reflection while the global
                        // priority session is active.
                        Log.i(TAG, "Only the system can dispatch media key event "
                                + "to the global priority session.");
                        return;
                    }
                    if (!isGlobalPriorityActive) {
                        if (mCurrentFullUserRecord.mOnMediaKeyListener != null) {
                            if (DEBUG_KEY_EVENT) {
                                Log.d(TAG, "Send " + keyEvent + " to the media key listener");
                            }
                            try {
                                mCurrentFullUserRecord.mOnMediaKeyListener.onMediaKey(keyEvent,
                                        new MediaKeyListenerResultReceiver(packageName, pid, uid,
                                                asSystemService, keyEvent, needWakeLock));
                                return;
                            } catch (RemoteException e) {
                                Log.w(TAG, "Failed to send " + keyEvent
                                        + " to the media key listener");
                            }
                        }
                    }
                    if (isGlobalPriorityActive) {
                        dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService,
                                keyEvent, needWakeLock);
                    } else {
                        mMediaKeyEventHandler.handleMediaKeyEventLocked(packageName, pid, uid,
                                asSystemService, keyEvent, needWakeLock);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Dispatches media key events to session as system service. This is used only when the
         * foreground activity has set
         * {@link android.app.Activity#setMediaController(MediaController)} and a media key was
         * pressed.
         *
         * @param packageName The caller's package name, obtained by Context#getPackageName()
         * @param sessionToken token for the session that the controller is pointing to
         * @param keyEvent media key event
         * @see #dispatchVolumeKeyEvent
         */
        @Override
        public boolean dispatchMediaKeyEventToSessionAsSystemService(String packageName,
                KeyEvent keyEvent, MediaSession.Token sessionToken) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    MediaSessionRecord record = getMediaSessionRecordLocked(sessionToken);
                    if (DEBUG_KEY_EVENT) {
                        Log.d(TAG, "dispatchMediaKeyEventToSessionAsSystemService, pkg="
                                + packageName + ", pid=" + pid + ", uid=" + uid + ", sessionToken="
                                + sessionToken + ", event=" + keyEvent + ", session=" + record);
                    }
                    if (record == null) {
                        Log.w(TAG, "Failed to find session to dispatch key event.");
                        return false;
                    }
                    return record.sendMediaButton(packageName, pid, uid, true /* asSystemService */,
                            keyEvent, 0, null);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void addOnMediaKeyEventDispatchedListener(
                final IOnMediaKeyEventDispatchedListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + "  add MediaKeyEventDispatchedListener");
                }
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can add the listener"
                                + ", userId=" + userId);
                        return;
                    }
                    user.addOnMediaKeyEventDispatchedListenerLocked(listener, uid);
                    Log.d(TAG, "The MediaKeyEventDispatchedListener (" + listener.asBinder()
                            + ") is added by " + getCallingPackageName(uid));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeOnMediaKeyEventDispatchedListener(
                final IOnMediaKeyEventDispatchedListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + "  remove MediaKeyEventDispatchedListener");
                }
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can remove the listener"
                                + ", userId=" + userId);
                        return;
                    }
                    user.removeOnMediaKeyEventDispatchedListenerLocked(listener);
                    Log.d(TAG, "The MediaKeyEventDispatchedListener (" + listener.asBinder()
                            + ") is removed by " + getCallingPackageName(uid));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void addOnMediaKeyEventSessionChangedListener(
                final IOnMediaKeyEventSessionChangedListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + "  add MediaKeyEventSessionChangedListener");
                }
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can add the listener"
                                + ", userId=" + userId);
                        return;
                    }
                    user.addOnMediaKeyEventSessionChangedListenerLocked(listener, uid);
                    Log.d(TAG, "The MediaKeyEventSessionChangedListener (" + listener.asBinder()
                            + ") is added by " + getCallingPackageName(uid));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void removeOnMediaKeyEventSessionChangedListener(
                final IOnMediaKeyEventSessionChangedListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!hasMediaControlPermission(pid, uid)) {
                    throw new SecurityException("MEDIA_CONTENT_CONTROL permission is required to"
                            + "  remove MediaKeyEventSessionChangedListener");
                }
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can remove the listener"
                                + ", userId=" + userId);
                        return;
                    }
                    user.removeOnMediaKeyEventSessionChangedListener(listener);
                    Log.d(TAG, "The MediaKeyEventSessionChangedListener (" + listener.asBinder()
                            + ") is removed by " + getCallingPackageName(uid));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setOnVolumeKeyLongPressListener(IOnVolumeKeyLongPressListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                // Enforce SET_VOLUME_KEY_LONG_PRESS_LISTENER permission.
                if (mContext.checkPermission(
                        android.Manifest.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER, pid, uid)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Must hold the SET_VOLUME_KEY_LONG_PRESS_LISTENER"
                            + " permission.");
                }

                synchronized (mLock) {
                    int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can set the volume key long-press listener"
                                + ", userId=" + userId);
                        return;
                    }
                    if (user.mOnVolumeKeyLongPressListener != null
                            && user.mOnVolumeKeyLongPressListenerUid != uid) {
                        Log.w(TAG, "The volume key long-press listener cannot be reset"
                                + " by another app , mOnVolumeKeyLongPressListener="
                                + user.mOnVolumeKeyLongPressListenerUid
                                + ", uid=" + uid);
                        return;
                    }

                    user.mOnVolumeKeyLongPressListener = listener;
                    user.mOnVolumeKeyLongPressListenerUid = uid;

                    Log.d(TAG, "The volume key long-press listener "
                            + listener + " is set by " + getCallingPackageName(uid));

                    if (user.mOnVolumeKeyLongPressListener != null) {
                        try {
                            user.mOnVolumeKeyLongPressListener.asBinder().linkToDeath(
                                    new IBinder.DeathRecipient() {
                                        @Override
                                        public void binderDied() {
                                            synchronized (mLock) {
                                                user.mOnVolumeKeyLongPressListener = null;
                                            }
                                        }
                                    }, 0);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to set death recipient "
                                    + user.mOnVolumeKeyLongPressListener);
                            user.mOnVolumeKeyLongPressListener = null;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setOnMediaKeyListener(IOnMediaKeyListener listener) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                // Enforce SET_MEDIA_KEY_LISTENER permission.
                if (mContext.checkPermission(
                        android.Manifest.permission.SET_MEDIA_KEY_LISTENER, pid, uid)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Must hold the SET_MEDIA_KEY_LISTENER permission.");
                }

                synchronized (mLock) {
                    int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can set the media key listener"
                                + ", userId=" + userId);
                        return;
                    }
                    if (user.mOnMediaKeyListener != null && user.mOnMediaKeyListenerUid != uid) {
                        Log.w(TAG, "The media key listener cannot be reset by another app. "
                                + ", mOnMediaKeyListenerUid=" + user.mOnMediaKeyListenerUid
                                + ", uid=" + uid);
                        return;
                    }

                    user.mOnMediaKeyListener = listener;
                    user.mOnMediaKeyListenerUid = uid;

                    Log.d(TAG, "The media key listener " + user.mOnMediaKeyListener
                            + " is set by " + getCallingPackageName(uid));

                    if (user.mOnMediaKeyListener != null) {
                        try {
                            user.mOnMediaKeyListener.asBinder().linkToDeath(
                                    new IBinder.DeathRecipient() {
                                        @Override
                                        public void binderDied() {
                                            synchronized (mLock) {
                                                user.mOnMediaKeyListener = null;
                                            }
                                        }
                                    }, 0);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed to set death recipient " + user.mOnMediaKeyListener);
                            user.mOnMediaKeyListener = null;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Dispatches volume key events. This is called when the foreground activity didn't handle
         * the incoming volume key event.
         * <p>
         * Handles the dispatching of the volume button events to one of the
         * registered listeners. If there's a volume key long-press listener and
         * there's no active global priority session, long-presses will be sent to the
         * long-press listener instead of adjusting volume.
         *
         * @param packageName The caller's package name, obtained by Context#getPackageName()
         * @param opPackageName The caller's op package name, obtained by Context#getOpPackageName()
         * @param asSystemService {@code true} if the event sent to the session as if it was come
         *          from the system service instead of the app process. This helps sessions to
         *          distinguish between the key injection by the app and key events from the
         *          hardware devices. Should be used only when the volume key events aren't handled
         *          by foreground activity. {@code false} otherwise to tell session about the real
         *          caller.
         * @param keyEvent a non-null KeyEvent whose key code is one of the
         *            {@link KeyEvent#KEYCODE_VOLUME_UP},
         *            {@link KeyEvent#KEYCODE_VOLUME_DOWN},
         *            or {@link KeyEvent#KEYCODE_VOLUME_MUTE}.
         * @param stream stream type to adjust volume.
         * @param musicOnly true if both UI and haptic feedback aren't needed when adjusting volume.
         * @see #dispatchVolumeKeyEventToSessionAsSystemService
         */
        @Override
        public void dispatchVolumeKeyEvent(String packageName, String opPackageName,
                boolean asSystemService, KeyEvent keyEvent, int stream, boolean musicOnly) {
            if (keyEvent == null
                    || (keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                    && keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                    && keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE)) {
                Log.w(TAG, "Attempted to dispatch null or non-volume key event.");
                return;
            }

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "dispatchVolumeKeyEvent, pkg=" + packageName
                        + ", opPkg=" + opPackageName + ", pid=" + pid + ", uid=" + uid
                        + ", asSystem=" + asSystemService + ", event=" + keyEvent
                        + ", stream=" + stream + ", musicOnly=" + musicOnly);
            }

            try {
                synchronized (mLock) {
                    if (isGlobalPriorityActiveLocked()) {
                        dispatchVolumeKeyEventLocked(packageName, opPackageName, pid, uid,
                                asSystemService, keyEvent, stream, musicOnly);
                    } else {
                        // TODO: Consider the case when both volume up and down keys are pressed
                        //       at the same time.
                        mVolumeKeyEventHandler.handleVolumeKeyEventLocked(packageName, pid, uid,
                                asSystemService, keyEvent, opPackageName, stream, musicOnly);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void dispatchVolumeKeyEventLocked(String packageName, String opPackageName, int pid,
                int uid, boolean asSystemService, KeyEvent keyEvent, int stream,
                boolean musicOnly) {
            boolean down = keyEvent.getAction() == KeyEvent.ACTION_DOWN;
            boolean up = keyEvent.getAction() == KeyEvent.ACTION_UP;
            int direction = 0;
            boolean isMute = false;
            switch (keyEvent.getKeyCode()) {
                case KeyEvent.KEYCODE_VOLUME_UP:
                    direction = AudioManager.ADJUST_RAISE;
                    break;
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                    direction = AudioManager.ADJUST_LOWER;
                    break;
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                    isMute = true;
                    break;
            }
            if (down || up) {
                int flags = AudioManager.FLAG_FROM_KEY;
                if (!musicOnly) {
                    // These flags are consistent with the home screen
                    if (up) {
                        flags |= AudioManager.FLAG_PLAY_SOUND | AudioManager.FLAG_VIBRATE;
                    } else {
                        flags |= AudioManager.FLAG_SHOW_UI | AudioManager.FLAG_VIBRATE;
                    }
                }
                if (direction != 0) {
                    // If this is action up we want to send a beep for non-music events
                    if (up) {
                        direction = 0;
                    }
                    dispatchAdjustVolumeLocked(packageName, opPackageName, pid, uid,
                            asSystemService, stream, direction, flags, musicOnly);
                } else if (isMute) {
                    if (down && keyEvent.getRepeatCount() == 0) {
                        dispatchAdjustVolumeLocked(packageName, opPackageName, pid, uid,
                                asSystemService, stream, AudioManager.ADJUST_TOGGLE_MUTE, flags,
                                musicOnly);
                    }
                }
            }
        }

        /**
         * Dispatches volume key events to session as system service. This is used only when the
         * foreground activity has set
         * {@link android.app.Activity#setMediaController(MediaController)} and a hardware volume
         * key was pressed.
         *
         * @param packageName The caller's package name, obtained by Context#getPackageName()
         * @param opPackageName The caller's op package name, obtained by Context#getOpPackageName()
         * @param sessionToken token for the session that the controller is pointing to
         * @param keyEvent volume key event
         * @see #dispatchVolumeKeyEvent
         */
        @Override
        public void dispatchVolumeKeyEventToSessionAsSystemService(String packageName,
                String opPackageName, KeyEvent keyEvent, MediaSession.Token sessionToken) {
            int pid = Binder.getCallingPid();
            int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    MediaSessionRecord record = getMediaSessionRecordLocked(sessionToken);
                    if (DEBUG_KEY_EVENT) {
                        Log.d(TAG, "dispatchVolumeKeyEventToSessionAsSystemService, pkg="
                                + packageName + ", opPkg=" + opPackageName + ", pid=" + pid
                                + ", uid=" + uid + ", sessionToken=" + sessionToken + ", event="
                                + keyEvent + ", session=" + record);
                    }
                    if (record == null) {
                        Log.w(TAG, "Failed to find session to dispatch key event, token="
                                + sessionToken + ". Fallbacks to the default handling.");
                        dispatchVolumeKeyEventLocked(packageName, opPackageName, pid, uid, true,
                                keyEvent, AudioManager.USE_DEFAULT_STREAM_TYPE, false);
                        return;
                    }
                    switch (keyEvent.getAction()) {
                        case KeyEvent.ACTION_DOWN: {
                            int direction = 0;
                            switch (keyEvent.getKeyCode()) {
                                case KeyEvent.KEYCODE_VOLUME_UP:
                                    direction = AudioManager.ADJUST_RAISE;
                                    break;
                                case KeyEvent.KEYCODE_VOLUME_DOWN:
                                    direction = AudioManager.ADJUST_LOWER;
                                    break;
                                case KeyEvent.KEYCODE_VOLUME_MUTE:
                                    direction = AudioManager.ADJUST_TOGGLE_MUTE;
                                    break;
                            }
                            record.adjustVolume(packageName, opPackageName, pid, uid,
                                    true /* asSystemService */, direction,
                                    AudioManager.FLAG_SHOW_UI, false /* useSuggested */);
                            break;
                        }

                        case KeyEvent.ACTION_UP: {
                            final int flags =
                                    AudioManager.FLAG_PLAY_SOUND | AudioManager.FLAG_VIBRATE
                                            | AudioManager.FLAG_FROM_KEY;
                            record.adjustVolume(packageName, opPackageName, pid, uid,
                                    true /* asSystemService */, 0, flags, false /* useSuggested */);
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void dispatchAdjustVolume(String packageName, String opPackageName,
                int suggestedStream, int delta, int flags) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    dispatchAdjustVolumeLocked(packageName, opPackageName, pid, uid, false,
                            suggestedStream, delta, flags, false);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void registerRemoteSessionCallback(IRemoteSessionCallback rvc) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            synchronized (mLock) {
                try {
                    enforceStatusBarServicePermission("listen for volume changes", pid, uid);
                    mRemoteVolumeControllers.register(rvc);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public void unregisterRemoteSessionCallback(IRemoteSessionCallback rvc) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            synchronized (mLock) {
                try {
                    enforceStatusBarServicePermission("listen for volume changes", pid, uid);
                    mRemoteVolumeControllers.unregister(rvc);
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        @Override
        public boolean isGlobalPriorityActive() {
            synchronized (mLock) {
                return isGlobalPriorityActiveLocked();
            }
        }

        @Override
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!MediaServerUtils.checkDumpPermission(mContext, TAG, pw)) return;

            pw.println("MEDIA SESSION SERVICE (dumpsys media_session)");
            pw.println();

            synchronized (mLock) {
                pw.println(mSessionsListeners.size() + " sessions listeners.");
                pw.println("Global priority session is " + mGlobalPrioritySession);
                if (mGlobalPrioritySession != null) {
                    mGlobalPrioritySession.dump(pw, "  ");
                }
                pw.println("User Records:");
                int count = mUserRecords.size();
                for (int i = 0; i < count; i++) {
                    mUserRecords.valueAt(i).dumpLocked(pw, "");
                }
                mAudioPlayerStateMonitor.dump(mContext, pw, "");
            }
            MediaSessionDeviceConfig.dump(pw, "");
        }

        /**
         * Returns if the controller's package is trusted (i.e. has either MEDIA_CONTENT_CONTROL
         * permission or an enabled notification listener)
         *
         * @param controllerPackageName package name of the controller app
         * @param controllerPid pid of the controller app
         * @param controllerUid uid of the controller app
         */
        @Override
        public boolean isTrusted(String controllerPackageName, int controllerPid,
                int controllerUid) {
            final int uid = Binder.getCallingUid();
            final int userId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            final long token = Binder.clearCallingIdentity();
            try {
                // Don't perform check between controllerPackageName and controllerUid.
                // When an (activity|service) runs on the another apps process by specifying
                // android:process in the AndroidManifest.xml, then PID and UID would have the
                // running process' information instead of the (activity|service) that has created
                // MediaController.
                // Note that we can use Context#getOpPackageName() instead of
                // Context#getPackageName() for getting package name that matches with the PID/UID,
                // but it doesn't tell which package has created the MediaController, so useless.
                return hasMediaControlPermission(controllerPid, controllerUid)
                        || hasEnabledNotificationListener(
                                userId, controllerPackageName, controllerUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setCustomMediaKeyDispatcher(String name) {
            instantiateCustomDispatcher(name);
        }

        @Override
        public void setCustomMediaSessionPolicyProvider(String name) {
            instantiateCustomProvider(name);
        }

        @Override
        public boolean hasCustomMediaKeyDispatcher(String componentName) {
            return mCustomMediaKeyDispatcher == null ? false
                    : TextUtils.equals(componentName,
                            mCustomMediaKeyDispatcher.getClass().getName());
        }

        @Override
        public boolean hasCustomMediaSessionPolicyProvider(String componentName) {
            return mCustomMediaSessionPolicyProvider == null ? false
                    : TextUtils.equals(componentName,
                            mCustomMediaSessionPolicyProvider.getClass().getName());
        }

        @Override
        public int getSessionPolicies(MediaSession.Token token) {
            synchronized (mLock) {
                MediaSessionRecord record = getMediaSessionRecordLocked(token);
                if (record != null) {
                    return record.getSessionPolicies();
                }
            }
            return 0;
        }

        @Override
        public void setSessionPolicies(MediaSession.Token token, int policies) {
            final long callingIdentityToken = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    MediaSessionRecord record = getMediaSessionRecordLocked(token);
                    FullUserRecord user = getFullUserRecordLocked(record.getUserId());
                    if (record != null && user != null) {
                        record.setSessionPolicies(policies);
                        user.mPriorityStack.updateMediaButtonSessionBySessionPolicyChange(record);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(callingIdentityToken);
            }
        }

        // For MediaSession
        private int verifySessionsRequest(ComponentName componentName, int userId, final int pid,
                final int uid) {
            String packageName = null;
            if (componentName != null) {
                // If they gave us a component name verify they own the
                // package
                packageName = componentName.getPackageName();
                enforcePackageName(packageName, uid);
            }
            // Check that they can make calls on behalf of the user and get the final user id
            int resolvedUserId = handleIncomingUser(pid, uid, userId, packageName);
            // Check if they have the permissions or their component is enabled for the user
            // they're calling from.
            enforceMediaPermissions(componentName, pid, uid, resolvedUserId);
            return resolvedUserId;
        }

        // Handles incoming user by checking whether the caller has permission to access the
        // given user id's information or not. Permission is not necessary if the given user id is
        // equal to the caller's user id, but if not, the caller needs to have the
        // INTERACT_ACROSS_USERS_FULL permission. Otherwise, a security exception will be thrown.
        // The return value will be the given user id, unless the given user id is
        // UserHandle.CURRENT, which will return the ActivityManager.getCurrentUser() value instead.
        private int handleIncomingUser(int pid, int uid, int userId, String packageName) {
            int callingUserId = UserHandle.getUserHandleForUid(uid).getIdentifier();
            if (userId == callingUserId) {
                return userId;
            }

            boolean canInteractAcrossUsersFull = mContext.checkPermission(
                    INTERACT_ACROSS_USERS_FULL, pid, uid) == PackageManager.PERMISSION_GRANTED;
            if (canInteractAcrossUsersFull) {
                if (userId == CURRENT.getIdentifier()) {
                    return ActivityManager.getCurrentUser();
                }
                return userId;
            }

            throw new SecurityException("Permission denied while calling from " + packageName
                    + " with user id: " + userId + "; Need to run as either the calling user id ("
                    + callingUserId + "), or with " + INTERACT_ACROSS_USERS_FULL + " permission");
        }

        private boolean hasEnabledNotificationListener(int callingUserId,
                String controllerPackageName, int controllerUid) {
            int controllerUserId = UserHandle.getUserHandleForUid(controllerUid).getIdentifier();
            if (callingUserId != controllerUserId) {
                // Enabled notification listener only works within the same user.
                return false;
            }

            if (mNotificationManager.hasEnabledNotificationListener(controllerPackageName,
                    UserHandle.getUserHandleForUid(controllerUid))) {
                return true;
            }
            if (DEBUG) {
                Log.d(TAG, controllerPackageName + " (uid=" + controllerUid
                        + ") doesn't have an enabled notification listener");
            }
            return false;
        }

        private void dispatchAdjustVolumeLocked(String packageName, String opPackageName, int pid,
                int uid, boolean asSystemService, int suggestedStream, int direction, int flags,
                boolean musicOnly) {
            MediaSessionRecordImpl session = isGlobalPriorityActiveLocked() ? mGlobalPrioritySession
                    : mCurrentFullUserRecord.mPriorityStack.getDefaultVolumeSession();

            boolean preferSuggestedStream = false;
            if (isValidLocalStreamType(suggestedStream)
                    && MediaServerUtils.isStreamActive(mAudioManager, suggestedStream)) {
                preferSuggestedStream = true;
            }
            if (session == null || preferSuggestedStream) {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Adjusting suggestedStream=" + suggestedStream + " by " + direction
                            + ". flags=" + flags + ", preferSuggestedStream="
                            + preferSuggestedStream + ", session=" + session);
                }
                if (musicOnly && !MediaServerUtils.isStreamActive(mAudioManager,
                        AudioManager.STREAM_MUSIC)) {
                    if (DEBUG_KEY_EVENT) {
                        Log.d(TAG, "Nothing is playing on the music stream. Skipping volume event,"
                                + " flags=" + flags);
                    }
                    return;
                }

                // Execute mAudioService.adjustSuggestedStreamVolume() on
                // handler thread of MediaSessionService.
                // This will release the MediaSessionService.mLock sooner and avoid
                // a potential deadlock between MediaSessionService.mLock and
                // ActivityManagerService lock.
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        final String callingOpPackageName;
                        final int callingUid;
                        final int callingPid;
                        if (asSystemService) {
                            callingOpPackageName = mContext.getOpPackageName();
                            callingUid = Process.myUid();
                            callingPid = Process.myPid();
                        } else {
                            callingOpPackageName = opPackageName;
                            callingUid = uid;
                            callingPid = pid;
                        }
                        try {
                            mAudioManager.adjustSuggestedStreamVolumeForUid(suggestedStream,
                                    direction, flags, callingOpPackageName, callingUid, callingPid,
                                    getContext().getApplicationInfo().targetSdkVersion);
                        } catch (SecurityException | IllegalArgumentException e) {
                            Log.e(TAG, "Cannot adjust volume: direction=" + direction
                                    + ", suggestedStream=" + suggestedStream + ", flags=" + flags
                                    + ", packageName=" + packageName + ", uid=" + uid
                                    + ", asSystemService=" + asSystemService, e);
                        }
                    }
                });
            } else {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Adjusting " + session + " by " + direction + ". flags="
                            + flags + ", suggestedStream=" + suggestedStream
                            + ", preferSuggestedStream=" + preferSuggestedStream);
                }
                session.adjustVolume(packageName, opPackageName, pid, uid, asSystemService,
                        direction, flags, true);
            }
        }

        private void dispatchMediaKeyEventLocked(String packageName, int pid, int uid,
                boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
            if (mCurrentFullUserRecord.getMediaButtonSessionLocked()
                    instanceof MediaSession2Record) {
                // TODO(jaewan): Make MediaSession2 to receive media key event
                return;
            }
            MediaSessionRecord session = null;
            MediaButtonReceiverHolder mediaButtonReceiverHolder = null;

            if (mCustomMediaKeyDispatcher != null) {
                MediaSession.Token token = mCustomMediaKeyDispatcher.getMediaSession(
                        keyEvent, uid, asSystemService);
                if (token != null) {
                    session = getMediaSessionRecordLocked(token);
                }

                if (session == null) {
                    PendingIntent pi = mCustomMediaKeyDispatcher.getMediaButtonReceiver(keyEvent,
                            uid, asSystemService);
                    if (pi != null) {
                        mediaButtonReceiverHolder = MediaButtonReceiverHolder.create(mContext,
                                mCurrentFullUserRecord.mFullUserId, pi,
                                /* sessionPackageName= */ "");
                    }
                }
            }

            if (session == null && mediaButtonReceiverHolder == null) {
                session = (MediaSessionRecord) mCurrentFullUserRecord.getMediaButtonSessionLocked();

                if (session == null) {
                    mediaButtonReceiverHolder =
                            mCurrentFullUserRecord.mLastMediaButtonReceiverHolder;
                }
            }

            if (session != null) {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Sending " + keyEvent + " to " + session);
                }
                if (needWakeLock) {
                    mKeyEventReceiver.acquireWakeLockLocked();
                }
                // If we don't need a wakelock use -1 as the id so we won't release it later.
                session.sendMediaButton(packageName, pid, uid, asSystemService, keyEvent,
                        needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1,
                        mKeyEventReceiver);
                try {
                    for (FullUserRecord.OnMediaKeyEventDispatchedListenerRecord cr
                            : mCurrentFullUserRecord.mOnMediaKeyEventDispatchedListeners.values()) {
                        cr.callback.onMediaKeyEventDispatched(
                                keyEvent, session.getPackageName(), session.getSessionToken());
                    }
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to send callback", e);
                }
            } else if (mediaButtonReceiverHolder != null) {
                if (needWakeLock) {
                    mKeyEventReceiver.acquireWakeLockLocked();
                }
                String callingPackageName =
                        (asSystemService) ? mContext.getPackageName() : packageName;
                boolean sent = mediaButtonReceiverHolder.send(
                        mContext, keyEvent, callingPackageName,
                        needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1, mKeyEventReceiver,
                        mHandler,
                        MediaSessionDeviceConfig.getMediaButtonReceiverFgsAllowlistDurationMs());
                if (sent) {
                    String pkgName = mediaButtonReceiverHolder.getPackageName();
                    for (FullUserRecord.OnMediaKeyEventDispatchedListenerRecord cr
                            : mCurrentFullUserRecord
                            .mOnMediaKeyEventDispatchedListeners.values()) {
                        try {
                            cr.callback.onMediaKeyEventDispatched(keyEvent, pkgName, null);
                        } catch (RemoteException e) {
                            Log.w(TAG, "Failed notify key event dispatch, uid=" + cr.uid, e);
                        }
                    }
                }
            }
        }

        private void startVoiceInput(boolean needWakeLock) {
            Intent voiceIntent = null;
            // select which type of search to launch:
            // - screen on and device unlocked: action is ACTION_WEB_SEARCH
            // - device locked or screen off: action is
            // ACTION_VOICE_SEARCH_HANDS_FREE
            // with EXTRA_SECURE set to true if the device is securely locked
            PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
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
                    mContext.startActivityAsUser(voiceIntent, UserHandle.CURRENT);
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
            return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    || (!mHasFeatureLeanback && keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        }

        private boolean isUserSetupComplete() {
            return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                    Settings.Secure.USER_SETUP_COMPLETE, 0, CURRENT.getIdentifier()) != 0;
        }

        // we only handle public stream types, which are 0-5
        private boolean isValidLocalStreamType(int streamType) {
            return streamType >= AudioManager.STREAM_VOICE_CALL
                    && streamType <= AudioManager.STREAM_NOTIFICATION;
        }

        private class MediaKeyListenerResultReceiver extends ResultReceiver implements Runnable {
            private final String mPackageName;
            private final int mPid;
            private final int mUid;
            private final boolean mAsSystemService;
            private final KeyEvent mKeyEvent;
            private final boolean mNeedWakeLock;
            private boolean mHandled;

            private MediaKeyListenerResultReceiver(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
                super(mHandler);
                mHandler.postDelayed(this, MEDIA_KEY_LISTENER_TIMEOUT);
                mPackageName = packageName;
                mPid = pid;
                mUid = uid;
                mAsSystemService = asSystemService;
                mKeyEvent = keyEvent;
                mNeedWakeLock = needWakeLock;
            }

            @Override
            public void run() {
                Log.d(TAG, "The media key listener is timed-out for " + mKeyEvent);
                dispatchMediaKeyEvent();
            }

            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == MediaSessionManager.RESULT_MEDIA_KEY_HANDLED) {
                    mHandled = true;
                    mHandler.removeCallbacks(this);
                    return;
                }
                dispatchMediaKeyEvent();
            }

            private void dispatchMediaKeyEvent() {
                if (mHandled) {
                    return;
                }
                mHandled = true;
                mHandler.removeCallbacks(this);
                synchronized (mLock) {
                    if (isGlobalPriorityActiveLocked()) {
                        dispatchMediaKeyEventLocked(mPackageName, mPid, mUid, mAsSystemService,
                                mKeyEvent, mNeedWakeLock);
                    } else {
                        mMediaKeyEventHandler.handleMediaKeyEventLocked(mPackageName, mPid, mUid,
                                mAsSystemService, mKeyEvent, mNeedWakeLock);
                    }
                }
            }
        }

        private KeyEventWakeLockReceiver mKeyEventReceiver = new KeyEventWakeLockReceiver(mHandler);

        class KeyEventWakeLockReceiver extends ResultReceiver implements Runnable,
                PendingIntent.OnFinished {
            private final Handler mHandler;
            private int mRefCount = 0;
            private int mLastTimeoutId = 0;

            KeyEventWakeLockReceiver(Handler handler) {
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

            public void acquireWakeLockLocked() {
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

        // A long press is determined by:
        // 1) A KeyEvent.ACTION_DOWN KeyEvent and repeat count of 0, followed by
        // 2) A KeyEvent.ACTION_DOWN KeyEvent with the same key code, a repeat count of 1, and
        //    FLAG_LONG_PRESS received within ViewConfiguration.getLongPressTimeout().
        // A tap is determined by:
        // 1) A KeyEvent.ACTION_DOWN KeyEvent followed by
        // 2) A KeyEvent.ACTION_UP KeyEvent with the same key code.
        class KeyEventHandler {
            private static final int KEY_TYPE_MEDIA = 0;
            private static final int KEY_TYPE_VOLUME = 1;

            private KeyEvent mTrackingFirstDownKeyEvent;
            private boolean mIsLongPressing;
            private Runnable mLongPressTimeoutRunnable;
            private int mMultiTapCount;
            private Runnable mMultiTapTimeoutRunnable;
            private int mMultiTapKeyCode;
            private int mKeyType;

            KeyEventHandler(int keyType) {
                mKeyType = keyType;
            }

            void handleMediaKeyEventLocked(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock) {
                handleKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, needWakeLock,
                        null, 0, false);
            }

            void handleVolumeKeyEventLocked(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, String opPackageName, int stream,
                    boolean musicOnly) {
                handleKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent, false,
                        opPackageName, stream, musicOnly);
            }

            void handleKeyEventLocked(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock,
                    String opPackageName, int stream, boolean musicOnly) {
                if (keyEvent.isCanceled()) {
                    return;
                }

                int overriddenKeyEvents = 0;
                if (mCustomMediaKeyDispatcher != null
                        && mCustomMediaKeyDispatcher.getOverriddenKeyEvents() != null) {
                    overriddenKeyEvents = mCustomMediaKeyDispatcher.getOverriddenKeyEvents()
                            .get(keyEvent.getKeyCode());
                }
                cancelTrackingIfNeeded(packageName, pid, uid, asSystemService, keyEvent,
                        needWakeLock, opPackageName, stream, musicOnly, overriddenKeyEvents);
                if (!needTracking(keyEvent, overriddenKeyEvents)) {
                    if (mKeyType == KEY_TYPE_VOLUME) {
                        dispatchVolumeKeyEventLocked(packageName, opPackageName, pid, uid,
                                asSystemService, keyEvent, stream, musicOnly);
                    } else {
                        dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService,
                                keyEvent, needWakeLock);
                    }
                    return;
                }

                if (isFirstDownKeyEvent(keyEvent)) {
                    mTrackingFirstDownKeyEvent = keyEvent;
                    mIsLongPressing = false;
                    return;
                }

                // Long press is always overridden here, otherwise the key event would have been
                // already handled
                if (isFirstLongPressKeyEvent(keyEvent)) {
                    mIsLongPressing = true;
                }
                if (mIsLongPressing) {
                    handleLongPressLocked(keyEvent, needWakeLock, overriddenKeyEvents);
                    return;
                }

                if (keyEvent.getAction() == KeyEvent.ACTION_UP) {
                    mTrackingFirstDownKeyEvent = null;
                    if (shouldTrackForMultipleTapsLocked(overriddenKeyEvents)) {
                        if (mMultiTapCount == 0) {
                            mMultiTapTimeoutRunnable = createSingleTapRunnable(packageName, pid,
                                    uid, asSystemService, keyEvent, needWakeLock,
                                    opPackageName, stream, musicOnly,
                                    isSingleTapOverridden(overriddenKeyEvents));
                            if (isSingleTapOverridden(overriddenKeyEvents)
                                    && !isDoubleTapOverridden(overriddenKeyEvents)
                                    && !isTripleTapOverridden(overriddenKeyEvents)) {
                                mMultiTapTimeoutRunnable.run();
                            } else {
                                mHandler.postDelayed(mMultiTapTimeoutRunnable,
                                        MULTI_TAP_TIMEOUT);
                                mMultiTapCount = 1;
                                mMultiTapKeyCode = keyEvent.getKeyCode();
                            }
                        } else if (mMultiTapCount == 1) {
                            mHandler.removeCallbacks(mMultiTapTimeoutRunnable);
                            mMultiTapTimeoutRunnable = createDoubleTapRunnable(packageName, pid,
                                    uid, asSystemService, keyEvent, needWakeLock, opPackageName,
                                    stream, musicOnly, isSingleTapOverridden(overriddenKeyEvents),
                                    isDoubleTapOverridden(overriddenKeyEvents));
                            if (isTripleTapOverridden(overriddenKeyEvents)) {
                                mHandler.postDelayed(mMultiTapTimeoutRunnable, MULTI_TAP_TIMEOUT);
                                mMultiTapCount = 2;
                            } else {
                                mMultiTapTimeoutRunnable.run();
                            }
                        } else if (mMultiTapCount == 2) {
                            mHandler.removeCallbacks(mMultiTapTimeoutRunnable);
                            onTripleTap(keyEvent);
                        }
                    } else {
                        dispatchDownAndUpKeyEventsLocked(packageName, pid, uid, asSystemService,
                                keyEvent, needWakeLock, opPackageName, stream, musicOnly);
                    }
                }
            }

            private boolean shouldTrackForMultipleTapsLocked(int overriddenKeyEvents) {
                return isSingleTapOverridden(overriddenKeyEvents)
                        || isDoubleTapOverridden(overriddenKeyEvents)
                        || isTripleTapOverridden(overriddenKeyEvents);
            }

            private void cancelTrackingIfNeeded(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock,
                    String opPackageName, int stream, boolean musicOnly, int overriddenKeyEvents) {
                if (mTrackingFirstDownKeyEvent == null && mMultiTapTimeoutRunnable == null) {
                    return;
                }

                if (isFirstDownKeyEvent(keyEvent)) {
                    if (mLongPressTimeoutRunnable != null) {
                        mHandler.removeCallbacks(mLongPressTimeoutRunnable);
                        mLongPressTimeoutRunnable.run();
                    }
                    if (mMultiTapTimeoutRunnable != null
                            && keyEvent.getKeyCode() != mMultiTapKeyCode) {
                        runExistingMultiTapRunnableLocked();
                    }
                    resetLongPressTracking();
                    return;
                }

                if (mTrackingFirstDownKeyEvent != null
                        && mTrackingFirstDownKeyEvent.getDownTime() == keyEvent.getDownTime()
                        && mTrackingFirstDownKeyEvent.getKeyCode() == keyEvent.getKeyCode()
                        && keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                    if (isFirstLongPressKeyEvent(keyEvent)) {
                        if (mMultiTapTimeoutRunnable != null) {
                            runExistingMultiTapRunnableLocked();
                        }
                        if ((overriddenKeyEvents & KEY_EVENT_LONG_PRESS) == 0) {
                            if (mKeyType == KEY_TYPE_VOLUME) {
                                if (mCurrentFullUserRecord.mOnVolumeKeyLongPressListener == null) {
                                    dispatchVolumeKeyEventLocked(packageName, opPackageName, pid,
                                            uid, asSystemService, keyEvent, stream, musicOnly);
                                    mTrackingFirstDownKeyEvent = null;
                                }
                            } else if (!isVoiceKey(keyEvent.getKeyCode())) {
                                dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService,
                                        keyEvent, needWakeLock);
                                mTrackingFirstDownKeyEvent = null;
                            }
                        }
                    } else if (keyEvent.getRepeatCount() > 1 && !mIsLongPressing) {
                        resetLongPressTracking();
                    }
                }
            }

            private boolean needTracking(KeyEvent keyEvent, int overriddenKeyEvents) {
                if (!isFirstDownKeyEvent(keyEvent)) {
                    if (mTrackingFirstDownKeyEvent == null) {
                        return false;
                    } else if (mTrackingFirstDownKeyEvent.getDownTime() != keyEvent.getDownTime()
                            || mTrackingFirstDownKeyEvent.getKeyCode() != keyEvent.getKeyCode()) {
                        return false;
                    }
                }
                if (overriddenKeyEvents == 0) {
                    if (mKeyType == KEY_TYPE_VOLUME) {
                        if (mCurrentFullUserRecord.mOnVolumeKeyLongPressListener == null) {
                            return false;
                        }
                    } else if (!isVoiceKey(keyEvent.getKeyCode())) {
                        return false;
                    }
                }
                return true;
            }

            private void runExistingMultiTapRunnableLocked() {
                mHandler.removeCallbacks(mMultiTapTimeoutRunnable);
                mMultiTapTimeoutRunnable.run();
            }

            private void resetMultiTapTrackingLocked() {
                mMultiTapCount = 0;
                mMultiTapTimeoutRunnable = null;
                mMultiTapKeyCode = 0;
            }

            private void handleLongPressLocked(KeyEvent keyEvent, boolean needWakeLock,
                    int overriddenKeyEvents) {
                if (mCustomMediaKeyDispatcher != null
                        && isLongPressOverridden(overriddenKeyEvents)) {
                    mCustomMediaKeyDispatcher.onLongPress(keyEvent);

                    if (mLongPressTimeoutRunnable != null) {
                        mHandler.removeCallbacks(mLongPressTimeoutRunnable);
                    }
                    if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                        if (mLongPressTimeoutRunnable == null) {
                            mLongPressTimeoutRunnable = createLongPressTimeoutRunnable(keyEvent);
                        }
                        mHandler.postDelayed(mLongPressTimeoutRunnable, LONG_PRESS_TIMEOUT);
                    } else {
                        resetLongPressTracking();
                    }
                } else {
                    if (mKeyType == KEY_TYPE_VOLUME) {
                        if (isFirstLongPressKeyEvent(keyEvent)) {
                            dispatchVolumeKeyLongPressLocked(mTrackingFirstDownKeyEvent);
                        }
                        dispatchVolumeKeyLongPressLocked(keyEvent);
                    } else if (isFirstLongPressKeyEvent(keyEvent)
                            && isVoiceKey(keyEvent.getKeyCode())) {
                        // Default implementation
                        startVoiceInput(needWakeLock);
                        resetLongPressTracking();
                    }
                }
            }

            private Runnable createLongPressTimeoutRunnable(KeyEvent keyEvent) {
                return new Runnable() {
                    @Override
                    public void run() {
                        if (mCustomMediaKeyDispatcher != null) {
                            mCustomMediaKeyDispatcher.onLongPress(createCanceledKeyEvent(keyEvent));
                        }
                        resetLongPressTracking();
                    }
                };
            }

            private void resetLongPressTracking() {
                mTrackingFirstDownKeyEvent = null;
                mIsLongPressing = false;
                mLongPressTimeoutRunnable = null;
            }

            private KeyEvent createCanceledKeyEvent(KeyEvent keyEvent) {
                KeyEvent upEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP);
                return KeyEvent.changeTimeRepeat(upEvent, System.currentTimeMillis(), 0,
                        KeyEvent.FLAG_CANCELED);
            }

            private boolean isFirstLongPressKeyEvent(KeyEvent keyEvent) {
                return ((keyEvent.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0)
                        && keyEvent.getRepeatCount() == 1;
            }

            private boolean isFirstDownKeyEvent(KeyEvent keyEvent) {
                return keyEvent.getAction() == KeyEvent.ACTION_DOWN
                        && keyEvent.getRepeatCount() == 0;
            }

            private void dispatchDownAndUpKeyEventsLocked(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock,
                    String opPackageName, int stream, boolean musicOnly) {
                KeyEvent downEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_DOWN);
                if (mKeyType == KEY_TYPE_VOLUME) {
                    dispatchVolumeKeyEventLocked(packageName, opPackageName, pid, uid,
                            asSystemService, downEvent, stream, musicOnly);
                    dispatchVolumeKeyEventLocked(packageName, opPackageName, pid, uid,
                            asSystemService, keyEvent, stream, musicOnly);
                } else {
                    dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService, downEvent,
                            needWakeLock);
                    dispatchMediaKeyEventLocked(packageName, pid, uid, asSystemService, keyEvent,
                            needWakeLock);
                }
            }

            Runnable createSingleTapRunnable(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock,
                    String opPackageName, int stream, boolean musicOnly, boolean overridden) {
                return new Runnable() {
                    @Override
                    public void run() {
                        resetMultiTapTrackingLocked();
                        if (overridden) {
                            mCustomMediaKeyDispatcher.onSingleTap(keyEvent);
                        } else {
                            dispatchDownAndUpKeyEventsLocked(packageName, pid, uid, asSystemService,
                                    keyEvent, needWakeLock, opPackageName, stream, musicOnly);
                        }
                    }
                };
            };

            Runnable createDoubleTapRunnable(String packageName, int pid, int uid,
                    boolean asSystemService, KeyEvent keyEvent, boolean needWakeLock,
                    String opPackageName, int stream, boolean musicOnly,
                    boolean singleTapOverridden, boolean doubleTapOverridden) {
                return new Runnable() {
                    @Override
                    public void run() {
                        resetMultiTapTrackingLocked();
                        if (doubleTapOverridden) {
                            mCustomMediaKeyDispatcher.onDoubleTap(keyEvent);
                        } else if (singleTapOverridden) {
                            mCustomMediaKeyDispatcher.onSingleTap(keyEvent);
                            mCustomMediaKeyDispatcher.onSingleTap(keyEvent);
                        } else {
                            dispatchDownAndUpKeyEventsLocked(packageName, pid, uid, asSystemService,
                                    keyEvent, needWakeLock, opPackageName, stream, musicOnly);
                            dispatchDownAndUpKeyEventsLocked(packageName, pid, uid, asSystemService,
                                    keyEvent, needWakeLock, opPackageName, stream, musicOnly);
                        }
                    }
                };
            };

            private void onTripleTap(KeyEvent keyEvent) {
                resetMultiTapTrackingLocked();
                mCustomMediaKeyDispatcher.onTripleTap(keyEvent);
            }
        }
    }

    final class MessageHandler extends Handler {
        private static final int MSG_SESSIONS_1_CHANGED = 1;
        private static final int MSG_SESSIONS_2_CHANGED = 2;
        private final SparseArray<Integer> mIntegerCache = new SparseArray<>();

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SESSIONS_1_CHANGED:
                    pushSession1Changed((int) msg.obj);
                    break;
                case MSG_SESSIONS_2_CHANGED:
                    pushSession2Changed((int) msg.obj);
                    break;
            }
        }

        public void postSessionsChanged(MediaSessionRecordImpl record) {
            // Use object instead of the arguments when posting message to remove pending requests.
            Integer userIdInteger = mIntegerCache.get(record.getUserId());
            if (userIdInteger == null) {
                userIdInteger = Integer.valueOf(record.getUserId());
                mIntegerCache.put(record.getUserId(), userIdInteger);
            }

            int msg = (record instanceof MediaSessionRecord)
                    ? MSG_SESSIONS_1_CHANGED : MSG_SESSIONS_2_CHANGED;
            removeMessages(msg, userIdInteger);
            obtainMessage(msg, userIdInteger).sendToTarget();
        }
    }
}
