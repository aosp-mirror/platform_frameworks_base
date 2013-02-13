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

import android.os.Debug;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

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
    private final static String TAG = "DisplayContent";

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
     * List controlling the ordering of windows in different applications which must
     * be kept in sync with ActivityManager.
     */
    final AppTokenList mAppTokens = new AppTokenList();

    /**
     * AppWindowTokens in the Z order they were in at the start of an animation. Between
     * animations this list is maintained in the exact order of mAppTokens. If tokens
     * are added to mAppTokens during an animation an attempt is made to insert them at the same
     * logical location in this list. Note that this list is always in sync with mWindows.
     */
    AppTokenList mAnimatingAppTokens = new AppTokenList();

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

    /**
     * Sorted most recent at top, oldest at [0].
     */
    ArrayList<TaskList> mTaskLists = new ArrayList<TaskList>();
    SparseArray<TaskList> mTaskIdToTaskList = new SparseArray<TaskList>();

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

    public void updateDisplayInfo() {
        mDisplay.getDisplayInfo(mDisplayInfo);
    }

    /**
     *  Find the location to insert a new AppWindowToken into the window-ordered app token list.
     * @param addPos The location the token was inserted into in mAppTokens.
     * @param wtoken The token to insert.
     */
    void addAppToken(final int addPos, final AppWindowToken wtoken) {
        mAppTokens.add(addPos, wtoken);

        if (addPos == 0 || addPos == mAnimatingAppTokens.size()) {
            // It was inserted into the beginning or end of mAppTokens. Honor that.
            mAnimatingAppTokens.add(addPos, wtoken);
        } else {
            // Find the item immediately above the mAppTokens insertion point and put the token
            // immediately below that one in mAnimatingAppTokens.
            final AppWindowToken aboveAnchor = mAppTokens.get(addPos + 1);
            mAnimatingAppTokens.add(mAnimatingAppTokens.indexOf(aboveAnchor), wtoken);
        }

        TaskList task = mTaskIdToTaskList.get(wtoken.groupId);
        if (task == null) {
            task = new TaskList(wtoken, this);
            mTaskIdToTaskList.put(wtoken.groupId, task);
            mTaskLists.add(task);
        } else {
            task.mAppTokens.add(wtoken);
        }
    }

    void removeAppToken(final AppWindowToken wtoken) {
        mAppTokens.remove(wtoken);
        mAnimatingAppTokens.remove(wtoken);
        final int taskId = wtoken.groupId;
        final TaskList task = mTaskIdToTaskList.get(taskId);
        if (task != null) {
            AppTokenList appTokens = task.mAppTokens;
            appTokens.remove(wtoken);
            if (appTokens.size() == 0) {
                mTaskLists.remove(task);
                mTaskIdToTaskList.delete(taskId);
            }
        }
    }

    void refillAnimatingAppTokens() {
        mAnimatingAppTokens.clear();
        mAnimatingAppTokens.addAll(mAppTokens);
    }

    void setAppTaskId(AppWindowToken wtoken, int newTaskId) {
        final int taskId = wtoken.groupId;
        TaskList task = mTaskIdToTaskList.get(taskId);
        if (task != null) {
            AppTokenList appTokens = task.mAppTokens;
            appTokens.remove(wtoken);
            if (appTokens.size() == 0) {
                mTaskIdToTaskList.delete(taskId);
            }
        }

        task = mTaskIdToTaskList.get(newTaskId);
        if (task == null) {
            task = new TaskList(wtoken, this);
            mTaskIdToTaskList.put(newTaskId, task);
        } else {
            task.mAppTokens.add(wtoken);
        }

        wtoken.groupId = newTaskId;
    }

    class TaskListsIterator implements Iterator<TaskList> {
        private int mCur;
        private boolean mReverse;

        TaskListsIterator() {
            this(false);
        }

        TaskListsIterator(boolean reverse) {
            mReverse = reverse;
            int numTaskLists = mTaskLists.size();
            mCur = reverse ? numTaskLists - 1 : 0;
        }

        @Override
        public boolean hasNext() {
            if (mReverse) {
                return mCur >= 0;
            }
            return mCur < mTaskLists.size();
        }

        @Override
        public TaskList next() {
            if (hasNext()) {
                TaskList taskList = mTaskLists.get(mCur);
                mCur += (mReverse ? -1 : 1);
                return taskList;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new IllegalArgumentException();
        }
    }

    class AppTokenIterator implements Iterator<AppWindowToken> {
        final TaskListsIterator mIterator;
        final boolean mReverse;
        int mCur;
        TaskList mTaskList;

        public AppTokenIterator() {
            this(false);
        }

        public AppTokenIterator(boolean reverse) {
            mReverse = reverse;
            mIterator = new TaskListsIterator(reverse);
            getNextTaskList();
        }

        private void getNextTaskList() {
            if (mIterator.hasNext()) {
                mTaskList = mIterator.next();
                mCur = mReverse ? mTaskList.mAppTokens.size() - 1 : 0;
            }
        }

        @Override
        public boolean hasNext() {
            if (mTaskList == null) {
                return false;
            }
            if (mReverse) {
                return mCur >= 0;
            }
            return mCur < mTaskList.mAppTokens.size();
        }

        @Override
        public AppWindowToken next() {
            if (hasNext()) {
                AppWindowToken wtoken = mTaskList.mAppTokens.get(mCur);
                mCur += mReverse ? -1 : 1;
                if (!hasNext()) {
                    getNextTaskList();
                }
                return wtoken;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new IllegalArgumentException();
        }
    }

    void verifyAppTokens() {
        AppTokenIterator iterator = new AppTokenIterator();
        for (int i = 0; i < mAppTokens.size(); ++i) {
            if (!iterator.hasNext()) {
                Slog.e(TAG, "compareAppTokens: More mAppTokens than TaskList tokens. Callers="
                        + Debug.getCallers(4));
                while (i < mAppTokens.size()) {
                    Slog.e(TAG, "compareAppTokens: mAppTokens[" + i + "]=" + mAppTokens.get(i));
                    i++;
                }
                return;
            }
            AppWindowToken appToken = mAppTokens.get(i);
            AppWindowToken taskListToken = iterator.next();
            if (appToken != taskListToken) {
                Slog.e(TAG, "compareAppTokens: Mismatch at " + i + " appToken=" + appToken
                        + " taskListToken=" + taskListToken + ". Callers=" + Debug.getCallers(4));
            }
        }
        if (iterator.hasNext()) {
            Slog.e(TAG, "compareAppTokens: More TaskList tokens than mAppTokens Callers="
                    + Debug.getCallers(4));
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
            if (mAppTokens.size() > 0) {
                pw.println();
                pw.println("  Application tokens in Z order:");
                for (int i=mAppTokens.size()-1; i>=0; i--) {
                    pw.print("  App #"); pw.print(i);
                            pw.print(' '); pw.print(mAppTokens.get(i)); pw.println(":");
                    mAppTokens.get(i).dump(pw, "    ");
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
            if (mTaskIdToTaskList.size() > 0) {
                pw.println();
                for (int i = 0; i < mTaskIdToTaskList.size(); ++i) {
                    pw.print("  TaskList #"); pw.print(i);
                      pw.print(" taskId="); pw.println(mTaskIdToTaskList.keyAt(i));
                    pw.print("    mAppTokens=");
                      pw.println(mTaskIdToTaskList.valueAt(i).mAppTokens);
                    pw.println();
                }
            }
        pw.print(subPrefix); pw.print("layoutNeeded="); pw.println(layoutNeeded);
        pw.println();
    }
}
