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

package com.android.server.app;

import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityTaskManager;
import android.content.ComponentName;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.LruCache;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.List;

final class GameTaskInfoProvider {
    private static final String TAG = "GameTaskInfoProvider";
    private static final int TASK_INFO_CACHE_MAX_SIZE = 50;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final LruCache<Integer, GameTaskInfo> mGameTaskInfoCache = new LruCache<>(
            TASK_INFO_CACHE_MAX_SIZE);

    private final UserHandle mUserHandle;
    private final IActivityTaskManager mActivityTaskManager;
    private final GameClassifier mGameClassifier;

    GameTaskInfoProvider(@NonNull UserHandle userHandle,
            @NonNull IActivityTaskManager activityTaskManager,
            @NonNull GameClassifier gameClassifier) {
        mUserHandle = userHandle;
        mActivityTaskManager = activityTaskManager;
        mGameClassifier = gameClassifier;
    }

    @Nullable
    GameTaskInfo get(int taskId) {
        synchronized (mLock) {
            final GameTaskInfo cachedTaskInfo = mGameTaskInfoCache.get(taskId);
            if (cachedTaskInfo != null) {
                return cachedTaskInfo;
            }
        }

        final RunningTaskInfo runningTaskInfo = getRunningTaskInfo(taskId);
        if (runningTaskInfo == null || runningTaskInfo.baseActivity == null) {
            return null;
        }

        return generateGameInfo(taskId, runningTaskInfo.baseActivity);
    }

    GameTaskInfo get(int taskId, @NonNull ComponentName componentName) {
        synchronized (mLock) {
            final GameTaskInfo cachedTaskInfo = mGameTaskInfoCache.get(taskId);
            if (cachedTaskInfo != null) {
                if (cachedTaskInfo.mComponentName.equals(componentName)) {
                    Slog.w(TAG, "Found cached task info for taskId " + taskId
                            + " but cached component name " + cachedTaskInfo.mComponentName
                            + " does not match " + componentName);
                } else {
                    return cachedTaskInfo;
                }
            }
        }

        return generateGameInfo(taskId, componentName);
    }

    @Nullable
    RunningTaskInfo getRunningTaskInfo(int taskId) {
        List<RunningTaskInfo> runningTaskInfos;
        try {
            runningTaskInfos = mActivityTaskManager.getTasks(
                    /* maxNum= */ Integer.MAX_VALUE,
                    /* filterOnlyVisibleRecents= */ false,
                    /* keepIntentExtra= */ false,
                    INVALID_DISPLAY);
        } catch (RemoteException ex) {
            Slog.w(TAG, "Failed to fetch running tasks");
            return null;
        }

        for (RunningTaskInfo taskInfo : runningTaskInfos) {
            if (taskInfo.taskId == taskId) {
                return taskInfo;
            }
        }

        return null;
    }

    private GameTaskInfo generateGameInfo(int taskId, @NonNull ComponentName componentName) {
        final GameTaskInfo gameTaskInfo = new GameTaskInfo(taskId,
                mGameClassifier.isGame(componentName.getPackageName(), mUserHandle), componentName);

        synchronized (mLock) {
            mGameTaskInfoCache.put(taskId, gameTaskInfo);
        }

        return gameTaskInfo;
    }
}
