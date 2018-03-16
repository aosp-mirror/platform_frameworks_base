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

import static android.app.ActivityManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.res.Configuration.EMPTY;
import static com.android.server.EventLogTags.WM_TASK_REMOVED;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_STACK;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.TaskProto.APP_WINDOW_TOKENS;
import static com.android.server.wm.TaskProto.BOUNDS;
import static com.android.server.wm.TaskProto.DEFER_REMOVAL;
import static com.android.server.wm.TaskProto.FILLS_PARENT;
import static com.android.server.wm.TaskProto.ID;
import static com.android.server.wm.TaskProto.TEMP_INSET_BOUNDS;
import static com.android.server.wm.TaskProto.WINDOW_CONTAINER;

import android.annotation.CallSuper;
import android.app.ActivityManager.TaskDescription;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;
import android.view.Surface;

import android.view.SurfaceControl;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.function.Consumer;

class Task extends WindowContainer<AppWindowToken> {
    static final String TAG = TAG_WITH_CLASS_NAME ? "Task" : TAG_WM;

    // TODO: Track parent marks like this in WindowContainer.
    TaskStack mStack;
    final int mTaskId;
    final int mUserId;
    private boolean mDeferRemoval = false;

    final Rect mPreparedFrozenBounds = new Rect();
    final Configuration mPreparedFrozenMergedConfig = new Configuration();

    // Bounds used to calculate the insets.
    private final Rect mTempInsetBounds = new Rect();

    // Device rotation as of the last time {@link #mBounds} was set.
    private int mRotation;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();
    // For retrieving dim bounds
    private Rect mTmpRect3 = new Rect();

    // Resize mode of the task. See {@link ActivityInfo#resizeMode}
    private int mResizeMode;

    // Whether the task supports picture-in-picture.
    // See {@link ActivityInfo#FLAG_SUPPORTS_PICTURE_IN_PICTURE}
    private boolean mSupportsPictureInPicture;

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;
    private int mDragResizeMode;

    private TaskDescription mTaskDescription;

    // If set to true, the task will report that it is not in the floating
    // state regardless of it's stack affiliation. As the floating state drives
    // production of content insets this can be used to preserve them across
    // stack moves and we in fact do so when moving from full screen to pinned.
    private boolean mPreserveNonFloatingState = false;

    private Dimmer mDimmer = new Dimmer(this);
    private final Rect mTmpDimBoundsRect = new Rect();

    /** @see #setCanAffectSystemUiFlags */
    private boolean mCanAffectSystemUiFlags = true;

    Task(int taskId, TaskStack stack, int userId, WindowManagerService service, int resizeMode,
            boolean supportsPictureInPicture, TaskDescription taskDescription,
            TaskWindowContainerController controller) {
        super(service);
        mTaskId = taskId;
        mStack = stack;
        mUserId = userId;
        mResizeMode = resizeMode;
        mSupportsPictureInPicture = supportsPictureInPicture;
        setController(controller);
        setBounds(getOverrideBounds());
        mTaskDescription = taskDescription;

        // Tasks have no set orientation value (including SCREEN_ORIENTATION_UNSPECIFIED).
        setOrientation(SCREEN_ORIENTATION_UNSET);
    }

    DisplayContent getDisplayContent() {
        return mStack != null ? mStack.getDisplayContent() : null;
    }

    private int getAdjustedAddPosition(int suggestedPosition) {
        final int size = mChildren.size();
        if (suggestedPosition >= size) {
            return Math.min(size, suggestedPosition);
        }

        for (int pos = 0; pos < size && pos < suggestedPosition; ++pos) {
            // TODO: Confirm that this is the behavior we want long term.
            if (mChildren.get(pos).removed) {
                // suggestedPosition assumes removed tokens are actually gone.
                ++suggestedPosition;
            }
        }
        return Math.min(size, suggestedPosition);
    }

    @Override
    void addChild(AppWindowToken wtoken, int position) {
        position = getAdjustedAddPosition(position);
        super.addChild(wtoken, position);
        mDeferRemoval = false;
    }

    @Override
    void positionChildAt(int position, AppWindowToken child, boolean includingParents) {
        position = getAdjustedAddPosition(position);
        super.positionChildAt(position, child, includingParents);
        mDeferRemoval = false;
    }

    private boolean hasWindowsAlive() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            if (mChildren.get(i).hasWindowsAlive()) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    boolean shouldDeferRemoval() {
        // TODO: This should probably return false if mChildren.isEmpty() regardless if the stack
        // is animating...
        return hasWindowsAlive() && mStack.isSelfOrChildAnimating();
    }

    @Override
    void removeIfPossible() {
        if (shouldDeferRemoval()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            mDeferRemoval = true;
            return;
        }
        removeImmediately();
    }

    @Override
    void removeImmediately() {
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeTask");
        mDeferRemoval = false;

        super.removeImmediately();
    }

    void reparent(TaskStack stack, int position, boolean moveParents) {
        if (stack == mStack) {
            throw new IllegalArgumentException(
                    "task=" + this + " already child of stack=" + mStack);
        }
        if (DEBUG_STACK) Slog.i(TAG, "reParentTask: removing taskId=" + mTaskId
                + " from stack=" + mStack);
        EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "reParentTask");
        final DisplayContent prevDisplayContent = getDisplayContent();

        // If we are moving from the fullscreen stack to the pinned stack
        // then we want to preserve our insets so that there will not
        // be a jump in the area covered by system decorations. We rely
        // on the pinned animation to later unset this value.
        if (stack.inPinnedWindowingMode()) {
            mPreserveNonFloatingState = true;
        } else {
            mPreserveNonFloatingState = false;
        }

        getParent().removeChild(this);
        stack.addTask(this, position, showForAllUsers(), moveParents);

        // Relayout display(s).
        final DisplayContent displayContent = stack.getDisplayContent();
        displayContent.setLayoutNeeded();
        if (prevDisplayContent != displayContent) {
            onDisplayChanged(displayContent);
            prevDisplayContent.setLayoutNeeded();
        }
    }

    /** @see com.android.server.am.ActivityManagerService#positionTaskInStack(int, int, int). */
    void positionAt(int position) {
        mStack.positionChildAt(position, this, false /* includingParents */);
    }

    @Override
    void onParentSet() {
        super.onParentSet();

        // Update task bounds if needed.
        updateDisplayInfo(getDisplayContent());

        if (getWindowConfiguration().windowsAreScaleable()) {
            // We force windows out of SCALING_MODE_FREEZE so that we can continue to animate them
            // while a resize is pending.
            forceWindowsScaleable(true /* force */);
        } else {
            forceWindowsScaleable(false /* force */);
        }
    }

    @Override
    void removeChild(AppWindowToken token) {
        if (!mChildren.contains(token)) {
            Slog.e(TAG, "removeChild: token=" + this + " not found.");
            return;
        }

        super.removeChild(token);

        if (mChildren.isEmpty()) {
            EventLog.writeEvent(WM_TASK_REMOVED, mTaskId, "removeAppToken: last token");
            if (mDeferRemoval) {
                removeIfPossible();
            }
        }
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mChildren.size(); appTokenNdx++) {
            mChildren.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    public int setBounds(Rect bounds, boolean forceResize) {
        final int boundsChanged = setBounds(bounds);

        if (forceResize && (boundsChanged & BOUNDS_CHANGE_SIZE) != BOUNDS_CHANGE_SIZE) {
            onResize();
            return BOUNDS_CHANGE_SIZE | boundsChanged;
        }

        return boundsChanged;
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    @Override
    public int setBounds(Rect bounds) {
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (displayContent != null) {
            rotation = displayContent.getDisplayInfo().rotation;
        } else if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return BOUNDS_CHANGE_NONE;
        }

        if (equivalentOverrideBounds(bounds)) {
            return BOUNDS_CHANGE_NONE;
        }

        final int boundsChange = super.setBounds(bounds);

        mRotation = rotation;

        return boundsChange;
    }

    /**
     * Sets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void setTempInsetBounds(Rect tempInsetBounds) {
        if (tempInsetBounds != null) {
            mTempInsetBounds.set(tempInsetBounds);
        } else {
            mTempInsetBounds.setEmpty();
        }
    }

    /**
     * Gets the bounds used to calculate the insets. See
     * {@link android.app.IActivityManager#resizeDockedStack} why this is needed.
     */
    void getTempInsetBounds(Rect out) {
        out.set(mTempInsetBounds);
    }

    void setResizeable(int resizeMode) {
        mResizeMode = resizeMode;
    }

    boolean isResizeable() {
        return ActivityInfo.isResizeableMode(mResizeMode) || mSupportsPictureInPicture
                || mService.mForceResizableTasks;
    }

    /**
     * Tests if the orientation should be preserved upon user interactive resizig operations.

     * @return true if orientation should not get changed upon resizing operation.
     */
    boolean preserveOrientationOnResize() {
        return mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY
                || mResizeMode == RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
    }

    boolean cropWindowsToStackBounds() {
        return isResizeable();
    }

    /**
     * Prepares the task bounds to be frozen with the current size. See
     * {@link AppWindowToken#freezeBounds}.
     */
    void prepareFreezingBounds() {
        mPreparedFrozenBounds.set(getBounds());
        mPreparedFrozenMergedConfig.setTo(getConfiguration());
    }

    /**
     * Align the task to the adjusted bounds.
     *
     * @param adjustedBounds Adjusted bounds to which the task should be aligned.
     * @param tempInsetBounds Insets bounds for the task.
     * @param alignBottom True if the task's bottom should be aligned to the adjusted
     *                    bounds's bottom; false if the task's top should be aligned
     *                    the adjusted bounds's top.
     */
    void alignToAdjustedBounds(Rect adjustedBounds, Rect tempInsetBounds, boolean alignBottom) {
        if (!isResizeable() || EMPTY.equals(getOverrideConfiguration())) {
            return;
        }

        getBounds(mTmpRect2);
        if (alignBottom) {
            int offsetY = adjustedBounds.bottom - mTmpRect2.bottom;
            mTmpRect2.offset(0, offsetY);
        } else {
            mTmpRect2.offsetTo(adjustedBounds.left, adjustedBounds.top);
        }
        setTempInsetBounds(tempInsetBounds);
        setBounds(mTmpRect2, false /* forced */);
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        final DisplayContent displayContent = getDisplayContent();
        return matchParentBounds()
                || !inSplitScreenSecondaryWindowingMode()
                || displayContent == null
                || displayContent.getSplitScreenPrimaryStackIgnoringVisibility() != null;
    }

    @Override
    public void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // No need to adjust the output bounds if fullscreen or the docked stack is visible
            // since it is already what we want to represent to the rest of the system.
            super.getBounds(out);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack is
        // not currently visible. Go ahead a represent it as fullscreen to the rest of the system.
        mStack.getDisplayContent().getBounds(out);
    }

    /**
     * Calculate the maximum visible area of this task. If the task has only one app,
     * the result will be visible frame of that app. If the task has more than one apps,
     * we search from top down if the next app got different visible area.
     *
     * This effort is to handle the case where some task (eg. GMail composer) might pop up
     * a dialog that's different in size from the activity below, in which case we should
     * be dimming the entire task area behind the dialog.
     *
     * @param out Rect containing the max visible bounds.
     * @return true if the task has some visible app windows; false otherwise.
     */
    boolean getMaxVisibleBounds(Rect out) {
        boolean foundTop = false;
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (token.mIsExiting || token.isClientHidden() || token.hiddenRequested) {
                continue;
            }
            final WindowState win = token.findMainWindow();
            if (win == null) {
                continue;
            }
            if (!foundTop) {
                out.set(win.mVisibleFrame);
                foundTop = true;
                continue;
            }
            if (win.mVisibleFrame.left < out.left) {
                out.left = win.mVisibleFrame.left;
            }
            if (win.mVisibleFrame.top < out.top) {
                out.top = win.mVisibleFrame.top;
            }
            if (win.mVisibleFrame.right > out.right) {
                out.right = win.mVisibleFrame.right;
            }
            if (win.mVisibleFrame.bottom > out.bottom) {
                out.bottom = win.mVisibleFrame.bottom;
            }
        }
        return foundTop;
    }

    /** Bounds of the task to be used for dimming, as well as touch related tests. */
    public void getDimBounds(Rect out) {
        final DisplayContent displayContent = mStack.getDisplayContent();
        // It doesn't matter if we in particular are part of the resize, since we couldn't have
        // a DimLayer anyway if we weren't visible.
        final boolean dockedResizing = displayContent != null
                && displayContent.mDividerControllerLocked.isResizing();
        if (useCurrentBounds()) {
            if (inFreeformWindowingMode() && getMaxVisibleBounds(out)) {
                return;
            }

            if (!matchParentBounds()) {
                // When minimizing the docked stack when going home, we don't adjust the task bounds
                // so we need to intersect the task bounds with the stack bounds here.
                //
                // If we are Docked Resizing with snap points, the task bounds could be smaller than the stack
                // bounds and so we don't even want to use them. Even if the app should not be resized the Dim
                // should keep up with the divider.
                if (dockedResizing) {
                    mStack.getBounds(out);
                } else {
                    mStack.getBounds(mTmpRect);
                    mTmpRect.intersect(getBounds());
                    out.set(mTmpRect);
                }
            } else {
                out.set(getBounds());
            }
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack is
        // not currently visible. Go ahead a represent it as fullscreen to the rest of the system.
        if (displayContent != null) {
            displayContent.getBounds(out);
        }
    }

    void setDragResizing(boolean dragResizing, int dragResizeMode) {
        if (mDragResizing != dragResizing) {
            if (!DragResizeMode.isModeAllowedForStack(mStack, dragResizeMode)) {
                throw new IllegalArgumentException("Drag resize mode not allow for stack stackId="
                        + mStack.mStackId + " dragResizeMode=" + dragResizeMode);
            }
            mDragResizing = dragResizing;
            mDragResizeMode = dragResizeMode;
            resetDragResizingChangeReported();
        }
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    int getDragResizeMode() {
        return mDragResizeMode;
    }

    void updateDisplayInfo(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (matchParentBounds()) {
            // TODO: Yeah...not sure if this works with WindowConfiguration, but shouldn't be a
            // problem once we move mBounds into WindowConfiguration.
            setBounds(null);
            return;
        }
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (mRotation == newRotation) {
            return;
        }

        // Device rotation changed.
        // - We don't want the task to move around on the screen when this happens, so update the
        //   task bounds so it stays in the same place.
        // - Rotate the bounds and notify activity manager if the task can be resized independently
        //   from its stack. The stack will take care of task rotation for the other case.
        mTmpRect2.set(getBounds());

        if (!getWindowConfiguration().canResizeTask()) {
            setBounds(mTmpRect2);
            return;
        }

        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        if (setBounds(mTmpRect2) != BOUNDS_CHANGE_NONE) {
            final TaskWindowContainerController controller = getController();
            if (controller != null) {
                controller.requestResize(getBounds(), RESIZE_MODE_SYSTEM_SCREEN_ROTATION);
            }
        }
    }

    /** Cancels any running app transitions associated with the task. */
    void cancelTaskWindowTransition() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).cancelAnimation();
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mChildren.size();
        return (tokensCount != 0) && mChildren.get(tokensCount - 1).mShowForAllUsers;
    }

    /**
     * When we are in a floating stack (Freeform, Pinned, ...) we calculate
     * insets differently. However if we are animating to the fullscreen stack
     * we need to begin calculating insets as if we were fullscreen, otherwise
     * we will have a jump at the end.
     */
    boolean isFloating() {
        return getWindowConfiguration().tasksAreFloating()
                && !mStack.isAnimatingBoundsToFullscreen() && !mPreserveNonFloatingState;
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        // Reparent to the animation layer so that we aren't clipped by the non-minimized
        // stack bounds, currently we only animate the task for the recents animation
        return getAppAnimationLayer(false /* boosted */);
    }

    boolean isTaskAnimating() {
        final RecentsAnimationController recentsAnim = mService.getRecentsAnimationController();
        if (recentsAnim != null) {
            if (recentsAnim.isAnimatingTask(this)) {
                return true;
            }
        }
        return false;
    }

    WindowState getTopVisibleAppMainWindow() {
        final AppWindowToken token = getTopVisibleAppToken();
        return token != null ? token.findMainWindow() : null;
    }

    AppWindowToken getTopFullscreenAppToken() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mChildren.get(i);
            final WindowState win = token.findMainWindow();
            if (win != null && win.mAttrs.isFullscreen()) {
                return token;
            }
        }
        return null;
    }

    AppWindowToken getTopVisibleAppToken() {
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken token = mChildren.get(i);
            // skip hidden (or about to hide) apps
            if (!token.mIsExiting && !token.isClientHidden() && !token.hiddenRequested) {
                return token;
            }
        }
        return null;
    }

    boolean isFullscreen() {
        if (useCurrentBounds()) {
            return matchParentBounds();
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    void forceWindowsScaleable(boolean force) {
        mService.openSurfaceTransaction();
        try {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                mChildren.get(i).forceWindowsScaleableInTransaction(force);
            }
        } finally {
            mService.closeSurfaceTransaction("forceWindowsScaleable");
        }
    }

    void setTaskDescription(TaskDescription taskDescription) {
        mTaskDescription = taskDescription;
    }

    TaskDescription getTaskDescription() {
        return mTaskDescription;
    }

    @Override
    boolean fillsParent() {
        return matchParentBounds() || !getWindowConfiguration().canResizeTask();
    }

    @Override
    TaskWindowContainerController getController() {
        return (TaskWindowContainerController) super.getController();
    }

    @Override
    void forAllTasks(Consumer<Task> callback) {
        callback.accept(this);
    }

    /**
     * @param canAffectSystemUiFlags If false, all windows in this task can not affect SystemUI
     *                               flags. See {@link WindowState#canAffectSystemUiFlags()}.
     */
    void setCanAffectSystemUiFlags(boolean canAffectSystemUiFlags) {
        mCanAffectSystemUiFlags = canAffectSystemUiFlags;
    }

    /**
     * @see #setCanAffectSystemUiFlags
     */
    boolean canAffectSystemUiFlags() {
        return mCanAffectSystemUiFlags;
    }

    void dontAnimateDimExit() {
        mDimmer.dontAnimateExit();
    }

    @Override
    public String toString() {
        return "{taskId=" + mTaskId + " appTokens=" + mChildren + " mdr=" + mDeferRemoval + "}";
    }

    String getName() {
        return toShortString();
    }

    void clearPreserveNonFloatingState() {
        mPreserveNonFloatingState = false;
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

    @CallSuper
    @Override
    public void writeToProto(ProtoOutputStream proto, long fieldId, boolean trim) {
        final long token = proto.start(fieldId);
        super.writeToProto(proto, WINDOW_CONTAINER, trim);
        proto.write(ID, mTaskId);
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken appWindowToken = mChildren.get(i);
            appWindowToken.writeToProto(proto, APP_WINDOW_TOKENS, trim);
        }
        proto.write(FILLS_PARENT, matchParentBounds());
        getBounds().writeToProto(proto, BOUNDS);
        mTempInsetBounds.writeToProto(proto, TEMP_INSET_BOUNDS);
        proto.write(DEFER_REMOVAL, mDeferRemoval);
        proto.end(token);
    }

    @Override
    public void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        final String doublePrefix = prefix + "  ";

        pw.println(prefix + "taskId=" + mTaskId);
        pw.println(doublePrefix + "mBounds=" + getBounds().toShortString());
        pw.println(doublePrefix + "mdr=" + mDeferRemoval);
        pw.println(doublePrefix + "appTokens=" + mChildren);
        pw.println(doublePrefix + "mTempInsetBounds=" + mTempInsetBounds.toShortString());

        final String triplePrefix = doublePrefix + "  ";
        final String quadruplePrefix = triplePrefix + "  ";

        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final AppWindowToken wtoken = mChildren.get(i);
            pw.println(triplePrefix + "Activity #" + i + " " + wtoken);
            wtoken.dump(pw, quadruplePrefix, dumpAll);
        }
    }

    String toShortString() {
        return "Task=" + mTaskId;
    }
}
