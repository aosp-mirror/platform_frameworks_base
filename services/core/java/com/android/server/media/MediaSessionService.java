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

import static android.media.SessionToken2.TYPE_SESSION;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.INotificationManager;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioSystem;
import android.media.IAudioService;
import android.media.IRemoteVolumeController;
import android.media.ISessionTokensListener;
import android.media.MediaController2;
import android.media.MediaLibraryService2;
import android.media.MediaSessionService2;
import android.media.SessionToken2;
import android.media.session.IActiveSessionsListener;
import android.media.session.ICallback;
import android.media.session.IOnMediaKeyListener;
import android.media.session.IOnVolumeKeyLongPressListener;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
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
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.android.internal.os.BackgroundThread;
import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.Watchdog.Monitor;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.NoSuchElementException;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService implements Monitor {
    private static final String TAG = "MediaSessionService";
    static final boolean USE_MEDIA2_APIS = false; // TODO: Change this to true when we're ready.
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    // Leave log for key event always.
    private static final boolean DEBUG_KEY_EVENT = true;

    private static final int WAKELOCK_TIMEOUT = 5000;
    private static final int MEDIA_KEY_LISTENER_TIMEOUT = 1000;

    private final SessionManagerImpl mSessionManagerImpl;

    // Keeps the full user id for each user.
    private final SparseIntArray mFullUserIds = new SparseIntArray();
    private final SparseArray<FullUserRecord> mUserRecords = new SparseArray<FullUserRecord>();
    private final ArrayList<SessionsListenerRecord> mSessionsListeners
            = new ArrayList<SessionsListenerRecord>();
    private final Object mLock = new Object();
    private final MessageHandler mHandler = new MessageHandler();
    private final PowerManager.WakeLock mMediaEventWakeLock;
    private final int mLongPressTimeout;
    private final INotificationManager mNotificationManager;
    private final IPackageManager mPackageManager;

    private KeyguardManager mKeyguardManager;
    private IAudioService mAudioService;
    private ContentResolver mContentResolver;
    private SettingsObserver mSettingsObserver;
    private boolean mHasFeatureLeanback;

    // The FullUserRecord of the current users. (i.e. The foreground user that isn't a profile)
    // It's always not null after the MediaSessionService is started.
    private FullUserRecord mCurrentFullUserRecord;
    private MediaSessionRecord mGlobalPrioritySession;
    private AudioPlayerStateMonitor mAudioPlayerStateMonitor;

    // Used to notify system UI when remote volume was changed. TODO find a
    // better way to handle this.
    private IRemoteVolumeController mRvc;

    // MediaSession2 support
    // TODO(jaewan): Support multi-user and managed profile. (b/73597722)
    // TODO(jaewan): Make it priority list for handling volume/media key. (b/73760382)
    private final Map<SessionToken2, MediaController2> mSessionRecords = new ArrayMap<>();

    private final List<SessionTokensListenerRecord> mSessionTokensListeners = new ArrayList<>();

    public MediaSessionService(Context context) {
        super(context);
        mSessionManagerImpl = new SessionManagerImpl();
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mMediaEventWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handleMediaEvent");
        mLongPressTimeout = ViewConfiguration.getLongPressTimeout();
        mNotificationManager = INotificationManager.Stub.asInterface(
                ServiceManager.getService(Context.NOTIFICATION_SERVICE));
        mPackageManager = AppGlobals.getPackageManager();
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
        Watchdog.getInstance().addMonitor(this);
        mKeyguardManager =
                (KeyguardManager) getContext().getSystemService(Context.KEYGUARD_SERVICE);
        mAudioService = getAudioService();
        mAudioPlayerStateMonitor = AudioPlayerStateMonitor.getInstance();
        mAudioPlayerStateMonitor.registerListener(
                (config, isRemoved) -> {
                    if (isRemoved || !config.isActive() || config.getPlayerType()
                            == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                        return;
                    }
                    synchronized (mLock) {
                        FullUserRecord user = getFullUserRecordLocked(
                                UserHandle.getUserId(config.getClientUid()));
                        if (user != null) {
                            user.mPriorityStack.updateMediaButtonSessionIfNeeded();
                        }
                    }
                }, null /* handler */);
        mAudioPlayerStateMonitor.registerSelfIntoAudioServiceIfNeeded(mAudioService);
        mContentResolver = getContext().getContentResolver();
        mSettingsObserver = new SettingsObserver();
        mSettingsObserver.observe();
        mHasFeatureLeanback = getContext().getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_LEANBACK);

        updateUser();

        registerPackageBroadcastReceivers();
        // TODO(jaewan): Query per users (b/73597722)
        buildMediaSessionService2List();
    }

    private IAudioService getAudioService() {
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        return IAudioService.Stub.asInterface(b);
    }

    private boolean isGlobalPriorityActiveLocked() {
        return mGlobalPrioritySession != null && mGlobalPrioritySession.isActive();
    }

    public void updateSession(MediaSessionRecord record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null) {
                Log.w(TAG, "Unknown session updated. Ignoring.");
                return;
            }
            if ((record.getFlags() & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0) {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Global priority session is updated, active=" + record.isActive());
                }
                user.pushAddressedPlayerChangedLocked();
            } else {
                if (!user.mPriorityStack.contains(record)) {
                    Log.w(TAG, "Unknown session updated. Ignoring.");
                    return;
                }
                user.mPriorityStack.onSessionStateChange(record);
            }
            mHandler.postSessionsChanged(record.getUserId());
        }
    }

    public void setGlobalPrioritySession(MediaSessionRecord record) {
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
        if (userId == UserHandle.USER_ALL) {
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
                && (userId == UserHandle.USER_ALL
                    || userId == mGlobalPrioritySession.getUserId())) {
            records.add(0, mGlobalPrioritySession);
        }
        return records;
    }

    /**
     * Tells the system UI that volume has changed on an active remote session.
     */
    public void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session) {
        if (mRvc == null || !session.isActive()) {
            return;
        }
        try {
            mRvc.remoteVolumeChanged(session.getControllerBinder(), flags);
        } catch (Exception e) {
            Log.wtf(TAG, "Error sending volume change to system UI.", e);
        }
    }

    public void onSessionPlaystateChanged(MediaSessionRecord record, int oldState, int newState) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            if (user == null || !user.mPriorityStack.contains(record)) {
                Log.d(TAG, "Unknown session changed playback state. Ignoring.");
                return;
            }
            user.mPriorityStack.onPlaystateChanged(record, oldState, newState);
        }
    }

    public void onSessionPlaybackTypeChanged(MediaSessionRecord record) {
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
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user != null) {
                if (user.mFullUserId == userId) {
                    user.destroySessionsForUserLocked(UserHandle.USER_ALL);
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
            mFullUserIds.clear();
            List<UserInfo> allUsers = manager.getUsers();
            if (allUsers != null) {
                for (UserInfo userInfo : allUsers) {
                    if (userInfo.isManagedProfile()) {
                        mFullUserIds.put(userInfo.id, userInfo.profileGroupId);
                    } else {
                        mFullUserIds.put(userInfo.id, userInfo.id);
                        if (mUserRecords.get(userInfo.id) == null) {
                            mUserRecords.put(userInfo.id, new FullUserRecord(userInfo.id));
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
        FullUserRecord user = getFullUserRecordLocked(session.getUserId());
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

        try {
            session.getCallback().asBinder().unlinkToDeath(session, 0);
        } catch (Exception e) {
            // ignore exceptions while destroying a session.
        }
        session.onDestroy();
        mHandler.postSessionsChanged(session.getUserId());
    }

    private void registerPackageBroadcastReceivers() {
        // TODO(jaewan): Only consider changed packages when building session service list
        //               when we make this multi-user aware. At that time,
        //               use PackageMonitor.getChangingUserId() to know which user has changed.
        //               (b/73597722)
        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        filter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
        filter.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);

        getContext().registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int changeUserId = intent.getIntExtra(
                        Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
                if (changeUserId == UserHandle.USER_NULL) {
                    Log.w(TAG, "Intent broadcast does not contain user handle: "+ intent);
                    return;
                }
                // Check if the package is replacing (i.e. reinstalling)
                final boolean isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
                // TODO(jaewan): Add multi-user support with this. (b/73597722)
                // final int uid = intent.getIntExtra(Intent.EXTRA_UID, 0);

                if (DEBUG) {
                    Log.d(TAG, "Received change in packages, intent=" + intent);
                }
                switch (intent.getAction()) {
                    case Intent.ACTION_PACKAGE_ADDED:
                    case Intent.ACTION_PACKAGE_REMOVED:
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE:
                    case Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE:
                        if (isReplacing) {
                            // Ignore if the package(s) are replacing. In that case, followings will
                            // happen in order.
                            //    1. ACTION_PACKAGE_REMOVED with isReplacing=true
                            //    2. ACTION_PACKAGE_ADDED with isReplacing=true
                            //    3. ACTION_PACKAGE_REPLACED
                            //    (Note that ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE and
                            //     ACTION_EXTERNAL_APPLICATIONS_AVAILABLE will be also called with
                            //     isReplacing=true for both ASEC hosted packages and packages in
                            //     external storage)
                            // Since we only want to update session service list once, ignore
                            // actions above when replacing.
                            // Replacing will be handled only once with the ACTION_PACKAGE_REPLACED.
                            break;
                        }
                        // pass-through
                    case Intent.ACTION_PACKAGE_CHANGED:
                    case Intent.ACTION_PACKAGES_SUSPENDED:
                    case Intent.ACTION_PACKAGES_UNSUSPENDED:
                    case Intent.ACTION_PACKAGE_REPLACED:
                        buildMediaSessionService2List();
                }
            }
        }, UserHandle.ALL, filter, null, BackgroundThread.getHandler());
    }

    private void buildMediaSessionService2List() {
        if (!USE_MEDIA2_APIS) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "buildMediaSessionService2List");
        }
        // TODO(jaewan): Also query for managed profile users. (b/73597722)
        PackageManager manager = getContext().getPackageManager();
        List<ResolveInfo> services = new ArrayList<>();
        // If multiple actions are declared for a service, browser gets higher priority.
        List<ResolveInfo> libraryServices = manager.queryIntentServices(
                new Intent(MediaLibraryService2.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        if (libraryServices != null) {
            services.addAll(libraryServices);
        }
        List<ResolveInfo> sessionServices = manager.queryIntentServices(
                new Intent(MediaSessionService2.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        if (sessionServices != null) {
            services.addAll(sessionServices);
        }
        synchronized (mLock) {
            // List to keep the session services that need be removed because they don't exist
            // in the 'services' above.
            boolean notifySessionTokensUpdated = false;
            Set<SessionToken2> sessionTokensToRemove = new HashSet<>();
            for (SessionToken2 token : mSessionRecords.keySet()) {
                if (token.getType() != TYPE_SESSION) {
                    sessionTokensToRemove.add(token);
                }
            }

            for (int i = 0; i < services.size(); i++) {
                if (services.get(i) == null || services.get(i).serviceInfo == null) {
                    continue;
                }
                ServiceInfo serviceInfo = services.get(i).serviceInfo;
                int uid;
                try {
                    // TODO(jaewan): Do this per user. (b/73597722)
                    uid = manager.getPackageUid(serviceInfo.packageName,
                            PackageManager.GET_META_DATA);
                } catch (NameNotFoundException e) {
                    continue;
                }
                SessionToken2 token;
                try {
                    token = new SessionToken2(getContext(),
                            serviceInfo.packageName, serviceInfo.name, uid);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Invalid session service", e);
                    continue;
                }
                // If the token already exists, keep it in the mSessions by removing from
                // sessionTokensToRemove.
                if (!sessionTokensToRemove.remove(token)) {
                    // New session service is found.
                    notifySessionTokensUpdated |= addSessionRecordLocked(token);
                }
            }
            for (SessionToken2 token : sessionTokensToRemove) {
                mSessionRecords.remove(token);
                notifySessionTokensUpdated |= removeSessionRecordLocked(token);
            }

            if (notifySessionTokensUpdated) {
                // TODO(jaewan): Pass proper user id to postSessionTokensUpdated(...)
                postSessionTokensUpdated(UserHandle.USER_ALL);
            }
        }
        if (DEBUG) {
            Log.d(TAG, "Found " + mSessionRecords.size() + " session services");
            for (SessionToken2 token : mSessionRecords.keySet()) {
                Log.d(TAG, "   " + token);
            }
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
        if (isCurrentVolumeController(pid, uid)) return;
        if (getContext()
                .checkPermission(android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                    != PackageManager.PERMISSION_GRANTED
                && !isEnabledNotificationListener(compName, UserHandle.getUserId(uid),
                        resolvedUserId)) {
            throw new SecurityException("Missing permission to control media.");
        }
    }

    private boolean isCurrentVolumeController(int pid, int uid) {
        return getContext().checkPermission(android.Manifest.permission.STATUS_BAR_SERVICE,
                pid, uid) == PackageManager.PERMISSION_GRANTED;
    }

    private void enforceSystemUiPermission(String action, int pid, int uid) {
        if (!isCurrentVolumeController(pid, uid)) {
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
            try {
                return mNotificationManager.isNotificationListenerAccessGrantedForUser(
                        compName, userId);
            } catch(RemoteException e) {
                Log.w(TAG, "Dead NotificationManager in isEnabledNotificationListener", e);
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
        FullUserRecord user = getFullUserRecordLocked(userId);
        if (user == null) {
            Log.wtf(TAG, "Request from invalid user: " +  userId);
            throw new RuntimeException("Session request from invalid user.");
        }

        final MediaSessionRecord session = new MediaSessionRecord(callerPid, callerUid, userId,
                callerPackageName, cb, tag, this, mHandler.getLooper());
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }

        user.mPriorityStack.addSession(session);
        mHandler.postSessionsChanged(userId);

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
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user == null) {
                Log.w(TAG, "pushSessionsChanged failed. No user with id=" + userId);
                return;
            }
            List<MediaSessionRecord> records = getActiveSessionsLocked(userId);
            int size = records.size();
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
                FullUserRecord user = getFullUserRecordLocked(userId);
                if (user == null) {
                    Log.w(TAG, "pushRemoteVolumeUpdateLocked failed. No user with id=" + userId);
                    return;
                }
                MediaSessionRecord record = user.mPriorityStack.getDefaultRemoteSession(userId);
                mRvc.updateRemoteController(record == null ? null : record.getControllerBinder());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error sending default remote volume to sys ui.", e);
            }
        }
    }

    /**
     * Called when the media button receiver for the {@param record} is changed.
     *
     * @param record the media session whose media button receiver is updated.
     */
    public void onMediaButtonReceiverChanged(MediaSessionRecord record) {
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(record.getUserId());
            MediaSessionRecord mediaButtonSession =
                    user.mPriorityStack.getMediaButtonSession();
            if (record == mediaButtonSession) {
                user.rememberMediaButtonReceiverLocked(mediaButtonSession);
            }
        }
    }

    private String getCallingPackageName(int uid) {
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        }
        return "";
    }

    private void dispatchVolumeKeyLongPressLocked(KeyEvent keyEvent) {
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

    void destroySession2Internal(SessionToken2 token) {
        synchronized (mLock) {
            boolean notifySessionTokensUpdated = false;
            if (token.getType() == SessionToken2.TYPE_SESSION) {
                notifySessionTokensUpdated |= removeSessionRecordLocked(token);
            } else {
                notifySessionTokensUpdated |= addSessionRecordLocked(token);
            }
            if (notifySessionTokensUpdated) {
                postSessionTokensUpdated(UserHandle.getUserId(token.getUid()));
            }
        }
    }

    /**
     * Information about a full user and its corresponding managed profiles.
     *
     * <p>Since the full user runs together with its managed profiles, a user wouldn't differentiate
     * them when he/she presses a media/volume button. So keeping media sessions for them in one
     * place makes more sense and increases the readability.</p>
     * <p>The contents of this object is guarded by {@link #mLock}.
     */
    final class FullUserRecord implements MediaSessionStack.OnMediaButtonSessionChangedListener {
        private static final String COMPONENT_NAME_USER_ID_DELIM = ",";
        private final int mFullUserId;
        private final MediaSessionStack mPriorityStack;
        private PendingIntent mLastMediaButtonReceiver;
        private ComponentName mRestoredMediaButtonReceiver;
        private int mRestoredMediaButtonReceiverUserId;

        private IOnVolumeKeyLongPressListener mOnVolumeKeyLongPressListener;
        private int mOnVolumeKeyLongPressListenerUid;
        private KeyEvent mInitialDownVolumeKeyEvent;
        private int mInitialDownVolumeStream;
        private boolean mInitialDownMusicOnly;

        private IOnMediaKeyListener mOnMediaKeyListener;
        private int mOnMediaKeyListenerUid;
        private ICallback mCallback;

        public FullUserRecord(int fullUserId) {
            mFullUserId = fullUserId;
            mPriorityStack = new MediaSessionStack(mAudioPlayerStateMonitor, this);
            // Restore the remembered media button receiver before the boot.
            String mediaButtonReceiver = Settings.Secure.getStringForUser(mContentResolver,
                    Settings.System.MEDIA_BUTTON_RECEIVER, mFullUserId);
            if (mediaButtonReceiver == null) {
                return;
            }
            String[] tokens = mediaButtonReceiver.split(COMPONENT_NAME_USER_ID_DELIM);
            if (tokens == null || tokens.length != 2) {
                return;
            }
            mRestoredMediaButtonReceiver = ComponentName.unflattenFromString(tokens[0]);
            mRestoredMediaButtonReceiverUserId = Integer.parseInt(tokens[1]);
        }

        public void destroySessionsForUserLocked(int userId) {
            List<MediaSessionRecord> sessions = mPriorityStack.getPriorityList(false, userId);
            for (MediaSessionRecord session : sessions) {
                MediaSessionService.this.destroySessionLocked(session);
            }
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
            pw.println(indent + "Volume key long-press listener package: " +
                    getCallingPackageName(mOnVolumeKeyLongPressListenerUid));
            pw.println(indent + "Media key listener: " + mOnMediaKeyListener);
            pw.println(indent + "Media key listener package: " +
                    getCallingPackageName(mOnMediaKeyListenerUid));
            pw.println(indent + "Callback: " + mCallback);
            pw.println(indent + "Last MediaButtonReceiver: " + mLastMediaButtonReceiver);
            pw.println(indent + "Restored MediaButtonReceiver: " + mRestoredMediaButtonReceiver);
            mPriorityStack.dump(pw, indent);
        }

        @Override
        public void onMediaButtonSessionChanged(MediaSessionRecord oldMediaButtonSession,
                MediaSessionRecord newMediaButtonSession) {
            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "Media button session is changed to " + newMediaButtonSession);
            }
            synchronized (mLock) {
                if (oldMediaButtonSession != null) {
                    mHandler.postSessionsChanged(oldMediaButtonSession.getUserId());
                }
                if (newMediaButtonSession != null) {
                    rememberMediaButtonReceiverLocked(newMediaButtonSession);
                    mHandler.postSessionsChanged(newMediaButtonSession.getUserId());
                }
                pushAddressedPlayerChangedLocked();
            }
        }

        // Remember media button receiver and keep it in the persistent storage.
        public void rememberMediaButtonReceiverLocked(MediaSessionRecord record) {
            PendingIntent receiver = record.getMediaButtonReceiver();
            mLastMediaButtonReceiver = receiver;
            mRestoredMediaButtonReceiver = null;
            String componentName = "";
            if (receiver != null) {
                ComponentName component = receiver.getIntent().getComponent();
                if (component != null
                        && record.getPackageName().equals(component.getPackageName())) {
                    componentName = component.flattenToString();
                }
            }
            Settings.Secure.putStringForUser(mContentResolver,
                    Settings.System.MEDIA_BUTTON_RECEIVER,
                    componentName + COMPONENT_NAME_USER_ID_DELIM + record.getUserId(),
                    mFullUserId);
        }

        private void pushAddressedPlayerChangedLocked() {
            if (mCallback == null) {
                return;
            }
            try {
                MediaSessionRecord mediaButtonSession = getMediaButtonSessionLocked();
                if (mediaButtonSession != null) {
                    mCallback.onAddressedPlayerChangedToMediaSession(
                            new MediaSession.Token(mediaButtonSession.getControllerBinder()));
                } else if (mCurrentFullUserRecord.mLastMediaButtonReceiver != null) {
                    mCallback.onAddressedPlayerChangedToMediaButtonReceiver(
                            mCurrentFullUserRecord.mLastMediaButtonReceiver
                                    .getIntent().getComponent());
                } else if (mCurrentFullUserRecord.mRestoredMediaButtonReceiver != null) {
                    mCallback.onAddressedPlayerChangedToMediaButtonReceiver(
                            mCurrentFullUserRecord.mRestoredMediaButtonReceiver);
                }
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to pushAddressedPlayerChangedLocked", e);
            }
        }

        private MediaSessionRecord getMediaButtonSessionLocked() {
            return isGlobalPriorityActiveLocked()
                    ? mGlobalPrioritySession : mPriorityStack.getMediaButtonSession();
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
                    List<MediaSessionRecord> records = getActiveSessionsLocked(resolvedUserId);
                    for (MediaSessionRecord record : records) {
                        binders.add(record.getControllerBinder().asBinder());
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

                synchronized (mLock) {
                    boolean isGlobalPriorityActive = isGlobalPriorityActiveLocked();
                    if (isGlobalPriorityActive && uid != Process.SYSTEM_UID) {
                        // Prevent dispatching key event through reflection while the global
                        // priority session is active.
                        Slog.i(TAG, "Only the system can dispatch media key event "
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
                                        new MediaKeyListenerResultReceiver(keyEvent, needWakeLock));
                                return;
                            } catch (RemoteException e) {
                                Log.w(TAG, "Failed to send " + keyEvent
                                        + " to the media key listener");
                            }
                        }
                    }
                    if (!isGlobalPriorityActive && isVoiceKey(keyEvent.getKeyCode())) {
                        handleVoiceKeyEventLocked(keyEvent, needWakeLock);
                    } else {
                        dispatchMediaKeyEventLocked(keyEvent, needWakeLock);
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void setCallback(ICallback callback) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                if (!UserHandle.isSameApp(uid, Process.BLUETOOTH_UID)) {
                    throw new SecurityException("Only Bluetooth service processes can set"
                            + " Callback");
                }
                synchronized (mLock) {
                    int userId = UserHandle.getUserId(uid);
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can set the callback"
                                + ", userId=" + userId);
                        return;
                    }
                    user.mCallback = callback;
                    Log.d(TAG, "The callback " + user.mCallback
                            + " is set by " + getCallingPackageName(uid));
                    if (user.mCallback == null) {
                        return;
                    }
                    try {
                        user.mCallback.asBinder().linkToDeath(
                                new IBinder.DeathRecipient() {
                                    @Override
                                    public void binderDied() {
                                        synchronized (mLock) {
                                            user.mCallback = null;
                                        }
                                    }
                                }, 0);
                        user.pushAddressedPlayerChangedLocked();
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to set callback", e);
                        user.mCallback = null;
                    }
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
                if (getContext().checkPermission(
                        android.Manifest.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER, pid, uid)
                            != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Must hold the SET_VOLUME_KEY_LONG_PRESS_LISTENER" +
                            " permission.");
                }

                synchronized (mLock) {
                    int userId = UserHandle.getUserId(uid);
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null || user.mFullUserId != userId) {
                        Log.w(TAG, "Only the full user can set the volume key long-press listener"
                                + ", userId=" + userId);
                        return;
                    }
                    if (user.mOnVolumeKeyLongPressListener != null &&
                            user.mOnVolumeKeyLongPressListenerUid != uid) {
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
                if (getContext().checkPermission(
                        android.Manifest.permission.SET_MEDIA_KEY_LISTENER, pid, uid)
                            != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Must hold the SET_MEDIA_KEY_LISTENER" +
                            " permission.");
                }

                synchronized (mLock) {
                    int userId = UserHandle.getUserId(uid);
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
         * Handles the dispatching of the volume button events to one of the
         * registered listeners. If there's a volume key long-press listener and
         * there's no active global priority session, long-pressess will be sent to the
         * long-press listener instead of adjusting volume.
         *
         * @param keyEvent a non-null KeyEvent whose key code is one of the
         *            {@link KeyEvent#KEYCODE_VOLUME_UP},
         *            {@link KeyEvent#KEYCODE_VOLUME_DOWN},
         *            or {@link KeyEvent#KEYCODE_VOLUME_MUTE}.
         * @param stream stream type to adjust volume.
         * @param musicOnly true if both UI nor haptic feedback aren't needed when adjust volume.
         */
        @Override
        public void dispatchVolumeKeyEvent(KeyEvent keyEvent, int stream, boolean musicOnly) {
            if (keyEvent == null ||
                    (keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                             && keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                             && keyEvent.getKeyCode() != KeyEvent.KEYCODE_VOLUME_MUTE)) {
                Log.w(TAG, "Attempted to dispatch null or non-volume key event.");
                return;
            }

            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            if (DEBUG_KEY_EVENT) {
                Log.d(TAG, "dispatchVolumeKeyEvent, pid=" + pid + ", uid=" + uid + ", event="
                        + keyEvent);
            }

            try {
                synchronized (mLock) {
                    if (isGlobalPriorityActiveLocked()
                            || mCurrentFullUserRecord.mOnVolumeKeyLongPressListener == null) {
                        dispatchVolumeKeyEventLocked(keyEvent, stream, musicOnly);
                    } else {
                        // TODO: Consider the case when both volume up and down keys are pressed
                        //       at the same time.
                        if (keyEvent.getAction() == KeyEvent.ACTION_DOWN) {
                            if (keyEvent.getRepeatCount() == 0) {
                                // Keeps the copy of the KeyEvent because it can be reused.
                                mCurrentFullUserRecord.mInitialDownVolumeKeyEvent =
                                        KeyEvent.obtain(keyEvent);
                                mCurrentFullUserRecord.mInitialDownVolumeStream = stream;
                                mCurrentFullUserRecord.mInitialDownMusicOnly = musicOnly;
                                mHandler.sendMessageDelayed(
                                        mHandler.obtainMessage(
                                                MessageHandler.MSG_VOLUME_INITIAL_DOWN,
                                                mCurrentFullUserRecord.mFullUserId, 0),
                                        mLongPressTimeout);
                            }
                            if (keyEvent.getRepeatCount() > 0 || keyEvent.isLongPress()) {
                                mHandler.removeMessages(MessageHandler.MSG_VOLUME_INITIAL_DOWN);
                                if (mCurrentFullUserRecord.mInitialDownVolumeKeyEvent != null) {
                                    dispatchVolumeKeyLongPressLocked(
                                            mCurrentFullUserRecord.mInitialDownVolumeKeyEvent);
                                    // Mark that the key is already handled.
                                    mCurrentFullUserRecord.mInitialDownVolumeKeyEvent = null;
                                }
                                dispatchVolumeKeyLongPressLocked(keyEvent);
                            }
                        } else { // if up
                            mHandler.removeMessages(MessageHandler.MSG_VOLUME_INITIAL_DOWN);
                            if (mCurrentFullUserRecord.mInitialDownVolumeKeyEvent != null
                                    && mCurrentFullUserRecord.mInitialDownVolumeKeyEvent
                                            .getDownTime() == keyEvent.getDownTime()) {
                                // Short-press. Should change volume.
                                dispatchVolumeKeyEventLocked(
                                        mCurrentFullUserRecord.mInitialDownVolumeKeyEvent,
                                        mCurrentFullUserRecord.mInitialDownVolumeStream,
                                        mCurrentFullUserRecord.mInitialDownMusicOnly);
                                dispatchVolumeKeyEventLocked(keyEvent, stream, musicOnly);
                            } else {
                                dispatchVolumeKeyLongPressLocked(keyEvent);
                            }
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void dispatchVolumeKeyEventLocked(
                KeyEvent keyEvent, int stream, boolean musicOnly) {
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
                if (musicOnly) {
                    // This flag is used when the screen is off to only affect active media.
                    flags |= AudioManager.FLAG_ACTIVE_MEDIA_ONLY;
                } else {
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
                    dispatchAdjustVolumeLocked(stream, direction, flags);
                } else if (isMute) {
                    if (down && keyEvent.getRepeatCount() == 0) {
                        dispatchAdjustVolumeLocked(stream, AudioManager.ADJUST_TOGGLE_MUTE, flags);
                    }
                }
            }
        }

        @Override
        public void dispatchAdjustVolume(int suggestedStream, int delta, int flags) {
            final long token = Binder.clearCallingIdentity();
            try {
                synchronized (mLock) {
                    dispatchAdjustVolumeLocked(suggestedStream, delta, flags);
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
            synchronized (mLock) {
                return isGlobalPriorityActiveLocked();
            }
        }

        @Override
        public void dump(FileDescriptor fd, final PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

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
                mAudioPlayerStateMonitor.dump(getContext(), pw, "");
            }
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
        public boolean isTrusted(String controllerPackageName, int controllerPid, int controllerUid)
                throws RemoteException {
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                int controllerUserId = UserHandle.getUserId(controllerUid);
                int controllerUidFromPackageName;
                try {
                    controllerUidFromPackageName = getContext().getPackageManager()
                            .getPackageUidAsUser(controllerPackageName, controllerUserId);
                } catch (NameNotFoundException e) {
                    if (DEBUG) {
                        Log.d(TAG, "Package " + controllerPackageName + " doesn't exist");
                    }
                    return false;
                }
                if (controllerUidFromPackageName != controllerUid) {
                    if (DEBUG) {
                        Log.d(TAG, "Package name " + controllerPackageName
                                + " doesn't match with the uid " + controllerUid);
                    }
                    return false;
                }
                return hasMediaControlPermission(UserHandle.getUserId(uid), controllerPackageName,
                        controllerPid, controllerUid);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        /**
         * Called when a {@link android.media.MediaSession2} instance is created.
         * <p>
         * This does two things.
         *   1. Keep the newly created session in the service
         *   2. Do sanity check to ensure unique id per package, and return result
         *
         * @param sessionToken SessionToken2 object in bundled form
         * @return {@code true} if the session's id isn't used by the package now. {@code false}
         *     otherwise.
         */
        @Override
        public boolean createSession2(Bundle sessionToken) {
            if (!USE_MEDIA2_APIS) {
                return false;
            }
            final int uid = Binder.getCallingUid();
            final SessionToken2 token = SessionToken2.fromBundle(sessionToken);
            if (token == null || token.getUid() != uid) {
                Log.w(TAG, "onSessionCreated failed, expected caller uid=" + token.getUid()
                        + " but from uid=" + uid);
            }
            if (DEBUG) {
                Log.d(TAG, "createSession2: " + token);
            }
            synchronized (mLock) {
                MediaController2 controller = mSessionRecords.get(token);
                if (controller != null && controller.isConnected()) {
                    return false;
                }
                Context context = getContext();
                controller = new MediaController2(context, token, context.getMainExecutor(),
                        new ControllerCallback(token));
                if (addSessionRecordLocked(token, controller)) {
                    postSessionTokensUpdated(UserHandle.getUserId(token.getUid()));
                }
                return true;
            }
        }

        /**
         * Called when a {@link android.media.MediaSession2} instance is closed. (i.e. destroyed)
         * <p>
         * Ideally service should know that a session is destroyed through the
         * {@link android.media.MediaController2.ControllerCallback#onDisconnected()}, which is
         * asynchronous call. However, we also need synchronous way together to address timing
         * issue. If the package recreates the session almost immediately, which happens commonly
         * for tests, service will reject the creation through {@link #onSessionCreated(Bundle)}
         * if the service hasn't notified previous destroy yet. This synchronous API will address
         * the issue.
         *
         * @param sessionToken SessionToken2 object in bundled form
         */
        @Override
        public void destroySession2(Bundle sessionToken) {
            if (!USE_MEDIA2_APIS) {
                return;
            }
            final int uid = Binder.getCallingUid();
            final SessionToken2 token = SessionToken2.fromBundle(sessionToken);
            if (token == null || token.getUid() != uid) {
                Log.w(TAG, "onSessionDestroyed failed, expected caller uid=" + token.getUid()
                        + " but from uid=" + uid);
            }
            if (DEBUG) {
                Log.d(TAG, "destroySession2 " + token);
            }
            destroySession2Internal(token);
        }

        // TODO(jaewan): Make this API take userId as an argument (b/73597722)
        @Override
        public List<Bundle> getSessionTokens(boolean activeSessionOnly,
                boolean sessionServiceOnly, String packageName) throws RemoteException {
            if (!USE_MEDIA2_APIS) {
                return null;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            List<Bundle> tokens = new ArrayList<>();
            try {
                verifySessionsRequest2(UserHandle.getUserId(uid), packageName, pid, uid);
                synchronized (mLock) {
                    for (Map.Entry<SessionToken2, MediaController2> record
                            : mSessionRecords.entrySet()) {
                        boolean isSessionService = (record.getKey().getType() != TYPE_SESSION);
                        boolean isActive = record.getValue() != null;
                        if ((activeSessionOnly && !isActive)
                                || (sessionServiceOnly && !isSessionService)) {
                            continue;
                        }
                        tokens.add(record.getKey().toBundle());
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return tokens;
        }

        @Override
        public void addSessionTokensListener(ISessionTokensListener listener, int userId,
                String packageName) throws RemoteException {
            if (!USE_MEDIA2_APIS) {
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                int resolvedUserId = verifySessionsRequest2(userId, packageName, pid, uid);
                synchronized (mLock) {
                    final SessionTokensListenerRecord record =
                            new SessionTokensListenerRecord(listener, resolvedUserId);
                    try {
                        listener.asBinder().linkToDeath(record, 0);
                    } catch (RemoteException e) {
                    }
                    mSessionTokensListeners.add(record);
                }
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        // TODO(jaewan): Make this API take userId as an argument (b/73597722)
        @Override
        public void removeSessionTokensListener(ISessionTokensListener listener,
                String packageName) throws RemoteException {
            if (!USE_MEDIA2_APIS) {
                return;
            }
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                verifySessionsRequest2(UserHandle.getUserId(uid), packageName, pid, uid);
                synchronized (mLock) {
                    IBinder listenerBinder = listener.asBinder();
                    for (SessionTokensListenerRecord record : mSessionTokensListeners) {
                        if (listenerBinder.equals(record.mListener.asBinder())) {
                            try {
                                listenerBinder.unlinkToDeath(record, 0);
                            } catch (NoSuchElementException e) {
                            }
                            mSessionTokensListeners.remove(record);
                            break;
                        }
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token);
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
            // Check that they can make calls on behalf of the user and
            // get the final user id
            int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                    true /* allowAll */, true /* requireFull */, "getSessions", packageName);
            // Check if they have the permissions or their component is
            // enabled for the user they're calling from.
            enforceMediaPermissions(componentName, pid, uid, resolvedUserId);
            return resolvedUserId;
        }

        // For MediaSession2
        private int verifySessionsRequest2(int targetUserId, String callerPackageName,
                int callerPid, int callerUid) throws RemoteException {
            // Check that they can make calls on behalf of the user and get the final user id.
            int resolvedUserId = ActivityManager.handleIncomingUser(callerPid, callerUid,
                    targetUserId, true /* allowAll */, true /* requireFull */, "getSessionTokens",
                    callerPackageName);
            // Check if they have the permissions or their component is
            // enabled for the user they're calling from.
            if (!hasMediaControlPermission(
                    resolvedUserId, callerPackageName, callerPid, callerUid)) {
                throw new SecurityException("Missing permission to control media.");
            }
            return resolvedUserId;
        }

        // For MediaSession2
        private boolean hasMediaControlPermission(int resolvedUserId, String packageName,
                int pid, int uid) throws RemoteException {
            // Allow API calls from the System UI
            if (isCurrentVolumeController(pid, uid)) {
                return true;
            }

            // Check if it's system server or has MEDIA_CONTENT_CONTROL.
            // Note that system server doesn't have MEDIA_CONTENT_CONTROL, so we need extra
            // check here.
            if (uid == Process.SYSTEM_UID || getContext().checkPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL, pid, uid)
                    == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else if (DEBUG) {
                Log.d(TAG, packageName + " (uid=" + uid + ") hasn't granted MEDIA_CONTENT_CONTROL");
            }

            // You may not access another user's content as an enabled listener.
            final int userId = UserHandle.getUserId(uid);
            if (resolvedUserId != userId) {
                return false;
            }

            // TODO(jaewan): (Post-P) Propose NotificationManager#hasEnabledNotificationListener(
            //               String pkgName) to notification team for optimization
            final List<ComponentName> enabledNotificationListeners =
                    mNotificationManager.getEnabledNotificationListeners(userId);
            if (enabledNotificationListeners != null) {
                for (int i = 0; i < enabledNotificationListeners.size(); i++) {
                    if (TextUtils.equals(packageName,
                            enabledNotificationListeners.get(i).getPackageName())) {
                        return true;
                    }
                }
            }
            if (DEBUG) {
                Log.d(TAG, packageName + " (uid=" + uid + ") doesn't have an enabled "
                        + "notification listener");
            }
            return false;
        }

        private void dispatchAdjustVolumeLocked(int suggestedStream, int direction, int flags) {
            MediaSessionRecord session = isGlobalPriorityActiveLocked() ? mGlobalPrioritySession
                    : mCurrentFullUserRecord.mPriorityStack.getDefaultVolumeSession();

            boolean preferSuggestedStream = false;
            if (isValidLocalStreamType(suggestedStream)
                    && AudioSystem.isStreamActive(suggestedStream, 0)) {
                preferSuggestedStream = true;
            }
            if (DEBUG_KEY_EVENT) {
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

                // Execute mAudioService.adjustSuggestedStreamVolume() on
                // handler thread of MediaSessionService.
                // This will release the MediaSessionService.mLock sooner and avoid
                // a potential deadlock between MediaSessionService.mLock and
                // ActivityManagerService lock.
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            String packageName = getContext().getOpPackageName();
                            mAudioService.adjustSuggestedStreamVolume(direction, suggestedStream,
                                    flags, packageName, TAG);
                        } catch (RemoteException e) {
                            Log.e(TAG, "Error adjusting default volume.", e);
                        } catch (IllegalArgumentException e) {
                            Log.e(TAG, "Cannot adjust volume: direction=" + direction
                                    + ", suggestedStream=" + suggestedStream + ", flags=" + flags,
                                    e);
                        }
                    }
                });
            } else {
                session.adjustVolume(getContext().getPackageName(), Process.myPid(),
                        Process.SYSTEM_UID, direction, flags, true);
            }
        }

        private void handleVoiceKeyEventLocked(KeyEvent keyEvent, boolean needWakeLock) {
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
                        dispatchMediaKeyEventLocked(downEvent, needWakeLock);
                        dispatchMediaKeyEventLocked(keyEvent, needWakeLock);
                    }
                }
            }
        }

        private void dispatchMediaKeyEventLocked(KeyEvent keyEvent, boolean needWakeLock) {
            MediaSessionRecord session = mCurrentFullUserRecord.getMediaButtonSessionLocked();
            if (session != null) {
                if (DEBUG_KEY_EVENT) {
                    Log.d(TAG, "Sending " + keyEvent + " to " + session);
                }
                if (needWakeLock) {
                    mKeyEventReceiver.aquireWakeLockLocked();
                }
                // If we don't need a wakelock use -1 as the id so we won't release it later.
                session.sendMediaButton(getContext().getPackageName(),
                        Process.myPid(),
                        Process.SYSTEM_UID,
                        keyEvent,
                        needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1,
                        mKeyEventReceiver);
                if (mCurrentFullUserRecord.mCallback != null) {
                    try {
                        mCurrentFullUserRecord.mCallback.onMediaKeyEventDispatchedToMediaSession(
                                keyEvent,
                                new MediaSession.Token(session.getControllerBinder()));
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to send callback", e);
                    }
                }
            } else if (mCurrentFullUserRecord.mLastMediaButtonReceiver != null
                    || mCurrentFullUserRecord.mRestoredMediaButtonReceiver != null) {
                if (needWakeLock) {
                    mKeyEventReceiver.aquireWakeLockLocked();
                }
                Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                mediaButtonIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                mediaButtonIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
                try {
                    if (mCurrentFullUserRecord.mLastMediaButtonReceiver != null) {
                        PendingIntent receiver = mCurrentFullUserRecord.mLastMediaButtonReceiver;
                        if (DEBUG_KEY_EVENT) {
                            Log.d(TAG, "Sending " + keyEvent
                                    + " to the last known PendingIntent " + receiver);
                        }
                        receiver.send(getContext(),
                                needWakeLock ? mKeyEventReceiver.mLastTimeoutId : -1,
                                mediaButtonIntent, mKeyEventReceiver, mHandler);
                        if (mCurrentFullUserRecord.mCallback != null) {
                            ComponentName componentName = mCurrentFullUserRecord
                                    .mLastMediaButtonReceiver.getIntent().getComponent();
                            if (componentName != null) {
                                mCurrentFullUserRecord.mCallback
                                        .onMediaKeyEventDispatchedToMediaButtonReceiver(
                                                keyEvent, componentName);
                            }
                        }
                    } else {
                        ComponentName receiver =
                                mCurrentFullUserRecord.mRestoredMediaButtonReceiver;
                        if (DEBUG_KEY_EVENT) {
                            Log.d(TAG, "Sending " + keyEvent + " to the restored intent "
                                    + receiver);
                        }
                        mediaButtonIntent.setComponent(receiver);
                        getContext().sendBroadcastAsUser(mediaButtonIntent,
                                UserHandle.of(mCurrentFullUserRecord
                                      .mRestoredMediaButtonReceiverUserId));
                        if (mCurrentFullUserRecord.mCallback != null) {
                            mCurrentFullUserRecord.mCallback
                                    .onMediaKeyEventDispatchedToMediaButtonReceiver(
                                            keyEvent, receiver);
                        }
                    }
                } catch (CanceledException e) {
                    Log.i(TAG, "Error sending key event to media button receiver "
                            + mCurrentFullUserRecord.mLastMediaButtonReceiver, e);
                } catch (RemoteException e) {
                    Log.w(TAG, "Failed to send callback", e);
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
            return keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    || (!mHasFeatureLeanback && keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
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

        private class MediaKeyListenerResultReceiver extends ResultReceiver implements Runnable {
            private KeyEvent mKeyEvent;
            private boolean mNeedWakeLock;
            private boolean mHandled;

            private MediaKeyListenerResultReceiver(KeyEvent keyEvent, boolean needWakeLock) {
                super(mHandler);
                mHandler.postDelayed(this, MEDIA_KEY_LISTENER_TIMEOUT);
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
                    if (!isGlobalPriorityActiveLocked()
                            && isVoiceKey(mKeyEvent.getKeyCode())) {
                        handleVoiceKeyEventLocked(mKeyEvent, mNeedWakeLock);
                    } else {
                        dispatchMediaKeyEventLocked(mKeyEvent, mNeedWakeLock);
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
        private static final int MSG_VOLUME_INITIAL_DOWN = 2;
        private static final int MSG_SESSIONS_TOKENS_CHANGED = 3;
        private final SparseArray<Integer> mIntegerCache = new SparseArray<>();

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SESSIONS_CHANGED:
                    pushSessionsChanged((int) msg.obj);
                    break;
                case MSG_VOLUME_INITIAL_DOWN:
                    synchronized (mLock) {
                        FullUserRecord user = mUserRecords.get((int) msg.arg1);
                        if (user != null && user.mInitialDownVolumeKeyEvent != null) {
                            dispatchVolumeKeyLongPressLocked(user.mInitialDownVolumeKeyEvent);
                            // Mark that the key is already handled.
                            user.mInitialDownVolumeKeyEvent = null;
                        }
                    }
                    break;
                case MSG_SESSIONS_TOKENS_CHANGED:
                    pushSessionTokensChanged((int) msg.obj);
                    break;
            }
        }

        public void postSessionsChanged(int userId) {
            // Use object instead of the arguments when posting message to remove pending requests.
            Integer userIdInteger = mIntegerCache.get(userId);
            if (userIdInteger == null) {
                userIdInteger = Integer.valueOf(userId);
                mIntegerCache.put(userId, userIdInteger);
            }
            removeMessages(MSG_SESSIONS_CHANGED, userIdInteger);
            obtainMessage(MSG_SESSIONS_CHANGED, userIdInteger).sendToTarget();
        }
    }

    private class ControllerCallback extends MediaController2.ControllerCallback {

        private final SessionToken2 mToken;

        ControllerCallback(SessionToken2 token) {
            mToken = token;
        }

        @Override
        public void onDisconnected(MediaController2 controller) {
            destroySession2Internal(mToken);
        }
    };

    private final class SessionTokensListenerRecord implements IBinder.DeathRecipient {
        private final ISessionTokensListener mListener;
        private final int mUserId;

        public SessionTokensListenerRecord(ISessionTokensListener listener, int userId) {
            mListener = listener;
            // TODO(jaewan): should userId be mapped through mFullUserIds? (b/73597722)
            mUserId = userId;
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mSessionTokensListeners.remove(this);
            }
        }
    }

    private void postSessionTokensUpdated(int userId) {
        mHandler.obtainMessage(MessageHandler.MSG_SESSIONS_TOKENS_CHANGED, userId).sendToTarget();
    }

    private void pushSessionTokensChanged(int userId) {
        synchronized (mLock) {
            List<Bundle> tokens = new ArrayList<>();
            for (SessionToken2 token : mSessionRecords.keySet()) {
                // TODO(jaewan): Remove the check for UserHandle.USER_ALL (shouldn't happen).
                //               This happens when called form buildMediaSessionService2List(...).
                //               (b/73760382)
                if (UserHandle.getUserId(token.getUid()) == userId
                        || UserHandle.USER_ALL == userId) {
                    tokens.add(token.toBundle());
                }
            }

            for (SessionTokensListenerRecord record : mSessionTokensListeners) {
                // TODO(jaewan): Should userId be mapped through mFullUserIds? (b/73760382)
                if (record.mUserId == userId || record.mUserId == UserHandle.USER_ALL) {
                    try {
                        record.mListener.onSessionTokensChanged(tokens);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to notify session tokens changed", e);
                    }
                }
            }
        }
    }

    private boolean addSessionRecordLocked(SessionToken2 token) {
        return addSessionRecordLocked(token, null);
    }

    private boolean addSessionRecordLocked(SessionToken2 token, MediaController2 controller) {
        if (mSessionRecords.containsKey(token) && mSessionRecords.get(token) == controller) {
            // The key/value pair already exists, no need to update.
            return false;
        }

        mSessionRecords.put(token, controller);
        return true;
    }

    private boolean removeSessionRecordLocked(SessionToken2 token) {
        if (!mSessionRecords.containsKey(token)) {
            // The key is already removed, no need to remove.
            return false;
        }

        mSessionRecords.remove(token);
        return true;
    }
}
