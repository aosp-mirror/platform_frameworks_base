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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.window.WindowOrganizer.DisplayAreaOrganizer.FEATURE_TASK_CONTAINER;

import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ADD_REMOVE;
import static com.android.server.wm.ProtoLogGroup.WM_DEBUG_ORIENTATION;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.util.Slog;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.protolog.common.ProtoLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Window container class that contains all containers on this display relating to Apps.
 * I.e Activities.
 */
final class TaskContainers extends DisplayArea<ActivityStack> {
    private DisplayContent mDisplayContent;
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

    TaskContainers(DisplayContent displayContent, WindowManagerService service) {
        super(service, Type.ANY, "TaskContainers", FEATURE_TASK_CONTAINER);
        mDisplayContent = displayContent;
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
        addStackReferenceIfNeeded(stack);
        position = findPositionForStack(position, stack, true /* adding */);

        super.addChild(stack, position);
        mDisplayContent.mAtmService.updateSleepIfNeededLocked();

        // The reparenting case is handled in WindowContainer.
        if (!stack.mReparenting) {
            mDisplayContent.setLayoutNeeded();
        }
    }

    @Override
    protected void removeChild(ActivityStack stack) {
        super.removeChild(stack);
        mDisplayContent.onStackRemoved(stack);
        mDisplayContent.mAtmService.updateSleepIfNeededLocked();
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
     * When stack is added or repositioned, find a proper position for it.
     * This will make sure that pinned stack always stays on top.
     * @param requestedPosition Position requested by caller.
     * @param stack Stack to be added or positioned.
     * @param adding Flag indicates whether we're adding a new stack or positioning an existing.
     * @return The proper position for the stack.
     */
    private int findPositionForStack(int requestedPosition, ActivityStack stack,
            boolean adding) {
        if (stack.isActivityTypeDream()) {
            return POSITION_TOP;
        }

        if (stack.inPinnedWindowingMode()) {
            return POSITION_TOP;
        }

        final int topChildPosition = mChildren.size() - 1;
        int belowAlwaysOnTopPosition = POSITION_BOTTOM;
        for (int i = topChildPosition; i >= 0; --i) {
            // Since a stack could be repositioned while being one of the child, return
            // current index if that's the same stack we are positioning and it is always on
            // top.
            final boolean sameStack = mDisplayContent.getStacks().get(i) == stack;
            if ((sameStack && stack.isAlwaysOnTop())
                    || (!sameStack && !mDisplayContent.getStacks().get(i).isAlwaysOnTop())) {
                belowAlwaysOnTopPosition = i;
                break;
            }
        }

        // The max possible position we can insert the stack at.
        int maxPosition = POSITION_TOP;
        // The min possible position we can insert the stack at.
        int minPosition = POSITION_BOTTOM;

        if (stack.isAlwaysOnTop()) {
            if (mDisplayContent.hasPinnedTask()) {
                // Always-on-top stacks go below the pinned stack.
                maxPosition = mDisplayContent.getStacks().indexOf(mRootPinnedTask) - 1;
            }
            // Always-on-top stacks need to be above all other stacks.
            minPosition = belowAlwaysOnTopPosition
                    != POSITION_BOTTOM ? belowAlwaysOnTopPosition : topChildPosition;
        } else {
            // Other stacks need to be below the always-on-top stacks.
            maxPosition = belowAlwaysOnTopPosition
                    != POSITION_BOTTOM ? belowAlwaysOnTopPosition : 0;
        }

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

        int prevPosition = mDisplayContent.getStacks().indexOf(stack);
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
        if (mDisplayContent.isStackVisible(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY)) {
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
}
