/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.os;

import static android.system.OsConstants.O_CLOEXEC;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ApplicationInfo;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.FactoryTest;
import android.os.IVold;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.DeviceConfig;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import com.android.internal.net.NetworkUtilsInternal;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import dalvik.system.ZygoteHooks;

import libcore.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;

/** @hide */
public final class Zygote {
    /*
    * Bit values for "runtimeFlags" argument.  The definitions are duplicated
    * in the native code.
    */

    /** enable debugging over JDWP */
    public static final int DEBUG_ENABLE_JDWP   = 1;
    /** enable JNI checks */
    public static final int DEBUG_ENABLE_CHECKJNI   = 1 << 1;
    /** enable Java programming language "assert" statements */
    public static final int DEBUG_ENABLE_ASSERT     = 1 << 2;
    /** disable the AOT compiler and JIT */
    public static final int DEBUG_ENABLE_SAFEMODE   = 1 << 3;
    /** Enable logging of third-party JNI activity. */
    public static final int DEBUG_ENABLE_JNI_LOGGING = 1 << 4;
    /** Force generation of native debugging information. */
    public static final int DEBUG_GENERATE_DEBUG_INFO = 1 << 5;
    /** Always use JIT-ed code. */
    public static final int DEBUG_ALWAYS_JIT = 1 << 6;
    /** Make the code native debuggable by turning off some optimizations. */
    public static final int DEBUG_NATIVE_DEBUGGABLE = 1 << 7;
    /** Make the code Java debuggable by turning off some optimizations. */
    public static final int DEBUG_JAVA_DEBUGGABLE = 1 << 8;

    /** Turn off the verifier. */
    public static final int DISABLE_VERIFIER = 1 << 9;
    /** Only use oat files located in /system. Otherwise use dex/jar/apk . */
    public static final int ONLY_USE_SYSTEM_OAT_FILES = 1 << 10;
    /** Force generation of native debugging information for backtraces. */
    public static final int DEBUG_GENERATE_MINI_DEBUG_INFO = 1 << 11;
    /**
     * Hidden API access restrictions. This is a mask for bits representing the API enforcement
     * policy, defined by {@code @ApplicationInfo.HiddenApiEnforcementPolicy}.
     */
    public static final int API_ENFORCEMENT_POLICY_MASK = (1 << 12) | (1 << 13);
    /**
     * Bit shift for use with {@link #API_ENFORCEMENT_POLICY_MASK}.
     *
     * (flags & API_ENFORCEMENT_POLICY_MASK) >> API_ENFORCEMENT_POLICY_SHIFT gives
     * {@link ApplicationInfo.HiddenApiEnforcementPolicy} values.
     */
    public static final int API_ENFORCEMENT_POLICY_SHIFT =
            Integer.numberOfTrailingZeros(API_ENFORCEMENT_POLICY_MASK);
    /**
     * Enable system server ART profiling.
     */
    public static final int PROFILE_SYSTEM_SERVER = 1 << 14;

    /**
     * Enable profiling from shell.
     */
    public static final int PROFILE_FROM_SHELL = 1 << 15;

    /*
     * Enable using the ART app image startup cache
     */
    public static final int USE_APP_IMAGE_STARTUP_CACHE = 1 << 16;

    /**
     * When set, application specified signal handlers are not chained (i.e, ignored)
     * by the runtime.
     *
     * Used for debugging only. Usage: set debug.ignoreappsignalhandler to 1.
     */
    public static final int DEBUG_IGNORE_APP_SIGNAL_HANDLER = 1 << 17;

    /**
     * Disable runtime access to {@link android.annotation.TestApi} annotated members.
     *
     * <p>This only takes effect if Hidden API access restrictions are enabled as well.
     */
    public static final int DISABLE_TEST_API_ENFORCEMENT_POLICY = 1 << 18;

    public static final int MEMORY_TAG_LEVEL_MASK = (1 << 19) | (1 << 20);

    public static final int MEMORY_TAG_LEVEL_NONE = 0;
    /**
     * Enable pointer tagging in this process.
     * Tags are checked during memory deallocation, but not on access.
     * TBI stands for Top-Byte-Ignore, an ARM CPU feature.
     * {@link https://developer.arm.com/docs/den0024/latest/the-memory-management-unit/translation-table-configuration/virtual-address-tagging}
     */
    public static final int MEMORY_TAG_LEVEL_TBI = 1 << 19;

    /**
     * Enable asynchronous memory tag checks in this process.
     */
    public static final int MEMORY_TAG_LEVEL_ASYNC = 2 << 19;

    /**
     * Enable synchronous memory tag checks in this process.
     */
    public static final int MEMORY_TAG_LEVEL_SYNC = 3 << 19;

    /**
     * A two-bit field for GWP-ASan level of this process. See the possible values below.
     */
    public static final int GWP_ASAN_LEVEL_MASK = (1 << 21) | (1 << 22);

    /**
     * Disable GWP-ASan in this process.
     * GWP-ASan is a low-overhead memory bug detector using guard pages on a small
     * subset of heap allocations.
     */
    public static final int GWP_ASAN_LEVEL_NEVER = 0 << 21;

    /**
     * Enable GWP-ASan in this process with a small sampling rate.
     * With approx. 1% chance GWP-ASan will be activated and apply its protection
     * to a small subset of heap allocations.
     * Otherwise (~99% chance) this process is unaffected.
     */
    public static final int GWP_ASAN_LEVEL_LOTTERY = 1 << 21;

    /**
     * Always enable GWP-ASan in this process.
     * GWP-ASan is activated unconditionally (but still, only a small subset of
     * allocations is protected).
     */
    public static final int GWP_ASAN_LEVEL_ALWAYS = 2 << 21;

    /**
     * Enable automatic zero-initialization of native heap memory allocations.
     */
    public static final int NATIVE_HEAP_ZERO_INIT = 1 << 23;

    /** No external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_NONE = IVold.REMOUNT_MODE_NONE;
    /** Default external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_DEFAULT = IVold.REMOUNT_MODE_DEFAULT;
    /**
     * Mount mode for package installers which should give them access to
     * all obb dirs in addition to their package sandboxes
     */
    public static final int MOUNT_EXTERNAL_INSTALLER = IVold.REMOUNT_MODE_INSTALLER;
    /** The lower file system should be bind mounted directly on external storage */
    public static final int MOUNT_EXTERNAL_PASS_THROUGH = IVold.REMOUNT_MODE_PASS_THROUGH;

    /** Use the regular scoped storage filesystem, but Android/ should be writable.
     * Used to support the applications hosting DownloadManager and the MTP server.
     */
    public static final int MOUNT_EXTERNAL_ANDROID_WRITABLE = IVold.REMOUNT_MODE_ANDROID_WRITABLE;

    /** Number of bytes sent to the Zygote over USAP pipes or the pool event FD */
    static final int USAP_MANAGEMENT_MESSAGE_BYTES = 8;

    /** Make the new process have top application priority. */
    public static final String START_AS_TOP_APP_ARG = "--is-top-app";

    /** List of packages with the same uid, and its app data info: volume uuid and inode. */
    public static final String PKG_DATA_INFO_MAP = "--pkg-data-info-map";

    /** List of allowlisted packages and its app data info: volume uuid and inode. */
    public static final String WHITELISTED_DATA_INFO_MAP = "--whitelisted-data-info-map";

    /** Bind mount app storage dirs to lower fs not via fuse */
    public static final String BIND_MOUNT_APP_STORAGE_DIRS = "--bind-mount-storage-dirs";

    /** Bind mount app storage dirs to lower fs not via fuse */
    public static final String BIND_MOUNT_APP_DATA_DIRS = "--bind-mount-data-dirs";

    /**
     * An extraArg passed when a zygote process is forking a child-zygote, specifying a name
     * in the abstract socket namespace. This socket name is what the new child zygote
     * should listen for connections on.
     */
    public static final String CHILD_ZYGOTE_SOCKET_NAME_ARG = "--zygote-socket=";

    /**
     * An extraArg passed when a zygote process is forking a child-zygote, specifying the
     * requested ABI for the child Zygote.
     */
    public static final String CHILD_ZYGOTE_ABI_LIST_ARG = "--abi-list=";

    /**
     * An extraArg passed when a zygote process is forking a child-zygote, specifying the
     * start of the UID range the children of the Zygote may setuid()/setgid() to. This
     * will be enforced with a seccomp filter.
     */
    public static final String CHILD_ZYGOTE_UID_RANGE_START = "--uid-range-start=";

    /**
     * An extraArg passed when a zygote process is forking a child-zygote, specifying the
     * end of the UID range the children of the Zygote may setuid()/setgid() to. This
     * will be enforced with a seccomp filter.
     */
    public static final String CHILD_ZYGOTE_UID_RANGE_END = "--uid-range-end=";

    private static final String TAG = "Zygote";

    /** Prefix prepended to socket names created by init */
    private static final String ANDROID_SOCKET_PREFIX = "ANDROID_SOCKET_";

    /**
     * The duration to wait before re-checking Zygote related system properties.
     *
     * One minute in milliseconds.
     */
    public static final long PROPERTY_CHECK_INTERVAL = 60000;

    /**
     * @hide for internal use only
     */
    public static final int SOCKET_BUFFER_SIZE = 256;

    /**
     * @hide for internal use only
     */
    private static final int PRIORITY_MAX = -20;

    /** a prototype instance for a future List.toArray() */
    static final int[][] INT_ARRAY_2D = new int[0][0];

    /**
     * @hide for internal use only.
     */
    public static final String PRIMARY_SOCKET_NAME = "zygote";

    /**
     * @hide for internal use only.
     */
    public static final String SECONDARY_SOCKET_NAME = "zygote_secondary";

    /**
     * @hide for internal use only
     */
    public static final String USAP_POOL_PRIMARY_SOCKET_NAME = "usap_pool_primary";

    /**
     * @hide for internal use only
     */
    public static final String USAP_POOL_SECONDARY_SOCKET_NAME = "usap_pool_secondary";

    private Zygote() {}

    private static boolean containsInetGid(int[] gids) {
        for (int i = 0; i < gids.length; i++) {
            if (gids[i] == android.os.Process.INET_GID) return true;
        }
        return false;
    }

    /**
     * Forks a new VM instance.  The current VM must have been started
     * with the -Xzygote flag. <b>NOTE: new instance keeps all
     * root capabilities. The new process is expected to call capset()</b>.
     *
     * @param uid the UNIX uid that the new process should setuid() to after
     * fork()ing and and before spawning any threads.
     * @param gid the UNIX gid that the new process should setgid() to after
     * fork()ing and and before spawning any threads.
     * @param gids null-ok; a list of UNIX gids that the new process should
     * setgroups() to after fork and before spawning any threads.
     * @param runtimeFlags bit flags that enable ART features.
     * @param rlimits null-ok an array of rlimit tuples, with the second
     * dimension having a length of 3 and representing
     * (resource, rlim_cur, rlim_max). These are set via the posix
     * setrlimit(2) call.
     * @param seInfo null-ok a string specifying SELinux information for
     * the new process.
     * @param niceName null-ok a string specifying the process name.
     * @param fdsToClose an array of ints, holding one or more POSIX
     * file descriptor numbers that are to be closed by the child
     * (and replaced by /dev/null) after forking.  An integer value
     * of -1 in any entry in the array means "ignore this one".
     * @param fdsToIgnore null-ok an array of ints, either null or holding
     * one or more POSIX file descriptor numbers that are to be ignored
     * in the file descriptor table check.
     * @param startChildZygote if true, the new child process will itself be a
     * new zygote process.
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     * @param isTopApp true if the process is for top (high priority) application.
     * @param pkgDataInfoList A list that stores related packages and its app data
     * info: volume uuid and inode.
     * @param whitelistedDataInfoList Like pkgDataInfoList, but it's for allowlisted apps.
     * @param bindMountAppDataDirs  True if the zygote needs to mount data dirs.
     * @param bindMountAppStorageDirs  True if the zygote needs to mount storage dirs.
     *
     * @return 0 if this is the child, pid of the child
     * if this is the parent, or -1 on error.
     */
    static int forkAndSpecialize(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, int mountExternal, String seInfo, String niceName, int[] fdsToClose,
            int[] fdsToIgnore, boolean startChildZygote, String instructionSet, String appDataDir,
            boolean isTopApp, String[] pkgDataInfoList, String[] whitelistedDataInfoList,
            boolean bindMountAppDataDirs, boolean bindMountAppStorageDirs) {
        ZygoteHooks.preFork();

        int pid = nativeForkAndSpecialize(
                uid, gid, gids, runtimeFlags, rlimits, mountExternal, seInfo, niceName, fdsToClose,
                fdsToIgnore, startChildZygote, instructionSet, appDataDir, isTopApp,
                pkgDataInfoList, whitelistedDataInfoList, bindMountAppDataDirs,
                bindMountAppStorageDirs);
        if (pid == 0) {
            // Note that this event ends at the end of handleChildProc,
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "PostFork");

            // If no GIDs were specified, don't make any permissions changes based on groups.
            if (gids != null && gids.length > 0) {
                NetworkUtilsInternal.setAllowNetworkingForProcess(containsInetGid(gids));
            }
        }

        // Set the Java Language thread priority to the default value for new apps.
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

        ZygoteHooks.postForkCommon();
        return pid;
    }

    private static native int nativeForkAndSpecialize(int uid, int gid, int[] gids,
            int runtimeFlags, int[][] rlimits, int mountExternal, String seInfo, String niceName,
            int[] fdsToClose, int[] fdsToIgnore, boolean startChildZygote, String instructionSet,
            String appDataDir, boolean isTopApp, String[] pkgDataInfoList,
            String[] whitelistedDataInfoList, boolean bindMountAppDataDirs,
            boolean bindMountAppStorageDirs);

    /**
     * Specialize an unspecialized app process.  The current VM must have been started
     * with the -Xzygote flag.
     *
     * @param uid  The UNIX uid that the new process should setuid() to before spawning any threads
     * @param gid  The UNIX gid that the new process should setgid() to before spawning any threads
     * @param gids null-ok;  A list of UNIX gids that the new process should
     * setgroups() to before spawning any threads
     * @param runtimeFlags  Bit flags that enable ART features
     * @param rlimits null-ok  An array of rlimit tuples, with the second
     * dimension having a length of 3 and representing
     * (resource, rlim_cur, rlim_max). These are set via the posix
     * setrlimit(2) call.
     * @param seInfo null-ok  A string specifying SELinux information for
     * the new process.
     * @param niceName null-ok  A string specifying the process name.
     * @param startChildZygote  If true, the new child process will itself be a
     * new zygote process.
     * @param instructionSet null-ok  The instruction set to use.
     * @param appDataDir null-ok  The data directory of the app.
     * @param isTopApp  True if the process is for top (high priority) application.
     * @param pkgDataInfoList A list that stores related packages and its app data
     * volume uuid and CE dir inode. For example, pkgDataInfoList = [app_a_pkg_name,
     * app_a_data_volume_uuid, app_a_ce_inode, app_b_pkg_name, app_b_data_volume_uuid,
     * app_b_ce_inode, ...];
     * @param whitelistedDataInfoList Like pkgDataInfoList, but it's for allowlisted apps.
     * @param bindMountAppDataDirs  True if the zygote needs to mount data dirs.
     * @param bindMountAppStorageDirs  True if the zygote needs to mount storage dirs.
     */
    private static void specializeAppProcess(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, int mountExternal, String seInfo, String niceName,
            boolean startChildZygote, String instructionSet, String appDataDir, boolean isTopApp,
            String[] pkgDataInfoList, String[] whitelistedDataInfoList,
            boolean bindMountAppDataDirs, boolean bindMountAppStorageDirs) {
        nativeSpecializeAppProcess(uid, gid, gids, runtimeFlags, rlimits, mountExternal, seInfo,
                niceName, startChildZygote, instructionSet, appDataDir, isTopApp,
                pkgDataInfoList, whitelistedDataInfoList,
                bindMountAppDataDirs, bindMountAppStorageDirs);

        // Note that this event ends at the end of handleChildProc.
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "PostFork");

        if (gids != null && gids.length > 0) {
            NetworkUtilsInternal.setAllowNetworkingForProcess(containsInetGid(gids));
        }

        // Set the Java Language thread priority to the default value for new apps.
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

        /*
         * This is called here (instead of after the fork but before the specialize) to maintain
         * consistancy with the code paths for forkAndSpecialize.
         *
         * TODO (chriswailes): Look into moving this to immediately after the fork.
         */
        ZygoteHooks.postForkCommon();
    }

    private static native void nativeSpecializeAppProcess(int uid, int gid, int[] gids,
            int runtimeFlags, int[][] rlimits, int mountExternal, String seInfo, String niceName,
            boolean startChildZygote, String instructionSet, String appDataDir, boolean isTopApp,
            String[] pkgDataInfoList, String[] whitelistedDataInfoList,
            boolean bindMountAppDataDirs, boolean bindMountAppStorageDirs);

    /**
     * Called to do any initialization before starting an application.
     */
    static native void nativePreApplicationInit();

    /**
     * Special method to start the system server process. In addition to the
     * common actions performed in forkAndSpecialize, the pid of the child
     * process is recorded such that the death of the child process will cause
     * zygote to exit.
     *
     * @param uid the UNIX uid that the new process should setuid() to after
     * fork()ing and and before spawning any threads.
     * @param gid the UNIX gid that the new process should setgid() to after
     * fork()ing and and before spawning any threads.
     * @param gids null-ok; a list of UNIX gids that the new process should
     * setgroups() to after fork and before spawning any threads.
     * @param runtimeFlags bit flags that enable ART features.
     * @param rlimits null-ok an array of rlimit tuples, with the second
     * dimension having a length of 3 and representing
     * (resource, rlim_cur, rlim_max). These are set via the posix
     * setrlimit(2) call.
     * @param permittedCapabilities argument for setcap()
     * @param effectiveCapabilities argument for setcap()
     *
     * @return 0 if this is the child, pid of the child
     * if this is the parent, or -1 on error.
     */
    static int forkSystemServer(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities) {
        ZygoteHooks.preFork();

        int pid = nativeForkSystemServer(
                uid, gid, gids, runtimeFlags, rlimits,
                permittedCapabilities, effectiveCapabilities);

        // Set the Java Language thread priority to the default value for new apps.
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);

        ZygoteHooks.postForkCommon();
        return pid;
    }

    private static native int nativeForkSystemServer(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities);

    /**
     * Lets children of the zygote inherit open file descriptors to this path.
     */
    protected static native void nativeAllowFileAcrossFork(String path);

    /**
     * Lets children of the zygote inherit open file descriptors that belong to the
     * ApplicationInfo that is passed in.
     *
     * @param appInfo ApplicationInfo of the application
     */
    static void allowAppFilesAcrossFork(ApplicationInfo appInfo) {
        for (String path : appInfo.getAllApkPaths()) {
            Zygote.nativeAllowFileAcrossFork(path);
        }
    }

    /**
     * Installs a seccomp filter that limits setresuid()/setresgid() to the passed-in range
     * @param uidGidMin The smallest allowed uid/gid
     * @param uidGidMax The largest allowed uid/gid
     */
    native protected static void nativeInstallSeccompUidGidFilter(int uidGidMin, int uidGidMax);

    /**
     * Initialize the native state of the Zygote.  This inclues
     *   - Fetching socket FDs from the environment
     *   - Initializing security properties
     *   - Unmounting storage as appropriate
     *   - Loading necessary performance profile information
     *
     * @param isPrimary  True if this is the zygote process, false if it is zygote_secondary
     */
    static void initNativeState(boolean isPrimary) {
        nativeInitNativeState(isPrimary);
    }

    protected static native void nativeInitNativeState(boolean isPrimary);

    /**
     * Returns the raw string value of a system property.
     *
     * Note that Device Config is not available without an application so SystemProperties is used
     * instead.
     *
     * TODO (chriswailes): Cache the system property location in native code and then write a JNI
     *                     function to fetch it.
     */
    public static String getConfigurationProperty(String propertyName, String defaultValue) {
        return SystemProperties.get(
                String.join(".",
                        "persist.device_config",
                        DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
                        propertyName),
                defaultValue);
    }

    static void emptyUsapPool() {
        nativeEmptyUsapPool();
    }

    private static native void nativeEmptyUsapPool();

    /**
     * Returns the value of a system property converted to a boolean using specific logic.
     *
     * Note that Device Config is not available without an application so SystemProperties is used
     * instead.
     *
     * @see SystemProperties#getBoolean
     *
     * TODO (chriswailes): Cache the system property location in native code and then write a JNI
     *                     function to fetch it.
     * TODO (chriswailes): Move into ZygoteConfig.java once the necessary CL lands (go/ag/6580627)
     */
    public static boolean getConfigurationPropertyBoolean(
            String propertyName, Boolean defaultValue) {
        return SystemProperties.getBoolean(
                String.join(".",
                        "persist.device_config",
                        DeviceConfig.NAMESPACE_RUNTIME_NATIVE,
                        propertyName),
                defaultValue);
    }

    /**
     * @return Number of unspecialized app processes currently in the pool
     */
    static int getUsapPoolCount() {
        return nativeGetUsapPoolCount();
    }

    private static native int nativeGetUsapPoolCount();

    /**
     * @return The event FD used for communication between the signal handler and the ZygoteServer
     *         poll loop
     */
    static FileDescriptor getUsapPoolEventFD() {
        FileDescriptor fd = new FileDescriptor();
        fd.setInt$(nativeGetUsapPoolEventFD());

        return fd;
    }

    private static native int nativeGetUsapPoolEventFD();

    /**
     * Fork a new unspecialized app process from the zygote. Adds the Usap to the native
     * Usap table.
     *
     * @param usapPoolSocket  The server socket the USAP will call accept on
     * @param sessionSocketRawFDs  Anonymous session sockets that are currently open.
     *         These are closed in the child.
     * @param isPriorityFork Raise the initial process priority level because this is on the
     *         critical path for application startup.
     * @return In the child process, this returns a Runnable that waits for specialization
     *         info to start an app process. In the sygote/parent process this returns null.
     */
    static @Nullable Runnable forkUsap(LocalServerSocket usapPoolSocket,
                                       int[] sessionSocketRawFDs,
                                       boolean isPriorityFork) {
        FileDescriptor readFD;
        FileDescriptor writeFD;

        try {
            FileDescriptor[] pipeFDs = Os.pipe2(O_CLOEXEC);
            readFD = pipeFDs[0];
            writeFD = pipeFDs[1];
        } catch (ErrnoException errnoEx) {
            throw new IllegalStateException("Unable to create USAP pipe.", errnoEx);
        }

        int pid = nativeForkApp(readFD.getInt$(), writeFD.getInt$(),
                                sessionSocketRawFDs, /*argsKnown=*/ false, isPriorityFork);
        if (pid == 0) {
            IoUtils.closeQuietly(readFD);
            return childMain(null, usapPoolSocket, writeFD);
        } else if (pid == -1) {
            // Fork failed.
            return null;
        } else {
            // readFD will be closed by the native code. See removeUsapTableEntry();
            IoUtils.closeQuietly(writeFD);
            nativeAddUsapTableEntry(pid, readFD.getInt$());
            return null;
        }
    }

    private static native int nativeForkApp(int readPipeFD,
                                            int writePipeFD,
                                            int[] sessionSocketRawFDs,
                                            boolean argsKnown,
                                            boolean isPriorityFork);

    /**
     * Add an entry for a new Usap to the table maintained in native code.
     */
    @CriticalNative
    private static native void nativeAddUsapTableEntry(int pid, int readPipeFD);

    /**
     * Fork a new app process from the zygote. argBuffer contains a fork command that
     * request neither a child zygote, nor a wrapped process. Continue to accept connections
     * on the specified socket, use those to refill argBuffer, and continue to process
     * sufficiently simple fork requests. We presume that the only open file descriptors
     * requiring special treatment are the session socket embedded in argBuffer, and
     * zygoteSocket.
     * @param argBuffer containing initial command and the connected socket from which to
     *         read more
     * @param zygoteSocket socket from which to obtain new connections when current argBuffer
     *         one is disconnected
     * @param expectedUId Uid of peer for initial requests. Subsequent requests from a different
     *               peer will cause us to return rather than perform the requested fork.
     * @param minUid Minimum Uid enforced for all but first fork request. The caller checks
     *               the Uid policy for the initial request.
     * @param firstNiceName name of first created process. Used for error reporting only.
     * @return A Runnable in each child process, null in the parent.
     * If this returns in then argBuffer still contains a command needing to be executed.
     */
    static @Nullable Runnable forkSimpleApps(@NonNull ZygoteCommandBuffer argBuffer,
                                             @NonNull FileDescriptor zygoteSocket,
                                             int expectedUid,
                                             int minUid,
                                             @Nullable String firstNiceName) {
        boolean in_child =
                argBuffer.forkRepeatedly(zygoteSocket, expectedUid, minUid, firstNiceName);
        if (in_child) {
            return childMain(argBuffer, /*usapPoolSocket=*/null, /*writePipe=*/null);
        } else {
            return null;
        }
    }

    /**
     * Specialize the current process into one described by argBuffer or the command read from
     * usapPoolSocket. Exactly one of those must be null. If we are given an argBuffer, we close
     * it. Used both for a specializing a USAP process, and for process creation without USAPs.
     * In both cases, we specialize the process after first returning to Java code.
     *
     * @param writePipe  The write end of the reporting pipe used to communicate with the poll loop
     *                   of the ZygoteServer.
     * @return A runnable oject representing the new application.
     */
    private static Runnable childMain(@Nullable ZygoteCommandBuffer argBuffer,
                                      @Nullable LocalServerSocket usapPoolSocket,
                                      FileDescriptor writePipe) {
        final int pid = Process.myPid();

        DataOutputStream usapOutputStream = null;
        ZygoteArguments args = null;

        // Block SIGTERM so we won't be killed if the Zygote flushes the USAP pool.
        blockSigTerm();

        LocalSocket sessionSocket = null;
        if (argBuffer == null) {
            // Read arguments from usapPoolSocket instead.

            Process.setArgV0(Process.is64Bit() ? "usap64" : "usap32");

            // Change the priority to max before calling accept so we can respond to new
            // specialization requests as quickly as possible.  This will be reverted to the
            // default priority in the native specialization code.
            boostUsapPriority();

            while (true) {
                ZygoteCommandBuffer tmpArgBuffer = null;
                try {
                    sessionSocket = usapPoolSocket.accept();

                    usapOutputStream =
                            new DataOutputStream(sessionSocket.getOutputStream());
                    Credentials peerCredentials = sessionSocket.getPeerCredentials();
                    tmpArgBuffer = new ZygoteCommandBuffer(sessionSocket);
                    args = ZygoteArguments.getInstance(argBuffer);
                    applyUidSecurityPolicy(args, peerCredentials);
                    // TODO (chriswailes): Should this only be run for debug builds?
                    validateUsapCommand(args);
                    break;
                } catch (Exception ex) {
                    Log.e("USAP", ex.getMessage());
                }
                // Re-enable SIGTERM so the USAP can be flushed from the pool if necessary.
                unblockSigTerm();
                IoUtils.closeQuietly(sessionSocket);
                IoUtils.closeQuietly(tmpArgBuffer);
                blockSigTerm();
            }
        } else {
            try {
                args = ZygoteArguments.getInstance(argBuffer);
            } catch (Exception ex) {
                Log.e("AppStartup", ex.getMessage());
                throw new AssertionError("Failed to parse application start command", ex);
            }
            // peerCredentials were checked in parent.
        }
        if (args == null) {
            throw new AssertionError("Empty command line");
        }
        try {
            // SIGTERM is blocked here.  This prevents a USAP that is specializing from being
            // killed during a pool flush.

            applyDebuggerSystemProperty(args);

            int[][] rlimits = null;

            if (args.mRLimits != null) {
                rlimits = args.mRLimits.toArray(INT_ARRAY_2D);
            }

            if (argBuffer == null) {
                // This must happen before the SELinux policy for this process is
                // changed when specializing.
                try {
                    // Used by ZygoteProcess.zygoteSendArgsAndGetResult to fill in a
                    // Process.ProcessStartResult object.
                    usapOutputStream.writeInt(pid);
                } catch (IOException ioEx) {
                    Log.e("USAP", "Failed to write response to session socket: "
                            + ioEx.getMessage());
                    throw new RuntimeException(ioEx);
                } finally {
                    try {
                        // Since the raw FD is created by init and then loaded from an environment
                        // variable (as opposed to being created by the LocalSocketImpl itself),
                        // the LocalSocket/LocalSocketImpl does not own the Os-level socket. See
                        // the spec for LocalSocket.createConnectedLocalSocket(FileDescriptor fd).
                        // Thus closing the LocalSocket does not suffice. See b/130309968 for more
                        // discussion.
                        FileDescriptor fd = usapPoolSocket.getFileDescriptor();
                        usapPoolSocket.close();
                        Os.close(fd);
                    } catch (ErrnoException | IOException ex) {
                        Log.e("USAP", "Failed to close USAP pool socket");
                        throw new RuntimeException(ex);
                    }
                }
            }

            if (writePipe != null) {
                try {
                    ByteArrayOutputStream buffer =
                            new ByteArrayOutputStream(Zygote.USAP_MANAGEMENT_MESSAGE_BYTES);
                    DataOutputStream outputStream = new DataOutputStream(buffer);

                    // This is written as a long so that the USAP reporting pipe and USAP pool
                    // event FD handlers in ZygoteServer.runSelectLoop can be unified.  These two
                    // cases should both send/receive 8 bytes.
                    // TODO: Needs tweaking to handle the non-Usap invoke-with case, which expects
                    // a different format.
                    outputStream.writeLong(pid);
                    outputStream.flush();
                    Os.write(writePipe, buffer.toByteArray(), 0, buffer.size());
                } catch (Exception ex) {
                    Log.e("USAP",
                            String.format("Failed to write PID (%d) to pipe (%d): %s",
                                    pid, writePipe.getInt$(), ex.getMessage()));
                    throw new RuntimeException(ex);
                } finally {
                    IoUtils.closeQuietly(writePipe);
                }
            }

            specializeAppProcess(args.mUid, args.mGid, args.mGids,
                                 args.mRuntimeFlags, rlimits, args.mMountExternal,
                                 args.mSeInfo, args.mNiceName, args.mStartChildZygote,
                                 args.mInstructionSet, args.mAppDataDir, args.mIsTopApp,
                                 args.mPkgDataInfoList, args.mWhitelistedDataInfoList,
                                 args.mBindMountAppDataDirs, args.mBindMountAppStorageDirs);

            Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

            return ZygoteInit.zygoteInit(args.mTargetSdkVersion,
                                         args.mDisabledCompatChanges,
                                         args.mRemainingArgs,
                                         null /* classLoader */);
        } finally {
            // Unblock SIGTERM to restore the process to default behavior.
            unblockSigTerm();
        }
    }

    private static void blockSigTerm() {
        nativeBlockSigTerm();
    }

    private static native void nativeBlockSigTerm();

    private static void unblockSigTerm() {
        nativeUnblockSigTerm();
    }

    private static native void nativeUnblockSigTerm();

    private static void boostUsapPriority() {
        nativeBoostUsapPriority();
    }

    private static native void nativeBoostUsapPriority();

    static void setAppProcessName(ZygoteArguments args, String loggingTag) {
        if (args.mNiceName != null) {
            Process.setArgV0(args.mNiceName);
        } else if (args.mPackageName != null) {
            Process.setArgV0(args.mPackageName);
        } else {
            Log.w(loggingTag, "Unable to set package name.");
        }
    }

    private static final String USAP_ERROR_PREFIX = "Invalid command to USAP: ";

    /**
     * Checks a set of zygote arguments to see if they can be handled by a USAP.  Throws an
     * exception if an invalid arugment is encountered.
     * @param args  The arguments to test
     */
    private static void validateUsapCommand(ZygoteArguments args) {
        if (args.mAbiListQuery) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--query-abi-list");
        } else if (args.mPidQuery) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--get-pid");
        } else if (args.mPreloadDefault) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--preload-default");
        } else if (args.mPreloadPackage != null) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--preload-package");
        } else if (args.mPreloadApp != null) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--preload-app");
        } else if (args.mStartChildZygote) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--start-child-zygote");
        } else if (args.mApiDenylistExemptions != null) {
            throw new IllegalArgumentException(
                    USAP_ERROR_PREFIX + "--set-api-denylist-exemptions");
        } else if (args.mHiddenApiAccessLogSampleRate != -1) {
            throw new IllegalArgumentException(
                    USAP_ERROR_PREFIX + "--hidden-api-log-sampling-rate=");
        } else if (args.mHiddenApiAccessStatslogSampleRate != -1) {
            throw new IllegalArgumentException(
                    USAP_ERROR_PREFIX + "--hidden-api-statslog-sampling-rate=");
        } else if (args.mInvokeWith != null) {
            throw new IllegalArgumentException(USAP_ERROR_PREFIX + "--invoke-with");
        } else if (args.mPermittedCapabilities != 0 || args.mEffectiveCapabilities != 0) {
            throw new ZygoteSecurityException("Client may not specify capabilities: "
                + "permitted=0x" + Long.toHexString(args.mPermittedCapabilities)
                + ", effective=0x" + Long.toHexString(args.mEffectiveCapabilities));
        }
    }

    /**
     * @return  Raw file descriptors for the read-end of USAP reporting pipes.
     */
    static int[] getUsapPipeFDs() {
        return nativeGetUsapPipeFDs();
    }

    private static native int[] nativeGetUsapPipeFDs();

    /**
     * Remove the USAP table entry for the provided process ID.
     *
     * @param usapPID  Process ID of the entry to remove
     * @return True if the entry was removed; false if it doesn't exist
     */
    static boolean removeUsapTableEntry(int usapPID) {
        return nativeRemoveUsapTableEntry(usapPID);
    }

    @CriticalNative
    private static native boolean nativeRemoveUsapTableEntry(int usapPID);

    /**
     * Return the minimum child uid that the given peer is allowed to create.
     * uid 1000 (Process.SYSTEM_UID) may specify any uid &ge; 1000 in normal
     * operation. It may also specify any gid and setgroups() list it chooses.
     * In factory test mode, it may specify any UID.
     */
    static int minChildUid(Credentials peer) {
        if (peer.getUid() == Process.SYSTEM_UID
                && FactoryTest.getMode() == FactoryTest.FACTORY_TEST_OFF) {
            /* In normal operation, SYSTEM_UID can only specify a restricted
             * set of UIDs. In factory test mode, SYSTEM_UID may specify any uid.
             */
            return Process.SYSTEM_UID;
        } else {
            return 0;
        }
    }

    /*
     * Adjust uid and gid arguments, ensuring that the security policy is satisfied.
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException Indicates a security issue when applying the UID based
     *  security policies
     */
    static void applyUidSecurityPolicy(ZygoteArguments args, Credentials peer)
            throws ZygoteSecurityException {

        if (args.mUidSpecified && (args.mUid < minChildUid(peer))) {
            throw new ZygoteSecurityException(
                    "System UID may not launch process with UID < "
                    + Process.SYSTEM_UID);
        }

        // If not otherwise specified, uid and gid are inherited from peer
        if (!args.mUidSpecified) {
            args.mUid = peer.getUid();
            args.mUidSpecified = true;
        }
        if (!args.mGidSpecified) {
            args.mGid = peer.getGid();
            args.mGidSpecified = true;
        }
    }

    /**
     * Applies debugger system properties to the zygote arguments.
     *
     * If "ro.debuggable" is "1", all apps are debuggable. Otherwise,
     * the debugger state is specified via the "--enable-jdwp" flag
     * in the spawn request.
     *
     * @param args non-null; zygote spawner args
     */
    static void applyDebuggerSystemProperty(ZygoteArguments args) {
        if (RoSystemProperties.DEBUGGABLE) {
            args.mRuntimeFlags |= Zygote.DEBUG_ENABLE_JDWP;
        }
    }

    /**
     * Applies zygote security policy.
     * Based on the credentials of the process issuing a zygote command:
     * <ol>
     * <li> uid 0 (root) may specify --invoke-with to launch Zygote with a
     * wrapper command.
     * <li> Any other uid may not specify any invoke-with argument.
     * </ul>
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException Thrown when `--invoke-with` is specified for a non-debuggable
     *  application.
     */
    static void applyInvokeWithSecurityPolicy(ZygoteArguments args, Credentials peer)
            throws ZygoteSecurityException {
        int peerUid = peer.getUid();

        if (args.mInvokeWith != null && peerUid != 0
                && (args.mRuntimeFlags & Zygote.DEBUG_ENABLE_JDWP) == 0) {
            throw new ZygoteSecurityException("Peer is permitted to specify an "
                + "explicit invoke-with wrapper command only for debuggable "
                + "applications.");
        }
    }

    /**
     * Gets the wrap property if set.
     *
     * @param appName the application name to check
     * @return value of wrap property or null if property not set or
     * null if app_name is null or null if app_name is empty
     */
    public static String getWrapProperty(String appName) {
        if (appName == null || appName.isEmpty()) {
            return null;
        }

        String propertyValue = SystemProperties.get("wrap." + appName);
        if (propertyValue != null && !propertyValue.isEmpty()) {
            return propertyValue;
        }
        return null;
    }

    /**
     * Applies invoke-with system properties to the zygote arguments.
     *
     * @param args non-null; zygote args
     */
    static void applyInvokeWithSystemProperty(ZygoteArguments args) {
        if (args.mInvokeWith == null) {
            args.mInvokeWith = getWrapProperty(args.mNiceName);
        }
    }

    /**
     * Creates a managed LocalServerSocket object using a file descriptor
     * created by an init.rc script.  The init scripts that specify the
     * sockets name can be found in system/core/rootdir.  The socket is bound
     * to the file system in the /dev/sockets/ directory, and the file
     * descriptor is shared via the ANDROID_SOCKET_<socketName> environment
     * variable.
     */
    static LocalServerSocket createManagedSocketFromInitSocket(String socketName) {
        int fileDesc;
        final String fullSocketName = ANDROID_SOCKET_PREFIX + socketName;

        try {
            String env = System.getenv(fullSocketName);
            fileDesc = Integer.parseInt(env);
        } catch (RuntimeException ex) {
            throw new RuntimeException("Socket unset or invalid: " + fullSocketName, ex);
        }

        try {
            FileDescriptor fd = new FileDescriptor();
            fd.setInt$(fileDesc);
            return new LocalServerSocket(fd);
        } catch (IOException ex) {
            throw new RuntimeException(
                "Error building socket from file descriptor: " + fileDesc, ex);
        }
    }

    // This function is called from native code in com_android_internal_os_Zygote.cpp
    @SuppressWarnings("unused")
    private static void callPostForkSystemServerHooks(int runtimeFlags) {
        // SystemServer specific post fork hooks run before child post fork hooks.
        ZygoteHooks.postForkSystemServer(runtimeFlags);
    }

    // This function is called from native code in com_android_internal_os_Zygote.cpp
    @SuppressWarnings("unused")
    private static void callPostForkChildHooks(int runtimeFlags, boolean isSystemServer,
            boolean isZygote, String instructionSet) {
        ZygoteHooks.postForkChild(runtimeFlags, isSystemServer, isZygote, instructionSet);
    }

    /**
     * Executes "/system/bin/sh -c &lt;command&gt;" using the exec() system call.
     * This method throws a runtime exception if exec() failed, otherwise, this
     * method never returns.
     *
     * @param command The shell command to execute.
     */
    static void execShell(String command) {
        String[] args = { "/system/bin/sh", "-c", command };
        try {
            Os.execv(args[0], args);
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Appends quotes shell arguments to the specified string builder.
     * The arguments are quoted using single-quotes, escaped if necessary,
     * prefixed with a space, and appended to the command.
     *
     * @param command A string builder for the shell command being constructed.
     * @param args An array of argument strings to be quoted and appended to the command.
     * @see #execShell(String)
     */
    static void appendQuotedShellArgs(StringBuilder command, String[] args) {
        for (String arg : args) {
            command.append(" '").append(arg.replace("'", "'\\''")).append("'");
        }
    }

    /**
     * Parse the given unsolicited zygote message as type SIGCHLD,
     * extract the payload information into the given output buffer.
     *
     * @param in The unsolicited zygote message to be parsed
     * @param length The number of bytes in the message
     * @param out The output buffer where the payload information will be placed
     * @return Number of elements being place into output buffer, or -1 if
     *         either the message is malformed or not the type as expected here.
     *
     * @hide
     */
    @FastNative
    public static native int nativeParseSigChld(byte[] in, int length, int[] out);

    /**
     * Returns whether the hardware supports memory tagging (ARM MTE).
     */
    public static native boolean nativeSupportsMemoryTagging();

    /**
     * Returns whether the kernel supports tagged pointers. Present in the
     * Android Common Kernel from 4.14 and up. By default, you should prefer
     * fully-feature Memory Tagging, rather than the static Tagged Pointers.
     */
    public static native boolean nativeSupportsTaggedPointers();

    /**
     * Returns the current native tagging level, as one of the
     * MEMORY_TAG_LEVEL_* constants. Returns zero if no tagging is present, or
     * we failed to determine the level.
     */
    public static native int nativeCurrentTaggingLevel();
}
