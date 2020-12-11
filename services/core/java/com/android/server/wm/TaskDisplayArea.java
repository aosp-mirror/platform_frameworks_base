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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.isSplitScreenWindowingMode;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_STATES;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerService.TAG_ROOT_TASK;
import static com.android.server.wm.DisplayContent.alwaysCreateRootTask;
import static com.android.server.wm.Task.ActivityState.RESUMED;
import static com.android.server.wm.Task.TASK_VISIBILITY_VISIBLE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_ROOT_TASK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@link DisplayArea} that represents a section of a screen that contains app window containers.
 */
final class TaskDisplayArea extends DisplayArea<Task> {

    DisplayContent mDisplayContent;

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
     * both docked stacks but underneath any assistant stacks.
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

    private final ArrayList<Task> mTmpAlwaysOnTopRootTasks = new ArrayList<>();
    private final ArrayList<Task> mTmpNormalRootTasks = new ArrayList<>();
    private final ArrayList<Task> mTmpHomeRootTasks = new ArrayList<>();
    private final IntArray mTmpNeedsZBoostIndexes = new IntArray();
    private int mTmpLayerForSplitScreenDividerAnchor;
    private int mTmpLayerForAnimationLayer;

    private ArrayList<Task> mTmpTasks = new ArrayList<>();

    private ActivityTaskManagerService mAtmService;

    private RootWindowContainer mRootWindowContainer;

    // When non-null, new tasks get put into this root task.
    Task mLaunchRootTask = null;

    /**
     * A focusable stack that is purposely to be positioned at the top. Although the stack may not
     * have the topmost index, it is used as a preferred candidate to prevent being unable to resume
     * target stack properly when there are other focusable always-on-top stacks.
     */
    Task mPreferredTopFocusableRootTask;

    private final RootWindowContainer.FindTaskResult
            mTmpFindTaskResult = new RootWindowContainer.FindTaskResult();

    /**
     * If this is the same as {@link #getFocusedRootTask} then the activity on the top of the
     * focused root task has been resumed. If root tasks are changing position this will hold the
     * old root task until the new root task becomes resumed after which it will be set to
     * current focused root task.
     */
    Task mLastFocusedRootTask;
    /**
     * All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes. Any calls
     * changing the list should also call {@link #onRootTaskOrderChanged(Task)}.
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

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
            int displayAreaFeature) {
        this(displayContent, service, name, displayAreaFeature, false /* createdByOrganizer */);
    }

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
            int displayAreaFeature, boolean createdByOrganizer) {
        super(service, Type.ANY, name, displayAreaFeature);
        mDisplayContent = displayContent;
        mRootWindowContainer = service.mRoot;
        mAtmService = service.mAtmService;
        mCreatedByOrganizer = createdByOrganizer;
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode
     * and activity type. Null is no compatible stack on the display.
     */
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
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final Task stack = getChildAt(i);
            if (activityType == ACTIVITY_TYPE_UNDEFINED
                    && windowingMode == stack.getWindowingMode()) {
                // Passing in undefined type means we want to match the topmost stack with the
                // windowing mode.
                return stack;
            }
            if (stack.isCompatible(windowingMode, activityType)) {
                return stack;
            }
        }
        return null;
    }

    @VisibleForTesting
    Task getTopRootTask() {
        final int count = getChildCount();
        return count > 0 ? getChildAt(count - 1) : null;
    }

    // TODO: Figure-out a way to remove since it might be a source of confusion.
    int getIndexOf(Task task) {
        return mChildren.indexOf(task);
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
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).inSplitScreenSecondaryWindowingMode()) {
                return mChildren.get(i);
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
            // Looks like this stack changed windowing mode to pinned. Move it to the top.
            positionChildAt(POSITION_TOP, rootTask, false /* includingParents */);
        }
    }

    void addRootTaskReferenceIfNeeded(Task rootTask) {
        if (rootTask.isActivityTypeHome()) {
            if (mRootHomeTask != null) {
                if (!rootTask.isDescendantOf(mRootHomeTask)) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack="
                            + mRootHomeTask + " already exist on display=" + this
                            + " stack=" + rootTask);
                }
            } else {
                mRootHomeTask = rootTask;
            }
        } else if (rootTask.isActivityTypeRecents()) {
            if (mRootRecentsTask != null) {
                if (!rootTask.isDescendantOf(mRootRecentsTask)) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: recents stack="
                            + mRootRecentsTask + " already exist on display=" + this
                            + " stack=" + rootTask);
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
                        "addStackReferenceIfNeeded: pinned stack=" + mRootPinnedTask
                                + " already exist on display=" + this + " stack=" + rootTask);
            }
            mRootPinnedTask = rootTask;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            if (mRootSplitScreenPrimaryTask != null) {
                throw new IllegalArgumentException(
                        "addStackReferenceIfNeeded: split screen primary stack="
                                + mRootSplitScreenPrimaryTask
                                + " already exist on display=" + this + " stack=" + rootTask);
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
    void addChild(Task task, int position) {
        if (DEBUG_ROOT_TASK) Slog.d(TAG_WM, "Set task=" + task + " on taskDisplayArea=" + this);

        addRootTaskReferenceIfNeeded(task);
        position = findPositionForRootTask(position, task, true /* adding */);

        super.addChild(task, position);
        mAtmService.updateSleepIfNeededLocked();
        onRootTaskOrderChanged(task);
    }

    @Override
    protected void removeChild(Task stack) {
        super.removeChild(stack);
        onRootTaskRemoved(stack);
        mAtmService.updateSleepIfNeededLocked();
        removeRootTaskReferenceIfNeeded(stack);
    }

    @Override
    boolean isOnTop() {
        // Considered always on top
        return true;
    }

    @Override
    void positionChildAt(int position, Task child, boolean includingParents) {
        final boolean moveToTop = position >= getChildCount() - 1;
        final boolean moveToBottom = position <= 0;

        final int oldPosition = mChildren.indexOf(child);
        if (child.getWindowConfiguration().isAlwaysOnTop() && !moveToTop) {
            // This stack is always-on-top, override the default behavior.
            Slog.w(TAG_WM, "Ignoring move of always-on-top stack=" + this + " to bottom");

            // Moving to its current position, as we must call super but we don't want to
            // perform any meaningful action.
            super.positionChildAt(oldPosition, child, false /* includingParents */);
            return;
        }
        // We don't allow untrusted display to top when task stack moves to top,
        // until user tapping this display to change display position as top intentionally.
        if (!mDisplayContent.isTrusted() && !getParent().isOnTop()) {
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

        // The insert position may be adjusted to non-top when there is always-on-top stack. Since
        // the original position is preferred to be top, the stack should have higher priority when
        // we are looking for top focusable stack. The condition {@code wasContained} restricts the
        // preferred stack is set only when moving an existing stack to top instead of adding a new
        // stack that may be too early (e.g. in the middle of launching or reparenting).
        if (moveToTop && child.isFocusableAndVisible()) {
            mPreferredTopFocusableRootTask = child;
        } else if (mPreferredTopFocusableRootTask == child) {
            mPreferredTopFocusableRootTask = null;
        }

        // Update the top resumed activity because the preferred top focusable task may be changed.
        mAtmService.mTaskSupervisor.updateTopResumedActivityIfNeeded();

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
        // Notifying only when a leak task moved to front. Or the listeners would be notified
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
        return callback.apply(this);
    }

    @Override
    void forAllTaskDisplayAreas(Consumer<TaskDisplayArea> callback, boolean traverseTopToBottom) {
        callback.accept(this);
    }

    @Nullable
    @Override
    <R> R reduceOnAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R> accumulator,
            @Nullable R initValue, boolean traverseTopToBottom) {
        return accumulator.apply(this, initValue);
    }

    @Nullable
    @Override
    <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback,
            boolean traverseTopToBottom) {
        return callback.apply(this);
    }

    /**
     * Assigns a priority number to stack types. This priority defines an order between the types
     * of stacks that are added to the task display area.
     *
     * Higher priority number indicates that the stack should have a higher z-order.
     *
     * @return the priority of the stack
     */
    private int getPriority(Task stack) {
        if (mWmService.mAssistantOnTopOfDream && stack.isActivityTypeAssistant()) return 4;
        if (stack.isActivityTypeDream()) return 3;
        if (stack.inPinnedWindowingMode()) return 2;
        if (stack.isAlwaysOnTop()) return 1;
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
            // Since a stack could be repositioned while still being one of the children, we check
            // if this always-on-top stack already exists and if so, set the minPosition to its
            // previous position.
            final int currentIndex = getIndexOf(rootTask);
            if (currentIndex > minPosition) {
                minPosition = currentIndex;
            }
        }
        return minPosition;
    }

    private int findMaxPositionForRootTask(Task rootTask) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task curr = mChildren.get(i);
            // Since a stack could be repositioned while still being one of the children, we check
            // if 'curr' is the same stack and skip it if so
            final boolean sameRootTask = curr == rootTask;
            if (getPriority(curr) <= getPriority(rootTask) && !sameRootTask) {
                return i;
            }
        }
        return 0;
    }

    /**
     * When stack is added or repositioned, find a proper position for it.
     *
     * The order is defined as:
     * - Dream is on top of everything
     * - PiP is directly below the Dream
     * - always-on-top stacks are directly below PiP; new always-on-top stacks are added above
     * existing ones
     * - other non-always-on-top stacks come directly below always-on-top stacks; new
     * non-always-on-top stacks are added directly below always-on-top stacks and above existing
     * non-always-on-top stacks
     * - if {@link #mAssistantOnTopOfDream} is enabled, then Assistant is on top of everything
     * (including the Dream); otherwise, it is a normal non-always-on-top stack
     *
     * @param requestedPosition Position requested by caller.
     * @param rootTask          Root task to be added or positioned.
     * @param adding            Flag indicates whether we're adding a new stack or positioning an
     *                          existing.
     * @return The proper position for the stack.
     */
    private int findPositionForRootTask(int requestedPosition, Task rootTask, boolean adding) {
        // The max possible position we can insert the stack at.
        int maxPosition = findMaxPositionForRootTask(rootTask);
        // The min possible position we can insert the stack at.
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
        // 2) When we are moving a stack to another position, we also need to adjust the
        //    position depending on whether the stack is moving to a higher or lower position.
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
        // For legacy reasons we process the TaskStack.mExitingActivities first here before the
        // app tokens.
        // TODO: Investigate if we need to continue to do this or if we can just process them
        // in-order.
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final List<ActivityRecord> activities = mChildren.get(i).mExitingActivities;
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
                final List<ActivityRecord> activities = mChildren.get(i).mExitingActivities;
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
        if (!canSpecifyOrientation()) {
            return SCREEN_ORIENTATION_UNSET;
        }

        if (isRootTaskVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
            // Apps and their containers are not allowed to specify an orientation while using
            // root tasks...except for the home stack if it is not resizable and currently
            // visible (top of) its root task.
            if (mRootHomeTask != null && !mRootHomeTask.isResizeable()) {
                // Manually nest one-level because because getOrientation() checks fillsParent()
                // which checks that requestedOverrideBounds() is empty. However, in this case,
                // it is not empty because it's been overridden to maintain the fullscreen size
                // within a smaller split-root.
                final Task topHomeTask = mRootHomeTask.getTopMostTask();
                final ActivityRecord topHomeActivity = topHomeTask.getTopNonFinishingActivity();
                // If a home activity is in the process of launching and isn't yet visible we
                // should still respect the stack's preferred orientation to ensure rotation occurs
                // before the home activity finishes launching.
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
            // Apps and their containers are not allowed to specify an orientation of full screen
            // tasks created by organizer. The organizer handles the orientation instead.
            final Task task = getTopRootTaskInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            if (task != null && task.isVisible() && task.mCreatedByOrganizer) {
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
            final Task s = mChildren.get(i);
            s.assignChildLayers(t);
        }
    }

    void assignRootTaskOrdering(SurfaceControl.Transaction t) {
        if (getParent() == null) {
            return;
        }
        mTmpAlwaysOnTopRootTasks.clear();
        mTmpHomeRootTasks.clear();
        mTmpNormalRootTasks.clear();
        for (int i = 0; i < mChildren.size(); ++i) {
            final Task s = mChildren.get(i);
            if (s.isAlwaysOnTop()) {
                mTmpAlwaysOnTopRootTasks.add(s);
            } else if (s.isActivityTypeHome()) {
                mTmpHomeRootTasks.add(s);
            } else {
                mTmpNormalRootTasks.add(s);
            }
        }

        int layer = 0;
        // Place home stacks to the bottom.
        layer = adjustRootTaskLayer(t, mTmpHomeRootTasks, layer, false /* normalStacks */);
        // The home animation layer is between the home stacks and the normal stacks.
        final int layerForHomeAnimationLayer = layer++;
        mTmpLayerForSplitScreenDividerAnchor = layer++;
        mTmpLayerForAnimationLayer = layer++;
        layer = adjustRootTaskLayer(t, mTmpNormalRootTasks, layer, true /* normalStacks */);

        // The boosted animation layer is between the normal stacks and the always on top
        // stacks.
        final int layerForBoostedAnimationLayer = layer++;
        adjustRootTaskLayer(t, mTmpAlwaysOnTopRootTasks, layer, false /* normalStacks */);

        t.setLayer(mHomeAppAnimationLayer, layerForHomeAnimationLayer);
        t.setLayer(mAppAnimationLayer, mTmpLayerForAnimationLayer);
        t.setLayer(mSplitScreenDividerAnchor, mTmpLayerForSplitScreenDividerAnchor);
        t.setLayer(mBoostedAppAnimationLayer, layerForBoostedAnimationLayer);
    }

    private int adjustNormalRootTaskLayer(Task s, int layer) {
        if (s.inSplitScreenWindowingMode()) {
            // The split screen divider anchor is located above the split screen window.
            mTmpLayerForSplitScreenDividerAnchor = layer++;
        }
        if (s.isAnimatingByRecents() || s.isAppTransitioning()) {
            // The animation layer is located above the highest animating stack and no
            // higher.
            mTmpLayerForAnimationLayer = layer++;
        }
        return layer;
    }

    /**
     * Adjusts the layer of the stack which belongs to the same group.
     * Note that there are three stack groups: home stacks, always on top stacks, and normal stacks.
     *
     * @param startLayer   The beginning layer of this group of stacks.
     * @param normalStacks Set {@code true} if this group is neither home nor always on top.
     * @return The adjusted layer value.
     */
    private int adjustRootTaskLayer(SurfaceControl.Transaction t, ArrayList<Task> stacks,
            int startLayer, boolean normalStacks) {
        mTmpNeedsZBoostIndexes.clear();
        final int stackSize = stacks.size();
        for (int i = 0; i < stackSize; i++) {
            final Task stack = stacks.get(i);
            if (!stack.needsZBoost()) {
                stack.assignLayer(t, startLayer++);
                if (normalStacks) {
                    startLayer = adjustNormalRootTaskLayer(stack, startLayer);
                }
            } else {
                mTmpNeedsZBoostIndexes.add(i);
            }
        }

        final int zBoostSize = mTmpNeedsZBoostIndexes.size();
        for (int i = 0; i < zBoostSize; i++) {
            final Task stack = stacks.get(mTmpNeedsZBoostIndexes.get(i));
            stack.assignLayer(t, startLayer++);
            if (normalStacks) {
                startLayer = adjustNormalRootTaskLayer(stack, startLayer);
            }
        }
        return startLayer;
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

    SurfaceControl getSplitScreenDividerAnchor() {
        return mSplitScreenDividerAnchor;
    }

    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        if (getParent() != null) {
            super.onParentChanged(newParent, oldParent, () -> {
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
                    .remove(mAppAnimationLayer)
                    .remove(mBoostedAppAnimationLayer)
                    .remove(mHomeAppAnimationLayer)
                    .remove(mSplitScreenDividerAnchor)
                    .apply();
            mAppAnimationLayer = null;
            mBoostedAppAnimationLayer = null;
            mHomeAppAnimationLayer = null;
            mSplitScreenDividerAnchor = null;
        }
    }

    void onRootTaskRemoved(Task rootTask) {
        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.v(TAG_ROOT_TASK, "removeStack: detaching " + rootTask + " from displayId="
                    + mDisplayContent.mDisplayId);
        }
        if (mPreferredTopFocusableRootTask == rootTask) {
            mPreferredTopFocusableRootTask = null;
        }
        mDisplayContent.releaseSelfIfNeeded();
        onRootTaskOrderChanged(rootTask);
    }

    void resetPreferredTopFocusableRootTaskIfBelow(Task task) {
        if (mPreferredTopFocusableRootTask != null
                && mPreferredTopFocusableRootTask.compareTo(task) < 0) {
            mPreferredTopFocusableRootTask = null;
        }
    }

    /**
     * Moves/reparents `task` to the back of whatever container the home stack is in. This is for
     * when we just want to move a task to "the back" vs. a specific place. The primary use-case
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
            // Apparently reparent early-outs if same stack, so we have to explicitly reorder.
            homeParentTask.positionChildAtBottom(task);
        } else {
            task.reparent(homeParentTask, false /* toTop */,
                    Task.REPARENT_LEAVE_ROOT_TASK_IN_PLACE, false /* animate */,
                    false /* deferResume */, "positionTaskBehindHome");
        }
    }

    /**
     * Returns an existing stack compatible with the windowing mode and activity type or creates one
     * if a compatible stack doesn't exist.
     *
     * @see #getOrCreateRootTask(int, int, boolean, Intent, Task)
     */
    Task getOrCreateRootTask(int windowingMode, int activityType, boolean onTop) {
        return getOrCreateRootTask(windowingMode, activityType, onTop, null /* intent */,
                null /* candidateTask */);
    }

    /**
     * When two level tasks are required for given windowing mode and activity type, returns an
     * existing compatible root task or creates a new one.
     * For one level task, the candidate task would be reused to also be the root task or create
     * a new root task if no candidate task.
     *
     * @see #getRootTask(int, int)
     * @see #createRootTask(int, int, boolean)
     */
    Task getOrCreateRootTask(int windowingMode, int activityType, boolean onTop,
            Intent intent, Task candidateTask) {
        // Need to pass in a determined windowing mode to see if a new stack should be created,
        // so use its parent's windowing mode if it is undefined.
        if (!alwaysCreateRootTask(
                windowingMode != WINDOWING_MODE_UNDEFINED ? windowingMode : getWindowingMode(),
                activityType)) {
            Task stack = getRootTask(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        } else if (candidateTask != null) {
            final Task stack = candidateTask;
            final int position = onTop ? POSITION_TOP : POSITION_BOTTOM;
            Task launchRootTask = updateLaunchRootTask(windowingMode);

            if (launchRootTask != null) {
                if (stack.getParent() == null) {
                    launchRootTask.addChild(stack, position);
                } else if (stack.getParent() != launchRootTask) {
                    stack.reparent(launchRootTask, position);
                }
            } else if (stack.getDisplayArea() != this || !stack.isRootTask()) {
                if (stack.getParent() == null) {
                    addChild(stack, position);
                } else {
                    stack.reparent(this, onTop);
                }
            }
            // Update windowing mode if necessary, e.g. moving a pinned task to fullscreen.
            if (candidateTask.getWindowingMode() != windowingMode) {
                candidateTask.setWindowingMode(windowingMode);
            }
            return stack;
        }
        return createRootTask(windowingMode, activityType, onTop, null /*info*/, intent,
                false /* createdByOrganizer */);
    }

    /**
     * Returns an existing stack compatible with the input params or creates one
     * if a compatible stack doesn't exist.
     *
     * @see #getOrCreateRootTask(int, int, boolean)
     */
    Task getOrCreateRootTask(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask, int activityType,
            boolean onTop) {
        // First preference is the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;
        // Validate that our desired windowingMode will work under the current conditions.
        // UNDEFINED windowing mode is a valid result and means that the new stack will inherit
        // it's display's windowing mode.
        windowingMode = validateWindowingMode(windowingMode, r, candidateTask, activityType);
        return getOrCreateRootTask(windowingMode, activityType, onTop, null /* intent */,
                candidateTask);
    }

    @VisibleForTesting
    int getNextRootTaskId() {
        return mAtmService.mTaskSupervisor.getNextTaskIdForUser();
    }

    Task createRootTask(int windowingMode, int activityType, boolean onTop) {
        return createRootTask(windowingMode, activityType, onTop, null /* info */,
                null /* intent */, false /* createdByOrganizer */);
    }

    Task createRootTask(int windowingMode, int activityType, boolean onTop, ActivityInfo info,
            Intent intent, boolean createdByOrganizer) {
        return createRootTask(windowingMode, activityType, onTop, null /* info */,
                null /* intent */, false /* createdByOrganizer */, false /* deferTaskAppear */,
                null /* launchCookie */);
    }

    /**
     * Creates a stack matching the input windowing mode and activity type on this display.
     *
     * @param windowingMode      The windowing mode the stack should be created in. If
     *                           {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the stack
     *                           will
     *                           inherit its parent's windowing mode.
     * @param activityType       The activityType the stack should be created in. If
     *                           {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the stack
     *                           will
     *                           be created in {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop              If true the stack will be created at the top of the display, else
     *                           at the bottom.
     * @param info               The started activity info.
     * @param intent             The intent that started this task.
     * @param createdByOrganizer @{code true} if this is created by task organizer, @{code false}
     *                           otherwise.
     * @param deferTaskAppear    @{code true} if the task appeared signal should be deferred.
     * @param launchCookie       Launch cookie used for tracking/association of the task we are
     *                           creating.
     * @return The newly created stack.
     */
    Task createRootTask(int windowingMode, int activityType, boolean onTop, ActivityInfo info,
            Intent intent, boolean createdByOrganizer, boolean deferTaskAppear,
            IBinder launchCookie) {
        if (activityType == ACTIVITY_TYPE_UNDEFINED && !createdByOrganizer) {
            // Can't have an undefined stack type yet...so re-map to standard. Anyone that wants
            // anything else should be passing it in anyways...except for the task organizer.
            activityType = ACTIVITY_TYPE_STANDARD;
        }

        if (activityType != ACTIVITY_TYPE_STANDARD && activityType != ACTIVITY_TYPE_UNDEFINED) {
            // For now there can be only one stack of a particular non-standard activity type on a
            // display. So, get that ignoring whatever windowing mode it is currently in.
            Task stack = getRootTask(WINDOWING_MODE_UNDEFINED, activityType);
            if (stack != null) {
                throw new IllegalArgumentException("Stack=" + stack + " of activityType="
                        + activityType + " already on display=" + this + ". Can't have multiple.");
            }
        }

        if (!isWindowingModeSupported(windowingMode, mAtmService.mSupportsMultiWindow,
                mAtmService.mSupportsSplitScreenMultiWindow,
                mAtmService.mSupportsFreeformWindowManagement,
                mAtmService.mSupportsPictureInPicture, activityType)) {
            throw new IllegalArgumentException("Can't create stack for unsupported windowingMode="
                    + windowingMode);
        }

        if (windowingMode == WINDOWING_MODE_PINNED && getRootPinnedTask() != null) {
            // Only 1 stack can be PINNED at a time, so dismiss the existing one
            getRootPinnedTask().dismissPip();
        }

        final int stackId = getNextRootTaskId();
        return createRootTaskUnchecked(windowingMode, activityType, stackId, onTop, info, intent,
                createdByOrganizer, deferTaskAppear, launchCookie);
    }

    /** @return the root task to create the next task in. */
    private Task updateLaunchRootTask(int windowingMode) {
        if (!isSplitScreenWindowingMode(windowingMode)) {
            // Only split-screen windowing modes can do this currently...
            return null;
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task t = mChildren.get(i);
            if (!t.mCreatedByOrganizer || t.getRequestedOverrideWindowingMode() != windowingMode) {
                continue;
            }
            // If not already set, pick a launch root which is not the one we are launching into.
            if (mLaunchRootTask == null) {
                for (int j = 0, n = mChildren.size(); j < n; ++j) {
                    final Task tt = mChildren.get(j);
                    if (tt.mCreatedByOrganizer && tt != t) {
                        mLaunchRootTask = tt;
                        break;
                    }
                }
            }
            return t;
        }
        return mLaunchRootTask;
    }

    @VisibleForTesting
    Task createRootTaskUnchecked(int windowingMode, int activityType, int stackId, boolean onTop,
            ActivityInfo info, Intent intent, boolean createdByOrganizer, boolean deferTaskAppear,
            IBinder launchCookie) {
        if (windowingMode == WINDOWING_MODE_PINNED && activityType != ACTIVITY_TYPE_STANDARD) {
            throw new IllegalArgumentException("Stack with windowing mode cannot with non standard "
                    + "activity type.");
        }
        if (info == null) {
            info = new ActivityInfo();
            info.applicationInfo = new ApplicationInfo();
        }

        // Task created by organizer are added as root.
        Task launchRootTask = createdByOrganizer ? null : updateLaunchRootTask(windowingMode);
        if (launchRootTask != null) {
            // Since this stack will be put into a root task, its windowingMode will be inherited.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        }

        final Task stack = new Task(mAtmService, stackId, activityType,
                info, intent, createdByOrganizer, deferTaskAppear, launchCookie);
        if (launchRootTask != null) {
            launchRootTask.addChild(stack, onTop ? POSITION_TOP : POSITION_BOTTOM);
            if (onTop) {
                positionChildAt(POSITION_TOP, launchRootTask, false /* includingParents */);
            }
        } else {
            addChild(stack, onTop ? POSITION_TOP : POSITION_BOTTOM);
            stack.setWindowingMode(windowingMode, true /* creating */);
        }
        return stack;
    }

    /**
     * Get the preferred focusable stack in priority. If the preferred stack does not exist, find a
     * focusable and visible stack from the top of stacks in this display.
     */
    Task getFocusedRootTask() {
        if (mPreferredTopFocusableRootTask != null) {
            return mPreferredTopFocusableRootTask;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task stack = mChildren.get(i);
            if (stack.isFocusableAndVisible()) {
                return stack;
            }
        }

        return null;
    }

    Task getNextFocusableRootTask(Task currentFocus, boolean ignoreCurrent) {
        final int currentWindowingMode = currentFocus != null
                ? currentFocus.getWindowingMode() : WINDOWING_MODE_UNDEFINED;

        Task candidate = null;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task rootTask = mChildren.get(i);
            if (ignoreCurrent && rootTask == currentFocus) {
                continue;
            }
            if (!rootTask.isFocusableAndVisible()) {
                continue;
            }

            if (currentWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && candidate == null && rootTask.inSplitScreenPrimaryWindowingMode()) {
                // If the currently focused stack is in split-screen secondary we save off the
                // top primary split-screen stack as a candidate for focus because we might
                // prefer focus to move to an other stack to avoid primary split-screen stack
                // overlapping with a fullscreen stack when a fullscreen stack is higher in z
                // than the next split-screen stack. Assistant stack, I am looking at you...
                // We only move the focus to the primary-split screen stack if there isn't a
                // better alternative.
                candidate = rootTask;
                continue;
            }
            if (candidate != null && rootTask.inSplitScreenSecondaryWindowingMode()) {
                // Use the candidate stack since we are now at the secondary split-screen.
                return candidate;
            }
            return rootTask;
        }
        return candidate;
    }

    ActivityRecord getFocusedActivity() {
        final Task focusedStack = getFocusedRootTask();
        if (focusedStack == null) {
            return null;
        }
        // TODO(b/111541062): Move this into ActivityStack#getResumedActivity()
        // Check if the focused stack has the resumed activity
        ActivityRecord resumedActivity = focusedStack.getResumedActivity();
        if (resumedActivity == null || resumedActivity.app == null) {
            // If there is no registered resumed activity in the stack or it is not running -
            // try to use previously resumed one.
            resumedActivity = focusedStack.mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                // If previously resumed activity doesn't work either - find the topmost running
                // activity that can be focused.
                resumedActivity = focusedStack.topRunningActivity(true /* focusableOnly */);
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
            currentFocusedTask.mLastPausedActivity = null;
        }

        mLastFocusedRootTask = prevFocusedTask;
        EventLogTags.writeWmFocusedStack(mRootWindowContainer.mCurrentUser,
                mDisplayContent.mDisplayId,
                currentFocusedTask == null ? -1 : currentFocusedTask.getRootTaskId(),
                mLastFocusedRootTask == null ? -1 : mLastFocusedRootTask.getRootTaskId(),
                updateLastFocusedTaskReason);
    }

    boolean allResumedActivitiesComplete() {
        for (int stackNdx = mChildren.size() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord r = mChildren.get(stackNdx).getResumedActivity();
            if (r != null && !r.isState(RESUMED)) {
                return false;
            }
        }
        final Task currentFocusedStack = getFocusedRootTask();
        if (ActivityTaskManagerDebugConfig.DEBUG_ROOT_TASK) {
            Slog.d(TAG_ROOT_TASK, "allResumedActivitiesComplete: mLastFocusedStack changing from="
                    + mLastFocusedRootTask + " to=" + currentFocusedStack);
        }
        mLastFocusedRootTask = currentFocusedStack;
        return true;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks. This is done before
     * resuming a new activity and to make sure that previously active activities are
     * paused in stacks that are no longer visible or in pinned windowing mode. This does not
     * pause activities in visible stacks, so if an activity is launched within the same stack/task,
     * then we should explicitly pause that stack's top activity.
     *
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @param resuming    The resuming activity.
     * @return {@code true} if any activity was paused as a result of this call.
     */
    boolean pauseBackTasks(boolean userLeaving, ActivityRecord resuming) {
        boolean someActivityPaused = false;
        for (int stackNdx = mChildren.size() - 1; stackNdx >= 0; --stackNdx) {
            final Task stack = mChildren.get(stackNdx);
            final ActivityRecord resumedActivity = stack.getResumedActivity();
            if (resumedActivity != null
                    && (stack.getVisibility(resuming) != TASK_VISIBILITY_VISIBLE
                    || !stack.isTopActivityFocusable())) {
                ProtoLog.d(WM_DEBUG_STATES, "pauseBackStacks: stack=%s "
                        + "mResumedActivity=%s", stack, resumedActivity);
                someActivityPaused |= stack.startPausingLocked(userLeaving, false /* uiSleeping*/,
                        resuming, "pauseBackStacks");
            }
        }
        return someActivityPaused;
    }

    /**
     * Find task for putting the Activity in.
     */
    void findTaskLocked(final ActivityRecord r, final boolean isPreferredDisplayArea,
            RootWindowContainer.FindTaskResult result) {
        mTmpFindTaskResult.clear();
        for (int stackNdx = mChildren.size() - 1; stackNdx >= 0; --stackNdx) {
            final Task stack = mChildren.get(stackNdx);
            if (!r.hasCompatibleActivityType(stack) && stack.isLeafTask()) {
                ProtoLog.d(WM_DEBUG_TASKS, "Skipping stack: (mismatch activity/stack) "
                        + "%s", stack);
                continue;
            }

            mTmpFindTaskResult.process(r, stack);
            // It is possible to have tasks in multiple stacks with the same root affinity, so
            // we should keep looking after finding an affinity match to see if there is a
            // better match in another stack. Also, task affinity isn't a good enough reason
            // to target a display which isn't the source of the intent, so skip any affinity
            // matches not on the specified display.
            if (mTmpFindTaskResult.mRecord != null) {
                if (mTmpFindTaskResult.mIdealMatch) {
                    result.setTo(mTmpFindTaskResult);
                    return;
                } else if (isPreferredDisplayArea) {
                    // Note: since the traversing through the stacks is top down, the floating
                    // tasks should always have lower priority than any affinity-matching tasks
                    // in the fullscreen stacks
                    result.setTo(mTmpFindTaskResult);
                }
            }
        }
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
            mLaunchRootTask = null;
            moveSplitScreenTasksToFullScreen();
        } finally {
            final Task topFullscreenStack = toTop != null
                    ? toTop : getTopRootTaskInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            final Task homeStack = getOrCreateRootHomeTask();
            if (homeStack != null && ((topFullscreenStack != null && !isTopRootTask(homeStack))
                    || toTop != null)) {
                // Whenever split-screen is dismissed we want the home stack directly behind the
                // current top fullscreen stack so it shows up when the top stack is finished.
                // Or, if the caller specified a stack to be on top after split-screen is dismissed.
                // TODO: Would be better to use ActivityDisplay.positionChildAt() for this, however
                // ActivityDisplay doesn't have a direct controller to WM side yet. We can switch
                // once we have that.
                homeStack.moveToFront("onSplitScreenModeDismissed");
                topFullscreenStack.moveToFront("onSplitScreenModeDismissed");
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
                wct.reparent(root.getChildAt(j).mRemoteToken.toWindowContainerToken(),
                        null, true /* toTop */);
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
    private boolean isWindowingModeSupported(int windowingMode, boolean supportsMultiWindow,
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
                supportsMultiWindow = task.isResizeable();
                supportsSplitScreen = task.supportsSplitScreenWindowingMode();
                // TODO: Do we need to check for freeform and Pip support here?
            } else if (r != null) {
                supportsMultiWindow = r.isResizeable();
                supportsSplitScreen = r.supportsSplitScreenWindowingMode();
                supportsFreeform = r.supportsFreeform();
                supportsPip = r.supportsPictureInPicture();
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

    boolean isTopRootTask(Task stack) {
        return stack == getTopRootTask();
    }

    ActivityRecord topRunningActivity() {
        return topRunningActivity(false /* considerKeyguardState */);
    }

    /**
     * Returns the top running activity in the focused stack. In the case the focused stack has no
     * such activity, the next focusable stack on this display is returned.
     *
     * @param considerKeyguardState Indicates whether the locked state should be considered. if
     *                              {@code true} and the keyguard is locked, only activities that
     *                              can be shown on top of the keyguard will be considered.
     * @return The top running activity. {@code null} if none is available.
     */
    ActivityRecord topRunningActivity(boolean considerKeyguardState) {
        ActivityRecord topRunning = null;
        final Task focusedStack = getFocusedRootTask();
        if (focusedStack != null) {
            topRunning = focusedStack.topRunningActivity();
        }

        // Look in other focusable stacks.
        if (topRunning == null) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final Task stack = mChildren.get(i);
                // Only consider focusable stacks other than the current focused one.
                if (stack == focusedStack || !stack.isTopActivityFocusable()) {
                    continue;
                }
                topRunning = stack.topRunningActivity();
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
     * Returns the existing home stack or creates and returns a new one if it should exist for the
     * display.
     *
     * @param onTop Only be used when there is no existing home stack. If true the home stack will
     *              be created at the top of the display, else at the bottom.
     */
    @Nullable
    Task getOrCreateRootHomeTask(boolean onTop) {
        Task homeTask = getRootHomeTask();
        if (homeTask == null && mDisplayContent.supportsSystemDecorations()) {
            homeTask = createRootTask(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, onTop);
        }
        return homeTask;
    }

    boolean isSplitScreenModeActivated() {
        Task task = getRootSplitScreenPrimaryTask();
        return task != null && task.hasChild();
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode.
     * Null is no compatible stack on the display.
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
     * Moves the focusable home activity to top. If there is no such activity, the home stack will
     * still move to top.
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
        final Task homeStack = getRootHomeTask();
        if (homeStack == null) {
            return null;
        }

        final PooledPredicate p = PooledLambda.obtainPredicate(
                TaskDisplayArea::isHomeActivityForUser, PooledLambda.__(ActivityRecord.class),
                userId);
        final ActivityRecord r = homeStack.getActivity(p);
        p.recycle();
        return r;
    }

    private static boolean isHomeActivityForUser(ActivityRecord r, int userId) {
        return r.isActivityTypeHome() && (userId == UserHandle.USER_ALL || r.mUserId == userId);
    }

    /**
     * Adjusts the {@param stack} behind the last visible stack in the display if necessary.
     * Generally used in conjunction with {@link #moveRootTaskBehindRootTask}.
     */
    // TODO(b/151575894): Remove special stack movement methods.
    void moveRootTaskBehindBottomMostVisibleRootTask(Task rootTask) {
        if (rootTask.shouldBeVisible(null)) {
            // Skip if the stack is already visible
            return;
        }

        // Move the stack to the bottom to not affect the following visibility checks
        rootTask.getParent().positionChildAt(POSITION_BOTTOM, rootTask,
                false /* includingParents */);

        // Find the next position where the stack should be placed
        final boolean isRootTask = rootTask.isRootTask();
        final int numRootTasks =
                isRootTask ? mChildren.size() : rootTask.getParent().getChildCount();
        for (int rootTaskNdx = 0; rootTaskNdx < numRootTasks; rootTaskNdx++) {
            final Task s = isRootTask ? mChildren.get(rootTaskNdx)
                    : (Task) rootTask.getParent().getChildAt(rootTaskNdx);
            if (s == rootTask) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN
                    || winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided stack to behind this stack
                final int position = Math.max(0, rootTaskNdx - 1);
                rootTask.getParent().positionChildAt(position, rootTask,
                        false /*includingParents */);
                break;
            }
        }
    }

    /**
     * Moves the {@param stack} behind the given {@param behindStack} if possible. If
     * {@param behindStack} is not currently in the display, then then the stack is moved to the
     * back. Generally used in conjunction with
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

        // Note that positionChildAt will first remove the given stack before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int stackIndex = parent.mChildren.indexOf(rootTask);
        final int behindStackIndex = parent.mChildren.indexOf(behindRootTask);
        final int insertIndex = stackIndex <= behindStackIndex
                ? behindStackIndex - 1 : behindStackIndex;
        final int position = Math.max(0, insertIndex);
        parent.positionChildAt(position, rootTask, false /* includingParents */);
    }

    boolean hasPinnedTask() {
        return getRootPinnedTask() != null;
    }

    /**
     * @return the stack currently above the {@param stack}. Can be null if the {@param stack} is
     * already top-most.
     */
    static Task getRootTaskAbove(Task rootTask) {
        final WindowContainer wc = rootTask.getParent();
        final int index = wc.mChildren.indexOf(rootTask) + 1;
        return (index < wc.mChildren.size()) ? (Task) wc.mChildren.get(index) : null;
    }

    /** Returns true if the stack in the windowing mode is visible. */
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
     * Adds a listener to be notified whenever the stack order in the display changes. Currently
     * only used by the {@link RecentsAnimation} to determine whether to interrupt and cancel the
     * current animation when the system state changes.
     */
    void registerRootTaskOrderChangedListener(OnRootTaskOrderChangedListener listener) {
        if (!mRootTaskOrderChangedCallbacks.contains(listener)) {
            mRootTaskOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered stack order change listener.
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
     * Callback for when the order of the stacks in the display changes.
     */
    interface OnRootTaskOrderChangedListener {
        void onRootTaskOrderChanged(Task rootTask);
    }

    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients, boolean userLeaving) {
        mAtmService.mTaskSupervisor.beginActivityVisibilityUpdate();
        try {
            for (int stackNdx = mChildren.size() - 1; stackNdx >= 0; --stackNdx) {
                final Task stack = mChildren.get(stackNdx);
                stack.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                        notifyClients, userLeaving);
            }
        } finally {
            mAtmService.mTaskSupervisor.endActivityVisibilityUpdate();
        }
    }

    /**
     * Removes the stacks in the node applying the content removal node from the display.
     *
     * @return last reparented stack, or {@code null} if the stacks had to be destroyed.
     */
    Task remove() {
        mPreferredTopFocusableRootTask = null;
        // TODO(b/153090332): Allow setting content removal mode per task display area
        final boolean destroyContentOnRemoval = mDisplayContent.shouldDestroyContentOnRemove();
        final TaskDisplayArea toDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task lastReparentedStack = null;

        // Stacks could be reparented from the removed display area to other display area. After
        // reparenting the last stack of the removed display area, the display area becomes ready to
        // be released (no more ActivityStack-s). But, we cannot release it at that moment or the
        // related WindowContainer will also be removed. So, we set display area as removed after
        // reparenting stack finished.
        // Keep the order from bottom to top.
        int numStacks = mChildren.size();

        final boolean splitScreenActivated = toDisplayArea.isSplitScreenModeActivated();
        final Task rootStack = splitScreenActivated ? toDisplayArea
                .getTopRootTaskInWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) : null;
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final Task stack = mChildren.get(stackNdx);
            // Always finish non-standard type stacks and stacks created by a organizer.
            // TODO: For stacks created by organizer, consider reparenting children tasks if the use
            //       case arises in the future.
            if (destroyContentOnRemoval
                    || !stack.isActivityTypeStandardOrUndefined()
                    || stack.mCreatedByOrganizer) {
                stack.finishAllActivitiesImmediately();
            } else {
                // Reparent the stack to the root task of secondary-split-screen or display area.
                stack.reparent(stack.supportsSplitScreenWindowingMode() && rootStack != null
                        ? rootStack : toDisplayArea, POSITION_TOP);

                // Set the windowing mode to undefined by default to let the stack inherited the
                // windowing mode.
                stack.setWindowingMode(WINDOWING_MODE_UNDEFINED);
                lastReparentedStack = stack;
            }
            // Stacks may be removed from this display. Ensure each stack will be processed
            // and the loop will end.
            stackNdx -= numStacks - mChildren.size();
            numStacks = mChildren.size();
        }
        if (lastReparentedStack != null && splitScreenActivated) {
            if (!lastReparentedStack.supportsSplitScreenWindowingMode()) {
                mAtmService.getTaskChangeNotificationController()
                        .notifyActivityDismissingDockedStack();
                toDisplayArea.onSplitScreenModeDismissed(lastReparentedStack);
            } else if (rootStack != null) {
                // update focus
                rootStack.moveToFront("display-removed");
            }
        }

        mRemoved = true;

        return lastReparentedStack;
    }

    /** Whether this task display area can request orientation. */
    @VisibleForTesting
    boolean canSpecifyOrientation() {
        // Only allow to specify orientation if this TDA is not set to ignore orientation request,
        // and it is the last focused one on this logical display that can request orientation
        // request.
        return !mIgnoreOrientationRequest
                && mDisplayContent.getOrientationRequestingTaskDisplayArea() == this;
    }

    @Override
    protected boolean isTaskDisplayArea() {
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
        pw.println(doublePrefix + "Application tokens in top down Z order:");
        for (int rootTaskNdx = getChildCount() - 1; rootTaskNdx >= 0; --rootTaskNdx) {
            final Task rootTask = getChildAt(rootTaskNdx);
            pw.println(doublePrefix + "* " + rootTask);
            rootTask.dump(pw, triplePrefix, dumpAll);
        }
    }
}
