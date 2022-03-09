/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.apppairs;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.app.ActivityManager;
import android.util.Slog;
import android.util.SparseArray;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayImeController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;

import java.io.PrintWriter;

/**
 * Class manages app-pairs multitasking mode and implements the main interface {@link AppPairs}.
 */
public class AppPairsController {
    private static final String TAG = AppPairsController.class.getSimpleName();

    private final ShellTaskOrganizer mTaskOrganizer;
    private final SyncTransactionQueue mSyncQueue;
    private final ShellExecutor mMainExecutor;
    private final AppPairsImpl mImpl = new AppPairsImpl();

    private AppPairsPool mPairsPool;
    // Active app-pairs mapped by root task id key.
    private final SparseArray<AppPair> mActiveAppPairs = new SparseArray<>();
    private final DisplayController mDisplayController;
    private final DisplayImeController mDisplayImeController;
    private final DisplayInsetsController mDisplayInsetsController;

    public AppPairsController(ShellTaskOrganizer organizer, SyncTransactionQueue syncQueue,
            DisplayController displayController, ShellExecutor mainExecutor,
            DisplayImeController displayImeController,
            DisplayInsetsController displayInsetsController) {
        mTaskOrganizer = organizer;
        mSyncQueue = syncQueue;
        mDisplayController = displayController;
        mDisplayImeController = displayImeController;
        mDisplayInsetsController = displayInsetsController;
        mMainExecutor = mainExecutor;
    }

    public AppPairs asAppPairs() {
        return mImpl;
    }

    public void onOrganizerRegistered() {
        if (mPairsPool == null) {
            setPairsPool(new AppPairsPool(this));
        }
    }

    @VisibleForTesting
    public void setPairsPool(AppPairsPool pool) {
        mPairsPool = pool;
    }

    public boolean pair(int taskId1, int taskId2) {
        final ActivityManager.RunningTaskInfo task1 = mTaskOrganizer.getRunningTaskInfo(taskId1);
        final ActivityManager.RunningTaskInfo task2 = mTaskOrganizer.getRunningTaskInfo(taskId2);
        if (task1 == null || task2 == null) {
            return false;
        }
        return pair(task1, task2);
    }

    public boolean pair(ActivityManager.RunningTaskInfo task1,
            ActivityManager.RunningTaskInfo task2) {
        return pairInner(task1, task2) != null;
    }

    @VisibleForTesting
    public AppPair pairInner(
            @NonNull ActivityManager.RunningTaskInfo task1,
            @NonNull ActivityManager.RunningTaskInfo task2) {
        final AppPair pair = mPairsPool.acquire();
        if (!pair.pair(task1, task2)) {
            mPairsPool.release(pair);
            return null;
        }

        mActiveAppPairs.put(pair.getRootTaskId(), pair);
        return pair;
    }

    public void unpair(int taskId) {
        unpair(taskId, true /* releaseToPool */);
    }

    public void unpair(int taskId, boolean releaseToPool) {
        AppPair pair = mActiveAppPairs.get(taskId);
        if (pair == null) {
            for (int i = mActiveAppPairs.size() - 1; i >= 0; --i) {
                final AppPair candidate = mActiveAppPairs.valueAt(i);
                if (candidate.contains(taskId)) {
                    pair = candidate;
                    break;
                }
            }
        }
        if (pair == null) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "taskId %d isn't isn't in an app-pair.", taskId);
            return;
        }

        ProtoLog.v(WM_SHELL_TASK_ORG, "unpair taskId=%d pair=%s", taskId, pair);
        mActiveAppPairs.remove(pair.getRootTaskId());
        pair.unpair();
        if (releaseToPool) {
            mPairsPool.release(pair);
        }
    }

    ShellTaskOrganizer getTaskOrganizer() {
        return mTaskOrganizer;
    }

    SyncTransactionQueue getSyncTransactionQueue() {
        return mSyncQueue;
    }

    DisplayController getDisplayController() {
        return mDisplayController;
    }

    DisplayImeController getDisplayImeController() {
        return mDisplayImeController;
    }

    DisplayInsetsController getDisplayInsetsController() {
        return mDisplayInsetsController;
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        final String childPrefix = innerPrefix + "  ";
        pw.println(prefix + this);

        for (int i = mActiveAppPairs.size() - 1; i >= 0; --i) {
            mActiveAppPairs.valueAt(i).dump(pw, childPrefix);
        }

        if (mPairsPool != null) {
            mPairsPool.dump(pw, prefix);
        }
    }

    @Override
    public String toString() {
        return TAG + "#" + mActiveAppPairs.size();
    }

    private class AppPairsImpl implements AppPairs {
        @Override
        public boolean pair(int task1, int task2) {
            boolean[] result = new boolean[1];
            try {
                mMainExecutor.executeBlocking(() -> {
                    result[0] = AppPairsController.this.pair(task1, task2);
                });
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to pair tasks: " + task1 + ", " + task2);
            }
            return result[0];
        }

        @Override
        public boolean pair(ActivityManager.RunningTaskInfo task1,
                ActivityManager.RunningTaskInfo task2) {
            boolean[] result = new boolean[1];
            try {
                mMainExecutor.executeBlocking(() -> {
                    result[0] = AppPairsController.this.pair(task1, task2);
                });
            } catch (InterruptedException e) {
                Slog.e(TAG, "Failed to pair tasks: " + task1 + ", " + task2);
            }
            return result[0];
        }

        @Override
        public void unpair(int taskId) {
            mMainExecutor.execute(() -> {
                AppPairsController.this.unpair(taskId);
            });
        }
    }
}
