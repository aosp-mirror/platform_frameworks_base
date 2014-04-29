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

import android.graphics.Rect;
import android.os.Debug;
import android.util.EventLog;
import android.util.Slog;
import android.util.TypedValue;
import com.android.server.EventLogTags;

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

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
    private final DisplayContent mDisplayContent;

    /** The Tasks that define this stack. Oldest Tasks are at the bottom. The ordering must match
     * mTaskHistory in the ActivityStack with the same mStackId */
    private final ArrayList<Task> mTasks = new ArrayList<Task>();

    /** The StackBox this sits in. */
    StackBox mStackBox;

    /** Used to support {@link android.view.WindowManager.LayoutParams#FLAG_DIM_BEHIND} */
    final DimLayer mDimLayer;

    /** The particular window with FLAG_DIM_BEHIND set. If null, hide mDimLayer. */
    WindowStateAnimator mDimWinAnimator;

    /** Support for non-zero {@link android.view.animation.Animation#getBackgroundColor()} */
    final DimLayer mAnimationBackgroundSurface;

    /** The particular window with an Animation with non-zero background color. */
    WindowStateAnimator mAnimationBackgroundAnimator;

    /** Set to false at the start of performLayoutAndPlaceSurfaces. If it is still false by the end
     * then stop any dimming. */
    boolean mDimmingTag;

    TaskStack(WindowManagerService service, int stackId, DisplayContent displayContent) {
        mService = service;
        mStackId = stackId;
        mDisplayContent = displayContent;
        mDimLayer = new DimLayer(service, this);
        mAnimationBackgroundSurface = new DimLayer(service, this);
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    ArrayList<Task> getTasks() {
        return mTasks;
    }

    boolean isHomeStack() {
        return mStackId == HOME_STACK_ID;
    }

    boolean hasSibling() {
        return mStackBox.mParent != null;
    }

    /**
     * Put a Task in this stack. Used for adding and moving.
     * @param task The task to add.
     * @param toTop Whether to add it to the top or bottom.
     */
    boolean addTask(Task task, boolean toTop) {
        mStackBox.makeDirty();

        int stackNdx;
        if (!toTop) {
            stackNdx = 0;
        } else {
            stackNdx = mTasks.size();
            final int currentUserId = mService.mCurrentUserId;
            if (task.mUserId != currentUserId) {
                // Place the task below all current user tasks.
                while (--stackNdx >= 0) {
                    if (currentUserId != mTasks.get(stackNdx).mUserId) {
                        break;
                    }
                }
                ++stackNdx;
            }
        }
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "addTask: task=" + task + " toTop=" + toTop
                + " pos=" + stackNdx);
        mTasks.add(stackNdx, task);

        task.mStack = this;
        mDisplayContent.addTask(task, toTop);
        return mDisplayContent.moveHomeStackBox(mStackId == HOME_STACK_ID);
    }

    boolean moveTaskToTop(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "moveTaskToTop: task=" + task + " Callers="
                + Debug.getCallers(6));
        mTasks.remove(task);
        return addTask(task, true);
    }

    boolean moveTaskToBottom(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "moveTaskToBottom: task=" + task);
        mTasks.remove(task);
        return addTask(task, false);
    }

    /**
     * Delete a Task from this stack. If it is the last Task in the stack, remove this stack from
     * its parent StackBox and merge the parent.
     * @param task The Task to delete.
     */
    void removeTask(Task task) {
        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG, "removeTask: task=" + task);
        mStackBox.makeDirty();
        mTasks.remove(task);
        mDisplayContent.removeTask(task);
    }

    int remove() {
        mAnimationBackgroundSurface.destroySurface();
        mDimLayer.destroySurface();
        EventLog.writeEvent(EventLogTags.WM_STACK_REMOVED, mStackId);
        return mStackBox.remove();
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
        final WindowStateAnimator existingDimWinAnimator = mDimWinAnimator;
        // Don't turn on for an unshown surface, or for any layer but the highest dimmed layer.
        if (newWinAnimator.mSurfaceShown && (existingDimWinAnimator == null
                || !existingDimWinAnimator.mSurfaceShown
                || existingDimWinAnimator.mAnimLayer < newWinAnimator.mAnimLayer)) {
            mDimWinAnimator = newWinAnimator;
        }
    }

    void stopDimmingIfNeeded() {
        if (!mDimmingTag && isDimming()) {
            mDimWinAnimator = null;
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

    void setBounds(Rect bounds, boolean underStatusBar) {
        mDimLayer.setBounds(bounds);
        mAnimationBackgroundSurface.setBounds(bounds);

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
                    win.mUnderStatusBar = underStatusBar;
                }
            }
        }
    }

    void switchUser(int userId) {
        int top = mTasks.size();
        for (int taskNdx = 0; taskNdx < top; ++taskNdx) {
            Task task = mTasks.get(taskNdx);
            if (task.mUserId == userId) {
                mTasks.remove(taskNdx);
                mTasks.add(task);
                --top;
            }
        }
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mStackId="); pw.println(mStackId);
        for (int taskNdx = 0; taskNdx < mTasks.size(); ++taskNdx) {
            pw.print(prefix); pw.println(mTasks.get(taskNdx));
        }
        if (mAnimationBackgroundSurface.isDimming()) {
            pw.print(prefix); pw.println("mWindowAnimationBackgroundSurface:");
            mAnimationBackgroundSurface.printTo(prefix + "  ", pw);
        }
        if (mDimLayer.isDimming()) {
            pw.print(prefix); pw.println("mDimLayer:");
            mDimLayer.printTo(prefix, pw);
            pw.print(prefix); pw.print("mDimWinAnimator="); pw.println(mDimWinAnimator);
        }
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mTasks + "}";
    }
}
