/*
 * Copyright 2020 The Android Open Source Project
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
import static android.os.UserHandle.getUserHandleForUid;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.IMediaCommunicationService;
import android.media.IMediaCommunicationServiceCallback;
import android.media.MediaController2;
import android.media.MediaParceledListSlice;
import android.media.Session2CommandGroup;
import android.media.Session2Token;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * A system service that manages {@link android.media.MediaSession2} creations
 * and their ongoing media playback state.
 * @hide
 */
public class MediaCommunicationService extends SystemService {
    private static final String TAG = "MediaCommunicationService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    final Context mContext;

    private final Object mLock = new Object();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @GuardedBy("mLock")
    private final SparseIntArray mFullUserIds = new SparseIntArray();
    @GuardedBy("mLock")
    private final SparseArray<FullUserRecord> mUserRecords = new SparseArray<>();

    private final Executor mRecordExecutor = Executors.newSingleThreadExecutor();
    @GuardedBy("mLock")
    private final List<CallbackRecord> mCallbackRecords = new ArrayList<>();
    final NotificationManager mNotificationManager;

    public MediaCommunicationService(Context context) {
        super(context);
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_COMMUNICATION_SERVICE, new Stub());
        updateUser();
    }

    @Override
    public void onUserStarting(@NonNull TargetUser user) {
        if (DEBUG) Log.d(TAG, "onUserStarting: " + user);
        updateUser();
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        if (DEBUG) Log.d(TAG, "onUserSwitching: " + to);
        updateUser();
    }

    @Override
    public void onUserStopped(@NonNull TargetUser targetUser) {
        int userId = targetUser.getUserHandle().getIdentifier();

        if (DEBUG) Log.d(TAG, "onUserStopped: " + userId);
        synchronized (mLock) {
            FullUserRecord user = getFullUserRecordLocked(userId);
            if (user != null) {
                if (user.getFullUserId() == userId) {
                    user.destroySessionsForUserLocked(UserHandle.ALL.getIdentifier());
                    mUserRecords.remove(userId);
                } else {
                    user.destroySessionsForUserLocked(userId);
                }
            }
            updateUser();
        }
    }

    @Nullable
    CallbackRecord findCallbackRecordLocked(@Nullable IMediaCommunicationServiceCallback callback) {
        if (callback == null) {
            return null;
        }
        for (CallbackRecord record : mCallbackRecords) {
            if (Objects.equals(callback.asBinder(), record.mCallback.asBinder())) {
                return record;
            }
        }
        return null;
    }

    private FullUserRecord getFullUserRecordLocked(int userId) {
        int fullUserId = mFullUserIds.get(userId, -1);
        if (fullUserId < 0) {
            return null;
        }
        return mUserRecords.get(fullUserId);
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

    private void updateUser() {
        UserManager manager = mContext.getSystemService(UserManager.class);
        List<UserHandle> allUsers = manager.getUserHandles(/*excludeDying=*/false);

        synchronized (mLock) {
            mFullUserIds.clear();
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
            FullUserRecord currentFullUserRecord = mUserRecords.get(currentFullUserId);
            if (currentFullUserRecord == null) {
                Log.w(TAG, "Cannot find FullUserInfo for the current user " + currentFullUserId);
                currentFullUserRecord = new FullUserRecord(currentFullUserId);
                mUserRecords.put(currentFullUserId, currentFullUserRecord);
            }
            mFullUserIds.put(currentFullUserId, currentFullUserId);
        }
    }

    void dispatchSessionCreated(Session2Token token) {
        for (CallbackRecord record : mCallbackRecords) {
            if (record.mUserId != ALL.getIdentifier()
                    && record.mUserId != getUserHandleForUid(token.getUid()).getIdentifier()) {
                continue;
            }
            try {
                record.mCallback.onSession2Created(token);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    void onSessionDied(Session2Record record) {
        synchronized (mLock) {
            destroySessionLocked(record);
        }
    }

    private void destroySessionLocked(Session2Record session) {
        if (DEBUG) {
            Log.d(TAG, "Destroying " + session);
        }
        if (session.isClosed()) {
            Log.w(TAG, "Destroying already destroyed session. Ignoring.");
            return;
        }

        FullUserRecord user = getFullUserRecordLocked(session.getUserId());

        if (user != null) {
            user.removeSession(session);
        }

        session.close();
    }

    private class Stub extends IMediaCommunicationService.Stub {
        @Override
        public void notifySession2Created(Session2Token sessionToken) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                if (DEBUG) {
                    Log.d(TAG, "Session2 is created " + sessionToken);
                }
                if (uid != sessionToken.getUid()) {
                    throw new SecurityException("Unexpected Session2Token's UID, expected=" + uid
                            + " but actually=" + sessionToken.getUid());
                }
                synchronized (mLock) {
                    int userId = getUserHandleForUid(sessionToken.getUid()).getIdentifier();
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    if (user == null) {
                        Log.w(TAG, "notifySession2Created: Ignore session of an unknown user");
                        return;
                    }
                    user.addSession(new Session2Record(MediaCommunicationService.this,
                            sessionToken, mRecordExecutor));
                    mHandler.post(() -> dispatchSessionCreated(sessionToken));
                }
            } finally {
                Binder.restoreCallingIdentity(token);
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
        public MediaParceledListSlice getSession2Tokens(int userId) {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();

            try {
                // Check that they can make calls on behalf of the user and get the final user id
                int resolvedUserId = handleIncomingUser(pid, uid, userId, null);
                List<Session2Token> result;
                synchronized (mLock) {
                    FullUserRecord user = getFullUserRecordLocked(userId);
                    result = user.getSession2Tokens(resolvedUserId);
                }
                return new MediaParceledListSlice(result);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void registerCallback(IMediaCommunicationServiceCallback callback,
                String packageName) throws RemoteException {
            Objects.requireNonNull(callback, "callback should not be null");
            Objects.requireNonNull(packageName, "packageName should not be null");

            synchronized (mLock) {
                if (findCallbackRecordLocked(callback) == null) {

                    CallbackRecord record = new CallbackRecord(callback, packageName,
                            Binder.getCallingUid(), Binder.getCallingPid());
                    mCallbackRecords.add(record);
                    try {
                        callback.asBinder().linkToDeath(record, 0);
                    } catch (RemoteException e) {
                        Log.w(TAG, "Failed to register callback", e);
                        mCallbackRecords.remove(record);
                    }
                } else {
                    Log.e(TAG, "registerCallback is called with already registered callback. "
                            + "packageName=" + packageName);
                }
            }
        }

        @Override
        public void unregisterCallback(IMediaCommunicationServiceCallback callback)
                throws RemoteException {
            synchronized (mLock) {
                CallbackRecord existingRecord = findCallbackRecordLocked(callback);
                if (existingRecord != null) {
                    mCallbackRecords.remove(existingRecord);
                    callback.asBinder().unlinkToDeath(existingRecord, 0);
                } else {
                    Log.e(TAG, "unregisterCallback is called with unregistered callback.");
                }
            }
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
                if (userId == UserHandle.CURRENT.getIdentifier()) {
                    return ActivityManager.getCurrentUser();
                }
                return userId;
            }

            throw new SecurityException("Permission denied while calling from " + packageName
                    + " with user id: " + userId + "; Need to run as either the calling user id ("
                    + callingUserId + "), or with " + INTERACT_ACROSS_USERS_FULL + " permission");
        }
    }

    final class CallbackRecord implements IBinder.DeathRecipient {
        private final IMediaCommunicationServiceCallback mCallback;
        private final String mPackageName;
        private final int mUid;
        private int mPid;
        private final int mUserId;

        CallbackRecord(IMediaCommunicationServiceCallback callback,
                String packageName, int uid, int pid) {
            mCallback = callback;
            mPackageName = packageName;
            mUid = uid;
            mPid = pid;
            mUserId = (mContext.checkPermission(
                    INTERACT_ACROSS_USERS_FULL, pid, uid) == PackageManager.PERMISSION_GRANTED)
                    ? ALL.getIdentifier() : UserHandle.getUserHandleForUid(mUid).getIdentifier();
        }

        @Override
        public String toString() {
            return "CallbackRecord[callback=" + mCallback + ", pkg=" + mPackageName
                    + ", uid=" + mUid + ", pid=" + mPid + "]";
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                mCallbackRecords.remove(this);
            }
        }
    }

    final class FullUserRecord {
        private final int mFullUserId;
        /** Sorted list of media sessions */
        private final List<Session2Record> mSessionRecords = new ArrayList<>();

        FullUserRecord(int fullUserId) {
            mFullUserId = fullUserId;
        }

        public void addSession(Session2Record record) {
            mSessionRecords.add(record);
        }

        public void removeSession(Session2Record record) {
            mSessionRecords.remove(record);
            //TODO: Handle if the removed session was the media button session.
        }

        public int getFullUserId() {
            return mFullUserId;
        }

        public List<Session2Token> getSession2Tokens(int userId) {
            return mSessionRecords.stream()
                    .filter(record -> record.isActive()
                            && (userId == UserHandle.ALL.getIdentifier()
                                    || record.getUserId() == userId))
                    .map(Session2Record::getSessionToken)
                    .collect(Collectors.toList());
        }

        public void destroySessionsForUserLocked(int userId) {
            synchronized (mLock) {
                for (Session2Record record : mSessionRecords) {
                    if (userId == UserHandle.ALL.getIdentifier()
                            || record.getUserId() == userId) {
                        destroySessionLocked(record);
                    }
                }
            }
        }
    }

    static final class Session2Record {
        private final Session2Token mSessionToken;
        private final Object mLock = new Object();
        private final WeakReference<MediaCommunicationService> mServiceRef;
        @GuardedBy("mLock")
        private final MediaController2 mController;

        @GuardedBy("mLock")
        private boolean mIsConnected;
        @GuardedBy("mLock")
        private boolean mIsClosed;

        Session2Record(MediaCommunicationService service, Session2Token token,
                Executor controllerExecutor) {
            mServiceRef = new WeakReference<>(service);
            mSessionToken = token;
            mController = new MediaController2.Builder(service.getContext(), token)
                    .setControllerCallback(controllerExecutor, new Controller2Callback())
                    .build();
        }

        public int getUserId() {
            return UserHandle.getUserHandleForUid(mSessionToken.getUid()).getIdentifier();
        }

        public boolean isActive() {
            synchronized (mLock) {
                return mIsConnected;
            }
        }

        public boolean isClosed() {
            synchronized (mLock) {
                return mIsClosed;
            }
        }

        public void close() {
            synchronized (mLock) {
                mIsClosed = true;
                // Call close regardless of the mIsConnected. This may be called when it's not yet
                // connected.
                mController.close();
            }
        }

        public Session2Token getSessionToken() {
            return mSessionToken;
        }

        private class Controller2Callback extends MediaController2.ControllerCallback {
            @Override
            public void onConnected(MediaController2 controller,
                    Session2CommandGroup allowedCommands) {
                if (DEBUG) {
                    Log.d(TAG, "connected to " + mSessionToken + ", allowed=" + allowedCommands);
                }
                synchronized (mLock) {
                    mIsConnected = true;
                }
                MediaCommunicationService service = mServiceRef.get();
                if (service != null) {
                    //TODO: notify session state changed
                }
            }

            @Override
            public void onDisconnected(MediaController2 controller) {
                if (DEBUG) {
                    Log.d(TAG, "disconnected from " + mSessionToken);
                }
                synchronized (mLock) {
                    mIsConnected = false;
                }
                MediaCommunicationService service = mServiceRef.get();
                if (service != null) {
                    service.onSessionDied(Session2Record.this);
                }
            }
        }
    }
}
