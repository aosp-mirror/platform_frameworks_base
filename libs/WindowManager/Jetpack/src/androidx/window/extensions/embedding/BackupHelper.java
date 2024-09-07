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

import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

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
    private final BackupIdler mBackupIdler = new BackupIdler();
    private boolean mBackupIdlerScheduled;

    BackupHelper(@NonNull SplitController splitController, @NonNull Bundle savedState) {
        mController = splitController;

        if (!savedState.isEmpty()) {
            restoreState(savedState);
        }
    }

    /**
     * Schedules a back-up request. It is no-op if there was a request scheduled and not yet
     * completed.
     */
    void scheduleBackup() {
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
                startBackup();
            }
            return false;
        }
    }

    private void startBackup() {
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

        final List<ParcelableTaskContainerData> parcelableTaskContainerDataList =
                savedState.getParcelableArrayList(KEY_TASK_CONTAINERS,
                        ParcelableTaskContainerData.class);
        for (ParcelableTaskContainerData data : parcelableTaskContainerDataList) {
            final TaskContainer taskContainer = new TaskContainer(data, mController);
            if (DEBUG) Log.d(TAG, "Restoring task " + taskContainer.getTaskId());
            // TODO(b/289875940): implement the TaskContainer restoration.
        }
    }
}
