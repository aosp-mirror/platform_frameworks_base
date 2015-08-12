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

import static com.android.server.wm.WindowManagerService.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerService.TAG;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.util.DisplayMetrics;
import android.util.EventLog;
import android.util.Slog;
import android.util.TypedValue;
import android.view.Surface;

import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;

public class TaskStack {
    /** Amount of time in milliseconds to animate the dim surface from one value to another,
     * when no window animation is driving it. */
    private static final int DEFAULT_DIM_DURATION = 200;

    /** Unique identifier */
    final int mStackId;

    /** The service */
    private final WindowManagerService mService;

    /** The display this stack sits under. */
    private DisplayContent mDisplayContent;

    /** The Tasks that define this stack. Oldest Tasks are at the bottom. The ordering must match
     * mTaskHistory in the ActivityStack with the same mStackId */
    private final ArrayList<Task> mTasks = new ArrayList<Task>();

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    /** For handling display rotations. */
    private Rect mTmpRect2 = new Rect();

    /** Content limits relative to the DisplayContent this sits in. */
    private Rect mBounds = new Rect();

    /** Whether mBounds is fullscreen */
    private boolean mFullscreen = true;

    /** Used to support {@link android.view.WindowManager.LayoutParams#FLAG_DIM_BEHIND} */
    private DimLayer mDimLayer;

    /** The particular window with FLAG_DIM_BEHIND set. If null, hide mDimLayer. */
    WindowStateAnimator mDimWinAnimator;

    /** Support for non-zero {@link android.view.animation.Animation#getBackgroundColor()} */
    DimLayer mAnimationBackgroundSurface;

    /** The particular window with an Animation with non-zero background color. */
    WindowStateAnimator mAnimationBackgroundAnimator;

    /** Set to false at the start of performLayoutAndPlaceSurfaces. If it is still false by the end
     * then stop any dimming. */
    boolean mDimmingTag;

    /** Application tokens that are exiting, but still on screen for animations. */
    final AppTokenList mExitingAppTokens = new AppTokenList();

    /** Detach this stack from its display when animation completes. */
    boolean mDeferDetach;

    // Contains configurations settings that are different from the global configuration due to
    // stack specific operations. E.g. {@link #setBounds}.
    Configuration mOverrideConfig;
    // True if the stack was forced to fullscreen disregarding the override configuration.
    private boolean mForceFullscreen;
    // The {@link #mBounds} before the stack was forced to fullscreen. Will be restored as the
    // stack bounds once the stack is no longer forced to fullscreen.
    final private Rect mPreForceFullscreenBounds;

    // Device rotation as of the last time {@link #mBounds} was set.
    int mRotation;

    TaskStack(WindowManagerService service, int stackId) {
        mService = service;
        mStackId = stackId;
        mOverrideConfig = Configuration.EMPTY;
        mForceFullscreen = false;
        mPreForceFullscreenBounds = new Rect();
        // TODO: remove bounds from log, they are always 0.
        EventLog.writeEvent(EventLogTags.WM_STACK_CREATED, stackId, mBounds.left, mBounds.top,
                mBounds.right, mBounds.bottom);
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    ArrayList<Task> getTasks() {
        return mTasks;
    }

    void resizeWindows() {
        final ArrayList<WindowState> resizingWindows = mService.mResizingWindows;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<AppWindowToken> activities = mTasks.get(taskNdx).mAppTokens;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ArrayList<WindowState> windows = activities.get(activityNdx).allAppWindows;
                for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                    final WindowState win = windows.get(winNdx);
                    if (!resizingWindows.contains(win)) {
                        if (WindowManagerService.DEBUG_RESIZE) Slog.d(TAG,
                                "setBounds: Resizing " + win);
                        resizingWindows.add(win);
                    }
                }
            }
        }
    }

    /** Set the stack bounds. Passing in null sets the bounds to fullscreen. */
    boolean setBounds(Rect bounds) {
        boolean oldFullscreen = mFullscreen;
        int rotation = Surface.ROTATION_0;
        if (mDisplayContent != null) {
            mDisplayContent.getLogicalDisplayRect(mTmpRect);
            rotation = mDisplayContent.getDisplayInfo().rotation;
            if (bounds == null) {
                bounds = mTmpRect;
                mFullscreen = true;
            } else {
                // ensure bounds are entirely within the display rect
                if (!bounds.intersect(mTmpRect)) {
                    // Can't set bounds outside the containing display.. Sorry!
                    return false;
                }
                mFullscreen = mTmpRect.equals(bounds);
            }
        }

        if (bounds == null) {
            // Can't set to fullscreen if we don't have a display to get bounds from...
            return false;
        }
        if (mBounds.equals(bounds) && oldFullscreen == mFullscreen && mRotation == rotation) {
            return false;
        }

        mDimLayer.setBounds(bounds);
        mAnimationBackgroundSurface.setBounds(bounds);
        mBounds.set(bounds);
        mRotation = rotation;
        updateOverrideConfiguration();
        return true;
    }

    void getBounds(Rect out) {
        out.set(mBounds);
    }

    private void updateOverrideConfiguration() {
        final Configuration serviceConfig = mService.mCurConfiguration;
        if (mFullscreen) {
            mOverrideConfig = Configuration.EMPTY;
            return;
        }

        if (mOverrideConfig == Configuration.EMPTY) {
            mOverrideConfig  = new Configuration();
        }

        // TODO(multidisplay): Update Dp to that of display stack is on.
        final float density = serviceConfig.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        mOverrideConfig.screenWidthDp =
                Math.min((int)(mBounds.width() / density), serviceConfig.screenWidthDp);
        mOverrideConfig.screenHeightDp =
                Math.min((int)(mBounds.height() / density), serviceConfig.screenHeightDp);
        mOverrideConfig.smallestScreenWidthDp =
                Math.min(mOverrideConfig.screenWidthDp, mOverrideConfig.screenHeightDp);
        mOverrideConfig.orientation =
                (mOverrideConfig.screenWidthDp <= mOverrideConfig.screenHeightDp)
                        ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
    }

    void updateDisplayInfo() {
        if (mFullscreen) {
            setBounds(null);
        } else if (mDisplayContent != null) {
            final int newRotation = mDisplayContent.getDisplayInfo().rotation;
            if (mRotation == newRotation) {
                return;
            }

            // Device rotation changed. We don't want the stack to move around on the screen when
            // this happens, so update the stack bounds so it stays in the same place.
            final int rotationDelta = DisplayContent.deltaRotation(mRotation, newRotation);
            mDisplayContent.getLogicalDisplayRect(mTmpRect);
            switch (rotationDelta) {
                case Surface.ROTATION_0:
                    mTmpRect2.set(mBounds);
                    break;
                case Surface.ROTATION_90:
                    mTmpRect2.top = mTmpRect.bottom - mBounds.right;
                    mTmpRect2.left = mBounds.top;
                    mTmpRect2.right = mTmpRect2.left + mBounds.height();
                    mTmpRect2.bottom = mTmpRect2.top + mBounds.width();
                    break;
                case Surface.ROTATION_180:
                    mTmpRect2.top = mTmpRect.bottom - mBounds.bottom;
                    mTmpRect2.left = mTmpRect.right - mBounds.right;
                    mTmpRect2.right = mTmpRect2.left + mBounds.width();
                    mTmpRect2.bottom = mTmpRect2.top + mBounds.height();
                    break;
                case Surface.ROTATION_270:
                    mTmpRect2.top = mBounds.left;
                    mTmpRect2.left = mTmpRect.right - mBounds.bottom;
                    mTmpRect2.right = mTmpRect2.left + mBounds.height();
                    mTmpRect2.bottom = mTmpRect2.top + mBounds.width();
                    break;
            }
            setBounds(mTmpRect2);
        }
    }

    boolean isFullscreen() {
        return mFullscreen;
    }

    /** Forces the stack to fullscreen if input is true, else un-forces the stack from fullscreen.
     * Returns true if something happened.
     */
    boolean forceFullscreen(boolean forceFullscreen) {
        if (mForceFullscreen == forceFullscreen) {
            return false;
        }
        mForceFullscreen = forceFullscreen;
        if (forceFullscreen) {
            if (mFullscreen) {
                return false;
            }
            mPreForceFullscreenBounds.set(mBounds);
            return setBounds(null);
        } else {
            if (!mFullscreen || mPreForceFullscreenBounds.isEmpty()) {
                return false;
            }
            return setBounds(mPreForceFullscreenBounds);
        }
    }

    boolean isAnimating() {
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final ArrayList<AppWindowToken> activities = mTasks.get(taskNdx).mAppTokens;
            for (int activityNdx = activities.size() - 1; activityNdx >= 0; --activityNdx) {
                final ArrayList<WindowState> windows = activities.get(activityNdx).allAppWindows;
                for (int winNdx = windows.size() - 1; winNdx >= 0; --winNdx) {
                    final WindowStateAnimator winAnimator = windows.get(winNdx).mWinAnimator;
                    if (winAnimator.isAnimating() || winAnimator.mWin.mExiting) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    void addTask(Task task, boolean toTop) {
        addTask(task, toTop, task.showForAllUsers());
    }

    /**
     * Put a Task in this stack. Used for adding and moving.
     * @param task The task to add.
     * @param toTop Whether to add it to the top or bottom.
     * @param showForAllUsers Whether to show the task regardless of the current user.
     */
    void addTask(Task task, boolean toTop, boolean showForAllUsers) {
        int stackNdx;
        if (!toTop) {
            stackNdx = 0;
        } else {
            stackNdx = mTasks.size();
            if (!showForAllUsers && !mService.isCurrentProfileLocked(task.mUserId)) {
                // Place the task below all current user tasks.
                while (--stackNdx >= 0) {
                    final Task tmpTask = mTasks.get(stackNdx);
                    if (!tmpTask.showForAllUsers()
                            || !mService.isCurrentProfileLocked(tmpTask.mUserId)) {
                        break;
                    }
                }
                // Put it above first non-current user task.
                ++stackNdx;
            }
        }
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "addTask: task=" + task + " toTop=" + toTop
                + " pos=" + stackNdx);
        mTasks.add(stackNdx, task);

        task.mStack = this;
        if (toTop) {
            mDisplayContent.moveStack(this, true);
        }
        EventLog.writeEvent(EventLogTags.WM_TASK_MOVED, task.mTaskId, toTop ? 1 : 0, stackNdx);
    }

    void moveTaskToTop(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "moveTaskToTop: task=" + task + " Callers="
                + Debug.getCallers(6));
        mTasks.remove(task);
        addTask(task, true);
    }

    void moveTaskToBottom(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "moveTaskToBottom: task=" + task);
        mTasks.remove(task);
        addTask(task, false);
    }

    /**
     * Delete a Task from this stack. If it is the last Task in the stack, move this stack to the
     * back.
     * @param task The Task to delete.
     */
    void removeTask(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "removeTask: task=" + task);
        mTasks.remove(task);
        if (mDisplayContent != null) {
            if (mTasks.isEmpty()) {
                mDisplayContent.moveStack(this, false);
            }
            mDisplayContent.layoutNeeded = true;
        }
        for (int appNdx = mExitingAppTokens.size() - 1; appNdx >= 0; --appNdx) {
            final AppWindowToken wtoken = mExitingAppTokens.get(appNdx);
            if (wtoken.mTask == task) {
                wtoken.mIsExiting = false;
                mExitingAppTokens.remove(appNdx);
            }
        }
    }

    void attachDisplayContent(DisplayContent displayContent) {
        if (mDisplayContent != null) {
            throw new IllegalStateException("attachDisplayContent: Already attached");
        }

        mDisplayContent = displayContent;
        mDimLayer = new DimLayer(mService, this, displayContent);
        mAnimationBackgroundSurface = new DimLayer(mService, this, displayContent);
        updateDisplayInfo();
    }

    void detachDisplay() {
        EventLog.writeEvent(EventLogTags.WM_STACK_REMOVED, mStackId);

        boolean doAnotherLayoutPass = false;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final AppTokenList appWindowTokens = mTasks.get(taskNdx).mAppTokens;
            for (int appNdx = appWindowTokens.size() - 1; appNdx >= 0; --appNdx) {
                final WindowList appWindows = appWindowTokens.get(appNdx).allAppWindows;
                for (int winNdx = appWindows.size() - 1; winNdx >= 0; --winNdx) {
                    mService.removeWindowInnerLocked(appWindows.get(winNdx));
                    doAnotherLayoutPass = true;
                }
            }
        }
        if (doAnotherLayoutPass) {
            mService.requestTraversalLocked();
        }

        close();
    }

    void resetAnimationBackgroundAnimator() {
        mAnimationBackgroundAnimator = null;
        mAnimationBackgroundSurface.hide();
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

    void resetDimmingTag() {
        mDimmingTag = false;
    }

    void setDimmingTag() {
        mDimmingTag = true;
    }

    boolean testDimmingTag() {
        return mDimmingTag;
    }

    boolean isDimming() {
        return mDimLayer.isDimming();
    }

    boolean isDimming(WindowStateAnimator winAnimator) {
        return mDimWinAnimator == winAnimator && mDimLayer.isDimming();
    }

    void startDimmingIfNeeded(WindowStateAnimator newWinAnimator) {
        // Only set dim params on the highest dimmed layer.
        // Don't turn on for an unshown surface, or for any layer but the highest dimmed layer.
        if (newWinAnimator.mSurfaceShown && (mDimWinAnimator == null
                || !mDimWinAnimator.mSurfaceShown
                || mDimWinAnimator.mAnimLayer < newWinAnimator.mAnimLayer)) {
            mDimWinAnimator = newWinAnimator;
            if (mDimWinAnimator.mWin.mAppToken == null
                    && !mFullscreen && mDisplayContent != null) {
                // Dim should cover the entire screen for system windows.
                mDisplayContent.getLogicalDisplayRect(mTmpRect);
                mDimLayer.setBounds(mTmpRect);
            }
        }
    }

    void stopDimmingIfNeeded() {
        if (!mDimmingTag && isDimming()) {
            mDimWinAnimator = null;
            mDimLayer.setBounds(mBounds);
        }
    }

    void setAnimationBackground(WindowStateAnimator winAnimator, int color) {
        int animLayer = winAnimator.mAnimLayer;
        if (mAnimationBackgroundAnimator == null
                || animLayer < mAnimationBackgroundAnimator.mAnimLayer) {
            mAnimationBackgroundAnimator = winAnimator;
            animLayer = mService.adjustAnimationBackground(winAnimator);
            mAnimationBackgroundSurface.show(animLayer - WindowManagerService.LAYER_OFFSET_DIM,
                    ((color >> 24) & 0xff) / 255f, 0);
        }
    }

    void switchUser() {
        int top = mTasks.size();
        for (int taskNdx = 0; taskNdx < top; ++taskNdx) {
            Task task = mTasks.get(taskNdx);
            if (mService.isCurrentProfileLocked(task.mUserId) || task.showForAllUsers()) {
                mTasks.remove(taskNdx);
                mTasks.add(task);
                --top;
            }
        }
    }

    void close() {
        if (mAnimationBackgroundSurface != null) {
            mAnimationBackgroundSurface.destroySurface();
            mAnimationBackgroundSurface = null;
        }
        if (mDimLayer != null) {
            mDimLayer.destroySurface();
            mDimLayer = null;
        }
        mDisplayContent = null;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mStackId="); pw.println(mStackId);
        pw.print(prefix); pw.print("mDeferDetach="); pw.println(mDeferDetach);
        for (int taskNdx = 0; taskNdx < mTasks.size(); ++taskNdx) {
            pw.print(prefix); pw.println(mTasks.get(taskNdx));
        }
        if (mAnimationBackgroundSurface.isDimming()) {
            pw.print(prefix); pw.println("mWindowAnimationBackgroundSurface:");
            mAnimationBackgroundSurface.printTo(prefix + "  ", pw);
        }
        if (mDimLayer.isDimming()) {
            pw.print(prefix); pw.println("mDimLayer:");
            mDimLayer.printTo(prefix + " ", pw);
            pw.print(prefix); pw.print("mDimWinAnimator="); pw.println(mDimWinAnimator);
        }
        if (!mExitingAppTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i=mExitingAppTokens.size()-1; i>=0; i--) {
                WindowToken token = mExitingAppTokens.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mTasks + "}";
    }
}
