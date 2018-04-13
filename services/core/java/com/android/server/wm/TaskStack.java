/*
 * Copyright (C) 2013 The Android Open Source Project
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

import static android.app.ActivityManager.SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT;
import static android.app.ActivityManager.SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.PINNED_WINDOWING_MODE_ELEVATION_IN_DIP;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.DENSITY_DPI_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.DOCKED_BOTTOM;
import static android.view.WindowManager.DOCKED_INVALID;
import static android.view.WindowManager.DOCKED_LEFT;
import static android.view.WindowManager.DOCKED_RIGHT;
import static android.view.WindowManager.DOCKED_TOP;
import static com.android.server.wm.DragResizeMode.DRAG_RESIZE_MODE_DOCKED_DIVIDER;
import static com.android.server.wm.StackProto.ADJUSTED_BOUNDS;
import static com.android.server.wm.StackProto.ADJUSTED_FOR_IME;
import static com.android.server.wm.StackProto.ADJUST_DIVIDER_AMOUNT;
import static com.android.server.wm.StackProto.ADJUST_IME_AMOUNT;
import static com.android.server.wm.StackProto.ANIMATING_BOUNDS;
import static com.android.server.wm.StackProto.ANIMATION_BACKGROUND_SURFACE_IS_DIMMING;
import static com.android.server.wm.StackProto.BOUNDS;
import static com.android.server.wm.StackProto.DEFER_REMOVAL;
import static com.android.server.wm.StackProto.FILLS_PARENT;
import static com.android.server.wm.StackProto.ID;
import static com.android.server.wm.StackProto.MINIMIZE_AMOUNT;
import static com.android.server.wm.StackProto.TASKS;
import static com.android.server.wm.StackProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.CallSuper;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;
import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.internal.policy.DividerSnapAlgorithm.SnapTarget;
import com.android.internal.policy.DockedDividerUtils;
import com.android.server.EventLogTags;
import java.io.PrintWriter;

public class TaskStack extends WindowContainer<Task> implements
        BoundsAnimationTarget {
    /** Minimum size of an adjusted stack bounds relative to original stack bounds. Used to
     * restrict IME adjustment so that a min portion of top stack remains visible.*/
    private static final float ADJUSTED_STACK_FRACTION_MIN = 0.3f;

    /** Dimming amount for non-focused stack when stacks are IME-adjusted. */
    private static final float IME_ADJUST_DIM_AMOUNT = 0.25f;

    /** Unique identifier */
    final int mStackId;

    /** The display this stack sits under. */
    // TODO: Track parent marks like this in WindowContainer.
    private DisplayContent mDisplayContent;

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();
    private Rect mTmpRect3 = new Rect();

    /** Stack bounds adjusted to screen content area (taking into account IM windows, etc.) */
    private final Rect mAdjustedBounds = new Rect();

    /**
     * Fully adjusted IME bounds. These are different from {@link #mAdjustedBounds} because they
     * represent the state when the animation has ended.
     */
    private final Rect mFullyAdjustedImeBounds = new Rect();

    // Device rotation as of the last time {@link #mBounds} was set.
    private int mRotation;

    /** Density as of last time {@link #mBounds} was set. */
    private int mDensity;

    private SurfaceControl mAnimationBackgroundSurface;
    private boolean mAnimationBackgroundSurfaceIsShown = false;

    /** The particular window with an Animation with non-zero background color. */
    private WindowStateAnimator mAnimationBackgroundAnimator;

    /** Application tokens that are exiting, but still on screen for animations. */
    final AppTokenList mExitingAppTokens = new AppTokenList();
    final AppTokenList mTmpAppTokens = new AppTokenList();

    /** Detach this stack from its display when animation completes. */
    // TODO: maybe tie this to WindowContainer#removeChild some how...
    boolean mDeferRemoval;

    private final Rect mTmpAdjustedBounds = new Rect();
    private boolean mAdjustedForIme;
    private boolean mImeGoingAway;
    private WindowState mImeWin;
    private float mMinimizeAmount;
    private float mAdjustImeAmount;
    private float mAdjustDividerAmount;
    private final int mDockedStackMinimizeThickness;

    // If this is true, we are in the bounds animating mode. The task will be down or upscaled to
    // perfectly fit the region it would have been cropped to. We may also avoid certain logic we
    // would otherwise apply while resizing, while resizing in the bounds animating mode.
    private boolean mBoundsAnimating = false;
    // Set when an animation has been requested but has not yet started from the UI thread. This is
    // cleared when the animation actually starts.
    private boolean mBoundsAnimatingRequested = false;
    private boolean mBoundsAnimatingToFullscreen = false;
    private boolean mCancelCurrentBoundsAnimation = false;
    private Rect mBoundsAnimationTarget = new Rect();
    private Rect mBoundsAnimationSourceHintBounds = new Rect();

    // Temporary storage for the new bounds that should be used after the configuration change.
    // Will be cleared once the client retrieves the new bounds via getBoundsForNewConfiguration().
    private final Rect mBoundsAfterRotation = new Rect();

    Rect mPreAnimationBounds = new Rect();

    private Dimmer mDimmer = new Dimmer(this);

    /**
     * For {@link #prepareSurfaces}.
     */
    final Rect mTmpDimBoundsRect = new Rect();
    private final Point mLastSurfaceSize = new Point();

    private final AnimatingAppWindowTokenRegistry mAnimatingAppWindowTokenRegistry =
            new AnimatingAppWindowTokenRegistry();

    TaskStack(WindowManagerService service, int stackId, StackWindowController controller) {
        super(service);
        mStackId = stackId;
        setController(controller);
        mDockedStackMinimizeThickness = service.mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_minimize_thickness);
        EventLog.writeEvent(EventLogTags.WM_STACK_CREATED, stackId);
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    Task findHomeTask() {
        if (!isActivityTypeHome() || mChildren.isEmpty()) {
            return null;
        }
        return mChildren.get(mChildren.size() - 1);
    }

    /**
     * Set the bounds of the stack and its containing tasks.
     * @param stackBounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param taskBounds Bounds for individual tasks, keyed by task id.
     * @param taskTempInsetBounds Inset bounds for individual tasks, keyed by task id.
     * @return True if the stack bounds was changed.
     * */
    boolean setBounds(
            Rect stackBounds, SparseArray<Rect> taskBounds, SparseArray<Rect> taskTempInsetBounds) {
        setBounds(stackBounds);

        // Update bounds of containing tasks.
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mChildren.get(taskNdx);
            task.setBounds(taskBounds.get(task.mTaskId), false /* forced */);
            task.setTempInsetBounds(taskTempInsetBounds != null ?
                    taskTempInsetBounds.get(task.mTaskId) : null);
        }
        return true;
    }

    void prepareFreezingTaskBounds() {
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mChildren.get(taskNdx);
            task.prepareFreezingBounds();
        }
    }

    /**
     * Overrides the adjusted bounds, i.e. sets temporary layout bounds which are different from
     * the normal task bounds.
     *
     * @param bounds The adjusted bounds.
     */
    private void setAdjustedBounds(Rect bounds) {
        if (mAdjustedBounds.equals(bounds) && !isAnimatingForIme()) {
            return;
        }

        mAdjustedBounds.set(bounds);
        final boolean adjusted = !mAdjustedBounds.isEmpty();
        Rect insetBounds = null;
        if (adjusted && isAdjustedForMinimizedDockedStack()) {
            insetBounds = getRawBounds();
        } else if (adjusted && mAdjustedForIme) {
            if (mImeGoingAway) {
                insetBounds = getRawBounds();
            } else {
                insetBounds = mFullyAdjustedImeBounds;
            }
        }
        alignTasksToAdjustedBounds(adjusted ? mAdjustedBounds : getRawBounds(), insetBounds);
        mDisplayContent.setLayoutNeeded();

        updateSurfaceBounds();
    }

    private void alignTasksToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds) {
        if (matchParentBounds()) {
            return;
        }

        final boolean alignBottom = mAdjustedForIme && getDockSide() == DOCKED_TOP;

        // Update bounds of containing tasks.
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mChildren.get(taskNdx);
            task.alignToAdjustedBounds(adjustedBounds, tempInsetBounds, alignBottom);
        }
    }

    private void updateAnimationBackgroundBounds() {
        if (mAnimationBackgroundSurface == null) {
            return;
        }
        getRawBounds(mTmpRect);
        final Rect stackBounds = getBounds();
        getPendingTransaction()
                .setSize(mAnimationBackgroundSurface, mTmpRect.width(), mTmpRect.height())
                .setPosition(mAnimationBackgroundSurface, mTmpRect.left - stackBounds.left,
                        mTmpRect.top - stackBounds.top);
        scheduleAnimation();
    }

    private void hideAnimationSurface() {
        if (mAnimationBackgroundSurface == null) {
            return;
        }
        getPendingTransaction().hide(mAnimationBackgroundSurface);
        mAnimationBackgroundSurfaceIsShown = false;
        scheduleAnimation();
    }

    private void showAnimationSurface(float alpha) {
        if (mAnimationBackgroundSurface == null) {
            return;
        }
        getPendingTransaction().setLayer(mAnimationBackgroundSurface, Integer.MIN_VALUE)
                .setAlpha(mAnimationBackgroundSurface, alpha)
                .show(mAnimationBackgroundSurface);
        mAnimationBackgroundSurfaceIsShown = true;
        scheduleAnimation();
    }

    @Override
    public int setBounds(Rect bounds) {
        return setBounds(getOverrideBounds(), bounds);
    }

    private int setBounds(Rect existing, Rect bounds) {
        int rotation = Surface.ROTATION_0;
        int density = DENSITY_DPI_UNDEFINED;
        if (mDisplayContent != null) {
            mDisplayContent.getBounds(mTmpRect);
            rotation = mDisplayContent.getDisplayInfo().rotation;
            density = mDisplayContent.getDisplayInfo().logicalDensityDpi;
        }

        if (equivalentBounds(existing, bounds) && mRotation == rotation) {
            return BOUNDS_CHANGE_NONE;
        }

        final int result = super.setBounds(bounds);

        if (mDisplayContent != null) {
            updateAnimationBackgroundBounds();
        }

        mRotation = rotation;
        mDensity = density;

        updateAdjustedBounds();

        updateSurfaceBounds();
        return result;
    }

    /** Bounds of the stack without adjusting for other factors in the system like visibility
     * of docked stack.
     * Most callers should be using {@link ConfigurationContainer#getOverrideBounds} as it take into
     * consideration other system factors. */
    void getRawBounds(Rect out) {
        out.set(getRawBounds());
    }

    Rect getRawBounds() {
        return super.getBounds();
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        if (matchParentBounds()
                || !inSplitScreenSecondaryWindowingMode()
                || mDisplayContent == null
                || mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null) {
            return true;
        }
        return false;
    }

    @Override
    public void getBounds(Rect bounds) {
        bounds.set(getBounds());
    }

    @Override
    public Rect getBounds() {
        if (useCurrentBounds()) {
            // If we're currently adjusting for IME or minimized docked stack, we use the adjusted
            // bounds; otherwise, no need to adjust the output bounds if fullscreen or the docked
            // stack is visible since it is already what we want to represent to the rest of the
            // system.
            if (!mAdjustedBounds.isEmpty()) {
                return mAdjustedBounds;
            } else {
                return super.getBounds();
            }
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return mDisplayContent.getBounds();
    }

    /**
     * Sets the bounds animation target bounds ahead of an animation.  This can't currently be done
     * in onAnimationStart() since that is started on the UiThread.
     */
    void setAnimationFinalBounds(Rect sourceHintBounds, Rect destBounds, boolean toFullscreen) {
        mBoundsAnimatingRequested = true;
        mBoundsAnimatingToFullscreen = toFullscreen;
        if (destBounds != null) {
            mBoundsAnimationTarget.set(destBounds);
        } else {
            mBoundsAnimationTarget.setEmpty();
        }
        if (sourceHintBounds != null) {
            mBoundsAnimationSourceHintBounds.set(sourceHintBounds);
        } else {
            mBoundsAnimationSourceHintBounds.setEmpty();
        }

        mPreAnimationBounds.set(getRawBounds());
    }

    /**
     * @return the final bounds for the bounds animation.
     */
    void getFinalAnimationBounds(Rect outBounds) {
        outBounds.set(mBoundsAnimationTarget);
    }

    /**
     * @return the final source bounds for the bounds animation.
     */
    void getFinalAnimationSourceHintBounds(Rect outBounds) {
        outBounds.set(mBoundsAnimationSourceHintBounds);
    }

    /**
     * @return the final animation bounds if the task stack is currently being animated, or the
     *         current stack bounds otherwise.
     */
    void getAnimationOrCurrentBounds(Rect outBounds) {
        if ((mBoundsAnimatingRequested || mBoundsAnimating) && !mBoundsAnimationTarget.isEmpty()) {
            getFinalAnimationBounds(outBounds);
            return;
        }
        getBounds(outBounds);
    }

    /** Bounds of the stack with other system factors taken into consideration. */
    public void getDimBounds(Rect out) {
        getBounds(out);
    }

    void updateDisplayInfo(Rect bounds) {
        if (mDisplayContent == null) {
            return;
        }

        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; --taskNdx) {
            mChildren.get(taskNdx).updateDisplayInfo(mDisplayContent);
        }
        if (bounds != null) {
            setBounds(bounds);
            return;
        } else if (matchParentBounds()) {
            setBounds(null);
            return;
        }

        mTmpRect2.set(getRawBounds());
        final int newRotation = mDisplayContent.getDisplayInfo().rotation;
        final int newDensity = mDisplayContent.getDisplayInfo().logicalDensityDpi;
        if (mRotation == newRotation && mDensity == newDensity) {
            setBounds(mTmpRect2);
        }

        // If the rotation or density didn't match, we'll update it in onConfigurationChanged.
    }

    /** @return true if bounds were updated to some non-empty value. */
    boolean updateBoundsAfterConfigChange() {
        if (mDisplayContent == null) {
            // If the stack is already detached we're not updating anything,
            // as it's going away soon anyway.
            return false;
        }

        if (inPinnedWindowingMode()) {
            getAnimationOrCurrentBounds(mTmpRect2);
            boolean updated = mDisplayContent.mPinnedStackControllerLocked.onTaskStackBoundsChanged(
                    mTmpRect2, mTmpRect3);
            if (updated) {
                mBoundsAfterRotation.set(mTmpRect3);

                // Once we've set the bounds based on the rotation of the old bounds in the new
                // orientation, clear the animation target bounds since they are obsolete, and
                // cancel any currently running animations
                mBoundsAnimationTarget.setEmpty();
                mBoundsAnimationSourceHintBounds.setEmpty();
                mCancelCurrentBoundsAnimation = true;
                return true;
            }
        }

        final int newRotation = getDisplayInfo().rotation;
        final int newDensity = getDisplayInfo().logicalDensityDpi;

        if (mRotation == newRotation && mDensity == newDensity) {
            // Nothing to do here as we already update the state in updateDisplayInfo.
            return false;
        }

        if (matchParentBounds()) {
            // Update stack bounds again since rotation changed since updateDisplayInfo().
            setBounds(null);
            // Return false since we don't need the client to resize.
            return false;
        }

        mTmpRect2.set(getRawBounds());
        mDisplayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (inSplitScreenPrimaryWindowingMode()) {
            repositionPrimarySplitScreenStackAfterRotation(mTmpRect2);
            snapDockedStackAfterRotation(mTmpRect2);
            final int newDockSide = getDockSide(mTmpRect2);

            // Update the dock create mode and clear the dock create bounds, these
            // might change after a rotation and the original values will be invalid.
            mService.setDockedStackCreateStateLocked(
                    (newDockSide == DOCKED_LEFT || newDockSide == DOCKED_TOP)
                            ? SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT
                            : SPLIT_SCREEN_CREATE_MODE_BOTTOM_OR_RIGHT,
                    null);
            mDisplayContent.getDockedDividerController().notifyDockSideChanged(newDockSide);
        }

        mBoundsAfterRotation.set(mTmpRect2);
        return true;
    }

    void getBoundsForNewConfiguration(Rect outBounds) {
        outBounds.set(mBoundsAfterRotation);
        mBoundsAfterRotation.setEmpty();
    }

    /**
     * Some primary split screen sides are not allowed by the policy. This method queries the policy
     * and moves the primary stack around if needed.
     *
     * @param inOutBounds the bounds of the primary stack to adjust
     */
    private void repositionPrimarySplitScreenStackAfterRotation(Rect inOutBounds) {
        int dockSide = getDockSide(inOutBounds);
        if (mDisplayContent.getDockedDividerController().canPrimaryStackDockTo(dockSide)) {
            return;
        }
        mDisplayContent.getBounds(mTmpRect);
        dockSide = DockedDividerUtils.invertDockSide(dockSide);
        switch (dockSide) {
            case DOCKED_LEFT:
                int movement = inOutBounds.left;
                inOutBounds.left -= movement;
                inOutBounds.right -= movement;
                break;
            case DOCKED_RIGHT:
                movement = mTmpRect.right - inOutBounds.right;
                inOutBounds.left += movement;
                inOutBounds.right += movement;
                break;
            case DOCKED_TOP:
                movement = inOutBounds.top;
                inOutBounds.top -= movement;
                inOutBounds.bottom -= movement;
                break;
            case DOCKED_BOTTOM:
                movement = mTmpRect.bottom - inOutBounds.bottom;
                inOutBounds.top += movement;
                inOutBounds.bottom += movement;
                break;
        }
    }

    /**
     * Snaps the bounds after rotation to the closest snap target for the docked stack.
     */
    private void snapDockedStackAfterRotation(Rect outBounds) {

        // Calculate the current position.
        final DisplayInfo displayInfo = mDisplayContent.getDisplayInfo();
        final int dividerSize = mDisplayContent.getDockedDividerController().getContentWidth();
        final int dockSide = getDockSide(outBounds);
        final int dividerPosition = DockedDividerUtils.calculatePositionForBounds(outBounds,
                dockSide, dividerSize);
        final int displayWidth = displayInfo.logicalWidth;
        final int displayHeight = displayInfo.logicalHeight;

        // Snap the position to a target.
        final int rotation = displayInfo.rotation;
        final int orientation = mDisplayContent.getConfiguration().orientation;
        mService.mPolicy.getStableInsetsLw(rotation, displayWidth, displayHeight,
                displayInfo.displayCutout, outBounds);
        final DividerSnapAlgorithm algorithm = new DividerSnapAlgorithm(
                mService.mContext.getResources(), displayWidth, displayHeight,
                dividerSize, orientation == Configuration.ORIENTATION_PORTRAIT, outBounds,
                getDockSide(), isMinimizedDockAndHomeStackResizable());
        final SnapTarget target = algorithm.calculateNonDismissingSnapTarget(dividerPosition);

        // Recalculate the bounds based on the position of the target.
        DockedDividerUtils.calculateBoundsForPosition(target.position, dockSide,
                outBounds, displayInfo.logicalWidth, displayInfo.logicalHeight,
                dividerSize);
    }

    // TODO: Checkout the call points of this method and the ones below to see how they can fit in WC.
    void addTask(Task task, int position) {
        addTask(task, position, task.showForAllUsers(), true /* moveParents */);
    }

    /**
     * Put a Task in this stack. Used for adding only.
     * When task is added to top of the stack, the entire branch of the hierarchy (including stack
     * and display) will be brought to top.
     * @param task The task to add.
     * @param position Target position to add the task to.
     * @param showForAllUsers Whether to show the task regardless of the current user.
     */
    void addTask(Task task, int position, boolean showForAllUsers, boolean moveParents) {
        final TaskStack currentStack = task.mStack;
        // TODO: We pass stack to task's constructor, but we still need to call this method.
        // This doesn't make sense, mStack will already be set equal to "this" at this point.
        if (currentStack != null && currentStack.mStackId != mStackId) {
            throw new IllegalStateException("Trying to add taskId=" + task.mTaskId
                    + " to stackId=" + mStackId
                    + ", but it is already attached to stackId=" + task.mStack.mStackId);
        }

        // Add child task.
        task.mStack = this;
        addChild(task, null);

        // Move child to a proper position, as some restriction for position might apply.
        positionChildAt(position, task, moveParents /* includingParents */, showForAllUsers);
    }

    @Override
    void positionChildAt(int position, Task child, boolean includingParents) {
        positionChildAt(position, child, includingParents, child.showForAllUsers());
    }

    /**
     * Overridden version of {@link TaskStack#positionChildAt(int, Task, boolean)}. Used in
     * {@link TaskStack#addTask(Task, int, boolean showForAllUsers, boolean)}, as it can receive
     * showForAllUsers param from {@link AppWindowToken} instead of {@link Task#showForAllUsers()}.
     */
    private void positionChildAt(int position, Task child, boolean includingParents,
            boolean showForAllUsers) {
        final int targetPosition = findPositionForTask(child, position, showForAllUsers,
                false /* addingNew */);
        super.positionChildAt(targetPosition, child, includingParents);

        // Log positioning.
        if (DEBUG_TASK_MOVEMENT)
            Slog.d(TAG_WM, "positionTask: task=" + this + " position=" + position);

        final int toTop = targetPosition == mChildren.size() - 1 ? 1 : 0;
        EventLog.writeEvent(EventLogTags.WM_TASK_MOVED, child.mTaskId, toTop, targetPosition);
    }

    // TODO: We should really have users as a window container in the hierarchy so that we don't
    // have to do complicated things like we are doing in this method.
    private int findPositionForTask(Task task, int targetPosition, boolean showForAllUsers,
            boolean addingNew) {
        final boolean canShowTask =
                showForAllUsers || mService.isCurrentProfileLocked(task.mUserId);

        final int stackSize = mChildren.size();
        int minPosition = 0;
        int maxPosition = addingNew ? stackSize : stackSize - 1;

        if (canShowTask) {
            minPosition = computeMinPosition(minPosition, stackSize);
        } else {
            maxPosition = computeMaxPosition(maxPosition);
        }

        // preserve POSITION_BOTTOM/POSITION_TOP positions if they are still valid.
        if (targetPosition == POSITION_BOTTOM && minPosition == 0) {
            return POSITION_BOTTOM;
        } else if (targetPosition == POSITION_TOP
                && maxPosition == (addingNew ? stackSize : stackSize - 1)) {
            return POSITION_TOP;
        }
        // Reset position based on minimum/maximum possible positions.
        return Math.min(Math.max(targetPosition, minPosition), maxPosition);
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    private int computeMinPosition(int minPosition, int size) {
        while (minPosition < size) {
            final Task tmpTask = mChildren.get(minPosition);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mService.isCurrentProfileLocked(tmpTask.mUserId);
            if (canShowTmpTask) {
                break;
            }
            minPosition++;
        }
        return minPosition;
    }

    /** Calculate the maximum possible position for a task that can't be shown to the user.
     *  The maximum position will be below all other tasks that can be shown.
     *  @param maxPosition The maximum position the caller is suggesting.
     *                  We will start adjusting down from here.
     */
    private int computeMaxPosition(int maxPosition) {
        while (maxPosition > 0) {
            final Task tmpTask = mChildren.get(maxPosition);
            final boolean canShowTmpTask =
                    tmpTask.showForAllUsers()
                            || mService.isCurrentProfileLocked(tmpTask.mUserId);
            if (!canShowTmpTask) {
                break;
            }
            maxPosition--;
        }
        return maxPosition;
    }

    /**
     * Delete a Task from this stack. If it is the last Task in the stack, move this stack to the
     * back.
     * @param task The Task to delete.
     */
    @Override
    void removeChild(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG_WM, "removeChild: task=" + task);

        super.removeChild(task);
        task.mStack = null;

        if (mDisplayContent != null) {
            if (mChildren.isEmpty()) {
                getParent().positionChildAt(POSITION_BOTTOM, this, false /* includingParents */);
            }
            mDisplayContent.setLayoutNeeded();
        }
        for (int appNdx = mExitingAppTokens.size() - 1; appNdx >= 0; --appNdx) {
            final AppWindowToken wtoken = mExitingAppTokens.get(appNdx);
            if (wtoken.getTask() == task) {
                wtoken.mIsExiting = false;
                mExitingAppTokens.remove(appNdx);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        final int prevWindowingMode = getWindowingMode();
        super.onConfigurationChanged(newParentConfig);

        // Only need to update surface size here since the super method will handle updating
        // surface position.
        updateSurfaceSize(getPendingTransaction());
        final int windowingMode = getWindowingMode();

        if (mDisplayContent == null || prevWindowingMode == windowingMode) {
            return;
        }
        mDisplayContent.onStackWindowingModeChanged(this);
        updateBoundsForWindowModeChange();
    }

    private void updateSurfaceBounds() {
        updateSurfaceSize(getPendingTransaction());
        updateSurfacePosition();
        scheduleAnimation();
    }

    /**
     * Calculate an amount by which to expand the stack bounds in each direction.
     * Used to make room for shadows in the pinned windowing mode.
     */
    int getStackOutset() {
        if (inPinnedWindowingMode()) {
            final DisplayMetrics displayMetrics = getDisplayContent().getDisplayMetrics();

            // We multiply by two to match the client logic for converting view elevation
            // to insets, as in {@link WindowManager.LayoutParams#setSurfaceInsets}
            return (int)Math.ceil(mService.dipToPixel(PINNED_WINDOWING_MODE_ELEVATION_IN_DIP,
                    displayMetrics) * 2);
        }
        return 0;
    }

    private void updateSurfaceSize(SurfaceControl.Transaction transaction) {
        if (mSurfaceControl == null) {
            return;
        }

        final Rect stackBounds = getBounds();
        int width = stackBounds.width();
        int height = stackBounds.height();

        final int outset = getStackOutset();
        width += 2*outset;
        height += 2*outset;

        if (width == mLastSurfaceSize.x && height == mLastSurfaceSize.y) {
            return;
        }
        transaction.setSize(mSurfaceControl, width, height);
        mLastSurfaceSize.set(width, height);
    }

    @Override
    void onDisplayChanged(DisplayContent dc) {
        if (mDisplayContent != null) {
            throw new IllegalStateException("onDisplayChanged: Already attached");
        }

        mDisplayContent = dc;

        updateBoundsForWindowModeChange();
        mAnimationBackgroundSurface = makeChildSurface(null).setColorLayer(true)
            .setName("animation background stackId=" + mStackId)
            .build();

        super.onDisplayChanged(dc);
    }

    private void updateBoundsForWindowModeChange() {
        Rect bounds = null;
        final boolean inSplitScreenPrimary = inSplitScreenPrimaryWindowingMode();
        final TaskStack splitScreenStack =
                mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (inSplitScreenPrimary || (splitScreenStack != null
                && inSplitScreenSecondaryWindowingMode() && !splitScreenStack.fillsParent())) {
            // The existence of a docked stack affects the size of other static stack created since
            // the docked stack occupies a dedicated region on screen, but only if the dock stack is
            // not fullscreen. If it's fullscreen, it means that we are in the transition of
            // dismissing it, so we must not resize this stack.
            bounds = new Rect();
            mDisplayContent.getBounds(mTmpRect);
            mTmpRect2.setEmpty();
            if (splitScreenStack != null) {
                splitScreenStack.getRawBounds(mTmpRect2);
            }
            final boolean dockedOnTopOrLeft = mService.mDockedStackCreateMode
                    == SPLIT_SCREEN_CREATE_MODE_TOP_OR_LEFT;
            getStackDockedModeBounds(mTmpRect, bounds, mTmpRect2,
                    mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);
        } else if (inPinnedWindowingMode()) {
            // Update the bounds based on any changes to the display info
            getAnimationOrCurrentBounds(mTmpRect2);
            if (mDisplayContent.mPinnedStackControllerLocked.onTaskStackBoundsChanged(
                    mTmpRect2, mTmpRect3)) {
                bounds = new Rect(mTmpRect3);
            }
        }

        if (inSplitScreenSecondaryWindowingMode()) {
            // When the stack is resized due to entering split screen secondary, offset the
            // windows to compensate for the new stack position.
            forAllWindows(w -> {
                w.mWinAnimator.setOffsetPositionForStackResize(true);
            }, true);
        }

        updateDisplayInfo(bounds);
        updateSurfaceBounds();
    }

    /**
     * Determines the stack and task bounds of the other stack when in docked mode. The current task
     * bounds is passed in but depending on the stack, the task and stack must match. Only in
     * minimized mode with resizable launcher, the other stack ignores calculating the stack bounds
     * and uses the task bounds passed in as the stack and task bounds, otherwise the stack bounds
     * is calculated and is also used for its task bounds.
     * If any of the out bounds are empty, it represents default bounds
     *
     * @param currentTempTaskBounds the current task bounds of the other stack
     * @param outStackBounds the calculated stack bounds of the other stack
     * @param outTempTaskBounds the calculated task bounds of the other stack
     * @param ignoreVisibility ignore visibility in getting the stack bounds
     */
    void getStackDockedModeBoundsLocked(Rect currentTempTaskBounds, Rect outStackBounds,
            Rect outTempTaskBounds, boolean ignoreVisibility) {
        outTempTaskBounds.setEmpty();

        // When the home stack is resizable, should always have the same stack and task bounds
        if (isActivityTypeHome()) {
            final Task homeTask = findHomeTask();
            if (homeTask != null && homeTask.isResizeable()) {
                // Calculate the home stack bounds when in docked mode and the home stack is
                // resizeable.
                getDisplayContent().mDividerControllerLocked
                        .getHomeStackBoundsInDockedMode(outStackBounds);
            } else {
                // Home stack isn't resizeable, so don't specify stack bounds.
                outStackBounds.setEmpty();
            }

            outTempTaskBounds.set(outStackBounds);
            return;
        }

        // When minimized state, the stack bounds for all non-home and docked stack bounds should
        // match the passed task bounds
        if (isMinimizedDockAndHomeStackResizable() && currentTempTaskBounds != null) {
            outStackBounds.set(currentTempTaskBounds);
            return;
        }

        if (!inSplitScreenWindowingMode() || mDisplayContent == null) {
            outStackBounds.set(getRawBounds());
            return;
        }

        final TaskStack dockedStack =
                mDisplayContent.getSplitScreenPrimaryStackIgnoringVisibility();
        if (dockedStack == null) {
            // Not sure why you are calling this method when there is no docked stack...
            throw new IllegalStateException(
                    "Calling getStackDockedModeBoundsLocked() when there is no docked stack.");
        }
        if (!ignoreVisibility && !dockedStack.isVisible()) {
            // The docked stack is being dismissed, but we caught before it finished being
            // dismissed. In that case we want to treat it as if it is not occupying any space and
            // let others occupy the whole display.
            mDisplayContent.getBounds(outStackBounds);
            return;
        }

        final int dockedSide = dockedStack.getDockSide();
        if (dockedSide == DOCKED_INVALID) {
            // Not sure how you got here...Only thing we can do is return current bounds.
            Slog.e(TAG_WM, "Failed to get valid docked side for docked stack=" + dockedStack);
            outStackBounds.set(getRawBounds());
            return;
        }

        mDisplayContent.getBounds(mTmpRect);
        dockedStack.getRawBounds(mTmpRect2);
        final boolean dockedOnTopOrLeft = dockedSide == DOCKED_TOP || dockedSide == DOCKED_LEFT;
        getStackDockedModeBounds(mTmpRect, outStackBounds, mTmpRect2,
                mDisplayContent.mDividerControllerLocked.getContentWidth(), dockedOnTopOrLeft);

    }

    /**
     * Outputs the bounds a stack should be given the presence of a docked stack on the display.
     * @param displayRect The bounds of the display the docked stack is on.
     * @param outBounds Output bounds that should be used for the stack.
     * @param dockedBounds Bounds of the docked stack.
     * @param dockDividerWidth We need to know the width of the divider make to the output bounds
     *                         close to the side of the dock.
     * @param dockOnTopOrLeft If the docked stack is on the top or left side of the screen.
     */
    private void getStackDockedModeBounds(
            Rect displayRect, Rect outBounds, Rect dockedBounds, int dockDividerWidth,
            boolean dockOnTopOrLeft) {
        final boolean dockedStack = inSplitScreenPrimaryWindowingMode();
        final boolean splitHorizontally = displayRect.width() > displayRect.height();

        outBounds.set(displayRect);
        if (dockedStack) {
            if (mService.mDockedStackCreateBounds != null) {
                outBounds.set(mService.mDockedStackCreateBounds);
                return;
            }

            // The initial bounds of the docked stack when it is created about half the screen space
            // and its bounds can be adjusted after that. The bounds of all other stacks are
            // adjusted to occupy whatever screen space the docked stack isn't occupying.
            final DisplayInfo di = mDisplayContent.getDisplayInfo();
            mService.mPolicy.getStableInsetsLw(di.rotation, di.logicalWidth, di.logicalHeight,
                    di.displayCutout, mTmpRect2);
            final int position = new DividerSnapAlgorithm(mService.mContext.getResources(),
                    di.logicalWidth,
                    di.logicalHeight,
                    dockDividerWidth,
                    mDisplayContent.getConfiguration().orientation == ORIENTATION_PORTRAIT,
                    mTmpRect2).getMiddleTarget().position;

            if (dockOnTopOrLeft) {
                if (splitHorizontally) {
                    outBounds.right = position;
                } else {
                    outBounds.bottom = position;
                }
            } else {
                if (splitHorizontally) {
                    outBounds.left = position + dockDividerWidth;
                } else {
                    outBounds.top = position + dockDividerWidth;
                }
            }
            return;
        }

        // Other stacks occupy whatever space is left by the docked stack.
        if (!dockOnTopOrLeft) {
            if (splitHorizontally) {
                outBounds.right = dockedBounds.left - dockDividerWidth;
            } else {
                outBounds.bottom = dockedBounds.top - dockDividerWidth;
            }
        } else {
            if (splitHorizontally) {
                outBounds.left = dockedBounds.right + dockDividerWidth;
            } else {
                outBounds.top = dockedBounds.bottom + dockDividerWidth;
            }
        }
        DockedDividerUtils.sanitizeStackBounds(outBounds, !dockOnTopOrLeft);
    }

    void resetDockedStackToMiddle() {
        if (inSplitScreenPrimaryWindowingMode()) {
            throw new IllegalStateException("Not a docked stack=" + this);
        }

        mService.mDockedStackCreateBounds = null;

        final Rect bounds = new Rect();
        final Rect tempBounds = new Rect();
        getStackDockedModeBoundsLocked(null /* currentTempTaskBounds */, bounds, tempBounds,
                true /*ignoreVisibility*/);
        getController().requestResize(bounds);
    }

    @Override
    StackWindowController getController() {
        return (StackWindowController) super.getController();
    }

    @Override
    void removeIfPossible() {
        if (isSelfOrChildAnimating()) {
            mDeferRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void onParentSet() {
        super.onParentSet();

        if (getParent() != null || mDisplayContent == null) {
            return;
        }

        EventLog.writeEvent(EventLogTags.WM_STACK_REMOVED, mStackId);

        if (mAnimationBackgroundSurface != null) {
            mAnimationBackgroundSurface.destroy();
            mAnimationBackgroundSurface = null;
        }

        mDisplayContent = null;
        mService.mWindowPlacerLocked.requestTraversal();
    }

    void resetAnimationBackgroundAnimator() {
        mAnimationBackgroundAnimator = null;
        hideAnimationSurface();
    }

    void setAnimationBackground(WindowStateAnimator winAnimator, int color) {
        int animLayer = winAnimator.mAnimLayer;
        if (mAnimationBackgroundAnimator == null
                || animLayer < mAnimationBackgroundAnimator.mAnimLayer) {
            mAnimationBackgroundAnimator = winAnimator;
            animLayer = mDisplayContent.getLayerForAnimationBackground(winAnimator);
            showAnimationSurface(((color >> 24) & 0xff) / 255f);
        }
    }

    // TODO: Should each user have there own stacks?
    @Override
    void switchUser() {
        super.switchUser();
        int top = mChildren.size();
        for (int taskNdx = 0; taskNdx < top; ++taskNdx) {
            Task task = mChildren.get(taskNdx);
            if (mService.isCurrentProfileLocked(task.mUserId) || task.showForAllUsers()) {
                mChildren.remove(taskNdx);
                mChildren.add(task);
                --top;
            }
        }
    }

    /**
     * Adjusts the stack bounds if the IME is visible.
     *
     * @param imeWin The IME window.
     */
    void setAdjustedForIme(WindowState imeWin, boolean forceUpdate) {
        mImeWin = imeWin;
        mImeGoingAway = false;
        if (!mAdjustedForIme || forceUpdate) {
            mAdjustedForIme = true;
            mAdjustImeAmount = 0f;
            mAdjustDividerAmount = 0f;
            updateAdjustForIme(0f, 0f, true /* force */);
        }
    }

    boolean isAdjustedForIme() {
        return mAdjustedForIme;
    }

    boolean isAnimatingForIme() {
        return mImeWin != null && mImeWin.isAnimatingLw();
    }

    /**
     * Update the stack's bounds (crop or position) according to the IME window's
     * current position. When IME window is animated, the bottom stack is animated
     * together to track the IME window's current position, and the top stack is
     * cropped as necessary.
     *
     * @return true if a traversal should be performed after the adjustment.
     */
    boolean updateAdjustForIme(float adjustAmount, float adjustDividerAmount, boolean force) {
        if (adjustAmount != mAdjustImeAmount
                || adjustDividerAmount != mAdjustDividerAmount || force) {
            mAdjustImeAmount = adjustAmount;
            mAdjustDividerAmount = adjustDividerAmount;
            updateAdjustedBounds();
            return isVisible();
        } else {
            return false;
        }
    }

    /**
     * Resets the adjustment after it got adjusted for the IME.
     * @param adjustBoundsNow if true, reset and update the bounds immediately and forget about
     *                        animations; otherwise, set flag and animates the window away together
     *                        with IME window.
     */
    void resetAdjustedForIme(boolean adjustBoundsNow) {
        if (adjustBoundsNow) {
            mImeWin = null;
            mImeGoingAway = false;
            mAdjustImeAmount = 0f;
            mAdjustDividerAmount = 0f;
            if (!mAdjustedForIme) {
                return;
            }
            mAdjustedForIme = false;
            updateAdjustedBounds();
            mService.setResizeDimLayer(false, getWindowingMode(), 1.0f);
        } else {
            mImeGoingAway |= mAdjustedForIme;
        }
    }

    /**
     * Sets the amount how much we currently minimize our stack.
     *
     * @param minimizeAmount The amount, between 0 and 1.
     * @return Whether the amount has changed and a layout is needed.
     */
    boolean setAdjustedForMinimizedDock(float minimizeAmount) {
        if (minimizeAmount != mMinimizeAmount) {
            mMinimizeAmount = minimizeAmount;
            updateAdjustedBounds();
            return isVisible();
        } else {
            return false;
        }
    }

    boolean shouldIgnoreInput() {
        return isAdjustedForMinimizedDockedStack() ||
                (inSplitScreenPrimaryWindowingMode() && isMinimizedDockAndHomeStackResizable());
    }

    /**
     * Puts all visible tasks that are adjusted for IME into resizing mode and adds the windows
     * to the list of to be drawn windows the service is waiting for.
     */
    void beginImeAdjustAnimation() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final Task task = mChildren.get(j);
            if (task.hasContentToDisplay()) {
                task.setDragResizing(true, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
                task.setWaitingForDrawnIfResizingChanged();
            }
        }
    }

    /**
     * Resets the resizing state of all windows.
     */
    void endImeAdjustAnimation() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            mChildren.get(j).setDragResizing(false, DRAG_RESIZE_MODE_DOCKED_DIVIDER);
        }
    }

    int getMinTopStackBottom(final Rect displayContentRect, int originalStackBottom) {
        return displayContentRect.top + (int)
                ((originalStackBottom - displayContentRect.top) * ADJUSTED_STACK_FRACTION_MIN);
    }

    private boolean adjustForIME(final WindowState imeWin) {
        final int dockedSide = getDockSide();
        final boolean dockedTopOrBottom = dockedSide == DOCKED_TOP || dockedSide == DOCKED_BOTTOM;
        if (imeWin == null || !dockedTopOrBottom) {
            return false;
        }

        final Rect displayStableRect = mTmpRect;
        final Rect contentBounds = mTmpRect2;

        // Calculate the content bounds excluding the area occupied by IME
        getDisplayContent().getStableRect(displayStableRect);
        contentBounds.set(displayStableRect);
        int imeTop = Math.max(imeWin.getFrameLw().top, contentBounds.top);

        imeTop += imeWin.getGivenContentInsetsLw().top;
        if (contentBounds.bottom > imeTop) {
            contentBounds.bottom = imeTop;
        }

        final int yOffset = displayStableRect.bottom - contentBounds.bottom;

        final int dividerWidth =
                getDisplayContent().mDividerControllerLocked.getContentWidth();
        final int dividerWidthInactive =
                getDisplayContent().mDividerControllerLocked.getContentWidthInactive();

        if (dockedSide == DOCKED_TOP) {
            // If this stack is docked on top, we make it smaller so the bottom stack is not
            // occluded by IME. We shift its bottom up by the height of the IME, but
            // leaves at least 30% of the top stack visible.
            final int minTopStackBottom =
                    getMinTopStackBottom(displayStableRect, getRawBounds().bottom);
            final int bottom = Math.max(
                    getRawBounds().bottom - yOffset + dividerWidth - dividerWidthInactive,
                    minTopStackBottom);
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.bottom = (int) (mAdjustImeAmount * bottom + (1 - mAdjustImeAmount)
                    * getRawBounds().bottom);
            mFullyAdjustedImeBounds.set(getRawBounds());
        } else {
            // When the stack is on bottom and has no focus, it's only adjusted for divider width.
            final int dividerWidthDelta = dividerWidthInactive - dividerWidth;

            // When the stack is on bottom and has focus, it needs to be moved up so as to
            // not occluded by IME, and at the same time adjusted for divider width.
            // We try to move it up by the height of the IME window, but only to the extent
            // that leaves at least 30% of the top stack visible.
            // 'top' is where the top of bottom stack will move to in this case.
            final int topBeforeImeAdjust =
                    getRawBounds().top - dividerWidth + dividerWidthInactive;
            final int minTopStackBottom =
                    getMinTopStackBottom(displayStableRect,
                            getRawBounds().top - dividerWidth);
            final int top = Math.max(
                    getRawBounds().top - yOffset, minTopStackBottom + dividerWidthInactive);

            mTmpAdjustedBounds.set(getRawBounds());
            // Account for the adjustment for IME and divider width separately.
            // (top - topBeforeImeAdjust) is the amount of movement due to IME only,
            // and dividerWidthDelta is due to divider width change only.
            mTmpAdjustedBounds.top = getRawBounds().top +
                    (int) (mAdjustImeAmount * (top - topBeforeImeAdjust) +
                            mAdjustDividerAmount * dividerWidthDelta);
            mFullyAdjustedImeBounds.set(getRawBounds());
            mFullyAdjustedImeBounds.top = top;
            mFullyAdjustedImeBounds.bottom = top + getRawBounds().height();
        }
        return true;
    }

    private boolean adjustForMinimizedDockedStack(float minimizeAmount) {
        final int dockSide = getDockSide();
        if (dockSide == DOCKED_INVALID && !mTmpAdjustedBounds.isEmpty()) {
            return false;
        }

        if (dockSide == DOCKED_TOP) {
            mService.getStableInsetsLocked(DEFAULT_DISPLAY, mTmpRect);
            int topInset = mTmpRect.top;
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.bottom = (int) (minimizeAmount * topInset + (1 - minimizeAmount)
                    * getRawBounds().bottom);
        } else if (dockSide == DOCKED_LEFT) {
            mTmpAdjustedBounds.set(getRawBounds());
            final int width = getRawBounds().width();
            mTmpAdjustedBounds.right =
                    (int) (minimizeAmount * mDockedStackMinimizeThickness
                            + (1 - minimizeAmount) * getRawBounds().right);
            mTmpAdjustedBounds.left = mTmpAdjustedBounds.right - width;
        } else if (dockSide == DOCKED_RIGHT) {
            mTmpAdjustedBounds.set(getRawBounds());
            mTmpAdjustedBounds.left = (int) (minimizeAmount *
                    (getRawBounds().right - mDockedStackMinimizeThickness)
                            + (1 - minimizeAmount) * getRawBounds().left);
        }
        return true;
    }

    private boolean isMinimizedDockAndHomeStackResizable() {
        return mDisplayContent.mDividerControllerLocked.isMinimizedDock()
                && mDisplayContent.mDividerControllerLocked.isHomeStackResizable();
    }

    /**
     * @return the distance in pixels how much the stack gets minimized from it's original size
     */
    int getMinimizeDistance() {
        final int dockSide = getDockSide();
        if (dockSide == DOCKED_INVALID) {
            return 0;
        }

        if (dockSide == DOCKED_TOP) {
            mService.getStableInsetsLocked(DEFAULT_DISPLAY, mTmpRect);
            int topInset = mTmpRect.top;
            return getRawBounds().bottom - topInset;
        } else if (dockSide == DOCKED_LEFT || dockSide == DOCKED_RIGHT) {
            return getRawBounds().width() - mDockedStackMinimizeThickness;
        } else {
            return 0;
        }
    }

    /**
     * Updates the adjustment depending on it's current state.
     */
    private void updateAdjustedBounds() {
        boolean adjust = false;
        if (mMinimizeAmount != 0f) {
            adjust = adjustForMinimizedDockedStack(mMinimizeAmount);
        } else if (mAdjustedForIme) {
            adjust = adjustForIME(mImeWin);
        }
        if (!adjust) {
            mTmpAdjustedBounds.setEmpty();
        }
        setAdjustedBounds(mTmpAdjustedBounds);

        final boolean isImeTarget = (mService.getImeFocusStackLocked() == this);
        if (mAdjustedForIme && adjust && !isImeTarget) {
            final float alpha = Math.max(mAdjustImeAmount, mAdjustDividerAmount)
                    * IME_ADJUST_DIM_AMOUNT;
            mService.setResizeDimLayer(true, getWindowingMode(), alpha);
        }
    }

    void applyAdjustForImeIfNeeded(Task task) {
        if (mMinimizeAmount != 0f || !mAdjustedForIme || mAdjustedBounds.isEmpty()) {
            return;
        }

        final Rect insetBounds = mImeGoingAway ? getRawBounds() : mFullyAdjustedImeBounds;
        task.alignToAdjustedBounds(mAdjustedBounds, insetBounds, getDockSide() == DOCKED_TOP);
        mDisplayContent.setLayoutNeeded();
    }


    boolean isAdjustedForMinimizedDockedStack() {
        return mMinimizeAmount != 0f;
    }

    /**
     * @return {@code true} if we have a {@link Task} that is animating (currently only used for the
     *         recents animation); {@code false} otherwise.
     */
    boolean isTaskAnimating() {
        for (int j = mChildren.size() - 1; j >= 0; j--) {
            final Task task = mChildren.get(j);
            if (task.isTaskAnimating()) {
                return true;
            }
        }
        return false;
    }

    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, trim);
        proto.write(ID, mStackId);
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            mChildren.get(taskNdx).writeToProto(proto, TASKS, trim);
        }
        proto.write(FILLS_PARENT, matchParentBounds());
        getRawBounds().writeToProto(proto, BOUNDS);
        proto.write(ANIMATION_BACKGROUND_SURFACE_IS_DIMMING, mAnimationBackgroundSurfaceIsShown);
        proto.write(DEFER_REMOVAL, mDeferRemoval);
        proto.write(MINIMIZE_AMOUNT, mMinimizeAmount);
        proto.write(ADJUSTED_FOR_IME, mAdjustedForIme);
        proto.write(ADJUST_IME_AMOUNT, mAdjustImeAmount);
        proto.write(ADJUST_DIVIDER_AMOUNT, mAdjustDividerAmount);
        mAdjustedBounds.writeToProto(proto, ADJUSTED_BOUNDS);
        proto.write(ANIMATING_BOUNDS, mBoundsAnimating);
        proto.end(token);
    }

    @Override
     void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        pw.println(prefix + "mStackId=" + mStackId);
        pw.println(prefix + "mDeferRemoval=" + mDeferRemoval);
        pw.println(prefix + "mBounds=" + getRawBounds().toShortString());
        if (mMinimizeAmount != 0f) {
            pw.println(prefix + "mMinimizeAmount=" + mMinimizeAmount);
        }
        if (mAdjustedForIme) {
            pw.println(prefix + "mAdjustedForIme=true");
            pw.println(prefix + "mAdjustImeAmount=" + mAdjustImeAmount);
            pw.println(prefix + "mAdjustDividerAmount=" + mAdjustDividerAmount);
        }
        if (!mAdjustedBounds.isEmpty()) {
            pw.println(prefix + "mAdjustedBounds=" + mAdjustedBounds.toShortString());
        }
        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; taskNdx--) {
            mChildren.get(taskNdx).dump(pw, prefix + "  ", dumpAll);
        }
        if (mAnimationBackgroundSurfaceIsShown) {
            pw.println(prefix + "mWindowAnimationBackgroundSurface is shown");
        }
        if (!mExitingAppTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i = mExitingAppTokens.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingAppTokens.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ", dumpAll);
            }
        }
        mAnimatingAppWindowTokenRegistry.dump(pw, "AnimatingApps:", prefix);
    }

    @Override
    boolean fillsParent() {
        if (useCurrentBounds()) {
            return matchParentBounds();
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mChildren + "}";
    }

    String getName() {
        return toShortString();
    }

    public String toShortString() {
        return "Stack=" + mStackId;
    }

    /**
     * For docked workspace (or workspace that's side-by-side to the docked), provides
     * information which side of the screen was the dock anchored.
     */
    int getDockSide() {
        return getDockSide(getRawBounds());
    }

    int getDockSideForDisplay(DisplayContent dc) {
        return getDockSide(dc, getRawBounds());
    }

    private int getDockSide(Rect bounds) {
        if (mDisplayContent == null) {
            return DOCKED_INVALID;
        }
        return getDockSide(mDisplayContent, bounds);
    }

    private int getDockSide(DisplayContent dc, Rect bounds) {
        if (!inSplitScreenWindowingMode()) {
            return DOCKED_INVALID;
        }
        dc.getBounds(mTmpRect);
        final int orientation = dc.getConfiguration().orientation;
        return getDockSideUnchecked(bounds, mTmpRect, orientation);
    }

    static int getDockSideUnchecked(Rect bounds, Rect displayRect, int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // Portrait mode, docked either at the top or the bottom.
            if (bounds.top - displayRect.top <= displayRect.bottom - bounds.bottom) {
                return DOCKED_TOP;
            } else {
                return DOCKED_BOTTOM;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape mode, docked either on the left or on the right.
            if (bounds.left - displayRect.left <= displayRect.right - bounds.right) {
                return DOCKED_LEFT;
            } else {
                return DOCKED_RIGHT;
            }
        } else {
            return DOCKED_INVALID;
        }
    }

    boolean hasTaskForUser(int userId) {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final Task task = mChildren.get(i);
            if (task.mUserId == userId) {
                return true;
            }
        }
        return false;
    }

    int taskIdFromPoint(int x, int y) {
        getBounds(mTmpRect);
        if (!mTmpRect.contains(x, y) || isAdjustedForMinimizedDockedStack()) {
            return -1;
        }

        for (int taskNdx = mChildren.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mChildren.get(taskNdx);
            final WindowState win = task.getTopVisibleAppMainWindow();
            if (win == null) {
                continue;
            }
            // We need to use the task's dim bounds (which is derived from the visible bounds of its
            // apps windows) for any touch-related tests. Can't use the task's original bounds
            // because it might be adjusted to fit the content frame. For example, the presence of
            // the IME adjusting the windows frames when the app window is the IME target.
            task.getDimBounds(mTmpRect);
            if (mTmpRect.contains(x, y)) {
                return task.mTaskId;
            }
        }

        return -1;
    }

    void findTaskForResizePoint(int x, int y, int delta,
            DisplayContent.TaskForResizePointSearchResult results) {
        if (!getWindowConfiguration().canResizeTask()) {
            results.searchDone = true;
            return;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task task = mChildren.get(i);
            if (task.isFullscreen()) {
                results.searchDone = true;
                return;
            }

            // We need to use the task's dim bounds (which is derived from the visible bounds of
            // its apps windows) for any touch-related tests. Can't use the task's original
            // bounds because it might be adjusted to fit the content frame. One example is when
            // the task is put to top-left quadrant, the actual visible area would not start at
            // (0,0) after it's adjusted for the status bar.
            task.getDimBounds(mTmpRect);
            mTmpRect.inset(-delta, -delta);
            if (mTmpRect.contains(x, y)) {
                mTmpRect.inset(delta, delta);

                results.searchDone = true;

                if (!mTmpRect.contains(x, y)) {
                    results.taskForResize = task;
                    return;
                }
                // User touched inside the task. No need to look further,
                // focus transfer will be handled in ACTION_UP.
                return;
            }
        }
    }

    void setTouchExcludeRegion(Task focusedTask, int delta, Region touchExcludeRegion,
            Rect contentRect, Rect postExclude) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final Task task = mChildren.get(i);
            AppWindowToken token = task.getTopVisibleAppToken();
            if (token == null || !token.hasContentToDisplay()) {
                continue;
            }

            /**
             * Exclusion region is the region that TapDetector doesn't care about.
             * Here we want to remove all non-focused tasks from the exclusion region.
             * We also remove the outside touch area for resizing for all freeform
             * tasks (including the focused).
             *
             * We save the focused task region once we find it, and add it back at the end.
             *
             * If the task is home stack and it is resizable in the minimized state, we want to
             * exclude the docked stack from touch so we need the entire screen area and not just a
             * small portion which the home stack currently is resized to.
             */

            if (task.isActivityTypeHome() && isMinimizedDockAndHomeStackResizable()) {
                mDisplayContent.getBounds(mTmpRect);
            } else {
                task.getDimBounds(mTmpRect);
            }

            if (task == focusedTask) {
                // Add the focused task rect back into the exclude region once we are done
                // processing stacks.
                postExclude.set(mTmpRect);
            }

            final boolean isFreeformed = task.inFreeformWindowingMode();
            if (task != focusedTask || isFreeformed) {
                if (isFreeformed) {
                    // If the task is freeformed, enlarge the area to account for outside
                    // touch area for resize.
                    mTmpRect.inset(-delta, -delta);
                    // Intersect with display content rect. If we have system decor (status bar/
                    // navigation bar), we want to exclude that from the tap detection.
                    // Otherwise, if the app is partially placed under some system button (eg.
                    // Recents, Home), pressing that button would cause a full series of
                    // unwanted transfer focus/resume/pause, before we could go home.
                    mTmpRect.intersect(contentRect);
                }
                touchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
            }
        }
    }

    public boolean setPinnedStackSize(Rect stackBounds, Rect tempTaskBounds) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mService.mWindowMap) {
            if (mCancelCurrentBoundsAnimation) {
                return false;
            }
        }

        try {
            mService.mActivityManager.resizePinnedStack(stackBounds, tempTaskBounds);
        } catch (RemoteException e) {
            // I don't believe you.
        }
        return true;
    }

    void onAllWindowsDrawn() {
        if (!mBoundsAnimating && !mBoundsAnimatingRequested) {
            return;
        }

        mService.mBoundsAnimationController.onAllWindowsDrawn();
    }

    @Override  // AnimatesBounds
    public void onAnimationStart(boolean schedulePipModeChangedCallback, boolean forceUpdate) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mService.mWindowMap) {
            mBoundsAnimatingRequested = false;
            mBoundsAnimating = true;
            mCancelCurrentBoundsAnimation = false;

            // If we are changing UI mode, as in the PiP to fullscreen
            // transition, then we need to wait for the window to draw.
            if (schedulePipModeChangedCallback) {
                forAllWindows((w) -> { w.mWinAnimator.resetDrawState(); },
                        false /* traverseTopToBottom */);
            }
        }

        if (inPinnedWindowingMode()) {
            try {
                mService.mActivityManager.notifyPinnedStackAnimationStarted();
            } catch (RemoteException e) {
                // I don't believe you...
            }

            final PinnedStackWindowController controller =
                    (PinnedStackWindowController) getController();
            if (schedulePipModeChangedCallback && controller != null) {
                // We need to schedule the PiP mode change before the animation up. It is possible
                // in this case for the animation down to not have been completed, so always
                // force-schedule and update to the client to ensure that it is notified that it
                // is no longer in picture-in-picture mode
                controller.updatePictureInPictureModeForPinnedStackAnimation(null, forceUpdate);
            }
        }
    }

    @Override  // AnimatesBounds
    public void onAnimationEnd(boolean schedulePipModeChangedCallback, Rect finalStackSize,
            boolean moveToFullscreen) {
        // Hold the lock since this is called from the BoundsAnimator running on the UiThread
        synchronized (mService.mWindowMap) {
            mBoundsAnimating = false;
            for (int i = 0; i < mChildren.size(); i++) {
                final Task t = mChildren.get(i);
                t.clearPreserveNonFloatingState();
            }
            mService.requestTraversal();
        }

        if (inPinnedWindowingMode()) {
            // Update to the final bounds if requested. This is done here instead of in the bounds
            // animator to allow us to coordinate this after we notify the PiP mode changed

            final PinnedStackWindowController controller =
                    (PinnedStackWindowController) getController();
            if (schedulePipModeChangedCallback && controller != null) {
                // We need to schedule the PiP mode change after the animation down, so use the
                // final bounds
                controller.updatePictureInPictureModeForPinnedStackAnimation(
                        mBoundsAnimationTarget, false /* forceUpdate */);
            }

            if (finalStackSize != null) {
                setPinnedStackSize(finalStackSize, null);
            }

            try {
                mService.mActivityManager.notifyPinnedStackAnimationEnded();
                if (moveToFullscreen) {
                    mService.mActivityManager.moveTasksToFullscreenStack(mStackId,
                            true /* onTop */);
                }
            } catch (RemoteException e) {
                // I don't believe you...
            }
        }
    }

    @Override
    public boolean shouldDeferStartOnMoveToFullscreen() {
        // Workaround for the recents animation -- normally we need to wait for the new activity to
        // show before starting the PiP animation, but because we start and show the home activity
        // early for the recents animation prior to the PiP animation starting, there is no
        // subsequent all-drawn signal. In this case, we can skip the pause when the home stack is
        // already visible and drawn.
        final TaskStack homeStack = mDisplayContent.getHomeStack();
        if (homeStack == null) {
            return true;
        }
        final Task homeTask = homeStack.getTopChild();
        final AppWindowToken homeApp = homeTask.getTopVisibleAppToken();
        if (!homeTask.isVisible() || homeApp == null) {
            return true;
        }
        return !homeApp.allDrawn;
    }

    /**
     * @return True if we are currently animating the pinned stack from fullscreen to non-fullscreen
     *         bounds and we have a deferred PiP mode changed callback set with the animation.
     */
    public boolean deferScheduleMultiWindowModeChanged() {
        if (inPinnedWindowingMode()) {
            return (mBoundsAnimatingRequested || mBoundsAnimating);
        }
        return false;
    }

    public boolean isForceScaled() {
        return mBoundsAnimating;
    }

    public boolean isAnimatingBounds() {
        return mBoundsAnimating;
    }

    public boolean lastAnimatingBoundsWasToFullscreen() {
        return mBoundsAnimatingToFullscreen;
    }

    public boolean isAnimatingBoundsToFullscreen() {
        return isAnimatingBounds() && lastAnimatingBoundsWasToFullscreen();
    }

    public boolean pinnedStackResizeDisallowed() {
        if (mBoundsAnimating && mCancelCurrentBoundsAnimation) {
            return true;
        }
        return false;
    }

    /** Returns true if a removal action is still being deferred. */
    boolean checkCompleteDeferredRemoval() {
        if (isSelfOrChildAnimating()) {
            return true;
        }
        if (mDeferRemoval) {
            removeImmediately();
        }

        return super.checkCompleteDeferredRemoval();
    }

    @Override
    int getOrientation() {
        return (canSpecifyOrientation()) ? super.getOrientation() : SCREEN_ORIENTATION_UNSET;
    }

    private boolean canSpecifyOrientation() {
        final int windowingMode = getWindowingMode();
        final int activityType = getActivityType();
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                || activityType == ACTIVITY_TYPE_HOME
                || activityType == ACTIVITY_TYPE_RECENTS
                || activityType == ACTIVITY_TYPE_ASSISTANT;
    }

    @Override
    Dimmer getDimmer() {
        return mDimmer;
    }

    @Override
    void prepareSurfaces() {
        mDimmer.resetDimStates();
        super.prepareSurfaces();
        getDimBounds(mTmpDimBoundsRect);

        // Bounds need to be relative, as the dim layer is a child.
        mTmpDimBoundsRect.offsetTo(0, 0);
        if (mDimmer.updateDims(getPendingTransaction(), mTmpDimBoundsRect)) {
            scheduleAnimation();
        }
    }

    public DisplayInfo getDisplayInfo() {
        return mDisplayContent.getDisplayInfo();
    }

    void dim(float alpha) {
        mDimmer.dimAbove(getPendingTransaction(), alpha);
        scheduleAnimation();
    }

    void stopDimming() {
        mDimmer.stopDim(getPendingTransaction());
        scheduleAnimation();
    }

    @Override
    void getRelativePosition(Point outPos) {
        super.getRelativePosition(outPos);
        final int outset = getStackOutset();
        outPos.x -= outset;
        outPos.y -= outset;
    }

    AnimatingAppWindowTokenRegistry getAnimatingAppWindowTokenRegistry() {
        return mAnimatingAppWindowTokenRegistry;
    }
}
