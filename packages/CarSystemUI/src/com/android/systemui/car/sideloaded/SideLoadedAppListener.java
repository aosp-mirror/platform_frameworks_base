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

package com.android.systemui.car.sideloaded;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.IActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.hardware.display.DisplayManager;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;

import java.util.List;

import javax.inject.Inject;

/**
 * A TaskStackListener to detect when an unsafe app is launched/foregrounded.
 */
public class SideLoadedAppListener extends TaskStackListener {
    private static final String TAG = SideLoadedAppListener.class.getSimpleName();

    private IActivityTaskManager mActivityTaskManager;
    private DisplayManager mDisplayManager;
    private SideLoadedAppDetector mSideLoadedAppDetector;
    private SideLoadedAppStateController mSideLoadedAppStateController;

    @Inject
    SideLoadedAppListener(SideLoadedAppDetector sideLoadedAppDetector,
            IActivityTaskManager activityTaskManager,
            DisplayManager displayManager,
            SideLoadedAppStateController sideLoadedAppStateController) {
        mSideLoadedAppDetector = sideLoadedAppDetector;
        mActivityTaskManager = activityTaskManager;
        mDisplayManager = displayManager;
        mSideLoadedAppStateController = sideLoadedAppStateController;
    }

    @Override
    public void onTaskCreated(int taskId, ComponentName componentName) throws RemoteException {
        super.onTaskCreated(taskId, componentName);

        List<StackInfo> stackInfoList = mActivityTaskManager.getAllStackInfos();
        ActivityManager.StackInfo stackInfo = getStackInfo(stackInfoList, taskId);
        if (stackInfo == null) {
            Log.e(TAG, "Stack info was not available for taskId: " + taskId);
            return;
        }

        if (!mSideLoadedAppDetector.isSafe(stackInfo)) {
            Display display = mDisplayManager.getDisplay(stackInfo.displayId);
            mSideLoadedAppStateController.onUnsafeTaskCreatedOnDisplay(display);
        }
    }

    @Override
    public void onTaskStackChanged() throws RemoteException {
        super.onTaskStackChanged();

        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            // Note that the stackInfoList is ordered by recency.
            List<StackInfo> stackInfoList =
                    mActivityTaskManager.getAllStackInfosOnDisplay(display.getDisplayId());

            if (stackInfoList == null) {
                continue;
            }
            StackInfo stackInfo = getTopVisibleStackInfo(stackInfoList);
            if (stackInfo == null) {
                continue;
            }
            if (mSideLoadedAppDetector.isSafe(stackInfo)) {
                mSideLoadedAppStateController.onSafeTaskDisplayedOnDisplay(display);
            } else {
                mSideLoadedAppStateController.onUnsafeTaskDisplayedOnDisplay(display);
            }
        }
    }

    /**
     * Returns stack info for a given taskId.
     */
    private ActivityManager.StackInfo getStackInfo(
            List<ActivityManager.StackInfo> stackInfoList, int taskId) {
        if (stackInfoList == null) {
            return null;
        }
        for (ActivityManager.StackInfo stackInfo : stackInfoList) {
            if (stackInfo.taskIds == null) {
                continue;
            }
            for (int stackTaskId : stackInfo.taskIds) {
                if (taskId == stackTaskId) {
                    return stackInfo;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first visible stackInfo.
     */
    private ActivityManager.StackInfo getTopVisibleStackInfo(
            List<ActivityManager.StackInfo> stackInfoList) {
        for (ActivityManager.StackInfo stackInfo : stackInfoList) {
            if (stackInfo.visible) {
                return stackInfo;
            }
        }
        return null;
    }
}
