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

import static com.android.internal.os.ZygoteConnectionConstants.MAX_ZYGOTE_ARGC;

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

import dalvik.system.ZygoteHooks;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStreamReader;

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
     * @ApplicationInfo.ApiEnforcementPolicy values.
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

    /** No external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_NONE = IVold.REMOUNT_MODE_NONE;
    /** Default external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_DEFAULT = IVold.REMOUNT_MODE_DEFAULT;
    /** Read-only external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_READ = IVold.REMOUNT_MODE_READ;
    /** Read-write external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_WRITE = IVold.REMOUNT_MODE_WRITE;
    /**
     * Mount mode for apps that are already installed on the device before the isolated_storage
     * feature is enabled.
     */
    public static final int MOUNT_EXTERNAL_LEGACY = IVold.REMOUNT_MODE_LEGACY;
    /**
     * Mount mode for package installers which should give them access to
     * all obb dirs in addition to their package sandboxes
     */
    public static final int MOUNT_EXTERNAL_INSTALLER = IVold.REMOUNT_MODE_INSTALLER;
    /** Read-write external storage should be mounted instead of package sandbox */
    public static final int MOUNT_EXTERNAL_FULL = IVold.REMOUNT_MODE_FULL;

    /** Number of bytes sent to the Zygote over blastula pipes or the pool event FD */
    public static final int BLASTULA_MANAGEMENT_MESSAGE_BYTES = 8;

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

    /** a prototype instance for a future List.toArray() */
    protected static final int[][] INT_ARRAY_2D = new int[0][0];

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
    public static final String BLASTULA_POOL_PRIMARY_SOCKET_NAME = "blastula_pool";

    /**
     * @hide for internal use only
     */
    public static final String BLASTULA_POOL_SECONDARY_SOCKET_NAME = "blastula_pool_secondary";

    private Zygote() {}

    /** Called for some security initialization before any fork. */
    static native void nativeSecurityInit();

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
     *
     * @return 0 if this is the child, pid of the child
     * if this is the parent, or -1 on error.
     */
    public static int forkAndSpecialize(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, int mountExternal, String seInfo, String niceName, int[] fdsToClose,
            int[] fdsToIgnore, boolean startChildZygote, String instructionSet, String appDataDir,
            String packageName, String[] packagesForUID, String sandboxId) {
        ZygoteHooks.preFork();
        // Resets nice priority for zygote process.
        resetNicePriority();
        int pid = nativeForkAndSpecialize(
                uid, gid, gids, runtimeFlags, rlimits, mountExternal, seInfo, niceName, fdsToClose,
                fdsToIgnore, startChildZygote, instructionSet, appDataDir, packageName,
                packagesForUID, sandboxId);
        // Enable tracing as soon as possible for the child process.
        if (pid == 0) {
            Trace.setTracingEnabled(true, runtimeFlags);

            // Note that this event ends at the end of handleChildProc,
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "PostFork");
        }
        ZygoteHooks.postForkCommon();
        return pid;
    }

    private static native int nativeForkAndSpecialize(int uid, int gid, int[] gids,
            int runtimeFlags, int[][] rlimits, int mountExternal, String seInfo, String niceName,
            int[] fdsToClose, int[] fdsToIgnore, boolean startChildZygote, String instructionSet,
            String appDataDir, String packageName, String[] packagesForUID,
            String sandboxId);

    /**
     * Specialize a Blastula instance.  The current VM must have been started
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
     */
    public static void specializeBlastula(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, int mountExternal, String seInfo, String niceName,
            boolean startChildZygote, String instructionSet, String appDataDir, String packageName,
            String[] packagesForUID, String sandboxId) {

        nativeSpecializeBlastula(uid, gid, gids, runtimeFlags, rlimits, mountExternal, seInfo,
                                 niceName, startChildZygote, instructionSet, appDataDir,
                                 packageName, packagesForUID, sandboxId);

        // Enable tracing as soon as possible for the child process.
        Trace.setTracingEnabled(true, runtimeFlags);

        // Note that this event ends at the end of handleChildProc.
        Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "PostFork");

        /*
         * This is called here (instead of after the fork but before the specialize) to maintain
         * consistancy with the code paths for forkAndSpecialize.
         *
         * TODO (chriswailes): Look into moving this to immediately after the fork.
         */
        ZygoteHooks.postForkCommon();
    }

    private static native void nativeSpecializeBlastula(int uid, int gid, int[] gids,
            int runtimeFlags, int[][] rlimits, int mountExternal, String seInfo, String niceName,
            boolean startChildZygote, String instructionSet, String appDataDir, String packageName,
            String[] packagesForUID, String sandboxId);

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
    public static int forkSystemServer(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities) {
        ZygoteHooks.preFork();
        // Resets nice priority for zygote process.
        resetNicePriority();
        int pid = nativeForkSystemServer(
                uid, gid, gids, runtimeFlags, rlimits,
                permittedCapabilities, effectiveCapabilities);
        // Enable tracing as soon as we enter the system_server.
        if (pid == 0) {
            Trace.setTracingEnabled(true, runtimeFlags);
        }
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
    protected static void allowAppFilesAcrossFork(ApplicationInfo appInfo) {
        Zygote.nativeAllowFileAcrossFork(appInfo.sourceDir);
        if (appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) {
                Zygote.nativeAllowFileAcrossFork(path);
            }
        }
        // As well as its shared libs
        if (appInfo.sharedLibraryFiles != null) {
            for (String path : appInfo.sharedLibraryFiles) {
                Zygote.nativeAllowFileAcrossFork(path);
            }
        }
    }

    /**
     * Installs a seccomp filter that limits setresuid()/setresgid() to the passed-in range
     * @param uidGidMin The smallest allowed uid/gid
     * @param uidGidMax The largest allowed uid/gid
     */
    native protected static void nativeInstallSeccompUidGidFilter(int uidGidMin, int uidGidMax);

    /**
     * Zygote unmount storage space on initializing.
     * This method is called once.
     */
    protected static native void nativeUnmountStorageOnInit();

    /**
     * Get socket file descriptors (opened by init) from the environment and
     * store them for access from native code later.
     *
     * @param isPrimary  True if this is the zygote process, false if it is zygote_secondary
     */
    public static void getSocketFDs(boolean isPrimary) {
        nativeGetSocketFDs(isPrimary);
    }

    protected static native void nativeGetSocketFDs(boolean isPrimary);

    /**
     * Returns the raw string value of a system property.
     *
     * Note that Device Config is not available without an application so SystemProperties is used
     * instead.
     *
     * TODO (chriswailes): Cache the system property location in native code and then write a JNI
     *                     function to fetch it.
     */
    public static String getSystemProperty(String propertyName, String defaultValue) {
        return SystemProperties.get(
                String.join(".",
                        "persist.device_config",
                        DeviceConfig.RuntimeNative.NAMESPACE,
                        propertyName),
                defaultValue);
    }

    protected static void emptyBlastulaPool() {
        nativeEmptyBlastulaPool();
    }

    private static native void nativeEmptyBlastulaPool();

    /**
     * Returns the value of a system property converted to a boolean using specific logic.
     *
     * Note that Device Config is not available without an application so SystemProperties is used
     * instead.
     *
     * @see SystemProperties.getBoolean
     *
     * TODO (chriswailes): Cache the system property location in native code and then write a JNI
     *                     function to fetch it.
     */
    public static boolean getSystemPropertyBoolean(String propertyName, Boolean defaultValue) {
        return SystemProperties.getBoolean(
                String.join(".",
                        "persist.device_config",
                        DeviceConfig.RuntimeNative.NAMESPACE,
                        propertyName),
                defaultValue);
    }

    /**
     * @return Number of blastulas currently in the pool
     */
    static int getBlastulaPoolCount() {
        return nativeGetBlastulaPoolCount();
    }

    private static native int nativeGetBlastulaPoolCount();

    /**
     * @return The event FD used for communication between the signal handler and the ZygoteServer
     *         poll loop
     */
    static FileDescriptor getBlastulaPoolEventFD() {
        FileDescriptor fd = new FileDescriptor();
        fd.setInt$(nativeGetBlastulaPoolEventFD());

        return fd;
    }

    private static native int nativeGetBlastulaPoolEventFD();

    /**
     * Fork a new blastula process from the zygote
     *
     * @param sessionSocketRawFDs  Anonymous session sockets that are currently open
     * @return In the Zygote process this function will always return null; in blastula processes
     *         this function will return a Runnable object representing the new application that is
     *         passed up from blastulaMain.
     */
    static Runnable forkBlastula(LocalServerSocket blastulaPoolSocket,
                                 int[] sessionSocketRawFDs) {
        FileDescriptor[] pipeFDs = null;

        try {
            pipeFDs = Os.pipe2(O_CLOEXEC);
        } catch (ErrnoException errnoEx) {
            throw new IllegalStateException("Unable to create blastula pipe.", errnoEx);
        }

        int pid =
                nativeForkBlastula(pipeFDs[0].getInt$(), pipeFDs[1].getInt$(), sessionSocketRawFDs);

        if (pid == 0) {
            IoUtils.closeQuietly(pipeFDs[0]);
            return blastulaMain(blastulaPoolSocket, pipeFDs[1]);
        } else {
            // The read-end of the pipe will be closed by the native code.
            // See removeBlastulaTableEntry();
            IoUtils.closeQuietly(pipeFDs[1]);
            return null;
        }
    }

    private static native int nativeForkBlastula(int readPipeFD,
                                                 int writePipeFD,
                                                 int[] sessionSocketRawFDs);

    /**
     * This function is used by blastulas to wait for specialization requests from the system
     * server.
     *
     * @param writePipe  The write end of the reporting pipe used to communicate with the poll loop
     *                   of the ZygoteServer.
     * @return A runnable oject representing the new application.
     */
    private static Runnable blastulaMain(LocalServerSocket blastulaPoolSocket,
                                         FileDescriptor writePipe) {
        final int pid = Process.myPid();

        LocalSocket sessionSocket = null;
        DataOutputStream blastulaOutputStream = null;
        Credentials peerCredentials = null;
        ZygoteArguments args = null;

        while (true) {
            try {
                sessionSocket = blastulaPoolSocket.accept();

                BufferedReader blastulaReader =
                        new BufferedReader(new InputStreamReader(sessionSocket.getInputStream()));
                blastulaOutputStream =
                        new DataOutputStream(sessionSocket.getOutputStream());

                peerCredentials = sessionSocket.getPeerCredentials();

                String[] argStrings = readArgumentList(blastulaReader);

                if (argStrings != null) {
                    args = new ZygoteArguments(argStrings);

                    // TODO (chriswailes): Should this only be run for debug builds?
                    validateBlastulaCommand(args);
                    break;
                } else {
                    Log.e("Blastula", "Truncated command received.");
                    IoUtils.closeQuietly(sessionSocket);
                }
            } catch (Exception ex) {
                Log.e("Blastula", ex.getMessage());
                IoUtils.closeQuietly(sessionSocket);
            }
        }

        applyUidSecurityPolicy(args, peerCredentials);
        applyDebuggerSystemProperty(args);

        int[][] rlimits = null;

        if (args.mRLimits != null) {
            rlimits = args.mRLimits.toArray(INT_ARRAY_2D);
        }

        // This must happen before the SELinux policy for this process is
        // changed when specializing.
        try {
            // Used by ZygoteProcess.zygoteSendArgsAndGetResult to fill in a
            // Process.ProcessStartResult object.
            blastulaOutputStream.writeInt(pid);
        } catch (IOException ioEx) {
            Log.e("Blastula", "Failed to write response to session socket: " + ioEx.getMessage());
            System.exit(-1);
        } finally {
            IoUtils.closeQuietly(sessionSocket);
            IoUtils.closeQuietly(blastulaPoolSocket);
        }

        try {
            ByteArrayOutputStream buffer =
                    new ByteArrayOutputStream(Zygote.BLASTULA_MANAGEMENT_MESSAGE_BYTES);
            DataOutputStream outputStream = new DataOutputStream(buffer);

            // This is written as a long so that the blastula reporting pipe and blastula pool
            // event FD handlers in ZygoteServer.runSelectLoop can be unified.  These two cases
            // should both send/receive 8 bytes.
            outputStream.writeLong(pid);
            outputStream.flush();

            Os.write(writePipe, buffer.toByteArray(), 0, buffer.size());
        } catch (Exception ex) {
            Log.e("Blastula",
                    String.format("Failed to write PID (%d) to pipe (%d): %s",
                            pid, writePipe.getInt$(), ex.getMessage()));
            System.exit(-1);
        } finally {
            IoUtils.closeQuietly(writePipe);
        }

        specializeBlastula(args.mUid, args.mGid, args.mGids,
                           args.mRuntimeFlags, rlimits, args.mMountExternal,
                           args.mSeInfo, args.mNiceName, args.mStartChildZygote,
                           args.mInstructionSet, args.mAppDataDir, args.mPackageName,
                           args.mPackagesForUid, args.mSandboxId);

        if (args.mNiceName != null) {
            Process.setArgV0(args.mNiceName);
        }

        // End of the postFork event.
        Trace.traceEnd(Trace.TRACE_TAG_ACTIVITY_MANAGER);

        return ZygoteInit.zygoteInit(args.mTargetSdkVersion,
                                     args.mRemainingArgs,
                                     null /* classLoader */);
    }

    private static final String BLASTULA_ERROR_PREFIX = "Invalid command to blastula: ";

    /**
     * Checks a set of zygote arguments to see if they can be handled by a blastula.  Throws an
     * exception if an invalid arugment is encountered.
     * @param args  The arguments to test
     */
    private static void validateBlastulaCommand(ZygoteArguments args) {
        if (args.mAbiListQuery) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--query-abi-list");
        } else if (args.mPidQuery) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--get-pid");
        } else if (args.mPreloadDefault) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--preload-default");
        } else if (args.mPreloadPackage != null) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--preload-package");
        } else if (args.mPreloadApp != null) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--preload-app");
        } else if (args.mStartChildZygote) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--start-child-zygote");
        } else if (args.mApiBlacklistExemptions != null) {
            throw new IllegalArgumentException(
                BLASTULA_ERROR_PREFIX + "--set-api-blacklist-exemptions");
        } else if (args.mHiddenApiAccessLogSampleRate != -1) {
            throw new IllegalArgumentException(
                BLASTULA_ERROR_PREFIX + "--hidden-api-log-sampling-rate=");
        } else if (args.mInvokeWith != null) {
            throw new IllegalArgumentException(BLASTULA_ERROR_PREFIX + "--invoke-with");
        } else if (args.mPermittedCapabilities != 0 || args.mEffectiveCapabilities != 0) {
            throw new ZygoteSecurityException("Client may not specify capabilities: "
                + "permitted=0x" + Long.toHexString(args.mPermittedCapabilities)
                + ", effective=0x" + Long.toHexString(args.mEffectiveCapabilities));
        }
    }

    /**
     * @return  Raw file descriptors for the read-end of blastula reporting pipes.
     */
    protected static int[] getBlastulaPipeFDs() {
        return nativeGetBlastulaPipeFDs();
    }

    private static native int[] nativeGetBlastulaPipeFDs();

    /**
     * Remove the blastula table entry for the provided process ID.
     *
     * @param blastulaPID  Process ID of the entry to remove
     * @return True if the entry was removed; false if it doesn't exist
     */
    protected static boolean removeBlastulaTableEntry(int blastulaPID) {
        return nativeRemoveBlastulaTableEntry(blastulaPID);
    }

    private static native boolean nativeRemoveBlastulaTableEntry(int blastulaPID);

    /**
     * uid 1000 (Process.SYSTEM_UID) may specify any uid &gt; 1000 in normal
     * operation. It may also specify any gid and setgroups() list it chooses.
     * In factory test mode, it may specify any UID.
     *
     * @param args non-null; zygote spawner arguments
     * @param peer non-null; peer credentials
     * @throws ZygoteSecurityException
     */
    protected static void applyUidSecurityPolicy(ZygoteArguments args, Credentials peer)
            throws ZygoteSecurityException {

        if (peer.getUid() == Process.SYSTEM_UID) {
            /* In normal operation, SYSTEM_UID can only specify a restricted
             * set of UIDs. In factory test mode, SYSTEM_UID may specify any uid.
             */
            boolean uidRestricted = FactoryTest.getMode() == FactoryTest.FACTORY_TEST_OFF;

            if (uidRestricted && args.mUidSpecified && (args.mUid < Process.SYSTEM_UID)) {
                throw new ZygoteSecurityException(
                        "System UID may not launch process with UID < "
                        + Process.SYSTEM_UID);
            }
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
    protected static void applyDebuggerSystemProperty(ZygoteArguments args) {
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
     * @throws ZygoteSecurityException
     */
    protected static void applyInvokeWithSecurityPolicy(ZygoteArguments args, Credentials peer)
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
     * Applies invoke-with system properties to the zygote arguments.
     *
     * @param args non-null; zygote args
     */
    protected static void applyInvokeWithSystemProperty(ZygoteArguments args) {
        if (args.mInvokeWith == null && args.mNiceName != null) {
            String property = "wrap." + args.mNiceName;
            args.mInvokeWith = SystemProperties.get(property);
            if (args.mInvokeWith != null && args.mInvokeWith.length() == 0) {
                args.mInvokeWith = null;
            }
        }
    }

    /**
     * Reads an argument list from the provided socket
     * @return Argument list or null if EOF is reached
     * @throws IOException passed straight through
     */
    static String[] readArgumentList(BufferedReader socketReader) throws IOException {
        int argc;

        try {
            String argc_string = socketReader.readLine();

            if (argc_string == null) {
                // EOF reached.
                return null;
            }
            argc = Integer.parseInt(argc_string);

        } catch (NumberFormatException ex) {
            Log.e("Zygote", "Invalid Zygote wire format: non-int at argc");
            throw new IOException("Invalid wire format");
        }

        // See bug 1092107: large argc can be used for a DOS attack
        if (argc > MAX_ZYGOTE_ARGC) {
            throw new IOException("Max arg count exceeded");
        }

        String[] args = new String[argc];
        for (int arg_index = 0; arg_index < argc; arg_index++) {
            args[arg_index] = socketReader.readLine();
            if (args[arg_index] == null) {
                // We got an unexpected EOF.
                throw new IOException("Truncated request");
            }
        }

        return args;
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

    private static void callPostForkSystemServerHooks() {
        // SystemServer specific post fork hooks run before child post fork hooks.
        ZygoteHooks.postForkSystemServer();
    }

    private static void callPostForkChildHooks(int runtimeFlags, boolean isSystemServer,
            boolean isZygote, String instructionSet) {
        ZygoteHooks.postForkChild(runtimeFlags, isSystemServer, isZygote, instructionSet);
    }

    /**
     * Resets the calling thread priority to the default value (Thread.NORM_PRIORITY
     * or nice value 0). This updates both the priority value in java.lang.Thread and
     * the nice value (setpriority).
     */
    static void resetNicePriority() {
        Thread.currentThread().setPriority(Thread.NORM_PRIORITY);
    }

    /**
     * Executes "/system/bin/sh -c &lt;command&gt;" using the exec() system call.
     * This method throws a runtime exception if exec() failed, otherwise, this
     * method never returns.
     *
     * @param command The shell command to execute.
     */
    public static void execShell(String command) {
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
    public static void appendQuotedShellArgs(StringBuilder command, String[] args) {
        for (String arg : args) {
            command.append(" '").append(arg.replace("'", "'\\''")).append("'");
        }
    }
}
