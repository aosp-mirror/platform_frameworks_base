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

import static android.Manifest.permission.INTERACT_ACROSS_PROFILES;
import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.Manifest.permission.MANAGE_USERS;
import static android.app.ActivityManager.STOP_USER_ON_SWITCH_DEFAULT;
import static android.app.ActivityManager.STOP_USER_ON_SWITCH_TRUE;
import static android.app.ActivityManager.StopUserOnSwitch;
import static android.app.ActivityManager.USER_OP_ERROR_IS_SYSTEM;
import static android.app.ActivityManager.USER_OP_ERROR_RELATED_USERS_CANNOT_STOP;
import static android.app.ActivityManager.USER_OP_IS_CURRENT;
import static android.app.ActivityManager.USER_OP_SUCCESS;
import static android.app.ActivityManagerInternal.ALLOW_FULL_ONLY;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL;
import static android.app.ActivityManagerInternal.ALLOW_NON_FULL_IN_PROFILE;
import static android.app.ActivityManagerInternal.ALLOW_PROFILES_OR_NON_FULL;
import static android.os.PowerWhitelistManager.REASON_BOOT_COMPLETED;
import static android.os.PowerWhitelistManager.REASON_LOCKED_BOOT_COMPLETED;
import static android.os.PowerWhitelistManager.TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED;
import static android.os.Process.SHELL_UID;
import static android.os.Process.SYSTEM_UID;

import static com.android.internal.util.FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__FRAMEWORK_LOCKED_BOOT_COMPLETED;
import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_MU;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.am.ActivityManagerService.MY_PID;
import static com.android.server.am.UserState.STATE_BOOTING;
import static com.android.server.am.UserState.STATE_RUNNING_LOCKED;
import static com.android.server.am.UserState.STATE_RUNNING_UNLOCKED;
import static com.android.server.am.UserState.STATE_RUNNING_UNLOCKING;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_ABORTED;
import static com.android.server.pm.UserJourneyLogger.ERROR_CODE_INVALID_SESSION_ID;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_BEGIN;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_FINISH;
import static com.android.server.pm.UserJourneyLogger.EVENT_STATE_NONE;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_START;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_STOP;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_SWITCH_FG;
import static com.android.server.pm.UserJourneyLogger.USER_JOURNEY_USER_SWITCH_UI;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_UNLOCKED_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_UNLOCKING_USER;
import static com.android.server.pm.UserJourneyLogger.USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_BACKGROUND_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_START_MODE_FOREGROUND;
import static com.android.server.pm.UserManagerInternal.userAssignmentResultToString;
import static com.android.server.pm.UserManagerInternal.userStartModeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.IStopUserCallback;
import android.app.IUserSwitchObserver;
import android.app.KeyguardManager;
import android.app.usage.UsageEvents;
import android.appwidget.AppWidgetManagerInternal;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.PermissionChecker;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackagePartitions;
import android.content.pm.UserInfo;
import android.content.pm.UserProperties;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.IRemoteCallback;
import android.os.IUserManager;
import android.os.Message;
import android.os.PowerManagerInternal;
import android.os.PowerWhitelistManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.IntArray;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.proto.ProtoOutputStream;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ObjectUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.widget.LockPatternUtils;
import com.android.server.FactoryResetter;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService.UserCompletedEventType;
import com.android.server.SystemServiceManager;
import com.android.server.am.UserState.KeyEvictedCallback;
import com.android.server.pm.UserJourneyLogger;
import com.android.server.pm.UserJourneyLogger.UserJourneySession;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.UserManagerInternal.UserLifecycleListener;
import com.android.server.pm.UserManagerInternal.UserStartMode;
import com.android.server.pm.UserManagerService;
import com.android.server.utils.Slogf;
import com.android.server.utils.TimingsTraceAndSlog;
import com.android.server.wm.ActivityTaskManagerInternal;
import com.android.server.wm.WindowManagerService;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Helper class for {@link ActivityManagerService} responsible for multi-user functionality.
 *
 * <p>This class use {@link #mLock} to synchronize access to internal state. Methods that require
 * {@link #mLock} to be held should have "LU" suffix in the name.
 *
 * <p><strong>Important:</strong> Synchronized code, i.e. one executed inside a synchronized(mLock)
 * block or inside LU method, should only access internal state of this class or make calls to
 * other LU methods. Non-LU method calls or calls to external classes are discouraged as they
 * may cause lock inversion.
 */
class UserController implements Handler.Callback {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "UserController" : TAG_AM;

    // Amount of time we wait for observers to handle a user switch before
    // giving up on them and dismissing the user switching dialog.
    static final int DEFAULT_USER_SWITCH_TIMEOUT_MS = 3 * 1000;

    /**
     * Amount of time we wait for an observer to handle a user switch before we log a warning. This
     * wait time is per observer.
     */
    private static final int LONG_USER_SWITCH_OBSERVER_WARNING_TIME_MS = 500;

    // ActivityManager thread message constants
    static final int REPORT_USER_SWITCH_MSG = 10;
    static final int CONTINUE_USER_SWITCH_MSG = 20;
    static final int USER_SWITCH_TIMEOUT_MSG = 30;
    static final int START_PROFILES_MSG = 40;
    static final int USER_START_MSG = 50;
    static final int USER_CURRENT_MSG = 60;
    static final int FOREGROUND_PROFILE_CHANGED_MSG = 70;
    static final int REPORT_USER_SWITCH_COMPLETE_MSG = 80;
    static final int USER_SWITCH_CALLBACKS_TIMEOUT_MSG = 90;
    static final int USER_UNLOCK_MSG = 100;
    static final int USER_UNLOCKED_MSG = 105;
    static final int REPORT_LOCKED_BOOT_COMPLETE_MSG = 110;
    static final int START_USER_SWITCH_FG_MSG = 120;
    static final int COMPLETE_USER_SWITCH_MSG = 130;
    static final int USER_COMPLETED_EVENT_MSG = 140;

    private static final int NO_ARG2 = 0;

    // Message constant to clear {@link UserJourneySession} from {@link mUserIdToUserJourneyMap} if
    // the user journey, defined in the UserLifecycleJourneyReported atom for statsd, is not
    // complete within {@link USER_JOURNEY_TIMEOUT}.
    static final int CLEAR_USER_JOURNEY_SESSION_MSG = 200;
    // Wait time for completing the user journey. If a user journey is not complete within this
    // time, the remaining lifecycle events for the journey would not be logged in statsd.
    // Timeout set for 90 seconds.
    private static final int USER_JOURNEY_TIMEOUT_MS = 90_000;

    // UI thread message constants
    static final int START_USER_SWITCH_UI_MSG = 1000;

    /**
     * If a callback wasn't called within USER_SWITCH_CALLBACKS_TIMEOUT_MS after
     * {@link #getUserSwitchTimeoutMs}, an error is reported. Usually it indicates a problem in the
     * observer when it never calls back.
     */
    private static final int USER_SWITCH_CALLBACKS_TIMEOUT_MS = 5 * 1000;

    /**
     * Amount of time waited for
     * {@link ActivityTaskManagerInternal.ScreenObserver#onKeyguardStateChanged} callbacks to be
     * called after calling {@link WindowManagerService#lockDeviceNow}.
     * Otherwise, we should throw a {@link RuntimeException} and never dismiss the
     * {@link UserSwitchingDialog}.
     */
    static final int SHOW_KEYGUARD_TIMEOUT_MS = 20 * 1000;

    /**
     * Amount of time waited for {@link WindowManagerService#dismissKeyguard} callbacks to be
     * called after dismissing the keyguard.
     * Otherwise, we should move on to dismiss the dialog {@link #dismissUserSwitchDialog}}
     * and report user switch is complete {@link #REPORT_USER_SWITCH_COMPLETE_MSG}.
     */
    private static final int DISMISS_KEYGUARD_TIMEOUT_MS = 2 * 1000;

    /**
     * Time after last scheduleOnUserCompletedEvent() call at which USER_COMPLETED_EVENT_MSG will be
     * scheduled (although it may fire sooner instead).
     * When it fires, {@link #reportOnUserCompletedEvent} will be processed.
     */
    // TODO(b/197344658): Increase to 10s or 15s once we have a switch-UX-is-done invocation too.
    private static final int USER_COMPLETED_EVENT_DELAY_MS = 5 * 1000;

    /**
     * Maximum number of users we allow to be running at a time, including system user.
     *
     * <p>This parameter only affects how many background users will be stopped when switching to a
     * new user. It has no impact on {@link #startUser(int, boolean)} behavior.
     *
     * <p>Note: Current and system user (and their related profiles) are never stopped when
     * switching users. Due to that, the actual number of running users can exceed mMaxRunningUsers
     */
    @GuardedBy("mLock")
    private int mMaxRunningUsers;

    // Lock for internal state.
    private final Object mLock = new Object();

    private final Injector mInjector;
    private final Handler mHandler;
    private final Handler mUiHandler;

    // Holds the current foreground user's id. Use mLock when updating
    @GuardedBy("mLock")
    private volatile int mCurrentUserId = UserHandle.USER_SYSTEM;
    // Holds the target user's id during a user switch. The value of mCurrentUserId will be updated
    // once target user goes into the foreground. Use mLock when updating
    @GuardedBy("mLock")
    private volatile int mTargetUserId = UserHandle.USER_NULL;
    // If a user switch request comes during an ongoing user switch, it is postponed to the end of
    // the current switch, and this variable holds those user ids. Use mLock when updating
    @GuardedBy("mLock")
    private final ArrayDeque<Integer> mPendingTargetUserIds = new ArrayDeque<>();

    /**
     * Which users have been started, so are allowed to run code.
     */
    @GuardedBy("mLock")
    private final SparseArray<UserState> mStartedUsers = new SparseArray<>();

    /**
     * LRU list of history of current users.  Most recently current is at the end.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mUserLru = new ArrayList<>();

    /**
     * Constant array of the users that are currently started.
     */
    @GuardedBy("mLock")
    private int[] mStartedUserArray = new int[] { 0 };

    /**
     * Contains the current user and its profiles (if any).
     *
     * <p><b>NOTE: </b>it lists all profiles, regardless of their running state (i.e., they're in
     * this list even if not running).
     */
    @GuardedBy("mLock")
    private int[] mCurrentProfileIds = new int[] {};

    /**
     * Mapping from each known user ID to the profile group ID it is associated with.
     * <p>Users not present in this array have a profile group of NO_PROFILE_GROUP_ID.
     */
    @GuardedBy("mLock")
    private final SparseIntArray mUserProfileGroupIds = new SparseIntArray();

    /**
     * Registered observers of the user switching mechanics.
     */
    private final RemoteCallbackList<IUserSwitchObserver> mUserSwitchObservers
            = new RemoteCallbackList<>();

    @GuardedBy("mLock")
    private boolean mUserSwitchUiEnabled = true;

    /**
     * Currently active user switch callbacks.
     */
    @GuardedBy("mLock")
    private volatile ArraySet<String> mCurWaitingUserSwitchCallbacks;

    /**
     * Messages for switching from {@link android.os.UserHandle#SYSTEM}.
     */
    @GuardedBy("mLock")
    private String mSwitchingFromSystemUserMessage;

    /**
     * Messages for switching to {@link android.os.UserHandle#SYSTEM}.
     */
    @GuardedBy("mLock")
    private String mSwitchingToSystemUserMessage;

    /**
     * Callbacks that are still active after {@link #getUserSwitchTimeoutMs}
     */
    @GuardedBy("mLock")
    private ArraySet<String> mTimeoutUserSwitchCallbacks;

    private final LockPatternUtils mLockPatternUtils;

    // TODO(b/266158156): remove this once we improve/refactor the way broadcasts are sent for
    //  the system user in HSUM.
    @GuardedBy("mLock")
    private boolean mIsBroadcastSentForSystemUserStarted;

    // TODO(b/266158156): remove this once we improve/refactor the way broadcasts are sent for
    //  the system user in HSUM.
    @GuardedBy("mLock")
    private boolean mIsBroadcastSentForSystemUserStarting;

    volatile boolean mBootCompleted;

    /**
     * In this mode, user is always stopped when switched out (unless overridden by the
     * {@code fw.stop_bg_users_on_switch} system property) but locking of user data is
     * postponed until total number of unlocked users in the system reaches mMaxRunningUsers.
     * Once total number of unlocked users reach mMaxRunningUsers, least recently used user
     * will be locked.
     */
    // TODO(b/302662311): Add javadoc changes corresponding to the user property that allows
    // delayed locking behavior once the private space flag is finalized.
    @GuardedBy("mLock")
    private boolean mDelayUserDataLocking;

    /**
     * Users are only allowed to be unlocked after boot complete.
     */
    private volatile boolean mAllowUserUnlocking;

    /**
     * Keep track of last active users for delayUserDataLocking.
     * The most recently stopped user with delayed locking is placed in front, while the least
     * recently stopped user in back.
     */
    @GuardedBy("mLock")
    private final ArrayList<Integer> mLastActiveUsersForDelayedLocking = new ArrayList<>();

    /**
     * Map of userId to {@link UserCompletedEventType} event flags, indicating which as-yet-
     * unreported user-starting events have transpired for the given user.
     */
    @GuardedBy("mCompletedEventTypes")
    private final SparseIntArray mCompletedEventTypes = new SparseIntArray();

    /**
     * Sets on {@link #setInitialConfig(boolean, int, boolean)}, which is called by
     * {@code ActivityManager} when the system is started.
     *
     * <p>It's useful to ignore external operations (i.e., originated outside {@code system_server},
     * like from {@code adb shell am switch-user})) that could happen before such call is made and
     * the system is ready.
     */
    @GuardedBy("mLock")
    private boolean mInitialized;

    /**
     * Defines the behavior of whether the background users should be stopped when the foreground
     * user is switched.
     */
    @GuardedBy("mLock")
    private @StopUserOnSwitch int mStopUserOnSwitch = STOP_USER_ON_SWITCH_DEFAULT;

    /** @see #getLastUserUnlockingUptime */
    private volatile long mLastUserUnlockingUptime = 0;

    /**
     * Pending user starts waiting for shutdown step to complete.
     */
    @GuardedBy("mLock")
    private final List<PendingUserStart> mPendingUserStarts = new ArrayList<>();

    private final UserLifecycleListener mUserLifecycleListener = new UserLifecycleListener() {
        @Override
        public void onUserCreated(UserInfo user, Object token) {
            onUserAdded(user);
        }

        @Override
        public void onUserRemoved(UserInfo user) {
            UserController.this.onUserRemoved(user.id);
        }
    };

    UserController(ActivityManagerService service) {
        this(new Injector(service));
    }

    @VisibleForTesting
    UserController(Injector injector) {
        mInjector = injector;
        // This should be called early to avoid a null mHandler inside the injector
        mHandler = mInjector.getHandler(this);
        mUiHandler = mInjector.getUiHandler(this);
        // User 0 is the first and only user that runs at boot.
        final UserState uss = new UserState(UserHandle.SYSTEM);
        uss.mUnlockProgress.addListener(new UserProgressListener());
        mStartedUsers.put(UserHandle.USER_SYSTEM, uss);
        mUserLru.add(UserHandle.USER_SYSTEM);
        mLockPatternUtils = mInjector.getLockPatternUtils();
        updateStartedUserArrayLU();
    }

    void setInitialConfig(boolean userSwitchUiEnabled, int maxRunningUsers,
            boolean delayUserDataLocking) {
        synchronized (mLock) {
            mUserSwitchUiEnabled = userSwitchUiEnabled;
            mMaxRunningUsers = maxRunningUsers;
            mDelayUserDataLocking = delayUserDataLocking;
            mInitialized = true;
        }
    }

    private boolean isUserSwitchUiEnabled() {
        synchronized (mLock) {
            return mUserSwitchUiEnabled;
        }
    }

    int getMaxRunningUsers() {
        synchronized (mLock) {
            return mMaxRunningUsers;
        }
    }

    void setStopUserOnSwitch(@StopUserOnSwitch int value) {
        if (mInjector.checkCallingPermission(android.Manifest.permission.MANAGE_USERS)
                == PackageManager.PERMISSION_DENIED && mInjector.checkCallingPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or INTERACT_ACROSS_USERS permission to "
                            + "call setStopUserOnSwitch()");
        }

        synchronized (mLock) {
            Slogf.i(TAG, "setStopUserOnSwitch(): %d -> %d", mStopUserOnSwitch, value);
            mStopUserOnSwitch = value;
        }
    }

    private boolean shouldStopUserOnSwitch() {
        synchronized (mLock) {
            if (mStopUserOnSwitch != STOP_USER_ON_SWITCH_DEFAULT) {
                final boolean value = mStopUserOnSwitch == STOP_USER_ON_SWITCH_TRUE;
                Slogf.i(TAG, "shouldStopUserOnSwitch(): returning overridden value (%b)", value);
                return value;
            }
        }
        final int property = SystemProperties.getInt("fw.stop_bg_users_on_switch", -1);
        return property == -1 ? mDelayUserDataLocking : property == 1;
    }

    void finishUserSwitch(UserState uss) {
        // This call holds the AM lock so we post to the handler.
        mHandler.post(() -> {
            finishUserBoot(uss);
            startProfiles();
            stopExcessRunningUsers();
        });
    }

    @GuardedBy("mLock")
    @VisibleForTesting
    List<Integer> getRunningUsersLU() {
        ArrayList<Integer> runningUsers = new ArrayList<>();
        for (Integer userId : mUserLru) {
            UserState uss = mStartedUsers.get(userId);
            if (uss == null) {
                // Shouldn't happen, but recover if it does.
                continue;
            }
            if (uss.state == UserState.STATE_STOPPING
                    || uss.state == UserState.STATE_SHUTDOWN) {
                // This user is already stopping, doesn't count.
                continue;
            }
            runningUsers.add(userId);
        }
        return runningUsers;
    }

    private void stopExcessRunningUsers() {
        final ArraySet<Integer> exemptedUsers = new ArraySet<>();
        final List<UserInfo> users = mInjector.getUserManager().getUsers(true);
        for (int i = 0; i < users.size(); i++) {
            final int userId = users.get(i).id;
            if (isAlwaysVisibleUser(userId)) {
                exemptedUsers.add(userId);
            }
        }

        synchronized (mLock) {
            stopExcessRunningUsersLU(mMaxRunningUsers, exemptedUsers);
        }
    }

    @GuardedBy("mLock")
    private void stopExcessRunningUsersLU(int maxRunningUsers, ArraySet<Integer> exemptedUsers) {
        List<Integer> currentlyRunning = getRunningUsersLU();
        Iterator<Integer> iterator = currentlyRunning.iterator();
        while (currentlyRunning.size() > maxRunningUsers && iterator.hasNext()) {
            Integer userId = iterator.next();
            if (userId == UserHandle.USER_SYSTEM
                    || userId == mCurrentUserId
                    || exemptedUsers.contains(userId)) {
                // System and current users can't be stopped, and an exempt user shouldn't be
                continue;
            }
            // allowDelayedLocking set here as stopping user is done without any explicit request
            // from outside.
            if (stopUsersLU(userId, /* force= */ false, /* allowDelayedLocking= */ true,
                    /* stopUserCallback= */ null, /* keyEvictedCallback= */ null)
                    == USER_OP_SUCCESS) {
                iterator.remove();
            }
        }
    }

    /**
     * Returns if more users can be started without stopping currently running users.
     */
    boolean canStartMoreUsers() {
        synchronized (mLock) {
            return getRunningUsersLU().size() < mMaxRunningUsers;
        }
    }

    private void finishUserBoot(UserState uss) {
        finishUserBoot(uss, null);
    }

    private void finishUserBoot(UserState uss, IIntentReceiver resultTo) {
        final int userId = uss.mHandle.getIdentifier();
        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_BOOT, userId);

        synchronized (mLock) {
            // Bail if we ended up with a stale user
            if (mStartedUsers.get(userId) != uss) {
                return;
            }
        }

        // We always walk through all the user lifecycle states to send
        // consistent developer events. We step into RUNNING_LOCKED here,
        // but we might immediately step into RUNNING below if the user
        // storage is already unlocked.
        if (uss.setState(STATE_BOOTING, STATE_RUNNING_LOCKED)) {
            mInjector.getUserJourneyLogger()
                    .logUserLifecycleEvent(userId, USER_LIFECYCLE_EVENT_USER_RUNNING_LOCKED,
                    EVENT_STATE_NONE);
            mInjector.getUserManagerInternal().setUserState(userId, uss.state);
            // Do not report secondary users, runtime restarts or first boot/upgrade
            if (userId == UserHandle.USER_SYSTEM
                    && !mInjector.isRuntimeRestarted() && !mInjector.isFirstBootOrUpgrade()) {
                final long elapsedTimeMs = SystemClock.elapsedRealtime();
                FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                        BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__FRAMEWORK_LOCKED_BOOT_COMPLETED,
                        elapsedTimeMs);
                final long maxElapsedTimeMs = 120_000;
                if (elapsedTimeMs > maxElapsedTimeMs) {
                    Slogf.wtf("SystemServerTiming",
                            "finishUserBoot took too long. elapsedTimeMs=" + elapsedTimeMs);
                }
            }

            if (!mInjector.getUserManager().isPreCreated(userId)) {
                mHandler.sendMessage(mHandler.obtainMessage(REPORT_LOCKED_BOOT_COMPLETE_MSG,
                        userId, 0));
                // The "locked boot complete" broadcast for the system user is supposed be sent when
                // the device has finished booting.  Normally, that is the same time that the system
                // user transitions to RUNNING_LOCKED.  However, in "headless system user mode", the
                // system user is explicitly started before the device has finished booting.  In
                // that case, we need to wait until onBootComplete() to send the broadcast.
                // Similarly, this occurs after a user switch, but in HSUM we switch to the main
                // user before boot is complete, so again this should be delayed until
                // onBootComplete if boot has not yet completed.
                if (mAllowUserUnlocking) {
                    // ACTION_LOCKED_BOOT_COMPLETED
                    sendLockedBootCompletedBroadcast(resultTo, userId);
                }
            }
        }

        // We need to delay unlocking profiles until the parent user is also unlocked.
        final UserInfo parent = mInjector.getUserManager().getProfileParent(userId);
        if (parent == null) {
            // Not a profile (or is a parentless profile) so no parent for which to wait.
            maybeUnlockUser(userId);
        } else if (isUserRunning(parent.id, ActivityManager.FLAG_AND_UNLOCKED)) {
            Slogf.d(TAG, "User " + userId + " (parent " + parent.id
                    + "): attempting unlock because parent is unlocked");
            maybeUnlockUser(userId);
        } else {
            Slogf.d(TAG, "User " + userId + " (parent " + parent.id
                    + "): delaying unlock because parent is locked");
        }
    }

    private void sendLockedBootCompletedBroadcast(IIntentReceiver receiver, @UserIdInt int userId) {
        final Intent intent = new Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED, null);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        intent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT
                | Intent.FLAG_RECEIVER_OFFLOAD
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        mInjector.broadcastIntent(intent, null, receiver, 0, null, null,
                new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                AppOpsManager.OP_NONE,
                getTemporaryAppAllowlistBroadcastOptions(REASON_LOCKED_BOOT_COMPLETED)
                        .toBundle(), true,
                false, MY_PID, SYSTEM_UID,
                Binder.getCallingUid(), Binder.getCallingPid(), userId);
    }

    /**
     * Step from {@link UserState#STATE_RUNNING_LOCKED} to
     * {@link UserState#STATE_RUNNING_UNLOCKING}.
     */
    private boolean finishUserUnlocking(final UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_UNLOCKING, userId);
        mInjector.getUserJourneyLogger()
                .logUserLifecycleEvent(userId, USER_LIFECYCLE_EVENT_UNLOCKING_USER,
                EVENT_STATE_BEGIN);
        // If the user's CE storage hasn't been unlocked yet, we cannot proceed.
        if (!StorageManager.isCeStorageUnlocked(userId)) return false;
        synchronized (mLock) {
            // Do not proceed if unexpected state or a stale user
            if (mStartedUsers.get(userId) != uss || uss.state != STATE_RUNNING_LOCKED) {
                return false;
            }
        }
        uss.mUnlockProgress.start();

        // Prepare app storage before we go any further
        uss.mUnlockProgress.setProgress(5,
                    mInjector.getContext().getString(R.string.android_start_title));

        // Call onBeforeUnlockUser on a worker thread that allows disk I/O
        FgThread.getHandler().post(() -> {
            if (!StorageManager.isCeStorageUnlocked(userId)) {
                Slogf.w(TAG, "User's CE storage got locked unexpectedly, leaving user locked.");
                return;
            }

            final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
            t.traceBegin("UM.onBeforeUnlockUser-" + userId);
            mInjector.getUserManager().onBeforeUnlockUser(userId);
            t.traceEnd();
            synchronized (mLock) {
                // Do not proceed if unexpected state
                if (!uss.setState(STATE_RUNNING_LOCKED, STATE_RUNNING_UNLOCKING)) {
                    return;
                }
            }
            mInjector.getUserManagerInternal().setUserState(userId, uss.state);

            uss.mUnlockProgress.setProgress(20);

            mLastUserUnlockingUptime = SystemClock.uptimeMillis();

            // Dispatch unlocked to system services; when fully dispatched,
            // that calls through to the next "unlocked" phase
            mHandler.obtainMessage(USER_UNLOCK_MSG, userId, 0, uss).sendToTarget();
        });
        return true;
    }

    /**
     * Step from {@link UserState#STATE_RUNNING_UNLOCKING} to
     * {@link UserState#STATE_RUNNING_UNLOCKED}.
     */
    private void finishUserUnlocked(final UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_UNLOCKED, userId);
        // Only keep marching forward if the user's CE storage is unlocked.
        if (!StorageManager.isCeStorageUnlocked(userId)) return;
        synchronized (mLock) {
            // Bail if we ended up with a stale user
            if (mStartedUsers.get(uss.mHandle.getIdentifier()) != uss) return;

            // Do not proceed if unexpected state
            if (!uss.setState(STATE_RUNNING_UNLOCKING, STATE_RUNNING_UNLOCKED)) {
                return;
            }
        }
        mInjector.getUserManagerInternal().setUserState(userId, uss.state);
        uss.mUnlockProgress.finish();

        // Get unaware persistent apps running and start any unaware providers
        // in already-running apps that are partially aware
        if (userId == UserHandle.USER_SYSTEM) {
            mInjector.startPersistentApps(PackageManager.MATCH_DIRECT_BOOT_UNAWARE);
        }
        mInjector.installEncryptionUnawareProviders(userId);

        if (!mInjector.getUserManager().isPreCreated(userId)) {
            // Dispatch unlocked to external apps
            final Intent unlockedIntent = new Intent(Intent.ACTION_USER_UNLOCKED);
            unlockedIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
            unlockedIntent.addFlags(
                    Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_FOREGROUND);
            mInjector.broadcastIntent(unlockedIntent, null, null, 0, null,
                    null, null, AppOpsManager.OP_NONE, null, false, false, MY_PID, SYSTEM_UID,
                    Binder.getCallingUid(), Binder.getCallingPid(), userId);
        }

        final UserInfo userInfo = getUserInfo(userId);
        if (userInfo.isProfile()) {
            UserInfo parent = mInjector.getUserManager().getProfileParent(userId);
            if (parent != null) {
                // Send PROFILE_ACCESSIBLE broadcast to the parent user if a profile was unlocked
                broadcastProfileAccessibleStateChanged(userId, parent.id,
                        Intent.ACTION_PROFILE_ACCESSIBLE);

                //TODO(b/175704931): send ACTION_MANAGED_PROFILE_AVAILABLE

                // Also send MANAGED_PROFILE_UNLOCKED broadcast to the parent user
                // if a managed profile was unlocked
                if (userInfo.isManagedProfile()) {
                    final Intent profileUnlockedIntent = new Intent(
                            Intent.ACTION_MANAGED_PROFILE_UNLOCKED);
                    profileUnlockedIntent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
                    profileUnlockedIntent.addFlags(
                            Intent.FLAG_RECEIVER_REGISTERED_ONLY
                                    | Intent.FLAG_RECEIVER_FOREGROUND);
                    mInjector.broadcastIntent(profileUnlockedIntent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                            Binder.getCallingPid(), parent.id);
                }
            }
        }

        // Send PRE_BOOT broadcasts if user fingerprint changed; we
        // purposefully block sending BOOT_COMPLETED until after all
        // PRE_BOOT receivers are finished to avoid ANR'ing apps
        final UserInfo info = getUserInfo(userId);
        if (!Objects.equals(info.lastLoggedInFingerprint, PackagePartitions.FINGERPRINT)
                || SystemProperties.getBoolean("persist.pm.mock-upgrade", false)) {
            // Suppress double notifications for managed profiles that
            // were unlocked automatically as part of their parent user being
            // unlocked.  TODO(b/217442918): this code doesn't work correctly.
            final boolean quiet = info.isManagedProfile();
            mInjector.sendPreBootBroadcast(userId, quiet,
                    () -> finishUserUnlockedCompleted(uss));
        } else {
            finishUserUnlockedCompleted(uss);
        }
    }

    private void finishUserUnlockedCompleted(UserState uss) {
        final int userId = uss.mHandle.getIdentifier();
        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_UNLOCKED_COMPLETED, userId);
        synchronized (mLock) {
            // Bail if we ended up with a stale user
            if (mStartedUsers.get(uss.mHandle.getIdentifier()) != uss) return;
        }
        UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null) {
            return;
        }
        // Only keep marching forward if the user's CE storage is unlocked.
        if (!StorageManager.isCeStorageUnlocked(userId)) return;

        // Remember that we logged in
        mInjector.getUserManager().onUserLoggedIn(userId);

        Runnable initializeUser = () -> mInjector.getUserManager().makeInitialized(userInfo.id);
        if (!userInfo.isInitialized()) {
            Slogf.d(TAG, "Initializing user #" + userId);
            if (userInfo.preCreated) {
                initializeUser.run();
            } else if (userId != UserHandle.USER_SYSTEM) {
                Intent intent = new Intent(Intent.ACTION_USER_INITIALIZE);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                        | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                mInjector.broadcastIntent(intent, null,
                        new IIntentReceiver.Stub() {
                            @Override
                            public void performReceive(Intent intent, int resultCode,
                                    String data, Bundle extras, boolean ordered,
                                    boolean sticky, int sendingUser) {
                                // Note: performReceive is called with mService lock held
                                initializeUser.run();
                            }
                        }, 0, null, null, null, AppOpsManager.OP_NONE,
                        null, true, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                        Binder.getCallingPid(), userId);
            }
        }

        if (userInfo.preCreated) {
            Slogf.i(TAG, "Stopping pre-created user " + userInfo.toFullString());
            // Pre-created user was started right after creation so services could properly
            // intialize it; it should be stopped right away as it's not really a "real" user.
            stopUser(userInfo.id, /* force= */ true, /* allowDelayedLocking= */ false,
                    /* stopUserCallback= */ null, /* keyEvictedCallback= */ null);
            return;
        }

        // Spin up app widgets prior to boot-complete, so they can be ready promptly
        mInjector.startUserWidgets(userId);

        mHandler.obtainMessage(USER_UNLOCKED_MSG, userId, 0).sendToTarget();

        Slogf.i(TAG, "Posting BOOT_COMPLETED user #" + userId);
        // Do not report secondary users, runtime restarts or first boot/upgrade
        if (userId == UserHandle.USER_SYSTEM
                && !mInjector.isRuntimeRestarted() && !mInjector.isFirstBootOrUpgrade()) {
            final long elapsedTimeMs = SystemClock.elapsedRealtime();
            FrameworkStatsLog.write(FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME_REPORTED,
                    FrameworkStatsLog.BOOT_TIME_EVENT_ELAPSED_TIME__EVENT__FRAMEWORK_BOOT_COMPLETED,
                    elapsedTimeMs);
        }
        final Intent bootIntent = new Intent(Intent.ACTION_BOOT_COMPLETED, null);
        bootIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        bootIntent.addFlags(Intent.FLAG_RECEIVER_NO_ABORT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND
                | Intent.FLAG_RECEIVER_OFFLOAD);
        // Widget broadcasts are outbound via FgThread, so to guarantee sequencing
        // we also send the boot_completed broadcast from that thread.
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        FgThread.getHandler().post(() -> {
            mInjector.broadcastIntent(bootIntent, null,
                    new IIntentReceiver.Stub() {
                        @Override
                        public void performReceive(Intent intent, int resultCode, String data,
                                Bundle extras, boolean ordered, boolean sticky, int sendingUser)
                                        throws RemoteException {
                            Slogf.i(UserController.TAG, "Finished processing BOOT_COMPLETED for u"
                                    + userId);
                            mBootCompleted = true;
                        }
                    }, 0, null, null,
                    new String[]{android.Manifest.permission.RECEIVE_BOOT_COMPLETED},
                    AppOpsManager.OP_NONE,
                    getTemporaryAppAllowlistBroadcastOptions(REASON_BOOT_COMPLETED).toBundle(),
                    true, false, MY_PID, SYSTEM_UID, callingUid, callingPid, userId);
        });
    }

    int restartUser(final int userId, @UserStartMode int userStartMode) {
        return stopUser(userId, /* force= */ true, /* allowDelayedLocking= */ false,
                /* stopUserCallback= */ null, new KeyEvictedCallback() {
                    @Override
                    public void keyEvicted(@UserIdInt int userId) {
                        // Post to the same handler that this callback is called from to ensure
                        // the user cleanup is complete before restarting.
                        mHandler.post(() -> UserController.this.startUser(userId, userStartMode));
                    }
                });
    }

    /**
     * Stops a user only if it's a profile, with a more relaxed permission requirement:
     * {@link android.Manifest.permission#MANAGE_USERS} or
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}.
     * To be called from ActivityManagerService.
     * @param userId the id of the user to stop.
     * @return true if the operation was successful.
     */
    boolean stopProfile(final @UserIdInt int userId) {
        if (mInjector.checkCallingPermission(android.Manifest.permission.MANAGE_USERS)
                == PackageManager.PERMISSION_DENIED && mInjector.checkCallingPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or INTERACT_ACROSS_USERS_FULL permission to "
                            + "stop a profile");
        }

        final UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null || !userInfo.isProfile()) {
            throw new IllegalArgumentException("User " + userId + " is not a profile");
        }

        enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, userId);
        synchronized (mLock) {
            return stopUsersLU(userId, /* force= */ true, /* allowDelayedLocking= */
                    false, /* stopUserCallback= */ null, /* keyEvictedCallback= */ null)
                    == ActivityManager.USER_OP_SUCCESS;
        }
    }

    int stopUser(final int userId, final boolean force, boolean allowDelayedLocking,
            final IStopUserCallback stopUserCallback, KeyEvictedCallback keyEvictedCallback) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();

        t.traceBegin("UserController"
                + (force ? "-force" : "")
                + (allowDelayedLocking ? "-allowDelayedLocking" : "")
                + (stopUserCallback != null ? "-withStopUserCallback" : "")
                + "-" + userId + "-[stopUser]");
        try {
            checkCallingPermission(INTERACT_ACROSS_USERS_FULL, "stopUser");
            Preconditions.checkArgument(userId >= 0, "Invalid user id %d", userId);

            enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, userId);
            synchronized (mLock) {
                return stopUsersLU(userId, force, allowDelayedLocking, stopUserCallback,
                        keyEvictedCallback);
            }
        } finally {
            t.traceEnd();
        }
    }

    /**
     * Stops the user along with its related users. The method calls
     * {@link #getUsersToStopLU(int)} to determine the list of users that should be stopped.
     */
    @GuardedBy("mLock")
    private int stopUsersLU(final int userId, boolean force, boolean allowDelayedLocking,
            final IStopUserCallback stopUserCallback, KeyEvictedCallback keyEvictedCallback) {
        if (userId == UserHandle.USER_SYSTEM) {
            return USER_OP_ERROR_IS_SYSTEM;
        }
        if (isCurrentUserLU(userId)) {
            return USER_OP_IS_CURRENT;
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        int[] usersToStop = getUsersToStopLU(userId);
        // If one of related users is system or current, no related users should be stopped
        for (int relatedUserId : usersToStop) {
            if ((UserHandle.USER_SYSTEM == relatedUserId) || isCurrentUserLU(relatedUserId)) {
                if (DEBUG_MU) {
                    Slogf.i(TAG, "stopUsersLocked cannot stop related user " + relatedUserId);
                }
                // We still need to stop the requested user if it's a force stop.
                if (force) {
                    Slogf.i(TAG,
                            "Force stop user " + userId + ". Related users will not be stopped");
                    t.traceBegin("stopSingleUserLU-force-" + userId + "-[stopUser]");
                    stopSingleUserLU(userId, allowDelayedLocking, stopUserCallback,
                            keyEvictedCallback);
                    t.traceEnd();
                    return USER_OP_SUCCESS;
                }
                return USER_OP_ERROR_RELATED_USERS_CANNOT_STOP;
            }
        }
        if (DEBUG_MU) Slogf.i(TAG, "stopUsersLocked usersToStop=" + Arrays.toString(usersToStop));
        for (int userIdToStop : usersToStop) {
            t.traceBegin("stopSingleUserLU-" + userIdToStop + "-[stopUser]");
            stopSingleUserLU(userIdToStop, allowDelayedLocking,
                    userIdToStop == userId ? stopUserCallback : null,
                    userIdToStop == userId ? keyEvictedCallback : null);
            t.traceEnd();
        }
        return USER_OP_SUCCESS;
    }

    /**
     * Stops a single User. This can also trigger locking user data out depending on device's
     * config ({@code mDelayUserDataLocking}) and arguments.
     *
     * In the default configuration for most device and users, users will be locked when stopping.
     * User will remain unlocked only if all the following are true
     * <li> {@link #canDelayDataLockingForUser(int)} (based on mDelayUserDataLocking) is true
     * <li> the parameter {@code allowDelayedLocking} is true
     * <li> {@code keyEvictedCallback} is null
     * -
     *
     * @param userId User Id to stop and lock the data.
     * @param allowDelayedLocking When set, do not lock user after stopping. Locking can happen
     *                            later when number of unlocked users reaches
     *                            {@code mMaxRunnngUsers}. Note that this is respected only when
     *                            delayed locking is enabled for this user and {@keyEvictedCallback}
     *                            is null. Otherwise the user nonetheless will be locked.
     * @param stopUserCallback Callback to notify that user has stopped.
     * @param keyEvictedCallback Callback to notify that user has been unlocked.
     */
    @GuardedBy("mLock")
    private void stopSingleUserLU(final int userId, boolean allowDelayedLocking,
            final IStopUserCallback stopUserCallback,
            KeyEvictedCallback keyEvictedCallback) {
        Slogf.i(TAG, "stopSingleUserLU userId=" + userId);
        final UserState uss = mStartedUsers.get(userId);
        if (uss == null) {  // User is not started
            // If canDelayDataLockingForUser() is true and allowDelayedLocking is false, we need
            // to lock the requested user as the client wants to stop and lock the user. On the
            // other hand, having keyEvictedCallback set will lead into locking user if
            // canDelayDataLockingForUser() is true as that means client wants to lock the user
            // immediately.
            // If canDelayDataLockingForUser() is false, the user was already locked when it was
            // stopped and no further action is necessary.
            if (canDelayDataLockingForUser(userId)) {
                if (allowDelayedLocking && keyEvictedCallback != null) {
                    Slogf.wtf(TAG, "allowDelayedLocking set with KeyEvictedCallback, ignore it"
                            + " and lock user:" + userId, new RuntimeException());
                    allowDelayedLocking = false;
                }
                if (!allowDelayedLocking) {
                    if (mLastActiveUsersForDelayedLocking.remove(Integer.valueOf(userId))) {
                        // should lock the user, user is already gone
                        final ArrayList<KeyEvictedCallback> keyEvictedCallbacks;
                        if (keyEvictedCallback != null) {
                            keyEvictedCallbacks = new ArrayList<>(1);
                            keyEvictedCallbacks.add(keyEvictedCallback);
                        } else {
                            keyEvictedCallbacks = null;
                        }
                        dispatchUserLocking(userId, keyEvictedCallbacks);
                    }
                }
            }
            // We do need to post the stopped callback even though user is already stopped.
            if (stopUserCallback != null) {
                mHandler.post(() -> {
                    try {
                        stopUserCallback.userStopped(userId);
                    } catch (RemoteException e) {
                    }
                });
            }
            return;
        }

        logUserJourneyBegin(userId, USER_JOURNEY_USER_STOP);

        if (stopUserCallback != null) {
            uss.mStopCallbacks.add(stopUserCallback);
        }
        if (keyEvictedCallback != null) {
            uss.mKeyEvictedCallbacks.add(keyEvictedCallback);
        }

        if (uss.state != UserState.STATE_STOPPING
                && uss.state != UserState.STATE_SHUTDOWN) {
            uss.setState(UserState.STATE_STOPPING);
            UserManagerInternal userManagerInternal = mInjector.getUserManagerInternal();
            TimingsTraceAndSlog t = new TimingsTraceAndSlog();
            t.traceBegin("setUserState-STATE_STOPPING-" + userId + "-[stopUser]");
            userManagerInternal.setUserState(userId, uss.state);
            t.traceEnd();
            t.traceBegin("unassignUserFromDisplayOnStop-" + userId + "-[stopUser]");
            userManagerInternal.unassignUserFromDisplayOnStop(userId);
            t.traceEnd();

            updateStartedUserArrayLU();

            final boolean allowDelayedLockingCopied = allowDelayedLocking;
            Runnable finishUserStoppingAsync = () ->
                    mHandler.post(() -> {
                        TimingsTraceAndSlog t2 = new TimingsTraceAndSlog();
                        t2.traceBegin("finishUserStopping-" + userId + "-[stopUser]");
                        finishUserStopping(userId, uss, allowDelayedLockingCopied);
                        t2.traceEnd();
                    });

            if (mInjector.getUserManager().isPreCreated(userId)) {
                finishUserStoppingAsync.run();
                return;
            }

            // Post to handler to obtain amLock
            mHandler.post(() -> {
                // We are going to broadcast ACTION_USER_STOPPING and then
                // once that is done send a final ACTION_SHUTDOWN and then
                // stop the user.
                final Intent stoppingIntent = new Intent(Intent.ACTION_USER_STOPPING);
                stoppingIntent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                stoppingIntent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
                stoppingIntent.putExtra(Intent.EXTRA_SHUTDOWN_USERSPACE_ONLY, true);
                // This is the result receiver for the initial stopping broadcast.
                final IIntentReceiver stoppingReceiver = new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode, String data,
                            Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                        asyncTraceEnd("broadcast-ACTION_USER_STOPPING-" + userId + "-[stopUser]",
                                userId);
                        finishUserStoppingAsync.run();
                    }
                };

                TimingsTraceAndSlog t2 = new TimingsTraceAndSlog();
                t2.traceBegin("clearBroadcastQueueForUser-" + userId + "-[stopUser]");
                // Clear broadcast queue for the user to avoid delivering stale broadcasts
                mInjector.clearBroadcastQueueForUser(userId);
                t2.traceEnd();
                asyncTraceBegin("broadcast-ACTION_USER_STOPPING-" + userId + "-[stopUser]", userId);
                // Kick things off.
                mInjector.broadcastIntent(stoppingIntent,
                        null, stoppingReceiver, 0, null, null,
                        new String[]{INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE,
                        null, true, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                        Binder.getCallingPid(), UserHandle.USER_ALL);
            });
        }
    }

    private void finishUserStopping(final int userId, final UserState uss,
            final boolean allowDelayedLocking) {
        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_STOPPING, userId);
        synchronized (mLock) {
            if (uss.state != UserState.STATE_STOPPING) {
                // Whoops, we are being started back up.  Abort, abort!
                UserJourneySession session = mInjector.getUserJourneyLogger()
                        .logUserJourneyFinishWithError(-1, getUserInfo(userId),
                                USER_JOURNEY_USER_STOP, ERROR_CODE_ABORTED);
                if (session != null) {
                    mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, session);
                } else {
                    mInjector.getUserJourneyLogger()
                            .logUserJourneyFinishWithError(-1, getUserInfo(userId),
                                    USER_JOURNEY_USER_STOP, ERROR_CODE_INVALID_SESSION_ID);
                }
                return;
            }
            uss.setState(UserState.STATE_SHUTDOWN);
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("setUserState-STATE_SHUTDOWN-" + userId + "-[stopUser]");
        mInjector.getUserManagerInternal().setUserState(userId, uss.state);
        t.traceEnd();

        mInjector.batteryStatsServiceNoteEvent(
                BatteryStats.HistoryItem.EVENT_USER_RUNNING_FINISH,
                Integer.toString(userId), userId);
        mInjector.getSystemServiceManager().onUserStopping(userId);

        Runnable finishUserStoppedAsync = () ->
                mHandler.post(() -> {
                    TimingsTraceAndSlog t2 = new TimingsTraceAndSlog();
                    t2.traceBegin("finishUserStopped-" + userId + "-[stopUser]");
                    finishUserStopped(uss, allowDelayedLocking);
                    t2.traceEnd();
                });
        if (mInjector.getUserManager().isPreCreated(userId)) {
            finishUserStoppedAsync.run();
            return;
        }

        // Fire the shutdown intent.
        final Intent shutdownIntent = new Intent(Intent.ACTION_SHUTDOWN);
        // This is the result receiver for the final shutdown broadcast.
        final IIntentReceiver shutdownReceiver = new IIntentReceiver.Stub() {
            @Override
            public void performReceive(Intent intent, int resultCode, String data,
                    Bundle extras, boolean ordered, boolean sticky, int sendingUser) {
                asyncTraceEnd("broadcast-ACTION_SHUTDOWN-" + userId + "-[stopUser]", userId);
                finishUserStoppedAsync.run();
            }
        };
        asyncTraceBegin("broadcast-ACTION_SHUTDOWN-" + userId + "-[stopUser]", userId);
        mInjector.broadcastIntent(shutdownIntent,
                null, shutdownReceiver, 0, null, null, null,
                AppOpsManager.OP_NONE,
                null, true, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                Binder.getCallingPid(), userId);
    }

    @VisibleForTesting
    void finishUserStopped(UserState uss, boolean allowDelayedLocking) {
        final int userId = uss.mHandle.getIdentifier();
        if (DEBUG_MU) {
            Slogf.i(TAG, "finishUserStopped(%d): allowDelayedLocking=%b", userId,
                    allowDelayedLocking);
        }

        EventLog.writeEvent(EventLogTags.UC_FINISH_USER_STOPPED, userId);
        final boolean stopped;
        boolean lockUser = true;
        final ArrayList<IStopUserCallback> stopCallbacks;
        final ArrayList<KeyEvictedCallback> keyEvictedCallbacks;
        int userIdToLock = userId;
        // Must get a reference to UserInfo before it's removed
        final UserInfo userInfo = getUserInfo(userId);
        synchronized (mLock) {
            stopCallbacks = new ArrayList<>(uss.mStopCallbacks);
            keyEvictedCallbacks = new ArrayList<>(uss.mKeyEvictedCallbacks);
            if (mStartedUsers.get(userId) != uss || uss.state != UserState.STATE_SHUTDOWN) {
                stopped = false;
            } else {
                stopped = true;
                // User can no longer run.
                Slogf.i(TAG, "Removing user state from UserController.mStartedUsers for user #"
                        + userId + " as a result of user being stopped");
                mStartedUsers.remove(userId);

                mUserLru.remove(Integer.valueOf(userId));
                updateStartedUserArrayLU();

                if (allowDelayedLocking && !keyEvictedCallbacks.isEmpty()) {
                    Slogf.wtf(TAG,
                            "Delayed locking enabled while KeyEvictedCallbacks not empty, userId:"
                                    + userId + " callbacks:" + keyEvictedCallbacks);
                    allowDelayedLocking = false;
                }
                userIdToLock = updateUserToLockLU(userId, allowDelayedLocking);
                if (userIdToLock == UserHandle.USER_NULL) {
                    lockUser = false;
                }
            }
        }
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        if (stopped) {
            Slogf.i(TAG, "Removing user state from UserManager.mUserStates for user #" + userId
                    + " as a result of user being stopped");
            mInjector.getUserManagerInternal().removeUserState(userId);

            mInjector.activityManagerOnUserStopped(userId);
            // Clean up all state and processes associated with the user.
            // Kill all the processes for the user.
            t.traceBegin("forceStopUser-" + userId + "-[stopUser]");
            forceStopUser(userId, "finish user");
            t.traceEnd();
        }

        for (final IStopUserCallback callback : stopCallbacks) {
            try {
                if (stopped) {
                    t.traceBegin("stopCallbacks.userStopped-" + userId + "-[stopUser]");
                    callback.userStopped(userId);
                    t.traceEnd();
                } else {
                    t.traceBegin("stopCallbacks.userStopAborted-" + userId + "-[stopUser]");
                    callback.userStopAborted(userId);
                    t.traceEnd();
                }
            } catch (RemoteException ignored) {
            }
        }

        if (stopped) {
            t.traceBegin("systemServiceManagerOnUserStopped-" + userId + "-[stopUser]");
            mInjector.systemServiceManagerOnUserStopped(userId);
            t.traceEnd();
            t.traceBegin("taskSupervisorRemoveUser-" + userId + "-[stopUser]");
            mInjector.taskSupervisorRemoveUser(userId);
            t.traceEnd();

            // Remove the user if it is ephemeral.
            if (userInfo.isEphemeral() && !userInfo.preCreated) {
                mInjector.getUserManager().removeUserEvenWhenDisallowed(userId);
            }

            UserJourneySession session = mInjector.getUserJourneyLogger()
                    .logUserJourneyFinish(-1, userInfo, USER_JOURNEY_USER_STOP);
            if (session != null) {
                mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, session);
            }

            if (lockUser) {
                dispatchUserLocking(userIdToLock, keyEvictedCallbacks);
            }

            // Resume any existing pending user start,
            // which was paused while the SHUTDOWN flow of the user was in progress.
            resumePendingUserStarts(userId);
        } else {
            UserJourneySession session = mInjector.getUserJourneyLogger()
                    .finishAndClearIncompleteUserJourney(userId, USER_JOURNEY_USER_STOP);
            if (session != null) {
                mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, session);
            }
        }
    }

    /**
     * Resume any existing pending user start for the specified userId which was paused
     * while the shutdown flow of the user was in progress.
     * Remove all the handled user starts from mPendingUserStarts.
     * @param userId the id of the user
     */
    private void resumePendingUserStarts(@UserIdInt int userId) {
        synchronized (mLock) {
            final List<PendingUserStart> handledUserStarts = new ArrayList<>();

            for (PendingUserStart userStart: mPendingUserStarts) {
                if (userStart.userId == userId) {
                    Slogf.i(TAG, "resumePendingUserStart for" + userStart);
                    mHandler.post(() -> startUser(userStart.userId,
                            userStart.userStartMode, userStart.unlockListener));

                    handledUserStarts.add(userStart);
                }
            }
            // remove all the pending user starts which are now handled
            mPendingUserStarts.removeAll(handledUserStarts);
        }
    }


    private void dispatchUserLocking(@UserIdInt int userId,
            @Nullable List<KeyEvictedCallback> keyEvictedCallbacks) {
        // Evict the user's credential encryption key. Performed on FgThread to make it
        // serialized with call to UserManagerService.onBeforeUnlockUser in finishUserUnlocking
        // to prevent data corruption.
        FgThread.getHandler().post(() -> {
            synchronized (mLock) {
                if (mStartedUsers.get(userId) != null) {
                    Slogf.w(TAG, "User was restarted, skipping key eviction");
                    return;
                }
            }
            try {
                Slogf.i(TAG, "Locking CE storage for user #" + userId);
                mInjector.getStorageManager().lockCeStorage(userId);
            } catch (RemoteException re) {
                throw re.rethrowAsRuntimeException();
            }
            if (keyEvictedCallbacks == null) {
                return;
            }
            for (int i = 0; i < keyEvictedCallbacks.size(); i++) {
                keyEvictedCallbacks.get(i).keyEvicted(userId);
            }
        });
    }

    /**
     * Returns which user, if any, should be locked when the given user is stopped.
     *
     * For typical (non-mDelayUserDataLocking) devices and users, this will be the provided user.
     *
     * However, for some devices or users (based on {@link #canDelayDataLockingForUser(int)}),
     * storage once unlocked is kept unlocked, even after the user is stopped, so the user to be
     * locked (if any) may differ.
     *
     * For mDelayUserDataLocking devices, the total number of unlocked user storage is limited
     * (currently by mMaxRunningUsers). If there are more unlocked users, evict and lock the least
     * recently stopped user and lock that user's data.
     *
     * Regardless of the mode, ephemeral user is always locked immediately.
     *
     * @return user id to lock. UserHandler.USER_NULL will be returned if no user should be locked.
     */
    @GuardedBy("mLock")
    private int updateUserToLockLU(@UserIdInt int userId, boolean allowDelayedLocking) {
        if (!canDelayDataLockingForUser(userId)
                || !allowDelayedLocking
                || getUserInfo(userId).isEphemeral()
                || hasUserRestriction(UserManager.DISALLOW_RUN_IN_BACKGROUND, userId)) {
            return userId;
        }

        // Once we reach here, we are in a delayed locking scenario.
        // Now, no user will be locked, unless the device's policy dictates we should based on the
        // maximum of such users allowed for the device.
        if (mDelayUserDataLocking) {
            // arg should be object, not index
            mLastActiveUsersForDelayedLocking.remove((Integer) userId);
            mLastActiveUsersForDelayedLocking.add(0, userId);
            int totalUnlockedUsers = mStartedUsers.size()
                    + mLastActiveUsersForDelayedLocking.size();
            // TODO: Decouple the delayed locking flows from mMaxRunningUsers. These users aren't
            //  running so this calculation shouldn't be based on this parameter. Also note that
            //  that if these devices ever support background running users (such as profiles), the
            //  implementation is incorrect since starting such users can cause the max to be
            //  exceeded.
            if (totalUnlockedUsers > mMaxRunningUsers) { // should lock a user
                final int userIdToLock = mLastActiveUsersForDelayedLocking.get(
                        mLastActiveUsersForDelayedLocking.size() - 1);
                mLastActiveUsersForDelayedLocking
                        .remove(mLastActiveUsersForDelayedLocking.size() - 1);
                Slogf.i(TAG, "finishUserStopped: should stop user " + userId
                        + " but should lock user " + userIdToLock);
                return userIdToLock;
            }
        }
        Slogf.i(TAG, "finishUserStopped: should stop user " + userId + " but without any locking");
        return UserHandle.USER_NULL;
    }

    /**
     * Returns whether the user can have its CE storage left unlocked, even when it is stopped,
     * either due to a global device configuration or an individual user's property.
     */
    private boolean canDelayDataLockingForUser(@UserIdInt int userIdToLock) {
        if (allowBiometricUnlockForPrivateProfile()) {
            final UserProperties userProperties = getUserProperties(userIdToLock);
            return (mDelayUserDataLocking || (userProperties != null
                    && userProperties.getAllowStoppingUserWithDelayedLocking()));
        }
        return mDelayUserDataLocking;
    }

    private boolean allowBiometricUnlockForPrivateProfile() {
        return android.os.Flags.allowPrivateProfile()
                && android.multiuser.Flags.enableBiometricsToUnlockPrivateSpace();
    }

    /**
     * Determines the list of users that should be stopped together with the specified
     * {@code userId}. The returned list includes {@code userId}.
     */
    @GuardedBy("mLock")
    private @NonNull int[] getUsersToStopLU(@UserIdInt int userId) {
        int startedUsersSize = mStartedUsers.size();
        IntArray userIds = new IntArray();
        userIds.add(userId);
        int userGroupId = mUserProfileGroupIds.get(userId, UserInfo.NO_PROFILE_GROUP_ID);
        for (int i = 0; i < startedUsersSize; i++) {
            UserState uss = mStartedUsers.valueAt(i);
            int startedUserId = uss.mHandle.getIdentifier();
            // Skip unrelated users (profileGroupId mismatch)
            int startedUserGroupId = mUserProfileGroupIds.get(startedUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            boolean sameGroup = (userGroupId != UserInfo.NO_PROFILE_GROUP_ID)
                    && (userGroupId == startedUserGroupId);
            // userId has already been added
            boolean sameUserId = startedUserId == userId;
            if (!sameGroup || sameUserId) {
                continue;
            }
            userIds.add(startedUserId);
        }
        return userIds.toArray();
    }

    private void forceStopUser(@UserIdInt int userId, String reason) {
        if (DEBUG_MU) Slogf.i(TAG, "forceStopUser(%d): %s", userId, reason);
        mInjector.activityManagerForceStopPackage(userId, reason);
        if (mInjector.getUserManager().isPreCreated(userId)) {
            // Don't fire intent for precreated.
            return;
        }
        Intent intent = new Intent(Intent.ACTION_USER_STOPPED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mInjector.broadcastIntent(intent,
                null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                null, false, false, MY_PID, SYSTEM_UID, Binder.getCallingUid(),
                Binder.getCallingPid(), UserHandle.USER_ALL);

        // Send PROFILE_INACCESSIBLE broadcast if a profile was stopped
        final UserInfo userInfo = getUserInfo(userId);
        if (userInfo != null && userInfo.isProfile()) {
            UserInfo parent = mInjector.getUserManager().getProfileParent(userId);
            if (parent != null) {
                broadcastProfileAccessibleStateChanged(userId, parent.id,
                        Intent.ACTION_PROFILE_INACCESSIBLE);
                //TODO(b/175704931): send ACTION_MANAGED_PROFILE_UNAVAILABLE
            }
        }
    }

    /**
     * Stops the guest or ephemeral user if it has gone to the background.
     */
    private void stopGuestOrEphemeralUserIfBackground(int oldUserId) {
        if (DEBUG_MU) Slogf.i(TAG, "Stop guest or ephemeral user if background: " + oldUserId);
        synchronized(mLock) {
            UserState oldUss = mStartedUsers.get(oldUserId);
            if (oldUserId == UserHandle.USER_SYSTEM || oldUserId == mCurrentUserId || oldUss == null
                    || oldUss.state == UserState.STATE_STOPPING
                    || oldUss.state == UserState.STATE_SHUTDOWN) {
                return;
            }
        }

        UserInfo userInfo = getUserInfo(oldUserId);
        if (userInfo.isEphemeral()) {
            LocalServices.getService(UserManagerInternal.class).onEphemeralUserStop(oldUserId);
        }
        if (userInfo.isGuest() || userInfo.isEphemeral()) {
            // This is a user to be stopped.
            synchronized (mLock) {
                stopUsersLU(oldUserId, /* force= */ true, /* allowDelayedLocking= */ false,
                        null, null);
            }
        }
    }

    void scheduleStartProfiles() {
        // Parent user transition to RUNNING_UNLOCKING happens on FgThread, so it is busy, there is
        // a chance the profile will reach RUNNING_LOCKED while parent is still locked, so no
        // attempt will be made to unlock the profile. If we go via FgThread, this will be executed
        // after the parent had chance to unlock fully.
        FgThread.getHandler().post(() -> {
            if (!mHandler.hasMessages(START_PROFILES_MSG)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(START_PROFILES_MSG),
                        DateUtils.SECOND_IN_MILLIS);
            }
        });
    }

    private void startProfiles() {
        int currentUserId = getCurrentUserId();
        if (DEBUG_MU) Slogf.i(TAG, "startProfilesLocked");
        List<UserInfo> profiles = mInjector.getUserManager().getProfiles(
                currentUserId, false /* enabledOnly */);
        List<UserInfo> profilesToStart = new ArrayList<>(profiles.size());
        for (UserInfo user : profiles) {
            if ((user.flags & UserInfo.FLAG_INITIALIZED) == UserInfo.FLAG_INITIALIZED
                    && user.id != currentUserId
                    && shouldStartWithParent(user)) {
                profilesToStart.add(user);
            }
        }
        final int profilesToStartSize = profilesToStart.size();
        int i = 0;
        for (; i < profilesToStartSize && i < (getMaxRunningUsers() - 1); ++i) {
            // NOTE: this method is setting the profiles of the current user - which is always
            // assigned to the default display
            startUser(profilesToStart.get(i).id, USER_START_MODE_BACKGROUND_VISIBLE);
        }
        if (i < profilesToStartSize) {
            Slogf.w(TAG, "More profiles than MAX_RUNNING_USERS");
        }
    }

    private boolean shouldStartWithParent(UserInfo user) {
        final UserProperties properties = getUserProperties(user.id);
        return (properties != null && properties.getStartWithParent())
                && !user.isQuietModeEnabled();
    }

    /**
     * Starts a {@link UserManager#isProfile() profile user}.
     *
     * <p>To be called from {@link com.android.server.am.ActivityManagerService}.
     *
     * @param userId the id of the profile user to start.
     * @param evenWhenDisabled whether the profile should be started if it's not enabled yet
     *        (most callers should pass {@code false}, except when starting the profile while it's
     *        being provisioned).
     * @param unlockListener listener to be informed when the profile has started and unlocked.
     *
     * @return {@code true} if the operation was successful.
     *
     * @throws IllegalArgumentException if the user doesn't exist or is not a profile.
     */
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    boolean startProfile(@UserIdInt int userId, boolean evenWhenDisabled,
            @Nullable IProgressListener unlockListener) {
        if (mInjector.checkCallingPermission(android.Manifest.permission.MANAGE_USERS)
                == PackageManager.PERMISSION_DENIED && mInjector.checkCallingPermission(
                android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(
                    "You either need MANAGE_USERS or INTERACT_ACROSS_USERS_FULL permission to "
                            + "start a profile");
        }

        final UserInfo userInfo = getUserInfo(userId);
        if (userInfo == null || !userInfo.isProfile()) {
            throw new IllegalArgumentException("User " + userId + " is not a profile");
        }

        if (!userInfo.isEnabled() && !evenWhenDisabled) {
            Slogf.w(TAG, "Cannot start disabled profile #%d", userId);
            return false;
        }

        return startUserNoChecks(userId, Display.DEFAULT_DISPLAY,
                USER_START_MODE_BACKGROUND_VISIBLE, unlockListener);
    }

    @VisibleForTesting
    boolean startUser(@UserIdInt int userId, @UserStartMode int userStartMode) {
        return startUser(userId, userStartMode, /* unlockListener= */ null);
    }

    /**
     * Start user, if its not already running.
     *
     * <p>The user will be brought to the foreground, if {@code userStartMode} parameter is
     * set to {@link UserManagerInternal#USER_START_MODE_FOREGROUND}
     * When starting the user, multiple intents will be broadcast in the following order:</p>
     * <ul>
     *     <li>{@link Intent#ACTION_USER_STARTED} - sent to registered receivers of the new user
     *     <li>{@link Intent#ACTION_USER_BACKGROUND} - sent to registered receivers of the outgoing
     *     user and all profiles of this user. Sent only if {@code foreground} parameter is
     *     {@code false}
     *     <li>{@link Intent#ACTION_USER_FOREGROUND} - sent to registered receivers of the new
     *     user and all profiles of this user. Sent only if {@code foreground} parameter is
     *     {@code true}
     *     <li>{@link Intent#ACTION_USER_SWITCHED} - sent to registered receivers of the new user.
     *     Sent only if {@code foreground} parameter is {@code true}
     *     <li>{@link Intent#ACTION_USER_STARTING} - ordered broadcast sent to registered receivers
     *     of the new fg user
     *     <li>{@link Intent#ACTION_LOCKED_BOOT_COMPLETED} - ordered broadcast sent to receivers of
     *     the new user
     *     <li>{@link Intent#ACTION_USER_UNLOCKED} - sent to registered receivers of the new user
     *     <li>{@link Intent#ACTION_PRE_BOOT_COMPLETED} - ordered broadcast sent to receivers of the
     *     new user. Sent only when the user is booting after a system update.
     *     <li>{@link Intent#ACTION_USER_INITIALIZE} - ordered broadcast sent to receivers of the
     *     new user. Sent only the first time a user is starting.
     *     <li>{@link Intent#ACTION_BOOT_COMPLETED} - ordered broadcast sent to receivers of the new
     *     user. Indicates that the user has finished booting.
     * </ul>
     *
     * @param userId ID of the user to start
     * @param userStartMode user starting mode
     * @param unlockListener Listener to be informed when the user has started and unlocked.
     * @return true if the user has been successfully started
     */
    boolean startUser(@UserIdInt int userId, @UserStartMode int userStartMode,
            @Nullable IProgressListener unlockListener) {
        checkCallingPermission(INTERACT_ACROSS_USERS_FULL, "startUser");

        return startUserNoChecks(userId, Display.DEFAULT_DISPLAY, userStartMode, unlockListener);
    }

    /**
     * Starts a user in background and make it visible in the given display.
     *
     * <p>This call will trigger the usual "user started" lifecycle events (i.e., `SystemService`
     * callbacks and app intents), plus a call to
     * {@link UserManagerInternal.UserVisibilityListener#onUserVisibilityChanged(int, boolean)} if
     * the user visibility changed. Notice that the visibility change is independent of the user
     * workflow state, and they can mismatch in some corner events (for example, if the user was
     * already running in the background but not associated with a display, this call for that user
     * would not trigger any lifecycle event but would trigger {@code onUserVisibilityChanged}).
     *
     * <p>See {@link ActivityManager#startUserInBackgroundOnSecondaryDisplay(int, int)} for more
     * semantics.
     *
     * @param userId user to be started
     * @param displayId display where the user will be visible
     * @param unlockListener Listener to be informed when the user has started and unlocked.
     *
     * @return whether the user was started
     */
    boolean startUserVisibleOnDisplay(@UserIdInt int userId, int displayId,
            @Nullable IProgressListener unlockListener) {
        checkCallingHasOneOfThosePermissions("startUserOnDisplay",
                MANAGE_USERS, INTERACT_ACROSS_USERS);

        try {
            return startUserNoChecks(userId, displayId, USER_START_MODE_BACKGROUND_VISIBLE,
                    unlockListener);
        } catch (RuntimeException e) {
            Slogf.e(TAG, "startUserOnSecondaryDisplay(%d, %d) failed: %s", userId, displayId, e);
            return false;
        }
    }

    private boolean startUserNoChecks(@UserIdInt int userId, int displayId,
            @UserStartMode int userStartMode, @Nullable IProgressListener unlockListener) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog();

        t.traceBegin("UserController.startUser-" + userId
                + (displayId == Display.DEFAULT_DISPLAY ? "" : "-display-" + displayId)
                + "-" + (userStartMode == USER_START_MODE_FOREGROUND ? "fg" : "bg")
                + "-start-mode-" + userStartMode);
        try {
            return startUserInternal(userId, displayId, userStartMode, unlockListener, t);
        } finally {
            t.traceEnd();
        }
    }

    private boolean startUserInternal(@UserIdInt int userId, int displayId,
            @UserStartMode int userStartMode, @Nullable IProgressListener unlockListener,
            TimingsTraceAndSlog t) {
        if (DEBUG_MU) {
            Slogf.i(TAG, "Starting user %d on display %d with mode  %s", userId, displayId,
                    userStartModeToString(userStartMode));
        }
        boolean foreground = userStartMode == USER_START_MODE_FOREGROUND;

        boolean onSecondaryDisplay = displayId != Display.DEFAULT_DISPLAY;
        if (onSecondaryDisplay) {
            Preconditions.checkArgument(!foreground, "Cannot start user %d in foreground AND "
                    + "on secondary display (%d)", userId, displayId);
        }
        EventLog.writeEvent(EventLogTags.UC_START_USER_INTERNAL, userId, foreground ? 1 : 0,
                displayId);

        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long ident = Binder.clearCallingIdentity();
        try {
            t.traceBegin("getStartedUserState");
            final int oldUserId = getCurrentUserId();
            if (oldUserId == userId) {
                final UserState state = getStartedUserState(userId);
                if (state == null) {
                    Slogf.wtf(TAG, "Current user has no UserState");
                    // continue starting.
                } else {
                    if (userId == UserHandle.USER_SYSTEM && state.state == STATE_BOOTING) {
                        // system user start explicitly requested. should continue starting as it
                        // is not in running state.
                    } else {
                        if (state.state == STATE_RUNNING_UNLOCKED) {
                            // We'll skip all later code, so we must tell listener it's already
                            // unlocked.
                            notifyFinished(userId, unlockListener);
                        }
                        t.traceEnd(); //getStartedUserState
                        return true;
                    }
                }
            }
            t.traceEnd(); //getStartedUserState

            if (foreground) {
                t.traceBegin("clearAllLockedTasks");
                mInjector.clearAllLockedTasks("startUser");
                t.traceEnd();
            }

            t.traceBegin("getUserInfo");
            final UserInfo userInfo = getUserInfo(userId);
            t.traceEnd();

            if (userInfo == null) {
                Slogf.w(TAG, "No user info for user #" + userId);
                return false;
            }
            if (foreground && userInfo.isProfile()) {
                Slogf.w(TAG, "Cannot switch to User #" + userId + ": not a full user");
                return false;
            }

            if ((foreground || onSecondaryDisplay) && userInfo.preCreated) {
                Slogf.w(TAG, "Cannot start pre-created user #" + userId + " in foreground or on "
                        + "secondary display");
                return false;
            }

            t.traceBegin("assignUserToDisplayOnStart");
            int result = mInjector.getUserManagerInternal().assignUserToDisplayOnStart(userId,
                    userInfo.profileGroupId, userStartMode, displayId);
            t.traceEnd();

            if (result == USER_ASSIGNMENT_RESULT_FAILURE) {
                Slogf.e(TAG, "%s user(%d) / display (%d) assignment failed: %s",
                        userStartModeToString(userStartMode), userId, displayId,
                        userAssignmentResultToString(result));
                return false;
            }

            boolean needStart = false;
            boolean updateUmState = false;
            UserState uss;

            // If the user we are switching to is not currently started, then
            // we need to start it now.
            t.traceBegin("updateStartedUserArrayStarting");
            synchronized (mLock) {
                uss = mStartedUsers.get(userId);
                if (uss == null) {
                    uss = new UserState(UserHandle.of(userId));
                    uss.mUnlockProgress.addListener(new UserProgressListener());
                    mStartedUsers.put(userId, uss);
                    updateStartedUserArrayLU();
                    needStart = true;
                    updateUmState = true;
                } else if (uss.state == UserState.STATE_SHUTDOWN) {
                    Slogf.i(TAG, "User #" + userId
                            + " is shutting down - will start after full shutdown");
                    mPendingUserStarts.add(new PendingUserStart(userId, userStartMode,
                            unlockListener));
                    t.traceEnd(); // updateStartedUserArrayStarting
                    return true;
                }
                final Integer userIdInt = userId;
                mUserLru.remove(userIdInt);
                mUserLru.add(userIdInt);
            }
            if (unlockListener != null) {
                uss.mUnlockProgress.addListener(unlockListener);
            }
            t.traceEnd(); // updateStartedUserArrayStarting

            if (updateUmState) {
                t.traceBegin("setUserState");
                mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                t.traceEnd();
            }
            t.traceBegin("updateConfigurationAndProfileIds");
            if (foreground) {
                // Make sure the old user is no longer considering the display to be on.
                mInjector.reportGlobalUsageEvent(UsageEvents.Event.SCREEN_NON_INTERACTIVE);
                boolean userSwitchUiEnabled;
                synchronized (mLock) {
                    mCurrentUserId = userId;
                    userSwitchUiEnabled = mUserSwitchUiEnabled;
                }
                mInjector.updateUserConfiguration();
                // NOTE: updateProfileRelatedCaches() is called on both if and else parts, ideally
                // it should be moved outside, but for now it's not as there are many calls to
                // external components here afterwards
                updateProfileRelatedCaches();
                mInjector.getWindowManager().setCurrentUser(userId);
                mInjector.reportCurWakefulnessUsageEvent();
                if (userSwitchUiEnabled) {
                    mInjector.getWindowManager().setSwitchingUser(true);
                }

            } else {
                final Integer currentUserIdInt = mCurrentUserId;
                updateProfileRelatedCaches();
                synchronized (mLock) {
                    mUserLru.remove(currentUserIdInt);
                    mUserLru.add(currentUserIdInt);
                }
            }
            t.traceEnd();

            // Make sure user is in the started state.  If it is currently
            // stopping, we need to knock that off.
            if (uss.state == UserState.STATE_STOPPING) {
                t.traceBegin("updateStateStopping");
                // If we are stopping, we haven't sent ACTION_SHUTDOWN,
                // so we can just fairly silently bring the user back from
                // the almost-dead.
                uss.setState(uss.lastState);
                mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                synchronized (mLock) {
                    updateStartedUserArrayLU();
                }
                needStart = true;
                t.traceEnd();
            } else if (uss.state == UserState.STATE_SHUTDOWN) {
                t.traceBegin("updateStateShutdown");
                // This means ACTION_SHUTDOWN has been sent, so we will
                // need to treat this as a new boot of the user.
                uss.setState(UserState.STATE_BOOTING);
                mInjector.getUserManagerInternal().setUserState(userId, uss.state);
                synchronized (mLock) {
                    updateStartedUserArrayLU();
                }
                needStart = true;
                t.traceEnd();
            }

            if (uss.state == UserState.STATE_BOOTING) {
                t.traceBegin("updateStateBooting");
                // Give user manager a chance to propagate user restrictions
                // to other services and prepare app storage
                mInjector.getUserManager().onBeforeStartUser(userId);

                // Booting up a new user, need to tell system services about it.
                // Note that this is on the same handler as scheduling of broadcasts,
                // which is important because it needs to go first.
                mHandler.sendMessage(mHandler.obtainMessage(USER_START_MSG, userId, NO_ARG2));
                t.traceEnd();
            }

            t.traceBegin("sendMessages");
            if (foreground) {
                mHandler.sendMessage(mHandler.obtainMessage(USER_CURRENT_MSG, userId, oldUserId));
                mHandler.removeMessages(REPORT_USER_SWITCH_MSG);
                mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
                mHandler.sendMessage(mHandler.obtainMessage(REPORT_USER_SWITCH_MSG,
                        oldUserId, userId, uss));
                mHandler.sendMessageDelayed(mHandler.obtainMessage(USER_SWITCH_TIMEOUT_MSG,
                        oldUserId, userId, uss), getUserSwitchTimeoutMs());
            }

            if (userInfo.preCreated) {
                needStart = false;
            }

            // In most cases, broadcast for the system user starting/started is sent by
            // ActivityManagerService#systemReady(). However on some HSUM devices (e.g. tablets)
            // the user switches from the system user to a secondary user while running
            // ActivityManagerService#systemReady(), thus broadcast is not sent for the system user.
            // Therefore we send the broadcast for the system user here as well in HSUM.
            // TODO(b/266158156): Improve/refactor the way broadcasts are sent for the system user
            // in HSUM. Ideally it'd be best to have one single place that sends this notification.
            final boolean isSystemUserInHeadlessMode = (userId == UserHandle.USER_SYSTEM)
                    && mInjector.isHeadlessSystemUserMode();
            if (needStart || isSystemUserInHeadlessMode) {
                sendUserStartedBroadcast(userId, callingUid, callingPid);
            }
            t.traceEnd();

            if (foreground) {
                t.traceBegin("moveUserToForeground");
                moveUserToForeground(uss, userId);
                t.traceEnd();
            } else {
                t.traceBegin("finishUserBoot");
                finishUserBoot(uss);
                t.traceEnd();
            }

            if (needStart || isSystemUserInHeadlessMode) {
                t.traceBegin("sendRestartBroadcast");
                sendUserStartingBroadcast(userId, callingUid, callingPid);
                t.traceEnd();
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return true;
    }

    /**
     * Start user, if it's not already running, and bring it to foreground.
     */
    void startUserInForeground(@UserIdInt int targetUserId) {
        if (android.multiuser.Flags.setPowerModeDuringUserSwitch()) {
            mInjector.setPerformancePowerMode(true);
        }
        boolean success = startUser(targetUserId, USER_START_MODE_FOREGROUND);
        if (!success) {
            mInjector.getWindowManager().setSwitchingUser(false);
            dismissUserSwitchDialog(this::endUserSwitch);
        }
    }

    boolean unlockUser(@UserIdInt int userId, @Nullable IProgressListener listener) {
        checkCallingPermission(INTERACT_ACROSS_USERS_FULL, "unlockUser");
        EventLog.writeEvent(EventLogTags.UC_UNLOCK_USER, userId);
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return maybeUnlockUser(userId, listener);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    private static void notifyFinished(@UserIdInt int userId,
            @Nullable IProgressListener listener) {
        if (listener == null) return;
        try {
            listener.onFinished(userId, null);
        } catch (RemoteException ignored) {
        }
    }

    private boolean maybeUnlockUser(@UserIdInt int userId) {
        return maybeUnlockUser(userId, null);
    }

    /**
     * Tries to unlock the given user.
     * <p>
     * This will succeed only if the user's CE storage key is already unlocked or if the user
     * doesn't have a lockscreen credential set.
     */
    private boolean maybeUnlockUser(@UserIdInt int userId, @Nullable IProgressListener listener) {

        // We cannot allow users to be unlocked before PHASE_BOOT_COMPLETED, for two reasons.
        // First, emulated volumes aren't supposed to be used until then; StorageManagerService
        // assumes it can reset everything upon reaching PHASE_BOOT_COMPLETED.  Second, on some
        // devices the Weaver HAL needed to unlock the user's storage isn't available until sometime
        // shortly before PHASE_BOOT_COMPLETED.  The below logic enforces a consistent flow across
        // all devices, regardless of their Weaver implementation.
        //
        // Any unlocks that get delayed by this will be done by onBootComplete() instead.
        if (!mAllowUserUnlocking) {
            Slogf.i(TAG, "Not unlocking user %d yet because boot hasn't completed", userId);
            notifyFinished(userId, listener);
            return false;
        }

        UserState uss;
        if (!StorageManager.isCeStorageUnlocked(userId)) {
            // We always want to try to unlock CE storage, even if the user is not started yet.
            mLockPatternUtils.unlockUserKeyIfUnsecured(userId);
        }
        synchronized (mLock) {
            // Register the given listener to watch for unlock progress
            uss = mStartedUsers.get(userId);
            if (uss != null) {
                uss.mUnlockProgress.addListener(listener);
            }
        }
        // Bail if user isn't actually running
        if (uss == null) {
            notifyFinished(userId, listener);
            return false;
        }

        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("finishUserUnlocking-" + userId);
        final boolean finishUserUnlockingResult = finishUserUnlocking(uss);
        t.traceEnd();
        if (!finishUserUnlockingResult) {
            notifyFinished(userId, listener);
            return false;
        }

        // We just unlocked a user, so let's now attempt to unlock any profiles under that user.

        // First, get list of userIds. Requires mLock, so we cannot make external calls, e.g. to UMS
        int[] userIds;
        synchronized (mLock) {
            userIds = new int[mStartedUsers.size()];
            for (int i = 0; i < userIds.length; i++) {
                userIds[i] = mStartedUsers.keyAt(i);
            }
        }
        for (int testUserId : userIds) {
            final UserInfo parent = mInjector.getUserManager().getProfileParent(testUserId);
            if (parent != null && parent.id == userId && testUserId != userId) {
                Slogf.d(TAG, "User " + testUserId + " (parent " + parent.id
                        + "): attempting unlock because parent was just unlocked");
                maybeUnlockUser(testUserId);
            }
        }

        return true;
    }

    boolean switchUser(final int targetUserId) {
        enforceShellRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, targetUserId);
        EventLog.writeEvent(EventLogTags.UC_SWITCH_USER, targetUserId);
        int currentUserId = getCurrentUserId();
        UserInfo targetUserInfo = getUserInfo(targetUserId);
        boolean userSwitchUiEnabled;
        synchronized (mLock) {
            if (targetUserId == currentUserId && mTargetUserId == UserHandle.USER_NULL) {
                Slogf.i(TAG, "user #" + targetUserId + " is already the current user");
                return true;
            }
            if (targetUserInfo == null) {
                Slogf.w(TAG, "No user info for user #" + targetUserId);
                return false;
            }
            if (!targetUserInfo.supportsSwitchTo()) {
                Slogf.w(TAG, "Cannot switch to User #" + targetUserId + ": not supported");
                return false;
            }
            if (FactoryResetter.isFactoryResetting()) {
                Slogf.w(TAG, "Cannot switch to User #" + targetUserId
                        + ": factory reset in progress");
                return false;
            }

            if (!mInitialized) {
                Slogf.e(TAG, "Cannot switch to User #" + targetUserId
                        + ": UserController not ready yet");
                return false;
            }
            if (mTargetUserId != UserHandle.USER_NULL) {
                Slogf.w(TAG, "There is already an ongoing user switch to User #" + mTargetUserId
                        + ". User #" + targetUserId + " will be added to the queue.");
                mPendingTargetUserIds.offer(targetUserId);
                return true;
            }
            mTargetUserId = targetUserId;
            userSwitchUiEnabled = mUserSwitchUiEnabled;
        }
        if (userSwitchUiEnabled) {
            UserInfo currentUserInfo = getUserInfo(currentUserId);
            Pair<UserInfo, UserInfo> userNames = new Pair<>(currentUserInfo, targetUserInfo);
            mUiHandler.removeMessages(START_USER_SWITCH_UI_MSG);
            mUiHandler.sendMessage(mUiHandler.obtainMessage(
                    START_USER_SWITCH_UI_MSG, userNames));
        } else {
            sendStartUserSwitchFgMessage(targetUserId);
        }
        return true;
    }

    private void sendStartUserSwitchFgMessage(int targetUserId) {
        mHandler.removeMessages(START_USER_SWITCH_FG_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(START_USER_SWITCH_FG_MSG, targetUserId, 0));
    }

    private void dismissUserSwitchDialog(Runnable onDismissed) {
        mUiHandler.post(() -> mInjector.dismissUserSwitchingDialog(onDismissed));
    }

    private void showUserSwitchDialog(Pair<UserInfo, UserInfo> fromToUserPair) {
        // The dialog will show and then initiate the user switch by calling startUserInForeground
        mInjector.showUserSwitchingDialog(fromToUserPair.first, fromToUserPair.second,
                getSwitchingFromSystemUserMessageUnchecked(),
                getSwitchingToSystemUserMessageUnchecked(),
                /* onShown= */ () -> sendStartUserSwitchFgMessage(fromToUserPair.second.id));
    }

    private void dispatchForegroundProfileChanged(@UserIdInt int userId) {
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
    @VisibleForTesting
    void dispatchUserSwitchComplete(@UserIdInt int oldUserId, @UserIdInt int newUserId) {
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("dispatchUserSwitchComplete-" + newUserId);
        mInjector.getWindowManager().setSwitchingUser(false);
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                t.traceBegin("onUserSwitchComplete-" + newUserId + " #" + i + " "
                        + mUserSwitchObservers.getBroadcastCookie(i));
                mUserSwitchObservers.getBroadcastItem(i).onUserSwitchComplete(newUserId);
                t.traceEnd();
            } catch (RemoteException e) {
                // Ignore
            }
        }
        mUserSwitchObservers.finishBroadcast();
        t.traceBegin("sendUserSwitchBroadcasts-" + oldUserId + "-" + newUserId);
        sendUserSwitchBroadcasts(oldUserId, newUserId);
        t.traceEnd();
        t.traceEnd();

        endUserSwitch();
    }

    private void endUserSwitch() {
        if (android.multiuser.Flags.setPowerModeDuringUserSwitch()) {
            mInjector.setPerformancePowerMode(false);
        }
        final int nextUserId;
        synchronized (mLock) {
            nextUserId = ObjectUtils.getOrElse(mPendingTargetUserIds.poll(), UserHandle.USER_NULL);
            mTargetUserId = UserHandle.USER_NULL;
        }
        if (nextUserId != UserHandle.USER_NULL) {
            switchUser(nextUserId);
        }
    }

    private void dispatchLockedBootComplete(@UserIdInt int userId) {
        final int observerCount = mUserSwitchObservers.beginBroadcast();
        for (int i = 0; i < observerCount; i++) {
            try {
                mUserSwitchObservers.getBroadcastItem(i).onLockedBootComplete(userId);
            } catch (RemoteException e) {
                // Ignore
            }
        }
        mUserSwitchObservers.finishBroadcast();
    }

    private void stopUserOnSwitchIfEnforced(@UserIdInt int oldUserId) {
        // Never stop system user
        if (oldUserId == UserHandle.USER_SYSTEM) {
            return;
        }
        boolean hasRestriction =
                hasUserRestriction(UserManager.DISALLOW_RUN_IN_BACKGROUND, oldUserId);
        synchronized (mLock) {
            // If running in background is disabled or mStopUserOnSwitch mode, stop the user.
            boolean disallowRunInBg = hasRestriction || shouldStopUserOnSwitch();
            if (!disallowRunInBg) {
                if (DEBUG_MU) {
                    Slogf.i(TAG, "stopUserOnSwitchIfEnforced() NOT stopping %d and related users",
                            oldUserId);
                }
                return;
            }
            if (DEBUG_MU) {
                Slogf.i(TAG, "stopUserOnSwitchIfEnforced() stopping %d and related users",
                        oldUserId);
            }
            stopUsersLU(oldUserId, /* force= */ false, /* allowDelayedLocking= */ true,
                    null, null);
        }
    }

    private void timeoutUserSwitch(UserState uss, int oldUserId, int newUserId) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("timeoutUserSwitch-" + oldUserId + "-to-" + newUserId);
        synchronized (mLock) {
            Slogf.e(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId);
            mTimeoutUserSwitchCallbacks = mCurWaitingUserSwitchCallbacks;
            mHandler.removeMessages(USER_SWITCH_CALLBACKS_TIMEOUT_MSG);
            sendContinueUserSwitchLU(uss, oldUserId, newUserId);
            // Report observers that never called back (USER_SWITCH_CALLBACKS_TIMEOUT)
            mHandler.sendMessageDelayed(mHandler.obtainMessage(USER_SWITCH_CALLBACKS_TIMEOUT_MSG,
                    oldUserId, newUserId), USER_SWITCH_CALLBACKS_TIMEOUT_MS);
        }
        t.traceEnd();
    }

    private void timeoutUserSwitchCallbacks(int oldUserId, int newUserId) {
        synchronized (mLock) {
            if (mTimeoutUserSwitchCallbacks != null && !mTimeoutUserSwitchCallbacks.isEmpty()) {
                Slogf.wtf(TAG, "User switch timeout: from " + oldUserId + " to " + newUserId
                        + ". Observers that didn't respond: " + mTimeoutUserSwitchCallbacks);
                mTimeoutUserSwitchCallbacks = null;
            }
        }
    }

    @VisibleForTesting
    void dispatchUserSwitch(final UserState uss, final int oldUserId, final int newUserId) {
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("dispatchUserSwitch-" + oldUserId + "-to-" + newUserId);

        EventLog.writeEvent(EventLogTags.UC_DISPATCH_USER_SWITCH, oldUserId, newUserId);

        final int observerCount = mUserSwitchObservers.beginBroadcast();
        if (observerCount > 0) {
            for (int i = 0; i < observerCount; i++) {
                final String name = "#" + i + " " + mUserSwitchObservers.getBroadcastCookie(i);
                t.traceBegin("onBeforeUserSwitching-" + name);
                try {
                    mUserSwitchObservers.getBroadcastItem(i).onBeforeUserSwitching(newUserId);
                } catch (RemoteException e) {
                    // Ignore
                } finally {
                    t.traceEnd();
                }
            }
            final ArraySet<String> curWaitingUserSwitchCallbacks = new ArraySet<>();
            synchronized (mLock) {
                uss.switching = true;
                mCurWaitingUserSwitchCallbacks = curWaitingUserSwitchCallbacks;
            }
            final AtomicInteger waitingCallbacksCount = new AtomicInteger(observerCount);
            final long userSwitchTimeoutMs = getUserSwitchTimeoutMs();
            final long dispatchStartedTime = SystemClock.elapsedRealtime();
            for (int i = 0; i < observerCount; i++) {
                final long dispatchStartedTimeForObserver = SystemClock.elapsedRealtime();
                try {
                    // Prepend with unique prefix to guarantee that keys are unique
                    final String name = "#" + i + " " + mUserSwitchObservers.getBroadcastCookie(i);
                    synchronized (mLock) {
                        curWaitingUserSwitchCallbacks.add(name);
                    }
                    final IRemoteCallback callback = new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle data) throws RemoteException {
                            asyncTraceEnd("onUserSwitching-" + name, newUserId);
                            synchronized (mLock) {
                                long delayForObserver = SystemClock.elapsedRealtime()
                                        - dispatchStartedTimeForObserver;
                                if (delayForObserver > LONG_USER_SWITCH_OBSERVER_WARNING_TIME_MS) {
                                    Slogf.w(TAG, "User switch slowed down by observer " + name
                                            + ": result took " + delayForObserver
                                            + " ms to process.");
                                }

                                long totalDelay = SystemClock.elapsedRealtime()
                                        - dispatchStartedTime;
                                if (totalDelay > userSwitchTimeoutMs) {
                                    Slogf.e(TAG, "User switch timeout: observer " + name
                                            + "'s result was received " + totalDelay
                                            + " ms after dispatchUserSwitch.");
                                }

                                curWaitingUserSwitchCallbacks.remove(name);
                                // Continue switching if all callbacks have been notified and
                                // user switching session is still valid
                                if (waitingCallbacksCount.decrementAndGet() == 0
                                        && (curWaitingUserSwitchCallbacks
                                        == mCurWaitingUserSwitchCallbacks)) {
                                    sendContinueUserSwitchLU(uss, oldUserId, newUserId);
                                }
                            }
                        }
                    };
                    asyncTraceBegin("onUserSwitching-" + name, newUserId);
                    mUserSwitchObservers.getBroadcastItem(i).onUserSwitching(newUserId, callback);
                } catch (RemoteException e) {
                    // Ignore
                }
            }
        } else {
            synchronized (mLock) {
                sendContinueUserSwitchLU(uss, oldUserId, newUserId);
            }
        }
        mUserSwitchObservers.finishBroadcast();
        t.traceEnd(); // end dispatchUserSwitch-
    }

    @GuardedBy("mLock")
    private void sendContinueUserSwitchLU(UserState uss, int oldUserId, int newUserId) {
        TimingsTraceAndSlog t = new TimingsTraceAndSlog(TAG);
        t.traceBegin("sendContinueUserSwitchLU-" + oldUserId + "-to-" + newUserId);
        mCurWaitingUserSwitchCallbacks = null;
        mHandler.removeMessages(USER_SWITCH_TIMEOUT_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(CONTINUE_USER_SWITCH_MSG,
                oldUserId, newUserId, uss));
        t.traceEnd();
    }

    @VisibleForTesting
    void continueUserSwitch(UserState uss, int oldUserId, int newUserId) {
        final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
        t.traceBegin("continueUserSwitch-" + oldUserId + "-to-" + newUserId);

        EventLog.writeEvent(EventLogTags.UC_CONTINUE_USER_SWITCH, oldUserId, newUserId);

        // Do the keyguard dismiss and dismiss the user switching dialog later
        mHandler.removeMessages(COMPLETE_USER_SWITCH_MSG);
        mHandler.sendMessage(mHandler.obtainMessage(
                COMPLETE_USER_SWITCH_MSG, oldUserId, newUserId));

        uss.switching = false;
        stopGuestOrEphemeralUserIfBackground(oldUserId);
        stopUserOnSwitchIfEnforced(oldUserId);

        t.traceEnd(); // end continueUserSwitch
    }

    @VisibleForTesting
    void completeUserSwitch(int oldUserId, int newUserId) {
        final Runnable sendUserSwitchCompleteMessage = () -> {
            mHandler.removeMessages(REPORT_USER_SWITCH_COMPLETE_MSG);
            mHandler.sendMessage(mHandler.obtainMessage(
                    REPORT_USER_SWITCH_COMPLETE_MSG, oldUserId, newUserId));
        };
        if (isUserSwitchUiEnabled()) {
            if (mInjector.getKeyguardManager().isDeviceSecure(newUserId)) {
                this.showKeyguard(() -> dismissUserSwitchDialog(sendUserSwitchCompleteMessage));
            } else {
                this.dismissKeyguard(() -> dismissUserSwitchDialog(sendUserSwitchCompleteMessage));
            }
        } else {
            sendUserSwitchCompleteMessage.run();
        }
    }

    protected void showKeyguard(Runnable runnable) {
        runWithTimeout(mInjector::showKeyguard, SHOW_KEYGUARD_TIMEOUT_MS, runnable, () -> {
            throw new RuntimeException(
                    "Keyguard is not shown in " + SHOW_KEYGUARD_TIMEOUT_MS + " ms.");
        }, "showKeyguard");
    }

    protected void dismissKeyguard(Runnable runnable) {
        runWithTimeout(mInjector::dismissKeyguard, DISMISS_KEYGUARD_TIMEOUT_MS, runnable, runnable,
                "dismissKeyguard");
    }

    private void runWithTimeout(Consumer<Runnable> task, int timeoutMs, Runnable onSuccess,
            Runnable onTimeout, String traceMsg) {
        final AtomicInteger state = new AtomicInteger(0); // state = 0 (RUNNING)

        asyncTraceBegin(traceMsg, 0);

        mHandler.postDelayed(() -> {
            if (state.compareAndSet(0, 1)) { // state = 1 (TIMEOUT)
                asyncTraceEnd(traceMsg, 0);
                Slogf.w(TAG, "Timeout: %s did not finish in %d ms", traceMsg, timeoutMs);
                onTimeout.run();
            }
        }, timeoutMs);

        task.accept(() -> {
            if (state.compareAndSet(0, 2)) { // state = 2 (SUCCESS)
                asyncTraceEnd(traceMsg, 0);
                onSuccess.run();
            }
        });
    }

    private void moveUserToForeground(UserState uss, int newUserId) {
        boolean homeInFront = mInjector.taskSupervisorSwitchUser(newUserId, uss);
        if (homeInFront) {
            mInjector.startHomeActivity(newUserId, "moveUserToForeground");
        } else {
            mInjector.taskSupervisorResumeFocusedStackTopActivity();
        }
        EventLogTags.writeAmSwitchUser(newUserId);
    }

    // The two methods sendUserStartedBroadcast() and sendUserStartingBroadcast()
    // could be merged for better reuse. However, the params they are calling broadcastIntent()
    // with are different - resultCode receiver, permissions, ordered, and userId, etc. Therefore,
    // we decided to keep two separate methods for better code readability/clarity.
    // TODO(b/266158156): Improve/refactor the way broadcasts are sent for the system user
    // in HSUM. Ideally it'd be best to have one single place that sends this notification.
    /** Sends {@code ACTION_USER_STARTED} broadcast. */
    void sendUserStartedBroadcast(@UserIdInt int userId, int callingUid, int callingPid) {
        if (userId == UserHandle.USER_SYSTEM) {
            synchronized (mLock) {
                // Make sure that the broadcast is sent only once for the system user.
                if (mIsBroadcastSentForSystemUserStarted) {
                    return;
                }
                mIsBroadcastSentForSystemUserStarted = true;
            }
        }
        final Intent intent = new Intent(Intent.ACTION_USER_STARTED);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mInjector.broadcastIntent(intent, /* resolvedType= */ null, /* resultTo= */ null,
                /* resultCode= */ 0, /* resultData= */ null, /* resultExtras= */ null,
                /* requiredPermissions= */ null, AppOpsManager.OP_NONE, /* bOptions= */ null,
                /* ordered= */ false, /* sticky= */ false, MY_PID, SYSTEM_UID,
                callingUid, callingPid, userId);
    }

    /** Sends {@code ACTION_USER_STARTING} broadcast. */
    void sendUserStartingBroadcast(@UserIdInt int userId, int callingUid, int callingPid) {
        if (userId == UserHandle.USER_SYSTEM) {
            synchronized (mLock) {
                // Make sure that the broadcast is sent only once for the system user.
                if (mIsBroadcastSentForSystemUserStarting) {
                    return;
                }
                mIsBroadcastSentForSystemUserStarting = true;
            }
        }
        final Intent intent = new Intent(Intent.ACTION_USER_STARTING);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, userId);
        mInjector.broadcastIntent(intent, /* resolvedType= */ null,
                new IIntentReceiver.Stub() {
                    @Override
                    public void performReceive(Intent intent, int resultCode,
                            String data, Bundle extras, boolean ordered,
                            boolean sticky,
                            int sendingUser) throws RemoteException {
                    }
                }, /* resultCode= */ 0, /* resultData= */ null, /* resultExtras= */ null,
                new String[]{INTERACT_ACROSS_USERS}, AppOpsManager.OP_NONE, /* bOptions= */ null,
                /* ordered= */ true, /* sticky= */ false, MY_PID, SYSTEM_UID,
                callingUid, callingPid, UserHandle.USER_ALL);
    }

    void sendUserSwitchBroadcasts(int oldUserId, int newUserId) {
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long ident = Binder.clearCallingIdentity();
        try {
            Intent intent;
            if (oldUserId >= 0) {
                // Send USER_BACKGROUND broadcast to all profiles of the outgoing user
                List<UserInfo> profiles = mInjector.getUserManager().getProfiles(oldUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_BACKGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    // Also, add the UserHandle for mainline modules which can't use the @hide
                    // EXTRA_USER_HANDLE.
                    intent.putExtra(Intent.EXTRA_USER, UserHandle.of(profileUserId));
                    mInjector.broadcastIntent(intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, callingUid, callingPid,
                            profileUserId);
                }
            }
            if (newUserId >= 0) {
                // Send USER_FOREGROUND broadcast to all profiles of the incoming user
                List<UserInfo> profiles = mInjector.getUserManager().getProfiles(newUserId, false);
                int count = profiles.size();
                for (int i = 0; i < count; i++) {
                    int profileUserId = profiles.get(i).id;
                    intent = new Intent(Intent.ACTION_USER_FOREGROUND);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                            | Intent.FLAG_RECEIVER_FOREGROUND);
                    intent.putExtra(Intent.EXTRA_USER_HANDLE, profileUserId);
                    // Also, add the UserHandle for mainline modules which can't use the @hide
                    // EXTRA_USER_HANDLE.
                    intent.putExtra(Intent.EXTRA_USER, UserHandle.of(profileUserId));
                    mInjector.broadcastIntent(intent,
                            null, null, 0, null, null, null, AppOpsManager.OP_NONE,
                            null, false, false, MY_PID, SYSTEM_UID, callingUid, callingPid,
                            profileUserId);
                }
                intent = new Intent(Intent.ACTION_USER_SWITCHED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_FOREGROUND);
                intent.putExtra(Intent.EXTRA_USER_HANDLE, newUserId);
                // Also, add the UserHandle for mainline modules which can't use the @hide
                // EXTRA_USER_HANDLE.
                intent.putExtra(Intent.EXTRA_USER, UserHandle.of(newUserId));
                mInjector.broadcastIntent(intent,
                        null, null, 0, null, null,
                        new String[] {android.Manifest.permission.MANAGE_USERS},
                        AppOpsManager.OP_NONE, null, false, false, MY_PID, SYSTEM_UID, callingUid,
                        callingPid, UserHandle.USER_ALL);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Broadcasts to the parent user when a profile is started+unlocked/stopped.
     * @param userId the id of the profile
     * @param parentId the id of the parent user
     * @param intentAction either ACTION_PROFILE_ACCESSIBLE or ACTION_PROFILE_INACCESSIBLE
     */
    private void broadcastProfileAccessibleStateChanged(@UserIdInt int userId,
            @UserIdInt int parentId,
            String intentAction) {
        final Intent intent = new Intent(intentAction);
        intent.putExtra(Intent.EXTRA_USER, UserHandle.of(userId));
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                | Intent.FLAG_RECEIVER_FOREGROUND);
        mInjector.broadcastIntent(intent, /* resolvedType= */ null, /* resultTo= */
                null, /* resultCode= */ 0, /* resultData= */ null, /* resultExtras= */
                null, /* requiredPermissions= */ null, AppOpsManager.OP_NONE, /* bOptions= */
                null, /* ordered= */ false, /* sticky= */ false, MY_PID, SYSTEM_UID,
                Binder.getCallingUid(), Binder.getCallingPid(), parentId);
    }

    int handleIncomingUser(int callingPid, int callingUid, @UserIdInt int userId, boolean allowAll,
            int allowMode, String name, String callerPackage) {
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }

        // Note that we may be accessing mCurrentUserId outside of a lock...
        // shouldn't be a big deal, if this is being called outside
        // of a locked context there is intrinsically a race with
        // the value the caller will receive and someone else changing it.
        // We assume that USER_CURRENT_OR_SELF will use the current user; later
        // we will switch to the calling user if access to the current user fails.
        int targetUserId = unsafeConvertIncomingUser(userId);

        if (callingUid != 0 && callingUid != SYSTEM_UID) {
            final boolean allow;
            final boolean isSameProfileGroup = isSameProfileGroup(callingUserId, targetUserId);
            if (mInjector.isCallerRecents(callingUid) && isSameProfileGroup) {
                // If the caller is Recents and the caller has ownership of the profile group,
                // we then allow it to access its profiles.
                allow = true;
            } else if (mInjector.checkComponentPermission(INTERACT_ACROSS_USERS_FULL, callingPid,
                    callingUid, -1, true) == PackageManager.PERMISSION_GRANTED) {
                // If the caller has this permission, they always pass go.  And collect $200.
                allow = true;
            } else if (allowMode == ALLOW_FULL_ONLY) {
                // We require full access, sucks to be you.
                allow = false;
            } else if (canInteractWithAcrossProfilesPermission(
                    allowMode, isSameProfileGroup, callingPid, callingUid, callerPackage)) {
                allow = true;
            } else if (mInjector.checkComponentPermission(INTERACT_ACROSS_USERS, callingPid,
                    callingUid, -1, true) != PackageManager.PERMISSION_GRANTED) {
                // If the caller does not have either permission, they are always doomed.
                allow = false;
            } else if (allowMode == ALLOW_NON_FULL || allowMode == ALLOW_PROFILES_OR_NON_FULL) {
                // We are blanket allowing non-full access, you lucky caller!
                allow = true;
            } else if (allowMode == ALLOW_NON_FULL_IN_PROFILE) {
                // We may or may not allow this depending on whether the two users are
                // in the same profile.
                allow = isSameProfileGroup;
            } else {
                throw new IllegalArgumentException("Unknown mode: " + allowMode);
            }
            if (!allow) {
                if (userId == UserHandle.USER_CURRENT_OR_SELF) {
                    // In this case, they would like to just execute as their
                    // owner user instead of failing.
                    targetUserId = callingUserId;
                } else {
                    StringBuilder builder = new StringBuilder(128);
                    builder.append("Permission Denial: ");
                    builder.append(name);
                    if (callerPackage != null) {
                        builder.append(" from ");
                        builder.append(callerPackage);
                    }
                    builder.append(" asks to run as user ");
                    builder.append(userId);
                    builder.append(" but is calling from uid ");
                    UserHandle.formatUid(builder, callingUid);
                    builder.append("; this requires ");
                    builder.append(INTERACT_ACROSS_USERS_FULL);
                    if (allowMode != ALLOW_FULL_ONLY) {
                        if (allowMode == ALLOW_NON_FULL
                                || allowMode == ALLOW_PROFILES_OR_NON_FULL
                                || (allowMode == ALLOW_NON_FULL_IN_PROFILE && isSameProfileGroup)) {
                            builder.append(" or ");
                            builder.append(INTERACT_ACROSS_USERS);
                        }
                        if (isSameProfileGroup && allowMode == ALLOW_PROFILES_OR_NON_FULL) {
                            builder.append(" or ");
                            builder.append(INTERACT_ACROSS_PROFILES);
                        }
                    }
                    String msg = builder.toString();
                    Slogf.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            }
        }
        if (!allowAll) {
            ensureNotSpecialUser(targetUserId);
        }
        // Check shell permission
        if (callingUid == Process.SHELL_UID && targetUserId >= UserHandle.USER_SYSTEM) {
            if (hasUserRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, targetUserId)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + targetUserId + "\n " + Debug.getCallers(3));
            }
        }
        return targetUserId;
    }

    private boolean canInteractWithAcrossProfilesPermission(
            int allowMode, boolean isSameProfileGroup, int callingPid, int callingUid,
            String callingPackage) {
        if (allowMode != ALLOW_PROFILES_OR_NON_FULL) {
            return false;
        }
        if (!isSameProfileGroup) {
            return false;
        }
        return mInjector.checkPermissionForPreflight(INTERACT_ACROSS_PROFILES, callingPid,
                callingUid, callingPackage);
    }

    int unsafeConvertIncomingUser(@UserIdInt int userId) {
        return (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF)
                ? getCurrentUserId(): userId;
    }

    void ensureNotSpecialUser(@UserIdInt int userId) {
        if (userId >= 0) {
            return;
        }
        throw new IllegalArgumentException("Call does not support special user #" + userId);
    }

    void registerUserSwitchObserver(IUserSwitchObserver observer, String name) {
        Objects.requireNonNull(name, "Observer name cannot be null");
        checkCallingPermission(INTERACT_ACROSS_USERS_FULL, "registerUserSwitchObserver");
        mUserSwitchObservers.register(observer, name);
    }

    void sendForegroundProfileChanged(@UserIdInt int userId) {
        mHandler.removeMessages(FOREGROUND_PROFILE_CHANGED_MSG);
        mHandler.obtainMessage(FOREGROUND_PROFILE_CHANGED_MSG, userId, 0).sendToTarget();
    }

    void unregisterUserSwitchObserver(IUserSwitchObserver observer) {
        mUserSwitchObservers.unregister(observer);
    }

    UserState getStartedUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            return mStartedUsers.get(userId);
        }
    }

    boolean hasStartedUserState(@UserIdInt int userId) {
        synchronized (mLock) {
            return mStartedUsers.get(userId) != null;
        }
    }

    @GuardedBy("mLock")
    private void updateStartedUserArrayLU() {
        int num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            // This list does not include stopping users.
            if (uss.state != UserState.STATE_STOPPING
                    && uss.state != UserState.STATE_SHUTDOWN) {
                num++;
            }
        }
        mStartedUserArray = new int[num];
        num = 0;
        for (int i = 0; i < mStartedUsers.size(); i++) {
            UserState uss = mStartedUsers.valueAt(i);
            if (uss.state != UserState.STATE_STOPPING
                    && uss.state != UserState.STATE_SHUTDOWN) {
                mStartedUserArray[num++] = mStartedUsers.keyAt(i);
            }
        }
    }

    @VisibleForTesting
    void setAllowUserUnlocking(boolean allowed) {
        mAllowUserUnlocking = allowed;
        if (DEBUG_MU) {
            Slogf.d(TAG, new Exception(), "setAllowUserUnlocking(%b)", allowed);
        }
    }

    void onBootComplete(IIntentReceiver resultTo) {
        // Now that PHASE_BOOT_COMPLETED has been reached, user unlocking is allowed.
        setAllowUserUnlocking(true);

        // Get a copy of mStartedUsers to use outside of lock.
        SparseArray<UserState> startedUsers;
        synchronized (mLock) {
            startedUsers = mStartedUsers.clone();
        }
        // In non-headless system user mode, call finishUserBoot() to transition the system user
        // from the BOOTING state to RUNNING_LOCKED, then to RUNNING_UNLOCKED if possible.
        //
        // In headless system user mode, additional users may have been started, and all users
        // (including the system user) that ever get started are started explicitly.  In this case,
        // we should *not* transition users out of the BOOTING state using finishUserBoot(), as that
        // doesn't handle issuing the needed onUserStarting() call, and it would just race with an
        // explicit start anyway.  We do, however, need to send the "locked boot complete" broadcast
        // as that got skipped earlier due to the *device* boot not being complete yet.
        // We also need to try to unlock all started users, since until now explicit user starts
        // didn't proceed to unlocking, due to it being too early in the device boot.
        //
        // USER_SYSTEM must be processed first.  It will be first in the array, as its ID is lowest.
        Preconditions.checkArgument(startedUsers.keyAt(0) == UserHandle.USER_SYSTEM);
        for (int i = 0; i < startedUsers.size(); i++) {
            int userId = startedUsers.keyAt(i);
            UserState uss = startedUsers.valueAt(i);
            if (!mInjector.isHeadlessSystemUserMode()) {
                finishUserBoot(uss, resultTo);
            } else {
                sendLockedBootCompletedBroadcast(resultTo, userId);
                maybeUnlockUser(userId);
            }
        }
    }

    void onSystemReady() {
        if (DEBUG_MU) {
            Slogf.d(TAG, "onSystemReady()");

        }
        mInjector.getUserManagerInternal().addUserLifecycleListener(mUserLifecycleListener);
        updateProfileRelatedCaches();
        mInjector.reportCurWakefulnessUsageEvent();
    }

    // TODO(b/266158156): remove this method if initial system user boot logic is refactored?
    void onSystemUserStarting() {
        if (!mInjector.isHeadlessSystemUserMode()) {
            // Don't need to call on HSUM because it will be called when the system user is
            // restarted on background
            mInjector.onUserStarting(UserHandle.USER_SYSTEM);
            mInjector.onSystemUserVisibilityChanged(/* visible= */ true);
        }
    }

    /**
     * Refreshes the internal caches related to user profiles.
     *
     * <p>It's called every time a user is started.
     */
    private void updateProfileRelatedCaches() {
        final List<UserInfo> profiles = mInjector.getUserManager().getProfiles(getCurrentUserId(),
                /* enabledOnly= */ false);
        int[] currentProfileIds = new int[profiles.size()]; // profiles will not be null
        for (int i = 0; i < currentProfileIds.length; i++) {
            currentProfileIds[i] = profiles.get(i).id;
        }
        final List<UserInfo> users = mInjector.getUserManager().getUsers(false);
        synchronized (mLock) {
            mCurrentProfileIds = currentProfileIds;

            mUserProfileGroupIds.clear();
            for (int i = 0; i < users.size(); i++) {
                UserInfo user = users.get(i);
                if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                    mUserProfileGroupIds.put(user.id, user.profileGroupId);
                }
            }
        }
    }

    int[] getStartedUserArray() {
        synchronized (mLock) {
            return mStartedUserArray;
        }
    }

    boolean isUserRunning(@UserIdInt int userId, int flags) {
        UserState state = getStartedUserState(userId);
        if (state == null) {
            return false;
        }
        if ((flags & ActivityManager.FLAG_OR_STOPPED) != 0) {
            return true;
        }
        if ((flags & ActivityManager.FLAG_AND_LOCKED) != 0) {
            switch (state.state) {
                case UserState.STATE_BOOTING:
                case UserState.STATE_RUNNING_LOCKED:
                    return true;
                default:
                    return false;
            }
        }
        if ((flags & ActivityManager.FLAG_AND_UNLOCKING_OR_UNLOCKED) != 0) {
            switch (state.state) {
                case UserState.STATE_RUNNING_UNLOCKING:
                case UserState.STATE_RUNNING_UNLOCKED:
                    return true;
                // In the stopping/shutdown state, return unlock state of the user's CE storage.
                case UserState.STATE_STOPPING:
                case UserState.STATE_SHUTDOWN:
                    return StorageManager.isCeStorageUnlocked(userId);
                default:
                    return false;
            }
        }
        if ((flags & ActivityManager.FLAG_AND_UNLOCKED) != 0) {
            switch (state.state) {
                case UserState.STATE_RUNNING_UNLOCKED:
                    return true;
                // In the stopping/shutdown state, return unlock state of the user's CE storage.
                case UserState.STATE_STOPPING:
                case UserState.STATE_SHUTDOWN:
                    return StorageManager.isCeStorageUnlocked(userId);
                default:
                    return false;
            }
        }

        return state.state != UserState.STATE_STOPPING && state.state != UserState.STATE_SHUTDOWN;
    }

    /**
     * Check if system user is already started. Unlike other user, system user is in STATE_BOOTING
     * even if it is not explicitly started. So isUserRunning cannot give the right state
     * to check if system user is started or not.
     * @return true if system user is started.
     */
    boolean isSystemUserStarted() {
        synchronized (mLock) {
            UserState uss = mStartedUsers.get(UserHandle.USER_SYSTEM);
            if (uss == null) {
                return false;
            }
            return uss.state == UserState.STATE_RUNNING_LOCKED
                || uss.state == UserState.STATE_RUNNING_UNLOCKING
                || uss.state == UserState.STATE_RUNNING_UNLOCKED;
        }
    }

    private void checkGetCurrentUserPermissions() {
        if ((mInjector.checkCallingPermission(INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) && (
                mInjector.checkCallingPermission(INTERACT_ACROSS_USERS_FULL)
                        != PackageManager.PERMISSION_GRANTED)) {
            String msg = "Permission Denial: getCurrentUser() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + INTERACT_ACROSS_USERS;
            Slogf.w(TAG, msg);
            throw new SecurityException(msg);
        }
    }

    UserInfo getCurrentUser() {
        checkGetCurrentUserPermissions();

        // Optimization - if there is no pending user switch, return user for current id
        // (no need to acquire lock because mTargetUserId and mCurrentUserId are volatile)
        if (mTargetUserId == UserHandle.USER_NULL) {
            return getUserInfo(mCurrentUserId);
        }
        synchronized (mLock) {
            return getCurrentUserLU();
        }
    }

    /**
     * Gets the current user id, but checking that caller has the proper permissions.
     */
    int getCurrentUserIdChecked() {
        checkGetCurrentUserPermissions();

        // Optimization - if there is no pending user switch, return current id
        // (no need to acquire lock because mTargetUserId and mCurrentUserId are volatile)
        if (mTargetUserId == UserHandle.USER_NULL) {
            return mCurrentUserId;
        }
        return getCurrentOrTargetUserId();
    }

    @GuardedBy("mLock")
    private UserInfo getCurrentUserLU() {
        int userId = getCurrentOrTargetUserIdLU();
        return getUserInfo(userId);
    }

    int getCurrentOrTargetUserId() {
        synchronized (mLock) {
            return getCurrentOrTargetUserIdLU();
        }
    }

    @GuardedBy("mLock")
    private int getCurrentOrTargetUserIdLU() {
        return mTargetUserId != UserHandle.USER_NULL ? mTargetUserId : mCurrentUserId;
    }

    Pair<Integer, Integer> getCurrentAndTargetUserIds() {
        synchronized (mLock) {
            return new Pair<>(mCurrentUserId, mTargetUserId);
        }
    }

    @GuardedBy("mLock")
    private int getCurrentUserIdLU() {
        return mCurrentUserId;
    }

    int getCurrentUserId() {
        synchronized (mLock) {
            return mCurrentUserId;
        }
    }

    @GuardedBy("mLock")
    private boolean isCurrentUserLU(@UserIdInt int userId) {
        return userId == getCurrentOrTargetUserIdLU();
    }

    /** Returns whether the user is always-visible (such as a communal profile). */
    private boolean isAlwaysVisibleUser(@UserIdInt int userId) {
        final UserProperties properties = getUserProperties(userId);
        return properties != null && properties.getAlwaysVisible();
    }

    int[] getUsers() {
        UserManagerService ums = mInjector.getUserManager();
        return ums != null ? ums.getUserIds() : new int[] { 0 };
    }

    private UserInfo getUserInfo(@UserIdInt int userId) {
        return mInjector.getUserManager().getUserInfo(userId);
    }

    private @Nullable UserProperties getUserProperties(@UserIdInt int userId) {
        return mInjector.getUserManagerInternal().getUserProperties(userId);
    }

    int[] getUserIds() {
        return mInjector.getUserManager().getUserIds();
    }

    /**
     * If {@code userId} is {@link UserHandle#USER_ALL}, then return an array with all running user
     * IDs. Otherwise return an array whose only element is the given user id.
     *
     * It doesn't handle other special user IDs such as {@link UserHandle#USER_CURRENT}.
     */
    int[] expandUserId(@UserIdInt int userId) {
        if (userId != UserHandle.USER_ALL) {
            return new int[] {userId};
        } else {
            return getUsers();
        }
    }

    boolean exists(@UserIdInt int userId) {
        return mInjector.getUserManager().exists(userId);
    }

    private void checkCallingPermission(String permission, String methodName) {
        checkCallingHasOneOfThosePermissions(methodName, permission);
    }

    private void checkCallingHasOneOfThosePermissions(String methodName, String...permissions) {
        for (String permission : permissions) {
            if (mInjector.checkCallingPermission(permission) == PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }
        String msg = "Permission denial: " + methodName
                + "() from pid=" + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires "
                + (permissions.length == 1
                        ? permissions[0]
                        : "one of " + Arrays.toString(permissions));
        Slogf.w(TAG, msg);
        throw new SecurityException(msg);
    }

    private void enforceShellRestriction(String restriction, @UserIdInt int userId) {
        if (Binder.getCallingUid() == SHELL_UID) {
            if (userId < 0 || hasUserRestriction(restriction, userId)) {
                throw new SecurityException("Shell does not have permission to access user "
                        + userId);
            }
        }
    }

    boolean hasUserRestriction(String restriction, @UserIdInt int userId) {
        return mInjector.getUserManager().hasUserRestriction(restriction, userId);
    }

    /** Returns whether the two users are in the same profile group. */
    boolean isSameProfileGroup(int callingUserId, int targetUserId) {
        if (callingUserId == targetUserId) {
            return true;
        }
        synchronized (mLock) {
            int callingProfile = mUserProfileGroupIds.get(callingUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            int targetProfile = mUserProfileGroupIds.get(targetUserId,
                    UserInfo.NO_PROFILE_GROUP_ID);
            return callingProfile != UserInfo.NO_PROFILE_GROUP_ID
                    && callingProfile == targetProfile;
        }
    }

    boolean isUserOrItsParentRunning(@UserIdInt int userId) {
        synchronized (mLock) {
            if (isUserRunning(userId, 0)) {
                return true;
            }
            final int parentUserId = mUserProfileGroupIds.get(userId, UserInfo.NO_PROFILE_GROUP_ID);
            if (parentUserId == UserInfo.NO_PROFILE_GROUP_ID) {
                return false;
            }
            return isUserRunning(parentUserId, 0);
        }
    }

    boolean isCurrentProfile(@UserIdInt int userId) {
        synchronized (mLock) {
            return ArrayUtils.contains(mCurrentProfileIds, userId);
        }
    }

    int[] getCurrentProfileIds() {
        synchronized (mLock) {
            return mCurrentProfileIds;
        }
    }

    private void onUserAdded(UserInfo user) {
        if (!user.isProfile()) {
            return;
        }
        synchronized (mLock) {
            if (user.profileGroupId == mCurrentUserId) {
                mCurrentProfileIds = ArrayUtils.appendInt(mCurrentProfileIds, user.id);
            }
            if (user.profileGroupId != UserInfo.NO_PROFILE_GROUP_ID) {
                mUserProfileGroupIds.put(user.id, user.profileGroupId);
            }
        }
    }

    void onUserRemoved(@UserIdInt int userId) {
        synchronized (mLock) {
            int size = mUserProfileGroupIds.size();
            for (int i = size - 1; i >= 0; i--) {
                if (mUserProfileGroupIds.keyAt(i) == userId
                        || mUserProfileGroupIds.valueAt(i) == userId) {
                    mUserProfileGroupIds.removeAt(i);

                }
            }
            mCurrentProfileIds = ArrayUtils.removeInt(mCurrentProfileIds, userId);
        }
    }

    /**
     * Returns whether the given user requires credential entry at this time. This is used to
     * intercept activity launches for apps corresponding to locked profiles due to separate
     * challenge being triggered or when the profile user is yet to be unlocked.
     */
    protected boolean shouldConfirmCredentials(@UserIdInt int userId) {
        if (getStartedUserState(userId) == null) {
            return false;
        }
        final UserProperties properties = getUserProperties(userId);
        if (properties == null || !properties.isCredentialShareableWithParent()) {
            return false;
        }
        if (mLockPatternUtils.isSeparateProfileChallengeEnabled(userId)) {
            final KeyguardManager km = mInjector.getKeyguardManager();
            return km.isDeviceLocked(userId) && km.isDeviceSecure(userId);
        } else {
            // For unified challenge, need to confirm credential if user is RUNNING_LOCKED.
            return isUserRunning(userId, ActivityManager.FLAG_AND_LOCKED);
        }
    }

    boolean isLockScreenDisabled(@UserIdInt int userId) {
        return mLockPatternUtils.isLockScreenDisabled(userId);
    }

    void setSwitchingFromSystemUserMessage(String switchingFromSystemUserMessage) {
        synchronized (mLock) {
            mSwitchingFromSystemUserMessage = switchingFromSystemUserMessage;
        }
    }

    void setSwitchingToSystemUserMessage(String switchingToSystemUserMessage) {
        synchronized (mLock) {
            mSwitchingToSystemUserMessage = switchingToSystemUserMessage;
        }
    }

    // Called by AMS, must check permission
    String getSwitchingFromSystemUserMessage() {
        checkHasManageUsersPermission("getSwitchingFromSystemUserMessage()");

        return getSwitchingFromSystemUserMessageUnchecked();
    }

    // Called by AMS, must check permission
    String getSwitchingToSystemUserMessage() {
        checkHasManageUsersPermission("getSwitchingToSystemUserMessage()");

        return getSwitchingToSystemUserMessageUnchecked();
    }

    private String getSwitchingFromSystemUserMessageUnchecked() {
        synchronized (mLock) {
            return mSwitchingFromSystemUserMessage;
        }
    }

    private String getSwitchingToSystemUserMessageUnchecked() {
        synchronized (mLock) {
            return mSwitchingToSystemUserMessage;
        }
    }

    private void checkHasManageUsersPermission(String operation) {
        if (mInjector.checkCallingPermission(
                android.Manifest.permission.MANAGE_USERS) == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException(
                    "You need MANAGE_USERS permission to call " + operation);
        }
    }

    void dumpDebug(ProtoOutputStream proto, long fieldId) {
        synchronized (mLock) {
            long token = proto.start(fieldId);
            for (int i = 0; i < mStartedUsers.size(); i++) {
                UserState uss = mStartedUsers.valueAt(i);
                final long uToken = proto.start(UserControllerProto.STARTED_USERS);
                proto.write(UserControllerProto.User.ID, uss.mHandle.getIdentifier());
                uss.dumpDebug(proto, UserControllerProto.User.STATE);
                proto.end(uToken);
            }
            for (int i = 0; i < mStartedUserArray.length; i++) {
                proto.write(UserControllerProto.STARTED_USER_ARRAY, mStartedUserArray[i]);
            }
            for (int i = 0; i < mUserLru.size(); i++) {
                proto.write(UserControllerProto.USER_LRU, mUserLru.get(i));
            }
            if (mUserProfileGroupIds.size() > 0) {
                for (int i = 0; i < mUserProfileGroupIds.size(); i++) {
                    final long uToken = proto.start(UserControllerProto.USER_PROFILE_GROUP_IDS);
                    proto.write(UserControllerProto.UserProfile.USER,
                            mUserProfileGroupIds.keyAt(i));
                    proto.write(UserControllerProto.UserProfile.PROFILE,
                            mUserProfileGroupIds.valueAt(i));
                    proto.end(uToken);
                }
            }
            proto.write(UserControllerProto.CURRENT_USER, mCurrentUserId);
            for (int i = 0; i < mCurrentProfileIds.length; i++) {
                proto.write(UserControllerProto.CURRENT_PROFILES, mCurrentProfileIds[i]);
            }
            proto.end(token);
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("  mStartedUsers:");
            for (int i = 0; i < mStartedUsers.size(); i++) {
                UserState uss = mStartedUsers.valueAt(i);
                pw.print("    User #");
                pw.print(uss.mHandle.getIdentifier());
                pw.print(": ");
                uss.dump("", pw);
            }
            pw.print("  mStartedUserArray: [");
            for (int i = 0; i < mStartedUserArray.length; i++) {
                if (i > 0)
                    pw.print(", ");
                pw.print(mStartedUserArray[i]);
            }
            pw.println("]");
            pw.print("  mUserLru: [");
            for (int i = 0; i < mUserLru.size(); i++) {
                if (i > 0)
                    pw.print(", ");
                pw.print(mUserLru.get(i));
            }
            pw.println("]");
            if (mUserProfileGroupIds.size() > 0) {
                pw.println("  mUserProfileGroupIds:");
                for (int i=0; i< mUserProfileGroupIds.size(); i++) {
                    pw.print("    User #");
                    pw.print(mUserProfileGroupIds.keyAt(i));
                    pw.print(" -> profile #");
                    pw.println(mUserProfileGroupIds.valueAt(i));
                }
            }
            pw.println("  mCurrentProfileIds:" + Arrays.toString(mCurrentProfileIds));
            pw.println("  mCurrentUserId:" + mCurrentUserId);
            pw.println("  mTargetUserId:" + mTargetUserId);
            pw.println("  mLastActiveUsersForDelayedLocking:" + mLastActiveUsersForDelayedLocking);
            pw.println("  mDelayUserDataLocking:" + mDelayUserDataLocking);
            pw.println("  mAllowUserUnlocking:" + mAllowUserUnlocking);
            pw.println("  shouldStopUserOnSwitch():" + shouldStopUserOnSwitch());
            pw.println("  mStopUserOnSwitch:" + mStopUserOnSwitch);
            pw.println("  mMaxRunningUsers:" + mMaxRunningUsers);
            pw.println("  mUserSwitchUiEnabled:" + mUserSwitchUiEnabled);
            pw.println("  mInitialized:" + mInitialized);
            pw.println("  mIsBroadcastSentForSystemUserStarted:"
                    + mIsBroadcastSentForSystemUserStarted);
            pw.println("  mIsBroadcastSentForSystemUserStarting:"
                    + mIsBroadcastSentForSystemUserStarting);
            if (mSwitchingFromSystemUserMessage != null) {
                pw.println("  mSwitchingFromSystemUserMessage: " + mSwitchingFromSystemUserMessage);
            }
            if (mSwitchingToSystemUserMessage != null) {
                pw.println("  mSwitchingToSystemUserMessage: " + mSwitchingToSystemUserMessage);
            }
            pw.println("  mLastUserUnlockingUptime: " + mLastUserUnlockingUptime);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case START_USER_SWITCH_FG_MSG:
                logUserJourneyBegin(msg.arg1, USER_JOURNEY_USER_SWITCH_FG);
                startUserInForeground(msg.arg1);
                break;
            case REPORT_USER_SWITCH_MSG:
                dispatchUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                break;
            case CONTINUE_USER_SWITCH_MSG:
                continueUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                break;
            case USER_SWITCH_TIMEOUT_MSG:
                timeoutUserSwitch((UserState) msg.obj, msg.arg1, msg.arg2);
                break;
            case USER_SWITCH_CALLBACKS_TIMEOUT_MSG:
                timeoutUserSwitchCallbacks(msg.arg1, msg.arg2);
                break;
            case START_PROFILES_MSG:
                startProfiles();
                break;
            case USER_START_MSG:
                mInjector.batteryStatsServiceNoteEvent(
                        BatteryStats.HistoryItem.EVENT_USER_RUNNING_START,
                        Integer.toString(msg.arg1), msg.arg1);
                logUserJourneyBegin(msg.arg1, USER_JOURNEY_USER_START);

                mInjector.onUserStarting(/* userId= */ msg.arg1);
                scheduleOnUserCompletedEvent(msg.arg1,
                        UserCompletedEventType.EVENT_TYPE_USER_STARTING,
                        USER_COMPLETED_EVENT_DELAY_MS);

                mInjector.getUserJourneyLogger().logUserJourneyFinish(-1 , getUserInfo(msg.arg1),
                        USER_JOURNEY_USER_START);
                break;
            case USER_UNLOCK_MSG:
                final int userId = msg.arg1;
                mInjector.getSystemServiceManager().onUserUnlocking(userId);
                // Loads recents on a worker thread that allows disk I/O
                FgThread.getHandler().post(() -> {
                    mInjector.loadUserRecents(userId);
                });

                mInjector.getUserJourneyLogger().logUserLifecycleEvent(msg.arg1,
                        USER_LIFECYCLE_EVENT_UNLOCKING_USER, EVENT_STATE_FINISH);
                mInjector.getUserJourneyLogger().logUserLifecycleEvent(msg.arg1,
                        USER_LIFECYCLE_EVENT_UNLOCKED_USER, EVENT_STATE_BEGIN);

                final TimingsTraceAndSlog t = new TimingsTraceAndSlog();
                t.traceBegin("finishUserUnlocked-" + userId);
                finishUserUnlocked((UserState) msg.obj);
                t.traceEnd();
                break;
            case USER_UNLOCKED_MSG:
                mInjector.getSystemServiceManager().onUserUnlocked(msg.arg1);
                scheduleOnUserCompletedEvent(msg.arg1,
                        UserCompletedEventType.EVENT_TYPE_USER_UNLOCKED,
                        // If it's the foreground user, we wait longer to let it fully load.
                        // Else, there's nothing specific to wait for, so we basically just proceed.
                        // (No need to acquire lock to read mCurrentUserId since it is volatile.)
                        // TODO: Find something to wait for in the case of a profile.
                        mCurrentUserId == msg.arg1 ? USER_COMPLETED_EVENT_DELAY_MS : 1000);
                mInjector.getUserJourneyLogger().logUserLifecycleEvent(msg.arg1,
                        USER_LIFECYCLE_EVENT_UNLOCKED_USER, EVENT_STATE_FINISH);
                // Unlocking user is not a journey no need to clear sessionId
                break;
            case USER_CURRENT_MSG:
                mInjector.batteryStatsServiceNoteEvent(
                        BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_FINISH,
                        Integer.toString(msg.arg2), msg.arg2);
                mInjector.batteryStatsServiceNoteEvent(
                        BatteryStats.HistoryItem.EVENT_USER_FOREGROUND_START,
                        Integer.toString(msg.arg1), msg.arg1);

                mInjector.getSystemServiceManager().onUserSwitching(msg.arg2, msg.arg1);
                scheduleOnUserCompletedEvent(msg.arg1,
                        UserCompletedEventType.EVENT_TYPE_USER_SWITCHING,
                        USER_COMPLETED_EVENT_DELAY_MS);
                break;
            case USER_COMPLETED_EVENT_MSG:
                reportOnUserCompletedEvent((Integer) msg.obj);
                break;
            case FOREGROUND_PROFILE_CHANGED_MSG:
                dispatchForegroundProfileChanged(msg.arg1);
                break;
            case REPORT_USER_SWITCH_COMPLETE_MSG:
                dispatchUserSwitchComplete(msg.arg1, msg.arg2);
                UserJourneySession session = mInjector.getUserJourneyLogger()
                        .logUserSwitchJourneyFinish(msg.arg1, getUserInfo(msg.arg2));
                if (session != null) {
                    mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, session);
                }
                break;
            case REPORT_LOCKED_BOOT_COMPLETE_MSG:
                dispatchLockedBootComplete(msg.arg1);
                break;
            case START_USER_SWITCH_UI_MSG:
                final Pair<UserInfo, UserInfo> fromToUserPair = (Pair<UserInfo, UserInfo>) msg.obj;
                logUserJourneyBegin(fromToUserPair.second.id, USER_JOURNEY_USER_SWITCH_UI);
                showUserSwitchDialog(fromToUserPair);
                break;
            case CLEAR_USER_JOURNEY_SESSION_MSG:
                mInjector.getUserJourneyLogger()
                        .finishAndClearIncompleteUserJourney(msg.arg1, msg.arg2);
                mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, msg.obj);
                break;
            case COMPLETE_USER_SWITCH_MSG:
                completeUserSwitch(msg.arg1, msg.arg2);
                break;
        }
        return false;
    }

    /**
     * Schedules {@link SystemServiceManager#onUserCompletedEvent()} with the given
     * {@link UserCompletedEventType} event, which will be combined with any other events for that
     * user already scheduled.
     * If it isn't rescheduled first, it will fire after a delayMs delay.
     *
     * @param eventType event type flags from {@link UserCompletedEventType} to append to the
     *                  schedule. Use 0 to schedule the ssm call without modifying the event types.
     */
    // TODO(b/197344658): Also call scheduleOnUserCompletedEvent(userId, 0, 0) after switch UX done.
    @VisibleForTesting
    void scheduleOnUserCompletedEvent(
            int userId, @UserCompletedEventType.EventTypesFlag int eventType, int delayMs) {

        if (eventType != 0) {
            synchronized (mCompletedEventTypes) {
                mCompletedEventTypes.put(userId, mCompletedEventTypes.get(userId, 0) | eventType);
            }
        }

        final Object msgObj = userId;
        mHandler.removeEqualMessages(USER_COMPLETED_EVENT_MSG, msgObj);
        mHandler.sendMessageDelayed(
                mHandler.obtainMessage(USER_COMPLETED_EVENT_MSG, msgObj),
                delayMs);
    }

    /**
     * Calls {@link SystemServiceManager#onUserCompletedEvent()} for the given user, sending all the
     * {@link UserCompletedEventType} events that have been scheduled for it if they are still
     * applicable.
     *
     * Called on the mHandler thread.
     */
    @VisibleForTesting
    void reportOnUserCompletedEvent(Integer userId) {
        mHandler.removeEqualMessages(USER_COMPLETED_EVENT_MSG, userId);

        int eventTypes;
        synchronized (mCompletedEventTypes) {
            eventTypes = mCompletedEventTypes.get(userId, 0);
            mCompletedEventTypes.delete(userId);
        }

        // Now, remove any eventTypes that are no longer true.
        int eligibleEventTypes = 0;
        synchronized (mLock) {
            final UserState uss = mStartedUsers.get(userId);
            if (uss != null && uss.state != UserState.STATE_SHUTDOWN) {
                eligibleEventTypes |= UserCompletedEventType.EVENT_TYPE_USER_STARTING;
            }
            if (uss != null && uss.state == STATE_RUNNING_UNLOCKED) {
                eligibleEventTypes |= UserCompletedEventType.EVENT_TYPE_USER_UNLOCKED;
            }
            if (userId == mCurrentUserId) {
                eligibleEventTypes |= UserCompletedEventType.EVENT_TYPE_USER_SWITCHING;
            }
        }
        Slogf.i(TAG, "reportOnUserCompletedEvent(%d): stored=%s, eligible=%s", userId,
                Integer.toBinaryString(eventTypes), Integer.toBinaryString(eligibleEventTypes));
        eventTypes &= eligibleEventTypes;

        mInjector.systemServiceManagerOnUserCompletedEvent(userId, eventTypes);
    }

    /**
     * statsd helper method for logging the start of a user journey via a UserLifecycleEventOccurred
     * atom given the originating and targeting users for the journey.
     */
    private void logUserJourneyBegin(int targetId,
            @UserJourneyLogger.UserJourney int journey) {
        UserJourneySession oldSession = mInjector.getUserJourneyLogger()
                    .finishAndClearIncompleteUserJourney(targetId, journey);
        if (oldSession != null) {
            if (DEBUG_MU) {
                Slogf.d(TAG,
                        "Starting a new journey: " + journey + " with session id: "
                                + oldSession);
            }
            /*
             * User lifecycle journey would be complete when {@code #clearSessionId} is called
             * after the last expected lifecycle event for the journey. It may be possible that
             * the last event is not called, e.g., user not unlocked after user switching. In such
             * cases user journey is cleared after {@link USER_JOURNEY_TIMEOUT}.
             */
            mHandler.removeMessages(CLEAR_USER_JOURNEY_SESSION_MSG, oldSession);
        }
        UserJourneySession newSession = mInjector.getUserJourneyLogger()
                .logUserJourneyBegin(targetId, journey);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(CLEAR_USER_JOURNEY_SESSION_MSG,
                targetId, /* arg2= */ journey, newSession), USER_JOURNEY_TIMEOUT_MS);

    }

    BroadcastOptions getTemporaryAppAllowlistBroadcastOptions(
            @PowerWhitelistManager.ReasonCode int reasonCode) {
        long duration = 10_000;
        final ActivityManagerInternal amInternal =
                LocalServices.getService(ActivityManagerInternal.class);
        if (amInternal != null) {
            duration = amInternal.getBootTimeTempAllowListDuration();
        }
        final BroadcastOptions bOptions = BroadcastOptions.makeBasic();
        bOptions.setTemporaryAppAllowlist(duration,
                TEMPORARY_ALLOWLIST_TYPE_FOREGROUND_SERVICE_ALLOWED, reasonCode, "");
        return bOptions;
    }

    private static int getUserSwitchTimeoutMs() {
        final String userSwitchTimeoutMs = SystemProperties.get(
                "debug.usercontroller.user_switch_timeout_ms");
        if (!TextUtils.isEmpty(userSwitchTimeoutMs)) {
            try {
                return Integer.parseInt(userSwitchTimeoutMs);
            } catch (NumberFormatException ignored) {
                // Ignored.
            }
        }
        return DEFAULT_USER_SWITCH_TIMEOUT_MS;
    }

    private static void asyncTraceBegin(String msg, int cookie) {
        Slogf.d(TAG, "%s - asyncTraceBegin(%d)", msg, cookie);
        Trace.asyncTraceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, msg, cookie);
    }

    private static void asyncTraceEnd(String msg, int cookie) {
        Trace.asyncTraceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER, msg, cookie);
        Slogf.d(TAG, "%s - asyncTraceEnd(%d)", msg, cookie);
    }

    /**
     * Uptime when any user was being unlocked most recently. 0 if no users have been unlocked
     * yet. To avoid lock contention (since it's used by OomAdjuster), it's volatile internally.
     */
    public long getLastUserUnlockingUptime() {
        return mLastUserUnlockingUptime;
    }

    private static class UserProgressListener extends IProgressListener.Stub {
        private volatile long mUnlockStarted;
        @Override
        public void onStarted(int id, Bundle extras) throws RemoteException {
            Slogf.d(TAG, "Started unlocking user " + id);
            mUnlockStarted = SystemClock.uptimeMillis();
        }

        @Override
        public void onProgress(int id, int progress, Bundle extras) throws RemoteException {
            Slogf.d(TAG, "Unlocking user " + id + " progress " + progress);
        }

        @Override
        public void onFinished(int id, Bundle extras) throws RemoteException {
            long unlockTime = SystemClock.uptimeMillis() - mUnlockStarted;

            // Report system user unlock time to perf dashboard
            if (id == UserHandle.USER_SYSTEM) {
                new TimingsTraceAndSlog().logDuration("SystemUserUnlock", unlockTime);
            } else {
                new TimingsTraceAndSlog().logDuration("User" + id + "Unlock", unlockTime);
            }
        }
    }

    /**
     * Helper class for keeping track of user starts which are paused while user's
     * shutdown is taking place.
     */
    private static class PendingUserStart {
        public final @UserIdInt int userId;
        public final @UserStartMode int userStartMode;
        public final IProgressListener unlockListener;

        PendingUserStart(int userId, @UserStartMode int userStartMode,
                IProgressListener unlockListener) {
            this.userId = userId;
            this.userStartMode = userStartMode;
            this.unlockListener = unlockListener;
        }

        @Override
        public String toString() {
            return "PendingUserStart{"
                    + "userId=" + userId
                    + ", userStartMode=" + userStartModeToString(userStartMode)
                    + ", unlockListener=" + unlockListener
                    + '}';
        }
    }

    @VisibleForTesting
    static class Injector {
        private final ActivityManagerService mService;
        private UserManagerService mUserManager;
        private UserManagerInternal mUserManagerInternal;
        private PowerManagerInternal mPowerManagerInternal;
        private Handler mHandler;
        private final Object mUserSwitchingDialogLock = new Object();
        @GuardedBy("mUserSwitchingDialogLock")
        private UserSwitchingDialog mUserSwitchingDialog;

        Injector(ActivityManagerService service) {
            mService = service;
        }

        protected Handler getHandler(Handler.Callback callback) {
            return mHandler = new Handler(mService.mHandlerThread.getLooper(), callback);
        }

        protected Handler getUiHandler(Handler.Callback callback) {
            return new Handler(mService.mUiHandler.getLooper(), callback);
        }

        protected UserJourneyLogger getUserJourneyLogger() {
            return getUserManager().getUserJourneyLogger();
        }

        protected Context getContext() {
            return mService.mContext;
        }

        protected LockPatternUtils getLockPatternUtils() {
            return new LockPatternUtils(getContext());
        }

        protected int broadcastIntent(Intent intent, String resolvedType,
                IIntentReceiver resultTo, int resultCode, String resultData,
                Bundle resultExtras, String[] requiredPermissions, int appOp, Bundle bOptions,
                boolean ordered, boolean sticky, int callingPid, int callingUid, int realCallingUid,
                int realCallingPid, @UserIdInt int userId) {

            int logUserId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL);
            if (logUserId == UserHandle.USER_NULL) {
                logUserId = userId;
            }
            EventLog.writeEvent(EventLogTags.UC_SEND_USER_BROADCAST, logUserId, intent.getAction());

            // When the modern broadcast stack is enabled, deliver all our
            // broadcasts as unordered, since the modern stack has better
            // support for sequencing cold-starts, and it supports delivering
            // resultTo for non-ordered broadcasts
            if (mService.mEnableModernQueue) {
                ordered = false;
            }

            TimingsTraceAndSlog t = new TimingsTraceAndSlog();
            // TODO b/64165549 Verify that mLock is not held before calling AMS methods
            synchronized (mService) {
                t.traceBegin("broadcastIntent-" + userId + "-" + intent.getAction());
                final int result = mService.broadcastIntentLocked(null, null, null, intent,
                        resolvedType, resultTo, resultCode, resultData, resultExtras,
                        requiredPermissions, null, null, appOp, bOptions, ordered, sticky,
                        callingPid, callingUid, realCallingUid, realCallingPid, userId);
                t.traceEnd();
                return result;
            }
        }

        int checkCallingPermission(String permission) {
            return mService.checkCallingPermission(permission);
        }

        WindowManagerService getWindowManager() {
            return mService.mWindowManager;
        }

        ActivityTaskManagerInternal getActivityTaskManagerInternal() {
            return mService.mAtmInternal;
        }

        void activityManagerOnUserStopped(@UserIdInt int userId) {
            LocalServices.getService(ActivityTaskManagerInternal.class).onUserStopped(userId);
        }

        void systemServiceManagerOnUserStopped(@UserIdInt int userId) {
            getSystemServiceManager().onUserStopped(userId);
        }

        void systemServiceManagerOnUserCompletedEvent(@UserIdInt int userId, int eventTypes) {
            getSystemServiceManager().onUserCompletedEvent(userId, eventTypes);
        }

        protected UserManagerService getUserManager() {
            if (mUserManager == null) {
                IBinder b = ServiceManager.getService(Context.USER_SERVICE);
                mUserManager = (UserManagerService) IUserManager.Stub.asInterface(b);
            }
            return mUserManager;
        }

        UserManagerInternal getUserManagerInternal() {
            if (mUserManagerInternal == null) {
                mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
            }
            return mUserManagerInternal;
        }

        PowerManagerInternal getPowerManagerInternal() {
            if (mPowerManagerInternal == null) {
                mPowerManagerInternal = LocalServices.getService(PowerManagerInternal.class);
            }
            return mPowerManagerInternal;
        }

        KeyguardManager getKeyguardManager() {
            return mService.mContext.getSystemService(KeyguardManager.class);
        }

        void batteryStatsServiceNoteEvent(int code, String name, int uid) {
            mService.mBatteryStatsService.noteEvent(code, name, uid);
        }

        boolean isRuntimeRestarted() {
            return getSystemServiceManager().isRuntimeRestarted();
        }

        SystemServiceManager getSystemServiceManager() {
            return mService.mSystemServiceManager;
        }

        boolean isFirstBootOrUpgrade() {
            IPackageManager pm = AppGlobals.getPackageManager();
            try {
                return pm.isFirstBoot() || pm.isDeviceUpgrading();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        void sendPreBootBroadcast(@UserIdInt int userId, boolean quiet, final Runnable onFinish) {
            EventLog.writeEvent(EventLogTags.UC_SEND_USER_BROADCAST,
                    userId, Intent.ACTION_PRE_BOOT_COMPLETED);
            new PreBootBroadcaster(mService, userId, null, quiet) {
                @Override
                public void onFinished() {
                    onFinish.run();
                }
            }.sendNext();
        }

        void activityManagerForceStopPackage(@UserIdInt int userId, String reason) {
            synchronized (mService) {
                mService.forceStopPackageLocked(null, -1, false, false, true, false, false, false,
                        userId, reason);
            }
        };

        int checkComponentPermission(String permission, int pid, int uid, int owningUid,
                boolean exported) {
            return mService.checkComponentPermission(permission, pid, uid, owningUid, exported);
        }

        boolean checkPermissionForPreflight(String permission, int pid, int uid, String pkg) {
            return  PermissionChecker.PERMISSION_GRANTED
                    == PermissionChecker.checkPermissionForPreflight(
                            getContext(), permission, pid, uid, pkg);
        }

        protected void startHomeActivity(@UserIdInt int userId, String reason) {
            mService.mAtmInternal.startHomeActivity(userId, reason);
        }

        void startUserWidgets(@UserIdInt int userId) {
            AppWidgetManagerInternal awm = LocalServices.getService(AppWidgetManagerInternal.class);
            if (awm != null) {
                // Out of band, because this is called during a sequence with
                // sensitive cross-service lock management
                FgThread.getHandler().post(() -> {
                    awm.unlockUser(userId);
                });
            }
        }

        void updateUserConfiguration() {
            mService.mAtmInternal.updateUserConfiguration();
        }

        void clearBroadcastQueueForUser(@UserIdInt int userId) {
            synchronized (mService) {
                mService.clearBroadcastQueueForUserLocked(userId);
            }
        }

        void loadUserRecents(@UserIdInt int userId) {
            mService.mAtmInternal.loadRecentTasksForUser(userId);
        }

        void startPersistentApps(int matchFlags) {
            mService.startPersistentApps(matchFlags);
        }

        void installEncryptionUnawareProviders(@UserIdInt int userId) {
            mService.mCpHelper.installEncryptionUnawareProviders(userId);
        }

        void dismissUserSwitchingDialog(@Nullable Runnable onDismissed) {
            synchronized (mUserSwitchingDialogLock) {
                if (mUserSwitchingDialog != null) {
                    mUserSwitchingDialog.dismiss(onDismissed);
                    mUserSwitchingDialog = null;
                } else if (onDismissed != null) {
                    onDismissed.run();
                }
            }
        }

        void showUserSwitchingDialog(UserInfo fromUser, UserInfo toUser,
                String switchingFromSystemUserMessage, String switchingToSystemUserMessage,
                @NonNull Runnable onShown) {
            if (mService.mContext.getPackageManager()
                    .hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
                // config_customUserSwitchUi is set to true on Automotive as CarSystemUI is
                // responsible to show the UI; OEMs should not change that, but if they do, we
                // should at least warn the user...
                Slogf.w(TAG, "Showing user switch dialog on UserController, it could cause a race "
                        + "condition if it's shown by CarSystemUI as well");
            }
            synchronized (mUserSwitchingDialogLock) {
                dismissUserSwitchingDialog(null);
                mUserSwitchingDialog = new UserSwitchingDialog(mService.mContext, fromUser, toUser,
                        switchingFromSystemUserMessage, switchingToSystemUserMessage,
                        getWindowManager());
                mUserSwitchingDialog.show(onShown);
            }
        }

        void reportGlobalUsageEvent(int event) {
            mService.reportGlobalUsageEvent(event);
        }

        void reportCurWakefulnessUsageEvent() {
            mService.reportCurWakefulnessUsageEvent();
        }

        void taskSupervisorRemoveUser(@UserIdInt int userId) {
            mService.mAtmInternal.removeUser(userId);
        }

        protected boolean taskSupervisorSwitchUser(@UserIdInt int userId, UserState uss) {
            return mService.mAtmInternal.switchUser(userId, uss);
        }

        protected void taskSupervisorResumeFocusedStackTopActivity() {
            mService.mAtmInternal.resumeTopActivities(false /* scheduleIdle */);
        }

        protected void clearAllLockedTasks(String reason) {
            mService.mAtmInternal.clearLockedTasks(reason);
        }

        boolean isCallerRecents(int callingUid) {
            return mService.mAtmInternal.isCallerRecents(callingUid);
        }

        protected IStorageManager getStorageManager() {
            return IStorageManager.Stub.asInterface(ServiceManager.getService("mount"));
        }

        protected void showKeyguard(Runnable runnable) {
            if (getWindowManager().isKeyguardLocked()) {
                runnable.run();
                return;
            }
            getActivityTaskManagerInternal().registerScreenObserver(
                    new ActivityTaskManagerInternal.ScreenObserver() {
                        @Override
                        public void onAwakeStateChanged(boolean isAwake) {

                        }

                        @Override
                        public void onKeyguardStateChanged(boolean isShowing) {
                            if (isShowing) {
                                getActivityTaskManagerInternal().unregisterScreenObserver(this);
                                runnable.run();
                            }
                        }
                    }
            );
            getWindowManager().lockDeviceNow();
        }

        protected void dismissKeyguard(Runnable runnable) {
            getWindowManager().dismissKeyguard(new IKeyguardDismissCallback.Stub() {
                @Override
                public void onDismissError() throws RemoteException {
                    runnable.run();
                }

                @Override
                public void onDismissSucceeded() throws RemoteException {
                    runnable.run();
                }

                @Override
                public void onDismissCancelled() throws RemoteException {
                    runnable.run();
                }
            }, /* message= */ null);
        }

        boolean isHeadlessSystemUserMode() {
            return UserManager.isHeadlessSystemUserMode();
        }

        boolean isUsersOnSecondaryDisplaysEnabled() {
            return UserManager.isVisibleBackgroundUsersEnabled();
        }

        void onUserStarting(@UserIdInt int userId) {
            getSystemServiceManager().onUserStarting(TimingsTraceAndSlog.newAsyncLog(), userId);
        }

        void setPerformancePowerMode(boolean enabled) {
            Slogf.i(TAG, "Setting power mode MODE_FIXED_PERFORMANCE to " + enabled);
            getPowerManagerInternal().setPowerMode(
                    PowerManagerInternal.MODE_FIXED_PERFORMANCE, enabled);
        }

        void onSystemUserVisibilityChanged(boolean visible) {
            getUserManagerInternal().onSystemUserVisibilityChanged(visible);
        }
    }
}
