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

package com.android.systemui.stackdivider;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManagerGlobal;
import android.window.TaskOrganizer;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import com.android.internal.annotations.GuardedBy;
import com.android.systemui.TransactionPool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Proxy to simplify calls into window manager/activity manager
 */
public class WindowManagerProxy {

    private static final String TAG = "WindowManagerProxy";
    private static final int[] HOME_AND_RECENTS = {ACTIVITY_TYPE_HOME, ACTIVITY_TYPE_RECENTS};

    @GuardedBy("mDockedRect")
    private final Rect mDockedRect = new Rect();

    private final Rect mTmpRect1 = new Rect();

    @GuardedBy("mDockedRect")
    private final Rect mTouchableRegion = new Rect();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final SyncTransactionQueue mSyncTransactionQueue;

    private final Runnable mSetTouchableRegionRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                synchronized (mDockedRect) {
                    mTmpRect1.set(mTouchableRegion);
                }
                WindowManagerGlobal.getWindowManagerService().setDockedStackDividerTouchRegion(
                        mTmpRect1);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to set touchable region: " + e);
            }
        }
    };

    WindowManagerProxy(TransactionPool transactionPool, Handler handler) {
        mSyncTransactionQueue = new SyncTransactionQueue(transactionPool, handler);
    }

    void dismissOrMaximizeDocked(final SplitScreenTaskOrganizer tiles, SplitDisplayLayout layout,
            final boolean dismissOrMaximize) {
        mExecutor.execute(() -> applyDismissSplit(tiles, layout, dismissOrMaximize));
    }

    public void setResizing(final boolean resizing) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ActivityTaskManager.getService().setSplitScreenResizing(resizing);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error calling setDockedStackResizing: " + e);
                }
            }
        });
    }

    /** Sets a touch region */
    public void setTouchRegion(Rect region) {
        synchronized (mDockedRect) {
            mTouchableRegion.set(region);
        }
        mExecutor.execute(mSetTouchableRegionRunnable);
    }

    void applyResizeSplits(int position, SplitDisplayLayout splitLayout) {
        WindowContainerTransaction t = new WindowContainerTransaction();
        splitLayout.resizeSplits(position, t);
        applySyncTransaction(t);
    }

    private static boolean getHomeAndRecentsTasks(List<ActivityManager.RunningTaskInfo> out,
            WindowContainerToken parent) {
        boolean resizable = false;
        List<ActivityManager.RunningTaskInfo> rootTasks = parent == null
                ? TaskOrganizer.getRootTasks(Display.DEFAULT_DISPLAY, HOME_AND_RECENTS)
                : TaskOrganizer.getChildTasks(parent, HOME_AND_RECENTS);
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
    static boolean applyHomeTasksMinimized(SplitDisplayLayout layout, WindowContainerToken parent,
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

    /**
     * Finishes entering split-screen by reparenting all FULLSCREEN tasks into the secondary split.
     * This assumes there is already something in the primary split since that is usually what
     * triggers a call to this. In the same transaction, this overrides the home task bounds via
     * {@link #applyHomeTasksMinimized}.
     *
     * @return whether the home stack is resizable
     */
    boolean applyEnterSplit(SplitScreenTaskOrganizer tiles, SplitDisplayLayout layout) {
        // Set launchtile first so that any stack created after
        // getAllStackInfos and before reparent (even if unlikely) are placed
        // correctly.
        TaskOrganizer.setLaunchRoot(DEFAULT_DISPLAY, tiles.mSecondary.token);
        List<ActivityManager.RunningTaskInfo> rootTasks =
                TaskOrganizer.getRootTasks(DEFAULT_DISPLAY, null /* activityTypes */);
        WindowContainerTransaction wct = new WindowContainerTransaction();
        if (rootTasks.isEmpty()) {
            return false;
        }
        ActivityManager.RunningTaskInfo topHomeTask = null;
        for (int i = rootTasks.size() - 1; i >= 0; --i) {
            final ActivityManager.RunningTaskInfo rootTask = rootTasks.get(i);
            // Only move resizeable task to split secondary. However, we have an exception
            // for non-resizable home because we will minimize to show it.
            if (!rootTask.isResizeable && rootTask.topActivityType != ACTIVITY_TYPE_HOME) {
                continue;
            }
            // Only move fullscreen tasks to split secondary.
            if (rootTask.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_FULLSCREEN) {
                continue;
            }
            // Since this iterates from bottom to top, update topHomeTask for every fullscreen task
            // so it will be left with the status of the top one.
            topHomeTask = isHomeOrRecentTask(rootTask) ? rootTask : null;
            wct.reparent(rootTask.token, tiles.mSecondary.token, true /* onTop */);
        }
        // Move the secondary split-forward.
        wct.reorder(tiles.mSecondary.token, true /* onTop */);
        boolean isHomeResizable = applyHomeTasksMinimized(layout, null /* parent */, wct);
        if (topHomeTask != null) {
            // Translate/update-crop of secondary out-of-band with sync transaction -- Until BALST
            // is enabled, this temporarily syncs the home surface position with offset until
            // sync transaction finishes.
            wct.setBoundsChangeTransaction(topHomeTask.token, tiles.mHomeBounds);
        }
        applySyncTransaction(wct);
        return isHomeResizable;
    }

    static boolean isHomeOrRecentTask(ActivityManager.RunningTaskInfo ti) {
        final int atype = ti.configuration.windowConfiguration.getActivityType();
        return atype == ACTIVITY_TYPE_HOME || atype == ACTIVITY_TYPE_RECENTS;
    }

    /**
     * Reparents all tile members back to their display and resets home task override bounds.
     * @param dismissOrMaximize When {@code true} this resolves the split by closing the primary
     *                          split (thus resulting in the top of the secondary split becoming
     *                          fullscreen. {@code false} resolves the other way.
     */
    void applyDismissSplit(SplitScreenTaskOrganizer tiles, SplitDisplayLayout layout,
            boolean dismissOrMaximize) {
        // Set launch root first so that any task created after getChildContainers and
        // before reparent (pretty unlikely) are put into fullscreen.
        TaskOrganizer.setLaunchRoot(Display.DEFAULT_DISPLAY, null);
        // TODO(task-org): Once task-org is more complete, consider using Appeared/Vanished
        //                 plus specific APIs to clean this up.
        List<ActivityManager.RunningTaskInfo> primaryChildren =
                TaskOrganizer.getChildTasks(tiles.mPrimary.token, null /* activityTypes */);
        List<ActivityManager.RunningTaskInfo> secondaryChildren =
                TaskOrganizer.getChildTasks(tiles.mSecondary.token, null /* activityTypes */);
        // In some cases (eg. non-resizable is launched), system-server will leave split-screen.
        // as a result, the above will not capture any tasks; yet, we need to clean-up the
        // home task bounds.
        List<ActivityManager.RunningTaskInfo> freeHomeAndRecents =
                TaskOrganizer.getRootTasks(DEFAULT_DISPLAY, HOME_AND_RECENTS);
        // Filter out the root split tasks
        freeHomeAndRecents.removeIf(p -> p.token.equals(tiles.mSecondary.token)
                || p.token.equals(tiles.mPrimary.token));

        if (primaryChildren.isEmpty() && secondaryChildren.isEmpty()
                && freeHomeAndRecents.isEmpty()) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        if (dismissOrMaximize) {
            // Dismissing, so move all primary split tasks first
            for (int i = primaryChildren.size() - 1; i >= 0; --i) {
                wct.reparent(primaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
            boolean homeOnTop = false;
            // Don't need to worry about home tasks because they are already in the "proper"
            // order within the secondary split.
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                final ActivityManager.RunningTaskInfo ti = secondaryChildren.get(i);
                wct.reparent(ti.token, null /* parent */, true /* onTop */);
                if (isHomeOrRecentTask(ti)) {
                    wct.setBounds(ti.token, null);
                    wct.setWindowingMode(ti.token, WINDOWING_MODE_UNDEFINED);
                    if (i == 0) {
                        homeOnTop = true;
                    }
                }
            }
            if (homeOnTop) {
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
                wct.setBoundsChangeTransaction(tiles.mSecondary.token, sft);
            }
        } else {
            // Maximize, so move non-home secondary split first
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                if (isHomeOrRecentTask(secondaryChildren.get(i))) {
                    continue;
                }
                wct.reparent(secondaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
            // Find and place home tasks in-between. This simulates the fact that there was
            // nothing behind the primary split's tasks.
            for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                final ActivityManager.RunningTaskInfo ti = secondaryChildren.get(i);
                if (isHomeOrRecentTask(ti)) {
                    wct.reparent(ti.token, null /* parent */, true /* onTop */);
                    // reset bounds and mode too
                    wct.setBounds(ti.token, null);
                    wct.setWindowingMode(ti.token, WINDOWING_MODE_UNDEFINED);
                }
            }
            for (int i = primaryChildren.size() - 1; i >= 0; --i) {
                wct.reparent(primaryChildren.get(i).token, null /* parent */,
                        true /* onTop */);
            }
        }
        for (int i = freeHomeAndRecents.size() - 1; i >= 0; --i) {
            wct.setBounds(freeHomeAndRecents.get(i).token, null);
            wct.setWindowingMode(freeHomeAndRecents.get(i).token, WINDOWING_MODE_UNDEFINED);
        }
        // Reset focusable to true
        wct.setFocusable(tiles.mPrimary.token, true /* focusable */);
        applySyncTransaction(wct);
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
