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

import android.net.LocalSocketAddress;
import android.net.LocalSocket;
import android.util.Log;
import dalvik.system.Zygote;

import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.ArrayList;

/*package*/ class ZygoteStartFailedEx extends Exception {
    /**
     * Something prevented the zygote process startup from happening normally
     */

    ZygoteStartFailedEx() {};
    ZygoteStartFailedEx(String s) {super(s);}
    ZygoteStartFailedEx(Throwable cause) {super(cause);}
}

/**
 * Tools for managing OS processes.
 */
public class Process {
    private static final String LOG_TAG = "Process";

    private static final String ZYGOTE_SOCKET = "zygote";

    /**
     * Name of a process for running the platform's media services.
     * {@hide}
     */
    public static final String ANDROID_SHARED_MEDIA = "com.android.process.media";

    /**
     * Name of the process that Google content providers can share.
     * {@hide}
     */
    public static final String GOOGLE_SHARED_APP_CONTENT = "com.google.process.content";

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
     * @hide
     */
    public static final int SHELL_UID = 2000;

    /**
     * Defines the UID/GID for the log group.
     * @hide
     */
    public static final int LOG_UID = 1007;

    /**
     * Defines the UID/GID for the WIFI supplicant process.
     * @hide
     */
    public static final int WIFI_UID = 1010;

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
    public static final int LAST_APPLICATION_UID = 99999;

    /**
     * Defines a secondary group id for access to the bluetooth hardware.
     */
    public static final int BLUETOOTH_GID = 2000;
    
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
     * Default thread group - gets a 'normal' share of the CPU
     * @hide
     */
    public static final int THREAD_GROUP_DEFAULT = 0;

    /**
     * Background non-interactive thread group - All threads in
     * this group are scheduled with a reduced share of the CPU.
     * @hide
     */
    public static final int THREAD_GROUP_BG_NONINTERACTIVE = 1;

    /**
     * Foreground 'boost' thread group - All threads in
     * this group are scheduled with an increased share of the CPU
     * @hide
     **/
    public static final int THREAD_GROUP_FG_BOOST = 2;

    public static final int SIGNAL_QUIT = 3;
    public static final int SIGNAL_KILL = 9;
    public static final int SIGNAL_USR1 = 10;
    
    // State for communicating with zygote process

    static LocalSocket sZygoteSocket;
    static DataInputStream sZygoteInputStream;
    static BufferedWriter sZygoteWriter;

    /** true if previous zygote open failed */
    static boolean sPreviousZygoteOpenFailed;

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
     * @param processClass The class to use as the process's main entry
     *                     point.
     * @param niceName A more readable name to use for the process.
     * @param uid The user-id under which the process will run.
     * @param gid The group-id under which the process will run.
     * @param gids Additional group-ids associated with the process.
     * @param enableDebugger True if debugging should be enabled for this process.
     * @param zygoteArgs Additional arguments to supply to the zygote process.
     * 
     * @return int If > 0 the pid of the new process; if 0 the process is
     *         being emulated by a thread
     * @throws RuntimeException on fatal start failure
     * 
     * {@hide}
     */
    public static final int start(final String processClass,
                                  final String niceName,
                                  int uid, int gid, int[] gids,
                                  int debugFlags,
                                  String[] zygoteArgs)
    {
        if (supportsProcesses()) {
            try {
                return startViaZygote(processClass, niceName, uid, gid, gids,
                        debugFlags, zygoteArgs);
            } catch (ZygoteStartFailedEx ex) {
                Log.e(LOG_TAG,
                        "Starting VM process through Zygote failed");
                throw new RuntimeException(
                        "Starting VM process through Zygote failed", ex);
            }
        } else {
            // Running in single-process mode
            
            Runnable runnable = new Runnable() {
                        public void run() {
                            Process.invokeStaticMain(processClass);
                        }
            };
            
            // Thread constructors must not be called with null names (see spec). 
            if (niceName != null) {
                new Thread(runnable, niceName).start();
            } else {
                new Thread(runnable).start();
            }
            
            return 0;
        }
    }
    
    /**
     * Start a new process.  Don't supply a custom nice name.
     * {@hide}
     */
    public static final int start(String processClass, int uid, int gid,
            int[] gids, int debugFlags, String[] zygoteArgs) {
        return start(processClass, "", uid, gid, gids, 
                debugFlags, zygoteArgs);
    }

    private static void invokeStaticMain(String className) {
        Class cl;
        Object args[] = new Object[1];

        args[0] = new String[0];     //this is argv
   
        try {
            cl = Class.forName(className);
            cl.getMethod("main", new Class[] { String[].class })
                    .invoke(null, args);            
        } catch (Exception ex) {
            // can be: ClassNotFoundException,
            // NoSuchMethodException, SecurityException,
            // IllegalAccessException, IllegalArgumentException
            // InvocationTargetException
            // or uncaught exception from main()

            Log.e(LOG_TAG, "Exception invoking static main on " 
                    + className, ex);

            throw new RuntimeException(ex);
        }

    }

    /** retry interval for opening a zygote socket */
    static final int ZYGOTE_RETRY_MILLIS = 500;

    /**
     * Tries to open socket to Zygote process if not already open. If
     * already open, does nothing.  May block and retry.
     */
    private static void openZygoteSocketIfNeeded() 
            throws ZygoteStartFailedEx {

        int retryCount;

        if (sPreviousZygoteOpenFailed) {
            /*
             * If we've failed before, expect that we'll fail again and
             * don't pause for retries.
             */
            retryCount = 0;
        } else {
            retryCount = 10;            
        }

        /*
         * See bug #811181: Sometimes runtime can make it up before zygote.
         * Really, we'd like to do something better to avoid this condition,
         * but for now just wait a bit...
         */
        for (int retry = 0
                ; (sZygoteSocket == null) && (retry < (retryCount + 1))
                ; retry++ ) {

            if (retry > 0) {
                try {
                    Log.i("Zygote", "Zygote not up yet, sleeping...");
                    Thread.sleep(ZYGOTE_RETRY_MILLIS);
                } catch (InterruptedException ex) {
                    // should never happen
                }
            }

            try {
                sZygoteSocket = new LocalSocket();

                sZygoteSocket.connect(new LocalSocketAddress(ZYGOTE_SOCKET, 
                        LocalSocketAddress.Namespace.RESERVED));

                sZygoteInputStream
                        = new DataInputStream(sZygoteSocket.getInputStream());

                sZygoteWriter =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    sZygoteSocket.getOutputStream()),
                            256);

                Log.i("Zygote", "Process: zygote socket opened");

                sPreviousZygoteOpenFailed = false;
                break;
            } catch (IOException ex) {
                if (sZygoteSocket != null) {
                    try {
                        sZygoteSocket.close();
                    } catch (IOException ex2) {
                        Log.e(LOG_TAG,"I/O exception on close after exception",
                                ex2);
                    }
                }

                sZygoteSocket = null;
            }
        }

        if (sZygoteSocket == null) {
            sPreviousZygoteOpenFailed = true;
            throw new ZygoteStartFailedEx("connect failed");                 
        }
    }

    /**
     * Sends an argument list to the zygote process, which starts a new child
     * and returns the child's pid. Please note: the present implementation
     * replaces newlines in the argument list with spaces.
     * @param args argument list
     * @return PID of new child process
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    private static int zygoteSendArgsAndGetPid(ArrayList<String> args)
            throws ZygoteStartFailedEx {

        int pid;

        openZygoteSocketIfNeeded();

        try {
            /**
             * See com.android.internal.os.ZygoteInit.readArgumentList()
             * Presently the wire format to the zygote process is:
             * a) a count of arguments (argc, in essence)
             * b) a number of newline-separated argument strings equal to count
             *
             * After the zygote process reads these it will write the pid of
             * the child or -1 on failure.
             */

            sZygoteWriter.write(Integer.toString(args.size()));
            sZygoteWriter.newLine();

            int sz = args.size();
            for (int i = 0; i < sz; i++) {
                String arg = args.get(i);
                if (arg.indexOf('\n') >= 0) {
                    throw new ZygoteStartFailedEx(
                            "embedded newlines not allowed");
                }
                sZygoteWriter.write(arg);
                sZygoteWriter.newLine();
            }

            sZygoteWriter.flush();

            // Should there be a timeout on this?
            pid = sZygoteInputStream.readInt();

            if (pid < 0) {
                throw new ZygoteStartFailedEx("fork() failed");
            }
        } catch (IOException ex) {
            try {
                if (sZygoteSocket != null) {
                    sZygoteSocket.close();
                }
            } catch (IOException ex2) {
                // we're going to fail anyway
                Log.e(LOG_TAG,"I/O exception on routine close", ex2);
            }

            sZygoteSocket = null;

            throw new ZygoteStartFailedEx(ex);
        }

        return pid;
    }

    /**
     * Starts a new process via the zygote mechanism.
     *
     * @param processClass Class name whose static main() to run
     * @param niceName 'nice' process name to appear in ps
     * @param uid a POSIX uid that the new process should setuid() to
     * @param gid a POSIX gid that the new process shuold setgid() to
     * @param gids null-ok; a list of supplementary group IDs that the
     * new process should setgroup() to.
     * @param enableDebugger True if debugging should be enabled for this process.
     * @param extraArgs Additional arguments to supply to the zygote process.
     * @return PID
     * @throws ZygoteStartFailedEx if process start failed for any reason
     */
    private static int startViaZygote(final String processClass,
                                  final String niceName,
                                  final int uid, final int gid,
                                  final int[] gids,
                                  int debugFlags,
                                  String[] extraArgs)
                                  throws ZygoteStartFailedEx {
        int pid;

        synchronized(Process.class) {
            ArrayList<String> argsForZygote = new ArrayList<String>();

            // --runtime-init, --setuid=, --setgid=,
            // and --setgroups= must go first
            argsForZygote.add("--runtime-init");
            argsForZygote.add("--setuid=" + uid);
            argsForZygote.add("--setgid=" + gid);
            if ((debugFlags & Zygote.DEBUG_ENABLE_SAFEMODE) != 0) {
                argsForZygote.add("--enable-safemode");
            }
            if ((debugFlags & Zygote.DEBUG_ENABLE_DEBUGGER) != 0) {
                argsForZygote.add("--enable-debugger");
            }
            if ((debugFlags & Zygote.DEBUG_ENABLE_CHECKJNI) != 0) {
                argsForZygote.add("--enable-checkjni");
            }
            if ((debugFlags & Zygote.DEBUG_ENABLE_ASSERT) != 0) {
                argsForZygote.add("--enable-assert");
            }

            //TODO optionally enable debuger
            //argsForZygote.add("--enable-debugger");

            // --setgroups is a comma-separated list
            if (gids != null && gids.length > 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("--setgroups=");

                int sz = gids.length;
                for (int i = 0; i < sz; i++) {
                    if (i != 0) {
                        sb.append(',');
                    }
                    sb.append(gids[i]);
                }

                argsForZygote.add(sb.toString());
            }

            if (niceName != null) {
                argsForZygote.add("--nice-name=" + niceName);
            }

            argsForZygote.add(processClass);

            if (extraArgs != null) {
                for (String arg : extraArgs) {
                    argsForZygote.add(arg);
                }
            }
            
            pid = zygoteSendArgsAndGetPid(argsForZygote);
        }

        if (pid <= 0) {
            throw new ZygoteStartFailedEx("zygote start failed:" + pid);
        }

        return pid;
    }
    
    /**
     * Returns elapsed milliseconds of the time this process has run.
     * @return  Returns the number of milliseconds this process has return.
     */
    public static final native long getElapsedCpuTime();
    
    /**
     * Returns the identifier of this process, which can be used with
     * {@link #killProcess} and {@link #sendSignal}.
     */
    public static final native int myPid();

    /**
     * Returns the identifier of the calling thread, which be used with
     * {@link #setThreadPriority(int, int)}.
     */
    public static final native int myTid();

    /**
     * Returns the identifier of this process's user.
     */
    public static final native int myUid();

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
    public static final int getUidForPid(int pid) {
        String[] procStatusLabels = { "Uid:" };
        long[] procStatusValues = new long[1];
        procStatusValues[0] = -1;
        Process.readProcLines("/proc/" + pid + "/status", procStatusLabels, procStatusValues);
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
    public static final native void setThreadPriority(int tid, int priority)
            throws IllegalArgumentException, SecurityException;

    /**
     * Sets the scheduling group for a thread.
     * @hide
     * @param tid The indentifier of the thread/process to change.
     * @param group The target group for this thread/process.
     * 
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     */
    public static final native void setThreadGroup(int tid, int group)
            throws IllegalArgumentException, SecurityException;
    /**
     * Sets the scheduling group for a process and all child threads
     * @hide
     * @param pid The indentifier of the process to change.
     * @param group The target group for this process.
     * 
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     * @throws SecurityException Throws SecurityException if your process does
     * not have permission to modify the given thread, or to use the given
     * priority.
     */
    public static final native void setProcessGroup(int pid, int group)
            throws IllegalArgumentException, SecurityException;
    
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
    public static final native void setThreadPriority(int priority)
            throws IllegalArgumentException, SecurityException;
    
    /**
     * Return the current priority of a thread, based on Linux priorities.
     * 
     * @param tid The identifier of the thread/process to change.
     * 
     * @return Returns the current priority, as a Linux priority level,
     * from -20 for highest scheduling priority to 19 for lowest scheduling
     * priority.
     * 
     * @throws IllegalArgumentException Throws IllegalArgumentException if
     * <var>tid</var> does not exist.
     */
    public static final native int getThreadPriority(int tid)
            throws IllegalArgumentException;
    
    /**
     * Determine whether the current environment supports multiple processes.
     * 
     * @return Returns true if the system can run in multiple processes, else
     * false if everything is running in a single process.
     */
    public static final native boolean supportsProcesses();

    /**
     * Set the out-of-memory badness adjustment for a process.
     * 
     * @param pid The process identifier to set.
     * @param amt Adjustment value -- linux allows -16 to +15.
     * 
     * @return Returns true if the underlying system supports this
     *         feature, else false.
     *         
     * {@hide}
     */
    public static final native boolean setOomAdj(int pid, int amt);

    /**
     * Change this process's argv[0] parameter.  This can be useful to show
     * more descriptive information in things like the 'ps' command.
     * 
     * @param text The new name of this process.
     * 
     * {@hide}
     */
    public static final native void setArgV0(String text);

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
    public static final native void sendSignalQuiet(int pid, int signal);
    
    /** @hide */
    public static final native long getFreeMemory();
    
    /** @hide */
    public static final native void readProcLines(String path,
            String[] reqFields, long[] outSizes);
    
    /** @hide */
    public static final native int[] getPids(String path, int[] lastArray);
    
    /** @hide */
    public static final int PROC_TERM_MASK = 0xff;
    /** @hide */
    public static final int PROC_ZERO_TERM = 0;
    /** @hide */
    public static final int PROC_SPACE_TERM = (int)' ';
    /** @hide */
    public static final int PROC_TAB_TERM = (int)'\t';
    /** @hide */
    public static final int PROC_COMBINE = 0x100;
    /** @hide */
    public static final int PROC_PARENS = 0x200;
    /** @hide */
    public static final int PROC_OUT_STRING = 0x1000;
    /** @hide */
    public static final int PROC_OUT_LONG = 0x2000;
    /** @hide */
    public static final int PROC_OUT_FLOAT = 0x4000;
    
    /** @hide */
    public static final native boolean readProcFile(String file, int[] format,
            String[] outStrings, long[] outLongs, float[] outFloats);
    
    /** @hide */
    public static final native boolean parseProcLine(byte[] buffer, int startIndex, 
            int endIndex, int[] format, String[] outStrings, long[] outLongs, float[] outFloats);

    /**
     * Gets the total Pss value for a given process, in bytes.
     * 
     * @param pid the process to the Pss for
     * @return the total Pss value for the given process in bytes,
     *  or -1 if the value cannot be determined 
     * @hide
     */
    public static final native long getPss(int pid);
}
