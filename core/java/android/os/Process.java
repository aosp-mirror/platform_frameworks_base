/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.annotation.SystemApi.Client.MODULE_LIBRARIES;

import android.annotation.ElapsedRealtimeLong;
import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UptimeMillisLong;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build.VERSION_CODES;
import android.sysprop.MemoryProperties;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Pair;
import android.webkit.WebViewZygote;

import com.android.internal.os.SomeArgs;
import com.android.internal.util.Preconditions;
import com.android.sdksandbox.flags.Flags;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Tools for managing OS processes.
 */
public class Process {
    private static final String LOG_TAG = "Process";

    /**
     * An invalid UID value.
     */
    public static final int INVALID_UID = -1;

    /**
     * Defines the root UID.
     */
    public static final int ROOT_UID = 0;

    /**
     * Defines the UID/GID under which system code runs.
     */
    public static final int SYSTEM_UID = 1000;

    /**
     * Defines the UID/GID under which the telephony code runs.
     */
    public static final int PHONE_UID = 1001;

    /**
     * Defines the UID/GID for the user shell.
     */
    public static final int SHELL_UID = 2000;

    /**
     * Defines the UID/GID for the log group.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int LOG_UID = 1007;

    /**
     * Defines the UID/GID for the WIFI native processes like wificond, supplicant, hostapd,
     * vendor HAL, etc.
     */
    public static final int WIFI_UID = 1010;

    /**
     * Defines the UID/GID for the mediaserver process.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int MEDIA_UID = 1013;

    /**
     * Defines the UID/GID for the DRM process.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int DRM_UID = 1019;

    /**
     * Defines the GID for the group that allows write access to the internal media storage.
     * @hide
     */
    public static final int SDCARD_RW_GID = 1015;

    /**
     * Defines the UID/GID for the group that controls VPN services.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int VPN_UID = 1016;

    /**
     * Defines the UID/GID for keystore.
     * @hide
     */
    public static final int KEYSTORE_UID = 1017;

    /**
     * Defines the UID/GID for credstore.
     * @hide
     */
    public static final int CREDSTORE_UID = 1076;

    /**
     * Defines the UID/GID for the NFC service process.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    @SystemApi(client = MODULE_LIBRARIES)
    public static final int NFC_UID = 1027;

    /**
     * Defines the UID/GID for the clatd process.
     * @hide
     * */
    public static final int CLAT_UID = 1029;

    /**
     * Defines the UID/GID for the Bluetooth service process.
     */
    public static final int BLUETOOTH_UID = 1002;

    /**
     * Defines the GID for the group that allows write access to the internal media storage.
     * @hide
     */
    public static final int MEDIA_RW_GID = 1023;

    /**
     * Access to installed package details
     * @hide
     */
    public static final int PACKAGE_INFO_GID = 1032;

    /**
     * Defines the UID/GID for the shared RELRO file updater process.
     * @hide
     */
    public static final int SHARED_RELRO_UID = 1037;

    /**
     * Defines the UID/GID for the audioserver process.
     * @hide
     */
    public static final int AUDIOSERVER_UID = 1041;

    /**
     * Defines the UID/GID for the cameraserver process
     * @hide
     */
    public static final int CAMERASERVER_UID = 1047;

    /**
     * Defines the UID/GID for the tethering DNS resolver (currently dnsmasq).
     * @hide
     */
    public static final int DNS_TETHER_UID = 1052;

    /**
     * Defines the UID/GID for the WebView zygote process.
     * @hide
     */
    public static final int WEBVIEW_ZYGOTE_UID = 1053;

    /**
     * Defines the UID used for resource tracking for OTA updates.
     * @hide
     */
    public static final int OTA_UPDATE_UID = 1061;

    /**
     * Defines the UID used for statsd
     * @hide
     */
    public static final int STATSD_UID = 1066;

    /**
     * Defines the UID used for incidentd.
     * @hide
     */
    public static final int INCIDENTD_UID = 1067;

    /**
     * Defines the UID/GID for the Secure Element service process.
     * @hide
     */
    public static final int SE_UID = 1068;

    /**
     * Defines the UID/GID for the NetworkStack app.
     * @hide
     */
    public static final int NETWORK_STACK_UID = 1073;

    /**
     * Defines the UID/GID for fs-verity certificate ownership in keystore.
     * @hide
     */
    public static final int FSVERITY_CERT_UID = 1075;

    /**
     * GID that gives access to USB OTG (unreliable) volumes on /mnt/media_rw/<vol name>
     * @hide
     */
    public static final int EXTERNAL_STORAGE_GID = 1077;

    /**
     * GID that gives write access to app-private data directories on external
     * storage (used on devices without sdcardfs only).
     * @hide
     */
    public static final int EXT_DATA_RW_GID = 1078;

    /**
     * GID that gives write access to app-private OBB directories on external
     * storage (used on devices without sdcardfs only).
     * @hide
     */
    public static final int EXT_OBB_RW_GID = 1079;

    /**
     * Defines the UID/GID for the Uwb service process.
     * @hide
     */
    public static final int UWB_UID = 1083;

    /**
     * Defines a virtual UID that is used to aggregate data related to SDK sandbox UIDs.
     * {@see SdkSandboxManager}
     * @hide
     */
    @TestApi
    public static final int SDK_SANDBOX_VIRTUAL_UID = 1090;

    /**
     * GID that corresponds to the INTERNET permission.
     * Must match the value of AID_INET.
     * @hide
     */
    public static final int INET_GID = 3003;

    /** {@hide} */
    public static final int NOBODY_UID = 9999;

    /**
     * Defines the start of a range of UIDs (and GIDs), going from this
     * number to {@link #LAST_APPLICATION_UID} that are reserved for assigning
     * to applications.
     */
    public static final int FIRST_APPLICATION_UID = 10000;

    /**
     * Last of application-specific UIDs starting at
     * {@link #FIRST_APPLICATION_UID}.
     */
    public static final int LAST_APPLICATION_UID = 19999;

    /**
     * Defines the start of a range of UIDs going from this number to
     * {@link #LAST_SDK_SANDBOX_UID} that are reserved for assigning to
     * sdk sandbox processes. There is a 1-1 mapping between a sdk sandbox
     * process UID and the app that it belongs to, which can be computed by
     * subtracting (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID) from the
     * uid of a sdk sandbox process.
     *
     * Note that there are no GIDs associated with these processes; storage
     * attribution for them will be done using project IDs.
     * @hide
     */
    public static final int FIRST_SDK_SANDBOX_UID = 20000;

    /**
     * Last UID that is used for sdk sandbox processes.
     * @hide
     */
    public static final int LAST_SDK_SANDBOX_UID = 29999;

    /**
     * First uid used for fully isolated sandboxed processes spawned from an app zygote
     * @hide
     */
    @TestApi
    public static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;

    /**
     * Number of UIDs we allocate per application zygote
     * @hide
     */
    @TestApi
    public static final int NUM_UIDS_PER_APP_ZYGOTE = 100;

    /**
     * Last uid used for fully isolated sandboxed processes spawned from an app zygote
     * @hide
     */
    @TestApi
    public static final int LAST_APP_ZYGOTE_ISOLATED_UID = 98999;

    /**
     * First uid used for fully isolated sandboxed processes (with no permissions of their own)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int FIRST_ISOLATED_UID = 99000;

    /**
     * Last uid used for fully isolated sandboxed processes (with no permissions of their own)
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int LAST_ISOLATED_UID = 99999;

    /**
     * Defines the gid shared by all applications running under the same profile.
     * @hide
     */
    public static final int SHARED_USER_GID = 9997;

    /**
     * First gid for applications to share resources. Used when forward-locking
     * is enabled but all UserHandles need to be able to read the resources.
     * @hide
     */
    public static final int FIRST_SHARED_APPLICATION_GID = 50000;

    /**
     * Last gid for applications to share resources. Used when forward-locking
     * is enabled but all UserHandles need to be able to read the resources.
     * @hide
     */
    public static final int LAST_SHARED_APPLICATION_GID = 59999;

    /** {@hide} */
    public static final int FIRST_APPLICATION_CACHE_GID = 20000;
    /** {@hide} */
    public static final int LAST_APPLICATION_CACHE_GID = 29999;

    /**
     * An invalid PID value.
     */
    public static final int INVALID_PID = -1;

    /**
     * Standard priority of application threads.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_DEFAULT = 0;

    /*
     * ***************************************
     * ** Keep in sync with utils/threads.h **
     * ***************************************
     */

    /**
     * Lowest available thread priority.  Only for those who really, really
     * don't want to run if anything else is happening.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_LOWEST = 19;

    /**
     * Standard priority background threads.  This gives your thread a slightly
     * lower than normal priority, so that it will have less chance of impacting
     * the responsiveness of the user interface.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_BACKGROUND = 10;

    /**
     * Standard priority of threads that are currently running a user interface
     * that the user is interacting with.  Applications can not normally
     * change to this priority; the system will automatically adjust your
     * application threads as the user moves through the UI.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_FOREGROUND = -2;

    /**
     * Standard priority of system display threads, involved in updating
     * the user interface.  Applications can not
     * normally change to this priority.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_DISPLAY = -4;

    /**
     * Standard priority of the most important display threads, for compositing
     * the screen and retrieving input events.  Applications can not normally
     * change to this priority.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_URGENT_DISPLAY = -8;

    /**
     * Standard priority of video threads.  Applications can not normally
     * change to this priority.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_VIDEO = -10;

    /**
     * Priority we boost main thread and RT of top app to.
     * @hide
     */
    public static final int THREAD_PRIORITY_TOP_APP_BOOST = -10;

    /**
     * Standard priority of audio threads.  Applications can not normally
     * change to this priority.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_AUDIO = -16;

    /**
     * Standard priority of the most important audio threads.
     * Applications can not normally change to this priority.
     * Use with {@link #setThreadPriority(int)} and
     * {@link #setThreadPriority(int, int)}, <b>not</b> with the normal
     * {@link java.lang.Thread} class.
     */
    public static final int THREAD_PRIORITY_URGENT_AUDIO = -19;

    /**
     * Minimum increment to make a priority more favorable.
     */
    public static final int THREAD_PRIORITY_MORE_FAVORABLE = -1;

    /**
     * Minimum increment to make a priority less favorable.
     */
    public static final int THREAD_PRIORITY_LESS_FAVORABLE = +1;

    /**
     * Default scheduling policy
     * @hide
     */
    public static final int SCHED_OTHER = 0;

    /**
     * First-In First-Out scheduling policy
     * @hide
     */
    public static final int SCHED_FIFO = 1;

    /**
     * Round-Robin scheduling policy
     * @hide
     */
    public static final int SCHED_RR = 2;

    /**
     * Batch scheduling policy
     * @hide
     */
    public static final int SCHED_BATCH = 3;

    /**
     * Idle scheduling policy
     * @hide
     */
    public static final int SCHED_IDLE = 5;

    /**
     * Reset scheduler choice on fork.
     * @hide
     */
    public static final int SCHED_RESET_ON_FORK = 0x40000000;

    // Keep in sync with SP_* constants of enum type SchedPolicy
    // declared in system/core/include/cutils/sched_policy.h,
    // except THREAD_GROUP_DEFAULT does not correspond to any SP_* value.

    /**
     * Default thread group -
     * has meaning with setProcessGroup() only, cannot be used with setThreadGroup().
     * When used with setProcessGroup(), the group of each thread in the process
     * is conditionally changed based on that thread's current priority, as follows:
     * threads with priority numerically less than THREAD_PRIORITY_BACKGROUND
     * are moved to foreground thread group.  All other threads are left unchanged.
     * @hide
     */
    public static final int THREAD_GROUP_DEFAULT = -1;

    /**
     * Background thread group - All threads in
     * this group are scheduled with a reduced share of the CPU.
     * Value is same as constant SP_BACKGROUND of enum SchedPolicy.
     * @hide
     */
    public static final int THREAD_GROUP_BACKGROUND = 0;

    /**
     * Foreground thread group - All threads in
     * this group are scheduled with a normal share of the CPU.
     * Value is same as constant SP_FOREGROUND of enum SchedPolicy.
     * Not used at this level.
     * @hide
     **/
    private static final int THREAD_GROUP_FOREGROUND = 1;

    /**
     * System thread group.
     * @hide
     **/
    public static final int THREAD_GROUP_SYSTEM = 2;

    /**
     * Application audio thread group.
     * @hide
     **/
    public static final int THREAD_GROUP_AUDIO_APP = 3;

    /**
     * System audio thread group.
     * @hide
     **/
    public static final int THREAD_GROUP_AUDIO_SYS = 4;

    /**
     * Thread group for top foreground app.
     * @hide
     **/
    public static final int THREAD_GROUP_TOP_APP = 5;

    /**
     * Thread group for RT app.
     * @hide
     **/
    public static final int THREAD_GROUP_RT_APP = 6;

    /**
     * Thread group for bound foreground services that should
     * have additional CPU restrictions during screen off
     * @hide
     **/
    public static final int THREAD_GROUP_RESTRICTED = 7;

    /**
     * Thread group for foreground apps in multi-window mode
     * @hide
     **/
    public static final int THREAD_GROUP_FOREGROUND_WINDOW = 8;

    /** @hide */
    public static final int SIGNAL_DEFAULT = 0;
    public static final int SIGNAL_QUIT = 3;
    public static final int SIGNAL_KILL = 9;
    public static final int SIGNAL_USR1 = 10;

    /**
     * When the process started and ActivityThread.handleBindApplication() was executed.
     */
    private static long sStartElapsedRealtime;

    /**
     * When the process started and ActivityThread.handleBindApplication() was executed.
     */
    private static long sStartUptimeMillis;

    /**
     * When the activity manager was about to ask zygote to fork.
     */
    private static long sStartRequestedElapsedRealtime;

    /**
     * When the activity manager was about to ask zygote to fork.
     */
    private static long sStartRequestedUptimeMillis;

    private static final int PIDFD_UNKNOWN = 0;
    private static final int PIDFD_SUPPORTED = 1;
    private static final int PIDFD_UNSUPPORTED = 2;

    /**
     * Whether or not the underlying OS supports pidfd
     */
    private static int sPidFdSupported = PIDFD_UNKNOWN;

    /**
     * Value used to indicate that there is no special information about an application launch.  App
     * launches with this policy will occur through the primary or secondary Zygote with no special
     * treatment.
     *
     * @hide
     */
    public static final int ZYGOTE_POLICY_FLAG_EMPTY = 0;

    /**
     * Flag used to indicate that an application launch is user-visible and latency sensitive.  Any
     * launch with this policy will use a Unspecialized App Process Pool if the target Zygote
     * supports it.
     *
     * @hide
     */
    public static final int ZYGOTE_POLICY_FLAG_LATENCY_SENSITIVE = 1 << 0;

    /**
     * Flag used to indicate that the launch is one in a series of app launches that will be
     * performed in quick succession.  For future use.
     *
     * @hide
     */
    public static final int ZYGOTE_POLICY_FLAG_BATCH_LAUNCH = 1 << 1;

    /**
     * Flag used to indicate that the current launch event is for a system process.  All system
     * processes are equally important, so none of them should be prioritized over the others.
     *
     * @hide
     */
    public static final int ZYGOTE_POLICY_FLAG_SYSTEM_PROCESS = 1 << 2;

    /**
     * State associated with the zygote process.
     * @hide
     */
    public static final ZygoteProcess ZYGOTE_PROCESS = new ZygoteProcess();


    /**
     * The process name set via {@link #setArgV0(String)}.
     */
    private static String sArgV0;

    /**
     * Start a new process.
     *
     * <p>If processes are enabled, a new process is created and the
     * static main() function of a <var>processClass</var> is executed there.
     * The process will continue running after this function returns.
     *
     * <p>If processes are not enabled, a new thread in the caller's
     * process is created and main() of <var>processClass</var> called there.
     *
     * <p>The niceName parameter, if not an empty string, is a custom name to
     * give to the process instead of using processClass.  This allows you to
     * make easily identifyable processes even if you are using the same base
     * <var>processClass</var> to start them.
     *
     * When invokeWith is not null, the process will be started as a fresh app
     * and not a zygote fork. Note that this is only allowed for uid 0 or when
     * runtimeFlags contains DEBUG_ENABLE_DEBUGGER.
     *
     * @param processClass The class to use as the process's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the process will run.
     * @param gid The group-id under which the process will run.
     * @param gids Additional group-ids associated with the process.
     * @param runtimeFlags Additional flags for the runtime.
     * @param targetSdkVersion The target SDK version for the app.
     * @param seInfo null-ok SELinux information for the new process.
     * @param abi non-null the ABI this app should be started with.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param invokeWith null-ok the command to invoke with.
     * @param packageName null-ok the name of the package this process belongs to.
     * @param zygotePolicyFlags Flags used to determine how to launch the application
     * @param isTopApp whether the process starts for high priority application.
     * @param disabledCompatChanges null-ok list of disabled compat changes for the process being
     *                             started.
     * @param pkgDataInfoMap Map from related package names to private data directory
     *                       volume UUID and inode number.
     * @param whitelistedDataInfoMap Map from allowlisted package names to private data directory
     *                       volume UUID and inode number.
     * @param bindMountAppsData whether zygote needs to mount CE and DE data.
     * @param bindMountAppStorageDirs whether zygote needs to mount Android/obb and Android/data.
     * @param zygoteArgs Additional arguments to supply to the zygote process.
     * @return An object that describes the result of the attempt to start the process.
     * @throws RuntimeException on fatal start failure
     *
     * {@hide}
     */
    public static ProcessStartResult start(@NonNull final String processClass,
                                           @Nullable final String niceName,
                                           int uid, int gid, @Nullable int[] gids,
                                           int runtimeFlags,
                                           int mountExternal,
                                           int targetSdkVersion,
                                           @Nullable String seInfo,
                                           @NonNull String abi,
                                           @Nullable String instructionSet,
                                           @Nullable String appDataDir,
                                           @Nullable String invokeWith,
                                           @Nullable String packageName,
                                           int zygotePolicyFlags,
                                           boolean isTopApp,
                                           @Nullable long[] disabledCompatChanges,
                                           @Nullable Map<String, Pair<String, Long>>
                                                   pkgDataInfoMap,
                                           @Nullable Map<String, Pair<String, Long>>
                                                   whitelistedDataInfoMap,
                                           boolean bindMountAppsData,
                                           boolean bindMountAppStorageDirs,
                                           boolean bindMountSystemOverrides,
                                           @Nullable String[] zygoteArgs) {
        return ZYGOTE_PROCESS.start(processClass, niceName, uid, gid, gids,
                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                    abi, instructionSet, appDataDir, invokeWith, packageName,
                    zygotePolicyFlags, isTopApp, disabledCompatChanges,
                    pkgDataInfoMap, whitelistedDataInfoMap, bindMountAppsData,
                    bindMountAppStorageDirs, bindMountSystemOverrides, zygoteArgs);
    }

    /** @hide */
    public static ProcessStartResult startWebView(@NonNull final String processClass,
                                                  @Nullable final String niceName,
                                                  int uid, int gid, @Nullable int[] gids,
                                                  int runtimeFlags,
                                                  int mountExternal,
                                                  int targetSdkVersion,
                                                  @Nullable String seInfo,
                                                  @NonNull String abi,
                                                  @Nullable String instructionSet,
                                                  @Nullable String appDataDir,
                                                  @Nullable String invokeWith,
                                                  @Nullable String packageName,
                                                  @Nullable long[] disabledCompatChanges,
                                                  @Nullable String[] zygoteArgs) {
        // Webview zygote can't access app private data files, so doesn't need to know its data
        // info.
        return WebViewZygote.getProcess().start(processClass, niceName, uid, gid, gids,
                    runtimeFlags, mountExternal, targetSdkVersion, seInfo,
                    abi, instructionSet, appDataDir, invokeWith, packageName,
                    /*zygotePolicyFlags=*/ ZYGOTE_POLICY_FLAG_EMPTY, /*isTopApp=*/ false,
                disabledCompatChanges, /* pkgDataInfoMap */ null,
                /* whitelistedDataInfoMap */ null, /* bindMountAppsData */ false,
                /* bindMountAppStorageDirs */ false, /* bindMountSyspropOverrides */ false, zygoteArgs);
    }

    /**
     * Returns elapsed milliseconds of the time this process has run.
     * @return  Returns the number of milliseconds this process has return.
     */
    public static final native long getElapsedCpuTime();

    /**
     * Return the {@link SystemClock#elapsedRealtime()} at which this process was started,
     * but before any of the application code was executed.
     */
    @ElapsedRealtimeLong
    public static long getStartElapsedRealtime() {
        return sStartElapsedRealtime;
    }

    /**
     * Return the {@link SystemClock#uptimeMillis()} at which this process was started,
     * but before any of the application code was executed.
     */
    @UptimeMillisLong
    public static long getStartUptimeMillis() {
        return sStartUptimeMillis;
    }

    /**
     * Return the {@link SystemClock#elapsedRealtime()} at which the system was about to
     * start this process. i.e. before a zygote fork.
     *
     * <p>More precisely, the system may start app processes before there's a start request,
     * in order to reduce the process start up latency, in which case this is set when the system
     * decides to "specialize" the process into a requested app.
     */
    @ElapsedRealtimeLong
    public static long getStartRequestedElapsedRealtime() {
        return sStartRequestedElapsedRealtime;
    }

    /**
     * Return the {@link SystemClock#uptimeMillis()} at which the system was about to
     * start this process. i.e. before a zygote fork.
     *
     * <p>More precisely, the system may start app processes before there's a start request,
     * in order to reduce the process start up latency, in which case this is set when the system
     * decides to "specialize" the process into a requested app.
     */
    @UptimeMillisLong
    public static long getStartRequestedUptimeMillis() {
        return sStartRequestedUptimeMillis;
    }

    /** @hide */
    public static final void setStartTimes(long elapsedRealtime, long uptimeMillis,
            long startRequestedElapsedRealtime, long startRequestedUptime) {
        sStartElapsedRealtime = elapsedRealtime;
        sStartUptimeMillis = uptimeMillis;
        sStartRequestedElapsedRealtime = startRequestedElapsedRealtime;
        sStartRequestedUptimeMillis = startRequestedUptime;
    }

    /**
     * Returns true if the current process is a 64-bit runtime.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean is64Bit() {
        return VMRuntime.getRuntime().is64Bit();
    }

    private static volatile ThreadLocal<SomeArgs> sIdentity$ravenwood;

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void init$ravenwood(final int uid, final int pid) {
        sIdentity$ravenwood = ThreadLocal.withInitial(() -> {
            final SomeArgs args = SomeArgs.obtain();
            args.argi1 = uid;
            args.argi2 = pid;
            args.argi3 = Long.hashCode(Thread.currentThread().getId());
            args.argi4 = THREAD_PRIORITY_DEFAULT;
            args.arg1 = Boolean.TRUE; // backgroundOk
            return args;
        });
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void reset$ravenwood() {
        sIdentity$ravenwood = null;
    }

    /**
     * Returns the identifier of this process, which can be used with
     * {@link #killProcess} and {@link #sendSignal}.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final int myPid() {
        return Os.getpid();
    }

    /** @hide */
    public static final int myPid$ravenwood() {
        return Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get().argi2;
    }

    /**
     * Returns the identifier of this process' parent.
     * @hide
     */
    @UnsupportedAppUsage(trackingBug = 171962076)
    public static final int myPpid() {
        return Os.getppid();
    }

    /**
     * Returns the identifier of the calling thread, which be used with
     * {@link #setThreadPriority(int, int)}.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final int myTid() {
        return Os.gettid();
    }

    /** @hide */
    public static final int myTid$ravenwood() {
        return Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get().argi3;
    }

    /**
     * Returns the identifier of this process's uid.  This is the kernel uid
     * that the process is running under, which is the identity of its
     * app-specific sandbox.  It is different from {@link #myUserHandle} in that
     * a uid identifies a specific app sandbox in a specific user.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final int myUid() {
        return Os.getuid();
    }

    /** @hide */
    public static final int myUid$ravenwood() {
        return Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get().argi1;
    }

    /**
     * Returns this process's user handle.  This is the
     * user the process is running under.  It is distinct from
     * {@link #myUid()} in that a particular user will have multiple
     * distinct apps running under it each with their own uid.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static UserHandle myUserHandle() {
        return UserHandle.of(UserHandle.getUserId(myUid()));
    }

    /**
     * Returns whether the given uid belongs to a system core component or not.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isCoreUid(int uid) {
        return UserHandle.isCore(uid);
    }

    /**
     * Returns whether the given uid belongs to an application.
     * @param uid A kernel uid.
     * @return Whether the uid corresponds to an application sandbox running in
     *     a specific user.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isApplicationUid(int uid) {
        return UserHandle.isApp(uid);
    }

    /**
     * Returns whether the current process is in an isolated sandbox.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isIsolated() {
        return isIsolated(myUid());
    }

    /**
     * @deprecated Use {@link #isIsolatedUid(int)} instead.
     * {@hide}
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.TIRAMISU,
            publicAlternatives = "Use {@link #isIsolatedUid(int)} instead.")
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isIsolated(int uid) {
        return isIsolatedUid(uid);
    }

    /**
     * Returns whether the process with the given {@code uid} is an isolated sandbox.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isIsolatedUid(int uid) {
        uid = UserHandle.getAppId(uid);
        return (uid >= FIRST_ISOLATED_UID && uid <= LAST_ISOLATED_UID)
                || (uid >= FIRST_APP_ZYGOTE_ISOLATED_UID && uid <= LAST_APP_ZYGOTE_ISOLATED_UID);
    }

    /**
     * Returns whether the provided UID belongs to an  sdk sandbox process
     * @see android.app.sdksandbox.SdkSandboxManager
     */
    @SuppressLint("UnflaggedApi") // promoting from @SystemApi.
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isSdkSandboxUid(int uid) {
        uid = UserHandle.getAppId(uid);
        return (uid >= FIRST_SDK_SANDBOX_UID && uid <= LAST_SDK_SANDBOX_UID);
    }

    /**
     * Returns the app uid corresponding to an sdk sandbox uid.
     * @see android.app.sdksandbox.SdkSandboxManager
     *
     * @param uid the sdk sandbox uid
     * @return the app uid for the given sdk sandbox uid
     *
     * @throws IllegalArgumentException if input is not an sdk sandbox uid
     */
    @SuppressLint("UnflaggedApi") // promoting from @SystemApi.
    @android.ravenwood.annotation.RavenwoodKeep
    public static final int getAppUidForSdkSandboxUid(int uid) {
        if (!isSdkSandboxUid(uid)) {
            throw new IllegalArgumentException("Input UID is not an SDK sandbox UID");
        }
        return uid - (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID);
    }

    /**
     *
     * Returns the sdk sandbox process corresponding to an app process.
     *
     * @hide
     */
    @SystemApi(client = MODULE_LIBRARIES)
    @TestApi
    @android.ravenwood.annotation.RavenwoodKeep
    // TODO(b/318651609): Deprecate once Process#getSdkSandboxUidForAppUid is rolled out to 100%
    public static final int toSdkSandboxUid(int uid) {
        return uid + (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID);
    }

    /**
     * Returns the sdk sandbox uid corresponding to an app uid.
     * @see android.app.sdksandbox.SdkSandboxManager
     *
     * @param uid the app uid
     * @return the sdk sandbox uid for the given app uid
     *
     * @throws IllegalArgumentException if input is not an app uid
     */
    @FlaggedApi(Flags.FLAG_SDK_SANDBOX_UID_TO_APP_UID_API)
    @android.ravenwood.annotation.RavenwoodKeep
    public static final int getSdkSandboxUidForAppUid(int uid) {
        if (!isApplicationUid(uid)) {
            throw new IllegalArgumentException("Input UID is not an app UID");
        }
        return uid + (FIRST_SDK_SANDBOX_UID - FIRST_APPLICATION_UID);
    }

    /**
     * Returns whether the current process is a sdk sandbox process.
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isSdkSandbox() {
        return isSdkSandboxUid(myUid());
    }

    /**
     * Returns the UID assigned to a particular user name, or -1 if there is
     * none.  If the given string consists of only numbers, it is converted
     * directly to a uid.
     */
    public static final native int getUidForName(String name);

    /**
     * Returns the GID assigned to a particular user name, or -1 if there is
     * none.  If the given string consists of only numbers, it is converted
     * directly to a gid.
     */
    public static final native int getGidForName(String name);

    /**
     * Returns a uid for a currently running process.
     * @param pid the process id
     * @return the uid of the process, or -1 if the process is not running.
     * @hide pending API council review
     */
    @UnsupportedAppUsage
    public static final int getUidForPid(int pid) {
        String[] procStatusLabels = { "Uid:" };
        long[] procStatusValues = new long[1];
        procStatusValues[0] = -1;
        Process.readProcLines("/proc/" + pid + "/status", procStatusLabels, procStatusValues);
        return (int) procStatusValues[0];
    }

    /**
     * Returns the parent process id for a currently running process.
     * @param pid the process id
     * @return the parent process id of the process, or -1 if the process is not running.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int getParentPid(int pid) {
        String[] procStatusLabels = { "PPid:" };
        long[] procStatusValues = new long[1];
        procStatusValues[0] = -1;
        Process.readProcLines("/proc/" + pid + "/status", procStatusLabels, procStatusValues);
        return (int) procStatusValues[0];
    }

    /**
     * Returns the thread group leader id for a currently running thread.
     * @param tid the thread id
     * @return the thread group leader id of the thread, or -1 if the thread is not running.
     *         This is same as what getpid(2) would return if called by tid.
     * @hide
     */
    public static final int getThreadGroupLeader(int tid) {
        String[] procStatusLabels = { "Tgid:" };
        long[] procStatusValues = new long[1];
        procStatusValues[0] = -1;
        Process.readProcLines("/proc/" + tid + "/status", procStatusLabels, procStatusValues);
        return (int) procStatusValues[0];
    }

    /**
     * Set the priority of a thread, based on Linux priorities.
     *
     * @param tid The identifier of the thread/process to change.
     * @param priority A Linux priority level, from -20 for highest scheduling
     * priority to 19 for lowest scheduling priority.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final native void setThreadPriority(int tid,
            @IntRange(from = -20, to = THREAD_PRIORITY_LOWEST) int priority)
            throws IllegalArgumentException, SecurityException;

    /** @hide */
    public static final void setThreadPriority$ravenwood(int tid, int priority) {
        final SomeArgs args =
                Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get();
        if (args.argi3 == tid) {
            boolean backgroundOk = (args.arg1 == Boolean.TRUE);
            if (priority >= THREAD_PRIORITY_BACKGROUND && !backgroundOk) {
                throw new IllegalArgumentException(
                        "Priority " + priority + " blocked by setCanSelfBackground()");
            }
            args.argi4 = priority;
        } else {
            throw new UnsupportedOperationException(
                    "Cross-thread priority management not yet available in Ravenwood");
        }
    }

    /**
     * Call with 'false' to cause future calls to {@link #setThreadPriority(int)} to
     * throw an exception if passed a background-level thread priority.  This is only
     * effective if the JNI layer is built with GUARD_THREAD_PRIORITY defined to 1.
     *
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final native void setCanSelfBackground(boolean backgroundOk);

    /** @hide */
    public static final void setCanSelfBackground$ravenwood(boolean backgroundOk) {
        final SomeArgs args =
                Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get();
        args.arg1 = Boolean.valueOf(backgroundOk);
    }

    /**
     * Sets the scheduling group for a thread.
     * @hide
     * @param tid The identifier of the thread to change.
     * @param group The target group for this thread from THREAD_GROUP_*.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     * If the thread is a thread group leader, that is it's gettid() == getpid(),
     * then the other threads in the same thread group are _not_ affected.
     *
     * Does not set cpuset for some historical reason, just calls
     * libcutils::set_sched_policy().
     */
    public static final native void setThreadGroup(int tid, int group)
            throws IllegalArgumentException, SecurityException;

    /**
     * Sets the scheduling group and the corresponding cpuset group
     * @hide
     * @param tid The identifier of the thread to change.
     * @param group The target group for this thread from THREAD_GROUP_*.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     */
    public static final native void setThreadGroupAndCpuset(int tid, int group)
            throws IllegalArgumentException, SecurityException;

    /**
     * Sets the scheduling group for a process and all child threads
     * @hide
     * @param pid The identifier of the process to change.
     * @param group The target group for this process from THREAD_GROUP_*.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     *
     * group == THREAD_GROUP_DEFAULT means to move all non-background priority
     * threads to the foreground scheduling group, but to leave background
     * priority threads alone.  group == THREAD_GROUP_BACKGROUND moves all
     * threads, regardless of priority, to the background scheduling group.
     * group == THREAD_GROUP_FOREGROUND is not allowed.
     *
     * Always sets cpusets.
     */
    @UnsupportedAppUsage
    public static final native void setProcessGroup(int pid, int group)
            throws IllegalArgumentException, SecurityException;

    /**
     * Freeze or unfreeze the specified process.
     *
     * @param pid Identifier of the process to freeze or unfreeze.
     * @param uid Identifier of the user the process is running under.
     * @param frozen Specify whether to free (true) or unfreeze (false).
     *
     * @hide
     */
    public static final native void setProcessFrozen(int pid, int uid, boolean frozen);

    /**
     * Return the scheduling group of requested process.
     *
     * @hide
     */
    public static final native int getProcessGroup(int pid)
            throws IllegalArgumentException, SecurityException;

    /**
     *
     * Create a new process group in the cgroup uid/pid hierarchy
     *
     * @return <0 in case of error
     *
     * @hide
     */
    public static final native int createProcessGroup(int uid, int pid);

    /**
     * On some devices, the foreground process may have one or more CPU
     * cores exclusively reserved for it. This method can be used to
     * retrieve which cores that are (if any), so the calling process
     * can then use sched_setaffinity() to lock a thread to these cores.
     * Note that the calling process must currently be running in the
     * foreground for this method to return any cores.
     *
     * The CPU core(s) exclusively reserved for the foreground process will
     * stay reserved for as long as the process stays in the foreground.
     *
     * As soon as a process leaves the foreground, those CPU cores will
     * no longer be reserved for it, and will most likely be reserved for
     * the new foreground process. It's not necessary to change the affinity
     * of your process when it leaves the foreground (if you had previously
     * set it to use a reserved core); the OS will automatically take care
     * of resetting the affinity at that point.
     *
     * @return an array of integers, indicating the CPU cores exclusively
     * reserved for this process. The array will have length zero if no
     * CPU cores are exclusively reserved for this process at this point
     * in time.
     */
    public static final native int[] getExclusiveCores();

    /**
     * Set the priority of the calling thread, based on Linux priorities.  See
     * {@link #setThreadPriority(int, int)} for more information.
     *
     * @param priority A Linux priority level, from -20 for highest scheduling
     * priority to 19 for lowest scheduling priority.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     *
     * @see #setThreadPriority(int, int)
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static final native void setThreadPriority(
            @IntRange(from = -20, to = THREAD_PRIORITY_LOWEST) int priority)
            throws IllegalArgumentException, SecurityException;

    /** @hide */
    public static final void setThreadPriority$ravenwood(int priority) {
        setThreadPriority(myTid(), priority);
    }

    /**
     * Return the current priority of a thread, based on Linux priorities.
     *
     * @param tid The identifier of the thread/process. If tid equals zero, the priority of the
     * calling process/thread will be returned.
     *
     * @return Returns the current priority, as a Linux priority level,
     * from -20 for highest scheduling priority to 19 for lowest scheduling
     * priority.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    @IntRange(from = -20, to = THREAD_PRIORITY_LOWEST)
    public static final native int getThreadPriority(int tid)
            throws IllegalArgumentException;

    /** @hide */
    public static final int getThreadPriority$ravenwood(int tid) {
        final SomeArgs args =
                Preconditions.requireNonNullViaRavenwoodRule(sIdentity$ravenwood).get();
        if (args.argi3 == tid) {
            return args.argi4;
        } else {
            throw new UnsupportedOperationException(
                    "Cross-thread priority management not yet available in Ravenwood");
        }
    }

    /**
     * Return the current scheduling policy of a thread, based on Linux.
     *
     * @param tid The identifier of the thread/process to get the scheduling policy.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist, or if <var>priority</var> is out of range for the policy.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * scheduling policy or priority.
     *
     * {@hide}
     */

    @TestApi
    public static final native int getThreadScheduler(int tid)
            throws IllegalArgumentException;

    /**
     * Set the scheduling policy and priority of a thread, based on Linux.
     *
     * @param tid The identifier of the thread/process to change.
     * @param policy A Linux scheduling policy such as SCHED_OTHER etc.
     * @param priority A Linux priority level in a range appropriate for the given policy.
     *
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist, or if <var>priority</var> is out of range for the policy.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * scheduling policy or priority.
     *
     * {@hide}
     */

    public static final native void setThreadScheduler(int tid, int policy, int priority)
            throws IllegalArgumentException;

    /**
     * Determine whether the current environment supports multiple processes.
     *
     * @return Returns true if the system can run in multiple processes, else
     * false if everything is running in a single process.
     *
     * @deprecated This method always returns true.  Do not use.
     */
    @Deprecated
    public static final boolean supportsProcesses() {
        return true;
    }

    /**
     * Adjust the swappiness level for a process.
     *
     * @param pid The process identifier to set.
     * @param is_increased Whether swappiness should be increased or default.
     *
     * @return Returns true if the underlying system supports this
     *         feature, else false.
     *
     * {@hide}
     */
    public static final native boolean setSwappiness(int pid, boolean is_increased);

    /**
     * Change this process's argv[0] parameter.  This can be useful to show
     * more descriptive information in things like the 'ps' command.
     *
     * @param text The new name of this process.
     *
     * {@hide}
     */
    @UnsupportedAppUsage(maxTargetSdk = VERSION_CODES.S, publicAlternatives = "Do not try to "
            + "change the process name. (If you must, you could use {@code pthread_setname_np(3)}, "
            + "but this could confuse the system)")
    public static void setArgV0(@NonNull String text) {
        sArgV0 = text;
        setArgV0Native(text);
    }

    private static native void setArgV0Native(String text);

    /**
     * Return the name of this process. By default, the process name is the same as the app's
     * package name, but this can be changed using {@code android:process}.
     */
    @NonNull
    public static String myProcessName() {
        // Note this could be different from the actual process name if someone changes the
        // process name using native code (using pthread_setname_np()). But sArgV0
        // is the name that the system thinks this process has.
        return sArgV0;
    }

    /**
     * Kill the process with the given PID.
     * Note that, though this API allows us to request to
     * kill any process based on its PID, the kernel will
     * still impose standard restrictions on which PIDs you
     * are actually able to kill.  Typically this means only
     * the process running the caller's packages/application
     * and any additional processes created by that app; packages
     * sharing a common UID will also be able to kill each
     * other's processes.
     */
    public static final void killProcess(int pid) {
        sendSignal(pid, SIGNAL_KILL);
    }

    /**
     * Check the tgid and tid pair to see if the tid still exists and belong to the tgid.
     *
     * TOCTOU warning: the status of the tid can change at the time this method returns. This should
     * be used in very rare cases such as checking if a (tid, tgid) pair that is known to exist
     * recently no longer exists now. As the possibility of the same tid to be reused under the same
     * tgid during a short window is rare. And even if it happens the caller logic should be robust
     * to handle it without error.
     *
     * @throws IllegalArgumentException if tgid or tid is not positive.
     * @throws SecurityException if the caller doesn't have the permission, this method is expected
     *                           to be used by system process with {@link #SYSTEM_UID} because it
     *                           internally uses tkill(2).
     * @throws NoSuchElementException if the Linux process with pid as the tid has exited or it
     *                                doesn't belong to the tgid.
     * @hide
     */
    public static final void checkTid(int tgid, int tid)
            throws IllegalArgumentException, SecurityException, NoSuchElementException {
        sendTgSignalThrows(tgid, tid, SIGNAL_DEFAULT);
    }

    /**
     * Check if the pid still exists.
     *
     * TOCTOU warning: the status of the pid can change at the time this method returns. This should
     * be used in very rare cases such as checking if a pid that belongs to an isolated process of a
     * uid known to exist recently no longer exists now. As the possibility of the same pid to be
     * reused again under the same uid during a short window is rare. And even if it happens the
     * caller logic should be robust to handle it without error.
     *
     * @throws IllegalArgumentException if pid is not positive.
     * @throws SecurityException if the caller doesn't have the permission, this method is expected
     *                           to be used by system process with {@link #SYSTEM_UID} because it
     *                           internally uses kill(2).
     * @throws NoSuchElementException if the Linux process with the pid has exited.
     * @hide
     */
    public static final void checkPid(int pid)
            throws IllegalArgumentException, SecurityException, NoSuchElementException {
        sendSignalThrows(pid, SIGNAL_DEFAULT);
    }

    /** @hide */
    public static final native int setUid(int uid);

    /** @hide */
    public static final native int setGid(int uid);

    /**
     * Send a signal to the given process.
     *
     * @param pid The pid of the target process.
     * @param signal The signal to send.
     */
    public static final native void sendSignal(int pid, int signal);

    private static native void sendSignalThrows(int pid, int signal)
            throws IllegalArgumentException, SecurityException, NoSuchElementException;

    private static native void sendTgSignalThrows(int pid, int tgid, int signal)
            throws IllegalArgumentException, SecurityException, NoSuchElementException;

    /**
     * @hide
     * Private impl for avoiding a log message...  DO NOT USE without doing
     * your own log, or the Android Illuminati will find you some night and
     * beat you up.
     */
    public static final void killProcessQuiet(int pid) {
        sendSignalQuiet(pid, SIGNAL_KILL);
    }

    /**
     * @hide
     * Private impl for avoiding a log message...  DO NOT USE without doing
     * your own log, or the Android Illuminati will find you some night and
     * beat you up.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static final native void sendSignalQuiet(int pid, int signal);

    /**
     * @return The advertised memory of the system, as the end user would encounter in a retail
     * display environment. If the advertised memory is not defined, it returns
     * {@code getTotalMemory()} rounded.
     *
     * @hide
     */
    public static final long getAdvertisedMem() {
        String formatSize = MemoryProperties.memory_ddr_size().orElse("0KB");
        long memSize = FileUtils.parseSize(formatSize);

        if (memSize <= 0) {
            return FileUtils.roundStorageSize(getTotalMemory());
        }

        return memSize;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static final native long getFreeMemory();

    /** @hide */
    @UnsupportedAppUsage
    public static final native long getTotalMemory();

    /** @hide */
    @UnsupportedAppUsage
    public static final native void readProcLines(String path,
            String[] reqFields, long[] outSizes);

    /** @hide */
    @UnsupportedAppUsage
    public static final native int[] getPids(String path, int[] lastArray);

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_TERM_MASK = 0xff;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_ZERO_TERM = 0;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_SPACE_TERM = (int)' ';
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_TAB_TERM = (int)'\t';
    /** @hide */
    public static final int PROC_NEWLINE_TERM = (int) '\n';
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_COMBINE = 0x100;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_PARENS = 0x200;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_QUOTES = 0x400;
    /** @hide */
    public static final int PROC_CHAR = 0x800;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_OUT_STRING = 0x1000;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_OUT_LONG = 0x2000;
    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROC_OUT_FLOAT = 0x4000;

    /**
     * Read and parse a {@code proc} file in the given format.
     *
     * <p>The format is a list of integers, where every integer describes a variable in the file. It
     * specifies how the variable is syntactically terminated (e.g. {@link Process#PROC_SPACE_TERM},
     * {@link Process#PROC_TAB_TERM}, {@link Process#PROC_ZERO_TERM}, {@link
     * Process#PROC_NEWLINE_TERM}).
     *
     * <p>If the variable should be parsed and returned to the caller, the termination type should
     * be binary OR'd with the type of output (e.g. {@link Process#PROC_OUT_STRING}, {@link
     * Process#PROC_OUT_LONG}, {@link Process#PROC_OUT_FLOAT}.
     *
     * <p>If the variable is wrapped in quotation marks it should be binary OR'd with {@link
     * Process#PROC_QUOTES}. If the variable is wrapped in parentheses it should be binary OR'd with
     * {@link Process#PROC_PARENS}.
     *
     * <p>If the variable is not formatted as a string and should be cast directly from characters
     * to a long, the {@link Process#PROC_CHAR} integer should be binary OR'd.
     *
     * <p>If the terminating character can be repeated, the {@link Process#PROC_COMBINE} integer
     * should be binary OR'd.
     *
     * @param file the path of the {@code proc} file to read
     * @param format the format of the file
     * @param outStrings the parsed {@code String}s from the file
     * @param outLongs the parsed {@code long}s from the file
     * @param outFloats the parsed {@code float}s from the file
     * @hide
     */
    @UnsupportedAppUsage
    public static final native boolean readProcFile(String file, int[] format,
            String[] outStrings, long[] outLongs, float[] outFloats);

    /** @hide */
    @UnsupportedAppUsage
    public static final native boolean parseProcLine(byte[] buffer, int startIndex,
            int endIndex, int[] format, String[] outStrings, long[] outLongs, float[] outFloats);

    /** @hide */
    @UnsupportedAppUsage
    public static final native int[] getPidsForCommands(String[] cmds);

    /**
     * Gets the total Pss value for a given process, in bytes.
     *
     * @param pid the process to the Pss for
     * @return the total Pss value for the given process in bytes,
     *  or -1 if the value cannot be determined
     * @hide
     */
    @UnsupportedAppUsage
    public static final native long getPss(int pid);

    /**
     * Gets the total Rss value for a given process, in bytes.
     *
     * @param pid the process to the Rss for
     * @return an ordered array containing multiple values, they are:
     *  [total_rss, file, anon, swap, shmem].
     *  or NULL if the value cannot be determined
     * @hide
     */
    public static final native long[] getRss(int pid);

    /**
     * Specifies the outcome of having started a process.
     * @hide
     */
    public static final class ProcessStartResult {
        /**
         * The PID of the newly started process.
         * Always >= 0.  (If the start failed, an exception will have been thrown instead.)
         */
        public int pid;

        /**
         * True if the process was started with a wrapper attached.
         */
        public boolean usingWrapper;
    }

    /**
     * Kill all processes in a process group started for the given
     * pid.
     * @hide
     */
    public static final native int killProcessGroup(int uid, int pid);

    /**
     * Send a signal to all processes in a group under the given PID, but do not wait for the
     * processes to be fully cleaned up, or for the cgroup to be removed before returning.
     * Callers should also ensure that killProcessGroup is called later to ensure the cgroup is
     * fully removed, otherwise system resources may leak.
     * @hide
     */
    public static final native boolean sendSignalToProcessGroup(int uid, int pid, int signal);

    /**
      * Freeze the cgroup for the given UID.
      * This cgroup may contain child cgroups which will also be frozen. If this cgroup or its
      * children contain processes with Binder interfaces, those interfaces should be frozen before
      * the cgroup to avoid blocking synchronous callers indefinitely.
      *
      * @param uid The UID to be frozen
      * @param freeze true = freeze; false = unfreeze
      * @hide
      */
    public static final native void freezeCgroupUid(int uid, boolean freeze);

    /**
     * Remove all process groups.  Expected to be called when ActivityManager
     * is restarted.
     * @hide
     */
    public static final native void removeAllProcessGroups();

    /**
     * Check to see if a thread belongs to a given process. This may require
     * more permissions than apps generally have.
     * @return true if this thread belongs to a process
     * @hide
     */
    public static final boolean isThreadInProcess(int tid, int pid) {
        StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
        try {
            if (Os.access("/proc/" + tid + "/task/" + pid, OsConstants.F_OK)) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            return false;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }

    }

    /**
     * Wait for the death of the given process.
     *
     * @param pid The process ID to be waited on
     * @param timeout The maximum time to wait in milliseconds, or -1 to wait forever
     * @hide
     */
    public static void waitForProcessDeath(int pid, int timeout)
            throws InterruptedException, TimeoutException {
        boolean fallback = supportsPidFd();
        if (!fallback) {
            FileDescriptor pidfd = null;
            try {
                final int fd = nativePidFdOpen(pid, 0);
                if (fd >= 0) {
                    pidfd = new FileDescriptor();
                    pidfd.setInt$(fd);
                } else {
                    fallback = true;
                }
                if (pidfd != null) {
                    StructPollfd[] fds = new StructPollfd[] {
                        new StructPollfd()
                    };
                    fds[0].fd = pidfd;
                    fds[0].events = (short) OsConstants.POLLIN;
                    fds[0].revents = 0;
                    fds[0].userData = null;
                    int res = Os.poll(fds, timeout);
                    if (res > 0) {
                        return;
                    } else if (res == 0) {
                        throw new TimeoutException();
                    } else {
                        // We should get an ErrnoException now
                    }
                }
            } catch (ErrnoException e) {
                if (e.errno == OsConstants.EINTR) {
                    throw new InterruptedException();
                }
                fallback = true;
            } finally {
                if (pidfd != null) {
                    IoUtils.closeQuietly(pidfd);
                }
            }
        }
        if (fallback) {
            boolean infinity = timeout < 0;
            long now = System.currentTimeMillis();
            final long end = now + timeout;
            while (infinity || now < end) {
                try {
                    Os.kill(pid, 0);
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.ESRCH) {
                        return;
                    }
                }
                Thread.sleep(1);
                now = System.currentTimeMillis();
            }
        }
        throw new TimeoutException();
    }

    /**
     * Determine whether the system supports pidfd APIs
     *
     * @return Returns true if the system supports pidfd APIs
     * @hide
     */
    public static boolean supportsPidFd() {
        if (sPidFdSupported == PIDFD_UNKNOWN) {
            int fd = -1;
            try {
                fd = nativePidFdOpen(myPid(), 0);
                sPidFdSupported = PIDFD_SUPPORTED;
            } catch (ErrnoException e) {
                sPidFdSupported = e.errno != OsConstants.ENOSYS
                        ? PIDFD_SUPPORTED : PIDFD_UNSUPPORTED;
            } finally {
                if (fd >= 0) {
                    final FileDescriptor f = new FileDescriptor();
                    f.setInt$(fd);
                    IoUtils.closeQuietly(f);
                }
            }
        }
        return sPidFdSupported == PIDFD_SUPPORTED;
    }

    /**
     * Open process file descriptor for given pid.
     *
     * @param pid The process ID to open for
     * @param flags Reserved, unused now, must be 0
     * @return The process file descriptor for given pid
     * @throws IOException if it can't be opened
     *
     * @hide
     */
    public static @Nullable FileDescriptor openPidFd(int pid, int flags) throws IOException {
        if (!supportsPidFd()) {
            return null;
        }
        if (flags != 0) {
            throw new IllegalArgumentException();
        }
        try {
            FileDescriptor pidfd = new FileDescriptor();
            pidfd.setInt$(nativePidFdOpen(pid, flags));
            return pidfd;
        } catch (ErrnoException e) {
            IOException ex = new IOException();
            ex.initCause(e);
            throw ex;
        }
    }

    private static native int nativePidFdOpen(int pid, int flags) throws ErrnoException;
}
