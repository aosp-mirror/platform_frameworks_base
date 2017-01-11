/*
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/


package com.android.commands.am;

import static android.app.ActivityManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityManager.RESIZE_MODE_USER;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

import android.app.ActivityManager;
import android.app.ActivityManager.StackInfo;
import android.app.ActivityManagerNative;
import android.app.ActivityOptions;
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
import android.content.pm.ResolveInfo;
import android.content.pm.UserInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SELinux;
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AndroidException;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.view.IWindowManager;

import com.android.internal.os.BaseCommand;
import com.android.internal.util.HexDump;
import com.android.internal.util.Preconditions;

import java.io.BufferedReader;
import java.io.File;
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
                "       am bug-report [--progress | --telephony]\n" +
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
                "    --stack <STACK_ID>: Specify into which stack should the activity be put." +
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
                "   --telephony: will dump only telephony sections.\n" +
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
                "\n" +
                "am supports-multiwindow: returns true if the device supports multiwindow.\n" +
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

        if (op.equals("start")) {
            runStart();
        } else if (op.equals("startservice")) {
            runStartService();
        } else if (op.equals("stopservice")) {
            runStopService();
        } else if (op.equals("force-stop")) {
            runForceStop();
        } else if (op.equals("kill")) {
            runKill();
        } else if (op.equals("kill-all")) {
            runKillAll();
        } else if (op.equals("instrument")) {
            runInstrument();
        } else if (op.equals("trace-ipc")) {
            runTraceIpc();
        } else if (op.equals("broadcast")) {
            sendBroadcast();
        } else if (op.equals("profile")) {
            runProfile();
        } else if (op.equals("dumpheap")) {
            runDumpHeap();
        } else if (op.equals("set-debug-app")) {
            runSetDebugApp();
        } else if (op.equals("clear-debug-app")) {
            runClearDebugApp();
        } else if (op.equals("set-watch-heap")) {
            runSetWatchHeap();
        } else if (op.equals("clear-watch-heap")) {
            runClearWatchHeap();
        } else if (op.equals("bug-report")) {
            runBugReport();
        } else if (op.equals("monitor")) {
            runMonitor();
        } else if (op.equals("hang")) {
            runHang();
        } else if (op.equals("restart")) {
            runRestart();
        } else if (op.equals("idle-maintenance")) {
            runIdleMaintenance();
        } else if (op.equals("screen-compat")) {
            runScreenCompat();
        } else if (op.equals("package-importance")) {
            runPackageImportance();
        } else if (op.equals("to-uri")) {
            runToUri(0);
        } else if (op.equals("to-intent-uri")) {
            runToUri(Intent.URI_INTENT_SCHEME);
        } else if (op.equals("to-app-uri")) {
            runToUri(Intent.URI_ANDROID_APP_SCHEME);
        } else if (op.equals("switch-user")) {
            runSwitchUser();
        } else if (op.equals("start-user")) {
            runStartUserInBackground();
        } else if (op.equals("unlock-user")) {
            runUnlockUser();
        } else if (op.equals("stop-user")) {
            runStopUser();
        } else if (op.equals("stack")) {
            runStack();
        } else if (op.equals("task")) {
            runTask();
        } else if (op.equals("get-config")) {
            runGetConfig();
        } else if (op.equals("suppress-resize-config-changes")) {
            runSuppressResizeConfigChanges();
        } else if (op.equals("set-inactive")) {
            runSetInactive();
        } else if (op.equals("get-inactive")) {
            runGetInactive();
        } else if (op.equals("send-trim-memory")) {
            runSendTrimMemory();
        } else if (op.equals("get-current-user")) {
            runGetCurrentUser();
        } else if (op.equals("supports-multiwindow")) {
            runSupportsMultiwindow();
        } else {
            showError("Error: unknown command '" + op + "'");
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
                    mUserId = parseUserArg(nextArgRequired());
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

    private void runStartService() throws Exception {
        Intent intent = makeIntent(UserHandle.USER_CURRENT);
        if (mUserId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't start activity with user 'all'");
            return;
        }
        System.out.println("Starting service: " + intent);
        ComponentName cn = mAm.startService(null, intent, intent.getType(),
                SHELL_PACKAGE_NAME, mUserId);
        if (cn == null) {
            System.err.println("Error: Not found; no service started.");
        } else if (cn.getPackageName().equals("!")) {
            System.err.println("Error: Requires permission " + cn.getClassName());
        } else if (cn.getPackageName().equals("!!")) {
            System.err.println("Error: " + cn.getClassName());
        }
    }

    private void runStopService() throws Exception {
        Intent intent = makeIntent(UserHandle.USER_CURRENT);
        if (mUserId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't stop activity with user 'all'");
            return;
        }
        System.out.println("Stopping service: " + intent);
        int result = mAm.stopService(null, intent, intent.getType(), mUserId);
        if (result == 0) {
            System.err.println("Service not stopped: was not running.");
        } else if (result == 1) {
            System.err.println("Service stopped");
        } else if (result == -1) {
            System.err.println("Error stopping service");
        }
    }

    private void runStart() throws Exception {
        Intent intent = makeIntent(UserHandle.USER_CURRENT);

        if (mUserId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't start service with user 'all'");
            return;
        }

        String mimeType = intent.getType();
        if (mimeType == null && intent.getData() != null
                && "content".equals(intent.getData().getScheme())) {
            mimeType = mAm.getProviderMimeType(intent.getData(), mUserId);
        }


        do {
            if (mStopOption) {
                String packageName;
                if (intent.getComponent() != null) {
                    packageName = intent.getComponent().getPackageName();
                } else {
                    List<ResolveInfo> activities = mPm.queryIntentActivities(intent, mimeType, 0,
                            mUserId).getList();
                    if (activities == null || activities.size() <= 0) {
                        System.err.println("Error: Intent does not match any activities: "
                                + intent);
                        return;
                    } else if (activities.size() > 1) {
                        System.err.println("Error: Intent matches multiple activities; can't stop: "
                                + intent);
                        return;
                    }
                    packageName = activities.get(0).activityInfo.packageName;
                }
                System.out.println("Stopping: " + packageName);
                mAm.forceStopPackage(packageName, mUserId);
                Thread.sleep(250);
            }

            System.out.println("Starting: " + intent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            ParcelFileDescriptor fd = null;
            ProfilerInfo profilerInfo = null;

            if (mProfileFile != null) {
                try {
                    fd = openForSystemServer(
                            new File(mProfileFile),
                            ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE |
                            ParcelFileDescriptor.MODE_WRITE_ONLY);
                } catch (FileNotFoundException e) {
                    System.err.println("Error: Unable to open file: " + mProfileFile);
                    System.err.println("Consider using a file under /data/local/tmp/");
                    return;
                }
                profilerInfo = new ProfilerInfo(mProfileFile, fd, mSamplingInterval, mAutoStop);
            }

            IActivityManager.WaitResult result = null;
            int res;
            final long startTime = SystemClock.uptimeMillis();
            ActivityOptions options = null;
            if (mStackId != INVALID_STACK_ID) {
                options = ActivityOptions.makeBasic();
                options.setLaunchStackId(mStackId);
            }
            if (mWaitOption) {
                result = mAm.startActivityAndWait(null, null, intent, mimeType,
                        null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
                res = result.result;
            } else {
                res = mAm.startActivityAsUser(null, null, intent, mimeType,
                        null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
            }
            final long endTime = SystemClock.uptimeMillis();
            PrintStream out = mWaitOption ? System.out : System.err;
            boolean launched = false;
            switch (res) {
                case ActivityManager.START_SUCCESS:
                    launched = true;
                    break;
                case ActivityManager.START_SWITCHES_CANCELED:
                    launched = true;
                    out.println(
                            "Warning: Activity not started because the "
                            + " current activity is being kept for the user.");
                    break;
                case ActivityManager.START_DELIVERED_TO_TOP:
                    launched = true;
                    out.println(
                            "Warning: Activity not started, intent has "
                            + "been delivered to currently running "
                            + "top-most instance.");
                    break;
                case ActivityManager.START_RETURN_INTENT_TO_CALLER:
                    launched = true;
                    out.println(
                            "Warning: Activity not started because intent "
                            + "should be handled by the caller");
                    break;
                case ActivityManager.START_TASK_TO_FRONT:
                    launched = true;
                    out.println(
                            "Warning: Activity not started, its current "
                            + "task has been brought to the front");
                    break;
                case ActivityManager.START_INTENT_NOT_RESOLVED:
                    out.println(
                            "Error: Activity not started, unable to "
                            + "resolve " + intent.toString());
                    break;
                case ActivityManager.START_CLASS_NOT_FOUND:
                    out.println(NO_CLASS_ERROR_CODE);
                    out.println("Error: Activity class " +
                            intent.getComponent().toShortString()
                            + " does not exist.");
                    break;
                case ActivityManager.START_FORWARD_AND_REQUEST_CONFLICT:
                    out.println(
                            "Error: Activity not started, you requested to "
                            + "both forward and receive its result");
                    break;
                case ActivityManager.START_PERMISSION_DENIED:
                    out.println(
                            "Error: Activity not started, you do not "
                            + "have permission to access it.");
                    break;
                case ActivityManager.START_NOT_VOICE_COMPATIBLE:
                    out.println(
                            "Error: Activity not started, voice control not allowed for: "
                                    + intent);
                    break;
                case ActivityManager.START_NOT_CURRENT_USER_ACTIVITY:
                    out.println(
                            "Error: Not allowed to start background user activity"
                            + " that shouldn't be displayed for all users.");
                    break;
                default:
                    out.println(
                            "Error: Activity not started, unknown error code " + res);
                    break;
            }
            if (mWaitOption && launched) {
                if (result == null) {
                    result = new IActivityManager.WaitResult();
                    result.who = intent.getComponent();
                }
                System.out.println("Status: " + (result.timeout ? "timeout" : "ok"));
                if (result.who != null) {
                    System.out.println("Activity: " + result.who.flattenToShortString());
                }
                if (result.thisTime >= 0) {
                    System.out.println("ThisTime: " + result.thisTime);
                }
                if (result.totalTime >= 0) {
                    System.out.println("TotalTime: " + result.totalTime);
                }
                System.out.println("WaitTime: " + (endTime-startTime));
                System.out.println("Complete");
            }
            mRepeat--;
            if (mRepeat > 0) {
                mAm.unhandledBack();
            }
        } while (mRepeat > 0);
    }

    private void runForceStop() throws Exception {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        mAm.forceStopPackage(nextArgRequired(), userId);
    }

    private void runKill() throws Exception {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        mAm.killBackgroundProcesses(nextArgRequired(), userId);
    }

    private void runKillAll() throws Exception {
        mAm.killAllBackgroundProcesses();
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

    private void runInstrument() throws Exception {
        String profileFile = null;
        boolean wait = false;
        boolean rawMode = false;
        boolean no_window_animation = false;
        int userId = UserHandle.USER_CURRENT;
        Bundle args = new Bundle();
        String argKey = null, argValue = null;
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));
        String abi = null;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-p")) {
                profileFile = nextArgRequired();
            } else if (opt.equals("-w")) {
                wait = true;
            } else if (opt.equals("-r")) {
                rawMode = true;
            } else if (opt.equals("-e")) {
                argKey = nextArgRequired();
                argValue = nextArgRequired();
                args.putString(argKey, argValue);
            } else if (opt.equals("--no_window_animation")
                    || opt.equals("--no-window-animation")) {
                no_window_animation = true;
            } else if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
            } else if (opt.equals("--abi")) {
                abi = nextArgRequired();
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        if (userId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't start instrumentation with user 'all'");
            return;
        }

        String cnArg = nextArgRequired();

        ComponentName cn;
        if (cnArg.contains("/")) {
            cn = ComponentName.unflattenFromString(cnArg);
            if (cn == null) throw new IllegalArgumentException("Bad component name: " + cnArg);
        } else {
            List<InstrumentationInfo> infos = mPm.queryInstrumentation(null, 0).getList();

            final int numInfos = infos == null ? 0: infos.size();
            List<ComponentName> cns = new ArrayList<>();
            for (int i = 0; i < numInfos; i++) {
                InstrumentationInfo info = infos.get(i);

                ComponentName c = new ComponentName(info.packageName, info.name);
                if (cnArg.equals(info.packageName)) {
                    cns.add(c);
                }
            }

            if (cns.size() == 0) {
                throw new IllegalArgumentException("No instrumentation found for: " + cnArg);
            } else if (cns.size() == 1) {
                cn = cns.get(0);
            } else {
                StringBuilder cnsStr = new StringBuilder();
                final int numCns = cns.size();
                for (int i = 0; i < numCns; i++) {
                    cnsStr.append(cns.get(i).flattenToString());
                    cnsStr.append(", ");
                }

                // Remove last ", "
                cnsStr.setLength(cnsStr.length() - 2);

                throw new IllegalArgumentException("Found multiple instrumentations: "
                        + cnsStr.toString());
            }
        }

        InstrumentationWatcher watcher = null;
        UiAutomationConnection connection = null;
        if (wait) {
            watcher = new InstrumentationWatcher();
            watcher.setRawOutput(rawMode);
            connection = new UiAutomationConnection();
        }

        float[] oldAnims = null;
        if (no_window_animation) {
            oldAnims = wm.getAnimationScales();
            wm.setAnimationScale(0, 0.0f);
            wm.setAnimationScale(1, 0.0f);
        }

        if (abi != null) {
            final String[] supportedAbis = Build.SUPPORTED_ABIS;
            boolean matched = false;
            for (String supportedAbi : supportedAbis) {
                if (supportedAbi.equals(abi)) {
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                throw new AndroidException(
                        "INSTRUMENTATION_FAILED: Unsupported instruction set " + abi);
            }
        }

        if (!mAm.startInstrumentation(cn, profileFile, 0, args, watcher, connection, userId, abi)) {
            throw new AndroidException("INSTRUMENTATION_FAILED: " + cn.flattenToString());
        }

        if (watcher != null) {
            if (!watcher.waitForFinish()) {
                System.out.println("INSTRUMENTATION_ABORTED: System has crashed.");
            }
        }

        if (oldAnims != null) {
            wm.setAnimationScales(oldAnims);
        }
    }

    private void runTraceIpc() throws Exception {
        String op = nextArgRequired();
        if (op.equals("start")) {
            runTraceIpcStart();
        } else if (op.equals("stop")) {
            runTraceIpcStop();
        } else {
            showError("Error: unknown command '" + op + "'");
            return;
        }
    }

    private void runTraceIpcStart() throws Exception {
        System.out.println("Starting IPC tracing.");
        mAm.startBinderTracking();
    }

    private void runTraceIpcStop() throws Exception {
        String opt;
        String filename = null;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--dump-file")) {
                filename = nextArgRequired();
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        if (filename == null) {
            System.err.println("Error: Specify filename to dump logs to.");
            return;
        }

        ParcelFileDescriptor fd = null;

        try {
            File file = new File(filename);
            file.delete();
            fd = openForSystemServer(file,
                    ParcelFileDescriptor.MODE_CREATE |
                            ParcelFileDescriptor.MODE_TRUNCATE |
                            ParcelFileDescriptor.MODE_WRITE_ONLY);
        } catch (FileNotFoundException e) {
            System.err.println("Error: Unable to open file: " + filename);
            System.err.println("Consider using a file under /data/local/tmp/");
            return;
        }

        ;
        if (!mAm.stopBinderTrackingAndDump(fd)) {
            throw new AndroidException("STOP TRACE FAILED.");
        }

        System.out.println("Stopped IPC tracing. Dumping logs to: " + filename);
    }

    static void removeWallOption() {
        String props = SystemProperties.get("dalvik.vm.extra-opts");
        if (props != null && props.contains("-Xprofile:wallclock")) {
            props = props.replace("-Xprofile:wallclock", "");
            props = props.trim();
            SystemProperties.set("dalvik.vm.extra-opts", props);
        }
    }

    private void runProfile() throws Exception {
        String profileFile = null;
        boolean start = false;
        boolean wall = false;
        int userId = UserHandle.USER_CURRENT;
        int profileType = 0;
        mSamplingInterval = 0;

        String process = null;

        String cmd = nextArgRequired();

        if ("start".equals(cmd)) {
            start = true;
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = parseUserArg(nextArgRequired());
                } else if (opt.equals("--wall")) {
                    wall = true;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(nextArgRequired());
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    return;
                }
            }
            process = nextArgRequired();
        } else if ("stop".equals(cmd)) {
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = parseUserArg(nextArgRequired());
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    return;
                }
            }
            process = nextArg();
        } else {
            // Compatibility with old syntax: process is specified first.
            process = cmd;
            cmd = nextArgRequired();
            if ("start".equals(cmd)) {
                start = true;
            } else if (!"stop".equals(cmd)) {
                throw new IllegalArgumentException("Profile command " + process + " not valid");
            }
        }

        if (userId == UserHandle.USER_ALL) {
            System.err.println("Error: Can't profile with user 'all'");
            return;
        }

        ParcelFileDescriptor fd = null;
        ProfilerInfo profilerInfo = null;

        if (start) {
            profileFile = nextArgRequired();
            try {
                fd = openForSystemServer(
                        new File(profileFile),
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE |
                        ParcelFileDescriptor.MODE_WRITE_ONLY);
            } catch (FileNotFoundException e) {
                System.err.println("Error: Unable to open file: " + profileFile);
                System.err.println("Consider using a file under /data/local/tmp/");
                return;
            }
            profilerInfo = new ProfilerInfo(profileFile, fd, mSamplingInterval, false);
        }

        try {
            if (wall) {
                // XXX doesn't work -- this needs to be set before booting.
                String props = SystemProperties.get("dalvik.vm.extra-opts");
                if (props == null || !props.contains("-Xprofile:wallclock")) {
                    props = props + " -Xprofile:wallclock";
                    //SystemProperties.set("dalvik.vm.extra-opts", props);
                }
            } else if (start) {
                //removeWallOption();
            }
            if (!mAm.profileControl(process, userId, start, profilerInfo, profileType)) {
                wall = false;
                throw new AndroidException("PROFILE FAILED on process " + process);
            }
        } finally {
            if (!wall) {
                //removeWallOption();
            }
        }
    }

    private void runDumpHeap() throws Exception {
        boolean managed = true;
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    System.err.println("Error: Can't dump heap with user 'all'");
                    return;
                }
            } else if (opt.equals("-n")) {
                managed = false;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        String process = nextArgRequired();
        String heapFile = nextArgRequired();
        ParcelFileDescriptor fd = null;

        try {
            File file = new File(heapFile);
            file.delete();
            fd = openForSystemServer(file,
                    ParcelFileDescriptor.MODE_CREATE |
                    ParcelFileDescriptor.MODE_TRUNCATE |
                    ParcelFileDescriptor.MODE_WRITE_ONLY);
        } catch (FileNotFoundException e) {
            System.err.println("Error: Unable to open file: " + heapFile);
            System.err.println("Consider using a file under /data/local/tmp/");
            return;
        }

        if (!mAm.dumpHeap(process, userId, managed, heapFile, fd)) {
            throw new AndroidException("HEAP DUMP FAILED on process " + process);
        }
    }

    private void runSetDebugApp() throws Exception {
        boolean wait = false;
        boolean persistent = false;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-w")) {
                wait = true;
            } else if (opt.equals("--persistent")) {
                persistent = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        String pkg = nextArgRequired();
        mAm.setDebugApp(pkg, wait, persistent);
    }

    private void runClearDebugApp() throws Exception {
        mAm.setDebugApp(null, false, true);
    }

    private void runSetWatchHeap() throws Exception {
        String proc = nextArgRequired();
        String limit = nextArgRequired();
        mAm.setDumpHeapDebugLimit(proc, 0, Long.parseLong(limit), null);
    }

    private void runClearWatchHeap() throws Exception {
        String proc = nextArgRequired();
        mAm.setDumpHeapDebugLimit(proc, 0, -1, null);
    }

    private void runBugReport() throws Exception {
        String opt;
        int bugreportType = ActivityManager.BUGREPORT_OPTION_FULL;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--progress")) {
                bugreportType = ActivityManager.BUGREPORT_OPTION_INTERACTIVE;
            } else if (opt.equals("--telephony")) {
                bugreportType = ActivityManager.BUGREPORT_OPTION_TELEPHONY;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        mAm.requestBugReport(bugreportType);
        System.out.println("Your lovely bug report is being created; please be patient.");
    }

    private void runSwitchUser() throws Exception {
        String user = nextArgRequired();
        mAm.switchUser(Integer.parseInt(user));
    }

    private void runStartUserInBackground() throws Exception {
        String user = nextArgRequired();
        boolean success = mAm.startUserInBackground(Integer.parseInt(user));
        if (success) {
            System.out.println("Success: user started");
        } else {
            System.err.println("Error: could not start user");
        }
    }

    private byte[] argToBytes(String arg) {
        if (arg.equals("!")) {
            return null;
        } else {
            return HexDump.hexStringToByteArray(arg);
        }
    }

    private void runUnlockUser() throws Exception {
        int userId = Integer.parseInt(nextArgRequired());
        byte[] token = argToBytes(nextArgRequired());
        byte[] secret = argToBytes(nextArgRequired());
        boolean success = mAm.unlockUser(userId, token, secret, null);
        if (success) {
            System.out.println("Success: user unlocked");
        } else {
            System.err.println("Error: could not unlock user");
        }
    }

    private static class StopUserCallback extends IStopUserCallback.Stub {
        private boolean mFinished = false;

        public synchronized void waitForFinish() {
            try {
                while (!mFinished) wait();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public synchronized void userStopped(int userId) {
            mFinished = true;
            notifyAll();
        }

        @Override
        public synchronized void userStopAborted(int userId) {
            mFinished = true;
            notifyAll();
        }
    }

    private void runStopUser() throws Exception {
        boolean wait = false;
        boolean force = false;
        String opt;
        while ((opt = nextOption()) != null) {
            if ("-w".equals(opt)) {
                wait = true;
            } else if ("-f".equals(opt)) {
                force = true;
            } else {
                System.err.println("Error: unknown option: " + opt);
                return;
            }
        }
        int user = Integer.parseInt(nextArgRequired());
        StopUserCallback callback = wait ? new StopUserCallback() : null;

        int res = mAm.stopUser(user, force, callback);
        if (res != ActivityManager.USER_OP_SUCCESS) {
            String txt = "";
            switch (res) {
                case ActivityManager.USER_OP_IS_CURRENT:
                    txt = " (Can't stop current user)";
                    break;
                case ActivityManager.USER_OP_UNKNOWN_USER:
                    txt = " (Unknown user " + user + ")";
                    break;
                case ActivityManager.USER_OP_ERROR_IS_SYSTEM:
                    txt = " (System user cannot be stopped)";
                    break;
                case ActivityManager.USER_OP_ERROR_RELATED_USERS_CANNOT_STOP:
                    txt = " (Can't stop user " + user
                            + " - one of its related users can't be stopped)";
                    break;
            }
            System.err.println("Switch failed: " + res + txt);
        } else if (callback != null) {
            callback.waitForFinish();
        }
    }

    class MyActivityController extends IActivityController.Stub {
        final String mGdbPort;
        final boolean mMonkey;

        static final int STATE_NORMAL = 0;
        static final int STATE_CRASHED = 1;
        static final int STATE_EARLY_ANR = 2;
        static final int STATE_ANR = 3;

        int mState;

        static final int RESULT_DEFAULT = 0;

        static final int RESULT_CRASH_DIALOG = 0;
        static final int RESULT_CRASH_KILL = 1;

        static final int RESULT_EARLY_ANR_CONTINUE = 0;
        static final int RESULT_EARLY_ANR_KILL = 1;

        static final int RESULT_ANR_DIALOG = 0;
        static final int RESULT_ANR_KILL = 1;
        static final int RESULT_ANR_WAIT = 1;

        int mResult;

        Process mGdbProcess;
        Thread mGdbThread;
        boolean mGotGdbPrint;

        MyActivityController(String gdbPort, boolean monkey) {
            mGdbPort = gdbPort;
            mMonkey = monkey;
        }

        @Override
        public boolean activityResuming(String pkg) {
            synchronized (this) {
                System.out.println("** Activity resuming: " + pkg);
            }
            return true;
        }

        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            synchronized (this) {
                System.out.println("** Activity starting: " + pkg);
            }
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            synchronized (this) {
                System.out.println("** ERROR: PROCESS CRASHED");
                System.out.println("processName: " + processName);
                System.out.println("processPid: " + pid);
                System.out.println("shortMsg: " + shortMsg);
                System.out.println("longMsg: " + longMsg);
                System.out.println("timeMillis: " + timeMillis);
                System.out.println("stack:");
                System.out.print(stackTrace);
                System.out.println("#");
                int result = waitControllerLocked(pid, STATE_CRASHED);
                return result == RESULT_CRASH_KILL ? false : true;
            }
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            synchronized (this) {
                System.out.println("** ERROR: EARLY PROCESS NOT RESPONDING");
                System.out.println("processName: " + processName);
                System.out.println("processPid: " + pid);
                System.out.println("annotation: " + annotation);
                int result = waitControllerLocked(pid, STATE_EARLY_ANR);
                if (result == RESULT_EARLY_ANR_KILL) return -1;
                return 0;
            }
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats) {
            synchronized (this) {
                System.out.println("** ERROR: PROCESS NOT RESPONDING");
                System.out.println("processName: " + processName);
                System.out.println("processPid: " + pid);
                System.out.println("processStats:");
                System.out.print(processStats);
                System.out.println("#");
                int result = waitControllerLocked(pid, STATE_ANR);
                if (result == RESULT_ANR_KILL) return -1;
                if (result == RESULT_ANR_WAIT) return 1;
                return 0;
            }
        }

        @Override
        public int systemNotResponding(String message) {
            synchronized (this) {
                System.out.println("** ERROR: PROCESS NOT RESPONDING");
                System.out.println("message: " + message);
                System.out.println("#");
                System.out.println("Allowing system to die.");
                return -1;
            }
        }

        void killGdbLocked() {
            mGotGdbPrint = false;
            if (mGdbProcess != null) {
                System.out.println("Stopping gdbserver");
                mGdbProcess.destroy();
                mGdbProcess = null;
            }
            if (mGdbThread != null) {
                mGdbThread.interrupt();
                mGdbThread = null;
            }
        }

        int waitControllerLocked(int pid, int state) {
            if (mGdbPort != null) {
                killGdbLocked();

                try {
                    System.out.println("Starting gdbserver on port " + mGdbPort);
                    System.out.println("Do the following:");
                    System.out.println("  adb forward tcp:" + mGdbPort + " tcp:" + mGdbPort);
                    System.out.println("  gdbclient app_process :" + mGdbPort);

                    mGdbProcess = Runtime.getRuntime().exec(new String[] {
                            "gdbserver", ":" + mGdbPort, "--attach", Integer.toString(pid)
                    });
                    final InputStreamReader converter = new InputStreamReader(
                            mGdbProcess.getInputStream());
                    mGdbThread = new Thread() {
                        @Override
                        public void run() {
                            BufferedReader in = new BufferedReader(converter);
                            String line;
                            int count = 0;
                            while (true) {
                                synchronized (MyActivityController.this) {
                                    if (mGdbThread == null) {
                                        return;
                                    }
                                    if (count == 2) {
                                        mGotGdbPrint = true;
                                        MyActivityController.this.notifyAll();
                                    }
                                }
                                try {
                                    line = in.readLine();
                                    if (line == null) {
                                        return;
                                    }
                                    System.out.println("GDB: " + line);
                                    count++;
                                } catch (IOException e) {
                                    return;
                                }
                            }
                        }
                    };
                    mGdbThread.start();

                    // Stupid waiting for .5s.  Doesn't matter if we end early.
                    try {
                        this.wait(500);
                    } catch (InterruptedException e) {
                    }

                } catch (IOException e) {
                    System.err.println("Failure starting gdbserver: " + e);
                    killGdbLocked();
                }
            }
            mState = state;
            System.out.println("");
            printMessageForState();

            while (mState != STATE_NORMAL) {
                try {
                    wait();
                } catch (InterruptedException e) {
                }
            }

            killGdbLocked();

            return mResult;
        }

        void resumeController(int result) {
            synchronized (this) {
                mState = STATE_NORMAL;
                mResult = result;
                notifyAll();
            }
        }

        void printMessageForState() {
            switch (mState) {
                case STATE_NORMAL:
                    System.out.println("Monitoring activity manager...  available commands:");
                    break;
                case STATE_CRASHED:
                    System.out.println("Waiting after crash...  available commands:");
                    System.out.println("(c)ontinue: show crash dialog");
                    System.out.println("(k)ill: immediately kill app");
                    break;
                case STATE_EARLY_ANR:
                    System.out.println("Waiting after early ANR...  available commands:");
                    System.out.println("(c)ontinue: standard ANR processing");
                    System.out.println("(k)ill: immediately kill app");
                    break;
                case STATE_ANR:
                    System.out.println("Waiting after ANR...  available commands:");
                    System.out.println("(c)ontinue: show ANR dialog");
                    System.out.println("(k)ill: immediately kill app");
                    System.out.println("(w)ait: wait some more");
                    break;
            }
            System.out.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            try {
                printMessageForState();

                mAm.setActivityController(this, mMonkey);
                mState = STATE_NORMAL;

                InputStreamReader converter = new InputStreamReader(System.in);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while ((line = in.readLine()) != null) {
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        resumeController(RESULT_DEFAULT);
                        break;
                    } else if (mState == STATE_CRASHED) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_CRASH_DIALOG);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_CRASH_KILL);
                        } else {
                            System.out.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_ANR_DIALOG);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_ANR_KILL);
                        } else if ("w".equals(line) || "wait".equals(line)) {
                            resumeController(RESULT_ANR_WAIT);
                        } else {
                            System.out.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_EARLY_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_CONTINUE);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_KILL);
                        } else {
                            System.out.println("Invalid command: " + line);
                        }
                    } else {
                        System.out.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            System.out.println("");
                        }
                        printMessageForState();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mAm.setActivityController(null, mMonkey);
            }
        }
    }

    private void runMonitor() throws Exception {
        String opt;
        String gdbPort = null;
        boolean monkey = false;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--gdb")) {
                gdbPort = nextArgRequired();
            } else if (opt.equals("-m")) {
                monkey = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        MyActivityController controller = new MyActivityController(gdbPort, monkey);
        controller.run();
    }

    private void runHang() throws Exception {
        String opt;
        boolean allowRestart = false;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--allow-restart")) {
                allowRestart = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        System.out.println("Hanging the system...");
        mAm.hang(new Binder(), allowRestart);
    }

    private void runRestart() throws Exception {
        String opt;
        while ((opt=nextOption()) != null) {
            System.err.println("Error: Unknown option: " + opt);
            return;
        }

        System.out.println("Restart the system...");
        mAm.restart();
    }

    private void runIdleMaintenance() throws Exception {
        String opt;
        while ((opt=nextOption()) != null) {
            System.err.println("Error: Unknown option: " + opt);
            return;
        }

        System.out.println("Performing idle maintenance...");
        try {
            mAm.sendIdleJobTrigger();
        } catch (RemoteException e) {
        }
    }

    private void runScreenCompat() throws Exception {
        String mode = nextArgRequired();
        boolean enabled;
        if ("on".equals(mode)) {
            enabled = true;
        } else if ("off".equals(mode)) {
            enabled = false;
        } else {
            System.err.println("Error: enabled mode must be 'on' or 'off' at " + mode);
            return;
        }

        String packageName = nextArgRequired();
        do {
            try {
                mAm.setPackageScreenCompatMode(packageName, enabled
                        ? ActivityManager.COMPAT_MODE_ENABLED
                        : ActivityManager.COMPAT_MODE_DISABLED);
            } catch (RemoteException e) {
            }
            packageName = nextArg();
        } while (packageName != null);
    }

    private void runPackageImportance() throws Exception {
        String packageName = nextArgRequired();
        try {
            int procState = mAm.getPackageProcessState(packageName, "com.android.shell");
            System.out.println(
                    ActivityManager.RunningAppProcessInfo.procStateToImportance(procState));
        } catch (RemoteException e) {
        }
    }

    private void runToUri(int flags) throws Exception {
        Intent intent = makeIntent(UserHandle.USER_CURRENT);
        System.out.println(intent.toUri(flags));
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

    private class InstrumentationWatcher extends IInstrumentationWatcher.Stub {
        private boolean mFinished = false;
        private boolean mRawMode = false;

        /**
         * Set or reset "raw mode".  In "raw mode", all bundles are dumped.  In "pretty mode",
         * if a bundle includes Instrumentation.REPORT_KEY_STREAMRESULT, just print that.
         * @param rawMode true for raw mode, false for pretty mode.
         */
        public void setRawOutput(boolean rawMode) {
            mRawMode = rawMode;
        }

        @Override
        public void instrumentationStatus(ComponentName name, int resultCode, Bundle results) {
            synchronized (this) {
                // pretty printer mode?
                String pretty = null;
                if (!mRawMode && results != null) {
                    pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
                }
                if (pretty != null) {
                    System.out.print(pretty);
                } else {
                    if (results != null) {
                        for (String key : results.keySet()) {
                            System.out.println(
                                    "INSTRUMENTATION_STATUS: " + key + "=" + results.get(key));
                        }
                    }
                    System.out.println("INSTRUMENTATION_STATUS_CODE: " + resultCode);
                }
                notifyAll();
            }
        }

        @Override
        public void instrumentationFinished(ComponentName name, int resultCode,
                Bundle results) {
            synchronized (this) {
                // pretty printer mode?
                String pretty = null;
                if (!mRawMode && results != null) {
                    pretty = results.getString(Instrumentation.REPORT_KEY_STREAMRESULT);
                }
                if (pretty != null) {
                    System.out.println(pretty);
                } else {
                    if (results != null) {
                        for (String key : results.keySet()) {
                            System.out.println(
                                    "INSTRUMENTATION_RESULT: " + key + "=" + results.get(key));
                        }
                    }
                    System.out.println("INSTRUMENTATION_CODE: " + resultCode);
                }
                mFinished = true;
                notifyAll();
            }
        }

        public boolean waitForFinish() {
            synchronized (this) {
                while (!mFinished) {
                    try {
                        if (!mAm.asBinder().pingBinder()) {
                            return false;
                        }
                        wait(1000);
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
            return true;
        }
    }

    private void runStack() throws Exception {
        String op = nextArgRequired();
        switch (op) {
            case "start":
                runStackStart();
                break;
            case "movetask":
                runStackMoveTask();
                break;
            case "resize":
                runStackResize();
                break;
            case "resize-animated":
                runStackResizeAnimated();
                break;
            case "resize-docked-stack":
                runStackResizeDocked();
                break;
            case "positiontask":
                runStackPositionTask();
                break;
            case "list":
                runStackList();
                break;
            case "info":
                runStackInfo();
                break;
            case "move-top-activity-to-pinned-stack":
                runMoveTopActivityToPinnedStack();
                break;
            case "size-docked-stack-test":
                runStackSizeDockedStackTest();
                break;
            case "remove":
                runStackRemove();
                break;
            default:
                showError("Error: unknown command '" + op + "'");
                break;
        }
    }

    private void runStackStart() throws Exception {
        String displayIdStr = nextArgRequired();
        int displayId = Integer.parseInt(displayIdStr);
        Intent intent = makeIntent(UserHandle.USER_CURRENT);

        try {
            IActivityContainer container = mAm.createStackOnDisplay(displayId);
            if (container != null) {
                container.startActivity(intent);
            }
        } catch (RemoteException e) {
        }
    }

    private void runStackMoveTask() throws Exception {
        String taskIdStr = nextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = nextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String toTopStr = nextArgRequired();
        final boolean toTop;
        if ("true".equals(toTopStr)) {
            toTop = true;
        } else if ("false".equals(toTopStr)) {
            toTop = false;
        } else {
            System.err.println("Error: bad toTop arg: " + toTopStr);
            return;
        }

        try {
            mAm.moveTaskToStack(taskId, stackId, toTop);
        } catch (RemoteException e) {
        }
    }

    private void runStackResize() throws Exception {
        String stackIdStr = nextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        final Rect bounds = getBounds();
        if (bounds == null) {
            System.err.println("Error: invalid input bounds");
            return;
        }
        resizeStack(stackId, bounds, 0);
    }

    private void runStackResizeAnimated() throws Exception {
        String stackIdStr = nextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        final Rect bounds;
        if ("null".equals(mArgs.peekNextArg())) {
            bounds = null;
        } else {
            bounds = getBounds();
            if (bounds == null) {
                System.err.println("Error: invalid input bounds");
                return;
            }
        }
        resizeStackUnchecked(stackId, bounds, 0, true);
    }

    private void resizeStackUnchecked(int stackId, Rect bounds, int delayMs, boolean animate) {
        try {
            mAm.resizeStack(stackId, bounds, false, false, animate, -1);
            Thread.sleep(delayMs);
        } catch (RemoteException e) {
            showError("Error: resizing stack " + e);
        } catch (InterruptedException e) {
        }
    }

    private void runStackResizeDocked() throws Exception {
        final Rect bounds = getBounds();
        final Rect taskBounds = getBounds();
        if (bounds == null || taskBounds == null) {
            System.err.println("Error: invalid input bounds");
            return;
        }
        try {
            mAm.resizeDockedStack(bounds, taskBounds, null, null, null);
        } catch (RemoteException e) {
            showError("Error: resizing docked stack " + e);
        }
    }

    private void resizeStack(int stackId, Rect bounds, int delayMs)
            throws Exception {
        if (bounds == null) {
            showError("Error: invalid input bounds");
            return;
        }
        resizeStackUnchecked(stackId, bounds, delayMs, false);
    }

    private void runStackPositionTask() throws Exception {
        String taskIdStr = nextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = nextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String positionStr = nextArgRequired();
        int position = Integer.parseInt(positionStr);

        try {
            mAm.positionTaskInStack(taskId, stackId, position);
        } catch (RemoteException e) {
        }
    }

    private void runStackList() throws Exception {
        try {
            List<StackInfo> stacks = mAm.getAllStackInfos();
            for (StackInfo info : stacks) {
                System.out.println(info);
            }
        } catch (RemoteException e) {
        }
    }

    private void runStackInfo() throws Exception {
        try {
            String stackIdStr = nextArgRequired();
            int stackId = Integer.parseInt(stackIdStr);
            StackInfo info = mAm.getStackInfo(stackId);
            System.out.println(info);
        } catch (RemoteException e) {
        }
    }

    private void runStackRemove() throws Exception {
        String stackIdStr = nextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        mAm.removeStack(stackId);
    }

    private void runMoveTopActivityToPinnedStack() throws Exception {
        int stackId = Integer.parseInt(nextArgRequired());
        final Rect bounds = getBounds();
        if (bounds == null) {
            System.err.println("Error: invalid input bounds");
            return;
        }

        try {
            if (!mAm.moveTopActivityToPinnedStack(stackId, bounds)) {
                showError("Didn't move top activity to pinned stack.");
            }
        } catch (RemoteException e) {
            showError("Unable to move top activity: " + e);
            return;
        }
    }

    private void runStackSizeDockedStackTest() throws Exception {
        final int stepSize = Integer.parseInt(nextArgRequired());
        final String side = nextArgRequired();
        final String delayStr = nextArg();
        final int delayMs = (delayStr != null) ? Integer.parseInt(delayStr) : 0;

        Rect bounds;
        try {
            StackInfo info = mAm.getStackInfo(DOCKED_STACK_ID);
            if (info == null) {
                showError("Docked stack doesn't exist");
                return;
            }
            if (info.bounds == null) {
                showError("Docked stack doesn't have a bounds");
                return;
            }
            bounds = info.bounds;
        } catch (RemoteException e) {
            showError("Unable to get docked stack info:" + e);
            return;
        }

        final boolean horizontalGrowth = "l".equals(side) || "r".equals(side);
        final int changeSize = (horizontalGrowth ? bounds.width() : bounds.height()) / 2;
        int currentPoint;
        switch (side) {
            case "l":
                currentPoint = bounds.left;
                break;
            case "r":
                currentPoint = bounds.right;
                break;
            case "t":
                currentPoint = bounds.top;
                break;
            case "b":
                currentPoint = bounds.bottom;
                break;
            default:
                showError("Unknown growth side: " + side);
                return;
        }

        final int startPoint = currentPoint;
        final int minPoint = currentPoint - changeSize;
        final int maxPoint = currentPoint + changeSize;

        int maxChange;
        System.out.println("Shrinking docked stack side=" + side);
        while (currentPoint > minPoint) {
            maxChange = Math.min(stepSize, currentPoint - minPoint);
            currentPoint -= maxChange;
            setBoundsSide(bounds, side, currentPoint);
            resizeStack(DOCKED_STACK_ID, bounds, delayMs);
        }

        System.out.println("Growing docked stack side=" + side);
        while (currentPoint < maxPoint) {
            maxChange = Math.min(stepSize, maxPoint - currentPoint);
            currentPoint += maxChange;
            setBoundsSide(bounds, side, currentPoint);
            resizeStack(DOCKED_STACK_ID, bounds, delayMs);
        }

        System.out.println("Back to Original size side=" + side);
        while (currentPoint > startPoint) {
            maxChange = Math.min(stepSize, currentPoint - startPoint);
            currentPoint -= maxChange;
            setBoundsSide(bounds, side, currentPoint);
            resizeStack(DOCKED_STACK_ID, bounds, delayMs);
        }
    }

    private void setBoundsSide(Rect bounds, String side, int value) {
        switch (side) {
            case "l":
                bounds.left = value;
                break;
            case "r":
                bounds.right = value;
                break;
            case "t":
                bounds.top = value;
                break;
            case "b":
                bounds.bottom = value;
                break;
            default:
                showError("Unknown set side: " + side);
                break;
        }
    }

    private void runTask() throws Exception {
        String op = nextArgRequired();
        if (op.equals("lock")) {
            runTaskLock();
        } else if (op.equals("resizeable")) {
            runTaskResizeable();
        } else if (op.equals("resize")) {
            runTaskResize();
        } else if (op.equals("drag-task-test")) {
            runTaskDragTaskTest();
        } else if (op.equals("size-task-test")) {
            runTaskSizeTaskTest();
        } else {
            showError("Error: unknown command '" + op + "'");
            return;
        }
    }

    private void runTaskLock() throws Exception {
        String taskIdStr = nextArgRequired();
        try {
            if (taskIdStr.equals("stop")) {
                mAm.stopLockTaskMode();
            } else {
                int taskId = Integer.parseInt(taskIdStr);
                mAm.startLockTaskMode(taskId);
            }
            System.err.println("Activity manager is " + (mAm.isInLockTaskMode() ? "" : "not ") +
                    "in lockTaskMode");
        } catch (RemoteException e) {
        }
    }

    private void runTaskResizeable() throws Exception {
        final String taskIdStr = nextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final String resizeableStr = nextArgRequired();
        final int resizeableMode = Integer.parseInt(resizeableStr);

        try {
            mAm.setTaskResizeable(taskId, resizeableMode);
        } catch (RemoteException e) {
        }
    }

    private void runTaskResize() throws Exception {
        final String taskIdStr = nextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final Rect bounds = getBounds();
        if (bounds == null) {
            System.err.println("Error: invalid input bounds");
            return;
        }
        taskResize(taskId, bounds, 0, false);
    }

    private void taskResize(int taskId, Rect bounds, int delay_ms, boolean pretendUserResize) {
        try {
            final int resizeMode = pretendUserResize ? RESIZE_MODE_USER : RESIZE_MODE_SYSTEM;
            mAm.resizeTask(taskId, bounds, resizeMode);
            Thread.sleep(delay_ms);
        } catch (RemoteException e) {
            System.err.println("Error changing task bounds: " + e);
        } catch (InterruptedException e) {
        }
    }

    private void runTaskDragTaskTest() {
        final int taskId = Integer.parseInt(nextArgRequired());
        final int stepSize = Integer.parseInt(nextArgRequired());
        final String delayStr = nextArg();
        final int delay_ms = (delayStr != null) ? Integer.parseInt(delayStr) : 0;
        final StackInfo stackInfo;
        Rect taskBounds;
        try {
            stackInfo = mAm.getStackInfo(mAm.getFocusedStackId());
            taskBounds = mAm.getTaskBounds(taskId);
        } catch (RemoteException e) {
            System.err.println("Error getting focus stack info or task bounds: " + e);
            return;
        }
        final Rect stackBounds = stackInfo.bounds;
        int travelRight = stackBounds.width() - taskBounds.width();
        int travelLeft = -travelRight;
        int travelDown = stackBounds.height() - taskBounds.height();
        int travelUp = -travelDown;
        int passes = 0;

        // We do 2 passes to get back to the original location of the task.
        while (passes < 2) {
            // Move right
            System.out.println("Moving right...");
            travelRight = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelRight, MOVING_FORWARD, MOVING_HORIZONTALLY, delay_ms);
            System.out.println("Still need to travel right by " + travelRight);

            // Move down
            System.out.println("Moving down...");
            travelDown = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelDown, MOVING_FORWARD, !MOVING_HORIZONTALLY, delay_ms);
            System.out.println("Still need to travel down by " + travelDown);

            // Move left
            System.out.println("Moving left...");
            travelLeft = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelLeft, !MOVING_FORWARD, MOVING_HORIZONTALLY, delay_ms);
            System.out.println("Still need to travel left by " + travelLeft);

            // Move up
            System.out.println("Moving up...");
            travelUp = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelUp, !MOVING_FORWARD, !MOVING_HORIZONTALLY, delay_ms);
            System.out.println("Still need to travel up by " + travelUp);

            try {
                taskBounds = mAm.getTaskBounds(taskId);
            } catch (RemoteException e) {
                System.err.println("Error getting task bounds: " + e);
                return;
            }
            passes++;
        }
    }

    private int moveTask(int taskId, Rect taskRect, Rect stackRect, int stepSize,
            int maxToTravel, boolean movingForward, boolean horizontal, int delay_ms) {
        int maxMove;
        if (movingForward) {
            while (maxToTravel > 0
                    && ((horizontal && taskRect.right < stackRect.right)
                        ||(!horizontal && taskRect.bottom < stackRect.bottom))) {
                if (horizontal) {
                    maxMove = Math.min(stepSize, stackRect.right - taskRect.right);
                    maxToTravel -= maxMove;
                    taskRect.right += maxMove;
                    taskRect.left += maxMove;
                } else {
                    maxMove = Math.min(stepSize, stackRect.bottom - taskRect.bottom);
                    maxToTravel -= maxMove;
                    taskRect.top += maxMove;
                    taskRect.bottom += maxMove;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        } else {
            while (maxToTravel < 0
                    && ((horizontal && taskRect.left > stackRect.left)
                    ||(!horizontal && taskRect.top > stackRect.top))) {
                if (horizontal) {
                    maxMove = Math.min(stepSize, taskRect.left - stackRect.left);
                    maxToTravel -= maxMove;
                    taskRect.right -= maxMove;
                    taskRect.left -= maxMove;
                } else {
                    maxMove = Math.min(stepSize, taskRect.top - stackRect.top);
                    maxToTravel -= maxMove;
                    taskRect.top -= maxMove;
                    taskRect.bottom -= maxMove;
                }
                taskResize(taskId, taskRect, delay_ms, false);
            }
        }
        // Return the remaining distance we didn't travel because we reached the target location.
        return maxToTravel;
    }

    private void runTaskSizeTaskTest() {
        final int taskId = Integer.parseInt(nextArgRequired());
        final int stepSize = Integer.parseInt(nextArgRequired());
        final String delayStr = nextArg();
        final int delay_ms = (delayStr != null) ? Integer.parseInt(delayStr) : 0;
        final StackInfo stackInfo;
        final Rect initialTaskBounds;
        try {
            stackInfo = mAm.getStackInfo(mAm.getFocusedStackId());
            initialTaskBounds = mAm.getTaskBounds(taskId);
        } catch (RemoteException e) {
            System.err.println("Error getting focus stack info or task bounds: " + e);
            return;
        }
        final Rect stackBounds = stackInfo.bounds;
        stackBounds.inset(STACK_BOUNDS_INSET, STACK_BOUNDS_INSET);
        final Rect currentTaskBounds = new Rect(initialTaskBounds);

        // Size by top-left
        System.out.println("Growing top-left");
        do {
            currentTaskBounds.top -= getStepSize(
                    currentTaskBounds.top, stackBounds.top, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.left -= getStepSize(
                    currentTaskBounds.left, stackBounds.left, stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.top < currentTaskBounds.top
                || stackBounds.left < currentTaskBounds.left);

        // Back to original size
        System.out.println("Shrinking top-left");
        do {
            currentTaskBounds.top += getStepSize(
                    currentTaskBounds.top, initialTaskBounds.top, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.left += getStepSize(
                    currentTaskBounds.left, initialTaskBounds.left, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.top > currentTaskBounds.top
                || initialTaskBounds.left > currentTaskBounds.left);

        // Size by top-right
        System.out.println("Growing top-right");
        do {
            currentTaskBounds.top -= getStepSize(
                    currentTaskBounds.top, stackBounds.top, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.right += getStepSize(
                    currentTaskBounds.right, stackBounds.right, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.top < currentTaskBounds.top
                || stackBounds.right > currentTaskBounds.right);

        // Back to original size
        System.out.println("Shrinking top-right");
        do {
            currentTaskBounds.top += getStepSize(
                    currentTaskBounds.top, initialTaskBounds.top, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.right -= getStepSize(currentTaskBounds.right, initialTaskBounds.right,
                    stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.top > currentTaskBounds.top
                || initialTaskBounds.right < currentTaskBounds.right);

        // Size by bottom-left
        System.out.println("Growing bottom-left");
        do {
            currentTaskBounds.bottom += getStepSize(
                    currentTaskBounds.bottom, stackBounds.bottom, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.left -= getStepSize(
                    currentTaskBounds.left, stackBounds.left, stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.bottom > currentTaskBounds.bottom
                || stackBounds.left < currentTaskBounds.left);

        // Back to original size
        System.out.println("Shrinking bottom-left");
        do {
            currentTaskBounds.bottom -= getStepSize(currentTaskBounds.bottom,
                    initialTaskBounds.bottom, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.left += getStepSize(
                    currentTaskBounds.left, initialTaskBounds.left, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.bottom < currentTaskBounds.bottom
                || initialTaskBounds.left > currentTaskBounds.left);

        // Size by bottom-right
        System.out.println("Growing bottom-right");
        do {
            currentTaskBounds.bottom += getStepSize(
                    currentTaskBounds.bottom, stackBounds.bottom, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.right += getStepSize(
                    currentTaskBounds.right, stackBounds.right, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.bottom > currentTaskBounds.bottom
                || stackBounds.right > currentTaskBounds.right);

        // Back to original size
        System.out.println("Shrinking bottom-right");
        do {
            currentTaskBounds.bottom -= getStepSize(currentTaskBounds.bottom,
                    initialTaskBounds.bottom, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.right -= getStepSize(currentTaskBounds.right, initialTaskBounds.right,
                    stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.bottom < currentTaskBounds.bottom
                || initialTaskBounds.right < currentTaskBounds.right);
    }

    private int getStepSize(int current, int target, int inStepSize, boolean greaterThanTarget) {
        int stepSize = 0;
        if (greaterThanTarget && target < current) {
            current -= inStepSize;
            stepSize = inStepSize;
            if (target > current) {
                stepSize -= (target - current);
            }
        }
        if (!greaterThanTarget && target > current) {
            current += inStepSize;
            stepSize = inStepSize;
            if (target < current) {
                stepSize += (current - target);
            }
        }
        return stepSize;
    }

    private List<Configuration> getRecentConfigurations(int days) {
        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                    Context.USAGE_STATS_SERVICE));
        final long now = System.currentTimeMillis();
        final long nDaysAgo = now - (days * 24 * 60 * 60 * 1000);
        try {
            @SuppressWarnings("unchecked")
            ParceledListSlice<ConfigurationStats> configStatsSlice = usm.queryConfigurationStats(
                    UsageStatsManager.INTERVAL_BEST, nDaysAgo, now, "com.android.shell");
            if (configStatsSlice == null) {
                return Collections.emptyList();
            }

            final ArrayMap<Configuration, Integer> recentConfigs = new ArrayMap<>();
            final List<ConfigurationStats> configStatsList = configStatsSlice.getList();
            final int configStatsListSize = configStatsList.size();
            for (int i = 0; i < configStatsListSize; i++) {
                final ConfigurationStats stats = configStatsList.get(i);
                final int indexOfKey = recentConfigs.indexOfKey(stats.getConfiguration());
                if (indexOfKey < 0) {
                    recentConfigs.put(stats.getConfiguration(), stats.getActivationCount());
                } else {
                    recentConfigs.setValueAt(indexOfKey,
                            recentConfigs.valueAt(indexOfKey) + stats.getActivationCount());
                }
            }

            final Comparator<Configuration> comparator = new Comparator<Configuration>() {
                @Override
                public int compare(Configuration a, Configuration b) {
                    return recentConfigs.get(b).compareTo(recentConfigs.get(a));
                }
            };

            ArrayList<Configuration> configs = new ArrayList<>(recentConfigs.size());
            configs.addAll(recentConfigs.keySet());
            Collections.sort(configs, comparator);
            return configs;

        } catch (RemoteException e) {
            return Collections.emptyList();
        }
    }

    private void runGetConfig() throws Exception {
        int days = 14;
        String option = nextOption();
        if (option != null) {
            if (!option.equals("--days")) {
                throw new IllegalArgumentException("unrecognized option " + option);
            }

            days = Integer.parseInt(nextArgRequired());
            if (days <= 0) {
                throw new IllegalArgumentException("--days must be a positive integer");
            }
        }

        try {
            Configuration config = mAm.getConfiguration();
            if (config == null) {
                System.err.println("Activity manager has no configuration");
                return;
            }

            System.out.println("config: " + Configuration.resourceQualifierString(config));
            System.out.println("abi: " + TextUtils.join(",", Build.SUPPORTED_ABIS));

            final List<Configuration> recentConfigs = getRecentConfigurations(days);
            final int recentConfigSize = recentConfigs.size();
            if (recentConfigSize > 0) {
                System.out.println("recentConfigs:");
            }

            for (int i = 0; i < recentConfigSize; i++) {
                System.out.println("  config: " + Configuration.resourceQualifierString(
                        recentConfigs.get(i)));
            }

        } catch (RemoteException e) {
        }
    }

    private void runSuppressResizeConfigChanges() throws Exception {
        boolean suppress = Boolean.valueOf(nextArgRequired());

        try {
            mAm.suppressResizeConfigChanges(suppress);
        } catch (RemoteException e) {
            System.err.println("Error suppressing resize config changes: " + e);
        }
    }

    private void runSetInactive() throws Exception {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        String packageName = nextArgRequired();
        String value = nextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        usm.setAppInactive(packageName, Boolean.parseBoolean(value), userId);
    }

    private void runGetInactive() throws Exception {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }
        String packageName = nextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        boolean isIdle = usm.isAppInactive(packageName, userId);
        System.out.println("Idle=" + isIdle);
    }

    private void runSendTrimMemory() throws Exception {
        int userId = UserHandle.USER_CURRENT;
        String opt;
        while ((opt = nextOption()) != null) {
            if (opt.equals("--user")) {
                userId = parseUserArg(nextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    System.err.println("Error: Can't use user 'all'");
                    return;
                }
            } else {
                System.err.println("Error: Unknown option: " + opt);
                return;
            }
        }

        String proc = nextArgRequired();
        String levelArg = nextArgRequired();
        int level;
        switch (levelArg) {
            case "HIDDEN":
                level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                break;
            case "RUNNING_MODERATE":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
                break;
            case "BACKGROUND":
                level = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                break;
            case "RUNNING_LOW":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                break;
            case "MODERATE":
                level = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                break;
            case "RUNNING_CRITICAL":
                level = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
                break;
            case "COMPLETE":
                level = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
                break;
            default:
                System.err.println("Error: Unknown level option: " + levelArg);
                return;
        }
        if (!mAm.setProcessMemoryTrimLevel(proc, userId, level)) {
            System.err.println("Error: Failure to set the level - probably Unknown Process: " +
                               proc);
        }
    }

    private void runGetCurrentUser() throws Exception {
        UserInfo currentUser = Preconditions.checkNotNull(mAm.getCurrentUser(),
                "Current user not set");
        System.out.println(currentUser.id);
    }

    private void runSupportsMultiwindow() throws Exception {
        // system resources does not contain all the device configuration, construct it manually.
        Configuration config = mAm.getConfiguration();
        if (config == null) {
            throw new AndroidException("Activity manager has no configuration");
        }

        final DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();

        Resources res = new Resources(AssetManager.getSystem(), metrics, config);

        System.out.println(res.getBoolean(com.android.internal.R.bool.config_supportsMultiWindow));
    }

    /**
     * Open the given file for sending into the system process. This verifies
     * with SELinux that the system will have access to the file.
     */
    private static ParcelFileDescriptor openForSystemServer(File file, int mode)
            throws FileNotFoundException {
        final ParcelFileDescriptor fd = ParcelFileDescriptor.open(file, mode);
        final String tcon = SELinux.getFileContext(file.getAbsolutePath());
        if (!SELinux.checkSELinuxAccess("u:r:system_server:s0", tcon, "file", "read")) {
            throw new FileNotFoundException("System server has no access to file context " + tcon);
        }
        return fd;
    }

    private Rect getBounds() {
        String leftStr = nextArgRequired();
        int left = Integer.parseInt(leftStr);
        String topStr = nextArgRequired();
        int top = Integer.parseInt(topStr);
        String rightStr = nextArgRequired();
        int right = Integer.parseInt(rightStr);
        String bottomStr = nextArgRequired();
        int bottom = Integer.parseInt(bottomStr);
        if (left < 0) {
            System.err.println("Error: bad left arg: " + leftStr);
            return null;
        }
        if (top < 0) {
            System.err.println("Error: bad top arg: " + topStr);
            return null;
        }
        if (right <= 0) {
            System.err.println("Error: bad right arg: " + rightStr);
            return null;
        }
        if (bottom <= 0) {
            System.err.println("Error: bad bottom arg: " + bottomStr);
            return null;
        }
        return new Rect(left, top, right, bottom);
    }
}
