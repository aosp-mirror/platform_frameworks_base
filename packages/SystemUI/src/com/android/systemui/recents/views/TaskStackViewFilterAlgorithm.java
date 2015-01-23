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

package com.android.systemui.recents.views;

import com.android.systemui.recents.Constants;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.model.Task;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/* The layout logic for a TaskStackView */
public class TaskStackViewFilterAlgorithm {

    RecentsConfiguration mConfig;
    TaskStackView mStackView;
    ViewPool<TaskView, Task> mViewPool;

    public TaskStackViewFilterAlgorithm(RecentsConfiguration config, TaskStackView stackView,
                                        ViewPool<TaskView, Task> viewPool) {
        mConfig = config;
        mStackView = stackView;
        mViewPool = viewPool;
    }

    /** Orchestrates the animations of the current child views and any new views. */
    void startFilteringAnimation(ArrayList<Task> curTasks,
                                 ArrayList<TaskViewTransform> curTaskTransforms,
                                 final ArrayList<Task> tasks,
                                 final ArrayList<TaskViewTransform> taskTransforms) {
        // Calculate the transforms to animate out all the existing views if they are not in the
        // new visible range (or to their final positions in the stack if they are)
        final ArrayList<TaskView> childrenToRemove = new ArrayList<TaskView>();
        final HashMap<TaskView, TaskViewTransform> childViewTransforms =
                new HashMap<TaskView, TaskViewTransform>();
        int duration = getExitTransformsForFilterAnimation(curTasks, curTaskTransforms, tasks,
                taskTransforms, childViewTransforms, childrenToRemove);

        // If all the current views are in the visible range of the new stack, then don't wait for
        // views to animate out and animate all the new views into their place
        final boolean unifyNewViewAnimation = childrenToRemove.isEmpty();
        if (unifyNewViewAnimation) {
            int inDuration = getEnterTransformsForFilterAnimation(tasks, taskTransforms,
                    childViewTransforms);
            duration = Math.max(duration, inDuration);
        }

        // Animate all the views to their final transforms
        for (final TaskView tv : childViewTransforms.keySet()) {
            TaskViewTransform t = childViewTransforms.get(tv);
            tv.animate().cancel();
            tv.animate()
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            childViewTransforms.remove(tv);
                            if (childViewTransforms.isEmpty()) {
                                // Return all the removed children to the view pool
                                for (TaskView tv : childrenToRemove) {
                                    mViewPool.returnViewToPool(tv);
                                }

                                if (!unifyNewViewAnimation) {
                                    // For views that are not already visible, animate them in
                                    childViewTransforms.clear();
                                    int duration = getEnterTransformsForFilterAnimation(tasks,
                                            taskTransforms, childViewTransforms);
                                    for (final TaskView tv : childViewTransforms.keySet()) {
                                        TaskViewTransform t = childViewTransforms.get(tv);
                                        tv.updateViewPropertiesToTaskTransform(t, duration);
                                    }
                                }
                            }
                        }
                    });
            tv.updateViewPropertiesToTaskTransform(t, duration);
        }
    }

    /**
     * Creates the animations for all the children views that need to be animated in when we are
     * un/filtering a stack, and returns the duration for these animations.
     */
    int getEnterTransformsForFilterAnimation(ArrayList<Task> tasks,
                                             ArrayList<TaskViewTransform> taskTransforms,
                                             HashMap<TaskView, TaskViewTransform> childViewTransformsOut) {
        int offset = 0;
        int movement = 0;
        int taskCount = tasks.size();
        for (int i = taskCount - 1; i >= 0; i--) {
            Task task = tasks.get(i);
            TaskViewTransform toTransform = taskTransforms.get(i);
            if (toTransform.visible) {
                TaskView tv = mStackView.getChildViewForTask(task);
                if (tv == null) {
                    // For views that are not already visible, animate them in
                    tv = mViewPool.pickUpViewFromPool(task, task);

                    // Compose a new transform to fade and slide the new task in
                    TaskViewTransform fromTransform = new TaskViewTransform(toTransform);
                    tv.prepareTaskTransformForFilterTaskHidden(fromTransform);
                    tv.updateViewPropertiesToTaskTransform(fromTransform, 0);

                    toTransform.startDelay = offset * Constants.Values.TaskStackView.FilterStartDelay;
                    childViewTransformsOut.put(tv, toTransform);

                    // Use the movement of the new views to calculate the duration of the animation
                    movement = Math.max(movement,
                            Math.abs(toTransform.translationY - fromTransform.translationY));
                    offset++;
                }
            }
        }
        return mConfig.filteringNewViewsAnimDuration;
    }

    /**
     * Creates the animations for all the children views that need to be removed or to move views
     * to their un/filtered position when we are un/filtering a stack, and returns the duration
     * for these animations.
     */
    int getExitTransformsForFilterAnimation(ArrayList<Task> curTasks,
                                            ArrayList<TaskViewTransform> curTaskTransforms,
                                            ArrayList<Task> tasks, ArrayList<TaskViewTransform> taskTransforms,
                                            HashMap<TaskView, TaskViewTransform> childViewTransformsOut,
                                            ArrayList<TaskView> childrenToRemoveOut) {
        // Animate all of the existing views out of view (if they are not in the visible range in
        // the new stack) or to their final positions in the new stack
        int offset = 0;
        int movement = 0;
        List<TaskView> taskViews = mStackView.getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskView tv = taskViews.get(i);
            Task task = tv.getTask();
            int taskIndex = tasks.indexOf(task);
            TaskViewTransform toTransform;

            // If the view is no longer visible, then we should just animate it out
            boolean willBeInvisible = taskIndex < 0 || !taskTransforms.get(taskIndex).visible;
            if (willBeInvisible) {
                if (taskIndex < 0) {
                    toTransform = curTaskTransforms.get(curTasks.indexOf(task));
                } else {
                    toTransform = new TaskViewTransform(taskTransforms.get(taskIndex));
                }
                tv.prepareTaskTransformForFilterTaskVisible(toTransform);
                childrenToRemoveOut.add(tv);
            } else {
                toTransform = taskTransforms.get(taskIndex);
                // Use the movement of the visible views to calculate the duration of the animation
                movement = Math.max(movement, Math.abs(toTransform.translationY -
                        (int) tv.getTranslationY()));
            }

            toTransform.startDelay = offset * Constants.Values.TaskStackView.FilterStartDelay;
            childViewTransformsOut.put(tv, toTransform);
            offset++;
        }
        return mConfig.filteringCurrentViewsAnimDuration;
    }

}