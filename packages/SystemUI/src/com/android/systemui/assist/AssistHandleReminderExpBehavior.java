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

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.android.systemui.Dependency;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.assist.AssistHandleBehaviorController.BehaviorController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;

/**
 * Assistant handle behavior that hides the handles when the phone is dozing or in immersive mode,
 * shows the handles when on lockscreen, and shows the handles temporarily when changing tasks or
 * entering overview.
 */
final class AssistHandleReminderExpBehavior implements BehaviorController {

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
    private final CommandQueue.Callbacks mCallbacks = new CommandQueue.Callbacks() {
        @Override
        public void setSystemUiVisibility(int displayId, int vis,
                int fullscreenStackVis, int dockedStackVis, int mask,
                Rect fullscreenStackBounds, Rect dockedStackBounds,
                boolean navbarColorManagedByIme) {
            if (mStatusBarDisplayId == displayId) {
                handleSystemUiVisibilityChange(vis, mask);
            }
        }
    };
    private final OverviewProxyService.OverviewProxyListener mOverviewProxyListener =
            new OverviewProxyService.OverviewProxyListener() {
                @Override
                public void onOverviewShown(boolean fromHome) {
                    handleOverviewShown();
                }
            };

    private StatusBarStateController mStatusBarStateController;
    private ActivityManagerWrapper mActivityManagerWrapper;
    private OverviewProxyService mOverviewProxyService;
    private int mStatusBarDisplayId;
    private CommandQueue mCommandQueue;
    private boolean mOnLockscreen;
    private boolean mIsDozing;
    private int mRunningTaskId;
    private boolean mIsImmersive;

    @Nullable private AssistHandleCallbacks mAssistHandleCallbacks;

    @Override
    public void onModeActivated(Context context, AssistHandleCallbacks callbacks) {
        mAssistHandleCallbacks = callbacks;
        mStatusBarStateController = Dependency.get(StatusBarStateController.class);
        mOnLockscreen = onLockscreen(mStatusBarStateController.getState());
        mIsDozing = mStatusBarStateController.isDozing();
        mStatusBarStateController.addCallback(mStatusBarStateListener);
        mActivityManagerWrapper = ActivityManagerWrapper.getInstance();
        mRunningTaskId = mActivityManagerWrapper.getRunningTask().taskId;
        mActivityManagerWrapper.registerTaskStackListener(mTaskStackChangeListener);
        mStatusBarDisplayId =
                ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE))
                        .getDefaultDisplay().getDisplayId();
        mCommandQueue = SysUiServiceProvider.getComponent(context, CommandQueue.class);
        mCommandQueue.addCallback(mCallbacks);
        mOverviewProxyService = Dependency.get(OverviewProxyService.class);
        mOverviewProxyService.addCallback(mOverviewProxyListener);
        callbackForCurrentState();
    }

    @Override
    public void onModeDeactivated() {
        mAssistHandleCallbacks = null;
        mStatusBarStateController.removeCallback(mStatusBarStateListener);
        mActivityManagerWrapper.unregisterTaskStackListener(mTaskStackChangeListener);
        mCommandQueue.removeCallback(mCallbacks);
        mOverviewProxyService.removeCallback(mOverviewProxyListener);
    }

    private void handleStatusBarStateChanged(int newState) {
        boolean onLockscreen = onLockscreen(newState);
        if (mOnLockscreen == onLockscreen) {
            return;
        }

        mOnLockscreen = onLockscreen;
        callbackForCurrentState();
    }

    private void handleDozingChanged(boolean isDozing) {
        if (mIsDozing == isDozing) {
            return;
        }

        mIsDozing = isDozing;
        callbackForCurrentState();
    }

    private void handleTaskStackTopChanged(int taskId) {
        if (mRunningTaskId == taskId) {
            return;
        }

        mRunningTaskId = taskId;
        callbackForCurrentState();
    }

    private void handleSystemUiVisibilityChange(int vis, int mask) {
        boolean isImmersive = isImmersive(vis, mask);
        if (mIsImmersive == isImmersive) {
            return;
        }

        mIsImmersive = isImmersive;
        callbackForCurrentState();
    }

    private void handleOverviewShown() {
        callbackForCurrentState();
    }

    private boolean onLockscreen(int statusBarState) {
        return statusBarState == StatusBarState.KEYGUARD
                || statusBarState == StatusBarState.SHADE_LOCKED;
    }

    private boolean isImmersive(int vis, int mask) {
        return ((vis & mask)
                & (View.SYSTEM_UI_FLAG_IMMERSIVE | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)) != 0;
    }

    private void callbackForCurrentState() {
        if (mAssistHandleCallbacks == null) {
            return;
        }

        if (mIsDozing || mIsImmersive) {
            mAssistHandleCallbacks.hide();
        } else if (mOnLockscreen) {
            mAssistHandleCallbacks.showAndStay();
        } else {
            mAssistHandleCallbacks.showAndGo();
        }
    }
}
