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

import android.app.ActivityTaskManager.RootTaskInfo;
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

        List<RootTaskInfo> taskInfoList = mActivityTaskManager.getAllRootTaskInfos();
        RootTaskInfo taskInfo = getStackInfo(taskInfoList, taskId);
        if (taskInfo == null) {
            Log.e(TAG, "Stack info was not available for taskId: " + taskId);
            return;
        }

        if (!mSideLoadedAppDetector.isSafe(taskInfo)) {
            Display display = mDisplayManager.getDisplay(taskInfo.displayId);
            mSideLoadedAppStateController.onUnsafeTaskCreatedOnDisplay(display);
        }
    }

    @Override
    public void onTaskStackChanged() throws RemoteException {
        super.onTaskStackChanged();

        Display[] displays = mDisplayManager.getDisplays();
        for (Display display : displays) {
            // Note that the taskInfoList is ordered by recency.
            List<RootTaskInfo> taskInfoList =
                    mActivityTaskManager.getAllRootTaskInfosOnDisplay(display.getDisplayId());

            if (taskInfoList == null) {
                continue;
            }
            RootTaskInfo taskInfo = getTopVisibleStackInfo(taskInfoList);
            if (taskInfo == null) {
                continue;
            }
            if (mSideLoadedAppDetector.isSafe(taskInfo)) {
                mSideLoadedAppStateController.onSafeTaskDisplayedOnDisplay(display);
            } else {
                mSideLoadedAppStateController.onUnsafeTaskDisplayedOnDisplay(display);
            }
        }
    }

    /**
     * Returns stack info for a given taskId.
     */
    private RootTaskInfo getStackInfo(List<RootTaskInfo> taskInfoList, int taskId) {
        if (taskInfoList == null) {
            return null;
        }
        for (RootTaskInfo taskInfo : taskInfoList) {
            if (taskInfo.childTaskIds == null) {
                continue;
            }
            for (int taskTaskId : taskInfo.childTaskIds) {
                if (taskId == taskTaskId) {
                    return taskInfo;
                }
            }
        }
        return null;
    }

    /**
     * Returns the first visible stackInfo.
     */
    private RootTaskInfo getTopVisibleStackInfo(List<RootTaskInfo> taskInfoList) {
        for (RootTaskInfo taskInfo : taskInfoList) {
            if (taskInfo.visible) {
                return taskInfo;
            }
        }
        return null;
    }
}
