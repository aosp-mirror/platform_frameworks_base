/*
 * Copyright (C) 2024 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.window.TaskFragmentOrganizer.KEY_RESTORE_TASK_FRAGMENTS_INFO;
import static android.window.TaskFragmentOrganizer.KEY_RESTORE_TASK_FRAGMENT_PARENT_INFO;

import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentParentInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Helper class to back up and restore the TaskFragmentOrganizer state, in order to resume
 * organizing the TaskFragments if the app process is restarted.
 */
@SuppressWarnings("GuardedBy")
class BackupHelper {
    private static final String TAG = "BackupHelper";
    private static final boolean DEBUG = Build.isDebuggable();

    private static final String KEY_TASK_CONTAINERS = "KEY_TASK_CONTAINERS";
    @NonNull
    private final SplitController mController;
    @NonNull
    private final SplitPresenter mPresenter;
    @NonNull
    private final BackupIdler mBackupIdler = new BackupIdler();
    private boolean mBackupIdlerScheduled;
    private boolean mSaveEmbeddingState = false;

    private final List<ParcelableTaskContainerData> mParcelableTaskContainerDataList =
            new ArrayList<>();
    private final ArrayMap<IBinder, TaskFragmentInfo> mTaskFragmentInfos = new ArrayMap<>();
    private final SparseArray<TaskFragmentParentInfo> mTaskFragmentParentInfos =
            new SparseArray<>();

    BackupHelper(@NonNull SplitController splitController, @NonNull SplitPresenter splitPresenter,
            @NonNull Bundle savedState) {
        mController = splitController;
        mPresenter = splitPresenter;

        if (!savedState.isEmpty()) {
            restoreState(savedState);
        }
    }

    void setAutoSaveEmbeddingState(boolean saveEmbeddingState) {
        if (mSaveEmbeddingState == saveEmbeddingState) {
            return;
        }

        Log.i(TAG, "Set save embedding state: " + saveEmbeddingState);
        mSaveEmbeddingState = saveEmbeddingState;
        if (!mSaveEmbeddingState) {
            removeSavedState();
            return;
        }

        if (!hasPendingStateToRestore() && !mController.getTaskContainers().isEmpty()) {
            scheduleBackup();
        }
    }
    /**
     * Schedules a back-up request. It is no-op if there was a request scheduled and not yet
     * completed.
     */
    void scheduleBackup() {
        if (!mSaveEmbeddingState) {
            return;
        }

        if (!mBackupIdlerScheduled) {
            mBackupIdlerScheduled = true;
            Looper.getMainLooper().getQueue().addIdleHandler(mBackupIdler);
        }
    }

    final class BackupIdler implements MessageQueue.IdleHandler {
        @Override
        public boolean queueIdle() {
            synchronized (mController.mLock) {
                mBackupIdlerScheduled = false;
                saveState();
            }
            return false;
        }
    }

    private void saveState() {
        final List<TaskContainer> taskContainers = mController.getTaskContainers();
        if (taskContainers.isEmpty()) {
            Log.w(TAG, "No task-container to back up");
            return;
        }

        if (DEBUG) Log.d(TAG, "Start to back up " + taskContainers);
        final List<ParcelableTaskContainerData> parcelableTaskContainerDataList = new ArrayList<>(
                taskContainers.size());
        for (TaskContainer taskContainer : taskContainers) {
            parcelableTaskContainerDataList.add(taskContainer.getParcelableData());
        }
        final Bundle state = new Bundle();
        state.setClassLoader(ParcelableTaskContainerData.class.getClassLoader());
        state.putParcelableList(KEY_TASK_CONTAINERS, parcelableTaskContainerDataList);
        mController.setSavedState(state);
    }

    private void restoreState(@NonNull Bundle savedState) {
        if (savedState.isEmpty()) {
            return;
        }

        if (DEBUG) Log.d(TAG, "Start restoring saved-state");
        mParcelableTaskContainerDataList.addAll(savedState.getParcelableArrayList(
                KEY_TASK_CONTAINERS, ParcelableTaskContainerData.class));
        if (DEBUG) Log.d(TAG, "Retrieved tasks : " + mParcelableTaskContainerDataList.size());
        if (mParcelableTaskContainerDataList.isEmpty()) {
            return;
        }

        final List<TaskFragmentInfo> infos = savedState.getParcelableArrayList(
                KEY_RESTORE_TASK_FRAGMENTS_INFO, TaskFragmentInfo.class);
        for (TaskFragmentInfo info : infos) {
            mTaskFragmentInfos.put(info.getFragmentToken(), info);
            mPresenter.updateTaskFragmentInfo(info);
        }

        final List<TaskFragmentParentInfo> parentInfos = savedState.getParcelableArrayList(
                KEY_RESTORE_TASK_FRAGMENT_PARENT_INFO,
                TaskFragmentParentInfo.class);
        for (TaskFragmentParentInfo info : parentInfos) {
            if (DEBUG) Log.d(TAG, "Retrieved: " + info);
            mTaskFragmentParentInfos.put(info.getTaskId(), info);
        }

        if (DEBUG) {
            Log.d(TAG, "Retrieved task-fragment info: " + infos.size() + ", task info: "
                    + parentInfos.size());
        }
    }

    void abortTaskContainerRebuilding(@NonNull WindowContainerTransaction wct) {
        // Clean-up the legacy states in the system
        for (int i = mTaskFragmentInfos.size() - 1; i >= 0; i--) {
            final TaskFragmentInfo info = mTaskFragmentInfos.valueAt(i);
            mPresenter.deleteTaskFragment(wct, info.getFragmentToken());
        }
        removeSavedState();
    }

    private void removeSavedState() {
        mPresenter.setSavedState(new Bundle());
        mParcelableTaskContainerDataList.clear();
        mTaskFragmentInfos.clear();
        mTaskFragmentParentInfos.clear();
    }

    boolean hasPendingStateToRestore() {
        return !mParcelableTaskContainerDataList.isEmpty();
    }

    /**
     * Returns {@code true} if any of the {@link TaskContainer} is restored.
     * Otherwise, returns {@code false}.
     */
    boolean rebuildTaskContainers(@NonNull WindowContainerTransaction wct,
            @NonNull Set<EmbeddingRule> rules) {
        if (mParcelableTaskContainerDataList.isEmpty()) {
            return false;
        }

        if (mTaskFragmentParentInfos.size() == 0) {
            // No Task left in the WM hierarchy, remove the states and no need to restore.
            if (DEBUG) Log.d(TAG, "Remove save states due to no task to restore.");
            removeSavedState();
            return false;
        }

        final ArrayList<Integer> taskIdsInSystem = new ArrayList<>();
        for (int i = mTaskFragmentParentInfos.size() - 1; i >= 0; --i) {
            final TaskFragmentParentInfo parentInfo = mTaskFragmentParentInfos.valueAt(i);
            taskIdsInSystem.add(parentInfo.getTaskId());
        }

        if (DEBUG) Log.d(TAG, "Rebuilding TaskContainers.");
        final ArrayMap<String, EmbeddingRule> embeddingRuleMap = new ArrayMap<>();
        for (EmbeddingRule rule : rules) {
            embeddingRuleMap.put(rule.getTag(), rule);
            if (DEBUG) {
                Log.d(TAG, "Tag: " + rule.getTag() + " rule: " + rule);
            }
        }

        boolean restoredAny = false;
        for (int i = mParcelableTaskContainerDataList.size() - 1; i >= 0; i--) {
            final ParcelableTaskContainerData parcelableTaskContainerData =
                    mParcelableTaskContainerDataList.get(i);
            final List<String> tags = parcelableTaskContainerData.getSplitRuleTags();
            if (!embeddingRuleMap.containsAll(tags)) {
                // has unknown tag, unable to restore.
                if (DEBUG) {
                    Log.d(TAG, "Rebuilding TaskContainer abort! Unknown Tag. Task#"
                            + parcelableTaskContainerData.mTaskId + ", tags = " + tags);
                }
                continue;
            }

            mParcelableTaskContainerDataList.remove(parcelableTaskContainerData);
            if (!taskIdsInSystem.contains(parcelableTaskContainerData.mTaskId)) {
                if (DEBUG) {
                    Log.d(TAG, "Rebuilding TaskContainer abort! Not existed. Task#"
                            + parcelableTaskContainerData.mTaskId);
                }
                continue;
            }

            final TaskContainer taskContainer = new TaskContainer(parcelableTaskContainerData,
                    mController, mTaskFragmentInfos);
            if (DEBUG) Log.d(TAG, "Created TaskContainer " + taskContainer.getTaskId());
            mController.addTaskContainer(taskContainer.getTaskId(), taskContainer);

            for (ParcelableSplitContainerData splitData :
                    parcelableTaskContainerData.getParcelableSplitContainerDataList()) {
                final SplitRule rule = (SplitRule) embeddingRuleMap.get(splitData.mSplitRuleTag);
                assert rule != null;
                if (mController.getContainer(splitData.getPrimaryContainerToken()) != null
                        && mController.getContainer(splitData.getSecondaryContainerToken())
                        != null) {
                    taskContainer.addSplitContainer(
                            new SplitContainer(splitData, mController, rule));
                }
            }

            mController.onTaskFragmentParentRestored(wct, taskContainer.getTaskId(),
                    mTaskFragmentParentInfos.get(taskContainer.getTaskId()));
            mTaskFragmentParentInfos.remove(taskContainer.getTaskId());
            restoredAny = true;
        }

        if (mParcelableTaskContainerDataList.isEmpty()) {
            mTaskFragmentParentInfos.clear();
            mTaskFragmentInfos.clear();
        }
        return restoredAny;
    }
}