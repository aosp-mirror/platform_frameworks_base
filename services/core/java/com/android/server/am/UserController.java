/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.am;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.app.ActivityManager.USER_OP_IS_CURRENT;
import static android.app.ActivityManager.USER_OP_SUCCESS;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_COMPLETE_MSG;
import static com.android.server.am.ActivityManagerService.REPORT_USER_SWITCH_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_CURRENT_MSG;
import static com.android.server.am.ActivityManagerService.SYSTEM_USER_START_MSG;
import static com.android.server.am.ActivityManagerService.USER_SWITCH_TIMEOUT_MSG;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.IStopUserCallback;
import android.app.IUserSwitchObserver;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;
import com.android.server.pm.UserManagerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class for {@link ActivityManagerService} responsible for multi-user functionality.
 */
final class UserController {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "UserController" : TAG_AM;
    // Maximum number of users we allow to be running at a time.
    static final int MAX_RUNNING_USERS = 3;

    // Amount of time we wait for observers to handle a user switch before
    // giving up on them and unfreezing the screen.
    static final int USER_SWITCH_TIMEOUT = 2 * 1000;

    private final ActivityManagerService mService;
    private final Handler mHandler;

    // Holds the current foreground user's id
    int mCurrentUserId = 0;
    // Holds the target user's id during a user switch
    int mTargetUserId = UserHandle.USER_NULL;

    /**
     * Which users have been started, so are allowed to run code.
     */
    private final SparseArray<UserState> mStartedUsers = new SparseArray<>();
    /**
     * LRU list of history of current users.  Most recently current is at the end.
     */
    private final ArrayList<Integer> mUserLru = new ArrayList<>();

    /**
     * Constant array of the users that are currently started.
     */
    private int[] mStartedUserArray = new int[] { 0 };

    // If there are multiple profiles for the current user, their ids are here
    // Currently only the primary user can have managed profiles
    int[] mCurrentProfileIds = new int[] {}; // Accessed by ActivityStack

    /**
     * Mapping from each known user ID to the profile group ID it is associated with.
     */
    private final SparseIntArray mUserProfileGroupIdsSelfLocked = new SparseIntArray();

    /**
     * Registered observers of the user switching mechanics.
     */
    final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers
            = new RemoteCallbackList<>();

    /**
     * Currently active user switch.
     */
    Object mCurUserSwitchCallback;

    UserController(ActivityManagerService service) {
        mService = service;
        mHandler = mService.mHandler;
        // User 0 is the first and only user that runs at boot.
        mStartedUsers.put(UserHandle.USER_SYSTEM, new UserState(UserHandle.SYSTEM, true));
        mUserLru.add(UserHandle.USER_SYSTEM);
        updateStartedUserArrayLocked();
    }

    void finishUserSwitch(UserState uss) {
        synchronized (mService) {
            finishUserBoot(uss);

            startProfilesLocked();

            int num = mUserLru.size();
            int i = 0;
            while (num > MAX_RUNNING_USERS && i < mUserLru.size()) {
                Integer oldUserId = mUserLru.get(i);
                UserState oldUss = mStartedUsers.get(oldUserId);
                if (oldUss == null) {
                    // Shouldn't happen, but be sane if it does.
                    mUserLru.remove(i);
                    num--;
                    continue;
                }
                if (oldUss.mState == UserState.STATE_STOPPING
                        || oldUss.mState == UserState.STATE_SHUTDOWN) {
                    // This user is already stopping, doesn't count.
                    num--;
                    i++;
                    continue;
                }
                if (oldUserId == UserHandle.USER_SYSTEM || oldUserId == mCurrentUserId) {
                    // Owner/System user and current user can't be stopped. We count it as running
                    // when it is not a pure system user.
                    if (UserInfo.isSystemOnly(oldUserId)) {
                        num--;
                    }
                    i++;
                    continue;
                }
                // This is a user to be stopped.
                stopUserLocked(oldUserId, null);
                num--;
                i++;
            }
        }
    }

    void finishUserBoot(UserState uss) {
        synchronized (mService) {
            if (uss.mState == UserState.STATE_BOOTING
                    && mStartedUsers.get(uss.mHandle.getIdentifier()) == uss) {
                uss.mState = UserState.STATE_RUNNING;
                final int userId = uss.mHandle.getIdentifier();
                Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                mService.broadcastIntentLocked(null, null, intent,
                        null, null, 0, null, null,
                        new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                        AppOpsManager.OP_NONE, null, true, false, ActivityManagerService.MY_PID,
                        Process.SYSTEM_UID, userId);
            }
        }
    }

    int stopUser(final int userId, final IStopUserCallback callback) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        if (userId < 0 || userId == UserHandle.USER_SYSTEM) {
            throw new IllegalArgumentException("Can't stop system user " + userId);
        }
        mService.enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES,
                userId);
        synchronized (mService) {
            return stopUserLocked(userId, callback);
        }
    }

    private int stopUserLocked(final int userId, final IStopUserCallback callback) {
        if (DEBUG_MU) Slog.i(TAG, "stopUserLocked userId=" + userId);
        if (mCurrentUserId == userId && mTargetUserId == UserHandle.USER_NULL) {
            return USER_OP_IS_CURRENT;
        }

        final UserState uss = mStartedUsers.get(userId);
        if (uss == null) {
            // User is not started, nothing to do...  but we do need to
            // callback if requested.
            if (callback != null) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            callback.userStopped(userId);
                        } catch (RemoteException e) {
                        }
                    }
                });
            }
            return USER_OP_SUCCESS;
        }

        if (callback != null) {
            uss.mStopCallbacks.add(callback);
        }

        if (uss.mState != UserState.STATE_STOPPING
                && uss.mState != UserState.STATE_SHUTDOWN) {
            uss.mState = UserState.STATE_STOPPING;
            updateStartedUserArrayLocked();

            long ident = Binder.clearCallingIdentity();
            try {
                // We are going to broadcast ACTION_USER_STOPPING and then
                // once that is done send a final ACTION_SHUTDOWN and then
                // stop the user.
                final Intent stoppingIntent = new Intent(Intent.ACTION_USER_STOPPING);
                stoppingIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                stoppingIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                stoppingIntent.putExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, true);
                final Intent shutdownIntent = new Intent(Intent.ACTION_SHUTDOWN);
                // This is the result receiver for the final shutdown broadcast.
                final IIntentReceiver shutdownReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        finishUserStop(uss);
                    }
                };
                // This is the result receiver for the initial stopping broadcast.
                final IIntentReceiver stoppingReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        // On to the next.
                        synchronized (mService) {
                            if (uss.mState != UserState.STATE_STOPPING) {
                                // Whoops, we are being started back up.  Abort, abort!
                                return;
                            }
                            uss.mState = UserState.STATE_SHUTDOWN;
                        }
                        mService.mBatteryStatsService.noteEvent(
                                BatteryStats.HistoryItem.EVENT_USER_RUNNING_FINISH,
                                Integer.toString(userId), userId);
                        mService.mSystemServiceManager.stopUser(userId);
                        mService.broadcastIntentLocked(null, null, shutdownIntent,
                                null, shutdownReceiver, 0, null, null, null, AppOpsManager.OP_NONE,
                                null, true, false, ActivityManagerService.MY_PID,
                                android.os.Process.SYSTEM_UID, userId);
                    }
                };
                // Kick things off.
                mService.broadcastIntentLocked(null, null, stoppingIntent,
                        null, stoppingReceiver, 0, null, null,
                        new String[]{INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                        null, true, false, ActivityManagerService.MY_PID, Process.SYSTEM_UID,
                        UserHandle.USER_ALL);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        return USER_OP_SUCCESS;
    }

    void finishUserStop(UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        boolean stopped;
        ArrayList<IStopUserCallback> callbacks;
        synchronized (mService) {
            callbacks = new ArrayList<>(uss.mStopCallbacks);
            if (mStartedUsers.get(userId) != uss) {
                stopped = false;
            } else if (uss.mState != UserState.STATE_SHUTDOWN) {
                stopped = false;
            } else {
                stopped = true;
                // User can no longer run.
                mStartedUsers.remove(userId);
                mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLocked();

                // Clean up all state and processes associated with the user.
                // Kill all the processes for the user.
                forceStopUserLocked(userId, "finish user");
            }
        }

        for (int i = 0; i < callbacks.size(); i++) {
            try {
                if (stopped) callbacks.get(i).userStopped(userId);
                else callbacks.get(i).userStopAborted(userId);
            } catch (RemoteException e) {
            }
        }

        if (stopped) {
            mService.mSystemServiceManager.cleanupUser(userId);
            synchronized (mService) {
                mService.mStackSupervisor.removeUserLocked(userId);
            }
        }
    }

    private void forceStopUserLocked(int userId, String reason) {
        mService.forceStopPackageLocked(null, -1, false, false, true, false, false,
                userId, reason);
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mService.broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                null, false, false, ActivityManagerService.MY_PID, Process.SYSTEM_UID,
                UserHandle.USER_ALL);
    }


    /**
     * Stops the guest user if it has gone to the background.
     */
    private void stopGuestUserIfBackground() {
        synchronized (mService) {
            final int num = mUserLru.size();
            for (int i = 0; i < num; i++) {
                Integer oldUserId = mUserLru.get(i);
                UserState oldUss = mStartedUsers.get(oldUserId);
                if (oldUserId == UserHandle.USER_SYSTEM || oldUserId == mCurrentUserId
                        || oldUss.mState == UserState.STATE_STOPPING
                        || oldUss.mState == UserState.STATE_SHUTDOWN) {
                    continue;
                }
                UserInfo userInfo = getUserManagerLocked().getUserInfo(oldUserId);
                if (userInfo.isGuest()) {
                    // This is a user to be stopped.
                    stopUserLocked(oldUserId, null);
                    break;
                }
            }
        }
    }

    void startProfilesLocked() {
        if (DEBUG_MU) Slog.i(TAG, "startProfilesLocked");
        List<UserInfo> profiles = getUserManagerLocked().getProfiles(
                mCurrentUserId, false /* enabledOnly */);
        List<UserInfo> profilesToStart = new ArrayList<>(profiles.size());
        for (UserInfo user : profiles) {
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == UserInfo.FLAG_INITIALIZED
                    && user.id != mCurrentUserId) {
                profilesToStart.add(user);
            }
        }
        final int profilesToStartSize = profilesToStart.size();
        int i = 0;
        for (; i < profilesToStartSize && i < (MAX_RUNNING_USERS - 1); ++i) {
            startUser(profilesToStart.get(i).id, /* foreground= */ false);
        }
        if (i < profilesToStartSize) {
            Slog.w(TAG, "More profiles than MAX_RUNNING_USERS");
        }
    }

    private UserManagerService getUserManagerLocked() {
        return mService.getUserManagerLocked();
    }

    boolean startUser(final int userId, final boolean foreground) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: switchUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (DEBUG_MU) Slog.i(TAG, "starting userid:" + userId + " fore:" + foreground);

        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mService) {
                final int oldUserId = mCurrentUserId;
                if (oldUserId == userId) {
                    return true;
                }

                mService.mStackSupervisor.setLockTaskModeLocked(null,
                        ActivityManager.LOCK_TASK_MODE_NONE, "startUser", false);

                final UserInfo userInfo = getUserManagerLocked().getUserInfo(userId);
                if (userInfo == null) {
                    Slog.w(TAG, "No user info for user #" + userId);
                    return false;
                }
                if (foreground && userInfo.isManagedProfile()) {
                    Slog.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                    return false;
                }

                if (foreground) {
                    mService.mWindowManager.startFreezingScreen(
                            R.anim.screen_user_exit, R.anim.screen_user_enter);
                }

                boolean needStart = false;

                // If the user we are switching to is not currently started, then
                // we need to start it now.
                if (mStartedUsers.get(userId) == null) {
                    mStartedUsers.put(userId, new UserState(new UserHandle(userId), false));
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                final Integer userIdInt = userId;
                mUserLru.remove(userIdInt);
                mUserLru.add(userIdInt);

                if (foreground) {
                    mCurrentUserId = userId;
                    mService.updateUserConfigurationLocked();
                    mTargetUserId = UserHandle.USER_NULL; // reset, mCurrentUserId has caught up
                    updateCurrentProfileIdsLocked();
                    mService.mWindowManager.setCurrentUser(userId, mCurrentProfileIds);
                    // Once the internal notion of the active user has switched, we lock the device
                    // with the option to show the user switcher on the keyguard.
                    mService.mWindowManager.lockNow(null);
                } else {
                    final Integer currentUserIdInt = mCurrentUserId;
                    updateCurrentProfileIdsLocked();
                    mService.mWindowManager.setCurrentProfileIds(mCurrentProfileIds);
                    mUserLru.remove(currentUserIdInt);
                    mUserLru.add(currentUserIdInt);
                }

                final UserState uss = mStartedUsers.get(userId);

                // Make sure user is in the started state.  If it is currently
                // stopping, we need to knock that off.
                if (uss.mState == UserState.STATE_STOPPING) {
                    // If we are stopping, we haven't sent ACTION_SHUTDOWN,
                    // so we can just fairly silently bring the user back from
                    // the almost-dead.
                    uss.mState = UserState.STATE_RUNNING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                } else if (uss.mState == UserState.STATE_SHUTDOWN) {
                    // This means ACTION_SHUTDOWN has been sent, so we will
                    // need to treat this as a new boot of the user.
                    uss.mState = UserState.STATE_BOOTING;
                    updateStartedUserArrayLocked();
                    needStart = true;
                }

                if (uss.mState == UserState.STATE_BOOTING) {
                    // Booting up a new user, need to tell system services about it.
                    // Note that this is on the same handler as scheduling of broadcasts,
                    // which is important because it needs to go first.
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_START_MSG, userId, 0));
                }

                if (foreground) {
                    mHandler.sendMessage(mHandler.obtainMessage(SYSTEM_USER_CURRENT_MSG, userId,
                            oldUserId));
                    mHandler.removeMessages(REPORT_USER_SWITCH_MSG);
                    mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
                    mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_MSG,
                            oldUserId, userId, uss));
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(USER_SWITCH_TIMEOUT_MSG,
                            oldUserId, userId, uss), USER_SWITCH_TIMEOUT);
                }

                if (needStart) {
                    // Send USER_STARTED broadcast
                    Intent intent = new Intent(Intent.ACTION_USER_STARTED);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, ActivityManagerService.MY_PID, Process.SYSTEM_UID,
                            userId);
                }

                if ((userInfo.flags&UserInfo.FLAG_INITIALIZED) == 0) {
                    if (userId != UserHandle.USER_SYSTEM) {
                        Intent intent = new Intent(Intent.ACTION_USER_INITIALIZE);
                        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                        mService.broadcastIntentLocked(null, null, intent, null,
                                new IIntentReceiver.Stub() {
                                    public void performReceive(Intent intent, int resultCode,
                                            String data, Bundle extras, boolean ordered,
                                            boolean sticky, int sendingUser) {
                                        onUserInitialized(uss, foreground, oldUserId, userId);
                                    }
                                }, 0, null, null, null, AppOpsManager.OP_NONE,
                                null, true, false, ActivityManagerService.MY_PID,
                                Process.SYSTEM_UID, userId);
                        uss.initializing = true;
                    } else {
                        getUserManagerLocked().makeInitialized(userInfo.id);
                    }
                }

                if (foreground) {
                    if (!uss.initializing) {
                        moveUserToForegroundLocked(uss, oldUserId, userId);
                    }
                } else {
                    mService.mStackSupervisor.startBackgroundUserLocked(userId, uss);
                }

                if (needStart) {
                    Intent intent = new Intent(Intent.ACTION_USER_STARTING);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                    mService.broadcastIntentLocked(null, null, intent,
                            null, new IIntentReceiver.Stub() {
                                @Override
                                public void performReceive(Intent intent, int resultCode,
                                        String data, Bundle extras, boolean ordered, boolean sticky,
                                        int sendingUser) throws RemoteException {
                                }
                            }, 0, null, null,
                            new String[]{INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                            null, true, false, ActivityManagerService.MY_PID, Process.SYSTEM_UID,
                            UserHandle.USER_ALL);
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return true;
    }

    void dispatchForegroundProfileChanged(int userId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                mUserSwitchObservers.getBroadcastItem(i).onForegroundProfileSwitch(userId);
            } catch (RemoteException e) {
                // Ignore
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    /** Called on handler thread */
    void dispatchUserSwitchComplete(int userId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                mUserSwitchObservers.getBroadcastItem(i).onUserSwitchComplete(userId);
            } catch (RemoteException e) {
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    void timeoutUserSwitch(UserState uss, int oldUserId, int newUserId) {
        synchronized (mService) {
            Slog.w(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId);
            sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
        }
    }

    void dispatchUserSwitch(final UserState uss, final int oldUserId,
            final int newUserId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        if (observerCount > 0) {
            final IRemoteCallback callback = new IRemoteCallback.Stub() {
                int mCount = 0;
                @Override
                public void sendResult(Bundle data) throws RemoteException {
                    synchronized (mService) {
                        if (mCurUserSwitchCallback == this) {
                            mCount++;
                            if (mCount == observerCount) {
                                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
                            }
                        }
                    }
                }
            };
            synchronized (mService) {
                uss.switching = true;
                mCurUserSwitchCallback = callback;
            }
            for (int i = 0; i < observerCount; i++) {
                try {
                    mUserSwitchObservers.getBroadcastItem(i).onUserSwitching(
                            newUserId, callback);
                } catch (RemoteException e) {
                }
            }
        } else {
            synchronized (mService) {
                sendContinueUserSwitchLocked(uss, oldUserId, newUserId);
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    void sendContinueUserSwitchLocked(UserState uss, int oldUserId, int newUserId) {
        mCurUserSwitchCallback = null;
        mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(ActivityManagerService.CONTINUE_USER_SWITCH_MSG,
                oldUserId, newUserId, uss));
    }

    void continueUserSwitch(UserState uss, int oldUserId, int newUserId) {
        completeSwitchAndInitialize(uss, newUserId, false, true);
    }

    void onUserInitialized(UserState uss, boolean foreground, int oldUserId, int newUserId) {
        synchronized (mService) {
            if (foreground) {
                moveUserToForegroundLocked(uss, oldUserId, newUserId);
            }
        }
        completeSwitchAndInitialize(uss, newUserId, true, false);
    }

    void completeSwitchAndInitialize(UserState uss, int newUserId,
            boolean clearInitializing, boolean clearSwitching) {
        boolean unfrozen = false;
        synchronized (mService) {
            if (clearInitializing) {
                uss.initializing = false;
                getUserManagerLocked().makeInitialized(uss.mHandle.getIdentifier());
            }
            if (clearSwitching) {
                uss.switching = false;
            }
            if (!uss.switching && !uss.initializing) {
                mService.mWindowManager.stopFreezingScreen();
                unfrozen = true;
            }
        }
        if (unfrozen) {
            mHandler.removeMessages(REPORT_USER_SWITCH_COMPLETE_MSG);
            mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_COMPLETE_MSG,
                    newUserId, 0));
        }
        stopGuestUserIfBackground();
    }

    void moveUserToForegroundLocked(UserState uss, int oldUserId, int newUserId) {
        boolean homeInFront = mService.mStackSupervisor.switchUserLocked(newUserId, uss);
        if (homeInFront) {
            mService.startHomeActivityLocked(newUserId, "moveUserToForeground");
        } else {
            mService.mStackSupervisor.resumeTopActivitiesLocked();
        }
        EventLogTags.writeAmSwitchUser(newUserId);
        getUserManagerLocked().onUserForeground(newUserId);
        mService.sendUserSwitchBroadcastsLocked(oldUserId, newUserId);
    }

    void registerUserSwitchObserver(IUserSwitchObserver observer) {
        if (mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED) {
            final String msg = "Permission Denial: registerUserSwitchObserver() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS_FULL;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        mUserSwitchObservers.register(observer);
    }

    void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        mUserSwitchObservers.unregister(observer);
    }

    UserState getStartedUserState(int userId) {
        return mStartedUsers.get(userId);
    }

    boolean hasStartedUserState(int userId) {
        return mStartedUsers.get(userId) != null;
    }

    private void updateStartedUserArrayLocked() {
        int num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            // This list does not include stopping users.
            if (uss.mState != UserState.STATE_STOPPING
                    && uss.mState != UserState.STATE_SHUTDOWN) {
                num++;
            }
        }
        mStartedUserArray = new int[num];
        num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            if (uss.mState != UserState.STATE_STOPPING
                    && uss.mState != UserState.STATE_SHUTDOWN) {
                mStartedUserArray[num] = mStartedUsers.keyAt(i);
                num++;
            }
        }
    }

    void sendBootCompletedLocked(IIntentReceiver resultTo) {
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            if (uss.mState == UserState.STATE_BOOTING) {
                uss.mState = UserState.STATE_RUNNING;
                final int userId = mStartedUsers.keyAt(i);
                Intent intent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT);
                mService.broadcastIntentLocked(null, null, intent, null,
                        resultTo, 0, null, null,
                        new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                        AppOpsManager.OP_NONE, null, true, false,
                        ActivityManagerService.MY_PID, Process.SYSTEM_UID, userId);
            }
        }
    }

    /**
     * Refreshes the list of users related to the current user when either a
     * user switch happens or when a new related user is started in the
     * background.
     */
    void updateCurrentProfileIdsLocked() {
        final List<UserInfo> profiles = getUserManagerLocked().getProfiles(mCurrentUserId,
                false /* enabledOnly */);
        int[] currentProfileIds = new int[profiles.size()]; // profiles will not be null
        for (int i = 0; i < currentProfileIds.length; i++) {
            currentProfileIds[i] = profiles.get(i).id;
        }
        mCurrentProfileIds = currentProfileIds;

        synchronized (mUserProfileGroupIdsSelfLocked) {
            mUserProfileGroupIdsSelfLocked.clear();
            final List<UserInfo> users = getUserManagerLocked().getUsers(false);
            for (int i = 0; i < users.size(); i++) {
                UserInfo user = users.get(i);
                if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                    mUserProfileGroupIdsSelfLocked.put(user.id, user.profileGroupId);
                }
            }
        }
    }

    int[] getStartedUserArrayLocked() {
        return mStartedUserArray;
    }

    UserInfo getCurrentUser() {
        if ((mService.checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) && (
                mService.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                        != PackageManager.PERMISSION_GRANTED)) {
            String msg = "Permission Denial: getCurrentUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        synchronized (mService) {
            return getCurrentUserLocked();
        }
    }

    UserInfo getCurrentUserLocked() {
        int userId = mTargetUserId != UserHandle.USER_NULL ? mTargetUserId : mCurrentUserId;
        return getUserManagerLocked().getUserInfo(userId);
    }

    int getCurrentUserIdLocked() {
        return mTargetUserId != UserHandle.USER_NULL ? mTargetUserId : mCurrentUserId;
    }

    boolean isSameProfileGroup(int callingUserId, int targetUserId) {
        synchronized (mUserProfileGroupIdsSelfLocked) {
            int callingProfile = mUserProfileGroupIdsSelfLocked.get(callingUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            int targetProfile = mUserProfileGroupIdsSelfLocked.get(targetUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            return callingProfile != UserInfo.NO_PROFILE_GROUP_ID
                    && callingProfile == targetProfile;
        }
    }

    boolean isCurrentProfileLocked(int userId) {
        return ArrayUtils.contains(mCurrentProfileIds, userId);
    }

    void dump(PrintWriter pw, boolean dumpAll) {
        pw.println("  mStartedUsers:");
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            pw.print("    User #"); pw.print(uss.mHandle.getIdentifier());
            pw.print(": "); uss.dump("", pw);
        }
        pw.print("  mStartedUserArray: [");
        for (int i = 0; i < mStartedUserArray.length; i++) {
            if (i > 0) pw.print(", ");
            pw.print(mStartedUserArray[i]);
        }
        pw.println("]");
        pw.print("  mUserLru: [");
        for (int i = 0; i < mUserLru.size(); i++) {
            if (i > 0) pw.print(", ");
            pw.print(mUserLru.get(i));
        }
        pw.println("]");
        if (dumpAll) {
            pw.print("  mStartedUserArray: "); pw.println(Arrays.toString(mStartedUserArray));
        }
        synchronized (mUserProfileGroupIdsSelfLocked) {
            if (mUserProfileGroupIdsSelfLocked.size() > 0) {
                pw.println("  mUserProfileGroupIds:");
                for (int i=0; i<mUserProfileGroupIdsSelfLocked.size(); i++) {
                    pw.print("    User #");
                    pw.print(mUserProfileGroupIdsSelfLocked.keyAt(i));
                    pw.print(" -> profile #");
                    pw.println(mUserProfileGroupIdsSelfLocked.valueAt(i));
                }
            }
        }
    }

}
