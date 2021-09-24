/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.WindowManagerPolicyConstants.SPLIT_DIVIDER_LAYER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.ActivityRecord.State.RESUMED;
import static com.android.server.wm.ActivityTaskManagerService.TAG_ROOT_TASK;
import static com.android.server.wm.DisplayContent.alwaysCreateRootTask;
import static com.android.server.wm.TaskFragment.TASK_FRAGMENT_VISIBILITY_VISIBLE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ROOT_TASK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import static java.lang.Integer.MIN_VALUE;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Slog;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.wm.LaunchParamsController.LaunchParams;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link DisplayArea} that represents a section of a screen that contains app window containers.
 *
 * The children can be either {@link Task} or {@link TaskDisplayArea}.
 */
final class TaskDisplayArea extends DisplayArea<WindowContainer> {

    DisplayContent mDisplayContent;

    /**
     * A color layer that serves as a solid color background to certain animations.
     */
    private SurfaceControl mColorBackgroundLayer;

    /**
     * This counter is used to make sure we don't prematurely clear the background color in the
     * case that background color animations are interleaved.
     * NOTE: The last set color will remain until the counter is reset to 0, which means that an
     * animation background color may sometime remain after the animation has finished through an
     * animation with a different background color if an animation starts after and ends before
     * another where both set different background colors. However, this is not a concern as
     * currently all task animation backgrounds are the same color.
     */
    private int mColorLayerCounter = 0;

    /**
     * A control placed at the appropriate level for transitions to occur.
     */
    private SurfaceControl mAppAnimationLayer;
    private SurfaceControl mBoostedAppAnimationLayer;
    private SurfaceControl mHomeAppAnimationLayer;

    /**
     * Given that the split-screen divider does not have an AppWindowToken, it
     * will have to live inside of a "NonAppWindowContainer". However, in visual Z order
     * it will need to be interleaved with some of our children, appearing on top of
     * both docked root tasks but underneath any assistant root tasks.
     *
     * To solve this problem we have this anchor control, which will always exist so
     * we can always assign it the correct value in our {@link #assignChildLayers}.
     * Likewise since it always exists, we can always
     * assign the divider a layer relative to it. This way we prevent linking lifecycle
     * events between tasks and the divider window.
     */
    private SurfaceControl mSplitScreenDividerAnchor;

    // Cached reference to some special tasks we tend to get a lot so we don't need to loop
    // through the list to find them.
    private Task mRootHomeTask;
    private Task mRootPinnedTask;
    private Task mRootSplitScreenPrimaryTask;

    // TODO(b/159029784): Remove when getStack() behavior is cleaned-up
    private Task mRootRecentsTask;

    private final ArrayList<WindowContainer> mTmpAlwaysOnTopChildren = new ArrayList<>();
    private final ArrayList<WindowContainer> mTmpNormalChildren = new ArrayList<>();
    private final ArrayList<WindowContainer> mTmpHomeChildren = new ArrayList<>();
    private final IntArray mTmpNeedsZBoostIndexes = new IntArray();
    private int mTmpLayerForAnimationLayer;

    private ArrayList<Task> mTmpTasks = new ArrayList<>();

    private ActivityTaskManagerService mAtmService;

    private RootWindowContainer mRootWindowContainer;

    // Launch root tasks by activityType then by windowingMode.
    static private class LaunchRootTaskDef {
        Task task;
        int[] windowingModes;
        int[] activityTypes;

        boolean contains(int windowingMode, int activityType) {
            return ArrayUtils.contains(windowingModes, windowingMode)
                    && ArrayUtils.contains(activityTypes, activityType);
        }
    }
    private final ArrayList<LaunchRootTaskDef> mLaunchRootTasks = new ArrayList<>();

    /**
     * A launch root task for activity launching with {@link FLAG_ACTIVITY_LAUNCH_ADJACENT} flag.
     */
    private Task mLaunchAdjacentFlagRootTask;

    /**
     * A focusable root task that is purposely to be positioned at the top. Although the root
     * task may not have the topmost index, it is used as a preferred candidate to prevent being
     * unable to resume target root task properly when there are other focusable always-on-top
     * root tasks.
     */
    @VisibleForTesting
    Task mPreferredTopFocusableRootTask;

    /**
     * If this is the same as {@link #getFocusedRootTask} then the activity on the top of the
     * focused root task has been resumed. If root tasks are changing position this will hold the
     * old root task until the new root task becomes resumed after which it will be set to
     * current focused root task.
     */
    Task mLastFocusedRootTask;
    /**
     * All of the root tasks on this display. Order matters, topmost root task is in front of all
     * other root tasks, bottommost behind. Accessed directly by ActivityManager package classes.
     * Any calls changing the list should also call {@link #onRootTaskOrderChanged(Task)}.
     */
    private ArrayList<OnRootTaskOrderChangedListener> mRootTaskOrderChangedCallbacks =
            new ArrayList<>();

    /**
     * The task display area is removed from the system and we are just waiting for all activities
     * on it to be finished before removing this object.
     */
    private boolean mRemoved;

    /**
     * The id of a leaf task that most recently being moved to front.
     */
    private int mLastLeafTaskToFrontId;

    /**
     * Whether this TaskDisplayArea was created by a {@link android.window.DisplayAreaOrganizer}.
     * If {@code true}, this will be removed when the organizer is unregistered.
     */
    final boolean mCreatedByOrganizer;

    /**
     * True if this TaskDisplayArea can have a home task
     * {@link WindowConfiguration#ACTIVITY_TYPE_HOME}
     */
    private final boolean mCanHostHomeTask;

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
                    int displayAreaFeature) {
        this(displayContent, service, name, displayAreaFeature, false /* createdByOrganizer */,
                true /* canHostHomeTask */);
    }

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
                    int displayAreaFeature, boolean createdByOrganizer) {
        this(displayContent, service, name, displayAreaFeature, createdByOrganizer,
                true /* canHostHomeTask */);
    }

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
                    int displayAreaFeature, boolean createdByOrganizer,
                    boolean canHostHomeTask) {
        super(service, Type.ANY, name, displayAreaFeature);
        mDisplayContent = displayContent;
        mRootWindowContainer = service.mRoot;
        mAtmService = service.mAtmService;
        mCreatedByOrganizer = createdByOrganizer;
        mCanHostHomeTask = canHostHomeTask;
    }

    /**
     * Returns the topmost root task on the display that is compatible with the input windowing mode
     * and activity type. Null is no compatible root task on the display.
     */
    @Nullable
    Task getRootTask(int windowingMode, int activityType) {
        if (activityType == ACTIVITY_TYPE_HOME) {
            return mRootHomeTask;
        } else if (activityType == ACTIVITY_TYPE_RECENTS) {
            return mRootRecentsTask;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return mRootPinnedTask;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            return mRootSplitScreenPrimaryTask;
        }
        return getRootTask(rootTask -> {
            if (activityType == ACTIVITY_TYPE_UNDEFINED
                    && windowingMode == rootTask.getWindowingMode()) {
                // Passing in undefined type means we want to match the topmost root task with the
                // windowing mode.
                return true;
            }
            return rootTask.isCompatible(windowingMode, activityType);
        });
    }

    @VisibleForTesting
    Task getTopRootTask() {
        return getRootTask(t -> true);
    }

    @Nullable
    Task getRootHomeTask() {
        return mRootHomeTask;
    }

    @Nullable
    Task getRootRecentsTask() {
        return mRootRecentsTask;
    }

    Task getRootPinnedTask() {
        return mRootPinnedTask;
    }

    Task getRootSplitScreenPrimaryTask() {
        return mRootSplitScreenPrimaryTask;
    }

    Task getRootSplitScreenSecondaryTask() {
        // Only check the direct child Task for now, since the primary is also a direct child Task.
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task task = mChildren.get(i).asTask();
            if (task != null && task.inSplitScreenSecondaryWindowingMode()) {
                return task;
            }
        }
        return null;
    }

    ArrayList<Task> getVisibleTasks() {
        final ArrayList<Task> visibleTasks = new ArrayList<>();
        forAllTasks(task -> {
            if (task.isLeafTask() && task.isVisible()) {
                visibleTasks.add(task);
            }
        });
        return visibleTasks;
    }

    void onRootTaskWindowingModeChanged(Task rootTask) {
        removeRootTaskReferenceIfNeeded(rootTask);
        addRootTaskReferenceIfNeeded(rootTask);
        if (rootTask == mRootPinnedTask && getTopRootTask() != rootTask) {
            // Looks like this root task changed windowing mode to pinned. Move it to the top.
            positionChildAt(POSITION_TOP, rootTask, false /* includingParents */);
        }
    }

    void addRootTaskReferenceIfNeeded(Task rootTask) {
        if (rootTask.isActivityTypeHome()) {
            if (mRootHomeTask != null) {
                if (!rootTask.isDescendantOf(mRootHomeTask)) {
                    throw new IllegalArgumentException("addRootTaskReferenceIfNeeded: root home"
                            + " task=" + mRootHomeTask + " already exist on display=" + this
                            + " rootTask=" + rootTask);
                }
            } else {
                mRootHomeTask = rootTask;
            }
        } else if (rootTask.isActivityTypeRecents()) {
            if (mRootRecentsTask != null) {
                if (!rootTask.isDescendantOf(mRootRecentsTask)) {
                    throw new IllegalArgumentException("addRootTaskReferenceIfNeeded: root recents"
                            + " task=" + mRootRecentsTask + " already exist on display=" + this
                            + " rootTask=" + rootTask);
                }
            } else {
                mRootRecentsTask = rootTask;
            }
        }

        if (!rootTask.isRootTask()) {
            return;
        }
        final int windowingMode = rootTask.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_PINNED) {
            if (mRootPinnedTask != null) {
                throw new IllegalArgumentException(
                        "addRootTaskReferenceIfNeeded: root pinned task=" + mRootPinnedTask
                                + " already exist on display=" + this + " rootTask=" + rootTask);
            }
            mRootPinnedTask = rootTask;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            if (mRootSplitScreenPrimaryTask != null) {
                throw new IllegalArgumentException(
                        "addRootTaskReferenceIfNeeded: root split screen primary task="
                                + mRootSplitScreenPrimaryTask
                                + " already exist on display=" + this + " rootTask=" + rootTask);
            }
            mRootSplitScreenPrimaryTask = rootTask;
        }
    }

    void removeRootTaskReferenceIfNeeded(Task rootTask) {
        if (rootTask == mRootHomeTask) {
            mRootHomeTask = null;
        } else if (rootTask == mRootRecentsTask) {
            mRootRecentsTask = null;
        } else if (rootTask == mRootPinnedTask) {
            mRootPinnedTask = null;
        } else if (rootTask == mRootSplitScreenPrimaryTask) {
            mRootSplitScreenPrimaryTask = null;
        }
    }

    @Override
    void addChild(WindowContainer child, int position) {
        if (child.asTaskDisplayArea() != null) {
            if (DEBUG_ROOT_TASK) {
                Slog.d(TAG_WM, "Set TaskDisplayArea=" + child + " on taskDisplayArea=" + this);
            }
            super.addChild(child, position);
        } else if (child.asTask() != null) {
            addChildTask(child.asTask(), position);
        } else {
            throw new IllegalArgumentException(
                    "TaskDisplayArea can only add Task and TaskDisplayArea, but found "
                            + child);
        }
    }

    private void addChildTask(Task task, int position) {
        if (DEBUG_ROOT_TASK) Slog.d(TAG_WM, "Set task=" + task + " on taskDisplayArea=" + this);

        addRootTaskReferenceIfNeeded(task);
        position = findPositionForRootTask(position, task, true /* adding */);

        super.addChild(task, position);
        if (mPreferredTopFocusableRootTask != null
                && task.isFocusable()
                && mPreferredTopFocusableRootTask.compareTo(task) < 0) {
            // Clear preferred top because the adding focusable task has a higher z-order.
            mPreferredTopFocusableRootTask = null;
        }
        mAtmService.updateSleepIfNeededLocked();
        onRootTaskOrderChanged(task);
    }

    @Override
    protected void removeChild(WindowContainer child) {
        if (child.asTaskDisplayArea() != null) {
            super.removeChild(child);
        } else if (child.asTask() != null) {
            removeChildTask(child.asTask());
        } else {
            throw new IllegalArgumentException(
                    "TaskDisplayArea can only remove Task and TaskDisplayArea, but found "
                            + child);
        }
    }

    private void removeChildTask(Task task) {
        super.removeChild(task);
        onRootTaskRemoved(task);
        mAtmService.updateSleepIfNeededLocked();
        removeRootTaskReferenceIfNeeded(task);
    }

    @Override
    boolean isOnTop() {
        // Considered always on top
        return true;
    }

    @Override
    void positionChildAt(int position, WindowContainer child, boolean includingParents) {
        if (child.asTaskDisplayArea() != null) {
            super.positionChildAt(position, child, includingParents);
        } else if (child.asTask() != null) {
            positionChildTaskAt(position, child.asTask(), includingParents);
        } else {
            throw new IllegalArgumentException(
                    "TaskDisplayArea can only position Task and TaskDisplayArea, but found "
                            + child);
        }
    }

    private void positionChildTaskAt(int position, Task child, boolean includingParents) {
        final boolean moveToTop = position >= getChildCount() - 1;
        final boolean moveToBottom = position <= 0;

        final int oldPosition = mChildren.indexOf(child);
        if (child.isAlwaysOnTop() && !moveToTop) {
            // This root task is always-on-top, override the default behavior.
            Slog.w(TAG_WM, "Ignoring move of always-on-top root task=" + this + " to bottom");

            // Moving to its current position, as we must call super but we don't want to
            // perform any meaningful action.
            super.positionChildAt(oldPosition, child, false /* includingParents */);
            return;
        }
        // We don't allow untrusted display to top when root task moves to top,
        // until user tapping this display to change display position as top intentionally.
        //
        // Displays with {@code mDontMoveToTop} property set to {@code true} won't be
        // allowed to top neither.
        if ((!mDisplayContent.isTrusted() || mDisplayContent.mDontMoveToTop)
                && !getParent().isOnTop()) {
            includingParents = false;
        }
        final int targetPosition = findPositionForRootTask(position, child, false /* adding */);
        super.positionChildAt(targetPosition, child, false /* includingParents */);

        if (includingParents && getParent() != null && (moveToTop || moveToBottom)) {
            getParent().positionChildAt(moveToTop ? POSITION_TOP : POSITION_BOTTOM,
                    this /* child */, true /* includingParents */);
        }

        child.updateTaskMovement(moveToTop, targetPosition);

        mDisplayContent.layoutAndAssignWindowLayersIfNeeded();

        // The insert position may be adjusted to non-top when there is always-on-top root task.
        // Since the original position is preferred to be top, the root task should have higher
        // priority when we are looking for top focusable root task. The condition {@code
        // wasContained} restricts the preferred root task is set only when moving an existing
        // root task to top instead of adding a new root task that may be too early (e.g. in the
        // middle of launching or reparenting).
        if (moveToTop && child.isFocusableAndVisible()) {
            mPreferredTopFocusableRootTask = child;
        } else if (mPreferredTopFocusableRootTask == child) {
            mPreferredTopFocusableRootTask = null;
        }

        // Update the top resumed activity because the preferred top focusable task may be changed.
        mAtmService.mTaskSupervisor.updateTopResumedActivityIfNeeded();

        final ActivityRecord r = child.getTopResumedActivity();
        if (r != null && r == mRootWindowContainer.getTopResumedActivity()) {
            mAtmService.setResumedActivityUncheckLocked(r, "positionChildAt");
        }

        if (mChildren.indexOf(child) != oldPosition) {
            onRootTaskOrderChanged(child);
        }
    }

    void onLeafTaskRemoved(int taskId) {
        if (mLastLeafTaskToFrontId == taskId) {
            mLastLeafTaskToFrontId = INVALID_TASK_ID;
        }
    }

    void onLeafTaskMoved(Task t, boolean toTop) {
        if (!toTop) {
            if (t.mTaskId == mLastLeafTaskToFrontId) {
                mLastLeafTaskToFrontId = INVALID_TASK_ID;
            }
            return;
        }
        if (t.mTaskId == mLastLeafTaskToFrontId || t.topRunningActivityLocked() == null) {
            return;
        }

        mLastLeafTaskToFrontId = t.mTaskId;
        EventLogTags.writeWmTaskToFront(t.mUserId, t.mTaskId);
        // Notifying only when a leaf task moved to front. Or the listeners would be notified
        // couple times from the leaf task all the way up to the root task.
        mAtmService.getTaskChangeNotificationController().notifyTaskMovedToFront(t.getTaskInfo());
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        super.onChildPositionChanged(child);
        mRootWindowContainer.invalidateTaskLayers();
    }

    @Override
    boolean forAllTaskDisplayAreas(Function<TaskDisplayArea, Boolean> callback,
            boolean traverseTopToBottom) {
        // Apply the callback to all TDAs at or below this container. If the callback returns true,
        // stop early.
        if (traverseTopToBottom) {
            // When it is top to bottom, run on child TDA first as they are on top of the parent.
            return super.forAllTaskDisplayAreas(callback, traverseTopToBottom)
                    || callback.apply(this);
        }
        return callback.apply(this) || super.forAllTaskDisplayAreas(callback, traverseTopToBottom);
    }

    @Override
    void forAllTaskDisplayAreas(Consumer<TaskDisplayArea> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            super.forAllTaskDisplayAreas(callback, traverseTopToBottom);
            callback.accept(this);
        } else {
            callback.accept(this);
            super.forAllTaskDisplayAreas(callback, traverseTopToBottom);
        }
    }

    @Nullable
    @Override
    <R> R reduceOnAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R> accumulator,
            @Nullable R initValue, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            final R result =
                    super.reduceOnAllTaskDisplayAreas(accumulator, initValue, traverseTopToBottom);
            return accumulator.apply(this, result);
        } else {
            final R result = accumulator.apply(this, initValue);
            return super.reduceOnAllTaskDisplayAreas(accumulator, result, traverseTopToBottom);

        }
    }

    @Nullable
    @Override
    <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback,
            boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            final R item = super.getItemFromTaskDisplayAreas(callback, traverseTopToBottom);
            return item != null ? item : callback.apply(this);
        } else {
            final R item = callback.apply(this);
            return item != null
                    ? item
                    : super.getItemFromTaskDisplayAreas(callback, traverseTopToBottom);
        }
    }

    /**
     * Assigns a priority number to root task types. This priority defines an order between the
     * types of root task that are added to the task display area.
     *
     * Higher priority number indicates that the root task should have a higher z-order.
     *
     * For child {@link TaskDisplayArea}, it will be the priority of its top child.
     *
     * @return the priority of the root task
     */
    private int getPriority(WindowContainer child) {
        final TaskDisplayArea tda = child.asTaskDisplayArea();
        if (tda != null) {
            // Use the top child priority as the TaskDisplayArea priority.
            return tda.getPriority(tda.getTopChild());
        }
        final Task rootTask = child.asTask();
        if (mWmService.mAssistantOnTopOfDream && rootTask.isActivityTypeAssistant()) return 4;
        if (rootTask.isActivityTypeDream()) return 3;
        if (rootTask.inPinnedWindowingMode()) return 2;
        if (rootTask.isAlwaysOnTop()) return 1;
        return 0;
    }

    private int findMinPositionForRootTask(Task rootTask) {
        int minPosition = POSITION_BOTTOM;
        for (int i = 0; i < mChildren.size(); ++i) {
            if (getPriority(mChildren.get(i)) < getPriority(rootTask)) {
                minPosition = i;
            } else {
                break;
            }
        }

        if (rootTask.isAlwaysOnTop()) {
            // Since a root task could be repositioned while still being one of the children, we
            // check if this always-on-top root task already exists and if so, set the minPosition
            // to its previous position.
            // Use mChildren.indexOf instead of getTaskIndexOf because we need to place the rootTask
            // as a direct child.
            final int currentIndex = mChildren.indexOf(rootTask);
            if (currentIndex > minPosition) {
                minPosition = currentIndex;
            }
        }
        return minPosition;
    }

    private int findMaxPositionForRootTask(Task rootTask) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer curr = mChildren.get(i);
            // Since a root task could be repositioned while still being one of the children, we
            // check if 'curr' is the same root task and skip it if so
            final boolean sameRootTask = curr == rootTask;
            if (getPriority(curr) <= getPriority(rootTask) && !sameRootTask) {
                return i;
            }
        }
        return 0;
    }

    /**
     * When root task is added or repositioned, find a proper position for it.
     *
     * The order is defined as:
     * - Dream is on top of everything
     * - PiP is directly below the Dream
     * - always-on-top root tasks are directly below PiP; new always-on-top root tasks are added
     * above existing ones
     * - other non-always-on-top root tasks come directly below always-on-top root tasks; new
     * non-always-on-top root tasks are added directly below always-on-top root tasks and above
     * existing non-always-on-top root tasks
     * - if {@link #mAssistantOnTopOfDream} is enabled, then Assistant is on top of everything
     * (including the Dream); otherwise, it is a normal non-always-on-top root task
     *
     * @param requestedPosition Position requested by caller.
     * @param rootTask          Root task to be added or positioned.
     * @param adding            Flag indicates whether we're adding a new root task or positioning
     *                          an existing.
     * @return The proper position for the root task.
     */
    private int findPositionForRootTask(int requestedPosition, Task rootTask, boolean adding) {
        // The max possible position we can insert the root task at.
        int maxPosition = findMaxPositionForRootTask(rootTask);
        // The min possible position we can insert the root task at.
        int minPosition = findMinPositionForRootTask(rootTask);

        // Cap the requested position to something reasonable for the previous position check
        // below.
        if (requestedPosition == POSITION_TOP) {
            requestedPosition = mChildren.size();
        } else if (requestedPosition == POSITION_BOTTOM) {
            requestedPosition = 0;
        }

        int targetPosition = requestedPosition;
        targetPosition = Math.min(targetPosition, maxPosition);
        targetPosition = Math.max(targetPosition, minPosition);

        int prevPosition = mChildren.indexOf(rootTask);
        // The positions we calculated above (maxPosition, minPosition) do not take into
        // consideration the following edge cases.
        // 1) We need to adjust the position depending on the value "adding".
        // 2) When we are moving a root task to another position, we also need to adjust the
        //    position depending on whether the root task is moving to a higher or lower position.
        if ((targetPosition != requestedPosition) && (adding || targetPosition < prevPosition)) {
            targetPosition++;
        }

        return targetPosition;
    }

    @Override
    boolean forAllWindows(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            if (super.forAllWindows(callback, traverseTopToBottom)) {
                return true;
            }
            if (forAllExitingAppTokenWindows(callback, traverseTopToBottom)) {
                return true;
            }
        } else {
            if (forAllExitingAppTokenWindows(callback, traverseTopToBottom)) {
                return true;
            }
            if (super.forAllWindows(callback, traverseTopToBottom)) {
                return true;
            }
        }
        return false;
    }

    private boolean forAllExitingAppTokenWindows(ToBooleanFunction<WindowState> callback,
            boolean traverseTopToBottom) {
        // For legacy reasons we process the RootTask.mExitingActivities first here before the
        // app tokens.
        // TODO: Investigate if we need to continue to do this or if we can just process them
        // in-order.
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                // Only run on those of direct Task child, because child TaskDisplayArea has run on
                // its child in #forAllWindows()
                if (mChildren.get(i).asTask() == null) {
                    continue;
                }
                final List<ActivityRecord> activities =
                        mChildren.get(i).asTask().mExitingActivities;
                for (int j = activities.size() - 1; j >= 0; --j) {
                    if (activities.get(j).forAllWindowsUnchecked(callback,
                            traverseTopToBottom)) {
                        return true;
                    }
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; ++i) {
                // Only run on those of direct Task child, because child TaskDisplayArea has run on
                // its child in #forAllWindows()
                if (mChildren.get(i).asTask() == null) {
                    continue;
                }
                final List<ActivityRecord> activities =
                        mChildren.get(i).asTask().mExitingActivities;
                final int appTokensCount = activities.size();
                for (int j = 0; j < appTokensCount; j++) {
                    if (activities.get(j).forAllWindowsUnchecked(callback,
                            traverseTopToBottom)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    int getOrientation(int candidate) {
        mLastOrientationSource = null;
        if (mIgnoreOrientationRequest) {
            return SCREEN_ORIENTATION_UNSET;
        }
        if (!canSpecifyOrientation()) {
            // We only respect orientation of the focused TDA, which can be a child of this TDA.
            return reduceOnAllTaskDisplayAreas((taskDisplayArea, orientation) -> {
                if (taskDisplayArea == this || orientation != SCREEN_ORIENTATION_UNSET) {
                    return orientation;
                }
                return taskDisplayArea.getOrientation(candidate);
            }, SCREEN_ORIENTATION_UNSET);
        }

        if (isRootTaskVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
            // Apps and their containers are not allowed to specify an orientation while using
            // root tasks...except for the root home task if it is not resizable and currently
            // visible (top of) its root task.
            if (mRootHomeTask != null && !mRootHomeTask.isResizeable()) {
                // Manually nest one-level because because getOrientation() checks fillsParent()
                // which checks that requestedOverrideBounds() is empty. However, in this case,
                // it is not empty because it's been overridden to maintain the fullscreen size
                // within a smaller split-root.
                final Task topHomeTask = mRootHomeTask.getTopMostTask();
                final ActivityRecord topHomeActivity = topHomeTask.getTopNonFinishingActivity();
                // If a home activity is in the process of launching and isn't yet visible we
                // should still respect the root task's preferred orientation to ensure rotation
                // occurs before the home activity finishes launching.
                final boolean isHomeActivityLaunching = topHomeActivity != null
                        && topHomeActivity.mVisibleRequested;
                if (topHomeTask.isVisible() || isHomeActivityLaunching) {
                    final int orientation = topHomeTask.getOrientation();
                    if (orientation != SCREEN_ORIENTATION_UNSET) {
                        return orientation;
                    }
                }
            }
            return SCREEN_ORIENTATION_UNSPECIFIED;
        } else {
            // Apps and their containers are not allowed to specify an orientation of non floating
            // visible tasks created by organizer. The organizer handles the orientation instead.
            final Task nonFloatingTopTask =
                    getRootTask(t -> !t.getWindowConfiguration().tasksAreFloating());
            if (nonFloatingTopTask != null && nonFloatingTopTask.mCreatedByOrganizer
                    && nonFloatingTopTask.isVisible()) {
                return SCREEN_ORIENTATION_UNSPECIFIED;
            }
        }

        final int orientation = super.getOrientation(candidate);
        if (orientation != SCREEN_ORIENTATION_UNSET
                && orientation != SCREEN_ORIENTATION_BEHIND) {
            ProtoLog.v(WM_DEBUG_ORIENTATION,
                    "App is requesting an orientation, return %d for display id=%d",
                    orientation, mDisplayContent.mDisplayId);
            return orientation;
        }

        ProtoLog.v(WM_DEBUG_ORIENTATION,
                "No app is requesting an orientation, return %d for display id=%d",
                mDisplayContent.getLastOrientation(), mDisplayContent.mDisplayId);
        // The next app has not been requested to be visible, so we keep the current orientation
        // to prevent freezing/unfreezing the display too early.
        return mDisplayContent.getLastOrientation();
    }

    @Override
    void assignChildLayers(SurfaceControl.Transaction t) {
        assignRootTaskOrdering(t);

        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).assignChildLayers(t);
        }
    }

    void assignRootTaskOrdering(SurfaceControl.Transaction t) {
        if (getParent() == null) {
            return;
        }
        mTmpAlwaysOnTopChildren.clear();
        mTmpHomeChildren.clear();
        mTmpNormalChildren.clear();
        for (int i = 0; i < mChildren.size(); ++i) {
            final WindowContainer child = mChildren.get(i);
            final TaskDisplayArea childTda = child.asTaskDisplayArea();
            if (childTda != null) {
                final Task childTdaTopRootTask = childTda.getTopRootTask();
                if (childTdaTopRootTask == null) {
                    mTmpNormalChildren.add(childTda);
                } else if (childTdaTopRootTask.isAlwaysOnTop()) {
                    mTmpAlwaysOnTopChildren.add(childTda);
                } else if (childTdaTopRootTask.isActivityTypeHome()) {
                    mTmpHomeChildren.add(childTda);
                } else {
                    mTmpNormalChildren.add(childTda);
                }
                continue;
            }

            final Task childTask = child.asTask();
            if (childTask.isAlwaysOnTop()) {
                mTmpAlwaysOnTopChildren.add(childTask);
            } else if (childTask.isActivityTypeHome()) {
                mTmpHomeChildren.add(childTask);
            } else {
                mTmpNormalChildren.add(childTask);
            }
        }

        int layer = 0;
        // Place root home tasks to the bottom.
        layer = adjustRootTaskLayer(t, mTmpHomeChildren, layer, false /* normalRootTasks */);
        // The home animation layer is between the root home tasks and the normal root tasks.
        final int layerForHomeAnimationLayer = layer++;
        mTmpLayerForAnimationLayer = layer++;
        layer = adjustRootTaskLayer(t, mTmpNormalChildren, layer, true /* normalRootTasks */);

        // The boosted animation layer is between the normal root tasks and the always on top
        // root tasks.
        final int layerForBoostedAnimationLayer = layer++;
        // Always on top tasks layer should higher than split divider layer so set it as start.
        layer = SPLIT_DIVIDER_LAYER + 1;
        adjustRootTaskLayer(t, mTmpAlwaysOnTopChildren, layer, false /* normalRootTasks */);

        t.setLayer(mHomeAppAnimationLayer, layerForHomeAnimationLayer);
        t.setLayer(mAppAnimationLayer, mTmpLayerForAnimationLayer);
        t.setLayer(mSplitScreenDividerAnchor, SPLIT_DIVIDER_LAYER);
        t.setLayer(mBoostedAppAnimationLayer, layerForBoostedAnimationLayer);
    }

    private int adjustNormalRootTaskLayer(WindowContainer child, int layer) {
        if ((child.asTask() != null && child.asTask().isAnimatingByRecents())
                || child.isAppTransitioning()) {
            // The animation layer is located above the highest animating root task and no
            // higher.
            mTmpLayerForAnimationLayer = layer++;
        }
        return layer;
    }

    /**
     * Adjusts the layer of the root task which belongs to the same group.
     * Note that there are three root task groups: home rootTasks, always on top rootTasks, and
     * normal rootTasks.
     *
     * @param startLayer   The beginning layer of this group of rootTasks.
     * @param normalRootTasks Set {@code true} if this group is neither home nor always on top.
     * @return The adjusted layer value.
     */
    private int adjustRootTaskLayer(SurfaceControl.Transaction t,
            ArrayList<WindowContainer> children, int startLayer, boolean normalRootTasks) {
        mTmpNeedsZBoostIndexes.clear();
        final int childCount = children.size();
        for (int i = 0; i < childCount; i++) {
            final WindowContainer child = children.get(i);
            final TaskDisplayArea childTda = child.asTaskDisplayArea();

            boolean childNeedsZBoost = childTda != null
                    ? childTda.childrenNeedZBoost()
                    : child.needsZBoost();

            if (!childNeedsZBoost) {
                child.assignLayer(t, startLayer++);
                if (normalRootTasks) {
                    startLayer = adjustNormalRootTaskLayer(child, startLayer);
                }
            } else {
                mTmpNeedsZBoostIndexes.add(i);
            }
        }

        final int zBoostSize = mTmpNeedsZBoostIndexes.size();
        for (int i = 0; i < zBoostSize; i++) {
            final WindowContainer child = children.get(mTmpNeedsZBoostIndexes.get(i));
            child.assignLayer(t, startLayer++);
            if (normalRootTasks) {
                startLayer = adjustNormalRootTaskLayer(child, startLayer);
            }
        }
        return startLayer;
    }

    private boolean childrenNeedZBoost() {
        final boolean[] needsZBoost = new boolean[1];
        forAllRootTasks(task -> {
            needsZBoost[0] |= task.needsZBoost();
        });
        return needsZBoost[0];
    }

    @Override
    SurfaceControl getAppAnimationLayer(@AnimationLayer int animationLayer) {
        switch (animationLayer) {
            case ANIMATION_LAYER_BOOSTED:
                return mBoostedAppAnimationLayer;
            case ANIMATION_LAYER_HOME:
                return mHomeAppAnimationLayer;
            case ANIMATION_LAYER_STANDARD:
            default:
                return mAppAnimationLayer;
        }
    }

    @Override
    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        final ActivityRecord activity = getTopMostActivity();
        return activity != null ? activity.createRemoteAnimationTarget(record) : null;
    }

    SurfaceControl getSplitScreenDividerAnchor() {
        return mSplitScreenDividerAnchor;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        if (getParent() != null) {
            super.onParentChanged(newParent, oldParent, () -> {
                mColorBackgroundLayer = makeChildSurface(null)
                        .setColorLayer()
                        .setName("colorBackgroundLayer")
                        .setCallsite("TaskDisplayArea.onParentChanged")
                        .build();
                mAppAnimationLayer = makeChildSurface(null)
                        .setName("animationLayer")
                        .setCallsite("TaskDisplayArea.onParentChanged")
                        .build();
                mBoostedAppAnimationLayer = makeChildSurface(null)
                        .setName("boostedAnimationLayer")
                        .setCallsite("TaskDisplayArea.onParentChanged")
                        .build();
                mHomeAppAnimationLayer = makeChildSurface(null)
                        .setName("homeAnimationLayer")
                        .setCallsite("TaskDisplayArea.onParentChanged")
                        .build();
                mSplitScreenDividerAnchor = makeChildSurface(null)
                        .setName("splitScreenDividerAnchor")
                        .setCallsite("TaskDisplayArea.onParentChanged")
                        .build();

                getSyncTransaction()
                        .show(mAppAnimationLayer)
                        .show(mBoostedAppAnimationLayer)
                        .show(mHomeAppAnimationLayer)
                        .show(mSplitScreenDividerAnchor);
            });
        } else {
            super.onParentChanged(newParent, oldParent);
            mWmService.mTransactionFactory.get()
                    .remove(mColorBackgroundLayer)
                    .remove(mAppAnimationLayer)
                    .remove(mBoostedAppAnimationLayer)
                    .remove(mHomeAppAnimationLayer)
                    .remove(mSplitScreenDividerAnchor)
                    .apply();
            mColorBackgroundLayer = null;
            mAppAnimationLayer = null;
            mBoostedAppAnimationLayer = null;
            mHomeAppAnimationLayer = null;
            mSplitScreenDividerAnchor = null;
        }
    }

    void setBackgroundColor(@ColorInt int color) {
        if (mColorBackgroundLayer == null) {
            return;
        }

        float r = ((color >> 16) & 0xff) / 255.0f;
        float g = ((color >>  8) & 0xff) / 255.0f;
        float b = ((color >>  0) & 0xff) / 255.0f;
        float a = ((color >> 24) & 0xff) / 255.0f;

        mColorLayerCounter++;

        getPendingTransaction().setLayer(mColorBackgroundLayer, MIN_VALUE)
                .setColor(mColorBackgroundLayer, new float[]{r, g, b})
                .setAlpha(mColorBackgroundLayer, a)
                .setWindowCrop(mColorBackgroundLayer, getSurfaceWidth(), getSurfaceHeight())
                .setPosition(mColorBackgroundLayer, 0, 0)
                .show(mColorBackgroundLayer);

        scheduleAnimation();
    }

    void clearBackgroundColor() {
        mColorLayerCounter--;

        // Only clear the color layer if we have received the same amounts of clear as set
        // requests.
        if (mColorLayerCounter == 0) {
            getPendingTransaction().hide(mColorBackgroundLayer);
            scheduleAnimation();
        }
    }

    @Override
    void migrateToNewSurfaceControl(SurfaceControl.Transaction t) {
        super.migrateToNewSurfaceControl(t);
        if (mAppAnimationLayer == null) {
            return;
        }

        // As TaskDisplayArea is getting a new surface, reparent and reorder the child surfaces.
        t.reparent(mColorBackgroundLayer, mSurfaceControl);
        t.reparent(mAppAnimationLayer, mSurfaceControl);
        t.reparent(mBoostedAppAnimationLayer, mSurfaceControl);
        t.reparent(mHomeAppAnimationLayer, mSurfaceControl);
        t.reparent(mSplitScreenDividerAnchor, mSurfaceControl);
        reassignLayer(t);
        scheduleAnimation();
    }

    void onRootTaskRemoved(Task rootTask) {
        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.v(TAG_ROOT_TASK, "onRootTaskRemoved: detaching " + rootTask + " from displayId="
                    + mDisplayContent.mDisplayId);
        }
        if (mPreferredTopFocusableRootTask == rootTask) {
            mPreferredTopFocusableRootTask = null;
        }
        if (mLaunchAdjacentFlagRootTask == rootTask) {
            mLaunchAdjacentFlagRootTask = null;
        }
        mDisplayContent.releaseSelfIfNeeded();
        onRootTaskOrderChanged(rootTask);
    }

    /**
     * Moves/reparents `task` to the back of whatever container the root home task is in. This is
     * for when we just want to move a task to "the back" vs. a specific place. The primary use-case
     * is to make sure that moved-to-back apps go into secondary split when in split-screen mode.
     */
    void positionTaskBehindHome(Task task) {
        final Task home = getOrCreateRootHomeTask();
        final WindowContainer homeParent = home.getParent();
        final Task homeParentTask = homeParent != null ? homeParent.asTask() : null;
        if (homeParentTask == null) {
            // reparent throws if parent didn't change...
            if (task.getParent() == this) {
                positionChildAt(POSITION_BOTTOM, task, false /*includingParents*/);
            } else {
                task.reparent(this, false /* onTop */);
            }
        } else if (homeParentTask == task.getParent()) {
            // Apparently reparent early-outs if same root task, so we have to explicitly reorder.
            homeParentTask.positionChildAtBottom(task);
        } else {
            task.reparent(homeParentTask, false /* toTop */,
                    Task.REPARENT_LEAVE_ROOT_TASK_IN_PLACE, false /* animate */,
                    false /* deferResume */, "positionTaskBehindHome");
        }
    }

    /**
     * Returns an existing root task compatible with the windowing mode and activity type or
     * creates one if a compatible root task doesn't exist.
     *
     * @see #getOrCreateRootTask(int, int, boolean, Task, Task, ActivityOptions, int)
     */
    Task getOrCreateRootTask(int windowingMode, int activityType, boolean onTop) {
        return getOrCreateRootTask(windowingMode, activityType, onTop, null /* candidateTask */,
                null /* sourceTask */, null /* options */, 0 /* intent */);
    }

    /**
     * When two level tasks are required for given windowing mode and activity type, returns an
     * existing compatible root task or creates a new one.
     * For one level task, the candidate task would be reused to also be the root task or create
     * a new root task if no candidate task.
     *
     * @param windowingMode The windowing mode the root task should be created in.
     * @param activityType  The activityType the root task should be created in.
     * @param onTop         If true the root task will be created at the top of the display,
     *                      else at the bottom.
     * @param candidateTask The possible task the activity might be launched in. Can be null.
     * @param sourceTask    The task requesting to start activity. Used to determine which of the
     *                      adjacent roots should be launch root of the new task. Can be null.
     * @param options       The activity options used to the launch. Can be null.
     * @param launchFlags   The launch flags for this launch.
     * @return The root task to use for the launch.
     * @see #getRootTask(int, int)
     */
    Task getOrCreateRootTask(int windowingMode, int activityType, boolean onTop,
            @Nullable Task candidateTask, @Nullable Task sourceTask,
            @Nullable ActivityOptions options, int launchFlags) {
        // Need to pass in a determined windowing mode to see if a new root task should be created,
        // so use its parent's windowing mode if it is undefined.
        if (!alwaysCreateRootTask(
                windowingMode != WINDOWING_MODE_UNDEFINED ? windowingMode : getWindowingMode(),
                activityType)) {
            Task rootTask = getRootTask(windowingMode, activityType);
            if (rootTask != null) {
                return rootTask;
            }
        } else if (candidateTask != null) {
            final int position = onTop ? POSITION_TOP : POSITION_BOTTOM;
            final Task launchRootTask = getLaunchRootTask(windowingMode, activityType, options,
                    sourceTask, launchFlags);
            if (launchRootTask != null) {
                if (candidateTask.getParent() == null) {
                    launchRootTask.addChild(candidateTask, position);
                } else if (candidateTask.getParent() != launchRootTask) {
                    candidateTask.reparent(launchRootTask, position);
                }
            } else if (candidateTask.getDisplayArea() != this || !candidateTask.isRootTask()) {
                if (candidateTask.getParent() == null) {
                    addChild(candidateTask, position);
                } else {
                    candidateTask.reparent(this, onTop);
                }
            }
            // Update windowing mode if necessary, e.g. moving a pinned task to fullscreen.
            if (candidateTask.getWindowingMode() != windowingMode) {
                candidateTask.setWindowingMode(windowingMode);
            }
            return candidateTask.getRootTask();
        }
        return new Task.Builder(mAtmService)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setOnTop(onTop)
                .setParent(this)
                .setSourceTask(sourceTask)
                .setActivityOptions(options)
                .setLaunchFlags(launchFlags)
                .build();
    }

    /**
     * Returns an existing root task compatible with the input params or creates one
     * if a compatible root task doesn't exist.
     *
     * @see #getOrCreateRootTask(int, int, boolean)
     */
    Task getOrCreateRootTask(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable Task candidateTask, @Nullable Task sourceTask,
            @Nullable LaunchParams launchParams, int launchFlags, int activityType, boolean onTop) {
        int windowingMode = WINDOWING_MODE_UNDEFINED;
        if (launchParams != null) {
            // If launchParams isn't null, windowing mode is already resolved.
            windowingMode = launchParams.mWindowingMode;
        } else if (options != null) {
            // If launchParams is null and options isn't let's use the windowing mode in the
            // options.
            windowingMode = options.getLaunchWindowingMode();
        }
        // Validate that our desired windowingMode will work under the current conditions.
        // UNDEFINED windowing mode is a valid result and means that the new root task will inherit
        // it's display's windowing mode.
        windowingMode = validateWindowingMode(windowingMode, r, candidateTask, activityType);
        return getOrCreateRootTask(windowingMode, activityType, onTop, candidateTask, sourceTask,
                options, launchFlags);
    }

    @VisibleForTesting
    int getNextRootTaskId() {
        return mAtmService.mTaskSupervisor.getNextTaskIdForUser();
    }

    Task createRootTask(int windowingMode, int activityType, boolean onTop) {
        return createRootTask(windowingMode, activityType, onTop, null /* activityOptions */);
    }

    /**
     * A convinenit method of creating a root task by providing windowing mode and activity type
     * on this display.
     *
     * @param windowingMode      The windowing mode the root task should be created in. If
     *                           {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the
     *                           root task will inherit its parent's windowing mode.
     * @param activityType       The activityType the root task should be created in. If
     *                           {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the
     *                           root task will be created in
     *                           {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop              If true the root task will be created at the top of the display,
     *                           else at the bottom.
     * @param opts               The activity options.
     * @return The newly created root task.
     */
    Task createRootTask(int windowingMode, int activityType, boolean onTop, ActivityOptions opts) {
        return new Task.Builder(mAtmService)
                .setWindowingMode(windowingMode)
                .setActivityType(activityType)
                .setParent(this)
                .setOnTop(onTop)
                .setActivityOptions(opts)
                .build();
    }

    // TODO: Also clear when task is removed from system?
    void setLaunchRootTask(Task rootTask, int[] windowingModes, int[] activityTypes) {
        if (!rootTask.mCreatedByOrganizer) {
            throw new IllegalArgumentException(
                    "Can't set not mCreatedByOrganizer as launch root tr=" + rootTask);
        }

        LaunchRootTaskDef def = getLaunchRootTaskDef(rootTask);
        if (def != null) {
            // Remove so we add to the end of the list.
            mLaunchRootTasks.remove(def);
        } else {
            def = new LaunchRootTaskDef();
            def.task = rootTask;
        }

        def.activityTypes = activityTypes;
        def.windowingModes = windowingModes;
        if (!ArrayUtils.isEmpty(windowingModes) || !ArrayUtils.isEmpty(activityTypes)) {
            mLaunchRootTasks.add(def);
        }
    }

    void removeLaunchRootTask(Task rootTask) {
        LaunchRootTaskDef def = getLaunchRootTaskDef(rootTask);
        if (def != null) {
            mLaunchRootTasks.remove(def);
        }
    }

    void setLaunchAdjacentFlagRootTask(@Nullable Task adjacentFlagRootTask) {
        if (adjacentFlagRootTask != null) {
            if (!adjacentFlagRootTask.mCreatedByOrganizer) {
                throw new IllegalArgumentException(
                        "Can't set not mCreatedByOrganizer as launch adjacent flag root tr="
                                + adjacentFlagRootTask);
            }

            if (adjacentFlagRootTask.getAdjacentTaskFragment() == null) {
                throw new UnsupportedOperationException(
                        "Can't set non-adjacent root as launch adjacent flag root tr="
                                + adjacentFlagRootTask);
            }
        }

        mLaunchAdjacentFlagRootTask = adjacentFlagRootTask;
    }

    private @Nullable LaunchRootTaskDef getLaunchRootTaskDef(Task rootTask) {
        LaunchRootTaskDef def = null;
        for (int i = mLaunchRootTasks.size() - 1; i >= 0; --i) {
            if (mLaunchRootTasks.get(i).task.mTaskId != rootTask.mTaskId) continue;
            def = mLaunchRootTasks.get(i);
            break;
        }
        return def;
    }

    @Nullable
    Task getLaunchRootTask(int windowingMode, int activityType, @Nullable ActivityOptions options,
            @Nullable Task sourceTask, int launchFlags) {
        // Try to use the launch root task in options if available.
        if (options != null) {
            final Task launchRootTask = Task.fromWindowContainerToken(options.getLaunchRootTask());
            // We only allow this for created by organizer tasks.
            if (launchRootTask != null && launchRootTask.mCreatedByOrganizer) {
                return launchRootTask;
            }
        }

        // Use launch-adjacent-flag-root if launching with launch-adjacent flag.
        if ((launchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0
                && mLaunchAdjacentFlagRootTask != null) {
            // If the adjacent launch is coming from the same root, launch to adjacent root instead.
            if (sourceTask != null
                    && sourceTask.getRootTask().mTaskId == mLaunchAdjacentFlagRootTask.mTaskId
                    && mLaunchAdjacentFlagRootTask.getAdjacentTaskFragment() != null) {
                return mLaunchAdjacentFlagRootTask.getAdjacentTaskFragment().asTask();
            } else {
                return mLaunchAdjacentFlagRootTask;
            }
        }

        for (int i = mLaunchRootTasks.size() - 1; i >= 0; --i) {
            if (mLaunchRootTasks.get(i).contains(windowingMode, activityType)) {
                final Task launchRootTask = mLaunchRootTasks.get(i).task;
                // Return the focusable root task for improving the UX with staged split screen.
                final TaskFragment adjacentTaskFragment = launchRootTask != null
                        ? launchRootTask.getAdjacentTaskFragment() : null;
                final Task adjacentRootTask =
                        adjacentTaskFragment != null ? adjacentTaskFragment.asTask() : null;
                if (adjacentRootTask != null && adjacentRootTask.isFocusedRootTaskOnDisplay()) {
                    return adjacentRootTask;
                } else {
                    return launchRootTask;
                }
            }
        }
        return null;
    }

    /**
     * Get the preferred focusable root task in priority. If the preferred root task does not exist,
     * find a focusable and visible root task from the top of root tasks in this display.
     */
    Task getFocusedRootTask() {
        if (mPreferredTopFocusableRootTask != null) {
            return mPreferredTopFocusableRootTask;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskDisplayArea() != null) {
                final Task rootTask = child.asTaskDisplayArea().getFocusedRootTask();
                if (rootTask != null) {
                    return rootTask;
                }
                continue;
            }

            final Task rootTask = mChildren.get(i).asTask();
            if (rootTask.isFocusableAndVisible()) {
                return rootTask;
            }
        }

        return null;
    }

    Task getNextFocusableRootTask(Task currentFocus, boolean ignoreCurrent) {
        final int currentWindowingMode = currentFocus != null
                ? currentFocus.getWindowingMode() : WINDOWING_MODE_UNDEFINED;

        Task candidate = null;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskDisplayArea() != null) {
                final Task rootTask = child.asTaskDisplayArea()
                        .getNextFocusableRootTask(currentFocus, ignoreCurrent);
                if (rootTask != null) {
                    return rootTask;
                }
                continue;
            }

            final Task rootTask = mChildren.get(i).asTask();
            if (ignoreCurrent && rootTask == currentFocus) {
                continue;
            }
            if (!rootTask.isFocusableAndVisible()) {
                continue;
            }

            if (currentWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && candidate == null && rootTask.inSplitScreenPrimaryWindowingMode()) {
                // If the currently focused root task is in split-screen secondary we save off the
                // top primary split-screen root task as a candidate for focus because we might
                // prefer focus to move to an other root task to avoid primary split-screen root
                // task overlapping with a fullscreen root task when a fullscreen root task is
                // higher in z than the next split-screen root task. Assistant root task, I am
                // looking at you...
                // We only move the focus to the primary-split screen root task if there isn't a
                // better alternative.
                candidate = rootTask;
                continue;
            }
            if (candidate != null && rootTask.inSplitScreenSecondaryWindowingMode()) {
                // Use the candidate root task since we are now at the secondary split-screen.
                return candidate;
            }
            return rootTask;
        }
        return candidate;
    }

    ActivityRecord getFocusedActivity() {
        final Task focusedRootTask = getFocusedRootTask();
        if (focusedRootTask == null) {
            return null;
        }
        // TODO(b/111541062): Move this into Task#getResumedActivity()
        // Check if the focused root task has the resumed activity
        ActivityRecord resumedActivity = focusedRootTask.getTopResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            // If there is no registered resumed activity in the root task or it is not running -
            // try to use previously resumed one.
            resumedActivity = focusedRootTask.getTopPausingActivity();
            if (resumedActivity == null || resumedActivity.app == null) {
                // If previously resumed activity doesn't work either - find the topmost running
                // activity that can be focused.
                resumedActivity = focusedRootTask.topRunningActivity(true /* focusableOnly */);
            }
        }
        return resumedActivity;
    }

    Task getLastFocusedRootTask() {
        return mLastFocusedRootTask;
    }

    void updateLastFocusedRootTask(Task prevFocusedTask, String updateLastFocusedTaskReason) {
        if (updateLastFocusedTaskReason == null) {
            return;
        }

        final Task currentFocusedTask = getFocusedRootTask();
        if (currentFocusedTask == prevFocusedTask) {
            return;
        }

        // Clear last paused activity if focused root task changed while sleeping, so that the
        // top activity of current focused task can be resumed.
        if (mDisplayContent.isSleeping()) {
            currentFocusedTask.clearLastPausedActivity();
        }

        mLastFocusedRootTask = prevFocusedTask;
        EventLogTags.writeWmFocusedRootTask(mRootWindowContainer.mCurrentUser,
                mDisplayContent.mDisplayId,
                currentFocusedTask == null ? -1 : currentFocusedTask.getRootTaskId(),
                mLastFocusedRootTask == null ? -1 : mLastFocusedRootTask.getRootTaskId(),
                updateLastFocusedTaskReason);
    }

    boolean allResumedActivitiesComplete() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskDisplayArea() != null) {
                if (!child.asTaskDisplayArea().allResumedActivitiesComplete()) {
                    return false;
                }
                continue;
            }

            final ActivityRecord r = mChildren.get(i).asTask().getTopResumedActivity();
            if (r != null && !r.isState(RESUMED)) {
                return false;
            }
        }
        final Task currentFocusedRootTask = getFocusedRootTask();
        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.d(TAG_ROOT_TASK, "allResumedActivitiesComplete: currentFocusedRootTask "
                    + "changing from=" + mLastFocusedRootTask + " to=" + currentFocusedRootTask);
        }
        mLastFocusedRootTask = currentFocusedRootTask;
        return true;
    }

    /**
     * Pause all activities in either all of the root tasks or just the back root tasks. This is
     * done before resuming a new activity and to make sure that previously active activities are
     * paused in root tasks that are no longer visible or in pinned windowing mode. This does not
     * pause activities in visible root tasks, so if an activity is launched within the same root
     * task, hen we should explicitly pause that root task's top activity.
     *
     * @param resuming    The resuming activity.
     * @return {@code true} if any activity was paused as a result of this call.
     */
    boolean pauseBackTasks(ActivityRecord resuming) {
        final int[] someActivityPaused = {0};
        forAllLeafTasks(leafTask -> {
            // Check if the direct child resumed activity in the leaf task needed to be paused if
            // the leaf task is not a leaf task fragment.
            if (!leafTask.isLeafTaskFragment()) {
                final ActivityRecord top = topRunningActivity();
                final ActivityRecord resumedActivity = leafTask.getResumedActivity();
                if (resumedActivity != null && top.getTaskFragment() != leafTask) {
                    // Pausing the resumed activity because it is occluded by other task fragment.
                    if (leafTask.startPausing(false /* uiSleeping*/, resuming, "pauseBackTasks")) {
                        someActivityPaused[0]++;
                    }
                }
            }

            leafTask.forAllLeafTaskFragments((taskFrag) -> {
                final ActivityRecord resumedActivity = taskFrag.getResumedActivity();
                if (resumedActivity != null
                        && (taskFrag.getVisibility(resuming) != TASK_FRAGMENT_VISIBILITY_VISIBLE
                        || !taskFrag.isTopActivityFocusable())) {
                    if (taskFrag.startPausing(false /* uiSleeping*/, resuming, "pauseBackTasks")) {
                        someActivityPaused[0]++;
                    }
                }
            }, true /* traverseTopToBottom */);
        }, true /* traverseTopToBottom */);
        return someActivityPaused[0] > 0;
    }

    void onSplitScreenModeDismissed() {
        // The focused task could be a non-resizeable fullscreen root task that is on top of the
        // other split-screen tasks, therefore had to dismiss split-screen, make sure the current
        // focused root task can still be on top after dismissal
        final Task rootTask = getFocusedRootTask();
        final Task toTop =
                rootTask != null && !rootTask.inSplitScreenWindowingMode() ? rootTask : null;
        onSplitScreenModeDismissed(toTop);
    }

    void onSplitScreenModeDismissed(Task toTop) {
        mAtmService.deferWindowLayout();
        try {
            moveSplitScreenTasksToFullScreen();
        } finally {
            final Task topFullscreenRootTask = toTop != null
                    ? toTop : getTopRootTaskInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            final Task rootHomeTask = getOrCreateRootHomeTask();
            if (rootHomeTask != null && ((topFullscreenRootTask != null && !isTopRootTask(
                    rootHomeTask)) || toTop != null)) {
                // Whenever split-screen is dismissed we want the root home task directly behind the
                // current top fullscreen root task so it shows up when the top root task is
                // finished. Or, if the caller specified a root task to be on top after
                // split-screen is dismissed.
                // TODO: Would be better to use ActivityDisplay.positionChildAt() for this, however
                // ActivityDisplay doesn't have a direct controller to WM side yet. We can switch
                // once we have that.
                rootHomeTask.moveToFront("onSplitScreenModeDismissed");
                topFullscreenRootTask.moveToFront("onSplitScreenModeDismissed");
            }
            mAtmService.continueWindowLayout();
        }
    }

    private void moveSplitScreenTasksToFullScreen() {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        mTmpTasks.clear();
        forAllTasks(task -> {
            if (task.mCreatedByOrganizer && task.inSplitScreenWindowingMode() && task.hasChild()) {
                mTmpTasks.add(task);
            }
        });

        for (int i = mTmpTasks.size() - 1; i >= 0; i--) {
            final Task root = mTmpTasks.get(i);
            for (int j = 0; j < root.getChildCount(); j++) {
                final WindowContainerToken token =
                        root.getChildAt(j).mRemoteToken.toWindowContainerToken();
                wct.reparent(token, null, true /* toTop */);
                wct.setBounds(token, null);
            }
        }
        mAtmService.mWindowOrganizerController.applyTransaction(wct);
    }

    /**
     * Returns true if the {@param windowingMode} is supported based on other parameters passed in.
     *
     * @param windowingMode       The windowing mode we are checking support for.
     * @param supportsMultiWindow If we should consider support for multi-window mode in general.
     * @param supportsSplitScreen If we should consider support for split-screen multi-window.
     * @param supportsFreeform    If we should consider support for freeform multi-window.
     * @param supportsPip         If we should consider support for picture-in-picture mutli-window.
     * @param activityType        The activity type under consideration.
     * @return true if the windowing mode is supported.
     */
    static boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow,
            boolean supportsSplitScreen, boolean supportsFreeform, boolean supportsPip,
            int activityType) {

        if (windowingMode == WINDOWING_MODE_UNDEFINED
                || windowingMode == WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        if (!supportsMultiWindow) {
            return false;
        }

        if (windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            return true;
        }

        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return supportsSplitScreen
                    && WindowConfiguration.supportSplitScreenWindowingMode(activityType);
        }

        if (!supportsFreeform && windowingMode == WINDOWING_MODE_FREEFORM) {
            return false;
        }

        if (!supportsPip && windowingMode == WINDOWING_MODE_PINNED) {
            return false;
        }
        return true;
    }

    /**
     * Resolves the windowing mode that an {@link ActivityRecord} would be in if started on this
     * display with the provided parameters.
     *
     * @param r            The ActivityRecord in question.
     * @param options      Options to start with.
     * @param task         The task within-which the activity would start.
     * @param activityType The type of activity to start.
     * @return The resolved (not UNDEFINED) windowing-mode that the activity would be in.
     */
    int resolveWindowingMode(@Nullable ActivityRecord r, @Nullable ActivityOptions options,
            @Nullable Task task, int activityType) {

        // First preference if the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;

        // If windowing mode is unset, then next preference is the candidate task, then the
        // activity record.
        if (windowingMode == WINDOWING_MODE_UNDEFINED) {
            if (task != null) {
                windowingMode = task.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED && r != null) {
                windowingMode = r.getWindowingMode();
            }
            if (windowingMode == WINDOWING_MODE_UNDEFINED) {
                // Use the display's windowing mode.
                windowingMode = getWindowingMode();
            }
        }
        windowingMode = validateWindowingMode(windowingMode, r, task, activityType);
        return windowingMode != WINDOWING_MODE_UNDEFINED
                ? windowingMode : WINDOWING_MODE_FULLSCREEN;
    }

    /**
     * Check if the requested windowing-mode is appropriate for the specified task and/or activity
     * on this display.
     *
     * @param windowingMode The windowing-mode to validate.
     * @param r             The {@link ActivityRecord} to check against.
     * @param task          The {@link Task} to check against.
     * @param activityType  An activity type.
     * @return {@code true} if windowingMode is valid, {@code false} otherwise.
     */
    boolean isValidWindowingMode(int windowingMode, @Nullable ActivityRecord r, @Nullable Task task,
            int activityType) {
        // Make sure the windowing mode we are trying to use makes sense for what is supported.
        boolean supportsMultiWindow = mAtmService.mSupportsMultiWindow;
        boolean supportsSplitScreen = mAtmService.mSupportsSplitScreenMultiWindow;
        boolean supportsFreeform = mAtmService.mSupportsFreeformWindowManagement;
        boolean supportsPip = mAtmService.mSupportsPictureInPicture;
        if (supportsMultiWindow) {
            if (task != null) {
                supportsSplitScreen = task.supportsSplitScreenWindowingModeInDisplayArea(this);
                supportsFreeform = task.supportsFreeformInDisplayArea(this);
                supportsMultiWindow = task.supportsMultiWindowInDisplayArea(this)
                        // When the activity needs to be moved to PIP while the Task is not in PIP,
                        // it can be moved to a new created PIP Task, so WINDOWING_MODE_PINNED is
                        // always valid for Task as long as the device supports it.
                        || (windowingMode == WINDOWING_MODE_PINNED && supportsPip);
            } else if (r != null) {
                supportsSplitScreen = r.supportsSplitScreenWindowingModeInDisplayArea(this);
                supportsFreeform = r.supportsFreeformInDisplayArea(this);
                supportsPip = r.supportsPictureInPicture();
                supportsMultiWindow = r.supportsMultiWindowInDisplayArea(this);
            }
        }

        return windowingMode != WINDOWING_MODE_UNDEFINED
                && isWindowingModeSupported(windowingMode, supportsMultiWindow, supportsSplitScreen,
                supportsFreeform, supportsPip, activityType);
    }

    /**
     * Check that the requested windowing-mode is appropriate for the specified task and/or activity
     * on this display.
     *
     * @param windowingMode The windowing-mode to validate.
     * @param r             The {@link ActivityRecord} to check against.
     * @param task          The {@link Task} to check against.
     * @param activityType  An activity type.
     * @return The provided windowingMode or the closest valid mode which is appropriate.
     */
    int validateWindowingMode(int windowingMode, @Nullable ActivityRecord r, @Nullable Task task,
            int activityType) {
        final boolean inSplitScreenMode = isSplitScreenModeActivated();
        if (!inSplitScreenMode && windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            // Switch to the display's windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        } else if (inSplitScreenMode && windowingMode == WINDOWING_MODE_UNDEFINED) {
            windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }
        if (!isValidWindowingMode(windowingMode, r, task, activityType)) {
            return WINDOWING_MODE_UNDEFINED;
        }
        return windowingMode;
    }

    /**
     * Whether we can show non-resizable activities in multi window below this
     * {@link TaskDisplayArea}
     */
    boolean supportsNonResizableMultiWindow() {
        final int configSupportsNonResizableMultiWindow =
                mAtmService.mSupportsNonResizableMultiWindow;
        if (mAtmService.mDevEnableNonResizableMultiWindow
                || configSupportsNonResizableMultiWindow == 1) {
            // Device override to support.
            return true;
        }
        if (configSupportsNonResizableMultiWindow == -1) {
            // Device override to not support.
            return false;
        }
        // Support on large screen.
        return isLargeEnoughForMultiWindow();
    }

    /**
     * Whether we can show activity requesting the given min width/height in multi window below
     * this {@link TaskDisplayArea}.
     */
    boolean supportsActivityMinWidthHeightMultiWindow(int minWidth, int minHeight) {
        final int configRespectsActivityMinWidthHeightMultiWindow =
                mAtmService.mRespectsActivityMinWidthHeightMultiWindow;
        if (minWidth <= 0 && minHeight <= 0) {
            // No request min width/height.
            return true;
        }
        if (configRespectsActivityMinWidthHeightMultiWindow == -1) {
            // Device override to ignore min width/height.
            return true;
        }
        if (configRespectsActivityMinWidthHeightMultiWindow == 0
                && isLargeEnoughForMultiWindow()) {
            // Ignore min width/height on large screen.
            return true;
        }
        // Check if the request min width/height is supported in multi window.
        final Configuration config = getConfiguration();
        final int orientation = config.orientation;
        if (orientation == ORIENTATION_LANDSCAPE) {
            final int maxSupportMinWidth = (int) (mAtmService.mMinPercentageMultiWindowSupportWidth
                    * config.screenWidthDp * mDisplayContent.getDisplayMetrics().density);
            return minWidth <= maxSupportMinWidth;
        } else {
            final int maxSupportMinHeight =
                    (int) (mAtmService.mMinPercentageMultiWindowSupportHeight
                            * config.screenHeightDp * mDisplayContent.getDisplayMetrics().density);
            return minHeight <= maxSupportMinHeight;
        }
    }

    /**
     * Whether this is large enough to support non-resizable, and activities with min width/height
     * in multi window.
     */
    private boolean isLargeEnoughForMultiWindow() {
        return getConfiguration().smallestScreenWidthDp
                >= mAtmService.mLargeScreenSmallestScreenWidthDp;
    }

    boolean isTopRootTask(Task rootTask) {
        return rootTask == getTopRootTask();
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* considerKeyguardState */);
    }

    /**
     * Returns the top running activity in the focused root task. In the case the focused root
     * task has no such activity, the next focusable root task on this display is returned.
     *
     * @param considerKeyguardState Indicates whether the locked state should be considered. if
     *                              {@code true} and the keyguard is locked, only activities that
     *                              can be shown on top of the keyguard will be considered.
     * @return The top running activity. {@code null} if none is available.
     */
    ActivityRecord topRunningActivity(boolean considerKeyguardState) {
        ActivityRecord topRunning = null;
        final Task focusedRootTask = getFocusedRootTask();
        if (focusedRootTask != null) {
            topRunning = focusedRootTask.topRunningActivity();
        }

        // Look in other focusable root tasks.
        if (topRunning == null) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowContainer child = mChildren.get(i);
                if (child.asTaskDisplayArea() != null) {
                    topRunning =
                            child.asTaskDisplayArea().topRunningActivity(considerKeyguardState);
                    if (topRunning != null) {
                        break;
                    }
                    continue;
                }
                final Task rootTask = mChildren.get(i).asTask();
                // Only consider focusable root tasks other than the current focused one.
                if (rootTask == focusedRootTask || !rootTask.isTopActivityFocusable()) {
                    continue;
                }
                topRunning = rootTask.topRunningActivity();
                if (topRunning != null) {
                    break;
                }
            }
        }

        // This activity can be considered the top running activity if we are not considering
        // the locked state, the keyguard isn't locked, or we can show when locked.
        if (topRunning != null && considerKeyguardState
                && mRootWindowContainer.mTaskSupervisor.getKeyguardController()
                .isKeyguardLocked()
                && !topRunning.canShowWhenLocked()) {
            return null;
        }

        return topRunning;
    }

    protected int getRootTaskCount() {
        final int[] count = new int[1];
        forAllRootTasks(task -> {
            count[0]++;
        });
        return count[0];
    }

    @Nullable
    Task getOrCreateRootHomeTask() {
        return getOrCreateRootHomeTask(false /* onTop */);
    }

    /**
     * Returns the existing root home task or creates and returns a new one if it should exist
     * for the display.
     *
     * @param onTop Only be used when there is no existing root home task. If true the root home
     *              task will be created at the top of the display, else at the bottom.
     */
    @Nullable
    Task getOrCreateRootHomeTask(boolean onTop) {
        Task homeTask = getRootHomeTask();
        // Take into account if this TaskDisplayArea can have a home task before trying to
        // create the root task
        if (homeTask == null && canHostHomeTask()) {
            homeTask = createRootTask(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, onTop);
        }
        return homeTask;
    }

    boolean isSplitScreenModeActivated() {
        Task task = getRootSplitScreenPrimaryTask();
        return task != null && task.hasChild();
    }

    /**
     * Returns the topmost root task on the display that is compatible with the input windowing
     * mode. Null is no compatible root task on the display.
     */
    Task getTopRootTaskInWindowingMode(int windowingMode) {
        return getRootTask(windowingMode, ACTIVITY_TYPE_UNDEFINED);
    }

    void moveHomeRootTaskToFront(String reason) {
        final Task homeRootTask = getOrCreateRootHomeTask();
        if (homeRootTask != null) {
            homeRootTask.moveToFront(reason);
        }
    }

    /**
     * Moves the focusable home activity to top. If there is no such activity, the root home task
     * will still move to top.
     */
    void moveHomeActivityToTop(String reason) {
        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            moveHomeRootTaskToFront(reason);
            return;
        }
        top.moveFocusableActivityToTop(reason);
    }

    @Nullable
    ActivityRecord getHomeActivity() {
        return getHomeActivityForUser(mRootWindowContainer.mCurrentUser);
    }

    @Nullable
    ActivityRecord getHomeActivityForUser(int userId) {
        final Task rootHomeTask = getRootHomeTask();
        if (rootHomeTask == null) {
            return null;
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(
                TaskDisplayArea::isHomeActivityForUser, PooledLambda.__(ActivityRecord.class),
                userId);
        final ActivityRecord r = rootHomeTask.getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isHomeActivityForUser(ActivityRecord r, int userId) {
        return r.isActivityTypeHome() && (userId == UserHandle.USER_ALL || r.mUserId == userId);
    }

    /**
     * Adjusts the {@param rootTask} behind the last visible rootTask in the display if necessary.
     * Generally used in conjunction with {@link #moveRootTaskBehindRootTask}.
     */
    // TODO(b/151575894): Remove special root task movement methods.
    void moveRootTaskBehindBottomMostVisibleRootTask(Task rootTask) {
        if (rootTask.shouldBeVisible(null)) {
            // Skip if the root task is already visible
            return;
        }

        // Move the root task to the bottom to not affect the following visibility checks
        rootTask.getParent().positionChildAt(POSITION_BOTTOM, rootTask,
                false /* includingParents */);

        // Find the next position where the root task should be placed
        final boolean isRootTask = rootTask.isRootTask();
        final int numRootTasks =
                isRootTask ? mChildren.size() : rootTask.getParent().getChildCount();
        for (int rootTaskNdx = 0; rootTaskNdx < numRootTasks; rootTaskNdx++) {
            Task s;
            if (isRootTask) {
                final WindowContainer child = mChildren.get(rootTaskNdx);
                if (child.asTaskDisplayArea() != null) {
                    s = child.asTaskDisplayArea().getBottomMostVisibleRootTask(rootTask);
                } else {
                    s = child.asTask();
                }
            } else {
                s = rootTask.getParent().getChildAt(rootTaskNdx).asTask();
            }
            if (s == rootTask || s == null) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN
                    || winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided root task to behind this root task
                final int position = Math.max(0, rootTaskNdx - 1);
                rootTask.getParent().positionChildAt(position, rootTask,
                        false /*includingParents */);
                break;
            }
        }
    }

    @Nullable
    private Task getBottomMostVisibleRootTask(Task excludeRootTask) {
        return getRootTask(task -> {
            final int winMode = task.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN
                    || winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            return task.shouldBeVisible(null) && isValidWindowingMode;
        }, false /* traverseTopToBottom */);
    }

    /**
     * Moves the {@param rootTask} behind the given {@param behindRootTask} if possible. If
     * {@param behindRootTask} is not currently in the display, then then the root task is moved
     * to the back. Generally used in conjunction with
     * {@link #moveRootTaskBehindBottomMostVisibleRootTask}.
     */
    void moveRootTaskBehindRootTask(Task rootTask, Task behindRootTask) {
        if (behindRootTask == null || behindRootTask == rootTask) {
            return;
        }

        final WindowContainer parent = rootTask.getParent();
        if (parent == null || parent != behindRootTask.getParent()) {
            return;
        }

        // Note that positionChildAt will first remove the given root task before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int rootTaskIndex = parent.mChildren.indexOf(rootTask);
        final int behindRootTaskIndex = parent.mChildren.indexOf(behindRootTask);
        final int insertIndex = rootTaskIndex <= behindRootTaskIndex
                ? behindRootTaskIndex - 1 : behindRootTaskIndex;
        final int position = Math.max(0, insertIndex);
        parent.positionChildAt(position, rootTask, false /* includingParents */);
    }

    boolean hasPinnedTask() {
        return getRootPinnedTask() != null;
    }

    /**
     * @return the root task currently above the {@param rootTask}. Can be null if the
     * {@param rootTask} is already top-most.
     */
    static Task getRootTaskAbove(Task rootTask) {
        final WindowContainer wc = rootTask.getParent();
        final int index = wc.mChildren.indexOf(rootTask) + 1;
        return (index < wc.mChildren.size()) ? (Task) wc.mChildren.get(index) : null;
    }

    /** Returns true if the root task in the windowing mode is visible. */
    boolean isRootTaskVisible(int windowingMode) {
        final Task rootTask = getTopRootTaskInWindowingMode(windowingMode);
        return rootTask != null && rootTask.isVisible();
    }

    void removeRootTask(Task rootTask) {
        removeChild(rootTask);
    }

    int getDisplayId() {
        return mDisplayContent.getDisplayId();
    }

    boolean isRemoved() {
        return mRemoved;
    }

    /**
     * Adds a listener to be notified whenever the root task order in the display changes. Currently
     * only used by the {@link RecentsAnimation} to determine whether to interrupt and cancel the
     * current animation when the system state changes.
     */
    void registerRootTaskOrderChangedListener(OnRootTaskOrderChangedListener listener) {
        if (!mRootTaskOrderChangedCallbacks.contains(listener)) {
            mRootTaskOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered root task order change listener.
     */
    void unregisterRootTaskOrderChangedListener(OnRootTaskOrderChangedListener listener) {
        mRootTaskOrderChangedCallbacks.remove(listener);
    }

    /**
     * Notifies of a root task order change
     *
     * @param rootTask The root task which triggered the order change
     */
    void onRootTaskOrderChanged(Task rootTask) {
        for (int i = mRootTaskOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            mRootTaskOrderChangedCallbacks.get(i).onRootTaskOrderChanged(rootTask);
        }
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    /**
     * Exposes the home task capability of the TaskDisplayArea
     */
    boolean canHostHomeTask() {
        return mDisplayContent.supportsSystemDecorations() && mCanHostHomeTask;
    }

    /**
     * Callback for when the order of the root tasks in the display changes.
     */
    interface OnRootTaskOrderChangedListener {
        void onRootTaskOrderChanged(Task rootTask);
    }

    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        mAtmService.mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            forAllRootTasks(rootTask -> {
                rootTask.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients);
            });
        } finally {
            mAtmService.mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    /**
     * Removes the root tasks in the node applying the content removal node from the display.
     *
     * @return last reparented root task, or {@code null} if the root tasks had to be destroyed.
     */
    Task remove() {
        mPreferredTopFocusableRootTask = null;
        // TODO(b/153090332): Allow setting content removal mode per task display area
        final boolean destroyContentOnRemoval = mDisplayContent.shouldDestroyContentOnRemove();
        final TaskDisplayArea toDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task lastReparentedRootTask = null;

        // Root tasks could be reparented from the removed display area to other display area. After
        // reparenting the last root task of the removed display area, the display area becomes
        // ready to be released (no more root tasks). But, we cannot release it at that moment
        // or the related WindowContainer will also be removed. So, we set display area as removed
        // after reparenting root task finished.
        // Keep the order from bottom to top.
        int numRootTasks = mChildren.size();

        for (int i = 0; i < numRootTasks; i++) {
            final WindowContainer child = mChildren.get(i);
            if (child.asTaskDisplayArea() != null) {
                lastReparentedRootTask = child.asTaskDisplayArea().remove();
                continue;
            }
            final Task task = mChildren.get(i).asTask();
            // Always finish non-standard type root tasks and root tasks created by a organizer.
            // TODO: For root tasks created by organizer, consider reparenting children tasks if
            //       the use case arises in the future.
            if (destroyContentOnRemoval
                    || !task.isActivityTypeStandardOrUndefined()
                    || task.mCreatedByOrganizer) {
                task.remove(false /* withTransition */, "removeTaskDisplayArea");
            } else {
                // Reparent task to corresponding launch root or display area.
                final WindowContainer launchRoot =
                        task.supportsSplitScreenWindowingModeInDisplayArea(toDisplayArea)
                                ? toDisplayArea.getLaunchRootTask(
                                        task.getWindowingMode(),
                                        task.getActivityType(),
                                        null /* options */,
                                        null /* sourceTask */,
                                        0 /* launchFlags */)
                                : null;
                task.reparent(launchRoot == null ? toDisplayArea : launchRoot, POSITION_TOP);

                // Set the windowing mode to undefined by default to let the root task inherited the
                // windowing mode.
                task.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                lastReparentedRootTask = task;
            }
            // Root task may be removed from this display. Ensure each root task will be processed
            // and the loop will end.
            i -= numRootTasks - mChildren.size();
            numRootTasks = mChildren.size();
        }

        if (lastReparentedRootTask != null) {
            if (toDisplayArea.isSplitScreenModeActivated()
                    && !lastReparentedRootTask.supportsSplitScreenWindowingModeInDisplayArea(
                            toDisplayArea)) {
                // Dismiss split screen if the last reparented root task doesn't support split mode.
                mAtmService.getTaskChangeNotificationController()
                        .notifyActivityDismissingDockedRootTask();
                toDisplayArea.onSplitScreenModeDismissed(lastReparentedRootTask);
            } else if (!lastReparentedRootTask.isRootTask()) {
                // Update focus when the last reparented root task is not a root task anymore.
                // (For example, if it has been reparented to a split screen root task, move the
                // focus to the split root task)
                lastReparentedRootTask.getRootTask().moveToFront("display-removed");
            }
        }

        mRemoved = true;

        return lastReparentedRootTask;
    }

    /** Whether this task display area can request orientation. */
    boolean canSpecifyOrientation() {
        // Only allow to specify orientation if this TDA is not set to ignore orientation request,
        // and it is the last focused one on this logical display that can request orientation
        // request.
        return !mIgnoreOrientationRequest
                && mDisplayContent.getOrientationRequestingTaskDisplayArea() == this;
    }

    void clearPreferredTopFocusableRootTask() {
        mPreferredTopFocusableRootTask = null;
    }

    @Override
    TaskDisplayArea getTaskDisplayArea() {
        return this;
    }

    @Override
    boolean isTaskDisplayArea() {
        return true;
    }

    @Override
    TaskDisplayArea asTaskDisplayArea() {
        return this;
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "TaskDisplayArea " + getName());
        final String doublePrefix = prefix + "  ";
        super.dump(pw, doublePrefix, dumpAll);
        if (mPreferredTopFocusableRootTask != null) {
            pw.println(doublePrefix + "mPreferredTopFocusableRootTask="
                    + mPreferredTopFocusableRootTask);
        }
        if (mLastFocusedRootTask != null) {
            pw.println(doublePrefix + "mLastFocusedRootTask=" + mLastFocusedRootTask);
        }

        final String triplePrefix = doublePrefix + "  ";

        if (mLaunchRootTasks.size() > 0) {
            pw.println(doublePrefix + "mLaunchRootTasks:");
            for (int i = mLaunchRootTasks.size() - 1; i >= 0; --i) {
                final LaunchRootTaskDef def = mLaunchRootTasks.get(i);
                pw.println(triplePrefix
                        + Arrays.toString(def.activityTypes) + " "
                        + Arrays.toString(def.windowingModes) + " "
                        + " task=" + def.task);
            }
        }

        pw.println(doublePrefix + "Application tokens in top down Z order:");
        for (int index = getChildCount() - 1; index >= 0; --index) {
            final WindowContainer child = getChildAt(index);
            if (child.asTaskDisplayArea() != null) {
                child.dump(pw, doublePrefix, dumpAll);
                continue;
            }
            final Task rootTask = child.asTask();
            pw.println(doublePrefix + "* " + rootTask);
            rootTask.dump(pw, triplePrefix, dumpAll);
        }
    }
}
