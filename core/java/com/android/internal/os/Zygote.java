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


import dalvik.system.ZygoteHooks;
import android.system.ErrnoException;
import android.system.Os;
import android.os.SystemClock;
import android.util.Slog;

/** @hide */
public final class Zygote {
    private static final String TAG = "Zygote";
    /*
    * Bit values for "debugFlags" argument.  The definitions are duplicated
    * in the native code.
    */

    /** enable debugging over JDWP */
    public static final int DEBUG_ENABLE_DEBUGGER   = 1;
    /** enable JNI checks */
    public static final int DEBUG_ENABLE_CHECKJNI   = 1 << 1;
    /** enable Java programming language "assert" statements */
    public static final int DEBUG_ENABLE_ASSERT     = 1 << 2;
    /** disable the JIT compiler */
    public static final int DEBUG_ENABLE_SAFEMODE   = 1 << 3;
    /** Enable logging of third-party JNI activity. */
    public static final int DEBUG_ENABLE_JNI_LOGGING = 1 << 4;

    /** No external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_NONE = 0;
    /** Single-user external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_SINGLEUSER = 1;
    /** Multi-user external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_MULTIUSER = 2;
    /** All multi-user external storage should be mounted. */
    public static final int MOUNT_EXTERNAL_MULTIUSER_ALL = 3;

    private static final ZygoteHooks VM_HOOKS = new ZygoteHooks();

    private Zygote() {}

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
     * @param debugFlags bit flags that enable debugging features.
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
     * @param instructionSet null-ok the instruction set to use.
     * @param appDataDir null-ok the data directory of the app.
     *
     * @return 0 if this is the child, pid of the child
     * if this is the parent, or -1 on error.
     */
    public static int forkAndSpecialize(int uid, int gid, int[] gids, int debugFlags,
          int[][] rlimits, int mountExternal, String seInfo, String niceName, int[] fdsToClose,
          String instructionSet, String appDataDir) {
        long startTime = SystemClock.elapsedRealtime();
        VM_HOOKS.preFork();
        checkTime(startTime, "Zygote.preFork");
        int pid = nativeForkAndSpecialize(
                  uid, gid, gids, debugFlags, rlimits, mountExternal, seInfo, niceName, fdsToClose,
                  instructionSet, appDataDir);
        checkTime(startTime, "Zygote.nativeForkAndSpecialize");
        VM_HOOKS.postForkCommon();
        checkTime(startTime, "Zygote.postForkCommon");
        return pid;
    }

    native private static int nativeForkAndSpecialize(int uid, int gid, int[] gids,int debugFlags,
          int[][] rlimits, int mountExternal, String seInfo, String niceName, int[] fdsToClose,
          String instructionSet, String appDataDir);

    /**
     * Temporary hack: check time since start time and log if over a fixed threshold.
     *
     */
    private static void checkTime(long startTime, String where) {
        long now = SystemClock.elapsedRealtime();
        if ((now-startTime) > 1000) {
            // If we are taking more than a second, log about it.
            Slog.w(TAG, "Slow operation: " + (now-startTime) + "ms so far, now at " + where);
        }
    }

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
     * @param debugFlags bit flags that enable debugging features.
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
    public static int forkSystemServer(int uid, int gid, int[] gids, int debugFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities) {
        VM_HOOKS.preFork();
        int pid = nativeForkSystemServer(
                uid, gid, gids, debugFlags, rlimits, permittedCapabilities, effectiveCapabilities);
        VM_HOOKS.postForkCommon();
        return pid;
    }

    native private static int nativeForkSystemServer(int uid, int gid, int[] gids, int debugFlags,
            int[][] rlimits, long permittedCapabilities, long effectiveCapabilities);

    private static void callPostForkChildHooks(int debugFlags, String instructionSet) {
        long startTime = SystemClock.elapsedRealtime();
        VM_HOOKS.postForkChild(debugFlags, instructionSet);
        checkTime(startTime, "Zygote.callPostForkChildHooks");
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
