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

package com.android.systemui.statusbar.tv.micdisclosure;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;

import static com.android.systemui.statusbar.tv.micdisclosure.AudioRecordingDisclosureBar.DEBUG;

import android.annotation.UiThread;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.IProcessObserver;
import android.content.Context;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The purpose of these class is to detect packages that are running foreground services of type
 * 'microphone' and to report back to {@link AudioRecordingDisclosureBar}.
 */
class MicrophoneForegroundServicesObserver extends AudioActivityObserver {
    private static final String TAG = "MicrophoneForegroundServicesObserver";
    private static final boolean ENABLED = true;

    private final IActivityManager mActivityManager;
    /**
     * A dictionary that maps PIDs to the package names. We only keep track of the PIDs that are
     * "active" (those that are running FGS with FOREGROUND_SERVICE_TYPE_MICROPHONE flag).
     */
    private final SparseArray<String[]> mPidToPackages = new SparseArray<>();
    /**
     * A dictionary that maps "active" packages to the number of the "active" processes associated
     * with those packages. We really only need this in case when one application is running in
     * multiple processes, so that we don't lose track of the package when one of its "active"
     * processes ceases, while others remain "active".
     */
    private final Map<String, Integer> mPackageToProcessCount = new ArrayMap<>();

    MicrophoneForegroundServicesObserver(Context context,
            OnAudioActivityStateChangeListener listener) {
        super(context, listener);

        mActivityManager = ActivityManager.getService();
        try {
            mActivityManager.registerProcessObserver(mProcessObserver);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't register process observer", e);
        }
    }

    @Override
    Set<String> getActivePackages() {
        return ENABLED ? mPackageToProcessCount.keySet() : Collections.emptySet();
    }

    @UiThread
    private void onProcessForegroundServicesChanged(int pid, boolean hasMicFgs) {
        final String[] changedPackages;
        if (hasMicFgs) {
            if (mPidToPackages.contains(pid)) {
                // We are already tracking this pid - ignore.
                changedPackages = null;
            } else {
                changedPackages = getPackageNames(pid);
                mPidToPackages.append(pid, changedPackages);
            }
        } else {
            changedPackages = mPidToPackages.removeReturnOld(pid);
        }

        if (changedPackages == null) {
            return;
        }

        for (int index = changedPackages.length - 1; index >= 0; index--) {
            final String packageName = changedPackages[index];
            int processCount = mPackageToProcessCount.getOrDefault(packageName, 0);
            final boolean shouldNotify;
            if (hasMicFgs) {
                processCount++;
                shouldNotify = processCount == 1;
            } else {
                processCount--;
                shouldNotify = processCount == 0;
            }
            if (processCount > 0) {
                mPackageToProcessCount.put(packageName, processCount);
            } else {
                mPackageToProcessCount.remove(packageName);
            }
            if (shouldNotify) notifyPackageStateChanged(packageName, hasMicFgs);
        }
    }

    @UiThread
    private void onProcessDied(int pid) {
        final String[] packages = mPidToPackages.removeReturnOld(pid);
        if (packages == null) {
            // This PID was not active - ignore.
            return;
        }

        for (int index = packages.length - 1; index >= 0; index--) {
            final String packageName = packages[index];
            int processCount = mPackageToProcessCount.getOrDefault(packageName, 0);
            if (processCount <= 0) {
                Log.e(TAG, "Bookkeeping error, process count for " + packageName + " is "
                        + processCount);
                continue;
            }
            processCount--;
            if (processCount > 0) {
                mPackageToProcessCount.put(packageName, processCount);
            } else {
                mPackageToProcessCount.remove(packageName);
                notifyPackageStateChanged(packageName, false);
            }
        }
    }

    @UiThread
    private void notifyPackageStateChanged(String packageName, boolean active) {
        if (active) {
            if (DEBUG) Log.d(TAG, "New microphone fgs detected, package=" + packageName);
        } else {
            if (DEBUG) Log.d(TAG, "Microphone fgs is gone, package=" + packageName);
        }

        if (ENABLED) mListener.onAudioActivityStateChange(active, packageName);
    }

    @UiThread
    private String[] getPackageNames(int pid) {
        final List<ActivityManager.RunningAppProcessInfo> runningApps;
        try {
            runningApps = mActivityManager.getRunningAppProcesses();
        } catch (RemoteException e) {
            Log.d(TAG, "Couldn't get package name for pid=" + pid);
            return null;
        }
        if (runningApps == null) {
            Log.wtf(TAG, "No running apps reported");
        }
        for (ActivityManager.RunningAppProcessInfo app : runningApps) {
            if (app.pid == pid) {
                return app.pkgList;
            }
        }
        return null;
    }

    private final IProcessObserver mProcessObserver = new IProcessObserver.Stub() {
        @Override
        public void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) {}

        @Override
        public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
            mContext.getMainExecutor().execute(() -> onProcessForegroundServicesChanged(pid,
                    (serviceTypes & FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0));
        }

        @Override
        public void onProcessDied(int pid, int uid) {
            mContext.getMainExecutor().execute(
                    () -> MicrophoneForegroundServicesObserver.this.onProcessDied(pid));
        }
    };
}
