/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist;

import static com.android.systemui.assist.AssistModule.ASSIST_HANDLE_THREAD_NAME;
import static com.android.systemui.assist.AssistModule.UPTIME_NAME;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;

import androidx.annotation.Nullable;
import androidx.slice.Clock;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.BootCompleteCache;
import com.android.systemui.assist.AssistHandleBehaviorController.BehaviorController;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.PackageManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.StatusBarState;

import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Assistant handle behavior that hides the handles when the phone is dozing or in immersive mode,
 * shows the handles when on lockscreen, and shows the handles temporarily when changing tasks or
 * entering overview.
 */
@Singleton
final class AssistHandleReminderExpBehavior implements BehaviorController {

    private static final Uri LEARNING_TIME_ELAPSED_URI =
            Settings.Secure.getUriFor(Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS);
    private static final Uri LEARNING_EVENT_COUNT_URI =
            Settings.Secure.getUriFor(Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT);
    private static final String LEARNED_HINT_LAST_SHOWN_KEY =
            "reminder_exp_learned_hint_last_shown";
    private static final long DEFAULT_LEARNING_TIME_MS = TimeUnit.DAYS.toMillis(10);
    private static final int DEFAULT_LEARNING_COUNT = 10;
    private static final long DEFAULT_SHOW_AND_GO_DELAYED_SHORT_DELAY_MS = 150;
    private static final long DEFAULT_SHOW_AND_GO_DELAYED_LONG_DELAY_MS =
            TimeUnit.SECONDS.toMillis(1);
    private static final long DEFAULT_SHOW_AND_GO_DELAY_RESET_TIMEOUT_MS =
            TimeUnit.SECONDS.toMillis(3);
    private static final boolean DEFAULT_SUPPRESS_ON_LOCKSCREEN = false;
    private static final boolean DEFAULT_SUPPRESS_ON_LAUNCHER = false;
    private static final boolean DEFAULT_SUPPRESS_ON_APPS = true;
    private static final boolean DEFAULT_SHOW_WHEN_TAUGHT = false;

    private static final String[] DEFAULT_HOME_CHANGE_ACTIONS = new String[] {
            PackageManagerWrapper.ACTION_PREFERRED_ACTIVITY_CHANGED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_CHANGED,
            Intent.ACTION_PACKAGE_REMOVED
    };

    private final StatusBarStateController.StateListener mStatusBarStateListener =
            new StatusBarStateController.StateListener() {
                @Override
                public void onStateChanged(int newState) {
                    handleStatusBarStateChanged(newState);
                }

                @Override
                public void onDozingChanged(boolean isDozing) {
                    handleDozingChanged(isDozing);
                }
            };
    private final TaskStackChangeListener mTaskStackChangeListener =
            new TaskStackChangeListener() {
                @Override
                public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
                    handleTaskStackTopChanged(taskInfo.taskId, taskInfo.topActivity);
                }

                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    handleTaskStackTopChanged(taskId, componentName);
                }
            };
    private final OverviewProxyService.OverviewProxyListener mOverviewProxyListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onOverviewShown(boolean fromHome) {
                    handleOverviewShown();
                }
            };
    private final SysUiState.SysUiStateCallback mSysUiStateCallback =
            this::handleSystemUiStateChanged;
    private final WakefulnessLifecycle.Observer mWakefulnessLifecycleObserver =
            new WakefulnessLifecycle.Observer() {
                @Override
                public void onStartedWakingUp() {
                    handleWakefullnessChanged(/* isAwake = */ false);
                }

                @Override
                public void onFinishedWakingUp() {
                    handleWakefullnessChanged(/* isAwake = */ true);
                }

                @Override
                public void onStartedGoingToSleep() {
                    handleWakefullnessChanged(/* isAwake = */ false);
                }

                @Override
                public void onFinishedGoingToSleep() {
                    handleWakefullnessChanged(/* isAwake = */ false);
                }
            };
    private final BroadcastReceiver mDefaultHomeBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mDefaultHome = getCurrentDefaultHome();
        }
    };

    private final BootCompleteCache.BootCompleteListener mBootCompleteListener =
            new BootCompleteCache.BootCompleteListener() {
        @Override
        public void onBootComplete() {
            mDefaultHome = getCurrentDefaultHome();
        }
    };

    private final IntentFilter mDefaultHomeIntentFilter;
    private final Runnable mResetConsecutiveTaskSwitches = this::resetConsecutiveTaskSwitches;

    private final Clock mClock;
    private final Handler mHandler;
    private final DeviceConfigHelper mDeviceConfigHelper;
    private final Lazy<StatusBarStateController> mStatusBarStateController;
    private final Lazy<ActivityManagerWrapper> mActivityManagerWrapper;
    private final Lazy<OverviewProxyService> mOverviewProxyService;
    private final Lazy<SysUiState> mSysUiFlagContainer;
    private final Lazy<WakefulnessLifecycle> mWakefulnessLifecycle;
    private final Lazy<PackageManagerWrapper> mPackageManagerWrapper;
    private final Lazy<BroadcastDispatcher> mBroadcastDispatcher;
    private final Lazy<BootCompleteCache> mBootCompleteCache;

    private boolean mOnLockscreen;
    private boolean mIsDozing;
    private boolean mIsAwake;
    private int mRunningTaskId;
    private boolean mIsNavBarHidden;
    private boolean mIsLauncherShowing;
    private int mConsecutiveTaskSwitches;
    @Nullable private ContentObserver mSettingObserver;

    /** Whether user has learned the gesture. */
    private boolean mIsLearned;
    private long mLastLearningTimestamp;
    /** Uptime while in this behavior. */
    private long mLearningTimeElapsed;
    /** Number of successful Assistant invocations while in this behavior. */
    private int mLearningCount;
    private long mLearnedHintLastShownEpochDay;

    @Nullable private Context mContext;
    @Nullable private AssistHandleCallbacks mAssistHandleCallbacks;
    @Nullable private ComponentName mDefaultHome;

    @Inject
    AssistHandleReminderExpBehavior(
            @Named(UPTIME_NAME) Clock clock,
            @Named(ASSIST_HANDLE_THREAD_NAME) Handler handler,
            DeviceConfigHelper deviceConfigHelper,
            Lazy<StatusBarStateController> statusBarStateController,
            Lazy<ActivityManagerWrapper> activityManagerWrapper,
            Lazy<OverviewProxyService> overviewProxyService,
            Lazy<SysUiState> sysUiFlagContainer,
            Lazy<WakefulnessLifecycle> wakefulnessLifecycle,
            Lazy<PackageManagerWrapper> packageManagerWrapper,
            Lazy<BroadcastDispatcher> broadcastDispatcher,
            Lazy<BootCompleteCache> bootCompleteCache) {
        mClock = clock;
        mHandler = handler;
        mDeviceConfigHelper = deviceConfigHelper;
        mStatusBarStateController = statusBarStateController;
        mActivityManagerWrapper = activityManagerWrapper;
        mOverviewProxyService = overviewProxyService;
        mSysUiFlagContainer = sysUiFlagContainer;
        mWakefulnessLifecycle = wakefulnessLifecycle;
        mPackageManagerWrapper = packageManagerWrapper;
        mDefaultHomeIntentFilter = new IntentFilter();
        for (String action : DEFAULT_HOME_CHANGE_ACTIONS) {
            mDefaultHomeIntentFilter.addAction(action);
        }
        mBroadcastDispatcher = broadcastDispatcher;
        mBootCompleteCache = bootCompleteCache;
    }

    @Override
    public void onModeActivated(Context context, AssistHandleCallbacks callbacks) {
        mContext = context;
        mAssistHandleCallbacks = callbacks;
        mConsecutiveTaskSwitches = 0;
        mBootCompleteCache.get().addListener(mBootCompleteListener);
        mDefaultHome = getCurrentDefaultHome();
        mBroadcastDispatcher.get()
                .registerReceiver(mDefaultHomeBroadcastReceiver, mDefaultHomeIntentFilter);
        mOnLockscreen = onLockscreen(mStatusBarStateController.get().getState());
        mIsDozing = mStatusBarStateController.get().isDozing();
        mStatusBarStateController.get().addCallback(mStatusBarStateListener);
        ActivityManager.RunningTaskInfo runningTaskInfo =
                mActivityManagerWrapper.get().getRunningTask();
        mRunningTaskId = runningTaskInfo == null ? 0 : runningTaskInfo.taskId;
        mActivityManagerWrapper.get().registerTaskStackListener(mTaskStackChangeListener);
        mOverviewProxyService.get().addCallback(mOverviewProxyListener);
        mSysUiFlagContainer.get().addCallback(mSysUiStateCallback);
        mIsAwake = mWakefulnessLifecycle.get().getWakefulness()
                == WakefulnessLifecycle.WAKEFULNESS_AWAKE;
        mWakefulnessLifecycle.get().addObserver(mWakefulnessLifecycleObserver);

        mLearningTimeElapsed = Settings.Secure.getLong(
                context.getContentResolver(),
                Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS,
                /* default = */ 0);
        mLearningCount = Settings.Secure.getInt(
                context.getContentResolver(),
                Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT,
                /* default = */ 0);
        mSettingObserver = new SettingsObserver(context, mHandler);
        context.getContentResolver().registerContentObserver(
                LEARNING_TIME_ELAPSED_URI,
                /* notifyForDescendants = */ true,
                mSettingObserver);
        context.getContentResolver().registerContentObserver(
                LEARNING_EVENT_COUNT_URI,
                /* notifyForDescendants = */ true,
                mSettingObserver);
        mLearnedHintLastShownEpochDay = Settings.Secure.getLong(
                context.getContentResolver(), LEARNED_HINT_LAST_SHOWN_KEY, /* default = */ 0);
        mLastLearningTimestamp = mClock.currentTimeMillis();

        callbackForCurrentState(/* justUnlocked = */ false);
    }

    @Override
    public void onModeDeactivated() {
        mAssistHandleCallbacks = null;
        if (mContext != null) {
            mBroadcastDispatcher.get().unregisterReceiver(mDefaultHomeBroadcastReceiver);
            mBootCompleteCache.get().removeListener(mBootCompleteListener);
            mContext.getContentResolver().unregisterContentObserver(mSettingObserver);
            mSettingObserver = null;
            // putString to use overrideableByRestore
            Settings.Secure.putString(
                    mContext.getContentResolver(),
                    Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS,
                    Long.toString(0L),
                    /* overrideableByRestore = */ true);
            // putString to use overrideableByRestore
            Settings.Secure.putString(
                    mContext.getContentResolver(),
                    Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT,
                    Integer.toString(0),
                    /* overrideableByRestore = */ true);
            Settings.Secure.putLong(mContext.getContentResolver(), LEARNED_HINT_LAST_SHOWN_KEY, 0);
            mContext = null;
        }
        mStatusBarStateController.get().removeCallback(mStatusBarStateListener);
        mActivityManagerWrapper.get().unregisterTaskStackListener(mTaskStackChangeListener);
        mOverviewProxyService.get().removeCallback(mOverviewProxyListener);
        mSysUiFlagContainer.get().removeCallback(mSysUiStateCallback);
        mWakefulnessLifecycle.get().removeObserver(mWakefulnessLifecycleObserver);
    }

    @Override
    public void onAssistantGesturePerformed() {
        if (mContext == null) {
            return;
        }

        // putString to use overrideableByRestore
        Settings.Secure.putString(
                mContext.getContentResolver(),
                Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT,
                Integer.toString(++mLearningCount),
                /* overrideableByRestore = */ true);
    }

    @Override
    public void onAssistHandlesRequested() {
        if (mAssistHandleCallbacks != null
                && isFullyAwake()
                && !mIsNavBarHidden
                && !mOnLockscreen) {
            mAssistHandleCallbacks.showAndGo();
        }
    }

    @Nullable
    private ComponentName getCurrentDefaultHome() {
        List<ResolveInfo> homeActivities = new ArrayList<>();
        ComponentName defaultHome = mPackageManagerWrapper.get().getHomeActivities(homeActivities);
        if (defaultHome != null) {
            return defaultHome;
        }

        int topPriority = Integer.MIN_VALUE;
        ComponentName topComponent = null;
        for (ResolveInfo resolveInfo : homeActivities) {
            if (resolveInfo.priority > topPriority) {
                topComponent = resolveInfo.activityInfo.getComponentName();
                topPriority = resolveInfo.priority;
            } else if (resolveInfo.priority == topPriority) {
                topComponent = null;
            }
        }
        return topComponent;
    }

    private void handleStatusBarStateChanged(int newState) {
        boolean onLockscreen = onLockscreen(newState);
        if (mOnLockscreen == onLockscreen) {
            return;
        }

        resetConsecutiveTaskSwitches();
        mOnLockscreen = onLockscreen;
        callbackForCurrentState(!onLockscreen);
    }

    private void handleDozingChanged(boolean isDozing) {
        if (mIsDozing == isDozing) {
            return;
        }

        resetConsecutiveTaskSwitches();
        mIsDozing = isDozing;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleWakefullnessChanged(boolean isAwake) {
        if (mIsAwake == isAwake) {
            return;
        }

        resetConsecutiveTaskSwitches();
        mIsAwake = isAwake;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleTaskStackTopChanged(int taskId, @Nullable ComponentName taskComponentName) {
        if (mRunningTaskId == taskId || taskComponentName == null) {
            return;
        }

        mRunningTaskId = taskId;
        mIsLauncherShowing = taskComponentName.equals(mDefaultHome);
        if (mIsLauncherShowing) {
            resetConsecutiveTaskSwitches();
        } else {
            rescheduleConsecutiveTaskSwitchesReset();
            mConsecutiveTaskSwitches++;
        }
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleSystemUiStateChanged(int sysuiStateFlags) {
        boolean isNavBarHidden =
                (sysuiStateFlags & QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN) != 0;
        if (mIsNavBarHidden == isNavBarHidden) {
            return;
        }

        resetConsecutiveTaskSwitches();
        mIsNavBarHidden = isNavBarHidden;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleOverviewShown() {
        resetConsecutiveTaskSwitches();
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private boolean onLockscreen(int statusBarState) {
        return statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED;
    }

    private void callbackForCurrentState(boolean justUnlocked) {
        updateLearningStatus();

        if (mIsLearned) {
            callbackForLearnedState(justUnlocked);
        } else {
            callbackForUnlearnedState();
        }
    }

    private void callbackForLearnedState(boolean justUnlocked) {
        if (mAssistHandleCallbacks == null) {
            return;
        }

        if (!isFullyAwake() || mIsNavBarHidden || mOnLockscreen || !getShowWhenTaught()) {
            mAssistHandleCallbacks.hide();
        } else if (justUnlocked) {
            long currentEpochDay = LocalDate.now().toEpochDay();
            if (mLearnedHintLastShownEpochDay < currentEpochDay) {
                if (mContext != null) {
                    Settings.Secure.putLong(
                            mContext.getContentResolver(),
                            LEARNED_HINT_LAST_SHOWN_KEY,
                            currentEpochDay);
                }
                mLearnedHintLastShownEpochDay = currentEpochDay;
                mAssistHandleCallbacks.showAndGo();
            }
        }
    }

    private void callbackForUnlearnedState() {
        if (mAssistHandleCallbacks == null) {
            return;
        }

        if (!isFullyAwake() || mIsNavBarHidden || isSuppressed()) {
            mAssistHandleCallbacks.hide();
        } else if (mOnLockscreen) {
            mAssistHandleCallbacks.showAndStay();
        } else if (mIsLauncherShowing) {
            mAssistHandleCallbacks.showAndGo();
        } else if (mConsecutiveTaskSwitches == 1) {
            mAssistHandleCallbacks.showAndGoDelayed(
                    getShowAndGoDelayedShortDelayMs(), /* hideIfShowing = */ false);
        } else {
            mAssistHandleCallbacks.showAndGoDelayed(
                    getShowAndGoDelayedLongDelayMs(), /* hideIfShowing = */ true);
        }
    }

    private boolean isSuppressed() {
        if (mOnLockscreen) {
            return getSuppressOnLockscreen();
        } else if (mIsLauncherShowing) {
            return getSuppressOnLauncher();
        } else {
            return getSuppressOnApps();
        }
    }

    private void updateLearningStatus() {
        if (mContext == null) {
            return;
        }

        long currentTimestamp = mClock.currentTimeMillis();
        mLearningTimeElapsed += currentTimestamp - mLastLearningTimestamp;
        mLastLearningTimestamp = currentTimestamp;

        mIsLearned =
                mLearningCount >= getLearningCount() || mLearningTimeElapsed >= getLearningTimeMs();

        // putString to use overrideableByRestore
        mHandler.post(() -> Settings.Secure.putString(
                mContext.getContentResolver(),
                Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS,
                Long.toString(mLearningTimeElapsed),
                /* overrideableByRestore = */ true));
    }

    private void resetConsecutiveTaskSwitches() {
        mHandler.removeCallbacks(mResetConsecutiveTaskSwitches);
        mConsecutiveTaskSwitches = 0;
    }

    private void rescheduleConsecutiveTaskSwitchesReset() {
        mHandler.removeCallbacks(mResetConsecutiveTaskSwitches);
        mHandler.postDelayed(mResetConsecutiveTaskSwitches, getShowAndGoDelayResetTimeoutMs());
    }

    private boolean isFullyAwake() {
        return mIsAwake && !mIsDozing;
    }

    private long getLearningTimeMs() {
        return mDeviceConfigHelper.getLong(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_TIME_MS,
                DEFAULT_LEARNING_TIME_MS);
    }

    private int getLearningCount() {
        return mDeviceConfigHelper.getInt(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_COUNT,
                DEFAULT_LEARNING_COUNT);
    }

    private long getShowAndGoDelayedShortDelayMs() {
        return mDeviceConfigHelper.getLong(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAYED_SHORT_DELAY_MS,
                DEFAULT_SHOW_AND_GO_DELAYED_SHORT_DELAY_MS);
    }

    private long getShowAndGoDelayedLongDelayMs() {
        return mDeviceConfigHelper.getLong(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAYED_LONG_DELAY_MS,
                DEFAULT_SHOW_AND_GO_DELAYED_LONG_DELAY_MS);
    }

    private long getShowAndGoDelayResetTimeoutMs() {
        return mDeviceConfigHelper.getLong(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAY_RESET_TIMEOUT_MS,
                DEFAULT_SHOW_AND_GO_DELAY_RESET_TIMEOUT_MS);
    }

    private boolean getSuppressOnLockscreen() {
        return mDeviceConfigHelper.getBoolean(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_LOCKSCREEN,
                DEFAULT_SUPPRESS_ON_LOCKSCREEN);
    }

    private boolean getSuppressOnLauncher() {
        return mDeviceConfigHelper.getBoolean(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_LAUNCHER,
                DEFAULT_SUPPRESS_ON_LAUNCHER);
    }

    private boolean getSuppressOnApps() {
        return mDeviceConfigHelper.getBoolean(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_APPS,
                DEFAULT_SUPPRESS_ON_APPS);
    }

    private boolean getShowWhenTaught() {
        return mDeviceConfigHelper.getBoolean(
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_WHEN_TAUGHT,
                DEFAULT_SHOW_WHEN_TAUGHT);
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Current AssistHandleReminderExpBehavior State:");
        pw.println(prefix + "   mOnLockscreen=" + mOnLockscreen);
        pw.println(prefix + "   mIsDozing=" + mIsDozing);
        pw.println(prefix + "   mIsAwake=" + mIsAwake);
        pw.println(prefix + "   mRunningTaskId=" + mRunningTaskId);
        pw.println(prefix + "   mDefaultHome=" + mDefaultHome);
        pw.println(prefix + "   mIsNavBarHidden=" + mIsNavBarHidden);
        pw.println(prefix + "   mIsLauncherShowing=" + mIsLauncherShowing);
        pw.println(prefix + "   mConsecutiveTaskSwitches=" + mConsecutiveTaskSwitches);
        pw.println(prefix + "   mIsLearned=" + mIsLearned);
        pw.println(prefix + "   mLastLearningTimestamp=" + mLastLearningTimestamp);
        pw.println(prefix + "   mLearningTimeElapsed=" + mLearningTimeElapsed);
        pw.println(prefix + "   mLearningCount=" + mLearningCount);
        pw.println(prefix + "   mLearnedHintLastShownEpochDay=" + mLearnedHintLastShownEpochDay);
        pw.println(
                prefix + "   mAssistHandleCallbacks present: " + (mAssistHandleCallbacks != null));

        pw.println(prefix + "   Phenotype Flags:");
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_TIME_MS
                + "="
                + getLearningTimeMs());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_COUNT
                + "="
                + getLearningCount());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAYED_SHORT_DELAY_MS
                + "="
                + getShowAndGoDelayedShortDelayMs());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAYED_LONG_DELAY_MS
                + "="
                + getShowAndGoDelayedLongDelayMs());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_AND_GO_DELAY_RESET_TIMEOUT_MS
                + "="
                + getShowAndGoDelayResetTimeoutMs());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_LOCKSCREEN
                + "="
                + getSuppressOnLockscreen());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_LAUNCHER
                + "="
                + getSuppressOnLauncher());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SUPPRESS_ON_APPS
                + "="
                + getSuppressOnApps());
        pw.println(prefix + "      "
                + SystemUiDeviceConfigFlags.ASSIST_HANDLES_SHOW_WHEN_TAUGHT
                + "="
                + getShowWhenTaught());
    }

    private final class SettingsObserver extends ContentObserver {

        private final Context mContext;

        SettingsObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            if (LEARNING_TIME_ELAPSED_URI.equals(uri)) {
                mLastLearningTimestamp = mClock.currentTimeMillis();
                mLearningTimeElapsed = Settings.Secure.getLong(
                        mContext.getContentResolver(),
                        Settings.Secure.ASSIST_HANDLES_LEARNING_TIME_ELAPSED_MILLIS,
                        /* default = */ 0);
            } else if (LEARNING_EVENT_COUNT_URI.equals(uri)) {
                mLearningCount = Settings.Secure.getInt(
                        mContext.getContentResolver(),
                        Settings.Secure.ASSIST_HANDLES_LEARNING_EVENT_COUNT,
                        /* default = */ 0);
            }

            super.onChange(selfChange, uri);
        }
    }
}
