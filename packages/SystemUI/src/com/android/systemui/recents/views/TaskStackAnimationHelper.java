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
 * limitations under the License.
 */

package com.android.systemui.recents.views;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.RectF;
import android.view.View;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsActivityLaunchState;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.ReferenceCountedTrigger;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.List;

/**
 * A helper class to create task view animations for {@link TaskView}s in a {@link TaskStackView},
 * but not the contents of the {@link TaskView}s.
 */
public class TaskStackAnimationHelper {

    /**
     * Callbacks from the helper to coordinate view-content animations with view animations.
     */
    public interface Callbacks {
        /**
         * Callback to prepare for the start animation for the launch target {@link TaskView}.
         */
        void onPrepareLaunchTargetForEnterAnimation();

        /**
         * Callback to start the animation for the launch target {@link TaskView}.
         */
        void onStartLaunchTargetEnterAnimation(int duration, boolean screenPinningEnabled,
                ReferenceCountedTrigger postAnimationTrigger);

        /**
         * Callback to start the animation for the launch target {@link TaskView} when it is
         * launched from Recents.
         */
        void onStartLaunchTargetLaunchAnimation(int duration, boolean screenPinningRequested,
                ReferenceCountedTrigger postAnimationTrigger);
    }

    private TaskStackView mStackView;

    private TaskViewTransform mTmpTransform = new TaskViewTransform();

    public TaskStackAnimationHelper(Context context, TaskStackView stackView) {
        mStackView = stackView;
    }

    /**
     * Prepares the stack views and puts them in their initial animation state while visible, before
     * the in-app enter animations start (after the window-transition completes).
     */
    public void prepareForEnterAnimation() {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        Resources res = mStackView.getResources();

        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();
        Task launchTargetTask = stack.getLaunchTarget();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int offscreenY = stackLayout.mStackRect.bottom;
        int taskViewAffiliateGroupEnterOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_view_affiliate_group_enter_offset);

        // Prepare each of the task views for their enter animation from front to back
        List<TaskView> taskViews = mStackView.getTaskViews();
        for (int i = taskViews.size() - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = (launchTargetTask != null &&
                    launchTargetTask.group.isTaskAboveTask(task, launchTargetTask));
            boolean hideTask = (launchTargetTask != null &&
                    launchTargetTask.isFreeformTask() && task.isFreeformTask());

            // Get the current transform for the task, which will be used to position it offscreen
            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);

            if (hideTask) {
                tv.setVisibility(View.INVISIBLE);
            } else if (launchState.launchedHasConfigurationChanged) {
                // Just load the views as-is
            } else if (launchState.launchedFromAppWithThumbnail) {
                if (task.isLaunchTarget) {
                    tv.onPrepareLaunchTargetForEnterAnimation();
                } else if (currentTaskOccludesLaunchTarget) {
                    // Move the task view slightly lower so we can animate it in
                    RectF bounds = new RectF(mTmpTransform.rect);
                    bounds.offset(0, taskViewAffiliateGroupEnterOffset);
                    tv.setClipViewInStack(false);
                    tv.setAlpha(0f);
                    tv.setLeftTopRightBottom((int) bounds.left, (int) bounds.top,
                            (int) bounds.right, (int) bounds.bottom);
                }
            } else if (launchState.launchedFromHome) {
                // Move the task view off screen (below) so we can animate it in
                RectF bounds = new RectF(mTmpTransform.rect);
                bounds.offsetTo(bounds.left, offscreenY);
                tv.setLeftTopRightBottom((int) bounds.left, (int) bounds.top, (int) bounds.right,
                        (int) bounds.bottom);
            }
        }
    }

    /**
     * Starts the in-app enter animation, which animates the {@link TaskView}s to their final places
     * depending on how Recents was triggered.
     */
    public void startEnterAnimation(final ReferenceCountedTrigger postAnimationTrigger) {
        RecentsConfiguration config = Recents.getConfiguration();
        RecentsActivityLaunchState launchState = config.getLaunchState();
        Resources res = mStackView.getResources();

        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();
        Task launchTargetTask = stack.getLaunchTarget();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int taskViewEnterFromAppDuration = res.getInteger(
                R.integer.recents_task_enter_from_app_duration);
        int taskViewEnterFromAffiliatedAppDuration = res.getInteger(
                R.integer.recents_task_enter_from_affiliated_app_duration);
        int taskViewEnterFromHomeDuration = res.getInteger(
                R.integer.recents_task_enter_from_home_duration);
        int taskViewEnterFromHomeStaggerDelay = res.getInteger(
                R.integer.recents_task_enter_from_home_stagger_delay);

        // Create enter animations for each of the views from front to back
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            final TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = false;
            if (launchTargetTask != null) {
                currentTaskOccludesLaunchTarget = launchTargetTask.group.isTaskAboveTask(task,
                        launchTargetTask);
            }

            // Get the current transform for the task, which will be updated to the final transform
            // to animate to depending on how recents was invoked
            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);

            if (launchState.launchedFromAppWithThumbnail) {
                if (task.isLaunchTarget) {
                    tv.onStartLaunchTargetEnterAnimation(taskViewEnterFromAppDuration,
                            mStackView.mScreenPinningEnabled, postAnimationTrigger);
                } else {
                    // Animate the task up if it was occluding the launch target
                    if (currentTaskOccludesLaunchTarget) {
                        AnimationProps taskAnimation = new AnimationProps(
                                taskViewEnterFromAffiliatedAppDuration, Interpolators.ALPHA_IN,
                                new AnimatorListenerAdapter() {
                                    @Override
                                    public void onAnimationEnd(Animator animation) {
                                        postAnimationTrigger.decrement();
                                        tv.setClipViewInStack(false);
                                    }
                                });
                        postAnimationTrigger.increment();
                        mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
                    }
                }

            } else if (launchState.launchedFromHome) {
                // Animate the tasks up
                int frontIndex = (taskViewCount - i - 1);
                int delay = frontIndex * taskViewEnterFromHomeStaggerDelay;
                int duration = taskViewEnterFromHomeDuration +
                        frontIndex * taskViewEnterFromHomeStaggerDelay;

                AnimationProps taskAnimation = new AnimationProps(delay,
                        duration, Interpolators.DECELERATE_QUINT,
                        postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
            }
        }
    }

    /**
     * Starts an in-app animation to hide all the task views so that we can transition back home.
     */
    public void startExitToHomeAnimation(boolean animated,
            ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();
        TaskStack stack = mStackView.getStack();

        // Break early if there are no tasks
        if (stack.getTaskCount() == 0) {
            return;
        }

        int offscreenY = stackLayout.mStackRect.bottom;
        int taskViewExitToHomeDuration = res.getInteger(
                R.integer.recents_task_exit_to_home_duration);

        // Create the animations for each of the tasks
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            AnimationProps taskAnimation = new AnimationProps(
                    animated ? taskViewExitToHomeDuration : 0, Interpolators.FAST_OUT_LINEAR_IN,
                    postAnimationTrigger.decrementOnAnimationEnd());
            postAnimationTrigger.increment();

            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);
            mTmpTransform.rect.offsetTo(mTmpTransform.rect.left, offscreenY);
            mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
        }
    }

    /**
     * Starts the animation for the launching task view, hiding any tasks that might occlude the
     * window transition for the launching task.
     */
    public void startLaunchTaskAnimation(TaskView launchingTaskView, boolean screenPinningRequested,
            final ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();

        int taskViewExitToAppDuration = res.getInteger(
                R.integer.recents_task_exit_to_app_duration);
        int taskViewAffiliateGroupEnterOffset = res.getDimensionPixelSize(
                R.dimen.recents_task_view_affiliate_group_enter_offset);

        Task launchingTask = launchingTaskView.getTask();
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            boolean currentTaskOccludesLaunchTarget = (launchingTask != null &&
                    launchingTask.group.isTaskAboveTask(task, launchingTask));

            if (tv == launchingTaskView) {
                tv.setClipViewInStack(false);
                tv.onStartLaunchTargetLaunchAnimation(taskViewExitToAppDuration,
                        screenPinningRequested, postAnimationTrigger);
            } else if (currentTaskOccludesLaunchTarget) {
                // Animate this task out of view
                AnimationProps taskAnimation = new AnimationProps(
                        taskViewExitToAppDuration, Interpolators.ALPHA_OUT,
                        postAnimationTrigger.decrementOnAnimationEnd());
                postAnimationTrigger.increment();

                stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                        null);
                mTmpTransform.alpha = 0f;
                mTmpTransform.rect.offset(0, taskViewAffiliateGroupEnterOffset);
                mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
            }
        }
    }

    /**
     * Starts the delete animation for the specified {@link TaskView}.
     */
    public void startDeleteTaskAnimation(Task deleteTask, final TaskView deleteTaskView,
            final ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();

        int taskViewRemoveAnimDuration = res.getInteger(
                R.integer.recents_animate_task_view_remove_duration);
        int taskViewRemoveAnimTranslationXPx = res.getDimensionPixelSize(
                R.dimen.recents_task_view_remove_anim_translation_x);

        // Disabling clipping with the stack while the view is animating away
        deleteTaskView.setClipViewInStack(false);

        // Compose the new animation and transform and star the animation
        AnimationProps taskAnimation = new AnimationProps(taskViewRemoveAnimDuration,
                Interpolators.ALPHA_OUT, new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                postAnimationTrigger.decrement();

                // Re-enable clipping with the stack (we will reuse this view)
                deleteTaskView.setClipViewInStack(true);
            }
        });
        postAnimationTrigger.increment();

        stackLayout.getStackTransform(deleteTask, stackScroller.getStackScroll(), mTmpTransform,
                null);
        mTmpTransform.alpha = 0f;
        mTmpTransform.rect.offset(taskViewRemoveAnimTranslationXPx, 0);
        mStackView.updateTaskViewToTransform(deleteTaskView, mTmpTransform, taskAnimation);
    }

    /**
     * Starts the animation to hide the {@link TaskView}s when the history is shown.
     */
    public void startShowHistoryAnimation(ReferenceCountedTrigger postAnimationTrigger) {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();

        int offscreenY = stackLayout.mStackRect.bottom;
        int historyTransitionDuration = res.getInteger(
                R.integer.recents_history_transition_duration);
        int startDelayIncr = 16;

        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            AnimationProps taskAnimation = new AnimationProps(startDelayIncr * i,
                    historyTransitionDuration, Interpolators.FAST_OUT_SLOW_IN,
                    postAnimationTrigger.decrementOnAnimationEnd());
            postAnimationTrigger.increment();

            stackLayout.getStackTransform(task, stackScroller.getStackScroll(), mTmpTransform,
                    null);
            mTmpTransform.alpha = 0f;
            mTmpTransform.rect.offsetTo(mTmpTransform.rect.left, offscreenY);
            mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
        }
    }

    /**
     * Starts the animation to show the {@link TaskView}s when the history is hidden.
     */
    public void startHideHistoryAnimation() {
        Resources res = mStackView.getResources();
        TaskStackLayoutAlgorithm stackLayout = mStackView.getStackAlgorithm();
        TaskStackViewScroller stackScroller = mStackView.getScroller();

        int historyTransitionDuration = res.getInteger(
                R.integer.recents_history_transition_duration);
        int startDelayIncr = 16;

        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = taskViewCount - 1; i >= 0; i--) {
            TaskView tv = taskViews.get(i);
            AnimationProps taskAnimation = new AnimationProps(startDelayIncr * i,
                    historyTransitionDuration, Interpolators.FAST_OUT_SLOW_IN);
            stackLayout.getStackTransform(tv.getTask(), stackScroller.getStackScroll(),
                    mTmpTransform, null);
            mTmpTransform.alpha = 1f;
            mStackView.updateTaskViewToTransform(tv, mTmpTransform, taskAnimation);
        }
    }
}
