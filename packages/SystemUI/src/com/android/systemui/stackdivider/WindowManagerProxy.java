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
import static android.view.Display.DEFAULT_DISPLAY;
import static android.window.WindowOrganizer.TaskOrganizer;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.WindowManagerGlobal;
import android.window.IWindowContainer;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import com.android.internal.annotations.GuardedBy;

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

    private static final WindowManagerProxy sInstance = new WindowManagerProxy();

    @GuardedBy("mDockedRect")
    private final Rect mDockedRect = new Rect();

    private final Rect mTmpRect1 = new Rect();

    @GuardedBy("mDockedRect")
    private final Rect mTouchableRegion = new Rect();

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

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

    private WindowManagerProxy() {
    }

    public static WindowManagerProxy getInstance() {
        return sInstance;
    }

    void dismissOrMaximizeDocked(
            final SplitScreenTaskOrganizer tiles, final boolean dismissOrMaximize) {
        mExecutor.execute(() -> applyDismissSplit(tiles, dismissOrMaximize));
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

    static void applyResizeSplits(int position, SplitDisplayLayout splitLayout) {
        WindowContainerTransaction t = new WindowContainerTransaction();
        splitLayout.resizeSplits(position, t);
        try {
            WindowOrganizer.applyTransaction(t);
        } catch (RemoteException e) {
        }
    }

    private static boolean getHomeAndRecentsTasks(List<IWindowContainer> out,
            IWindowContainer parent) {
        boolean resizable = false;
        try {
            List<ActivityManager.RunningTaskInfo> rootTasks = parent == null
                    ? TaskOrganizer.getRootTasks(Display.DEFAULT_DISPLAY, HOME_AND_RECENTS)
                    : TaskOrganizer.getChildTasks(parent, HOME_AND_RECENTS);
            for (int i = 0, n = rootTasks.size(); i < n; ++i) {
                final ActivityManager.RunningTaskInfo ti = rootTasks.get(i);
                out.add(ti.token);
                if (ti.topActivityType == ACTIVITY_TYPE_HOME) {
                    resizable = ti.isResizable();
                }
            }
        } catch (RemoteException e) {
        }
        return resizable;
    }

    /**
     * Assign a fixed override-bounds to home tasks that reflect their geometry while the primary
     * split is minimized. This actually "sticks out" of the secondary split area, but when in
     * minimized mode, the secondary split gets a 'negative' crop to expose it.
     */
    static boolean applyHomeTasksMinimized(SplitDisplayLayout layout, IWindowContainer parent,
            @NonNull WindowContainerTransaction wct) {
        // Resize the home/recents stacks to the larger minimized-state size
        final Rect homeBounds;
        final ArrayList<IWindowContainer> homeStacks = new ArrayList<>();
        boolean isHomeResizable = getHomeAndRecentsTasks(homeStacks, parent);
        if (isHomeResizable) {
            homeBounds = layout.calcMinimizedHomeStackBounds();
        } else {
            homeBounds = new Rect(0, 0, layout.mDisplayLayout.width(),
                    layout.mDisplayLayout.height());
        }
        for (int i = homeStacks.size() - 1; i >= 0; --i) {
            wct.setBounds(homeStacks.get(i), homeBounds);
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
    static boolean applyEnterSplit(SplitScreenTaskOrganizer tiles, SplitDisplayLayout layout) {
        try {
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
            tiles.mHomeAndRecentsSurfaces.clear();
            for (int i = rootTasks.size() - 1; i >= 0; --i) {
                final ActivityManager.RunningTaskInfo rootTask = rootTasks.get(i);
                if (isHomeOrRecentTask(rootTask)) {
                    tiles.mHomeAndRecentsSurfaces.add(rootTask.token.getLeash());
                }
                if (rootTask.configuration.windowConfiguration.getWindowingMode()
                        != WINDOWING_MODE_FULLSCREEN) {
                    continue;
                }
                wct.reparent(rootTask.token, tiles.mSecondary.token, true /* onTop */);
            }
            boolean isHomeResizable = applyHomeTasksMinimized(layout, null /* parent */, wct);
            WindowOrganizer.applyTransaction(wct);
            return isHomeResizable;
        } catch (RemoteException e) {
            Log.w(TAG, "Error moving fullscreen tasks to secondary split: " + e);
        }
        return false;
    }

    private static boolean isHomeOrRecentTask(ActivityManager.RunningTaskInfo ti) {
        final int atype = ti.configuration.windowConfiguration.getActivityType();
        return atype == ACTIVITY_TYPE_HOME || atype == ACTIVITY_TYPE_RECENTS;
    }

    /**
     * Reparents all tile members back to their display and resets home task override bounds.
     * @param dismissOrMaximize When {@code true} this resolves the split by closing the primary
     *                          split (thus resulting in the top of the secondary split becoming
     *                          fullscreen. {@code false} resolves the other way.
     */
    static void applyDismissSplit(SplitScreenTaskOrganizer tiles, boolean dismissOrMaximize) {
        try {
            // Set launch root first so that any task created after getChildContainers and
            // before reparent (pretty unlikely) are put into fullscreen.
            TaskOrganizer.setLaunchRoot(Display.DEFAULT_DISPLAY, null);
            tiles.mHomeAndRecentsSurfaces.clear();
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
                    TaskOrganizer.getRootTasks(Display.DEFAULT_DISPLAY, HOME_AND_RECENTS);
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
                // Don't need to worry about home tasks because they are already in the "proper"
                // order within the secondary split.
                for (int i = secondaryChildren.size() - 1; i >= 0; --i) {
                    final ActivityManager.RunningTaskInfo ti = secondaryChildren.get(i);
                    wct.reparent(ti.token, null /* parent */, true /* onTop */);
                    if (isHomeOrRecentTask(ti)) {
                        wct.setBounds(ti.token, null);
                    }
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
                        // reset bounds too
                        wct.setBounds(ti.token, null);
                    }
                }
                for (int i = primaryChildren.size() - 1; i >= 0; --i) {
                    wct.reparent(primaryChildren.get(i).token, null /* parent */,
                            true /* onTop */);
                }
            }
            for (int i = freeHomeAndRecents.size() - 1; i >= 0; --i) {
                wct.setBounds(freeHomeAndRecents.get(i).token, null);
            }
            // Reset focusable to true
            wct.setFocusable(tiles.mPrimary.token, true /* focusable */);
            WindowOrganizer.applyTransaction(wct);
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to remove stack: " + e);
        }
    }

    static void applyContainerTransaction(WindowContainerTransaction wct) {
        try {
            WindowOrganizer.applyTransaction(wct);
        } catch (RemoteException e) {
            Log.w(TAG, "Error setting focusability: " + e);
        }
    }
}
