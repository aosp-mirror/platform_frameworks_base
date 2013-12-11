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

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;
import static com.android.server.wm.WindowManagerService.DEBUG_STACK;
import static com.android.server.wm.WindowManagerService.DEBUG_VISIBILITY;
import static com.android.server.wm.WindowManagerService.TAG;

import android.app.ActivityManager.StackBoxInfo;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Debug;
import android.util.EventLog;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import com.android.server.EventLogTags;

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
    private WindowList mWindows = new WindowList();

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
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final Display mDisplay;

    Rect mBaseDisplayRect = new Rect();

    // Accessed directly by all users.
    boolean layoutNeeded;
    int pendingLayoutChanges;
    final boolean isDefaultDisplay;

    /**
     * Window tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final ArrayList<WindowToken> mExitingTokens = new ArrayList<WindowToken>();

    /**
     * Application tokens that are in the process of exiting, but still
     * on screen for animations.
     */
    final AppTokenList mExitingAppTokens = new AppTokenList();

    /** Array containing the home StackBox and possibly one more which would contain apps. Array
     * is stored in display order with the current bottom stack at 0. */
    private ArrayList<StackBox> mStackBoxes = new ArrayList<StackBox>();

    /** True when the home StackBox is at the top of mStackBoxes, false otherwise. */
    private TaskStack mHomeStack = null;

    /** Detect user tapping outside of current focused stack bounds .*/
    StackTapPointerEventListener mTapDetector;

    /** Detect user tapping outside of current focused stack bounds .*/
    Region mTouchExcludeRegion = new Region();

    /** Save allocating when retrieving tasks */
    private ArrayList<Task> mTaskHistory = new ArrayList<Task>();

    /** Save allocating when calculating rects */
    Rect mTmpRect = new Rect();

    final WindowManagerService mService;

    /**
     * @param display May not be null.
     * @param service TODO(cmautner):
     */
    DisplayContent(Display display, WindowManagerService service) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        display.getDisplayInfo(mDisplayInfo);
        isDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
        mService = service;

        StackBox newBox = new StackBox(service, this, null);
        mStackBoxes.add(newBox);
        TaskStack newStack = new TaskStack(service, HOME_STACK_ID, this);
        newStack.mStackBox = newBox;
        newBox.mStack = newStack;
        mHomeStack = newStack;
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

    boolean homeOnTop() {
        return mStackBoxes.get(0).mStack != mHomeStack;
    }

    public boolean isPrivate() {
        return (mDisplay.getFlags() & Display.FLAG_PRIVATE) != 0;
    }

    /**
     * Retrieve the tasks on this display in stack order from the bottommost TaskStack up.
     * @return All the Tasks, in order, on this display.
     */
    ArrayList<Task> getTasks() {
        return mTaskHistory;
    }

    void addTask(Task task, boolean toTop) {
        mTaskHistory.remove(task);

        final int userId = task.mUserId;
        int taskNdx;
        final int numTasks = mTaskHistory.size();
        if (toTop) {
            for (taskNdx = numTasks - 1; taskNdx >= 0; --taskNdx) {
                if (mTaskHistory.get(taskNdx).mUserId == userId) {
                    break;
                }
            }
            ++taskNdx;
        } else {
            for (taskNdx = 0; taskNdx < numTasks; ++taskNdx) {
                if (mTaskHistory.get(taskNdx).mUserId == userId) {
                    break;
                }
            }
        }

        mTaskHistory.add(taskNdx, task);
        EventLog.writeEvent(EventLogTags.WM_TASK_MOVED, task.taskId, toTop ? 1 : 0, taskNdx);
    }

    void removeTask(Task task) {
        mTaskHistory.remove(task);
    }

    TaskStack getHomeStack() {
        return mHomeStack;
    }

    void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
    }

    void getLogicalDisplayRect(Rect out) {
        updateDisplayInfo();
        // Uses same calculation as in LogicalDisplay#configureDisplayInTransactionLocked.
        int width = mDisplayInfo.logicalWidth;
        int left = (mBaseDisplayWidth - width) / 2;
        int height = mDisplayInfo.logicalHeight;
        int top = (mBaseDisplayHeight - height) / 2;
        out.set(left, top, left + width, top + height);
    }

    /** @return The number of tokens in all of the Tasks on this display. */
    int numTokens() {
        int count = 0;
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTaskHistory.get(taskNdx).mAppTokens.size();
        }
        return count;
    }

    /** Refer to {@link WindowManagerService#createStack(int, int, int, float)} */
    TaskStack createStack(int stackId, int relativeStackBoxId, int position, float weight) {
        TaskStack newStack = null;
        if (DEBUG_STACK) Slog.d(TAG, "createStack: stackId=" + stackId + " relativeStackBoxId="
                + relativeStackBoxId + " position=" + position + " weight=" + weight);
        if (stackId == HOME_STACK_ID) {
            if (mStackBoxes.size() != 1) {
                throw new IllegalArgumentException("createStack: HOME_STACK_ID (0) not first.");
            }
            newStack = mHomeStack;
        } else {
            int stackBoxNdx;
            for (stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
                final StackBox box = mStackBoxes.get(stackBoxNdx);
                if (position == StackBox.TASK_STACK_GOES_OVER
                        || position == StackBox.TASK_STACK_GOES_UNDER) {
                    // Position indicates a new box is added at top level only.
                    if (box.contains(relativeStackBoxId)) {
                        StackBox newBox = new StackBox(mService, this, null);
                        newStack = new TaskStack(mService, stackId, this);
                        newStack.mStackBox = newBox;
                        newBox.mStack = newStack;
                        final int offset = position == StackBox.TASK_STACK_GOES_OVER ? 1 : 0;
                        if (DEBUG_STACK) Slog.d(TAG, "createStack: inserting stack at " +
                                (stackBoxNdx + offset));
                        mStackBoxes.add(stackBoxNdx + offset, newBox);
                        break;
                    }
                } else {
                    // Remaining position values indicate a box must be split.
                    newStack = box.split(stackId, relativeStackBoxId, position, weight);
                    if (newStack != null) {
                        break;
                    }
                }
            }
            if (stackBoxNdx < 0) {
                throw new IllegalArgumentException("createStack: stackBoxId " + relativeStackBoxId
                        + " not found.");
            }
        }
        if (newStack != null) {
            layoutNeeded = true;
        }
        EventLog.writeEvent(EventLogTags.WM_STACK_CREATED, stackId, relativeStackBoxId, position,
                (int)(weight * 100 + 0.5));
        return newStack;
    }

    /** Refer to {@link WindowManagerService#resizeStackBox(int, float)} */
    boolean resizeStack(int stackBoxId, float weight) {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            final StackBox box = mStackBoxes.get(stackBoxNdx);
            if (box.resize(stackBoxId, weight)) {
                layoutNeeded = true;
                return true;
            }
        }
        return false;
    }

    void addStackBox(StackBox box, boolean toTop) {
        if (mStackBoxes.size() >= 2) {
            throw new RuntimeException("addStackBox: Too many toplevel StackBoxes!");
        }
        mStackBoxes.add(toTop ? mStackBoxes.size() : 0, box);
    }

    void removeStackBox(StackBox box) {
        if (DEBUG_STACK) Slog.d(TAG, "removeStackBox: box=" + box);
        final TaskStack stack = box.mStack;
        if (stack != null && stack.mStackId == HOME_STACK_ID) {
            // Never delete the home stack, even if it is empty.
            if (DEBUG_STACK) Slog.d(TAG, "removeStackBox: Not deleting home stack.");
            return;
        }
        mStackBoxes.remove(box);
    }

    StackBoxInfo getStackBoxInfo(StackBox box) {
        StackBoxInfo info = new StackBoxInfo();
        info.stackBoxId = box.mStackBoxId;
        info.weight = box.mWeight;
        info.vertical = box.mVertical;
        info.bounds = new Rect(box.mBounds);
        if (box.mStack != null) {
            info.stackId = box.mStack.mStackId;
            // ActivityManagerService will fill in the StackInfo.
        } else {
            info.stackId = -1;
            info.children = new StackBoxInfo[2];
            info.children[0] = getStackBoxInfo(box.mFirst);
            info.children[1] = getStackBoxInfo(box.mSecond);
        }
        return info;
    }

    ArrayList<StackBoxInfo> getStackBoxInfos() {
        ArrayList<StackBoxInfo> list = new ArrayList<StackBoxInfo>();
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            list.add(getStackBoxInfo(mStackBoxes.get(stackBoxNdx)));
        }
        return list;
    }

    /**
     * Move the home StackBox to the top or bottom of mStackBoxes. That is the only place
     * it is allowed to be. This is a nop if the home StackBox is already in the correct position.
     * @param toTop Move home to the top of mStackBoxes if true, to the bottom if false.
     * @return true if a change was made, false otherwise.
     */
    boolean moveHomeStackBox(boolean toTop) {
        if (DEBUG_STACK) Slog.d(TAG, "moveHomeStackBox: toTop=" + toTop + " Callers=" +
                Debug.getCallers(4));
        EventLog.writeEvent(EventLogTags.WM_HOME_STACK_MOVED, toTop ? 1 : 0);
        switch (mStackBoxes.size()) {
            case 0: throw new RuntimeException("moveHomeStackBox: No home StackBox!");
            case 1: return false; // Only the home StackBox exists.
            case 2:
                if (homeOnTop() ^ toTop) {
                    mStackBoxes.add(mStackBoxes.remove(0));
                    return true;
                }
                return false;
            default: throw new RuntimeException("moveHomeStackBox: Too many toplevel StackBoxes!");
        }
    }

    /**
     * Propagate the new bounds to all child stack boxes, applying weights as we move down.
     * @param contentRect The bounds to apply at the top level.
     */
    boolean setStackBoxSize(Rect contentRect) {
        boolean change = false;
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            change |= mStackBoxes.get(stackBoxNdx).setStackBoxSizes(contentRect, true);
        }
        return change;
    }

    Rect getStackBounds(int stackId) {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            Rect bounds = mStackBoxes.get(stackBoxNdx).getStackBounds(stackId);
            if (bounds != null) {
                return bounds;
            }
        }
        return null;
    }

    int stackIdFromPoint(int x, int y) {
        StackBox topBox = mStackBoxes.get(mStackBoxes.size() - 1);
        return topBox.stackIdFromPoint(x, y);
    }

    void setTouchExcludeRegion(TaskStack focusedStack) {
        mTouchExcludeRegion.set(mBaseDisplayRect);
        WindowList windows = getWindowList();
        for (int i = windows.size() - 1; i >= 0; --i) {
            final WindowState win = windows.get(i);
            final TaskStack stack = win.getStack();
            if (win.isVisibleLw() && stack != null && stack != focusedStack) {
                mTmpRect.set(win.mVisibleFrame);
                mTmpRect.intersect(win.mVisibleInsets);
                mTouchExcludeRegion.op(mTmpRect, Region.Op.DIFFERENCE);
            }
        }
    }

    void switchUserStacks(int oldUserId, int newUserId) {
        final WindowList windows = getWindowList();
        for (int i = 0; i < windows.size(); i++) {
            final WindowState win = windows.get(i);
            if (win.isHiddenFromUserLocked()) {
                if (DEBUG_VISIBILITY) Slog.w(TAG, "user changing " + newUserId + " hiding "
                        + win + ", attrs=" + win.mAttrs.type + ", belonging to "
                        + win.mOwnerUid);
                win.hideLw(false);
            }
        }

        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            mStackBoxes.get(stackBoxNdx).switchUserStacks(newUserId);
        }
    }

    void resetAnimationBackgroundAnimator() {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            mStackBoxes.get(stackBoxNdx).resetAnimationBackgroundAnimator();
        }
    }

    boolean animateDimLayers() {
        boolean result = false;
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            result |= mStackBoxes.get(stackBoxNdx).animateDimLayers();
        }
        return result;
    }

    void resetDimming() {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            mStackBoxes.get(stackBoxNdx).resetDimming();
        }
    }

    boolean isDimming() {
        boolean result = false;
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            result |= mStackBoxes.get(stackBoxNdx).isDimming();
        }
        return result;
    }

    void stopDimmingIfNeeded() {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            mStackBoxes.get(stackBoxNdx).stopDimmingIfNeeded();
        }
    }

    void close() {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            mStackBoxes.get(stackBoxNdx).close();
        }
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
            pw.print(subPrefix); pw.print("layoutNeeded="); pw.println(layoutNeeded);
        for (int boxNdx = 0; boxNdx < mStackBoxes.size(); ++boxNdx) {
            pw.print(prefix); pw.print("StackBox #"); pw.println(boxNdx);
            mStackBoxes.get(boxNdx).dump(prefix + "  ", pw);
        }
        int ndx = numTokens();
        if (ndx > 0) {
            pw.println();
            pw.println("  Application tokens in Z order:");
            getTasks();
            for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
                AppTokenList tokens = mTaskHistory.get(taskNdx).mAppTokens;
                for (int tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) {
                    final AppWindowToken wtoken = tokens.get(tokenNdx);
                    pw.print("  App #"); pw.print(ndx--);
                            pw.print(' '); pw.print(wtoken); pw.println(":");
                    wtoken.dump(pw, "    ");
                }
            }
        }
        if (mExitingTokens.size() > 0) {
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
        if (mExitingAppTokens.size() > 0) {
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
        pw.println();
    }
}
