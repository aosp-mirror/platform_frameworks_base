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

import android.os.IVold;
import android.os.Trace;
import android.system.ErrnoException;
import android.system.Os;

import dalvik.system.ZygoteHooks;

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

    /** No external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_NONE = IVold.REMOUNT_MODE_NONE;
    /** Default external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_DEFAULT = IVold.REMOUNT_MODE_DEFAULT;
    /** Read-only external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_READ = IVold.REMOUNT_MODE_READ;
    /** Read-write external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_WRITE = IVold.REMOUNT_MODE_WRITE;

    private static final ZygoteHooks VM_HOOKS = new ZygoteHooks();

    /**
     * An extraArg passed when a zygote process is forking a child-zygote, specifying a name
     * in the abstract socket namespace. This socket name is what the new child zygote
     * should listen for connections on.
     */
    public static final String CHILD_ZYGOTE_SOCKET_NAME_ARG = "--zygote-socket=";

    private Zygote() {}

    /** Called for some security initialization before any fork. */
    native static void nativeSecurityInit();

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
          int[] fdsToIgnore, boolean startChildZygote, String instructionSet, String appDataDir) {
        VM_HOOKS.preFork();
        // Resets nice priority for zygote process.
        resetNicePriority();
        int pid = nativeForkAndSpecialize(
                  uid, gid, gids, runtimeFlags, rlimits, mountExternal, seInfo, niceName, fdsToClose,
                  fdsToIgnore, startChildZygote, instructionSet, appDataDir);
        // Enable tracing as soon as possible for the child process.
        if (pid == 0) {
            Trace.setTracingEnabled(true, runtimeFlags);

            // Note that this event ends at the end of handleChildProc,
            Trace.traceBegin(Trace.TRACE_TAG_ACTIVITY_MANAGER, "PostFork");
        }
        VM_HOOKS.postForkCommon();
        return pid;
    }

    native private static int nativeForkAndSpecialize(int uid, int gid, int[] gids,int runtimeFlags,
          int[][] rlimits, int mountExternal, String seInfo, String niceName, int[] fdsToClose,
          int[] fdsToIgnore, boolean startChildZygote, String instructionSet, String appDataDir);

    /**
     * Called to do any initialization before starting an application.
     */
    native static void nativePreApplicationInit();

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
        VM_HOOKS.preFork();
        // Resets nice priority for zygote process.
        resetNicePriority();
        int pid = nativeForkSystemServer(
                uid, gid, gids, runtimeFlags, rlimits, permittedCapabilities, effectiveCapabilities);
        // Enable tracing as soon as we enter the system_server.
        if (pid == 0) {
            Trace.setTracingEnabled(true, runtimeFlags);
        }
        VM_HOOKS.postForkCommon();
        return pid;
    }

    native private static int nativeForkSystemServer(int uid, int gid, int[] gids, int runtimeFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities);

    /**
     * Lets children of the zygote inherit open file descriptors to this path.
     */
    native protected static void nativeAllowFileAcrossFork(String path);

    /**
     * Zygote unmount storage space on initializing.
     * This method is called once.
     */
    native protected static void nativeUnmountStorageOnInit();

    private static void callPostForkChildHooks(int runtimeFlags, boolean isSystemServer,
            boolean isZygote, String instructionSet) {
        VM_HOOKS.postForkChild(runtimeFlags, isSystemServer, isZygote, instructionSet);
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
