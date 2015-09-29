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

import static android.app.ActivityManager.DOCKED_STACK_ID;
import static com.android.server.wm.WindowManagerService.TAG;
import static com.android.server.wm.WindowManagerService.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerService.DEBUG_STACK;
import static android.app.ActivityManager.FREEFORM_WORKSPACE_STACK_ID;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;

class Task implements DimLayer.DimLayerUser {
    /** Amount of time in milliseconds to animate the dim surface from one value to another,
     * when no window animation is driving it. */
    private static final int DEFAULT_DIM_DURATION = 200;

    // Return value from {@link setBounds} indicating no change was made to the Task bounds.
    static final int BOUNDS_CHANGE_NONE = 0;
    // Return value from {@link setBounds} indicating the position of the Task bounds changed.
    static final int BOUNDS_CHANGE_POSITION = 1;
    // Return value from {@link setBounds} indicating the size of the Task bounds changed.
    static final int BOUNDS_CHANGE_SIZE = 1 << 1;

    TaskStack mStack;
    final AppTokenList mAppTokens = new AppTokenList();
    final int mTaskId;
    final int mUserId;
    boolean mDeferRemoval = false;
    final WindowManagerService mService;

    // Content limits relative to the DisplayContent this sits in.
    private Rect mBounds = new Rect();

    // Device rotation as of the last time {@link #mBounds} was set.
    int mRotation;

    // Whether mBounds is fullscreen
    private boolean mFullscreen = true;

    // Contains configurations settings that are different from the global configuration due to
    // stack specific operations. E.g. {@link #setBounds}.
    Configuration mOverrideConfig;

    // For comparison with DisplayContent bounds.
    private Rect mTmpRect = new Rect();
    // For handling display rotations.
    private Rect mTmpRect2 = new Rect();

    // Whether the task is currently being drag-resized
    private boolean mDragResizing;

    // The particular window with FLAG_DIM_BEHIND set. If null, hide mDimLayer.
    WindowStateAnimator mDimWinAnimator;
    // Used to support {@link android.view.WindowManager.LayoutParams#FLAG_DIM_BEHIND}
    private DimLayer mDimLayer;
    // Set to false at the start of performLayoutAndPlaceSurfaces. If it is still false by the end
    // then stop any dimming.
    private boolean mContinueDimming;
    // Shared dim layer for fullscreen tasks. {@link #mDimLayer} will point to this instead
    // of creating a new object per fullscreen task on a display.
    private static final SparseArray<DimLayer> sSharedFullscreenDimLayers = new SparseArray<>();

    Task(int taskId, TaskStack stack, int userId, WindowManagerService service, Rect bounds,
            Configuration config) {
        mTaskId = taskId;
        mStack = stack;
        mUserId = userId;
        mService = service;
        setBounds(bounds, config);
    }

    DisplayContent getDisplayContent() {
        return mStack.getDisplayContent();
    }

    void addAppToken(int addPos, AppWindowToken wtoken) {
        final int lastPos = mAppTokens.size();
        if (addPos >= lastPos) {
            addPos = lastPos;
        } else {
            for (int pos = 0; pos < lastPos && pos < addPos; ++pos) {
                if (mAppTokens.get(pos).removed) {
                    // addPos assumes removed tokens are actually gone.
                    ++addPos;
                }
            }
        }
        mAppTokens.add(addPos, wtoken);
        wtoken.mTask = this;
        mDeferRemoval = false;
    }

    void removeLocked() {
        if (!mAppTokens.isEmpty() && mStack.isAnimating()) {
            if (DEBUG_STACK) Slog.i(TAG, "removeTask: deferring removing taskId=" + mTaskId);
            mDeferRemoval = true;
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "removeTask: removing taskId=" + mTaskId);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "removeTask");
        mDeferRemoval = false;
        mStack.removeTask(this);
        mService.mTaskIdToTask.delete(mTaskId);
    }

    void moveTaskToStack(TaskStack stack, boolean toTop) {
        if (stack == mStack) {
            return;
        }
        if (DEBUG_STACK) Slog.i(TAG, "moveTaskToStack: removing taskId=" + mTaskId
                + " from stack=" + mStack);
        EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "moveTask");
        if (mStack != null) {
            mStack.removeTask(this);
        }
        stack.addTask(this, toTop);
    }

    void positionTaskInStack(TaskStack stack, int position) {
        if (mStack != null && stack != mStack) {
            if (DEBUG_STACK) Slog.i(TAG, "positionTaskInStack: removing taskId=" + mTaskId
                    + " from stack=" + mStack);
            EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId, "moveTask");
            mStack.removeTask(this);
        }
        stack.positionTask(this, position, showForAllUsers());
    }

    boolean removeAppToken(AppWindowToken wtoken) {
        boolean removed = mAppTokens.remove(wtoken);
        if (mAppTokens.size() == 0) {
            EventLog.writeEvent(EventLogTags.WM_TASK_REMOVED, mTaskId,
                    "removeAppToken: last token");
            if (mDeferRemoval) {
                removeLocked();
            }
        }
        wtoken.mTask = null;
        /* Leave mTaskId for now, it might be useful for debug
        wtoken.mTaskId = -1;
         */
        return removed;
    }

    void setSendingToBottom(boolean toBottom) {
        for (int appTokenNdx = 0; appTokenNdx < mAppTokens.size(); appTokenNdx++) {
            mAppTokens.get(appTokenNdx).sendingToBottom = toBottom;
        }
    }

    /** Set the task bounds. Passing in null sets the bounds to fullscreen. */
    int setBounds(Rect bounds, Configuration config) {
        if (config == null) {
            config = Configuration.EMPTY;
        }
        if (bounds == null && !Configuration.EMPTY.equals(config)) {
            throw new IllegalArgumentException("null bounds but non empty configuration: "
                    + config);
        }
        if (bounds != null && Configuration.EMPTY.equals(config)) {
            throw new IllegalArgumentException("non null bounds, but empty configuration");
        }
        boolean oldFullscreen = mFullscreen;
        int rotation = Surface.ROTATION_0;
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (displayContent != null) {
            displayContent.getLogicalDisplayRect(mTmpRect);
            rotation = displayContent.getDisplayInfo().rotation;
            if (bounds == null) {
                bounds = mTmpRect;
                mFullscreen = true;
            } else {
                if (mStack.mStackId != FREEFORM_WORKSPACE_STACK_ID || bounds.isEmpty()) {
                    // ensure bounds are entirely within the display rect
                    if (!bounds.intersect(mTmpRect)) {
                        // Can't set bounds outside the containing display...Sorry!
                        return BOUNDS_CHANGE_NONE;
                    }
                }
                mFullscreen = mTmpRect.equals(bounds);
            }
        }

        if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return BOUNDS_CHANGE_NONE;
        }
        if (mBounds.equals(bounds) && oldFullscreen == mFullscreen && mRotation == rotation) {
            return BOUNDS_CHANGE_NONE;
        }

        int boundsChange = BOUNDS_CHANGE_NONE;
        if (mBounds.left != bounds.left || mBounds.right != bounds.right) {
            boundsChange |= BOUNDS_CHANGE_POSITION;
        }
        if (mBounds.width() != bounds.width() || mBounds.height() != bounds.height()) {
            boundsChange |= BOUNDS_CHANGE_SIZE;
        }

        mBounds.set(bounds);
        mRotation = rotation;
        updateDimLayer();
        mOverrideConfig = mFullscreen ? Configuration.EMPTY : config;
        return boundsChange;
    }

    boolean resizeLocked(Rect bounds, Configuration configuration, boolean forced) {
        int boundsChanged = setBounds(bounds, configuration);
        if (forced) {
            boundsChanged |= BOUNDS_CHANGE_SIZE;
        }
        if (boundsChanged == BOUNDS_CHANGE_NONE) {
            return false;
        }
        if ((boundsChanged & BOUNDS_CHANGE_SIZE) == BOUNDS_CHANGE_SIZE) {
            resizeWindows();
        }
        return true;
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        final DisplayContent displayContent = mStack.getDisplayContent();
        if (mFullscreen
                || mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID
                || mStack.mStackId == DOCKED_STACK_ID
                || displayContent == null
                || displayContent.getDockedStackLocked() != null) {
            return true;
        }
        return false;
    }

    /** Bounds of the task with other system factors taken into consideration. */
    void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // No need to adjust the output bounds if fullscreen or the docked stack is visible
            // since it is already what we want to represent to the rest of the system.
            out.set(mBounds);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        mStack.getDisplayContent().getLogicalDisplayRect(out);
    }

    void setDragResizing(boolean dragResizing) {
        mDragResizing = dragResizing;
    }

    boolean isDragResizing() {
        return mDragResizing;
    }

    void updateDisplayInfo(final DisplayContent displayContent) {
        if (displayContent == null) {
            return;
        }
        if (mFullscreen) {
            setBounds(null, Configuration.EMPTY);
            return;
        }
        final int newRotation = displayContent.getDisplayInfo().rotation;
        if (mRotation == newRotation) {
            return;
        }

        // Device rotation changed. We don't want the task to move around on the screen when
        // this happens, so update the task bounds so it stays in the same place.
        mTmpRect2.set(mBounds);
        displayContent.rotateBounds(mRotation, newRotation, mTmpRect2);
        setBounds(mTmpRect2, mOverrideConfig);
    }

    /** Updates the dim layer bounds, recreating it if needed. */
    private void updateDimLayer() {
        DimLayer newDimLayer;
        final boolean previousFullscreen =
                mDimLayer != null && sSharedFullscreenDimLayers.indexOfValue(mDimLayer) > -1;
        final int displayId = mStack.getDisplayContent().getDisplayId();
        if (mFullscreen) {
            if (previousFullscreen) {
                // Nothing to do here...
                return;
            }
            // Use shared fullscreen dim layer
            newDimLayer = sSharedFullscreenDimLayers.get(displayId);
            if (newDimLayer == null) {
                if (mDimLayer != null) {
                    // Re-purpose the previous dim layer.
                    newDimLayer = mDimLayer;
                } else {
                    // Create new full screen dim layer.
                    newDimLayer = new DimLayer(mService, this, displayId);
                }
                newDimLayer.setBounds(mBounds);
                sSharedFullscreenDimLayers.put(displayId, newDimLayer);
            } else if (mDimLayer != null) {
                mDimLayer.destroySurface();
            }
        } else {
            newDimLayer = (mDimLayer == null || previousFullscreen)
                    ? new DimLayer(mService, this, displayId) : mDimLayer;
            newDimLayer.setBounds(mBounds);
        }
        mDimLayer = newDimLayer;
    }

    boolean animateDimLayers() {
        final int dimLayer;
        final float dimAmount;
        if (mDimWinAnimator == null) {
            dimLayer = mDimLayer.getLayer();
            dimAmount = 0;
        } else {
            dimLayer = mDimWinAnimator.mAnimLayer - WindowManagerService.LAYER_OFFSET_DIM;
            dimAmount = mDimWinAnimator.mWin.mAttrs.dimAmount;
        }
        final float targetAlpha = mDimLayer.getTargetAlpha();
        if (targetAlpha != dimAmount) {
            if (mDimWinAnimator == null) {
                mDimLayer.hide(DEFAULT_DIM_DURATION);
            } else {
                long duration = (mDimWinAnimator.mAnimating && mDimWinAnimator.mAnimation != null)
                        ? mDimWinAnimator.mAnimation.computeDurationHint()
                        : DEFAULT_DIM_DURATION;
                if (targetAlpha > dimAmount) {
                    duration = getDimBehindFadeDuration(duration);
                }
                mDimLayer.show(dimLayer, dimAmount, duration);
            }
        } else if (mDimLayer.getLayer() != dimLayer) {
            mDimLayer.setLayer(dimLayer);
        }
        if (mDimLayer.isAnimating()) {
            if (!mService.okToDisplay()) {
                // Jump to the end of the animation.
                mDimLayer.show();
            } else {
                return mDimLayer.stepAnimation();
            }
        }
        return false;
    }

    private long getDimBehindFadeDuration(long duration) {
        TypedValue tv = new TypedValue();
        mService.mContext.getResources().getValue(
                com.android.internal.R.fraction.config_dimBehindFadeDuration, tv, true);
        if (tv.type == TypedValue.TYPE_FRACTION) {
            duration = (long)tv.getFraction(duration, duration);
        } else if (tv.type >= TypedValue.TYPE_FIRST_INT && tv.type <= TypedValue.TYPE_LAST_INT) {
            duration = tv.data;
        }
        return duration;
    }

    void clearContinueDimming() {
        mContinueDimming = false;
    }

    void setContinueDimming() {
        mContinueDimming = true;
    }

    boolean getContinueDimming() {
        return mContinueDimming;
    }

    boolean isDimming() {
        return mDimLayer.isDimming();
    }

    boolean isDimming(WindowStateAnimator winAnimator) {
        return mDimWinAnimator == winAnimator && isDimming();
    }

    void startDimmingIfNeeded(WindowStateAnimator newWinAnimator) {
        // Only set dim params on the highest dimmed layer.
        // Don't turn on for an unshown surface, or for any layer but the highest dimmed layer.
        if (newWinAnimator.mSurfaceShown && (mDimWinAnimator == null
                || !mDimWinAnimator.mSurfaceShown
                || mDimWinAnimator.mAnimLayer < newWinAnimator.mAnimLayer)) {
            mDimWinAnimator = newWinAnimator;
            if (mDimWinAnimator.mWin.mAppToken == null
                    && !mFullscreen && mStack.getDisplayContent() != null) {
                // Dim should cover the entire screen for system windows.
                mStack.getDisplayContent().getLogicalDisplayRect(mTmpRect);
                mDimLayer.setBounds(mTmpRect);
            }
        }
    }

    void stopDimmingIfNeeded() {
        if (!mContinueDimming && isDimming()) {
            mDimWinAnimator = null;
            mDimLayer.setBounds(mBounds);
        }
    }

    void close() {
        if (mDimLayer != null) {
            mDimLayer.destroySurface();
            mDimLayer = null;
        }
    }

    void resizeWindows() {
        final ArrayList<WindowState> resizingWindows = mService.mResizingWindows;
        for (int activityNdx = mAppTokens.size() - 1; activityNdx >= 0; --activityNdx) {
            final ArrayList<WindowState> windows = mAppTokens.get(activityNdx).allAppWindows;
            for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                final WindowState win = windows.get(winNdx);
                if (!resizingWindows.contains(win)) {
                    if (DEBUG_RESIZE) Slog.d(TAG, "setBounds: Resizing " + win);
                    resizingWindows.add(win);
                }
            }
        }
    }

    boolean showForAllUsers() {
        final int tokensCount = mAppTokens.size();
        return (tokensCount != 0) && mAppTokens.get(tokensCount - 1).showForAllUsers;
    }

    boolean inFreeformWorkspace() {
        return mStack != null && mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID;
    }

    WindowState getTopAppMainWindow() {
        final int tokensCount = mAppTokens.size();
        return tokensCount > 0 ? mAppTokens.get(tokensCount - 1).findMainWindow() : null;
    }

    @Override
    public boolean isFullscreen() {
        if (useCurrentBounds()) {
            return mFullscreen;
        }
        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        return true;
    }

    @Override
    public DisplayInfo getDisplayInfo() {
        return mStack.getDisplayContent().getDisplayInfo();
    }

    @Override
    public String toString() {
        return "{taskId=" + mTaskId + " appTokens=" + mAppTokens + " mdr=" + mDeferRemoval + "}";
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("taskId="); pw.print(mTaskId);
                pw.print(prefix); pw.print("appTokens="); pw.print(mAppTokens);
                pw.print(prefix); pw.print("mdr="); pw.println(mDeferRemoval);
        if (mDimLayer.isDimming()) {
            pw.print(prefix); pw.println("mDimLayer:");
            mDimLayer.printTo(prefix + " ", pw);
            pw.print(prefix); pw.print("mDimWinAnimator="); pw.println(mDimWinAnimator);
        } else {
            pw.println();
        }
    }
}
