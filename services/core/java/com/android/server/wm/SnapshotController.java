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

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.annotation.IntDef;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Integrates common functionality from TaskSnapshotController and ActivitySnapshotController.
 */
class SnapshotController {
    private static final boolean DEBUG = false;
    private static final String TAG = AbsAppSnapshotController.TAG;

    static final int ACTIVITY_OPEN = 1;
    static final int ACTIVITY_CLOSE = 2;
    static final int TASK_OPEN = 4;
    static final int TASK_CLOSE = 8;
    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            value = {ACTIVITY_OPEN,
                    ACTIVITY_CLOSE,
                    TASK_OPEN,
                    TASK_CLOSE})
    @interface TransitionStateType {}

    private final SnapshotPersistQueue mSnapshotPersistQueue;
    final TaskSnapshotController mTaskSnapshotController;
    final ActivitySnapshotController mActivitySnapshotController;

    private final ArraySet<Task> mTmpCloseTasks = new ArraySet<>();
    private final ArraySet<Task> mTmpOpenTasks = new ArraySet<>();

    private final SparseArray<TransitionState> mTmpOpenCloseRecord = new SparseArray<>();
    private final ArraySet<Integer> mTmpAnalysisRecord = new ArraySet<>();
    private final SparseArray<ArrayList<Consumer<TransitionState>>> mTransitionStateConsumer =
            new SparseArray<>();
    private int mActivatedType;

    private final ActivityOrderCheck mActivityOrderCheck = new ActivityOrderCheck();
    private final ActivityOrderCheck.AnalysisResult mResultHandler = (type, close, open) -> {
        addTransitionRecord(type, true/* open */, open);
        addTransitionRecord(type, false/* open */, close);
    };

    private static class ActivityOrderCheck {
        private ActivityRecord mOpenActivity;
        private ActivityRecord mCloseActivity;
        private int mOpenIndex = -1;
        private int mCloseIndex = -1;

        private void reset() {
            mOpenActivity = null;
            mCloseActivity = null;
            mOpenIndex = -1;
            mCloseIndex = -1;
        }

        private void setTarget(boolean open, ActivityRecord ar, int index) {
            if (open) {
                mOpenActivity = ar;
                mOpenIndex = index;
            } else {
                mCloseActivity = ar;
                mCloseIndex = index;
            }
        }

        void analysisOrder(ArraySet<ActivityRecord> closeApps,
                ArraySet<ActivityRecord> openApps, Task task, AnalysisResult result) {
            for (int j = closeApps.size() - 1; j >= 0; j--) {
                final ActivityRecord ar = closeApps.valueAt(j);
                if (ar.getTask() == task) {
                    setTarget(false, ar, task.mChildren.indexOf(ar));
                    break;
                }
            }
            for (int j = openApps.size() - 1; j >= 0; j--) {
                final ActivityRecord ar = openApps.valueAt(j);
                if (ar.getTask() == task) {
                    setTarget(true, ar, task.mChildren.indexOf(ar));
                    break;
                }
            }
            if (mOpenIndex > mCloseIndex && mCloseIndex != -1) {
                result.onCheckResult(ACTIVITY_OPEN, mCloseActivity, mOpenActivity);
            } else if (mOpenIndex < mCloseIndex && mOpenIndex != -1) {
                result.onCheckResult(ACTIVITY_CLOSE, mCloseActivity, mOpenActivity);
            }
            reset();
        }
        private interface AnalysisResult {
            void onCheckResult(@TransitionStateType int type,
                    ActivityRecord close, ActivityRecord open);
        }
    }

    private void addTransitionRecord(int type, boolean open, WindowContainer target) {
        TransitionState record = mTmpOpenCloseRecord.get(type);
        if (record == null) {
            record =  new TransitionState();
            mTmpOpenCloseRecord.set(type, record);
        }
        record.addParticipant(target, open);
        mTmpAnalysisRecord.add(type);
    }

    private void clearRecord() {
        mTmpOpenCloseRecord.clear();
        mTmpAnalysisRecord.clear();
    }

    static class TransitionState<TYPE extends WindowContainer> {
        private final ArraySet<TYPE> mOpenParticipant = new ArraySet<>();
        private final ArraySet<TYPE> mCloseParticipant = new ArraySet<>();

        void addParticipant(TYPE target, boolean open) {
            final ArraySet<TYPE> participant = open
                    ? mOpenParticipant : mCloseParticipant;
            participant.add(target);
        }

        ArraySet<TYPE> getParticipant(boolean open) {
            return open ? mOpenParticipant : mCloseParticipant;
        }
    }

    SnapshotController(WindowManagerService wms) {
        mSnapshotPersistQueue = new SnapshotPersistQueue();
        mTaskSnapshotController = new TaskSnapshotController(wms, mSnapshotPersistQueue);
        mActivitySnapshotController = new ActivitySnapshotController(wms, mSnapshotPersistQueue);
    }

    void registerTransitionStateConsumer(@TransitionStateType int type,
            Consumer<TransitionState> consumer) {
        ArrayList<Consumer<TransitionState>> consumers = mTransitionStateConsumer.get(type);
        if (consumers == null) {
            consumers = new ArrayList<>();
            mTransitionStateConsumer.set(type, consumers);
        }
        if (!consumers.contains(consumer)) {
            consumers.add(consumer);
        }
        mActivatedType |= type;
    }

    void unregisterTransitionStateConsumer(int type, Consumer<TransitionState> consumer) {
        final ArrayList<Consumer<TransitionState>> consumers = mTransitionStateConsumer.get(type);
        if (consumers == null) {
            return;
        }
        consumers.remove(consumer);
        if (consumers.size() == 0) {
            mActivatedType &= ~type;
        }
    }

    private boolean hasTransitionStateConsumer(@TransitionStateType int type) {
        return (mActivatedType & type) != 0;
    }

    void systemReady() {
        mSnapshotPersistQueue.systemReady();
        mTaskSnapshotController.systemReady();
        mActivitySnapshotController.systemReady();
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
        if (!visible && hasTransitionStateConsumer(TASK_CLOSE)) {
            // close task transition
            addTransitionRecord(TASK_CLOSE, false /*open*/, appWindowToken.getTask());
            mActivitySnapshotController.preTransitionStart();
            notifyTransition(TASK_CLOSE);
            mActivitySnapshotController.postTransitionStart();
            clearRecord();
        }
    }

    // For legacy transition
    void onTransitionStarting(DisplayContent displayContent) {
        handleAppTransition(displayContent.mClosingApps, displayContent.mOpeningApps);
    }

    // For shell transition, adapt to legacy transition.
    void onTransitionReady(@WindowManager.TransitionType int type,
            ArraySet<WindowContainer> participants) {
        final boolean isTransitionOpen = isTransitionOpen(type);
        final boolean isTransitionClose = isTransitionClose(type);
        if (!isTransitionOpen && !isTransitionClose && type < TRANSIT_FIRST_CUSTOM
                || (mActivatedType == 0)) {
            return;
        }
        final ArraySet<ActivityRecord> openingApps = new ArraySet<>();
        final ArraySet<ActivityRecord> closingApps = new ArraySet<>();

        for (int i = participants.size() - 1; i >= 0; --i) {
            final ActivityRecord ar = participants.valueAt(i).asActivityRecord();
            if (ar == null || ar.getTask() == null) continue;
            if (ar.isVisibleRequested()) {
                openingApps.add(ar);
            } else {
                closingApps.add(ar);
            }
        }
        handleAppTransition(closingApps, openingApps);
    }

    private static boolean isTransitionOpen(int type) {
        return type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT;
    }
    private static boolean isTransitionClose(int type) {
        return type == TRANSIT_CLOSE || type == TRANSIT_TO_BACK;
    }

    @VisibleForTesting
    void handleAppTransition(ArraySet<ActivityRecord> closingApps,
            ArraySet<ActivityRecord> openApps) {
        if (mActivatedType == 0) {
            return;
        }
        analysisTransition(closingApps, openApps);
        mActivitySnapshotController.preTransitionStart();
        for (Integer transitionType : mTmpAnalysisRecord) {
            notifyTransition(transitionType);
        }
        mActivitySnapshotController.postTransitionStart();
        clearRecord();
    }

    private void notifyTransition(int transitionType) {
        final TransitionState record = mTmpOpenCloseRecord.get(transitionType);
        final ArrayList<Consumer<TransitionState>> consumers =
                mTransitionStateConsumer.get(transitionType);
        for (Consumer<TransitionState> consumer : consumers) {
            consumer.accept(record);
        }
    }

    private void analysisTransition(ArraySet<ActivityRecord> closingApps,
            ArraySet<ActivityRecord> openingApps) {
        getParticipantTasks(closingApps, mTmpCloseTasks, false /* isOpen */);
        getParticipantTasks(openingApps, mTmpOpenTasks, true /* isOpen */);
        if (DEBUG) {
            Slog.d(TAG, "AppSnapshotController#analysisTransition participants"
                    + " mTmpCloseTasks " + mTmpCloseTasks
                    + " mTmpOpenTasks " + mTmpOpenTasks);
        }
        for (int i = mTmpCloseTasks.size() - 1; i >= 0; i--) {
            final Task closeTask = mTmpCloseTasks.valueAt(i);
            if (mTmpOpenTasks.contains(closeTask)) {
                if (hasTransitionStateConsumer(ACTIVITY_OPEN)
                        || hasTransitionStateConsumer(ACTIVITY_CLOSE)) {
                    mActivityOrderCheck.analysisOrder(closingApps, openingApps, closeTask,
                            mResultHandler);
                }
            } else if (hasTransitionStateConsumer(TASK_CLOSE)) {
                // close task transition
                addTransitionRecord(TASK_CLOSE, false /*open*/, closeTask);
            }
        }
        if (hasTransitionStateConsumer(TASK_OPEN)) {
            for (int i = mTmpOpenTasks.size() - 1; i >= 0; i--) {
                final Task openTask = mTmpOpenTasks.valueAt(i);
                if (!mTmpCloseTasks.contains(openTask)) {
                    // this is open task transition
                    addTransitionRecord(TASK_OPEN, true /*open*/, openTask);
                }
            }
        }
        mTmpCloseTasks.clear();
        mTmpOpenTasks.clear();
    }

    private void getParticipantTasks(ArraySet<ActivityRecord> activityRecords, ArraySet<Task> tasks,
            boolean isOpen) {
        for (int i = activityRecords.size() - 1; i >= 0; i--) {
            final ActivityRecord activity = activityRecords.valueAt(i);
            final Task task = activity.getTask();
            if (task == null) continue;

            if (isOpen == activity.isVisibleRequested()) {
                tasks.add(task);
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        mTaskSnapshotController.dump(pw, prefix);
        mActivitySnapshotController.dump(pw, prefix);
    }
}
