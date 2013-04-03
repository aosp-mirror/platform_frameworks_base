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

import android.graphics.Rect;
import android.view.Display;
import android.view.DisplayInfo;

import static com.android.server.am.ActivityStackSupervisor.HOME_STACK_ID;

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
//    private final static String TAG = "DisplayContent";

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

    private ArrayList<StackBox> mStackBoxes = new ArrayList<StackBox>();

    /**
     * Sorted most recent at top, oldest at [0].
     */
    ArrayList<Task> mTmpTasks = new ArrayList<Task>();

    /**
     * @param display May not be null.
     */
    DisplayContent(Display display) {
        mDisplay = display;
        mDisplayId = display.getDisplayId();
        display.getDisplayInfo(mDisplayInfo);
        isDefaultDisplay = mDisplayId == Display.DEFAULT_DISPLAY;
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
     * Retrieve the tasks on this display in stack order from the topmost TaskStack down.
     * Note that the order of TaskStacks in the same StackBox is defined within StackBox.
     * @return All the Tasks, in order, on this display.
     */
    ArrayList<Task> getTasks() {
        mTmpTasks.clear();
        int numBoxes = mStackBoxes.size();
        for (int boxNdx = 0; boxNdx < numBoxes; ++boxNdx) {
            mTmpTasks.addAll(mStackBoxes.get(boxNdx).getTasks());
        }
        return mTmpTasks;
    }

    public void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
    }

    /** @return The number of tokens in all of the Tasks on this display. */
    int numTokens() {
        getTasks();
        int count = 0;
        for (int taskNdx = mTmpTasks.size() - 1; taskNdx >= 0; --taskNdx) {
            count += mTmpTasks.get(taskNdx).mAppTokens.size();
        }
        return count;
    }

    /** Refer to {@link WindowManagerService#createStack(int, int, int, float)} */
    TaskStack createStack(int stackId, int relativeStackId, int position, float weight) {
        TaskStack newStack = null;
        if (mStackBoxes.isEmpty()) {
            StackBox newBox = new StackBox(this, new Rect(0, 0, mDisplayInfo.logicalWidth,
                    mDisplayInfo.logicalHeight));
            mStackBoxes.add(newBox);
            newStack = new TaskStack(stackId, newBox);
            newBox.mStack = newStack;
        } else {
            int stackBoxNdx;
            for (stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
                final StackBox box = mStackBoxes.get(stackBoxNdx);
                if (position == StackBox.TASK_STACK_GOES_OVER
                        || position == StackBox.TASK_STACK_GOES_UNDER) {
                    // Position indicates a new box is added at top level only.
                    if (box.contains(relativeStackId)) {
                        final int offset = position == StackBox.TASK_STACK_GOES_OVER ? 1 : 0;
                        StackBox newBox = new StackBox(this, box.mBounds);
                        newStack = new TaskStack(stackId, newBox);
                        newBox.mStack = newStack;
                        mStackBoxes.add(stackBoxNdx + offset, newBox);
                        break;
                    }
                } else {
                    // Remaining position values indicate a box must be split.
                    newStack = box.split(stackId, relativeStackId, position, weight);
                    if (newStack != null) {
                        break;
                    }
                }
            }
            if (stackBoxNdx < 0) {
                throw new IllegalArgumentException("createStack: stackId " + relativeStackId
                        + " not found.");
            }
        }
        return newStack;
    }

    /** Refer to {@link WindowManagerService#resizeStack(int, float)} */
    boolean resizeStack(int stackId, float weight) {
        int stackBoxNdx;
        for (stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            final StackBox box = mStackBoxes.get(stackBoxNdx);
            if (box.resize(stackId, weight)) {
                return true;
            }
        }
        return false;
    }

    void removeStackBox(StackBox box) {
        final TaskStack stack = box.mStack;
        if (stack != null && stack.mStackId == HOME_STACK_ID) {
            // Never delete the home stack, even if it is empty.
            return;
        }
        mStackBoxes.remove(box);
    }

    /**
     * Reorder a StackBox within mStackBox. The StackBox to reorder is the one containing the
     * specified TaskStack.
     * @param stackId The TaskStack to reorder.
     * @param toTop Move to the top of all StackBoxes if true, to the bottom if false. Only the
     * topmost layer of StackBoxes, those in mStackBoxes can be reordered.
     */
    void moveStackBox(int stackId, boolean toTop) {
        for (int stackBoxNdx = mStackBoxes.size() - 1; stackBoxNdx >= 0; --stackBoxNdx) {
            final StackBox box = mStackBoxes.get(stackBoxNdx);
            if (box.contains(stackId)) {
                mStackBoxes.remove(box);
                mStackBoxes.add(toTop ? mStackBoxes.size() : 0, box);
                return;
            }
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
                for (int taskNdx = mTmpTasks.size() - 1; taskNdx >= 0; --taskNdx) {
                    AppTokenList tokens = mTmpTasks.get(taskNdx).mAppTokens;
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
