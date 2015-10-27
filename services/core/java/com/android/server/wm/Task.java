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
import static android.app.ActivityManager.FREEFORM_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.HOME_STACK_ID;
import static android.app.ActivityManager.PINNED_STACK_ID;
import static android.app.ActivityManager.RESIZE_MODE_SYSTEM_SCREEN_ROTATION;
import static com.android.server.wm.WindowManagerService.TAG;
import static com.android.server.wm.WindowManagerService.DEBUG_RESIZE;
import static com.android.server.wm.WindowManagerService.DEBUG_STACK;
import static com.android.server.wm.WindowManagerService.H.RESIZE_TASK;


import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;
import android.view.DisplayInfo;
import android.view.Surface;

import com.android.server.EventLogTags;

import java.io.PrintWriter;
import java.util.ArrayList;

class Task implements DimLayer.DimLayerUser {
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
        DisplayContent content = getDisplayContent();
        if (content != null) {
            content.mDimBehindController.removeDimLayerUser(this);
        }
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
                if ((mStack.mStackId != FREEFORM_WORKSPACE_STACK_ID
                        && mStack.mStackId != PINNED_STACK_ID) || bounds.isEmpty()) {
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
        if (displayContent != null) {
            displayContent.mDimBehindController.updateDimLayer(this);
        }
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
                || mStack.allowTaskResize()
                || displayContent == null
                || displayContent.getDockedStackLocked() != null) {
            return true;
        }
        return false;
    }

    /** Bounds of the task with other system factors taken into consideration. */
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
        if (setBounds(mTmpRect2, mOverrideConfig) != BOUNDS_CHANGE_NONE) {
            // Post message to inform activity manager of the bounds change simulating
            // a one-way call. We do this to prevent a deadlock between window manager
            // lock and activity manager lock been held. Only tasks within the freeform stack
            // are resizeable independently of their stack resizing.
            if (mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID) {
                mService.mH.sendMessage(mService.mH.obtainMessage(
                        RESIZE_TASK, mTaskId, RESIZE_MODE_SYSTEM_SCREEN_ROTATION, mBounds));
            }
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

    boolean inHomeStack() {
        return mStack != null && mStack.mStackId == HOME_STACK_ID;
    }

    boolean inFreeformWorkspace() {
        return mStack != null && mStack.mStackId == FREEFORM_WORKSPACE_STACK_ID;
    }

    boolean inDockedWorkspace() {
        return mStack != null && mStack.mStackId == DOCKED_STACK_ID;
    }

    WindowState getTopAppMainWindow() {
        final int tokensCount = mAppTokens.size();
        return tokensCount > 0 ? mAppTokens.get(tokensCount - 1).findMainWindow() : null;
    }

    AppWindowToken getTopAppWindowToken() {
        final int tokensCount = mAppTokens.size();
        return tokensCount > 0 ? mAppTokens.get(tokensCount - 1) : null;
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

    @Override
    public String toShortString() {
        return "Task=" + mTaskId;
    }

    public void printTo(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("taskId="); pw.print(mTaskId);
                pw.print(prefix); pw.print("appTokens="); pw.print(mAppTokens);
                pw.print(prefix); pw.print("mdr="); pw.println(mDeferRemoval);
    }
}
