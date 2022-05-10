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
import android.content.pm.ActivityInfo;
import android.os.UserHandle;
import android.util.ArraySet;
import android.window.DisplayWindowPolicyController;

import java.io.PrintWriter;
import java.util.List;

class DisplayWindowPolicyControllerHelper {

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
     * @see DisplayWindowPolicyController#canContainActivities(List, int)
     */
    public boolean canContainActivities(@NonNull List<ActivityInfo> activities,
            @WindowConfiguration.WindowingMode int windowingMode) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }
        return mDisplayWindowPolicyController.canContainActivities(activities, windowingMode);
    }

    /**
     * @see DisplayWindowPolicyController#canActivityBeLaunched(ActivityInfo, int, int, boolean)
     */
    public boolean canActivityBeLaunched(ActivityInfo activityInfo,
            @WindowConfiguration.WindowingMode int windowingMode, int launchingFromDisplayId,
            boolean isNewTask) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }
        return mDisplayWindowPolicyController.canActivityBeLaunched(activityInfo, windowingMode,
            launchingFromDisplayId, isNewTask);
    }

    /**
     * @see DisplayWindowPolicyController#keepActivityOnWindowFlagsChanged(ActivityInfo, int, int)
     */
    boolean keepActivityOnWindowFlagsChanged(ActivityInfo aInfo, int flagChanges,
            int privateFlagChanges) {
        if (mDisplayWindowPolicyController == null) {
            return true;
        }

        if (!mDisplayWindowPolicyController.isInterestedWindowFlags(
                flagChanges, privateFlagChanges)) {
            return true;
        }

        return mDisplayWindowPolicyController.keepActivityOnWindowFlagsChanged(
                aInfo, flagChanges, privateFlagChanges);
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
            mDisplayWindowPolicyController.onTopActivityChanged(
                    topActivity == null ? null : topActivity.info.getComponentName(),
                    topActivity == null
                            ? UserHandle.USER_NULL : topActivity.info.applicationInfo.uid);
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

    void dump(String prefix, PrintWriter pw) {
        if (mDisplayWindowPolicyController != null) {
            pw.println();
            mDisplayWindowPolicyController.dump(prefix, pw);
        }
    }
}
