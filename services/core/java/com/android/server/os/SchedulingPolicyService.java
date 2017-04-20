/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.os;

import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.ISchedulingPolicyService;
import android.os.Process;
import android.util.Log;

/**
 * The implementation of the scheduling policy service interface.
 *
 * @hide
 */
public class SchedulingPolicyService extends ISchedulingPolicyService.Stub {

    private static final String TAG = "SchedulingPolicyService";

    // Minimum and maximum values allowed for requestPriority parameter prio
    private static final int PRIORITY_MIN = 1;
    private static final int PRIORITY_MAX = 3;

    public SchedulingPolicyService() {
    }

    // TODO(b/35196900) We should pass the period in time units, rather
    // than a fixed priority number.
    public int requestPriority(int pid, int tid, int prio, boolean isForApp) {
        //Log.i(TAG, "requestPriority(pid=" + pid + ", tid=" + tid + ", prio=" + prio + ")");

        // Verify that the caller uid is permitted, priority is in range,
        // and that the callback thread specified by app belongs to the app that
        // called mediaserver or audioserver.
        // Once we've verified that the caller uid is permitted, we can trust the pid but
        // we can't trust the tid.  No need to explicitly check for pid == 0 || tid == 0,
        // since if not the case then the getThreadGroupLeader() test will also fail.
        if (!isPermitted() || prio < PRIORITY_MIN ||
                prio > PRIORITY_MAX || Process.getThreadGroupLeader(tid) != pid) {
           return PackageManager.PERMISSION_DENIED;
        }
        if (Binder.getCallingUid() != Process.BLUETOOTH_UID) {
            try {
                // make good use of our CAP_SYS_NICE capability
                Process.setThreadGroup(tid, !isForApp ?
                  Process.THREAD_GROUP_AUDIO_SYS : Process.THREAD_GROUP_RT_APP);
            } catch (RuntimeException e) {
                Log.e(TAG, "Failed setThreadGroup: " + e);
                return PackageManager.PERMISSION_DENIED;
           }
        }
        try {
            // must be in this order or it fails the schedulability constraint
            Process.setThreadScheduler(tid, Process.SCHED_FIFO | Process.SCHED_RESET_ON_FORK,
                                       prio);
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed setThreadScheduler: " + e);
            return PackageManager.PERMISSION_DENIED;
        }
        return PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPermitted() {
        // schedulerservice hidl
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        switch (Binder.getCallingUid()) {
        case Process.AUDIOSERVER_UID: // fastcapture, fastmixer
        case Process.CAMERASERVER_UID: // camera high frame rate recording
        case Process.BLUETOOTH_UID: // Bluetooth audio playback
            return true;
        default:
            return false;
        }
    }
}
