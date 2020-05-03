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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.app.WindowConfiguration.isSplitScreenWindowingMode;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.wm.ActivityStack.ActivityState.RESUMED;
import static com.android.server.wm.ActivityStack.STACK_VISIBILITY_VISIBLE;
import static com.android.server.wm.ActivityStackSupervisor.TAG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_STATES;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerService.TAG_STACK;
import static com.android.server.wm.DisplayContent.alwaysCreateStack;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.RootWindowContainer.TAG_STATES;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;
import android.util.Slog;
import android.view.SurfaceControl;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.internal.util.function.pooled.PooledPredicate;
import com.android.server.protolog.common.ProtoLog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link DisplayArea} that represents a section of a screen that contains app window containers.
 */
final class TaskDisplayArea extends DisplayArea<ActivityStack> {

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
    private ActivityStack mRootHomeTask;
    private ActivityStack mRootPinnedTask;
    private ActivityStack mRootSplitScreenPrimaryTask;

    private final ArrayList<ActivityStack> mTmpAlwaysOnTopStacks = new ArrayList<>();
    private final ArrayList<ActivityStack> mTmpNormalStacks = new ArrayList<>();
    private final ArrayList<ActivityStack> mTmpHomeStacks = new ArrayList<>();

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
    ActivityStack mPreferredTopFocusableStack;

    private final RootWindowContainer.FindTaskResult
            mTmpFindTaskResult = new RootWindowContainer.FindTaskResult();

    // Indicates whether the Assistant should show on top of the Dream (respectively, above
    // everything else on screen). Otherwise, it will be put under always-on-top stacks.
    private final boolean mAssistantOnTopOfDream;

    /**
     * If this is the same as {@link #getFocusedStack} then the activity on the top of the focused
     * stack has been resumed. If stacks are changing position this will hold the old stack until
     * the new stack becomes resumed after which it will be set to current focused stack.
     */
    ActivityStack mLastFocusedStack;
    /**
     * All of the stacks on this display. Order matters, topmost stack is in front of all other
     * stacks, bottommost behind. Accessed directly by ActivityManager package classes. Any calls
     * changing the list should also call {@link #onStackOrderChanged()}.
     */
    private ArrayList<OnStackOrderChangedListener> mStackOrderChangedCallbacks = new ArrayList<>();

    /**
     * The task display area is removed from the system and we are just waiting for all activities
     * on it to be finished before removing this object.
     */
    private boolean mRemoved;

    TaskDisplayArea(DisplayContent displayContent, WindowManagerService service, String name,
            int displayAreaFeature) {
        super(service, Type.ANY, name, displayAreaFeature);
        mDisplayContent = displayContent;
        mRootWindowContainer = service.mRoot;
        mAtmService = service.mAtmService;

        mAssistantOnTopOfDream = mWmService.mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_assistantOnTopOfDream);
    }

    /**
     * Returns the topmost stack on the display that is compatible with the input windowing mode
     * and activity type. Null is no compatible stack on the display.
     */
    ActivityStack getStack(int windowingMode, int activityType) {
        if (activityType == ACTIVITY_TYPE_HOME) {
            return mRootHomeTask;
        }
        if (windowingMode == WINDOWING_MODE_PINNED) {
            return mRootPinnedTask;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            return mRootSplitScreenPrimaryTask;
        }
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getChildAt(i);
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
    ActivityStack getTopStack() {
        final int count = getChildCount();
        return count > 0 ? getChildAt(count - 1) : null;
    }

    // TODO: Figure-out a way to remove since it might be a source of confusion.
    int getIndexOf(ActivityStack stack) {
        return mChildren.indexOf(stack);
    }

    ActivityStack getRootHomeTask() {
        return mRootHomeTask;
    }

    ActivityStack getRootPinnedTask() {
        return mRootPinnedTask;
    }

    ActivityStack getRootSplitScreenPrimaryTask() {
        return mRootSplitScreenPrimaryTask;
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

    void onStackWindowingModeChanged(ActivityStack stack) {
        removeStackReferenceIfNeeded(stack);
        addStackReferenceIfNeeded(stack);
        if (stack == mRootPinnedTask && getTopStack() != stack) {
            // Looks like this stack changed windowing mode to pinned. Move it to the top.
            positionChildAt(POSITION_TOP, stack, false /* includingParents */);
        }
    }

    void addStackReferenceIfNeeded(ActivityStack stack) {
        if (stack.isActivityTypeHome()) {
            if (mRootHomeTask != null) {
                if (!stack.isDescendantOf(mRootHomeTask)) {
                    throw new IllegalArgumentException("addStackReferenceIfNeeded: home stack="
                            + mRootHomeTask + " already exist on display=" + this
                            + " stack=" + stack);
                }
            } else {
                mRootHomeTask = stack;
            }
        }

        if (!stack.isRootTask()) {
            return;
        }
        final int windowingMode = stack.getWindowingMode();
        if (windowingMode == WINDOWING_MODE_PINNED) {
            if (mRootPinnedTask != null) {
                throw new IllegalArgumentException(
                        "addStackReferenceIfNeeded: pinned stack=" + mRootPinnedTask
                                + " already exist on display=" + this + " stack=" + stack);
            }
            mRootPinnedTask = stack;
        } else if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
            if (mRootSplitScreenPrimaryTask != null) {
                throw new IllegalArgumentException(
                        "addStackReferenceIfNeeded: split screen primary stack="
                                + mRootSplitScreenPrimaryTask
                                + " already exist on display=" + this + " stack=" + stack);
            }
            mRootSplitScreenPrimaryTask = stack;
        }
    }

    void removeStackReferenceIfNeeded(ActivityStack stack) {
        if (stack == mRootHomeTask) {
            mRootHomeTask = null;
        } else if (stack == mRootPinnedTask) {
            mRootPinnedTask = null;
        } else if (stack == mRootSplitScreenPrimaryTask) {
            mRootSplitScreenPrimaryTask = null;
        }
    }

    @Override
    void addChild(ActivityStack stack, int position) {
        if (DEBUG_STACK) Slog.d(TAG_WM, "Set stack=" + stack + " on taskDisplayArea=" + this);
        addStackReferenceIfNeeded(stack);
        position = findPositionForStack(position, stack, true /* adding */);

        super.addChild(stack, position);
        mAtmService.updateSleepIfNeededLocked();

        positionStackAt(stack, position);
    }

    @Override
    protected void removeChild(ActivityStack stack) {
        super.removeChild(stack);
        onStackRemoved(stack);
        mAtmService.updateSleepIfNeededLocked();
        removeStackReferenceIfNeeded(stack);
    }

    @Override
    boolean isOnTop() {
        // Considered always on top
        return true;
    }

    @Override
    void positionChildAt(int position, ActivityStack child, boolean includingParents) {
        final boolean moveToTop = (position == POSITION_TOP || position == getChildCount());
        final boolean moveToBottom = (position == POSITION_BOTTOM || position == 0);
        if (child.getWindowConfiguration().isAlwaysOnTop() && !moveToTop) {
            // This stack is always-on-top, override the default behavior.
            Slog.w(TAG_WM, "Ignoring move of always-on-top stack=" + this + " to bottom");

            // Moving to its current position, as we must call super but we don't want to
            // perform any meaningful action.
            final int currentPosition = mChildren.indexOf(child);
            super.positionChildAt(currentPosition, child, false /* includingParents */);
            return;
        }
        // We don't allow untrusted display to top when task stack moves to top,
        // until user tapping this display to change display position as top intentionally.
        if (mDisplayContent.isUntrustedVirtualDisplay() && !getParent().isOnTop()) {
            includingParents = false;
        }
        final int targetPosition = findPositionForStack(position, child, false /* adding */);
        super.positionChildAt(targetPosition, child, false /* includingParents */);

        if (includingParents && (moveToTop || moveToBottom)) {
            // The DisplayContent children do not re-order, but we still want to move the
            // display of this stack container because the intention of positioning is to have
            // higher z-order to gain focus.
            mDisplayContent.positionDisplayAt(moveToTop ? POSITION_TOP : POSITION_BOTTOM,
                    true /* includingParents */);
        }

        child.updateTaskMovement(moveToTop);

        mDisplayContent.setLayoutNeeded();
    }

    /**
     * Assigns a priority number to stack types. This priority defines an order between the types
     * of stacks that are added to the task display area.
     *
     * Higher priority number indicates that the stack should have a higher z-order.
     *
     * @return the priority of the stack
     */
    private int getPriority(ActivityStack stack) {
        if (mAssistantOnTopOfDream && stack.isActivityTypeAssistant()) return 4;
        if (stack.isActivityTypeDream()) return 3;
        if (stack.inPinnedWindowingMode()) return 2;
        if (stack.isAlwaysOnTop()) return 1;
        return 0;
    }

    private int findMinPositionForStack(ActivityStack stack) {
        int minPosition = POSITION_BOTTOM;
        for (int i = 0; i < mChildren.size(); ++i) {
            if (getPriority(getStackAt(i)) < getPriority(stack)) {
                minPosition = i;
            } else {
                break;
            }
        }

        if (stack.isAlwaysOnTop()) {
            // Since a stack could be repositioned while still being one of the children, we check
            // if this always-on-top stack already exists and if so, set the minPosition to its
            // previous position.
            final int currentIndex = getIndexOf(stack);
            if (currentIndex > minPosition) {
                minPosition = currentIndex;
            }
        }
        return minPosition;
    }

    private int findMaxPositionForStack(ActivityStack stack) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final ActivityStack curr = getStackAt(i);
            // Since a stack could be repositioned while still being one of the children, we check
            // if 'curr' is the same stack and skip it if so
            final boolean sameStack = curr == stack;
            if (getPriority(curr) <= getPriority(stack) && !sameStack) {
                return i;
            }
        }
        return 0;
    }

    /**
     * When stack is added or repositioned, find a proper position for it.
     *
     * The order is defined as:
     *  - Dream is on top of everything
     *  - PiP is directly below the Dream
     *  - always-on-top stacks are directly below PiP; new always-on-top stacks are added above
     *    existing ones
     *  - other non-always-on-top stacks come directly below always-on-top stacks; new
     *    non-always-on-top stacks are added directly below always-on-top stacks and above existing
     *    non-always-on-top stacks
     *  - if {@link #mAssistantOnTopOfDream} is enabled, then Assistant is on top of everything
     *    (including the Dream); otherwise, it is a normal non-always-on-top stack
     *
     * @param requestedPosition Position requested by caller.
     * @param stack Stack to be added or positioned.
     * @param adding Flag indicates whether we're adding a new stack or positioning an existing.
     * @return The proper position for the stack.
     */
    private int findPositionForStack(int requestedPosition, ActivityStack stack, boolean adding) {
        // The max possible position we can insert the stack at.
        int maxPosition = findMaxPositionForStack(stack);
        // The min possible position we can insert the stack at.
        int minPosition = findMinPositionForStack(stack);

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

        int prevPosition = mChildren.indexOf(stack);
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

    void setExitingTokensHasVisible(boolean hasVisible) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final ArrayList<ActivityRecord> activities = mChildren.get(i).mExitingActivities;
            for (int j = activities.size() - 1; j >= 0; --j) {
                activities.get(j).hasVisible = hasVisible;
            }
        }
    }

    void removeExistingAppTokensIfPossible() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final ArrayList<ActivityRecord> activities = mChildren.get(i).mExitingActivities;
            for (int j = activities.size() - 1; j >= 0; --j) {
                final ActivityRecord activity = activities.get(j);
                if (!activity.hasVisible && !mDisplayContent.mClosingApps.contains(activity)
                        && (!activity.mIsExiting || activity.isEmpty())) {
                    // Make sure there is no animation running on this activity, so any windows
                    // associated with it will be removed as soon as their animations are
                    // complete.
                    cancelAnimation();
                    ProtoLog.v(WM_DEBUG_ADD_REMOVE,
                            "performLayout: Activity exiting now removed %s", activity);
                    activity.removeIfPossible();
                }
            }
        }
    }

    @Override
    int getOrientation(int candidate) {
        if (isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
            // Apps and their containers are not allowed to specify an orientation while using
            // root tasks...except for the home stack if it is not resizable and currently
            // visible (top of) its root task.
            if (mRootHomeTask != null && mRootHomeTask.isVisible()) {
                final Task topMost = mRootHomeTask.getTopMostTask();
                final boolean resizable = topMost != null && topMost.isResizeable();
                if (!(resizable && mRootHomeTask.matchParentBounds())) {
                    final int orientation = mRootHomeTask.getOrientation();
                    if (orientation != SCREEN_ORIENTATION_UNSET) {
                        return orientation;
                    }
                }
            }
            return SCREEN_ORIENTATION_UNSPECIFIED;
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
        assignStackOrdering(t);

        for (int i = 0; i < mChildren.size(); i++) {
            final ActivityStack s = mChildren.get(i);
            s.assignChildLayers(t);
        }
    }

    void assignStackOrdering(SurfaceControl.Transaction t) {
        if (getParent() == null) {
            return;
        }
        mTmpAlwaysOnTopStacks.clear();
        mTmpHomeStacks.clear();
        mTmpNormalStacks.clear();
        for (int i = 0; i < mChildren.size(); ++i) {
            final ActivityStack s = mChildren.get(i);
            if (s.isAlwaysOnTop()) {
                mTmpAlwaysOnTopStacks.add(s);
            } else if (s.isActivityTypeHome()) {
                mTmpHomeStacks.add(s);
            } else {
                mTmpNormalStacks.add(s);
            }
        }

        int layer = 0;
        // Place home stacks to the bottom.
        for (int i = 0; i < mTmpHomeStacks.size(); i++) {
            mTmpHomeStacks.get(i).assignLayer(t, layer++);
        }
        // The home animation layer is between the home stacks and the normal stacks.
        final int layerForHomeAnimationLayer = layer++;
        int layerForSplitScreenDividerAnchor = layer++;
        int layerForAnimationLayer = layer++;
        for (int i = 0; i < mTmpNormalStacks.size(); i++) {
            final ActivityStack s = mTmpNormalStacks.get(i);
            s.assignLayer(t, layer++);
            if (s.inSplitScreenWindowingMode()) {
                // The split screen divider anchor is located above the split screen window.
                layerForSplitScreenDividerAnchor = layer++;
            }
            if (s.isTaskAnimating() || s.isAppTransitioning()) {
                // The animation layer is located above the highest animating stack and no
                // higher.
                layerForAnimationLayer = layer++;
            }
        }
        // The boosted animation layer is between the normal stacks and the always on top
        // stacks.
        final int layerForBoostedAnimationLayer = layer++;
        for (int i = 0; i < mTmpAlwaysOnTopStacks.size(); i++) {
            mTmpAlwaysOnTopStacks.get(i).assignLayer(t, layer++);
        }

        t.setLayer(mHomeAppAnimationLayer, layerForHomeAnimationLayer);
        t.setLayer(mAppAnimationLayer, layerForAnimationLayer);
        t.setLayer(mSplitScreenDividerAnchor, layerForSplitScreenDividerAnchor);
        t.setLayer(mBoostedAppAnimationLayer, layerForBoostedAnimationLayer);
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
                        .build();
                mBoostedAppAnimationLayer = makeChildSurface(null)
                        .setName("boostedAnimationLayer")
                        .build();
                mHomeAppAnimationLayer = makeChildSurface(null)
                        .setName("homeAnimationLayer")
                        .build();
                mSplitScreenDividerAnchor = makeChildSurface(null)
                        .setName("splitScreenDividerAnchor")
                        .build();
                getPendingTransaction()
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

    void onStackRemoved(ActivityStack stack) {
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.v(TAG_STACK, "removeStack: detaching " + stack + " from displayId="
                    + mDisplayContent.mDisplayId);
        }
        if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }
        mDisplayContent.releaseSelfIfNeeded();
        onStackOrderChanged(stack);
    }

    void positionStackAt(int position, ActivityStack child, boolean includingParents) {
        positionChildAt(position, child, includingParents);
        mDisplayContent.layoutAndAssignWindowLayersIfNeeded();
    }

    void positionStackAtTop(ActivityStack stack, boolean includingParents) {
        positionStackAtTop(stack, includingParents, null /* updateLastFocusedStackReason */);
    }

    void positionStackAtTop(ActivityStack stack, boolean includingParents,
            String updateLastFocusedStackReason) {
        positionStackAt(stack, getStackCount(), includingParents,
                updateLastFocusedStackReason);
    }

    void positionStackAtBottom(ActivityStack stack) {
        positionStackAtBottom(stack, null /* updateLastFocusedStackReason */);
    }

    void positionStackAtBottom(ActivityStack stack, String updateLastFocusedStackReason) {
        positionStackAt(stack, 0, false /* includingParents */,
                updateLastFocusedStackReason);
    }

    void positionStackAt(ActivityStack stack, int position) {
        positionStackAt(stack, position, false /* includingParents */,
                null /* updateLastFocusedStackReason */);
    }

    void positionStackAt(ActivityStack stack, int position, boolean includingParents,
            String updateLastFocusedStackReason) {
        // TODO: Keep in sync with WindowContainer.positionChildAt(), once we change that to adjust
        //       the position internally, also update the logic here
        final ActivityStack prevFocusedStack = updateLastFocusedStackReason != null
                ? getFocusedStack() : null;
        final boolean wasContained = mChildren.contains(stack);
        if (mDisplayContent.mSingleTaskInstance && getStackCount() == 1 && !wasContained) {
            throw new IllegalStateException(
                    "positionStackAt: Can only have one task on display=" + this);
        }

        final boolean movingToTop = wasContained && position >= getStackCount() - 1;
        // Reset mPreferredTopFocusableStack before positioning to top or {@link
        // ActivityStackSupervisor#updateTopResumedActivityIfNeeded()} won't update the top
        // resumed activity.
        if (movingToTop && stack.isFocusable()) {
            mPreferredTopFocusableStack = null;
        }

        // Since positionChildAt() is called during the creation process of pinned stacks,
        // ActivityStack#getStack() can be null.
        positionStackAt(position, stack, includingParents);

        // The insert position may be adjusted to non-top when there is always-on-top stack. Since
        // the original position is preferred to be top, the stack should have higher priority when
        // we are looking for top focusable stack. The condition {@code wasContained} restricts the
        // preferred stack is set only when moving an existing stack to top instead of adding a new
        // stack that may be too early (e.g. in the middle of launching or reparenting).
        if (movingToTop && stack.isFocusableAndVisible()) {
            mPreferredTopFocusableStack = stack;
        } else if (mPreferredTopFocusableStack == stack) {
            mPreferredTopFocusableStack = null;
        }

        if (updateLastFocusedStackReason != null) {
            final ActivityStack currentFocusedStack = getFocusedStack();
            if (currentFocusedStack != prevFocusedStack) {
                mLastFocusedStack = prevFocusedStack;
                EventLogTags.writeWmFocusedStack(mRootWindowContainer.mCurrentUser,
                        mDisplayContent.mDisplayId,
                        currentFocusedStack == null ? -1 : currentFocusedStack.getRootTaskId(),
                        mLastFocusedStack == null ? -1 : mLastFocusedStack.getRootTaskId(),
                        updateLastFocusedStackReason);
            }
        }

        onStackOrderChanged(stack);
    }

    ActivityStack getStack(int rootTaskId) {
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (stack.getRootTaskId() == rootTaskId) {
                return stack;
            }
        }
        return null;
    }

    /**
     * Returns an existing stack compatible with the windowing mode and activity type or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean, Intent, Task)
     */
    ActivityStack getOrCreateStack(int windowingMode, int activityType, boolean onTop) {
        return getOrCreateStack(windowingMode, activityType, onTop, null /* intent */,
                null /* candidateTask */);
    }

    /**
     * When two level tasks are required for given windowing mode and activity type, returns an
     * existing compatible root task or creates a new one.
     * For one level task, the candidate task would be reused to also be the root task or create
     * a new root task if no candidate task.
     * @see #getStack(int, int)
     * @see #createStack(int, int, boolean)
     */
    ActivityStack getOrCreateStack(int windowingMode, int activityType, boolean onTop,
            Intent intent, Task candidateTask) {
        // Need to pass in a determined windowing mode to see if a new stack should be created,
        // so use its parent's windowing mode if it is undefined.
        if (!alwaysCreateStack(
                windowingMode != WINDOWING_MODE_UNDEFINED ? windowingMode : getWindowingMode(),
                activityType)) {
            ActivityStack stack = getStack(windowingMode, activityType);
            if (stack != null) {
                return stack;
            }
        } else if (candidateTask != null) {
            final ActivityStack stack = (ActivityStack) candidateTask;
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
        return createStack(windowingMode, activityType, onTop, null /*info*/, intent,
                false /* createdByOrganizer */);
    }

    /**
     * Returns an existing stack compatible with the input params or creates one
     * if a compatible stack doesn't exist.
     * @see #getOrCreateStack(int, int, boolean)
     */
    ActivityStack getOrCreateStack(@Nullable ActivityRecord r,
            @Nullable ActivityOptions options, @Nullable Task candidateTask, int activityType,
            boolean onTop) {
        // First preference is the windowing mode in the activity options if set.
        int windowingMode = (options != null)
                ? options.getLaunchWindowingMode() : WINDOWING_MODE_UNDEFINED;
        // Validate that our desired windowingMode will work under the current conditions.
        // UNDEFINED windowing mode is a valid result and means that the new stack will inherit
        // it's display's windowing mode.
        windowingMode = validateWindowingMode(windowingMode, r, candidateTask, activityType);
        return getOrCreateStack(windowingMode, activityType, onTop, null /* intent */,
                candidateTask);
    }

    @VisibleForTesting
    int getNextStackId() {
        return mAtmService.mStackSupervisor.getNextTaskIdForUser();
    }

    ActivityStack createStack(int windowingMode, int activityType, boolean onTop) {
        return createStack(windowingMode, activityType, onTop, null /* info */, null /* intent */,
                false /* createdByOrganizer */);
    }

    /**
     * Creates a stack matching the input windowing mode and activity type on this display.
     * @param windowingMode The windowing mode the stack should be created in. If
     *                      {@link WindowConfiguration#WINDOWING_MODE_UNDEFINED} then the stack will
     *                      inherit its parent's windowing mode.
     * @param activityType The activityType the stack should be created in. If
     *                     {@link WindowConfiguration#ACTIVITY_TYPE_UNDEFINED} then the stack will
     *                     be created in {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}.
     * @param onTop If true the stack will be created at the top of the display, else at the bottom.
     * @param info The started activity info.
     * @param intent The intent that started this task.
     * @param createdByOrganizer @{code true} if this is created by task organizer, @{code false}
     *                          otherwise.
     * @return The newly created stack.
     */
    ActivityStack createStack(int windowingMode, int activityType, boolean onTop, ActivityInfo info,
            Intent intent, boolean createdByOrganizer) {
        if (mDisplayContent.mSingleTaskInstance && getStackCount() > 0) {
            // Create stack on default display instead since this display can only contain 1 stack.
            // TODO: Kinda a hack, but better that having the decision at each call point. Hoping
            // this goes away once ActivityView is no longer using virtual displays.
            return mRootWindowContainer.getDefaultTaskDisplayArea().createStack(
                    windowingMode, activityType, onTop, info, intent, createdByOrganizer);
        }

        if (activityType == ACTIVITY_TYPE_UNDEFINED && !createdByOrganizer) {
            // Can't have an undefined stack type yet...so re-map to standard. Anyone that wants
            // anything else should be passing it in anyways...except for the task organizer.
            activityType = ACTIVITY_TYPE_STANDARD;
        }

        if (activityType != ACTIVITY_TYPE_STANDARD && activityType != ACTIVITY_TYPE_UNDEFINED) {
            // For now there can be only one stack of a particular non-standard activity type on a
            // display. So, get that ignoring whatever windowing mode it is currently in.
            ActivityStack stack = getStack(WINDOWING_MODE_UNDEFINED, activityType);
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

        final int stackId = getNextStackId();
        return createStackUnchecked(windowingMode, activityType, stackId, onTop, info, intent,
                createdByOrganizer);
    }

    /** @return the root task to create the next task in. */
    private Task updateLaunchRootTask(int windowingMode) {
        if (!isSplitScreenWindowingMode(windowingMode)) {
            // Only split-screen windowing modes can do this currently...
            return null;
        }
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final Task t = getStackAt(i);
            if (!t.mCreatedByOrganizer || t.getRequestedOverrideWindowingMode() != windowingMode) {
                continue;
            }
            // If not already set, pick a launch root which is not the one we are launching into.
            if (mLaunchRootTask == null) {
                for (int j = 0, n = getStackCount(); j < n; ++j) {
                    final Task tt = getStackAt(j);
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
    ActivityStack createStackUnchecked(int windowingMode, int activityType, int stackId,
            boolean onTop, ActivityInfo info, Intent intent, boolean createdByOrganizer) {
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

        final ActivityStack stack = new ActivityStack(mAtmService, stackId, activityType,
                info, intent, createdByOrganizer);
        if (launchRootTask != null) {
            launchRootTask.addChild(stack, onTop ? POSITION_TOP : POSITION_BOTTOM);
            if (onTop) {
                positionStackAtTop((ActivityStack) launchRootTask, false /* includingParents */);
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
    ActivityStack getFocusedStack() {
        if (mPreferredTopFocusableStack != null) {
            return mPreferredTopFocusableStack;
        }

        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (stack.isFocusableAndVisible()) {
                return stack;
            }
        }

        return null;
    }

    ActivityStack getNextFocusableStack(ActivityStack currentFocus, boolean ignoreCurrent) {
        final int currentWindowingMode = currentFocus != null
                ? currentFocus.getWindowingMode() : WINDOWING_MODE_UNDEFINED;

        ActivityStack candidate = null;
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack stack = getStackAt(i);
            if (ignoreCurrent && stack == currentFocus) {
                continue;
            }
            if (!stack.isFocusableAndVisible()) {
                continue;
            }

            if (currentWindowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                    && candidate == null && stack.inSplitScreenPrimaryWindowingMode()) {
                // If the currently focused stack is in split-screen secondary we save off the
                // top primary split-screen stack as a candidate for focus because we might
                // prefer focus to move to an other stack to avoid primary split-screen stack
                // overlapping with a fullscreen stack when a fullscreen stack is higher in z
                // than the next split-screen stack. Assistant stack, I am looking at you...
                // We only move the focus to the primary-split screen stack if there isn't a
                // better alternative.
                candidate = stack;
                continue;
            }
            if (candidate != null && stack.inSplitScreenSecondaryWindowingMode()) {
                // Use the candidate stack since we are now at the secondary split-screen.
                return candidate;
            }
            return stack;
        }
        return candidate;
    }

    ActivityRecord getFocusedActivity() {
        final ActivityStack focusedStack = getFocusedStack();
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

    ActivityStack getLastFocusedStack() {
        return mLastFocusedStack;
    }

    boolean allResumedActivitiesComplete() {
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityRecord r = getStackAt(stackNdx).getResumedActivity();
            if (r != null && !r.isState(RESUMED)) {
                return false;
            }
        }
        final ActivityStack currentFocusedStack = getFocusedStack();
        if (ActivityTaskManagerDebugConfig.DEBUG_STACK) {
            Slog.d(TAG_STACK, "allResumedActivitiesComplete: mLastFocusedStack changing from="
                    + mLastFocusedStack + " to=" + currentFocusedStack);
        }
        mLastFocusedStack = currentFocusedStack;
        return true;
    }

    /**
     * Pause all activities in either all of the stacks or just the back stacks. This is done before
     * resuming a new activity and to make sure that previously active activities are
     * paused in stacks that are no longer visible or in pinned windowing mode. This does not
     * pause activities in visible stacks, so if an activity is launched within the same stack/task,
     * then we should explicitly pause that stack's top activity.
     * @param userLeaving Passed to pauseActivity() to indicate whether to call onUserLeaving().
     * @param resuming The resuming activity.
     * @return {@code true} if any activity was paused as a result of this call.
     */
    boolean pauseBackStacks(boolean userLeaving, ActivityRecord resuming) {
        boolean someActivityPaused = false;
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            final ActivityRecord resumedActivity = stack.getResumedActivity();
            if (resumedActivity != null
                    && (stack.getVisibility(resuming) != STACK_VISIBILITY_VISIBLE
                    || !stack.isTopActivityFocusable())) {
                if (DEBUG_STATES) {
                    Slog.d(TAG_STATES, "pauseBackStacks: stack=" + stack
                            + " mResumedActivity=" + resumedActivity);
                }
                someActivityPaused |= stack.startPausingLocked(userLeaving, false /* uiSleeping*/,
                        resuming);
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
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            if (!r.hasCompatibleActivityType(stack) && stack.isLeafTask()) {
                if (DEBUG_TASKS) {
                    Slog.d(TAG_TASKS, "Skipping stack: (mismatch activity/stack) " + stack);
                }
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

    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(int... windowingModes) {
        if (windowingModes == null || windowingModes.length == 0) {
            return;
        }

        // Collect the stacks that are necessary to be removed instead of performing the removal
        // by looping mStacks, so that we don't miss any stacks after the stack size changed or
        // stacks reordered.
        final ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = windowingModes.length - 1; j >= 0; --j) {
            final int windowingMode = windowingModes[j];
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
                if (!stack.isActivityTypeStandardOrUndefined()) {
                    continue;
                }
                if (stack.getWindowingMode() != windowingMode) {
                    continue;
                }
                stacks.add(stack);
            }
        }

        for (int i = stacks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mStackSupervisor.removeStack(stacks.get(i));
        }
    }

    void removeStacksWithActivityTypes(int... activityTypes) {
        if (activityTypes == null || activityTypes.length == 0) {
            return;
        }

        // Collect the stacks that are necessary to be removed instead of performing the removal
        // by looping mStacks, so that we don't miss any stacks after the stack size changed or
        // stacks reordered.
        final ArrayList<ActivityStack> stacks = new ArrayList<>();
        for (int j = activityTypes.length - 1; j >= 0; --j) {
            final int activityType = activityTypes[j];
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
                // Collect the root tasks that are currently being organized.
                if (stack.mCreatedByOrganizer) {
                    for (int k = stack.getChildCount() - 1; k >= 0; --k) {
                        final ActivityStack childStack = (ActivityStack) stack.getChildAt(k);
                        if (childStack.getActivityType() == activityType) {
                            stacks.add(childStack);
                        }
                    }
                } else if (stack.getActivityType() == activityType) {
                    stacks.add(stack);
                }
            }
        }

        for (int i = stacks.size() - 1; i >= 0; --i) {
            mRootWindowContainer.mStackSupervisor.removeStack(stacks.get(i));
        }
    }

    void onSplitScreenModeDismissed() {
        onSplitScreenModeDismissed(null /* toTop */);
    }

    void onSplitScreenModeDismissed(ActivityStack toTop) {
        mAtmService.deferWindowLayout();
        try {
            mLaunchRootTask = null;
            moveSplitScreenTasksToFullScreen();
        } finally {
            final ActivityStack topFullscreenStack = toTop != null
                    ? toTop : getTopStackInWindowingMode(WINDOWING_MODE_FULLSCREEN);
            final ActivityStack homeStack = getOrCreateRootHomeTask();
            if (homeStack != null && ((topFullscreenStack != null && !isTopStack(homeStack))
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
     * @param windowingMode The windowing mode we are checking support for.
     * @param supportsMultiWindow If we should consider support for multi-window mode in general.
     * @param supportsSplitScreen If we should consider support for split-screen multi-window.
     * @param supportsFreeform If we should consider support for freeform multi-window.
     * @param supportsPip If we should consider support for picture-in-picture mutli-window.
     * @param activityType The activity type under consideration.
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

        final int displayWindowingMode = getWindowingMode();
        if (windowingMode == WINDOWING_MODE_SPLIT_SCREEN_PRIMARY
                || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
            return supportsSplitScreen
                    && WindowConfiguration.supportSplitScreenWindowingMode(activityType)
                    // Freeform windows and split-screen windows don't mix well, so prevent
                    // split windowing modes on freeform displays.
                    && displayWindowingMode != WINDOWING_MODE_FREEFORM;
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
     * @param r The ActivityRecord in question.
     * @param options Options to start with.
     * @param task The task within-which the activity would start.
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
     * Check that the requested windowing-mode is appropriate for the specified task and/or activity
     * on this display.
     *
     * @param windowingMode The windowing-mode to validate.
     * @param r The {@link ActivityRecord} to check against.
     * @param task The {@link Task} to check against.
     * @param activityType An activity type.
     * @return The provided windowingMode or the closest valid mode which is appropriate.
     */
    int validateWindowingMode(int windowingMode, @Nullable ActivityRecord r, @Nullable Task task,
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

        final boolean inSplitScreenMode = isSplitScreenModeActivated();
        if (!inSplitScreenMode
                && windowingMode == WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY) {
            // Switch to the display's windowing mode if we are not in split-screen mode and we are
            // trying to launch in split-screen secondary.
            windowingMode = WINDOWING_MODE_UNDEFINED;
        } else if (inSplitScreenMode && (windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_UNDEFINED)
                && supportsSplitScreen) {
            windowingMode = WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
        }

        if (windowingMode != WINDOWING_MODE_UNDEFINED
                && isWindowingModeSupported(windowingMode, supportsMultiWindow, supportsSplitScreen,
                supportsFreeform, supportsPip, activityType)) {
            return windowingMode;
        }
        return WINDOWING_MODE_UNDEFINED;
    }

    boolean isTopStack(ActivityStack stack) {
        return stack == getTopStack();
    }

    boolean isTopNotPinnedStack(ActivityStack stack) {
        for (int i = getStackCount() - 1; i >= 0; --i) {
            final ActivityStack current = getStackAt(i);
            if (!current.inPinnedWindowingMode()) {
                return current == stack;
            }
        }
        return false;
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
        final ActivityStack focusedStack = getFocusedStack();
        if (focusedStack != null) {
            topRunning = focusedStack.topRunningActivity();
        }

        // Look in other focusable stacks.
        if (topRunning == null) {
            for (int i = getStackCount() - 1; i >= 0; --i) {
                final ActivityStack stack = getStackAt(i);
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
                && mRootWindowContainer.mStackSupervisor.getKeyguardController()
                .isKeyguardLocked()
                && !topRunning.canShowWhenLocked()) {
            return null;
        }

        return topRunning;
    }

    protected int getStackCount() {
        return mChildren.size();
    }

    protected ActivityStack getStackAt(int index) {
        return mChildren.get(index);
    }

    /**
     * Returns the existing home stack or creates and returns a new one if it should exist for the
     * display.
     */
    @Nullable
    ActivityStack getOrCreateRootHomeTask() {
        ActivityStack homeTask = getRootHomeTask();
        if (homeTask == null && mDisplayContent.supportsSystemDecorations()
                && !mDisplayContent.isUntrustedVirtualDisplay()) {
            homeTask = createStack(WINDOWING_MODE_UNDEFINED, ACTIVITY_TYPE_HOME, false /* onTop */);
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
    ActivityStack getTopStackInWindowingMode(int windowingMode) {
        return getStack(windowingMode, ACTIVITY_TYPE_UNDEFINED);
    }

    void moveHomeStackToFront(String reason) {
        final ActivityStack homeStack = getOrCreateRootHomeTask();
        if (homeStack != null) {
            homeStack.moveToFront(reason);
        }
    }

    /**
     * Moves the focusable home activity to top. If there is no such activity, the home stack will
     * still move to top.
     */
    void moveHomeActivityToTop(String reason) {
        final ActivityRecord top = getHomeActivity();
        if (top == null) {
            moveHomeStackToFront(reason);
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
        final ActivityStack homeStack = getRootHomeTask();
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
     * Generally used in conjunction with {@link #moveStackBehindStack}.
     */
    // TODO(b/151575894): Remove special stack movement methods.
    void moveStackBehindBottomMostVisibleStack(ActivityStack stack) {
        if (stack.shouldBeVisible(null)) {
            // Skip if the stack is already visible
            return;
        }

        final boolean isRootTask = stack.isRootTask();
        if (isRootTask) {
            // Move the stack to the bottom to not affect the following visibility checks
            positionStackAtBottom(stack);
        } else {
            stack.getParent().positionChildAt(POSITION_BOTTOM, stack, false /* includingParents */);
        }

        // Find the next position where the stack should be placed
        final int numStacks = isRootTask ? getStackCount() : stack.getParent().getChildCount();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final ActivityStack s = isRootTask ? getStackAt(stackNdx)
                    : (ActivityStack) stack.getParent().getChildAt(stackNdx);
            if (s == stack) {
                continue;
            }
            final int winMode = s.getWindowingMode();
            final boolean isValidWindowingMode = winMode == WINDOWING_MODE_FULLSCREEN
                    || winMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
            if (s.shouldBeVisible(null) && isValidWindowingMode) {
                // Move the provided stack to behind this stack
                final int position = Math.max(0, stackNdx - 1);
                if (isRootTask) {
                    positionStackAt(stack, position);
                } else {
                    stack.getParent().positionChildAt(position, stack, false /*includingParents */);
                }
                break;
            }
        }
    }

    /**
     * Moves the {@param stack} behind the given {@param behindStack} if possible. If
     * {@param behindStack} is not currently in the display, then then the stack is moved to the
     * back. Generally used in conjunction with {@link #moveStackBehindBottomMostVisibleStack}.
     */
    void moveStackBehindStack(ActivityStack stack, ActivityStack behindStack) {
        if (behindStack == null || behindStack == stack) {
            return;
        }

        final WindowContainer parent = stack.getParent();
        if (parent == null || parent != behindStack.getParent()) {
            return;
        }

        // Note that positionChildAt will first remove the given stack before inserting into the
        // list, so we need to adjust the insertion index to account for the removed index
        // TODO: Remove this logic when WindowContainer.positionChildAt() is updated to adjust the
        //       position internally
        final int stackIndex = parent.mChildren.indexOf(stack);
        final int behindStackIndex = parent.mChildren.indexOf(behindStack);
        final int insertIndex = stackIndex <= behindStackIndex
                ? behindStackIndex - 1 : behindStackIndex;
        final int position = Math.max(0, insertIndex);
        if (stack.isRootTask()) {
            positionStackAt(stack, position);
        } else {
            parent.positionChildAt(position, stack, false /* includingParents */);
        }
    }

    boolean hasPinnedTask() {
        return getRootPinnedTask() != null;
    }

    /**
     * @return the stack currently above the {@param stack}. Can be null if the {@param stack} is
     *         already top-most.
     */
    static ActivityStack getStackAbove(ActivityStack stack) {
        final WindowContainer wc = stack.getParent();
        final int index = wc.mChildren.indexOf(stack) + 1;
        return (index < wc.mChildren.size()) ? (ActivityStack) wc.mChildren.get(index) : null;
    }

    /** Returns true if the stack in the windowing mode is visible. */
    boolean isStackVisible(int windowingMode) {
        final ActivityStack stack = getTopStackInWindowingMode(windowingMode);
        return stack != null && stack.isVisible();
    }

    void removeStack(ActivityStack stack) {
        removeChild(stack);
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
    void registerStackOrderChangedListener(OnStackOrderChangedListener listener) {
        if (!mStackOrderChangedCallbacks.contains(listener)) {
            mStackOrderChangedCallbacks.add(listener);
        }
    }

    /**
     * Removes a previously registered stack order change listener.
     */
    void unregisterStackOrderChangedListener(OnStackOrderChangedListener listener) {
        mStackOrderChangedCallbacks.remove(listener);
    }

    /**
     * Notifies of a stack order change
     * @param stack The stack which triggered the order change
     */
    void onStackOrderChanged(ActivityStack stack) {
        for (int i = mStackOrderChangedCallbacks.size() - 1; i >= 0; i--) {
            mStackOrderChangedCallbacks.get(i).onStackOrderChanged(stack);
        }
    }

    @Override
    boolean canCreateRemoteAnimationTarget() {
        return true;
    }

    /**
     * Callback for when the order of the stacks in the display changes.
     */
    interface OnStackOrderChangedListener {
        void onStackOrderChanged(ActivityStack stack);
    }

    void ensureActivitiesVisible(ActivityRecord starting, int configChanges,
            boolean preserveWindows, boolean notifyClients) {
        for (int stackNdx = getStackCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getStackAt(stackNdx);
            stack.ensureActivitiesVisible(starting, configChanges, preserveWindows,
                    notifyClients);
        }
    }

    void prepareFreezingTaskBounds() {
        for (int stackNdx = getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getChildAt(stackNdx);
            stack.prepareFreezingTaskBounds();
        }
    }

    /**
     * Removes the stacks in the node applying the content removal node from the display.
     * @return last reparented stack, or {@code null} if the stacks had to be destroyed.
     */
    ActivityStack remove() {
        mPreferredTopFocusableStack = null;
        // TODO(b/153090332): Allow setting content removal mode per task display area
        final boolean destroyContentOnRemoval = mDisplayContent.shouldDestroyContentOnRemove();
        final TaskDisplayArea toDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        ActivityStack lastReparentedStack = null;

        // Stacks could be reparented from the removed display area to other display area. After
        // reparenting the last stack of the removed display area, the display area becomes ready to
        // be released (no more ActivityStack-s). But, we cannot release it at that moment or the
        // related WindowContainer will also be removed. So, we set display area as removed after
        // reparenting stack finished.
        // Keep the order from bottom to top.
        int numStacks = getStackCount();
        for (int stackNdx = 0; stackNdx < numStacks; stackNdx++) {
            final ActivityStack stack = getStackAt(stackNdx);
            // Always finish non-standard type stacks.
            if (destroyContentOnRemoval || !stack.isActivityTypeStandardOrUndefined()) {
                stack.finishAllActivitiesImmediately();
            } else {
                // If default display is in split-window mode, set windowing mode of the
                // stack to split-screen secondary. Otherwise, set the windowing mode to
                // undefined by default to let stack inherited the windowing mode from the
                // new display.
                final int windowingMode = toDisplayArea.isSplitScreenModeActivated()
                        ? WINDOWING_MODE_SPLIT_SCREEN_SECONDARY
                        : WINDOWING_MODE_UNDEFINED;
                stack.reparent(toDisplayArea, true /* onTop */);
                stack.setWindowingMode(windowingMode);
                lastReparentedStack = stack;
            }
            // Stacks may be removed from this display. Ensure each stack will be processed
            // and the loop will end.
            stackNdx -= numStacks - getStackCount();
            numStacks = getStackCount();
        }
        mRemoved = true;

        return lastReparentedStack;
    }


    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "TaskDisplayArea " + getName());
        if (mPreferredTopFocusableStack != null) {
            pw.println(prefix + "  mPreferredTopFocusableStack=" + mPreferredTopFocusableStack);
        }
        if (mLastFocusedStack != null) {
            pw.println(prefix + "  mLastFocusedStack=" + mLastFocusedStack);
        }
        pw.println(prefix + "  Application tokens in top down Z order:");
        for (int stackNdx = getChildCount() - 1; stackNdx >= 0; --stackNdx) {
            final ActivityStack stack = getChildAt(stackNdx);
            stack.dump(pw, prefix + "    ", dumpAll);
        }
    }
}
