/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.os.Trace;
import android.util.ArrayMap;
import android.view.WindowManager;
import android.window.TaskSnapshot;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Integrates common functionality from TaskSnapshotController and ActivitySnapshotController.
 */
class SnapshotController {
    private final SnapshotPersistQueue mSnapshotPersistQueue;
    final TaskSnapshotController mTaskSnapshotController;
    final ActivitySnapshotController mActivitySnapshotController;

    SnapshotController(WindowManagerService wms) {
        mSnapshotPersistQueue = new SnapshotPersistQueue();
        mTaskSnapshotController = new TaskSnapshotController(wms, mSnapshotPersistQueue);
        mActivitySnapshotController = new ActivitySnapshotController(wms, mSnapshotPersistQueue);
    }

    void systemReady() {
        mSnapshotPersistQueue.systemReady();
    }

    void setPause(boolean paused) {
        mSnapshotPersistQueue.setPaused(paused);
    }

    void onAppRemoved(ActivityRecord activity) {
        mTaskSnapshotController.onAppRemoved(activity);
        mActivitySnapshotController.onAppRemoved(activity);
    }

    void onAppDied(ActivityRecord activity) {
        mTaskSnapshotController.onAppDied(activity);
        mActivitySnapshotController.onAppDied(activity);
    }

    void notifyAppVisibilityChanged(ActivityRecord appWindowToken, boolean visible) {
        mActivitySnapshotController.notifyAppVisibilityChanged(appWindowToken, visible);
    }

    // For legacy transition, which won't support activity snapshot
    void onTransitionStarting(DisplayContent displayContent) {
        mTaskSnapshotController.handleClosingApps(displayContent.mClosingApps);
    }

    // For shell transition, record snapshots before transaction start.
    void onTransactionReady(@WindowManager.TransitionType int type,
            ArrayList<Transition.ChangeInfo> changeInfos) {
        final boolean isTransitionOpen = isTransitionOpen(type);
        final boolean isTransitionClose = isTransitionClose(type);
        if (!isTransitionOpen && !isTransitionClose && type < TRANSIT_FIRST_CUSTOM) {
            return;
        }
        ActivitiesByTask activityTargets = null;
        for (int i = changeInfos.size() - 1; i >= 0; --i) {
            Transition.ChangeInfo info = changeInfos.get(i);
            // Intentionally skip record snapshot for changes originated from PiP.
            if (info.mWindowingMode == WINDOWING_MODE_PINNED) continue;
            if (info.mContainer.isActivityTypeHome()) continue;
            final Task task = info.mContainer.asTask();
            if (task != null && !task.mCreatedByOrganizer && !task.isVisibleRequested()) {
                mTaskSnapshotController.recordSnapshot(task, info);
            }
            // Won't need to capture activity snapshot in close transition.
            if (isTransitionClose) {
                continue;
            }
            if (info.mContainer.asActivityRecord() != null
                    || info.mContainer.asTaskFragment() != null) {
                final TaskFragment tf = info.mContainer.asTaskFragment();
                final ActivityRecord ar = tf != null ? tf.getTopMostActivity()
                        : info.mContainer.asActivityRecord();
                if (ar != null && ar.getTask().isVisibleRequested()) {
                    if (activityTargets == null) {
                        activityTargets = new ActivitiesByTask();
                    }
                    activityTargets.put(ar);
                }
            }
        }
        if (activityTargets != null) {
            activityTargets.recordSnapshot(mActivitySnapshotController);
        }
    }

    private static class ActivitiesByTask {
        final ArrayMap<Task, OpenCloseActivities> mActivitiesMap = new ArrayMap<>();

        void put(ActivityRecord ar) {
            OpenCloseActivities activities = mActivitiesMap.get(ar.getTask());
            if (activities == null) {
                activities = new OpenCloseActivities();
                mActivitiesMap.put(ar.getTask(), activities);
            }
            activities.add(ar);
        }

        void recordSnapshot(ActivitySnapshotController controller) {
            for (int i = mActivitiesMap.size() - 1; i >= 0; i--) {
                final OpenCloseActivities pair = mActivitiesMap.valueAt(i);
                pair.recordSnapshot(controller);
            }
        }

        static class OpenCloseActivities {
            final ArrayList<ActivityRecord> mOpenActivities = new ArrayList<>();
            final ArrayList<ActivityRecord> mCloseActivities = new ArrayList<>();

            void add(ActivityRecord ar) {
                if (ar.isVisibleRequested()) {
                    mOpenActivities.add(ar);
                } else {
                    mCloseActivities.add(ar);
                }
            }

            boolean allOpensOptInOnBackInvoked() {
                if (mOpenActivities.isEmpty()) {
                    return false;
                }
                for (int i = mOpenActivities.size() - 1; i >= 0; --i) {
                    if (!mOpenActivities.get(i).mOptInOnBackInvoked) {
                        return false;
                    }
                }
                return true;
            }

            void recordSnapshot(ActivitySnapshotController controller) {
                if (!allOpensOptInOnBackInvoked() || mCloseActivities.isEmpty()) {
                    return;
                }
                controller.recordSnapshot(mCloseActivities);
            }
        }
    }

    void onTransitionFinish(@WindowManager.TransitionType int type,
            ArrayList<Transition.ChangeInfo> changeInfos) {
        final boolean isTransitionOpen = isTransitionOpen(type);
        final boolean isTransitionClose = isTransitionClose(type);
        if (!isTransitionOpen && !isTransitionClose && type < TRANSIT_FIRST_CUSTOM
                || (changeInfos.isEmpty())) {
            return;
        }
        Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "SnapshotController_analysis");
        mActivitySnapshotController.beginSnapshotProcess();
        final ArrayList<WindowContainer> windows = new ArrayList<>();
        for (int i = changeInfos.size() - 1; i >= 0; --i) {
            final WindowContainer wc = changeInfos.get(i).mContainer;
            if (wc.asTask() == null && wc.asTaskFragment() == null
                    && wc.asActivityRecord() == null) {
                continue;
            }
            windows.add(wc);
        }
        mActivitySnapshotController.handleTransitionFinish(windows);
        mActivitySnapshotController.endSnapshotProcess();
        // Remove task snapshot if it is visible at the end of transition.
        for (int i = changeInfos.size() - 1; i >= 0; --i) {
            final WindowContainer wc = changeInfos.get(i).mContainer;
            final Task task = wc.asTask();
            if (task != null && wc.isVisibleRequested()) {
                final TaskSnapshot snapshot = mTaskSnapshotController.getSnapshot(task.mTaskId,
                        task.mUserId, false /* restoreFromDisk */, false /* isLowResolution */);
                if (snapshot != null) {
                    mTaskSnapshotController.removeAndDeleteSnapshot(task.mTaskId, task.mUserId);
                }
            }
        }
        Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
    }

    private static boolean isTransitionOpen(int type) {
        return type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT;
    }
    private static boolean isTransitionClose(int type) {
        return type == TRANSIT_CLOSE || type == TRANSIT_TO_BACK;
    }

    void dump(PrintWriter pw, String prefix) {
        mTaskSnapshotController.dump(pw, prefix);
        mActivitySnapshotController.dump(pw, prefix);
    }
}
