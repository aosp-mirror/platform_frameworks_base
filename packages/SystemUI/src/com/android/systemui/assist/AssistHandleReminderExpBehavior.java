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

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.Dependency;
import com.android.systemui.assist.AssistHandleBehaviorController.BehaviorController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.StatusBarState;

import java.util.concurrent.TimeUnit;

/**
 * Assistant handle behavior that hides the handles when the phone is dozing or in immersive mode,
 * shows the handles when on lockscreen, and shows the handles temporarily when changing tasks or
 * entering overview.
 */
final class AssistHandleReminderExpBehavior implements BehaviorController {

    private static final String LEARNING_TIME_ELAPSED_KEY = "reminder_exp_learning_time_elapsed";
    private static final String LEARNING_EVENT_COUNT_KEY = "reminder_exp_learning_event_count";
    private static final long DEFAULT_LEARNING_TIME_MS = TimeUnit.DAYS.toMillis(3);
    private static final int DEFAULT_LEARNING_COUNT = 3;

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
                public void onTaskMovedToFront(int taskId) {
                    handleTaskStackTopChanged(taskId);
                }

                @Override
                public void onTaskCreated(int taskId, ComponentName componentName) {
                    handleTaskStackTopChanged(taskId);
                }
            };
    private final OverviewProxyService.OverviewProxyListener mOverviewProxyListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onOverviewShown(boolean fromHome) {
                    handleOverviewShown();
                }

                @Override
                public void onSystemUiStateChanged(int sysuiStateFlags) {
                    handleSystemUiStateChanged(sysuiStateFlags);
                }
            };

    private final StatusBarStateController mStatusBarStateController;
    private final ActivityManagerWrapper mActivityManagerWrapper;
    private final OverviewProxyService mOverviewProxyService;

    private boolean mOnLockscreen;
    private boolean mIsDozing;
    private int mRunningTaskId;
    private boolean mIsNavBarHidden;

    /** Whether user has learned the gesture. */
    private boolean mIsLearned;
    private long mLastLearningTimestamp;
    /** Uptime while in this behavior. */
    private long mLearningTimeElapsed;
    /** Number of successful Assistant invocations while in this behavior. */
    private int mLearningCount;

    @Nullable private Context mContext;
    @Nullable private AssistHandleCallbacks mAssistHandleCallbacks;

    AssistHandleReminderExpBehavior() {
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
    }

    @Override
    public void onModeActivated(Context context, AssistHandleCallbacks callbacks) {
        mContext = context;
        mAssistHandleCallbacks = callbacks;
        mOnLockscreen = onLockscreen(mStatusBarStateController.getState());
        mIsDozing = mStatusBarStateController.isDozing();
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        ActivityManager.RunningTaskInfo runningTaskInfo = mActivityManagerWrapper.getRunningTask();
        mRunningTaskId = runningTaskInfo == null ? 0 : runningTaskInfo.taskId;
        mActivityManagerWrapper.registerTaskStackListener(mTaskStackChangeListener);
        mOverviewProxyService.addCallback(mOverviewProxyListener);

        mLearningTimeElapsed = Settings.Secure.getLong(
                context.getContentResolver(), LEARNING_TIME_ELAPSED_KEY, /* default = */ 0);
        mLearningCount = Settings.Secure.getInt(
                context.getContentResolver(), LEARNING_EVENT_COUNT_KEY, /* default = */ 0);
        mLastLearningTimestamp = SystemClock.uptimeMillis();

        callbackForCurrentState(/* justUnlocked = */ false);
    }

    @Override
    public void onModeDeactivated() {
        mAssistHandleCallbacks = null;
        if (mContext != null) {
            Settings.Secure.putLong(
                    mContext.getContentResolver(), LEARNING_TIME_ELAPSED_KEY, mLearningTimeElapsed);
            Settings.Secure.putInt(
                    mContext.getContentResolver(), LEARNING_EVENT_COUNT_KEY, mLearningCount);
            mContext = null;
        }
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mActivityManagerWrapper.unregisterTaskStackListener(mTaskStackChangeListener);
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
    }

    @Override
    public void onAssistantGesturePerformed() {
        if (mContext == null) {
            return;
        }

        Settings.Secure.putLong(
                mContext.getContentResolver(), LEARNING_EVENT_COUNT_KEY, ++mLearningCount);
    }

    private static boolean isNavBarHidden(int sysuiStateFlags) {
        return (sysuiStateFlags & QuickStepContract.SYSUI_STATE_NAV_BAR_HIDDEN) != 0;
    }

    private void handleStatusBarStateChanged(int newState) {
        boolean onLockscreen = onLockscreen(newState);
        if (mOnLockscreen == onLockscreen) {
            return;
        }

        mOnLockscreen = onLockscreen;
        callbackForCurrentState(!onLockscreen);
    }

    private void handleDozingChanged(boolean isDozing) {
        if (mIsDozing == isDozing) {
            return;
        }

        mIsDozing = isDozing;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleTaskStackTopChanged(int taskId) {
        if (mRunningTaskId == taskId) {
            return;
        }

        mRunningTaskId = taskId;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleSystemUiStateChanged(int sysuiStateFlags) {
        boolean isNavBarHidden = isNavBarHidden(sysuiStateFlags);
        if (mIsNavBarHidden == isNavBarHidden) {
            return;
        }

        mIsNavBarHidden = isNavBarHidden;
        callbackForCurrentState(/* justUnlocked = */ false);
    }

    private void handleOverviewShown() {
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

        if (mIsDozing || mIsNavBarHidden || mOnLockscreen) {
            mAssistHandleCallbacks.hide();
        } else if (justUnlocked) {
            mAssistHandleCallbacks.showAndGo();
        }
    }

    private void callbackForUnlearnedState() {
        if (mAssistHandleCallbacks == null) {
            return;
        }

        if (mIsDozing || mIsNavBarHidden) {
            mAssistHandleCallbacks.hide();
        } else if (mOnLockscreen) {
            mAssistHandleCallbacks.showAndStay();
        } else {
            mAssistHandleCallbacks.showAndGo();
        }
    }

    private void updateLearningStatus() {
        if (mContext == null) {
            return;
        }

        long currentTimestamp = SystemClock.uptimeMillis();
        mLearningTimeElapsed += currentTimestamp - mLastLearningTimestamp;
        mLastLearningTimestamp = currentTimestamp;
        Settings.Secure.putLong(
                mContext.getContentResolver(), LEARNING_TIME_ELAPSED_KEY, mLearningTimeElapsed);

        mIsLearned =
                mLearningCount >= getLearningCount() || mLearningTimeElapsed >= getLearningTimeMs();
    }

    private long getLearningTimeMs() {
        return DeviceConfig.getLong(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_TIME_MS,
                DEFAULT_LEARNING_TIME_MS);
    }

    private int getLearningCount() {
        return DeviceConfig.getInt(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.ASSIST_HANDLES_LEARN_COUNT,
                DEFAULT_LEARNING_COUNT);
    }
}
