/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.os;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.VMRuntime;

/**
 * AppZygote is responsible for interfacing with an application-specific zygote.
 *
 * Application zygotes can pre-load app-specific code and data, and this interface can
 * be used to spawn isolated services from such an application zygote.
 *
 * Note that we'll have only one instance of this per application / uid combination.
 *
 * @hide
 */
public class AppZygote {
    private static final String LOG_TAG = "AppZygote";

    // UID of the Zygote itself
    private final int mZygoteUid;

    // First UID/GID of the range the AppZygote can setuid()/setgid() to
    private final int mZygoteUidGidMin;

    // Last UID/GID of the range the AppZygote can setuid()/setgid() to
    private final int mZygoteUidGidMax;

    private final Object mLock = new Object();

    /**
     * Instance that maintains the socket connection to the zygote. This is {@code null} if the
     * zygote is not running or is not connected.
     */
    @GuardedBy("mLock")
    private ChildZygoteProcess mZygote;

    private final ApplicationInfo mAppInfo;

    public AppZygote(ApplicationInfo appInfo, int zygoteUid, int uidGidMin, int uidGidMax) {
        mAppInfo = appInfo;
        mZygoteUid = zygoteUid;
        mZygoteUidGidMin = uidGidMin;
        mZygoteUidGidMax = uidGidMax;
    }

    /**
     * Returns the zygote process associated with this app zygote.
     * Creates the process if it's not already running.
     */
    public ChildZygoteProcess getProcess() {
        synchronized (mLock) {
            if (mZygote != null) return mZygote;

            connectToZygoteIfNeededLocked();
            return mZygote;
        }
    }

    /**
     * Stops the Zygote and kills the zygote process.
     */
    public void stopZygote() {
        synchronized (mLock) {
            stopZygoteLocked();
        }
    }

    public ApplicationInfo getAppInfo() {
        return mAppInfo;
    }

    @GuardedBy("mLock")
    private void stopZygoteLocked() {
        if (mZygote != null) {
            mZygote.close();
            // use killProcessGroup() here, so we kill all untracked children as well.
            Process.killProcessGroup(mZygoteUid, mZygote.getPid());
            mZygote = null;
        }
    }

    @GuardedBy("mLock")
    private void connectToZygoteIfNeededLocked() {
        String abi = mAppInfo.primaryCpuAbi != null ? mAppInfo.primaryCpuAbi :
                Build.SUPPORTED_ABIS[0];
        try {
            mZygote = Process.ZYGOTE_PROCESS.startChildZygote(
                    "com.android.internal.os.AppZygoteInit",
                    mAppInfo.processName + "_zygote",
                    mZygoteUid,
                    mZygoteUid,
                    null,  // gids
                    0,  // runtimeFlags
                    "app_zygote",  // seInfo
                    abi,  // abi
                    abi, // acceptedAbiList
                    VMRuntime.getInstructionSet(abi), // instructionSet
                    mZygoteUidGidMin,
                    mZygoteUidGidMax);

            ZygoteProcess.waitForConnectionToZygote(mZygote.getPrimarySocketAddress());
            // preload application code in the zygote
            Log.i(LOG_TAG, "Starting application preload.");
            mZygote.preloadApp(mAppInfo, abi);
            Log.i(LOG_TAG, "Application preload done.");
        } catch (Exception e) {
            Log.e(LOG_TAG, "Error connecting to app zygote", e);
            stopZygoteLocked();
        }
    }
}
