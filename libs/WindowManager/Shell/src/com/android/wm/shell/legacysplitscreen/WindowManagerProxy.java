/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.wm.shell.legacysplitscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;

/**
 * Proxy to simplify calls into window manager/activity manager
 */
class WindowManagerProxy {

    private static final String TAG = "WindowManagerProxy";
    private static final int[] HOME_AND_RECENTS = {ACTIVITY_TYPE_HOME, ACTIVITY_TYPE_RECENTS};
    private static final int[] CONTROLLED_ACTIVITY_TYPES = {
            ACTIVITY_TYPE_STANDARD,
            ACTIVITY_TYPE_HOME,
            ACTIVITY_TYPE_RECENTS,
            ACTIVITY_TYPE_UNDEFINED
    };
    private static final int[] CONTROLLED_WINDOWING_MODES = {
            WINDOWING_MODE_FULLSCREEN,
            WINDOWING_MODE_SPLIT_SCREEN_SECONDARY,
            WINDOWING_MODE_UNDEFINED
    };

    @GuardedBy("mDockedRect")
    private final Rect mDockedRect = new Rect();

    private final Rect mTmpRect1 = new Rect();

    @GuardedBy("mDockedRect")
    private final Rect mTouchableRegion = new Rect();

    private final SyncTransactionQueue mSyncTransactionQueue;
    private final TaskOrganizer mTaskOrganizer;

    WindowManagerProxy(SyncTransactionQueue syncQueue, TaskOrganizer taskOrganizer) {
        mSyncTransactionQueue = syncQueue;
        mTaskOrganizer = taskOrganizer;
    }

    void dismissOrMaximizeDocked(final LegacySplitScreenTaskListener tiles,
            LegacySplitDisplayLayout layout, final boolean dismissOrMaximize) {
        if (Transitions.ENABLE_SHELL_TRANSITIONS) {
            tiles.mSplitScreenController.startDismissSplit(!dismissOrMaximize, true /* snapped */);
        } else {
            applyDismissSplit(tiles, layout, dismissOrMaximize);
        }
    }

    public void setResizing(final boolean resizing) {
        try {
            ActivityTaskManager.getService().setSplitScreenResizing(resizing);
        } catch (RemoteException e) {
            Log.w(TAG, "Error calling setDockedStackResizing: " + e);
        }
    }

    /** Sets a touch region */
    public void setTouchRegion(Rect region) {
        try {
            synchronized (mDockedRect) {
                mTouchableRegion.set(region);
            }
            WindowManagerGlobal.getWindowManagerService().setDockedTaskDividerTouchRegion(
                    mTouchableRegion);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to set touchable region: " + e);
        }
    }

    void applyResizeSplits(int position, LegacySplitDisplayLayout splitLayout) {
        WindowContainerTransaction t = new WindowContainerTransaction();
        splitLayout.resizeSplits(position, t);
        applySyncTransaction(t);
    }

    boolean getHomeAndRecentsTasks(List<ActivityManager.RunningTaskInfo> out,
            WindowContainerToken parent) {
        boolean resizable = false;
        List<ActivityManager.RunningTaskInfo> rootTasks = parent == null
                ? mTaskOrganizer.getRootTasks(Display.DEFAULT_DISPLAY, HOME_AND_RECENTS)
                : mTaskOrganizer.getChildTasks(parent, HOME_AND_RECENTS);
        for (int i = 0, n = rootTasks.size(); i < n; ++i) {
            final ActivityManager.RunningTaskInfo ti = rootTasks.get(i);
            out.add(ti);
            if (ti.topActivityType == ACTIVITY_TYPE_HOME) {
                resizable = ti.isResizeable;
            }
        }
        return resizable;
    }

    /**
     * Assign a fixed override-bounds to home tasks that reflect their geometry while the primary
     * split is minimized. This actually "sticks out" of the secondary split area, but when in
     * minimized mode, the secondary split gets a 'negative' crop to expose it.
     */
    boolean applyHomeTasksMinimized(LegacySplitDisplayLayout layout, WindowContainerToken parent,
            @NonNull WindowContainerTransaction wct) {
        // Resize the home/recents stacks to the larger minimized-state size
        final Rect homeBounds;
        final ArrayList<ActivityManager.RunningTaskInfo> homeStacks = new ArrayList<>();
        boolean isHomeResizable = getHomeAndRecentsTasks(homeStacks, parent);
        if (isHomeResizable) {
            homeBounds = layout.calcResizableMinimizedHomeStackBounds();
        } else {
            // home is not resizable, so lock it to its inherent orientation size.
            homeBounds = new Rect(0, 0, 0, 0);
            for (int i = homeStacks.size() - 1; i >= 0; --i) {
                if (homeStacks.get(i).topActivityType == ACTIVITY_TYPE_HOME) {
                    final int orient = homeStacks.get(i).configuration.orientation;
                    final boolean displayLandscape = layout.mDisplayLayout.isLandscape();
                    final boolean isLandscape = orient == ORIENTATION_LANDSCAPE
                            || (orient == ORIENTATION_UNDEFINED && displayLandscape);
                    homeBounds.right = isLandscape == displayLandscape
                            ? layout.mDisplayLayout.width() : layout.mDisplayLayout.height();
                    homeBounds.bottom = isLandscape == displayLandscape
                            ? layout.mDisplayLayout.height() : layout.mDisplayLayout.width();
                    break;
                }
            }
        }
        for (int i = homeStacks.size() - 1; i >= 0; --i) {
            // For non-resizable homes, the minimized size is actually the fullscreen-size. As a
            // result, we don't minimize for recents since it only shows half-size screenshots.
            if (!isHomeResizable) {
                if (homeStacks.get(i).topActivityType == ACTIVITY_TYPE_RECENTS) {
                    continue;
                }
                wct.setWindowingMode(homeStacks.get(i).token, WINDOWING_MODE_FULLSCREEN);
            }
            wct.setBounds(homeStacks.get(i).token, homeBounds);
        }
        layout.mTiles.mHomeBounds.set(homeBounds);
        return isHomeResizable;
    }

    /** @see #buildEnterSplit */
    boolean applyEnterSplit(LegacySplitScreenTaskListener tiles, LegacySplitDisplayLayout layout) {
        // Set launchtile first so that any stack created after
        // getAllRootTaskInfos and before reparent (even if unlikely) are placed
        // correctly.
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setLaunchRoot(tiles.mSecondary.token, CONTROLLED_WINDOWING_MODES,
                CONTROLLED_ACTIVITY_TYPES);
        final boolean isHomeResizable = buildEnterSplit(wct, tiles, layout);
        applySyncTransaction(wct);
        return isHomeResizable;
    }

    /**
     * Finishes entering split-screen by reparenting all FULLSCREEN tasks into the secondary split.
     * This assumes there is already something in the primary split since that is usually what
     * triggers a call to this. In the same transaction, this overrides the home task bounds via
     * {@link #applyHomeTasksMinimized}.
     *
     * @return whether the home stack is resizable
     */
    boolean buildEnterSplit(WindowContainerTransaction outWct, LegacySplitScreenTaskListener tiles,
            LegacySplitDisplayLayout layout) {
        List<ActivityManager.RunningTaskInfo> rootTasks =
                mTaskOrganizer.getRootTasks(DEFAULT_DISPLAY, null /* activityTypes */);
        if (rootTasks.isEmpty()) {
            return false;
        }
        ActivityManager.RunningTaskInfo topHomeTask = null;
        for (int i = rootTasks.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo rootTask = rootTasks.get(i);
            // Check whether the task can be moved to split secondary.
            if (!rootTask.supportsMultiWindow && rootTask.topActivityType != ACTIVITY_TYPE_HOME) {
                continue;
            }
            // Only move split controlling tasks to split secondary.
            final int windowingMode = rootTask.getWindowingMode();
            if (!ArrayUtils.contains(CONTROLLED_WINDOWING_MODES, windowingMode)
                    || !ArrayUtils.contains(CONTROLLED_ACTIVITY_TYPES, rootTask.getActivityType())
                    // Excludes split screen secondary due to it's the root we're reparenting to.
                    || windowingMode == WINDOWING_MODE_SPLIT_SCREEN_SECONDARY) {
                continue;
            }
            // Since this iterates from bottom to top, update topHomeTask for every fullscreen task
            // so it will be left with the status of the top one.
            topHomeTask = isHomeOrRecentTask(rootTask) ? rootTask : null;
            outWct.reparent(rootTask.token, tiles.mSecondary.token, true /* onTop */);
        }
        // Move the secondary split-forward.
        outWct.reorder(tiles.mSecondary.token, true /* onTop */);
        boolean isHomeResizable = applyHomeTasksMinimized(layout, null /* parent */,
                outWct);
        if (topHomeTask != null && !Transitions.ENABLE_SHELL_TRANSITIONS) {
            // Translate/update-crop of secondary out-of-band with sync transaction -- Until BALST
            // is enabled, this temporarily syncs the home surface position with offset until
            // sync transaction finishes.
            outWct.setBoundsChangeTransaction(topHomeTask.token, tiles.mHomeBounds);
        }
        return isHomeResizable;
    }

    static boolean isHomeOrRecentTask(ActivityManager.RunningTaskInfo ti) {
        final int atype = ti.getActivityType();
        return atype == ACTIVITY_TYPE_HOME || atype == ACTIVITY_TYPE_RECENTS;
    }

    /** @see #buildDismissSplit */
    void applyDismissSplit(LegacySplitScreenTaskListener tiles, LegacySplitDisplayLayout layout,
            boolean dismissOrMaximize) {
        // TODO(task-org): Once task-org is more complete, consider using Appeared/Vanished
        //                 plus specific APIs to clean this up.
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        // Set launch root first so that any task created after getChildContainers and
        // before reparent (pretty unlikely) are put into fullscreen.
        wct.setLaunchRoot(tiles.mSecondary.token, null, null);
        buildDismissSplit(wct, tiles, layout, dismissOrMaximize);
        applySyncTransaction(wct);
    }

    /**
     * Reparents all tile members back to their display and resets home task override bounds.
     * @param dismissOrMaximize When {@code true} this resolves the split by closing the primary
     *                          split (thus resulting in the top of the secondary split becoming
     *                          fullscreen. {@code false} resolves the other way.
     */
    static void buildDismissSplit(WindowContainerTransaction outWct,
            LegacySplitScreenTaskListener tiles, LegacySplitDisplayLayout layout,
            boolean dismissOrMaximize) {
        // TODO(task-org): Once task-org is more complete, consider using Appeared/Vanished
        //                 plus specific APIs to clean this up.
        final TaskOrganizer taskOrg = tiles.getTaskOrganizer();
        List<ActivityManager.RunningTaskInfo> primaryChildren =
                taskOrg.getChildTasks(tiles.mPrimary.token, null /* activityTypes */);
        List<ActivityManager.RunningTaskInfo> secondaryChildren =
                taskOrg.getChildTasks(tiles.mSecondary.token, null /* activityTypes */);
        // In some cases (eg. non-resizable is launched), system-server will leave split-screen.
        // as a result, the above will not capture any tasks; yet, we need to clean-up the
        // home task bounds.
        List<ActivityManager.RunningTaskInfo> freeHomeAndRecents =
                taskOrg.getRootTasks(DEFAULT_DISPLAY, HOME_AND_RECENTS);
        // Filter out the root split tasks
        freeHomeAndRecents.removeIf(p -> p.token.equals(tiles.mSecondary.token)
                || p.token.equals(tiles.mPrimary.token));

        if (primaryChildren.isEmpty() && secondaryChildren.isEmpty()
                && freeHomeAndRecents.isEmpty()) {
            return;
        }
        if (dismissOrMaximize) {
            // Dismissing, so move all primary split tasks first
            for (int i = primaryChildren.size() - 1; i >= 0; --i) {
                outWct.reparent(primaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
            boolean homeOnTop = false;
            // Don't need to worry about home tasks because they are already in the "proper"
            // order within the secondary split.
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                final ActivityManager.RunningTaskInfo ti = secondaryChildren.get(i);
                outWct.reparent(ti.token, null /* parent */, true /* onTop */);
                if (isHomeOrRecentTask(ti)) {
                    outWct.setBounds(ti.token, null);
                    outWct.setWindowingMode(ti.token, WINDOWING_MODE_UNDEFINED);
                    if (i == 0) {
                        homeOnTop = true;
                    }
                }
            }
            if (homeOnTop && !Transitions.ENABLE_SHELL_TRANSITIONS) {
                // Translate/update-crop of secondary out-of-band with sync transaction -- instead
                // play this in sync with new home-app frame because until BALST is enabled this
                // shows up on screen before the syncTransaction returns.
                // We only have access to the secondary root surface, though, so in order to
                // position things properly, we have to take into account the existing negative
                // offset/crop of the minimized-home task.
                final boolean landscape = layout.mDisplayLayout.isLandscape();
                final int posX = landscape ? layout.mSecondary.left - tiles.mHomeBounds.left
                        : layout.mSecondary.left;
                final int posY = landscape ? layout.mSecondary.top
                        : layout.mSecondary.top - tiles.mHomeBounds.top;
                final SurfaceControl.Transaction sft = new SurfaceControl.Transaction();
                sft.setPosition(tiles.mSecondarySurface, posX, posY);
                final Rect crop = new Rect(0, 0, layout.mDisplayLayout.width(),
                        layout.mDisplayLayout.height());
                crop.offset(-posX, -posY);
                sft.setWindowCrop(tiles.mSecondarySurface, crop);
                outWct.setBoundsChangeTransaction(tiles.mSecondary.token, sft);
            }
        } else {
            // Maximize, so move non-home secondary split first
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                if (isHomeOrRecentTask(secondaryChildren.get(i))) {
                    continue;
                }
                outWct.reparent(secondaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
            // Find and place home tasks in-between. This simulates the fact that there was
            // nothing behind the primary split's tasks.
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                final ActivityManager.RunningTaskInfo ti = secondaryChildren.get(i);
                if (isHomeOrRecentTask(ti)) {
                    outWct.reparent(ti.token, null /* parent */, true /* onTop */);
                    // reset bounds and mode too
                    outWct.setBounds(ti.token, null);
                    outWct.setWindowingMode(ti.token, WINDOWING_MODE_UNDEFINED);
                }
            }
            for (int i = primaryChildren.size() - 1; i >= 0; --i) {
                outWct.reparent(primaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
        }
        for (int i = freeHomeAndRecents.size() - 1; i >= 0; --i) {
            outWct.setBounds(freeHomeAndRecents.get(i).token, null);
            outWct.setWindowingMode(freeHomeAndRecents.get(i).token, WINDOWING_MODE_UNDEFINED);
        }
        // Reset focusable to true
        outWct.setFocusable(tiles.mPrimary.token, true /* focusable */);
    }

    /**
     * Utility to apply a sync transaction serially with other sync transactions.
     *
     * @see SyncTransactionQueue#queue
     */
    void applySyncTransaction(WindowContainerTransaction wct) {
        mSyncTransactionQueue.queue(wct);
    }

    /**
     * @see SyncTransactionQueue#queueIfWaiting
     */
    boolean queueSyncTransactionIfWaiting(WindowContainerTransaction wct) {
        return mSyncTransactionQueue.queueIfWaiting(wct);
    }

    /**
     * @see SyncTransactionQueue#runInSync
     */
    void runInSync(SyncTransactionQueue.TransactionRunnable runnable) {
        mSyncTransactionQueue.runInSync(runnable);
    }
}
