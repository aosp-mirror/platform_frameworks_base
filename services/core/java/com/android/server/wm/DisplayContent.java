/*
 * Copyright (C) 2012 The Android Open Source Project
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

import static android.app.ActivityManager.HOME_STACK_ID;

import static com.android.server.wm.WindowManagerService.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerService.TAG;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.DisplayMetrics;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;

import java.io.PrintWriter;
import java.util.ArrayList;

class DisplayContentList extends ArrayList<DisplayContent> {
}

/**
 * Utility class for keeping track of the WindowStates and other pertinent contents of a
 * particular Display.
 *
 * IMPORTANT: No method from this class should ever be used without holding
 * WindowManagerService.mWindowMap.
 */
class DisplayContent {

    /** Unique identifier of this stack. */
    private final int mDisplayId;

    /** Z-ordered (bottom-most first) list of all Window objects. Assigned to an element
     * from mDisplayWindows; */
    private final WindowList mWindows = new WindowList();

    // This protects the following display size properties, so that
    // getDisplaySize() doesn't need to acquire the global lock.  This is
    // needed because the window manager sometimes needs to use ActivityThread
    // while it has its global state locked (for example to load animation
    // resources), but the ActivityThread also needs get the current display
    // size sometimes when it has its package lock held.
    //
    // These will only be modified with both mWindowMap and mDisplaySizeLock
    // held (in that order) so the window manager doesn't need to acquire this
    // lock when needing these values in its normal operation.
    final Object mDisplaySizeLock = new Object();
    int mInitialDisplayWidth = 0;
    int mInitialDisplayHeight = 0;
    int mInitialDisplayDensity = 0;
    int mBaseDisplayWidth = 0;
    int mBaseDisplayHeight = 0;
    int mBaseDisplayDensity = 0;
    boolean mDisplayScalingDisabled;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;

    Rect mBaseDisplayRect = new Rect();
    Rect mContentRect = new Rect();

    // Accessed directly by all users.
    boolean layoutNeeded;
    int pendingLayoutChanges;
    final boolean isDefaultDisplay;

    /** Window tokens that are in the process of exiting, but still on screen for animations. */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<WindowToken>();

    /** Array containing all TaskStacks on this display.  Array
     * is stored in display order with the current bottom stack at 0. */
    private final ArrayList<TaskStack> mStacks = new ArrayList<TaskStack>();

    /** A special TaskStack with id==HOME_STACK_ID that moves to the bottom whenever any TaskStack
     * (except a future lockscreen TaskStack) moves to the top. */
    private TaskStack mHomeStack = null;

    /** Detect user tapping outside of current focused task bounds .*/
    TaskTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    Region mTouchExcludeRegion = new Region();

    /** Save allocating when calculating rects */
    Rect mTmpRect = new Rect();

    /** For gathering Task objects in order. */
    final ArrayList<Task> mTmpTaskHistory = new ArrayList<Task>();

    final WindowManagerService mService;

    /** Remove this display when animation on it has completed. */
    boolean mDeferredRemoval;

    /**
     * @param display May not be null.
     * @param service You know.
     */
    DisplayContent(Display display, WindowManagerService service) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        display.getDisplayInfo(mDisplayInfo);
        isDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
        mService = service;
    }

    int getDisplayId() {
        return mDisplayId;
    }

    WindowList getWindowList() {
        return mWindows;
    }

    Display getDisplay() {
        return mDisplay;
    }

    DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    /**
     * Returns true if the specified UID has access to this display.
     */
    public boolean hasAccess(int uid) {
        return mDisplay.hasAccess(uid);
    }

    public boolean isPrivate() {
        return (mDisplay.getFlags() & Display.FLAG_PRIVATE) != 0;
    }

    ArrayList<TaskStack> getStacks() {
        return mStacks;
    }

    /**
     * Retrieve the tasks on this display in stack order from the bottommost TaskStack up.
     * @return All the Tasks, in order, on this display.
     */
    ArrayList<Task> getTasks() {
        mTmpTaskHistory.clear();
        final int numStacks = mStacks.size();
        for (int stackNdx = 0; stackNdx < numStacks; ++stackNdx) {
            mTmpTaskHistory.addAll(mStacks.get(stackNdx).getTasks());
        }
        return mTmpTaskHistory;
    }

    TaskStack getHomeStack() {
        if (mHomeStack == null && mDisplayId == Display.DEFAULT_DISPLAY) {
            Slog.e(TAG, "getHomeStack: Returning null from this=" + this);
        }
        return mHomeStack;
    }

    void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
        for (int i = mStacks.size() - 1; i >= 0; --i) {
            mStacks.get(i).updateDisplayInfo(null);
        }
    }

    void getLogicalDisplayRect(Rect out) {
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        final int orientation = mDisplayInfo.rotation;
        boolean rotated = (orientation == Surface.ROTATION_90
                || orientation == Surface.ROTATION_270);
        final int physWidth = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
        final int physHeight = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
        int width = mDisplayInfo.logicalWidth;
        int left = (physWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (physHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    /** Refer to {@link WindowManagerService#attachStack(int, int, boolean)} */
    void attachStack(TaskStack stack, boolean onTop) {
        if (stack.mStackId == HOME_STACK_ID) {
            if (mHomeStack != null) {
                throw new IllegalArgumentException("attachStack: HOME_STACK_ID (0) not first.");
            }
            mHomeStack = stack;
        }
        if (onTop) {
            mStacks.add(stack);
        } else {
            mStacks.add(0, stack);
        }
        layoutNeeded = true;
    }

    void moveStack(TaskStack stack, boolean toTop) {
        if (!mStacks.remove(stack)) {
            Slog.wtf(TAG, "moving stack that was not added: " + stack, new Throwable());
        }
        mStacks.add(toTop ? mStacks.size() : 0, stack);
    }

    void detachStack(TaskStack stack) {
        mStacks.remove(stack);
    }

    /**
     * Propagate the new bounds to all child stacks.
     * @param contentRect The bounds to apply at the top level.
     */
    void resize(Rect contentRect) {
        mContentRect.set(contentRect);
    }

    int taskIdFromPoint(int x, int y) {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                task.getBounds(mTmpRect);
                if (mTmpRect.contains(x, y)) {
                    return task.mTaskId;
                }
            }
        }
        return -1;
    }

    /**
     * Find the id of the task whose outside touch area (for resizing) (x, y)
     * falls within. Returns -1 if the touch doesn't fall into a resizing area.
     */
    int taskIdForControlPoint(int x, int y) {
        final int delta = calculatePixelFromDp(WindowState.RESIZE_HANDLE_WIDTH_IN_DP);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            TaskStack stack = mStacks.get(stackNdx);
            if (!stack.allowTaskResize()) {
                break;
            }
            final ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                if (task.isFullscreen()) {
                    return -1;
                }
                task.getBounds(mTmpRect);
                mTmpRect.inset(-delta, -delta);
                if (mTmpRect.contains(x, y)) {
                    mTmpRect.inset(delta, delta);
                    if (!mTmpRect.contains(x, y)) {
                        return task.mTaskId;
                    }
                    // User touched inside the task. No need to look further,
                    // focus transfer will be handled in ACTION_UP.
                    return -1;
                }
            }
        }
        return -1;
    }

    private int calculatePixelFromDp(int dp) {
        final Configuration serviceConfig = mService.mCurConfiguration;
        // TODO(multidisplay): Update Dp to that of display stack is on.
        final float density = serviceConfig.densityDpi * DisplayMetrics.DENSITY_DEFAULT_SCALE;
        return (int)(dp * density);
    }

    void setTouchExcludeRegion(Task focusedTask) {
        mTouchExcludeRegion.set(mBaseDisplayRect);
        WindowList windows = getWindowList();
        final int delta = calculatePixelFromDp(WindowState.RESIZE_HANDLE_WIDTH_IN_DP);
        for (int i = windows.size() - 1; i >= 0; --i) {
            final WindowState win = windows.get(i);
            final Task task = win.mAppToken != null ? win.getTask() : null;
            if (win.isVisibleLw() && task != null) {
                /**
                 * Exclusion region is the region that TapDetector doesn't care about.
                 * Here we want to remove all non-focused tasks from the exclusion region.
                 * We also remove the outside touch area for resizing for all freeform
                 * tasks (including the focused).
                 *
                 * (For freeform focused task, the below logic will first remove the enlarged
                 * area, then add back the inner area.)
                 */
                final boolean isFreeformed = win.inFreeformWorkspace();
                if (task != focusedTask || isFreeformed) {
                    mTmpRect.set(win.mVisibleFrame);
                    mTmpRect.intersect(win.mVisibleInsets);
                    /**
                     * If the task is freeformed, enlarge the area to account for outside
                     * touch area for resize.
                     */
                    if (isFreeformed) {
                        mTmpRect.inset(-delta, -delta);
                    }
                    mTouchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
                }
                /**
                 * If we removed the focused task above, add it back and only leave its
                 * outside touch area in the exclusion. TapDectector is not interested in
                 * any touch inside the focused task itself.
                 */
                if (task == focusedTask && isFreeformed) {
                    mTmpRect.inset(delta, delta);
                    mTouchExcludeRegion.op(mTmpRect, Region.Op.UNION);
                }
            }
        }
        if (mTapDetector != null) {
            mTapDetector.setTouchExcludeRegion(mTouchExcludeRegion);
        }
    }

    void switchUserStacks() {
        final WindowList windows = getWindowList();
        for (int i = 0; i < windows.size(); i++) {
            final WindowState win = windows.get(i);
            if (win.isHiddenFromUserLocked()) {
                if (DEBUG_VISIBILITY) Slog.w(TAG, "user changing, hiding " + win
                        + ", attrs=" + win.mAttrs.type + ", belonging to " + win.mOwnerUid);
                win.hideLw(false);
            }
        }

        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).switchUser();
        }
    }

    void resetAnimationBackgroundAnimator() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).resetAnimationBackgroundAnimator();
        }
    }

    boolean animateDimLayers() {
        boolean result = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                result |= task.animateDimLayers();
                if (task.isFullscreen()) {
                    // No point in continuing as this task covers the entire screen.
                    // Also, fullscreen tasks all share the same dim layer, so we don't want
                    // processing of fullscreen task below this one affecting the dim layer state.
                    return result;
                }
            }
        }
        return result;
    }

    void resetDimming() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                tasks.get(taskNdx).clearContinueDimming();
            }
        }
    }

    boolean isDimming() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                if (tasks.get(taskNdx).isDimming()) {
                    return true;
                }
            }
        }
        return false;
    }

    void stopDimmingIfNeeded() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final ArrayList<Task> tasks = mStacks.get(stackNdx).getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                tasks.get(taskNdx).stopDimmingIfNeeded();
            }
        }
    }

    void close() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            mStacks.get(stackNdx).close();
        }
    }

    boolean isAnimating() {
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                return true;
            }
        }
        return false;
    }

    void checkForDeferredActions() {
        boolean animating = false;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            if (stack.isAnimating()) {
                animating = true;
            } else {
                if (stack.mDeferDetach) {
                    mService.detachStackLocked(this, stack);
                }
                final ArrayList<Task> tasks = stack.getTasks();
                for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                    final Task task = tasks.get(taskNdx);
                    AppTokenList tokens = task.mAppTokens;
                    for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                        AppWindowToken wtoken = tokens.get(tokenNdx);
                        if (wtoken.mIsExiting) {
                            wtoken.removeAppFromTaskLocked();
                        }
                    }
                }
            }
        }
        if (!animating && mDeferredRemoval) {
            mService.onDisplayRemoved(mDisplayId);
        }
    }

    static int deltaRotation(int oldRotation, int newRotation) {
        int delta = newRotation - oldRotation;
        if (delta < 0) delta += 4;
        return delta;
    }

    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("Display: mDisplayId="); pw.println(mDisplayId);
        final String subPrefix = "  " + prefix;
        pw.print(subPrefix); pw.print("init="); pw.print(mInitialDisplayWidth); pw.print("x");
            pw.print(mInitialDisplayHeight); pw.print(" "); pw.print(mInitialDisplayDensity);
            pw.print("dpi");
            if (mInitialDisplayWidth != mBaseDisplayWidth
                    || mInitialDisplayHeight != mBaseDisplayHeight
                    || mInitialDisplayDensity != mBaseDisplayDensity) {
                pw.print(" base=");
                pw.print(mBaseDisplayWidth); pw.print("x"); pw.print(mBaseDisplayHeight);
                pw.print(" "); pw.print(mBaseDisplayDensity); pw.print("dpi");
            }
            if (mDisplayScalingDisabled) {
                pw.println(" noscale");
            }
            pw.print(" cur=");
            pw.print(mDisplayInfo.logicalWidth);
            pw.print("x"); pw.print(mDisplayInfo.logicalHeight);
            pw.print(" app=");
            pw.print(mDisplayInfo.appWidth);
            pw.print("x"); pw.print(mDisplayInfo.appHeight);
            pw.print(" rng="); pw.print(mDisplayInfo.smallestNominalAppWidth);
            pw.print("x"); pw.print(mDisplayInfo.smallestNominalAppHeight);
            pw.print("-"); pw.print(mDisplayInfo.largestNominalAppWidth);
            pw.print("x"); pw.println(mDisplayInfo.largestNominalAppHeight);
            pw.print(subPrefix); pw.print("deferred="); pw.print(mDeferredRemoval);
                pw.print(" layoutNeeded="); pw.println(layoutNeeded);
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            pw.print(prefix); pw.print("mStacks[" + stackNdx + "]"); pw.println(stack.mStackId);
            stack.dump(prefix + "  ", pw);
        }
        pw.println();
        pw.println("  Application tokens in top down Z order:");
        int ndx = 0;
        for (int stackNdx = mStacks.size() - 1; stackNdx >= 0; --stackNdx) {
            final TaskStack stack = mStacks.get(stackNdx);
            pw.print("  mStackId="); pw.println(stack.mStackId);
            ArrayList<Task> tasks = stack.getTasks();
            for (int taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) {
                final Task task = tasks.get(taskNdx);
                pw.print("    mTaskId="); pw.println(task.mTaskId);
                AppTokenList tokens = task.mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx, ++ndx) {
                    final AppWindowToken wtoken = tokens.get(tokenNdx);
                    pw.print("    Activity #"); pw.print(tokenNdx);
                            pw.print(' '); pw.print(wtoken); pw.println(":");
                    wtoken.dump(pw, "      ");
                }
            }
        }
        if (ndx == 0) {
            pw.println("    None");
        }
        pw.println();
        if (!mExitingTokens.isEmpty()) {
            pw.println();
            pw.println("  Exiting tokens:");
            for (int i=mExitingTokens.size()-1; i>=0; i--) {
                WindowToken token = mExitingTokens.get(i);
                pw.print("  Exiting #"); pw.print(i);
                pw.print(' '); pw.print(token);
                pw.println(':');
                token.dump(pw, "    ");
            }
        }
        pw.println();
    }

    @Override
    public String toString() {
        return "Display " + mDisplayId + " info=" + mDisplayInfo + " stacks=" + mStacks;
    }
}
