/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.commands.am;

import static android.app.ActivityManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityManager.RESIZE_MODE_USER;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.IActivityContainer;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IInstrumentationWatcher;
import android.app.Instrumentation;
import android.app.IStopUserCallback;
import android.app.ProfilerInfo;
import android.app.UiAutomationConnection;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageStatsManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AndroidException;
import android.util.ArrayMap;
import android.util.Log;
import android.view.IWindowManager;

import com.android.internal.os.BaseCommand;
import com.android.internal.util.HexDump;
import com.android.internal.util.Preconditions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Am extends BaseCommand {

    private static final String SHELL_PACKAGE_NAME = "com.android.shell";

    // Is the object moving in a positive direction?
    private static final boolean MOVING_FORWARD = true;
    // Is the object moving in the horizontal plan?
    private static final boolean MOVING_HORIZONTALLY = true;
    // Is the object current point great then its target point?
    private static final boolean GREATER_THAN_TARGET = true;
    // Amount we reduce the stack size by when testing a task re-size.
    private static final int STACK_BOUNDS_INSET = 10;

    public static final String NO_CLASS_ERROR_CODE = "Error type 3";
    private IActivityManager mAm;
    private IPackageManager mPm;

    private int mStartFlags = 0;
    private boolean mWaitOption = false;
    private boolean mStopOption = false;

    private int mRepeat = 0;
    private int mUserId;
    private String mReceiverPermission;

    private String mProfileFile;
    private int mSamplingInterval;
    private boolean mAutoStop;
    private int mStackId;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Am()).run(args);
    }

    @Override
    public void onShowUsage(PrintStream out) {
        PrintWriter pw = new PrintWriter(out);
        pw.println(
                "usage: am [subcommand] [options]\n" +
                "usage: am start [-D] [-N] [-W] [-P <FILE>] [--start-profiler <FILE>]\n" +
                "               [--sampling INTERVAL] [-R COUNT] [-S]\n" +
                "               [--track-allocation] [--user <USER_ID> | current] <INTENT>\n" +
                "       am startservice [--user <USER_ID> | current] <INTENT>\n" +
                "       am stopservice [--user <USER_ID> | current] <INTENT>\n" +
                "       am force-stop [--user <USER_ID> | all | current] <PACKAGE>\n" +
                "       am kill [--user <USER_ID> | all | current] <PACKAGE>\n" +
                "       am kill-all\n" +
                "       am broadcast [--user <USER_ID> | all | current] <INTENT>\n" +
                "       am instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]\n" +
                "               [--user <USER_ID> | current]\n" +
                "               [--no-window-animation] [--abi <ABI>] <COMPONENT>\n" +
                "       am profile start [--user <USER_ID> current] [--sampling INTERVAL] <PROCESS> <FILE>\n" +
                "       am profile stop [--user <USER_ID> current] [<PROCESS>]\n" +
                "       am dumpheap [--user <USER_ID> current] [-n] <PROCESS> <FILE>\n" +
                "       am set-debug-app [-w] [--persistent] <PACKAGE>\n" +
                "       am clear-debug-app\n" +
                "       am set-watch-heap <PROCESS> <MEM-LIMIT>\n" +
                "       am clear-watch-heap\n" +
                "       am bug-report [--progress]\n" +
                "       am monitor [--gdb <port>]\n" +
                "       am hang [--allow-restart]\n" +
                "       am restart\n" +
                "       am idle-maintenance\n" +
                "       am screen-compat [on|off] <PACKAGE>\n" +
                "       am package-importance <PACKAGE>\n" +
                "       am to-uri [INTENT]\n" +
                "       am to-intent-uri [INTENT]\n" +
                "       am to-app-uri [INTENT]\n" +
                "       am switch-user <USER_ID>\n" +
                "       am start-user <USER_ID>\n" +
                "       am unlock-user <USER_ID> [TOKEN_HEX]\n" +
                "       am stop-user [-w] [-f] <USER_ID>\n" +
                "       am stack start <DISPLAY_ID> <INTENT>\n" +
                "       am stack movetask <TASK_ID> <STACK_ID> [true|false]\n" +
                "       am stack resize <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>\n" +
                "       am stack resize-animated <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>\n" +
                "       am stack resize-docked-stack <LEFT,TOP,RIGHT,BOTTOM> [<TASK_LEFT,TASK_TOP,TASK_RIGHT,TASK_BOTTOM>]\n" +
                "       am stack size-docked-stack-test: <STEP_SIZE> <l|t|r|b> [DELAY_MS]\n" +
                "       am stack move-top-activity-to-pinned-stack: <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>\n" +
                "       am stack positiontask <TASK_ID> <STACK_ID> <POSITION>\n" +
                "       am stack list\n" +
                "       am stack info <STACK_ID>\n" +
                "       am stack remove <STACK_ID>\n" +
                "       am task lock <TASK_ID>\n" +
                "       am task lock stop\n" +
                "       am task resizeable <TASK_ID> [0 (unresizeable) | 1 (crop_windows) | 2 (resizeable) | 3 (resizeable_and_pipable)]\n" +
                "       am task resize <TASK_ID> <LEFT,TOP,RIGHT,BOTTOM>\n" +
                "       am task drag-task-test <TASK_ID> <STEP_SIZE> [DELAY_MS] \n" +
                "       am task size-task-test <TASK_ID> <STEP_SIZE> [DELAY_MS] \n" +
                "       am get-config\n" +
                "       am suppress-resize-config-changes <true|false>\n" +
                "       am set-inactive [--user <USER_ID>] <PACKAGE> true|false\n" +
                "       am get-inactive [--user <USER_ID>] <PACKAGE>\n" +
                "       am send-trim-memory [--user <USER_ID>] <PROCESS>\n" +
                "               [HIDDEN|RUNNING_MODERATE|BACKGROUND|RUNNING_LOW|MODERATE|RUNNING_CRITICAL|COMPLETE]\n" +
                "       am get-current-user\n" +
                "\n" +
                "am start: start an Activity.  Options are:\n" +
                "    -D: enable debugging\n" +
                "    -N: enable native debugging\n" +
                "    -W: wait for launch to complete\n" +
                "    --start-profiler <FILE>: start profiler and send results to <FILE>\n" +
                "    --sampling INTERVAL: use sample profiling with INTERVAL microseconds\n" +
                "        between samples (use with --start-profiler)\n" +
                "    -P <FILE>: like above, but profiling stops when app goes idle\n" +
                "    -R: repeat the activity launch <COUNT> times.  Prior to each repeat,\n" +
                "        the top activity will be finished.\n" +
                "    -S: force stop the target app before starting the activity\n" +
                "    --track-allocation: enable tracking of object allocations\n" +
                "    --user <USER_ID> | current: Specify which user to run as; if not\n" +
                "        specified then run as the current user.\n" +
                "    --stack <STACK_ID>: Specify into which stack should the activity be put.\n" +
                "\n" +
                "am startservice: start a Service.  Options are:\n" +
                "    --user <USER_ID> | current: Specify which user to run as; if not\n" +
                "        specified then run as the current user.\n" +
                "\n" +
                "am stopservice: stop a Service.  Options are:\n" +
                "    --user <USER_ID> | current: Specify which user to run as; if not\n" +
                "        specified then run as the current user.\n" +
                "\n" +
                "am force-stop: force stop everything associated with <PACKAGE>.\n" +
                "    --user <USER_ID> | all | current: Specify user to force stop;\n" +
                "        all users if not specified.\n" +
                "\n" +
                "am kill: Kill all processes associated with <PACKAGE>.  Only kills.\n" +
                "  processes that are safe to kill -- that is, will not impact the user\n" +
                "  experience.\n" +
                "    --user <USER_ID> | all | current: Specify user whose processes to kill;\n" +
                "        all users if not specified.\n" +
                "\n" +
                "am kill-all: Kill all background processes.\n" +
                "\n" +
                "am broadcast: send a broadcast Intent.  Options are:\n" +
                "    --user <USER_ID> | all | current: Specify which user to send to; if not\n" +
                "        specified then send to all users.\n" +
                "    --receiver-permission <PERMISSION>: Require receiver to hold permission.\n" +
                "\n" +
                "am instrument: start an Instrumentation.  Typically this target <COMPONENT>\n" +
                "  is the form <TEST_PACKAGE>/<RUNNER_CLASS> or only <TEST_PACKAGE> if there \n" +
                "  is only one instrumentation.  Options are:\n" +
                "    -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with\n" +
                "        [-e perf true] to generate raw output for performance measurements.\n" +
                "    -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a\n" +
                "        common form is [-e <testrunner_flag> <value>[,<value>...]].\n" +
                "    -p <FILE>: write profiling data to <FILE>\n" +
                "    -m: Write output as protobuf (machine readable)\n" +
                "    -w: wait for instrumentation to finish before returning.  Required for\n" +
                "        test runners.\n" +
                "    --user <USER_ID> | current: Specify user instrumentation runs in;\n" +
                "        current user if not specified.\n" +
                "    --no-window-animation: turn off window animations while running.\n" +
                "    --abi <ABI>: Launch the instrumented process with the selected ABI.\n"  +
                "        This assumes that the process supports the selected ABI.\n" +
                "\n" +
                "am trace-ipc: Trace IPC transactions.\n" +
                "  start: start tracing IPC transactions.\n" +
                "  stop: stop tracing IPC transactions and dump the results to file.\n" +
                "    --dump-file <FILE>: Specify the file the trace should be dumped to.\n" +
                "\n" +
                "am profile: start and stop profiler on a process.  The given <PROCESS> argument\n" +
                "  may be either a process name or pid.  Options are:\n" +
                "    --user <USER_ID> | current: When supplying a process name,\n" +
                "        specify user of process to profile; uses current user if not specified.\n" +
                "\n" +
                "am dumpheap: dump the heap of a process.  The given <PROCESS> argument may\n" +
                "  be either a process name or pid.  Options are:\n" +
                "    -n: dump native heap instead of managed heap\n" +
                "    --user <USER_ID> | current: When supplying a process name,\n" +
                "        specify user of process to dump; uses current user if not specified.\n" +
                "\n" +
                "am set-debug-app: set application <PACKAGE> to debug.  Options are:\n" +
                "    -w: wait for debugger when application starts\n" +
                "    --persistent: retain this value\n" +
                "\n" +
                "am clear-debug-app: clear the previously set-debug-app.\n" +
                "\n" +
                "am set-watch-heap: start monitoring pss size of <PROCESS>, if it is at or\n" +
                "    above <HEAP-LIMIT> then a heap dump is collected for the user to report\n" +
                "\n" +
                "am clear-watch-heap: clear the previously set-watch-heap.\n" +
                "\n" +
                "am bug-report: request bug report generation; will launch a notification\n" +
                "    when done to select where it should be delivered. Options are: \n" +
                "   --progress: will launch a notification right away to show its progress.\n" +
                "\n" +
                "am monitor: start monitoring for crashes or ANRs.\n" +
                "    --gdb: start gdbserv on the given port at crash/ANR\n" +
                "\n" +
                "am hang: hang the system.\n" +
                "    --allow-restart: allow watchdog to perform normal system restart\n" +
                "\n" +
                "am restart: restart the user-space system.\n" +
                "\n" +
                "am idle-maintenance: perform idle maintenance now.\n" +
                "\n" +
                "am screen-compat: control screen compatibility mode of <PACKAGE>.\n" +
                "\n" +
                "am package-importance: print current importance of <PACKAGE>.\n" +
                "\n" +
                "am to-uri: print the given Intent specification as a URI.\n" +
                "\n" +
                "am to-intent-uri: print the given Intent specification as an intent: URI.\n" +
                "\n" +
                "am to-app-uri: print the given Intent specification as an android-app: URI.\n" +
                "\n" +
                "am switch-user: switch to put USER_ID in the foreground, starting\n" +
                "  execution of that user if it is currently stopped.\n" +
                "\n" +
                "am start-user: start USER_ID in background if it is currently stopped,\n" +
                "  use switch-user if you want to start the user in foreground.\n" +
                "\n" +
                "am stop-user: stop execution of USER_ID, not allowing it to run any\n" +
                "  code until a later explicit start or switch to it.\n" +
                "  -w: wait for stop-user to complete.\n" +
                "  -f: force stop even if there are related users that cannot be stopped.\n" +
                "\n" +
                "am stack start: start a new activity on <DISPLAY_ID> using <INTENT>.\n" +
                "\n" +
                "am stack movetask: move <TASK_ID> from its current stack to the top (true) or" +
                "   bottom (false) of <STACK_ID>.\n" +
                "\n" +
                "am stack resize: change <STACK_ID> size and position to <LEFT,TOP,RIGHT,BOTTOM>.\n" +
                "\n" +
                "am stack resize-docked-stack: change docked stack to <LEFT,TOP,RIGHT,BOTTOM>\n" +
                "   and supplying temporary different task bounds indicated by\n" +
                "   <TASK_LEFT,TOP,RIGHT,BOTTOM>\n" +
                "\n" +
                "am stack size-docked-stack-test: test command for sizing docked stack by\n" +
                "   <STEP_SIZE> increments from the side <l>eft, <t>op, <r>ight, or <b>ottom\n" +
                "   applying the optional [DELAY_MS] between each step.\n" +
                "\n" +
                "am stack move-top-activity-to-pinned-stack: moves the top activity from\n" +
                "   <STACK_ID> to the pinned stack using <LEFT,TOP,RIGHT,BOTTOM> for the\n" +
                "   bounds of the pinned stack.\n" +
                "\n" +
                "am stack positiontask: place <TASK_ID> in <STACK_ID> at <POSITION>" +
                "\n" +
                "am stack list: list all of the activity stacks and their sizes.\n" +
                "\n" +
                "am stack info: display the information about activity stack <STACK_ID>.\n" +
                "\n" +
                "am stack remove: remove stack <STACK_ID>.\n" +
                "\n" +
                "am task lock: bring <TASK_ID> to the front and don't allow other tasks to run.\n" +
                "\n" +
                "am task lock stop: end the current task lock.\n" +
                "\n" +
                "am task resizeable: change resizeable mode of <TASK_ID>.\n" +
                "   0 (unresizeable) | 1 (crop_windows) | 2 (resizeable) | 3 (resizeable_and_pipable)\n" +
                "\n" +
                "am task resize: makes sure <TASK_ID> is in a stack with the specified bounds.\n" +
                "   Forces the task to be resizeable and creates a stack if no existing stack\n" +
                "   has the specified bounds.\n" +
                "\n" +
                "am task drag-task-test: test command for dragging/moving <TASK_ID> by\n" +
                "   <STEP_SIZE> increments around the screen applying the optional [DELAY_MS]\n" +
                "   between each step.\n" +
                "\n" +
                "am task size-task-test: test command for sizing <TASK_ID> by <STEP_SIZE>" +
                "   increments within the screen applying the optional [DELAY_MS] between\n" +
                "   each step.\n" +
                "\n" +
                "am get-config: retrieve the configuration and any recent configurations\n" +
                "  of the device.\n" +
                "am suppress-resize-config-changes: suppresses configuration changes due to\n" +
                "  user resizing an activity/task.\n" +
                "\n" +
                "am set-inactive: sets the inactive state of an app.\n" +
                "\n" +
                "am get-inactive: returns the inactive state of an app.\n" +
                "\n" +
                "am send-trim-memory: send a memory trim event to a <PROCESS>.\n" +
                "\n" +
                "am get-current-user: returns id of the current foreground user.\n" +
                "\n"
        );
        Intent.printIntentArgsHelp(pw, "");
        pw.flush();
    }

    @Override
    public void onRun() throws Exception {

        mAm = ActivityManagerNative.getDefault();
        if (mAm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to activity manager; is the system running?");
        }

        mPm = IPackageManager.Stub.asInterface(ServiceManager.getService("package"));
        if (mPm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to package manager; is the system running?");
        }

        String op = nextArgRequired();

        if (op.equals("broadcast")) {
            sendBroadcast();
        } else if (op.equals("instrument")) {
            runInstrument();
        } else {
            runAmCmd(getRawArgs());
        }
    }

    int parseUserArg(String arg) {
        int userId;
        if ("all".equals(arg)) {
            userId = UserHandle.USER_ALL;
        } else if ("current".equals(arg) || "cur".equals(arg)) {
            userId = UserHandle.USER_CURRENT;
        } else {
            userId = Integer.parseInt(arg);
        }
        return userId;
    }

    static final class MyShellCallback extends ShellCallback {
        boolean mActive = true;

        @Override public ParcelFileDescriptor onOpenOutputFile(String path, String seLinuxContext) {
            if (!mActive) {
                System.err.println("Open attempt after active for: " + path);
                return null;
            }
            File file = new File(path);
            //System.err.println("Opening file: " + file.getAbsolutePath());
            //Log.i("Am", "Opening file: " + file.getAbsolutePath());
            final ParcelFileDescriptor fd;
            try {
                fd = ParcelFileDescriptor.open(file,
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE |
                        ParcelFileDescriptor.MODE_WRITE_ONLY);
            } catch (FileNotFoundException e) {
                String msg = "Unable to open file " + path + ": " + e;
                System.err.println(msg);
                throw new IllegalArgumentException(msg);
            }
            if (seLinuxContext != null) {
                final String tcon = SELinux.getFileContext(file.getAbsolutePath());
                if (!SELinux.checkSELinuxAccess(seLinuxContext, tcon, "file", "write")) {
                    try {
                        fd.close();
                    } catch (IOException e) {
                    }
                    String msg = "System server has no access to file context " + tcon;
                    System.err.println(msg + " (from path " + file.getAbsolutePath()
                            + ", context " + seLinuxContext + ")");
                    throw new IllegalArgumentException(msg);
                }
            }
            return fd;
        }
    }

    void runAmCmd(String[] args) throws AndroidException {
        final MyShellCallback cb = new MyShellCallback();
        try {
            mAm.asBinder().shellCommand(FileDescriptor.in, FileDescriptor.out, FileDescriptor.err,
                    args, cb, new ResultReceiver(null) { });
        } catch (RemoteException e) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't call activity manager; is the system running?");
        } finally {
            cb.mActive = false;
        }
    }

    private Intent makeIntent(int defUser) throws URISyntaxException {
        mStartFlags = 0;
        mWaitOption = false;
        mStopOption = false;
        mRepeat = 0;
        mProfileFile = null;
        mSamplingInterval = 0;
        mAutoStop = false;
        mUserId = defUser;
        mStackId = INVALID_STACK_ID;

        return Intent.parseCommandArgs(mArgs, new Intent.CommandOptionHandler() {
            @Override
            public boolean handleOption(String opt, ShellCommand cmd) {
                if (opt.equals("-D")) {
                    mStartFlags |= ActivityManager.START_FLAG_DEBUG;
                } else if (opt.equals("-N")) {
                    mStartFlags |= ActivityManager.START_FLAG_NATIVE_DEBUGGING;
                } else if (opt.equals("-W")) {
                    mWaitOption = true;
                } else if (opt.equals("-P")) {
                    mProfileFile = nextArgRequired();
                    mAutoStop = true;
                } else if (opt.equals("--start-profiler")) {
                    mProfileFile = nextArgRequired();
                    mAutoStop = false;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(nextArgRequired());
                } else if (opt.equals("-R")) {
                    mRepeat = Integer.parseInt(nextArgRequired());
                } else if (opt.equals("-S")) {
                    mStopOption = true;
                } else if (opt.equals("--track-allocation")) {
                    mStartFlags |= ActivityManager.START_FLAG_TRACK_ALLOCATION;
                } else if (opt.equals("--user")) {
                    mUserId = UserHandle.parseUserArg(nextArgRequired());
                } else if (opt.equals("--receiver-permission")) {
                    mReceiverPermission = nextArgRequired();
                } else if (opt.equals("--stack")) {
                    mStackId = Integer.parseInt(nextArgRequired());
                } else {
                    return false;
                }
                return true;
            }
        });
    }

    private class IntentReceiver extends IIntentReceiver.Stub {
        private boolean mFinished = false;

        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            String line = "Broadcast completed: result=" + resultCode;
            if (data != null) line = line + ", data=\"" + data + "\"";
            if (extras != null) line = line + ", extras: " + extras;
            System.out.println(line);
            synchronized (this) {
                mFinished = true;
                notifyAll();
            }
        }

        public synchronized void waitForFinish() {
            try {
                while (!mFinished) wait();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void sendBroadcast() throws Exception {
        Intent intent = makeIntent(UserHandle.USER_CURRENT);
        IntentReceiver receiver = new IntentReceiver();
        String[] requiredPermissions = mReceiverPermission == null ? null
                : new String[] {mReceiverPermission};
        System.out.println("Broadcasting: " + intent);
        mAm.broadcastIntent(null, intent, null, receiver, 0, null, null, requiredPermissions,
                android.app.AppOpsManager.OP_NONE, null, true, false, mUserId);
        receiver.waitForFinish();
    }

    public void runInstrument() throws Exception {
        Instrument instrument = new Instrument(mAm, mPm);

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-p")) {
                instrument.profileFile = nextArgRequired();
            } else if (opt.equals("-w")) {
                instrument.wait = true;
            } else if (opt.equals("-r")) {
                instrument.rawMode = true;
            } else if (opt.equals("-m")) {
                instrument.proto = true;
            } else if (opt.equals("-e")) {
                final String argKey = nextArgRequired();
                final String argValue = nextArgRequired();
                instrument.args.putString(argKey, argValue);
            } else if (opt.equals("--no_window_animation")
                    || opt.equals("--no-window-animation")) {
                instrument.noWindowAnimation = true;
            } else if (opt.equals("--user")) {
                instrument.userId = parseUserArg(nextArgRequired());
            } else if (opt.equals("--abi")) {
                instrument.abi = nextArgRequired();
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        if (instrument.userId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't start instrumentation with user 'all'");
            return;
        }

        instrument.componentNameArg = nextArgRequired();

        instrument.run();
    }
}
