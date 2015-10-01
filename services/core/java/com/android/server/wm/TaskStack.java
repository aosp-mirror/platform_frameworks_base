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

import static android.app.ActivityManager.*;
import static com.android.server.wm.WindowManagerService.DEBUG_TASK_MOVEMENT;
import static com.android.server.wm.WindowManagerService.H.RESIZE_STACK;
import static com.android.server.wm.WindowManagerService.TAG;

import android.annotation.IntDef;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Debug;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;

import android.view.Surface;
import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

public class TaskStack implements DimLayer.DimLayerUser {

    // If the stack should be resized to fullscreen.
    private static final boolean FULLSCREEN = true;

    /** Unique identifier */
    final int mStackId;

    /** The service */
    private final WindowManagerService mService;

    /** The display this stack sits under. */
    private DisplayContent mDisplayContent;

    /** The Tasks that define this stack. Oldest Tasks are at the bottom. The ordering must match
     * mTaskHistory in the ActivityStack with the same mStackId */
    private final ArrayList<Task> mTasks = new ArrayList<>();

    /** For comparison with DisplayContent bounds. */
    private Rect mTmpRect = new Rect();
    private Rect mTmpRect2 = new Rect();

    /** Content limits relative to the DisplayContent this sits in. */
    private Rect mBounds = new Rect();

    /** Whether mBounds is fullscreen */
    private boolean mFullscreen = true;

    // Device rotation as of the last time {@link #mBounds} was set.
    int mRotation;

    /** Support for non-zero {@link android.view.animation.Animation#getBackgroundColor()} */
    DimLayer mAnimationBackgroundSurface;

    /** The particular window with an Animation with non-zero background color. */
    WindowStateAnimator mAnimationBackgroundAnimator;

    /** Application tokens that are exiting, but still on screen for animations. */
    final AppTokenList mExitingAppTokens = new AppTokenList();

    /** Detach this stack from its display when animation completes. */
    boolean mDeferDetach;

    static final int DOCKED_INVALID = -1;
    static final int DOCKED_LEFT = 1;
    static final int DOCKED_TOP = 2;
    static final int DOCKED_RIGHT = 3;
    static final int DOCKED_BOTTOM = 4;

    @IntDef({
            DOCKED_INVALID,
            DOCKED_LEFT,
            DOCKED_TOP,
            DOCKED_RIGHT,
            DOCKED_BOTTOM})
    @Retention(RetentionPolicy.SOURCE)
    @interface DockSide {}

    TaskStack(WindowManagerService service, int stackId) {
        mService = service;
        mStackId = stackId;
        EventLog.writeEvent(EventLogTags.WM_STACK_CREATED, stackId);
    }

    DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    ArrayList<Task> getTasks() {
        return mTasks;
    }

    void resizeWindows() {
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            mTasks.get(taskNdx).resizeWindows();
        }
    }

    boolean allowTaskResize() {
        return mStackId == FREEFORM_WORKSPACE_STACK_ID
                || mStackId == DOCKED_STACK_ID;
    }

    /**
     * Set the bounds of the stack and its containing tasks.
     * @param stackBounds New stack bounds. Passing in null sets the bounds to fullscreen.
     * @param configs Configuration for individual tasks, keyed by task id.
     * @param taskBounds Bounds for individual tasks, keyed by task id.
     * @return True if the stack bounds was changed.
     * */
    boolean setBounds(
            Rect stackBounds, SparseArray<Configuration> configs, SparseArray<Rect> taskBounds) {
        if (!setBounds(stackBounds)) {
            return false;
        }

        // Update bounds of containing tasks.
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final Task task = mTasks.get(taskNdx);
            Configuration config = configs.get(task.mTaskId);
            if (config != null) {
                Rect bounds = taskBounds.get(task.mTaskId);
                task.setBounds(bounds, config);
            } else {
                Slog.wtf(TAG, "No config for task: " + task + ", is there a mismatch with AM?");
            }
        }
        return true;
    }

    private boolean setBounds(Rect bounds) {
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

        if (mDisplayContent != null) {
            mDisplayContent.mDimBehindController.updateDimLayer(this);
        }

        mAnimationBackgroundSurface.setBounds(bounds);
        mBounds.set(bounds);
        mRotation = rotation;
        return true;
    }

    /** Bounds of the stack without adjusting for other factors in the system like visibility
     * of docked stack.
     * Most callers should be using {@link #getBounds} as it take into consideration other system
     * factors. */
    void getRawBounds(Rect out) {
        out.set(mBounds);
    }

    /** Return true if the current bound can get outputted to the rest of the system as-is. */
    private boolean useCurrentBounds() {
        if (mFullscreen
                || mStackId == DOCKED_STACK_ID
                || mDisplayContent == null
                || mDisplayContent.getDockedStackLocked() != null) {
            return true;
        }
        return false;
    }

    /** Bounds of the stack with other system factors taken into consideration. */
    @Override
    public void getBounds(Rect out) {
        if (useCurrentBounds()) {
            // No need to adjust the output bounds if fullscreen or the docked stack is visible
            // since it is already what we want to represent to the rest of the system.
            out.set(mBounds);
            return;
        }

        // The bounds has been adjusted to accommodate for a docked stack, but the docked stack
        // is not currently visible. Go ahead a represent it as fullscreen to the rest of the
        // system.
        mDisplayContent.getLogicalDisplayRect(out);
    }

    void updateDisplayInfo(Rect bounds) {
        if (mDisplayContent != null) {
            for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
                mTasks.get(taskNdx).updateDisplayInfo(mDisplayContent);
            }
            if (bounds != null) {
                setBounds(bounds);
            } else if (mFullscreen) {
                setBounds(null);
            } else {
                mTmpRect2.set(mBounds);
                mDisplayContent.rotateBounds(
                        mRotation, mDisplayContent.getDisplayInfo().rotation, mTmpRect2);
                if (setBounds(mTmpRect2)) {
                    // Post message to inform activity manager of the bounds change simulating
                    // a one-way call. We do this to prevent a deadlock between window manager
                    // lock and activity manager lock been held.
                    mService.mH.sendMessage(
                            mService.mH.obtainMessage(RESIZE_STACK, mStackId, -1, mBounds));
                }
            }
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
        positionTask(task, toTop ? mTasks.size() : 0, showForAllUsers);
    }

    void positionTask(Task task, int position, boolean showForAllUsers) {
        final boolean canShowTask =
                showForAllUsers || mService.isCurrentProfileLocked(task.mUserId);
        mTasks.remove(task);
        int stackSize = mTasks.size();
        int minPosition = 0;
        int maxPosition = stackSize;

        if (canShowTask) {
            minPosition = computeMinPosition(minPosition, stackSize);
        } else {
            maxPosition = computeMaxPosition(maxPosition);
        }
        // Reset position based on minimum/maximum possible positions.
        position = Math.min(Math.max(position, minPosition), maxPosition);

        if (DEBUG_TASK_MOVEMENT) Slog.d(TAG,
                "positionTask: task=" + task + " position=" + position);
        mTasks.add(position, task);

        task.mStack = this;
        task.updateDisplayInfo(mDisplayContent);
        boolean toTop = position == mTasks.size() - 1;
        if (toTop) {
            mDisplayContent.moveStack(this, true);
        }
        EventLog.writeEvent(EventLogTags.WM_TASK_MOVED, task.mTaskId, toTop ? 1 : 0, position);
    }

    /** Calculate the minimum possible position for a task that can be shown to the user.
     *  The minimum position will be above all other tasks that can't be shown.
     *  @param minPosition The minimum position the caller is suggesting.
     *                  We will start adjusting up from here.
     *  @param size The size of the current task list.
     */
    private int computeMinPosition(int minPosition, int size) {
        while (minPosition < size) {
            final Task tmpTask = mTasks.get(minPosition);
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
            final Task tmpTask = mTasks.get(maxPosition - 1);
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
        mAnimationBackgroundSurface = new DimLayer(mService, this, mDisplayContent.getDisplayId());

        Rect bounds = null;
        final TaskStack dockedStack = mService.mStackIdToStack.get(DOCKED_STACK_ID);
        if (mStackId == DOCKED_STACK_ID || (dockedStack != null
                && mStackId >= FIRST_STATIC_STACK_ID && mStackId <= LAST_STATIC_STACK_ID)) {
            // The existence of a docked stack affects the size of any static stack created since
            // the docked stack occupies a dedicated region on screen.
            bounds = new Rect();
            displayContent.getLogicalDisplayRect(mTmpRect);
            mTmpRect2.setEmpty();
            if (dockedStack != null) {
                dockedStack.getRawBounds(mTmpRect2);
            }
            getInitialDockedStackBounds(mTmpRect, bounds, mStackId, mTmpRect2,
                    mDisplayContent.mDividerControllerLocked.getWidthAdjustment());
        }

        updateDisplayInfo(bounds);

        if (mStackId == DOCKED_STACK_ID) {
            // Attaching a docked stack to the display affects the size of all other static
            // stacks since the docked stack occupies a dedicated region on screen.
            // Resize existing static stacks so they are pushed to the side of the docked stack.
            resizeNonDockedStacks(!FULLSCREEN, mBounds);
        }
    }

    /**
     * Outputs the initial bounds a stack should be given the presence of a docked stack on the
     * display.
     * @param displayRect The bounds of the display the docked stack is on.
     * @param outBounds Output bounds that should be used for the stack.
     * @param stackId Id of stack we are calculating the bounds for.
     * @param dockedBounds Bounds of the docked stack.
     * @param adjustment
     */
    private static void getInitialDockedStackBounds(
            Rect displayRect, Rect outBounds, int stackId, Rect dockedBounds, int adjustment) {
        final boolean dockedStack = stackId == DOCKED_STACK_ID;
        final boolean splitHorizontally = displayRect.width() > displayRect.height();
        final boolean topOrLeftCreateMode =
                WindowManagerService.sDockedStackCreateMode == DOCKED_STACK_CREATE_MODE_TOP_OR_LEFT;

        outBounds.set(displayRect);
        if (dockedStack) {
            // The initial bounds of the docked stack when it is created half the screen space and
            // its bounds can be adjusted after that. The bounds of all other stacks are adjusted
            // to occupy whatever screen space the docked stack isn't occupying.
            if (topOrLeftCreateMode) {
                if (splitHorizontally) {
                    outBounds.right = displayRect.centerX() - adjustment;
                } else {
                    outBounds.bottom = displayRect.centerY() - adjustment;
                }
            } else {
                if (splitHorizontally) {
                    outBounds.left = displayRect.centerX() + adjustment;
                } else {
                    outBounds.top = displayRect.centerY() + adjustment;
                }
            }
            return;
        }

        // Other stacks occupy whatever space is left by the docked stack.
        if (!topOrLeftCreateMode) {
            if (splitHorizontally) {
                outBounds.right = dockedBounds.left - adjustment;
            } else {
                outBounds.bottom = dockedBounds.top - adjustment;
            }
        } else {
            if (splitHorizontally) {
                outBounds.left = dockedBounds.right + adjustment;
            } else {
                outBounds.top = dockedBounds.bottom + adjustment;
            }
        }
    }

    /** Resizes all non-docked stacks in the system to either fullscreen or the appropriate size
     * based on the presence of a docked stack.
     * @param fullscreen If true the stacks will be resized to fullscreen, else they will be
     *                   resized to the appropriate size based on the presence of a docked stack.
     * @param dockedBounds Bounds of the docked stack.
     */
    private void resizeNonDockedStacks(boolean fullscreen, Rect dockedBounds) {
        // Not using mTmpRect because we are posting the object in a message.
        final Rect bounds = new Rect();
        mDisplayContent.getLogicalDisplayRect(bounds);
        if (!fullscreen) {
            getInitialDockedStackBounds(bounds, bounds, FULLSCREEN_WORKSPACE_STACK_ID, dockedBounds,
                    mDisplayContent.mDividerControllerLocked.getWidth());
        }

        final int count = mService.mStackIdToStack.size();
        for (int i = 0; i < count; i++) {
            final TaskStack otherStack = mService.mStackIdToStack.valueAt(i);
            final int otherStackId = otherStack.mStackId;
            if (otherStackId != DOCKED_STACK_ID
                    && otherStackId >= FIRST_STATIC_STACK_ID
                    && otherStackId <= LAST_STATIC_STACK_ID) {
                mService.mH.sendMessage(
                        mService.mH.obtainMessage(RESIZE_STACK, otherStackId, -1, bounds));
            }
        }
    }

    void detachDisplay() {
        EventLog.writeEvent(EventLogTags.WM_STACK_REMOVED, mStackId);

        boolean doAnotherLayoutPass = false;
        for (int taskNdx = mTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            final AppTokenList appWindowTokens = mTasks.get(taskNdx).mAppTokens;
            for (int appNdx = appWindowTokens.size() - 1; appNdx >= 0; --appNdx) {
                final WindowList appWindows = appWindowTokens.get(appNdx).allAppWindows;
                for (int winNdx = appWindows.size() - 1; winNdx >= 0; --winNdx) {
                    // We are in the middle of changing the state of displays/stacks/tasks. We need
                    // to finish that, before we let layout interfere with it.
                    mService.removeWindowLocked(appWindows.get(winNdx));
                    doAnotherLayoutPass = true;
                }
            }
        }
        if (doAnotherLayoutPass) {
            mService.mWindowPlacerLocked.requestTraversal();
        }

        if (mStackId == DOCKED_STACK_ID) {
            // Docked stack was detached from the display, so we no longer need to restrict the
            // region of the screen other static stacks occupy. Go ahead and make them fullscreen.
            resizeNonDockedStacks(FULLSCREEN, null);
        }

        close();
    }

    void resetAnimationBackgroundAnimator() {
        mAnimationBackgroundAnimator = null;
        mAnimationBackgroundSurface.hide();
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
        mDisplayContent = null;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("mStackId="); pw.println(mStackId);
        pw.print(prefix); pw.print("mDeferDetach="); pw.println(mDeferDetach);
        for (int taskNdx = 0; taskNdx < mTasks.size(); ++taskNdx) {
            pw.print(prefix);
            mTasks.get(taskNdx).printTo(prefix + " ", pw);
        }
        if (mAnimationBackgroundSurface.isDimming()) {
            pw.print(prefix); pw.println("mWindowAnimationBackgroundSurface:");
            mAnimationBackgroundSurface.printTo(prefix + "  ", pw);
        }
        if (!mExitingAppTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting application tokens:");
            for (int i = mExitingAppTokens.size() - 1; i >= 0; i--) {
                WindowToken token = mExitingAppTokens.get(i);
                pw.print("  Exiting App #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
    }

    /** Fullscreen status of the stack without adjusting for other factors in the system like
     * visibility of docked stack.
     * Most callers should be using {@link #isFullscreen} as it take into consideration other
     * system factors. */
    boolean getRawFullscreen() {
        return mFullscreen;
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
        return mDisplayContent.getDisplayInfo();
    }

    @Override
    public String toString() {
        return "{stackId=" + mStackId + " tasks=" + mTasks + "}";
    }

    @Override
    public String toShortString() {
        return "Stack=" + mStackId;
    }

    /**
     * For docked workspace provides information which side of the screen was the dock anchored.
     */
    @DockSide
    int getDockSide() {
        if (mStackId != DOCKED_STACK_ID) {
            return DOCKED_INVALID;
        }
        if (mDisplayContent == null) {
            return DOCKED_INVALID;
        }
        mDisplayContent.getLogicalDisplayRect(mTmpRect);
        final int orientation = mService.mCurConfiguration.orientation;
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            // Portrait mode, docked either at the top or the bottom.
            if (mBounds.top - mTmpRect.top < mTmpRect.bottom - mBounds.bottom) {
                return DOCKED_TOP;
            } else {
                return DOCKED_BOTTOM;
            }
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Landscape mode, docked either on the left or on the right.
            if (mBounds.left - mTmpRect.left < mTmpRect.right - mBounds.right) {
                return DOCKED_LEFT;
            } else {
                return DOCKED_RIGHT;
            }
        } else {
            return DOCKED_INVALID;
        }
    }

    boolean isVisibleLocked() {
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            Task task = mTasks.get(i);
            for (int j = task.mAppTokens.size() - 1; j >= 0; j--) {
                if (!task.mAppTokens.get(j).hidden) {
                    return true;
                }
            }
        }
        return false;
    }
}
