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

    public int requestPriority(int pid, int tid, int prio) {
        //Log.i(TAG, "requestPriority(pid=" + pid + ", tid=" + tid + ", prio=" + prio + ")");

        // Verify that the caller uid is permitted, priority is in range,
        // and that the callback thread specified by app belongs to the app that
        // called mediaserver or audioserver.
        // Once we've verified that the caller uid is permitted, we can trust the pid but
        // we can't trust the tid.  No need to explicitly check for pid == 0 || tid == 0,
        // since if not the case then the getThreadGroupLeader() test will also fail.
        if (!isPermittedCallingUid() || prio < PRIORITY_MIN ||
                prio > PRIORITY_MAX || Process.getThreadGroupLeader(tid) != pid) {
            return PackageManager.PERMISSION_DENIED;
        }
        try {
            // make good use of our CAP_SYS_NICE capability
            Process.setThreadGroup(tid, Binder.getCallingPid() == pid ?
                    Process.THREAD_GROUP_AUDIO_SYS : Process.THREAD_GROUP_AUDIO_APP);
            // must be in this order or it fails the schedulability constraint
            Process.setThreadScheduler(tid, Process.SCHED_FIFO, prio);
        } catch (RuntimeException e) {
            return PackageManager.PERMISSION_DENIED;
        }
        return PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPermittedCallingUid() {
        final int callingUid = Binder.getCallingUid();
        switch (callingUid) {
        case Process.AUDIOSERVER_UID: // fastcapture, fastmixer
        case Process.CAMERASERVER_UID: // camera high frame rate recording
            return true;
        default:
            return false;
        }
    }
}
