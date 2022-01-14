/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.NonNull;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.window.DisplayWindowPolicyController;

import java.util.List;


/**
 * A controller to control the policies of the windows that can be displayed on the virtual display.
 */
class GenericWindowPolicyController extends DisplayWindowPolicyController {

    private static final String TAG = "VirtualDeviceManager";

    /**
     * If required, allow the secure activity to display on remote device since
     * {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE = 201712607L;
    @NonNull private final ArraySet<UserHandle> mAllowedUsers;

    @NonNull final ArraySet<Integer> mRunningUids = new ArraySet<>();

    GenericWindowPolicyController(int windowFlags, int systemWindowFlags,
            @NonNull ArraySet<UserHandle> allowedUsers) {
        mAllowedUsers = allowedUsers;
        setInterestedWindowFlags(windowFlags, systemWindowFlags);
    }

    @Override
    public boolean canContainActivities(@NonNull List<ActivityInfo> activities) {
        // Can't display all the activities if any of them don't want to be displayed.
        final int activityCount = activities.size();
        for (int i = 0; i < activityCount; i++) {
            final ActivityInfo aInfo = activities.get(i);
            if (!canContainActivity(aInfo, /* windowFlags= */ 0, /* systemWindowFlags= */ 0)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        return canContainActivity(activityInfo, windowFlags, systemWindowFlags);
    }

    @Override
    public void onTopActivityChanged(ComponentName topActivity, int uid) {

    }

    @Override
    public void onRunningAppsChanged(int[] runningUids) {
        mRunningUids.clear();
        for (int i = 0; i < runningUids.length; i++) {
            mRunningUids.add(runningUids[i]);
        }
    }

    /**
     * Returns true if an app with the given UID has an activity running on the virtual display for
     * this controller.
     */
    boolean containsUid(int uid) {
        return mRunningUids.contains(uid);
    }

    private boolean canContainActivity(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        if ((activityInfo.flags & FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES) == 0) {
            return false;
        }
        final UserHandle activityUser =
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid);
        if (!mAllowedUsers.contains(activityUser)) {
            Slog.d(TAG, "Virtual device activity not allowed from user " + activityUser);
            return false;
        }
        if (!CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE,
                activityInfo.packageName, activityUser)) {
            // TODO(b/201712607): Add checks for the apps that use SurfaceView#setSecure.
            if ((windowFlags & FLAG_SECURE) != 0) {
                return false;
            }
            if ((systemWindowFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                return false;
            }
        }
        return true;
    }
}
