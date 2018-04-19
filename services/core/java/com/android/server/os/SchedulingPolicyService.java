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
import android.os.IBinder;
import android.os.ISchedulingPolicyService;
import android.os.Process;
import android.os.RemoteException;
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

    private static final String[] MEDIA_PROCESS_NAMES = new String[] {
            "media.codec", // vendor/bin/hw/android.hardware.media.omx@1.0-service
    };
    private final IBinder.DeathRecipient mDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            requestCpusetBoost(false /*enable*/, null /*client*/);
        }
    };
    // Current process that received a cpuset boost
    private int mBoostedPid = -1;
    // Current client registered to the death recipient
    private IBinder mClient;

    public SchedulingPolicyService() {
        // system_server (our host) could have crashed before. The app may not survive
        // it, but mediaserver/media.codec could have, and mediaserver probably tried
        // to disable the boost while we were dead.
        // We do a restore of media.codec to default cpuset upon service restart to
        // catch this case. We can't leave media.codec in boosted state, because we've
        // lost the death recipient of mClient from mediaserver after the restart,
        // if mediaserver dies in the future we won't have a notification to reset.
        // (Note that if mediaserver thinks we're in boosted state before the crash,
        // the state could go out of sync temporarily until mediaserver enables/disable
        // boost next time, but this won't be a big issue.)
        int[] nativePids = Process.getPidsForCommands(MEDIA_PROCESS_NAMES);
        if (nativePids != null && nativePids.length == 1) {
            mBoostedPid = nativePids[0];
            disableCpusetBoost(nativePids[0]);
        }
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

    // Request to move media.codec process between SP_FOREGROUND and SP_TOP_APP.
    public int requestCpusetBoost(boolean enable, IBinder client) {
        if (!isPermitted()) {
            return PackageManager.PERMISSION_DENIED;
        }

        int[] nativePids = Process.getPidsForCommands(MEDIA_PROCESS_NAMES);
        if (nativePids == null || nativePids.length != 1) {
            Log.e(TAG, "requestCpusetBoost: can't find media.codec process");
            return PackageManager.PERMISSION_DENIED;
        }

        synchronized (mDeathRecipient) {
            if (enable) {
                return enableCpusetBoost(nativePids[0], client);
            } else {
                return disableCpusetBoost(nativePids[0]);
            }
        }
    }

    private int enableCpusetBoost(int pid, IBinder client) {
        if (mBoostedPid == pid) {
            return PackageManager.PERMISSION_GRANTED;
        }

        // The mediacodec process has changed, clean up the old pid and
        // client before we boost the new process, so that the state
        // is left clean if things go wrong.
        mBoostedPid = -1;
        if (mClient != null) {
            try {
                mClient.unlinkToDeath(mDeathRecipient, 0);
            } catch (Exception e) {
            } finally {
                mClient = null;
            }
        }

        try {
            client.linkToDeath(mDeathRecipient, 0);

            Log.i(TAG, "Moving " + pid + " to group " + Process.THREAD_GROUP_TOP_APP);
            Process.setProcessGroup(pid, Process.THREAD_GROUP_TOP_APP);

            mBoostedPid = pid;
            mClient = client;

            return PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            Log.e(TAG, "Failed enableCpusetBoost: " + e);
            try {
                // unlink if things go wrong and don't crash.
                client.unlinkToDeath(mDeathRecipient, 0);
            } catch (Exception e1) {}
        }

        return PackageManager.PERMISSION_DENIED;
    }

    private int disableCpusetBoost(int pid) {
        int boostedPid = mBoostedPid;

        // Clean up states first.
        mBoostedPid = -1;
        if (mClient != null) {
            try {
                mClient.unlinkToDeath(mDeathRecipient, 0);
            } catch (Exception e) {
            } finally {
                mClient = null;
            }
        }

        // Try restore the old thread group, no need to fail as the
        // mediacodec process could be dead just now.
        if (boostedPid == pid) {
            try {
                Log.i(TAG, "Moving " + pid + " back to group default");
                Process.setProcessGroup(pid, Process.THREAD_GROUP_DEFAULT);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't move pid " + pid + " back to group default");
            }
        }

        return PackageManager.PERMISSION_GRANTED;
    }

    private boolean isPermitted() {
        // schedulerservice hidl
        if (Binder.getCallingPid() == Process.myPid()) {
            return true;
        }

        switch (Binder.getCallingUid()) {
        case Process.AUDIOSERVER_UID:  // fastcapture, fastmixer
        case Process.MEDIA_UID:        // mediaserver
        case Process.CAMERASERVER_UID: // camera high frame rate recording
        case Process.BLUETOOTH_UID:    // Bluetooth audio playback
            return true;
        default:
            return false;
        }
    }
}
