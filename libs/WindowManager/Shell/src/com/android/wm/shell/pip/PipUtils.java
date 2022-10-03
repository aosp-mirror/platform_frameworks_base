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

package com.android.wm.shell.pip;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.window.TaskSnapshot;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.List;
import java.util.Objects;

/** A class that includes convenience methods. */
public class PipUtils {
    private static final String TAG = "PipUtils";

    // Minimum difference between two floats (e.g. aspect ratios) to consider them not equal.
    private static final double EPSILON = 1e-7;

    /**
     * @return the ComponentName and user id of the top non-SystemUI activity in the pinned stack.
     * The component name may be null if no such activity exists.
     */
    public static Pair<ComponentName, Integer> getTopPipActivity(Context context) {
        try {
            final String sysUiPackageName = context.getPackageName();
            final RootTaskInfo pinnedTaskInfo = ActivityTaskManager.getService().getRootTaskInfo(
                    WINDOWING_MODE_PINNED, ACTIVITY_TYPE_UNDEFINED);
            if (pinnedTaskInfo != null && pinnedTaskInfo.childTaskIds != null
                    && pinnedTaskInfo.childTaskIds.length > 0) {
                for (int i = pinnedTaskInfo.childTaskNames.length - 1; i >= 0; i--) {
                    ComponentName cn = ComponentName.unflattenFromString(
                            pinnedTaskInfo.childTaskNames[i]);
                    if (cn != null && !cn.getPackageName().equals(sysUiPackageName)) {
                        return new Pair<>(cn, pinnedTaskInfo.childTaskUserIds[i]);
                    }
                }
            }
        } catch (RemoteException e) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Unable to get pinned stack.", TAG);
        }
        return new Pair<>(null, 0);
    }

    /**
     * @return true if the aspect ratios differ
     */
    public static boolean aspectRatioChanged(float aspectRatio1, float aspectRatio2) {
        return Math.abs(aspectRatio1 - aspectRatio2) > EPSILON;
    }

    /**
     * Checks whether title, description and intent match.
     * Comparing icons would be good, but using equals causes false negatives
     */
    public static boolean remoteActionsMatch(RemoteAction action1, RemoteAction action2) {
        if (action1 == action2) return true;
        if (action1 == null || action2 == null) return false;
        return action1.isEnabled() == action2.isEnabled()
                && action1.shouldShowIcon() == action2.shouldShowIcon()
                && Objects.equals(action1.getTitle(), action2.getTitle())
                && Objects.equals(action1.getContentDescription(), action2.getContentDescription())
                && Objects.equals(action1.getActionIntent(), action2.getActionIntent());
    }

    /**
     * Returns true if the actions in the lists match each other according to {@link
     * PipUtils#remoteActionsMatch(RemoteAction, RemoteAction)}, including their position.
     */
    public static boolean remoteActionsChanged(List<RemoteAction> list1, List<RemoteAction> list2) {
        if (list1 == null && list2 == null) {
            return false;
        }
        if (list1 == null || list2 == null) {
            return true;
        }
        if (list1.size() != list2.size()) {
            return true;
        }
        for (int i = 0; i < list1.size(); i++) {
            if (!remoteActionsMatch(list1.get(i), list2.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** @return {@link TaskSnapshot} for a given task id. */
    @Nullable
    public static TaskSnapshot getTaskSnapshot(int taskId, boolean isLowResolution) {
        if (taskId <= 0) return null;
        try {
            return ActivityTaskManager.getService().getTaskSnapshot(
                    taskId, isLowResolution, false /* takeSnapshotIfNeeded */);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get task snapshot, taskId=" + taskId, e);
            return null;
        }
    }
}
