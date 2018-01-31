/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION;
import static android.view.WindowManager.TRANSIT_NONE;
import static com.android.server.am.ActivityStackSupervisor.PRESERVE_WINDOWS;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.view.IRecentsAnimationRunner;
import com.android.server.wm.RecentsAnimationController.RecentsAnimationCallbacks;
import com.android.server.wm.WindowManagerService;

/**
 * Manages the recents animation, including the reordering of the stacks for the transition and
 * cleanup. See {@link com.android.server.wm.RecentsAnimationController}.
 */
class RecentsAnimation implements RecentsAnimationCallbacks {
    private static final String TAG = RecentsAnimation.class.getSimpleName();

    private static final int RECENTS_ANIMATION_TIMEOUT = 10 * 1000;

    private final ActivityManagerService mService;
    private final ActivityStackSupervisor mStackSupervisor;
    private final ActivityStartController mActivityStartController;
    private final WindowManagerService mWindowManager;
    private final UserController mUserController;
    private final Handler mHandler;

    private final Runnable mCancelAnimationRunnable;

    // The stack to restore the home stack behind when the animation is finished
    private ActivityStack mRestoreHomeBehindStack;

    RecentsAnimation(ActivityManagerService am, ActivityStackSupervisor stackSupervisor,
            ActivityStartController activityStartController, WindowManagerService wm,
            UserController userController) {
        mService = am;
        mStackSupervisor = stackSupervisor;
        mActivityStartController = activityStartController;
        mHandler = new Handler(mStackSupervisor.mLooper);
        mWindowManager = wm;
        mUserController = userController;
        mCancelAnimationRunnable = () -> {
            // The caller has not finished the animation in a predefined amount of time, so
            // force-cancel the animation
            mWindowManager.cancelRecentsAnimation();
        };
    }

    void startRecentsActivity(Intent intent, IRecentsAnimationRunner recentsAnimationRunner,
            ComponentName recentsComponent, int recentsUid) {
        mWindowManager.deferSurfaceLayout();
        try {
            // Cancel the previous recents animation if necessary
            mWindowManager.cancelRecentsAnimation();

            final boolean hasExistingHomeActivity = mStackSupervisor.getHomeActivity() != null;
            if (!hasExistingHomeActivity) {
                // No home activity
                final ActivityOptions opts = ActivityOptions.makeBasic();
                opts.setLaunchActivityType(ACTIVITY_TYPE_HOME);
                opts.setAvoidMoveToFront();
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NO_ANIMATION);

                mActivityStartController
                        .obtainStarter(intent, "startRecentsActivity_noHomeActivity")
                        .setCallingUid(recentsUid)
                        .setCallingPackage(recentsComponent.getPackageName())
                        .setActivityOptions(SafeActivityOptions.fromBundle(opts.toBundle()))
                        .setMayWait(mUserController.getCurrentUserId())
                        .execute();
                mWindowManager.prepareAppTransition(TRANSIT_NONE, false);

                // TODO: Maybe wait for app to draw in this particular case?
            }

            final ActivityRecord homeActivity = mStackSupervisor.getHomeActivity();
            final ActivityDisplay display = homeActivity.getDisplay();

            // Save the initial position of the home activity stack to be restored to after the
            // animation completes
            mRestoreHomeBehindStack = hasExistingHomeActivity
                    ? display.getStackAboveHome()
                    : null;

            // Move the home activity into place for the animation
            display.moveHomeStackBehindBottomMostVisibleStack();

            // Mark the home activity as launch-behind to bump its visibility for the
            // duration of the gesture that is driven by the recents component
            homeActivity.mLaunchTaskBehind = true;

            // Fetch all the surface controls and pass them to the client to get the animation
            // started
            mWindowManager.initializeRecentsAnimation(recentsAnimationRunner, this,
                    display.mDisplayId);

            // If we updated the launch-behind state, update the visibility of the activities after
            // we fetch the visible tasks to be controlled by the animation
            mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, PRESERVE_WINDOWS);

            // Post a timeout for the animation
            mHandler.postDelayed(mCancelAnimationRunnable, RECENTS_ANIMATION_TIMEOUT);
        } finally {
            mWindowManager.continueSurfaceLayout();
        }
    }

    @Override
    public void onAnimationFinished(boolean moveHomeToTop) {
        mHandler.removeCallbacks(mCancelAnimationRunnable);
        synchronized (mService) {
            if (mWindowManager.getRecentsAnimationController() == null) return;

            mWindowManager.inSurfaceTransaction(() -> {
                mWindowManager.deferSurfaceLayout();
                try {
                    mWindowManager.cleanupRecentsAnimation();

                    // Move the home stack to the front
                    final ActivityRecord homeActivity = mStackSupervisor.getHomeActivity();
                    if (homeActivity == null) {
                        return;
                    }

                    // Restore the launched-behind state
                    homeActivity.mLaunchTaskBehind = false;

                    if (moveHomeToTop) {
                        // Bring the home stack to the front
                        final ActivityStack homeStack = homeActivity.getStack();
                        mStackSupervisor.mNoAnimActivities.add(homeActivity);
                        homeStack.moveToFront("RecentsAnimation.onAnimationFinished()");
                    } else {
                        // Restore the home stack to its previous position
                        final ActivityDisplay display = homeActivity.getDisplay();
                        display.moveHomeStackBehindStack(mRestoreHomeBehindStack);
                    }

                    mWindowManager.prepareAppTransition(TRANSIT_NONE, false);
                    mStackSupervisor.ensureActivitiesVisibleLocked(null, 0, false);
                    mStackSupervisor.resumeFocusedStackTopActivityLocked();

                    // No reason to wait for the pausing activity in this case, as the hiding of
                    // surfaces needs to be done immediately.
                    mWindowManager.executeAppTransition();
                } finally {
                    mWindowManager.continueSurfaceLayout();
                }
            });
        }
    }
}
