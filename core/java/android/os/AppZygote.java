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

import static android.os.Process.ZYGOTE_POLICY_FLAG_EMPTY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProcessInfo;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.Zygote;

import dalvik.system.VMRuntime;

import java.util.Map;

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
    private final ProcessInfo mProcessInfo;

    public AppZygote(ApplicationInfo appInfo, ProcessInfo processInfo, int zygoteUid, int uidGidMin,
            int uidGidMax) {
        mAppInfo = appInfo;
        mProcessInfo = processInfo;
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

    /**
     * Start a new process.
     *
     * <p>Wrap ZygoteProcess.start with retry logic.
     *
     * @param processClass The class to use as the process's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the process will run.
     * @param gids Additional group-ids associated with the process.
     * @param runtimeFlags Additional flags.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi non-null the ABI this app should be started with.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param packageName null-ok the name of the package this process belongs to.
     * @param isTopApp Whether the process starts for high priority application.
     * @param disabledCompatChanges null-ok list of disabled compat changes for the process being
     *                             started.
     * @param pkgDataInfoMap Map from related package names to private data directory
     *                       volume UUID and inode number.
     * @param allowlistedDataInfoList Map from allowlisted package names to private data directory
     *                       volume UUID and inode number.
     * @param zygoteArgs Additional arguments to supply to the Zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws RuntimeException on fatal start failure
     */
    public final Process.ProcessStartResult startProcess(@NonNull final String processClass,
            final String niceName,
            int uid, @Nullable int[] gids,
            int runtimeFlags, int mountExternal,
            int targetSdkVersion,
            @Nullable String seInfo,
            @NonNull String abi,
            @Nullable String instructionSet,
            @Nullable String appDataDir,
            @Nullable String packageName,
            boolean isTopApp,
            @Nullable long[] disabledCompatChanges,
            @Nullable Map<String, Pair<String, Long>>
            pkgDataInfoMap,
            @Nullable Map<String, Pair<String, Long>>
            allowlistedDataInfoList,
            @Nullable String[] zygoteArgs) {
        try {
            return getProcess().start(processClass,
                    niceName, uid, uid, gids, runtimeFlags, mountExternal,
                    targetSdkVersion, seInfo, abi, instructionSet,
                    appDataDir, null, packageName,
                    /*zygotePolicyFlags=*/ ZYGOTE_POLICY_FLAG_EMPTY, isTopApp,
                    disabledCompatChanges, pkgDataInfoMap, allowlistedDataInfoList,
                    false, false, false,
                    zygoteArgs);
        } catch (RuntimeException e) {
            if (!Flags.appZygoteRetryStart()) {
                throw e;
            }
            final boolean zygote_dead = getProcess().isDead();
            if (!zygote_dead) {
                throw e; // Zygote process is alive. Do nothing.
            }
        }
        // Retry here if the previous start fails.
        Log.w(LOG_TAG, "retry starting process " + niceName);
        stopZygote();
        return getProcess().start(processClass,
                niceName, uid, uid, gids, runtimeFlags, mountExternal,
                targetSdkVersion, seInfo, abi, instructionSet,
                appDataDir, null, packageName,
                /*zygotePolicyFlags=*/ ZYGOTE_POLICY_FLAG_EMPTY, isTopApp,
                disabledCompatChanges, pkgDataInfoMap, allowlistedDataInfoList,
                false, false, false,
                zygoteArgs);
    }

    @GuardedBy("mLock")
    private void stopZygoteLocked() {
        if (mZygote != null) {
            mZygote.close();
            // use killProcessGroup() here, so we kill all untracked children as well.
            if (!mZygote.isDead()) {
                Process.killProcessGroup(mZygoteUid, mZygote.getPid());
            }
            mZygote = null;
        }
    }

    @GuardedBy("mLock")
    private void connectToZygoteIfNeededLocked() {
        String abi = mAppInfo.primaryCpuAbi != null ? mAppInfo.primaryCpuAbi :
                Build.SUPPORTED_ABIS[0];
        try {
            int runtimeFlags = Zygote.getMemorySafetyRuntimeFlagsForSecondaryZygote(
                    mAppInfo, mProcessInfo);

            final int[] sharedAppGid = {
                    UserHandle.getSharedAppGid(UserHandle.getAppId(mAppInfo.uid)) };
            mZygote = Process.ZYGOTE_PROCESS.startChildZygote(
                    "com.android.internal.os.AppZygoteInit",
                    mAppInfo.processName + "_zygote",
                    mZygoteUid,
                    mZygoteUid,
                    sharedAppGid,  // Zygote gets access to shared app GID for profiles
                    runtimeFlags,
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
