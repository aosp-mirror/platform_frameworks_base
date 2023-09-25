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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Process;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.window.DisplayWindowPolicyController;

import java.io.PrintWriter;
import java.util.List;

class DisplayWindowPolicyControllerHelper {
    private static final String TAG = "DisplayWindowPolicyControllerHelper";

    private final DisplayContent mDisplayContent;

    /**
     * The policy controller of the windows that can be displayed on the virtual display.
     *
     * @see DisplayWindowPolicyController
     */
    @Nullable
    private DisplayWindowPolicyController mDisplayWindowPolicyController;

    /**
     * The top non-finishing activity of this display.
     */
    private ActivityRecord mTopRunningActivity = null;

    /**
     * All the uids of non-finishing activity on this display.
     * @see DisplayWindowPolicyController#onRunningAppsChanged(ArraySet)
     */
    private ArraySet<Integer> mRunningUid = new ArraySet<>();

    DisplayWindowPolicyControllerHelper(DisplayContent displayContent) {
        mDisplayContent = displayContent;
        mDisplayWindowPolicyController = mDisplayContent.mWmService.mDisplayManagerInternal
                .getDisplayWindowPolicyController(mDisplayContent.mDisplayId);
    }

    /**
     * Return {@code true} if there is DisplayWindowPolicyController.
     */
    public boolean hasController() {
        return mDisplayWindowPolicyController != null;
    }

    /**
     * @see DisplayWindowPolicyController#canContainActivities
     */
    public boolean canContainActivities(@NonNull List<ActivityInfo> activities,
            @WindowConfiguration.WindowingMode int windowingMode) {
        if (mDisplayWindowPolicyController == null) {
            for (int i = 0; i < activities.size(); ++i) {
                // Missing controller means that this display has no categories for activity launch
                // restriction.
                if (hasDisplayCategory(activities.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return mDisplayWindowPolicyController.canContainActivities(activities, windowingMode);
    }

    /**
     * @see DisplayWindowPolicyController#canActivityBeLaunched
     */
    public boolean canActivityBeLaunched(ActivityInfo activityInfo,
            Intent intent, @WindowConfiguration.WindowingMode int windowingMode,
            int launchingFromDisplayId, boolean isNewTask) {
        if (mDisplayWindowPolicyController == null) {
            // Missing controller means that this display has no categories for activity launch
            // restriction.
            return !hasDisplayCategory(activityInfo);
        }
        return mDisplayWindowPolicyController.canActivityBeLaunched(activityInfo, intent,
            windowingMode, launchingFromDisplayId, isNewTask);
    }

    private boolean hasDisplayCategory(ActivityInfo aInfo) {
        if (aInfo.requiredDisplayCategory != null) {
            Slog.d(TAG,
                    String.format("Checking activity launch with requiredDisplayCategory='%s' on"
                                    + " display %d, which doesn't have a matching category.",
                            aInfo.requiredDisplayCategory, mDisplayContent.mDisplayId));
            return true;
        }
        return false;
    }

    /**
     * @see DisplayWindowPolicyController#keepActivityOnWindowFlagsChanged(ActivityInfo, int, int)
     */
    boolean keepActivityOnWindowFlagsChanged(ActivityInfo aInfo, int flagChanges,
            int privateFlagChanges, int flagValues, int privateFlagValues) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }

        if (!mDisplayWindowPolicyController.isInterestedWindowFlags(
                flagChanges, privateFlagChanges)) {
            return true;
        }

        return mDisplayWindowPolicyController.keepActivityOnWindowFlagsChanged(
                aInfo, flagValues, privateFlagValues);
    }

    /** Update the top activity and the uids of non-finishing activity */
    void onRunningActivityChanged() {
        if (mDisplayWindowPolicyController == null) {
            return;
        }

        // Update top activity.
        ActivityRecord topActivity = mDisplayContent.getTopActivity(false /* includeFinishing */,
                true /* includeOverlays */);
        if (topActivity != mTopRunningActivity) {
            mTopRunningActivity = topActivity;
            if (topActivity == null) {
                mDisplayWindowPolicyController.onTopActivityChanged(null, Process.INVALID_UID,
                        UserHandle.USER_NULL);
            } else {
                mDisplayWindowPolicyController.onTopActivityChanged(
                        topActivity.info.getComponentName(), topActivity.info.applicationInfo.uid,
                        topActivity.mUserId);
            }
        }

        // Update running uid.
        final boolean[] notifyChanged = {false};
        ArraySet<Integer> runningUids = new ArraySet<>();
        mDisplayContent.forAllActivities((r) -> {
            if (!r.finishing) {
                notifyChanged[0] |= runningUids.add(r.getUid());
            }
        });

        // We need to compare the size because if it is the following case, we can't know the
        // existence of 3 in the forAllActivities() loop.
        // Old set: 1,2,3
        // New set: 1,2
        if (notifyChanged[0] || (mRunningUid.size() != runningUids.size())) {
            mRunningUid = runningUids;
            mDisplayWindowPolicyController.onRunningAppsChanged(runningUids);
        }
    }

    /**
     * @see DisplayWindowPolicyController#isWindowingModeSupported(int)
     */
    public final boolean isWindowingModeSupported(
            @WindowConfiguration.WindowingMode int windowingMode) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }
        return mDisplayWindowPolicyController.isWindowingModeSupported(windowingMode);
    }

    /**
     * @see DisplayWindowPolicyController#canShowTasksInHostDeviceRecents()
     */
    public final boolean canShowTasksInHostDeviceRecents() {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }
        return mDisplayWindowPolicyController.canShowTasksInHostDeviceRecents();
    }

    /**
     * @see DisplayWindowPolicyController#isEnteringPipAllowed(int)
     */
    public final boolean isEnteringPipAllowed(int uid) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }
        return mDisplayWindowPolicyController.isEnteringPipAllowed(uid);
    }

    /**
     * @see DisplayWindowPolicyController#getCustomHomeComponent
     */
    @Nullable
    public ComponentName getCustomHomeComponent() {
        if (mDisplayWindowPolicyController == null) {
            return null;
        }
        return mDisplayWindowPolicyController.getCustomHomeComponent();
    }

    void dump(String prefix, PrintWriter pw) {
        if (mDisplayWindowPolicyController != null) {
            pw.println();
            mDisplayWindowPolicyController.dump(prefix, pw);
        }
    }
}
