/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.am;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AppGlobals;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IStopUserCallback;
import android.app.IUidObserver;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.usage.ConfigurationStats;
import android.app.usage.IUsageStatsManager;
import android.app.usage.UsageStatsManager;
import android.content.ComponentCallbacks2;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.IPackageManager;
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
import android.os.ServiceManager;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.DisplayMetrics;

import com.android.internal.util.HexDump;
import com.android.internal.util.Preconditions;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static android.app.ActivityManager.RESIZE_MODE_SYSTEM;
import static android.app.ActivityManager.RESIZE_MODE_USER;
import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.app.ActivityManager.StackId.INVALID_STACK_ID;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.am.TaskRecord.INVALID_TASK_ID;

final class ActivityManagerShellCommand extends ShellCommand {
    public static final String NO_CLASS_ERROR_CODE = "Error type 3";
    private static final String SHELL_PACKAGE_NAME = "com.android.shell";

    // Is the object moving in a positive direction?
    private static final boolean MOVING_FORWARD = true;
    // Is the object moving in the horizontal plan?
    private static final boolean MOVING_HORIZONTALLY = true;
    // Is the object current point great then its target point?
    private static final boolean GREATER_THAN_TARGET = true;
    // Amount we reduce the stack size by when testing a task re-size.
    private static final int STACK_BOUNDS_INSET = 10;

    // IPC interface to activity manager -- don't need to do additional security checks.
    final IActivityManager mInterface;

    // Internal service impl -- must perform security checks before touching.
    final ActivityManagerService mInternal;

    // Convenience for interacting with package manager.
    final IPackageManager mPm;

    private int mStartFlags = 0;
    private boolean mWaitOption = false;
    private boolean mStopOption = false;

    private int mRepeat = 0;
    private int mUserId;
    private String mReceiverPermission;

    private String mProfileFile;
    private int mSamplingInterval;
    private boolean mAutoStop;
    private boolean mStreaming;   // Streaming the profiling output to a file.
    private String mAgent;  // Agent to attach on startup.
    private int mDisplayId;
    private int mStackId;
    private int mTaskId;
    private boolean mIsTaskOverlay;

    final boolean mDumping;

    ActivityManagerShellCommand(ActivityManagerService service, boolean dumping) {
        mInterface = service;
        mInternal = service;
        mPm = AppGlobals.getPackageManager();
        mDumping = dumping;
    }

    @Override
    public int onCommand(String cmd) {
        if (cmd == null) {
            return handleDefaultCommands(cmd);
        }
        PrintWriter pw = getOutPrintWriter();
        try {
            switch (cmd) {
                case "start":
                case "start-activity":
                    return runStartActivity(pw);
                case "startservice":
                case "start-service":
                    return runStartService(pw, false);
                case "startforegroundservice":
                case "startfgservice":
                case "start-foreground-service":
                case "start-fg-service":
                    return runStartService(pw, true);
                case "stopservice":
                case "stop-service":
                    return runStopService(pw);
                case "broadcast":
                    return runSendBroadcast(pw);
                case "instrument":
                    getOutPrintWriter().println("Error: must be invoked through 'am instrument'.");
                    return -1;
                case "trace-ipc":
                    return runTraceIpc(pw);
                case "profile":
                    return runProfile(pw);
                case "dumpheap":
                    return runDumpHeap(pw);
                case "set-debug-app":
                    return runSetDebugApp(pw);
                case "clear-debug-app":
                    return runClearDebugApp(pw);
                case "set-watch-heap":
                    return runSetWatchHeap(pw);
                case "clear-watch-heap":
                    return runClearWatchHeap(pw);
                case "bug-report":
                    return runBugReport(pw);
                case "force-stop":
                    return runForceStop(pw);
                case "crash":
                    return runCrash(pw);
                case "kill":
                    return runKill(pw);
                case "kill-all":
                    return runKillAll(pw);
                case "make-uid-idle":
                    return runMakeIdle(pw);
                case "monitor":
                    return runMonitor(pw);
                case "watch-uids":
                    return runWatchUids(pw);
                case "hang":
                    return runHang(pw);
                case "restart":
                    return runRestart(pw);
                case "idle-maintenance":
                    return runIdleMaintenance(pw);
                case "screen-compat":
                    return runScreenCompat(pw);
                case "package-importance":
                    return runPackageImportance(pw);
                case "to-uri":
                    return runToUri(pw, 0);
                case "to-intent-uri":
                    return runToUri(pw, Intent.URI_INTENT_SCHEME);
                case "to-app-uri":
                    return runToUri(pw, Intent.URI_ANDROID_APP_SCHEME);
                case "switch-user":
                    return runSwitchUser(pw);
                case "get-current-user":
                    return runGetCurrentUser(pw);
                case "start-user":
                    return runStartUser(pw);
                case "unlock-user":
                    return runUnlockUser(pw);
                case "stop-user":
                    return runStopUser(pw);
                case "is-user-stopped":
                    return runIsUserStopped(pw);
                case "get-started-user-state":
                    return runGetStartedUserState(pw);
                case "track-associations":
                    return runTrackAssociations(pw);
                case "untrack-associations":
                    return runUntrackAssociations(pw);
                case "get-uid-state":
                    return getUidState(pw);
                case "get-config":
                    return runGetConfig(pw);
                case "suppress-resize-config-changes":
                    return runSuppressResizeConfigChanges(pw);
                case "set-inactive":
                    return runSetInactive(pw);
                case "get-inactive":
                    return runGetInactive(pw);
                case "send-trim-memory":
                    return runSendTrimMemory(pw);
                case "display":
                    return runDisplay(pw);
                case "stack":
                    return runStack(pw);
                case "task":
                    return runTask(pw);
                case "write":
                    return runWrite(pw);
                case "attach-agent":
                    return runAttachAgent(pw);
                case "supports-multiwindow":
                    return runSupportsMultiwindow(pw);
                case "supports-split-screen-multi-window":
                    return runSupportsSplitScreenMultiwindow(pw);
                case "update-appinfo":
                    return runUpdateApplicationInfo(pw);
                case "no-home-screen":
                    return runNoHomeScreen(pw);
                case "wait-for-broadcast-idle":
                    return runWaitForBroadcastIdle(pw);
                default:
                    return handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private Intent makeIntent(int defUser) throws URISyntaxException {
        mStartFlags = 0;
        mWaitOption = false;
        mStopOption = false;
        mRepeat = 0;
        mProfileFile = null;
        mSamplingInterval = 0;
        mAutoStop = false;
        mStreaming = false;
        mUserId = defUser;
        mDisplayId = INVALID_DISPLAY;
        mStackId = INVALID_STACK_ID;
        mTaskId = INVALID_TASK_ID;
        mIsTaskOverlay = false;

        return Intent.parseCommandArgs(this, new Intent.CommandOptionHandler() {
            @Override
            public boolean handleOption(String opt, ShellCommand cmd) {
                if (opt.equals("-D")) {
                    mStartFlags |= ActivityManager.START_FLAG_DEBUG;
                } else if (opt.equals("-N")) {
                    mStartFlags |= ActivityManager.START_FLAG_NATIVE_DEBUGGING;
                } else if (opt.equals("-W")) {
                    mWaitOption = true;
                } else if (opt.equals("-P")) {
                    mProfileFile = getNextArgRequired();
                    mAutoStop = true;
                } else if (opt.equals("--start-profiler")) {
                    mProfileFile = getNextArgRequired();
                    mAutoStop = false;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--streaming")) {
                    mStreaming = true;
                } else if (opt.equals("--attach-agent")) {
                    mAgent = getNextArgRequired();
                } else if (opt.equals("-R")) {
                    mRepeat = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("-S")) {
                    mStopOption = true;
                } else if (opt.equals("--track-allocation")) {
                    mStartFlags |= ActivityManager.START_FLAG_TRACK_ALLOCATION;
                } else if (opt.equals("--user")) {
                    mUserId = UserHandle.parseUserArg(getNextArgRequired());
                } else if (opt.equals("--receiver-permission")) {
                    mReceiverPermission = getNextArgRequired();
                } else if (opt.equals("--display")) {
                    mDisplayId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--stack")) {
                    mStackId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--task")) {
                    mTaskId = Integer.parseInt(getNextArgRequired());
                } else if (opt.equals("--task-overlay")) {
                    mIsTaskOverlay = true;
                } else {
                    return false;
                }
                return true;
            }
        });
    }

    int runStartActivity(PrintWriter pw) throws RemoteException {
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        if (mUserId == UserHandle.USER_ALL) {
            getErrPrintWriter().println("Error: Can't start service with user 'all'");
            return 1;
        }

        String mimeType = intent.getType();
        if (mimeType == null && intent.getData() != null
                && "content".equals(intent.getData().getScheme())) {
            mimeType = mInterface.getProviderMimeType(intent.getData(), mUserId);
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
                        getErrPrintWriter().println("Error: Intent does not match any activities: "
                                + intent);
                        return 1;
                    } else if (activities.size() > 1) {
                        getErrPrintWriter().println(
                                "Error: Intent matches multiple activities; can't stop: "
                                + intent);
                        return 1;
                    }
                    packageName = activities.get(0).activityInfo.packageName;
                }
                pw.println("Stopping: " + packageName);
                pw.flush();
                mInterface.forceStopPackage(packageName, mUserId);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                }
            }

            ProfilerInfo profilerInfo = null;

            if (mProfileFile != null || mAgent != null) {
                ParcelFileDescriptor fd = null;
                if (mProfileFile != null) {
                    fd = openOutputFileForSystem(mProfileFile);
                    if (fd == null) {
                        return 1;
                    }
                }
                profilerInfo = new ProfilerInfo(mProfileFile, fd, mSamplingInterval, mAutoStop,
                        mStreaming, mAgent);
            }

            pw.println("Starting: " + intent);
            pw.flush();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            WaitResult result = null;
            int res;
            final long startTime = SystemClock.uptimeMillis();
            ActivityOptions options = null;
            if (mDisplayId != INVALID_DISPLAY) {
                options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(mDisplayId);
            }
            if (mStackId != INVALID_STACK_ID) {
                options = ActivityOptions.makeBasic();
                options.setLaunchStackId(mStackId);
            }
            if (mTaskId != INVALID_TASK_ID) {
                options = ActivityOptions.makeBasic();
                options.setLaunchTaskId(mTaskId);

                if (mIsTaskOverlay) {
                    options.setTaskOverlay(true, true /* canResume */);
                }
            }
            if (mWaitOption) {
                result = mInterface.startActivityAndWait(null, null, intent, mimeType,
                        null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
                res = result.result;
            } else {
                res = mInterface.startActivityAsUser(null, null, intent, mimeType,
                        null, null, 0, mStartFlags, profilerInfo,
                        options != null ? options.toBundle() : null, mUserId);
            }
            final long endTime = SystemClock.uptimeMillis();
            PrintWriter out = mWaitOption ? pw : getErrPrintWriter();
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
            out.flush();
            if (mWaitOption && launched) {
                if (result == null) {
                    result = new WaitResult();
                    result.who = intent.getComponent();
                }
                pw.println("Status: " + (result.timeout ? "timeout" : "ok"));
                if (result.who != null) {
                    pw.println("Activity: " + result.who.flattenToShortString());
                }
                if (result.thisTime >= 0) {
                    pw.println("ThisTime: " + result.thisTime);
                }
                if (result.totalTime >= 0) {
                    pw.println("TotalTime: " + result.totalTime);
                }
                pw.println("WaitTime: " + (endTime-startTime));
                pw.println("Complete");
                pw.flush();
            }
            mRepeat--;
            if (mRepeat > 0) {
                mInterface.unhandledBack();
            }
        } while (mRepeat > 0);
        return 0;
    }

    int runStartService(PrintWriter pw, boolean asForeground) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (mUserId == UserHandle.USER_ALL) {
            err.println("Error: Can't start activity with user 'all'");
            return -1;
        }
        pw.println("Starting service: " + intent);
        pw.flush();
        ComponentName cn = mInterface.startService(null, intent, intent.getType(),
                asForeground, SHELL_PACKAGE_NAME, mUserId);
        if (cn == null) {
            err.println("Error: Not found; no service started.");
            return -1;
        } else if (cn.getPackageName().equals("!")) {
            err.println("Error: Requires permission " + cn.getClassName());
            return -1;
        } else if (cn.getPackageName().equals("!!")) {
            err.println("Error: " + cn.getClassName());
            return -1;
        } else if (cn.getPackageName().equals("?")) {
            err.println("Error: " + cn.getClassName());
            return -1;
        }
        return 0;
    }

    int runStopService(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        if (mUserId == UserHandle.USER_ALL) {
            err.println("Error: Can't stop activity with user 'all'");
            return -1;
        }
        pw.println("Stopping service: " + intent);
        pw.flush();
        int result = mInterface.stopService(null, intent, intent.getType(), mUserId);
        if (result == 0) {
            err.println("Service not stopped: was not running.");
            return -1;
        } else if (result == 1) {
            err.println("Service stopped");
            return -1;
        } else if (result == -1) {
            err.println("Error stopping service");
            return -1;
        }
        return 0;
    }

    final static class IntentReceiver extends IIntentReceiver.Stub {
        private final PrintWriter mPw;
        private boolean mFinished = false;

        IntentReceiver(PrintWriter pw) {
            mPw = pw;
        }

        @Override
        public void performReceive(Intent intent, int resultCode, String data, Bundle extras,
                boolean ordered, boolean sticky, int sendingUser) {
            String line = "Broadcast completed: result=" + resultCode;
            if (data != null) line = line + ", data=\"" + data + "\"";
            if (extras != null) line = line + ", extras: " + extras;
            mPw.println(line);
            mPw.flush();
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

    int runSendBroadcast(PrintWriter pw) throws RemoteException {
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        intent.addFlags(Intent.FLAG_RECEIVER_FROM_SHELL);
        IntentReceiver receiver = new IntentReceiver(pw);
        String[] requiredPermissions = mReceiverPermission == null ? null
                : new String[] {mReceiverPermission};
        pw.println("Broadcasting: " + intent);
        pw.flush();
        mInterface.broadcastIntent(null, intent, null, receiver, 0, null, null, requiredPermissions,
                android.app.AppOpsManager.OP_NONE, null, true, false, mUserId);
        receiver.waitForFinish();
        return 0;
    }

    int runTraceIpc(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("start")) {
            return runTraceIpcStart(pw);
        } else if (op.equals("stop")) {
            return runTraceIpcStop(pw);
        } else {
            getErrPrintWriter().println("Error: unknown trace ipc command '" + op + "'");
            return -1;
        }
    }

    int runTraceIpcStart(PrintWriter pw) throws RemoteException {
        pw.println("Starting IPC tracing.");
        pw.flush();
        mInterface.startBinderTracking();
        return 0;
    }

    int runTraceIpcStop(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        String opt;
        String filename = null;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--dump-file")) {
                filename = getNextArgRequired();
            } else {
                err.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        if (filename == null) {
            err.println("Error: Specify filename to dump logs to.");
            return -1;
        }

        File file = new File(filename);
        file.delete();
        ParcelFileDescriptor fd = openOutputFileForSystem(filename);
        if (fd == null) {
            return -1;
        }

        ;
        if (!mInterface.stopBinderTrackingAndDump(fd)) {
            err.println("STOP TRACE FAILED.");
            return -1;
        }

        pw.println("Stopped IPC tracing. Dumping logs to: " + filename);
        return 0;
    }

    static void removeWallOption() {
        String props = SystemProperties.get("dalvik.vm.extra-opts");
        if (props != null && props.contains("-Xprofile:wallclock")) {
            props = props.replace("-Xprofile:wallclock", "");
            props = props.trim();
            SystemProperties.set("dalvik.vm.extra-opts", props);
        }
    }

    private int runProfile(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        String profileFile = null;
        boolean start = false;
        boolean wall = false;
        int userId = UserHandle.USER_CURRENT;
        int profileType = 0;
        mSamplingInterval = 0;
        mStreaming = false;

        String process = null;

        String cmd = getNextArgRequired();

        if ("start".equals(cmd)) {
            start = true;
            String opt;
            while ((opt=getNextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else if (opt.equals("--wall")) {
                    wall = true;
                } else if (opt.equals("--streaming")) {
                    mStreaming = true;
                } else if (opt.equals("--sampling")) {
                    mSamplingInterval = Integer.parseInt(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            }
            process = getNextArgRequired();
        } else if ("stop".equals(cmd)) {
            String opt;
            while ((opt=getNextOption()) != null) {
                if (opt.equals("--user")) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    err.println("Error: Unknown option: " + opt);
                    return -1;
                }
            }
            process = getNextArg();
        } else {
            // Compatibility with old syntax: process is specified first.
            process = cmd;
            cmd = getNextArgRequired();
            if ("start".equals(cmd)) {
                start = true;
            } else if (!"stop".equals(cmd)) {
                throw new IllegalArgumentException("Profile command " + process + " not valid");
            }
        }

        if (userId == UserHandle.USER_ALL) {
            err.println("Error: Can't profile with user 'all'");
            return -1;
        }

        ParcelFileDescriptor fd = null;
        ProfilerInfo profilerInfo = null;

        if (start) {
            profileFile = getNextArgRequired();
            fd = openOutputFileForSystem(profileFile);
            if (fd == null) {
                return -1;
            }
            profilerInfo = new ProfilerInfo(profileFile, fd, mSamplingInterval, false, mStreaming,
                    null);
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
            if (!mInterface.profileControl(process, userId, start, profilerInfo, profileType)) {
                wall = false;
                err.println("PROFILE FAILED on process " + process);
                return -1;
            }
        } finally {
            if (!wall) {
                //removeWallOption();
            }
        }
        return 0;
    }

    int runDumpHeap(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        boolean managed = true;
        boolean mallocInfo = false;
        int userId = UserHandle.USER_CURRENT;
        boolean runGc = false;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    err.println("Error: Can't dump heap with user 'all'");
                    return -1;
                }
            } else if (opt.equals("-n")) {
                managed = false;
            } else if (opt.equals("-g")) {
                runGc = true;
            } else if (opt.equals("-m")) {
                managed = false;
                mallocInfo = true;
            } else {
                err.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String process = getNextArgRequired();
        String heapFile = getNextArgRequired();

        File file = new File(heapFile);
        file.delete();
        ParcelFileDescriptor fd = openOutputFileForSystem(heapFile);
        if (fd == null) {
            return -1;
        }

        if (!mInterface.dumpHeap(process, userId, managed, mallocInfo, runGc, heapFile, fd)) {
            err.println("HEAP DUMP FAILED on process " + process);
            return -1;
        }
        return 0;
    }

    int runSetDebugApp(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean persistent = false;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("-w")) {
                wait = true;
            } else if (opt.equals("--persistent")) {
                persistent = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        String pkg = getNextArgRequired();
        mInterface.setDebugApp(pkg, wait, persistent);
        return 0;
    }

    int runClearDebugApp(PrintWriter pw) throws RemoteException {
        mInterface.setDebugApp(null, false, true);
        return 0;
    }

    int runSetWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        String limit = getNextArgRequired();
        mInterface.setDumpHeapDebugLimit(proc, 0, Long.parseLong(limit), null);
        return 0;
    }

    int runClearWatchHeap(PrintWriter pw) throws RemoteException {
        String proc = getNextArgRequired();
        mInterface.setDumpHeapDebugLimit(proc, 0, -1, null);
        return 0;
    }

    int runBugReport(PrintWriter pw) throws RemoteException {
        String opt;
        int bugreportType = ActivityManager.BUGREPORT_OPTION_FULL;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--progress")) {
                bugreportType = ActivityManager.BUGREPORT_OPTION_INTERACTIVE;
            } else if (opt.equals("--telephony")) {
                bugreportType = ActivityManager.BUGREPORT_OPTION_TELEPHONY;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.requestBugReport(bugreportType);
        pw.println("Your lovely bug report is being created; please be patient.");
        return 0;
    }

    int runForceStop(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.forceStopPackage(getNextArgRequired(), userId);
        return 0;
    }

    int runCrash(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        int pid = -1;
        String packageName = null;
        final String arg = getNextArgRequired();
        // The argument is either a pid or a package name
        try {
            pid = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            packageName = arg;
        }
        mInterface.crashApplication(-1, pid, packageName, userId, "shell-induced crash");
        return 0;
    }

    int runKill(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.killBackgroundProcesses(getNextArgRequired(), userId);
        return 0;
    }

    int runKillAll(PrintWriter pw) throws RemoteException {
        mInterface.killAllBackgroundProcesses();
        return 0;
    }

    int runMakeIdle(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.makePackageIdle(getNextArgRequired(), userId);
        return 0;
    }

    static final class MyActivityController extends IActivityController.Stub {
        final IActivityManager mInterface;
        final PrintWriter mPw;
        final InputStream mInput;
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

        MyActivityController(IActivityManager iam, PrintWriter pw, InputStream input,
                String gdbPort, boolean monkey) {
            mInterface = iam;
            mPw = pw;
            mInput = input;
            mGdbPort = gdbPort;
            mMonkey = monkey;
        }

        @Override
        public boolean activityResuming(String pkg) {
            synchronized (this) {
                mPw.println("** Activity resuming: " + pkg);
                mPw.flush();
            }
            return true;
        }

        @Override
        public boolean activityStarting(Intent intent, String pkg) {
            synchronized (this) {
                mPw.println("** Activity starting: " + pkg);
                mPw.flush();
            }
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) {
            synchronized (this) {
                mPw.println("** ERROR: PROCESS CRASHED");
                mPw.println("processName: " + processName);
                mPw.println("processPid: " + pid);
                mPw.println("shortMsg: " + shortMsg);
                mPw.println("longMsg: " + longMsg);
                mPw.println("timeMillis: " + timeMillis);
                mPw.println("stack:");
                mPw.print(stackTrace);
                mPw.println("#");
                mPw.flush();
                int result = waitControllerLocked(pid, STATE_CRASHED);
                return result == RESULT_CRASH_KILL ? false : true;
            }
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation) {
            synchronized (this) {
                mPw.println("** ERROR: EARLY PROCESS NOT RESPONDING");
                mPw.println("processName: " + processName);
                mPw.println("processPid: " + pid);
                mPw.println("annotation: " + annotation);
                mPw.flush();
                int result = waitControllerLocked(pid, STATE_EARLY_ANR);
                if (result == RESULT_EARLY_ANR_KILL) return -1;
                return 0;
            }
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats) {
            synchronized (this) {
                mPw.println("** ERROR: PROCESS NOT RESPONDING");
                mPw.println("processName: " + processName);
                mPw.println("processPid: " + pid);
                mPw.println("processStats:");
                mPw.print(processStats);
                mPw.println("#");
                mPw.flush();
                int result = waitControllerLocked(pid, STATE_ANR);
                if (result == RESULT_ANR_KILL) return -1;
                if (result == RESULT_ANR_WAIT) return 1;
                return 0;
            }
        }

        @Override
        public int systemNotResponding(String message) {
            synchronized (this) {
                mPw.println("** ERROR: PROCESS NOT RESPONDING");
                mPw.println("message: " + message);
                mPw.println("#");
                mPw.println("Allowing system to die.");
                mPw.flush();
                return -1;
            }
        }

        void killGdbLocked() {
            mGotGdbPrint = false;
            if (mGdbProcess != null) {
                mPw.println("Stopping gdbserver");
                mPw.flush();
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
                    mPw.println("Starting gdbserver on port " + mGdbPort);
                    mPw.println("Do the following:");
                    mPw.println("  adb forward tcp:" + mGdbPort + " tcp:" + mGdbPort);
                    mPw.println("  gdbclient app_process :" + mGdbPort);
                    mPw.flush();

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
                                    mPw.println("GDB: " + line);
                                    mPw.flush();
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
                    mPw.println("Failure starting gdbserver: " + e);
                    mPw.flush();
                    killGdbLocked();
                }
            }
            mState = state;
            mPw.println("");
            printMessageForState();
            mPw.flush();

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
                    mPw.println("Monitoring activity manager...  available commands:");
                    break;
                case STATE_CRASHED:
                    mPw.println("Waiting after crash...  available commands:");
                    mPw.println("(c)ontinue: show crash dialog");
                    mPw.println("(k)ill: immediately kill app");
                    break;
                case STATE_EARLY_ANR:
                    mPw.println("Waiting after early ANR...  available commands:");
                    mPw.println("(c)ontinue: standard ANR processing");
                    mPw.println("(k)ill: immediately kill app");
                    break;
                case STATE_ANR:
                    mPw.println("Waiting after ANR...  available commands:");
                    mPw.println("(c)ontinue: show ANR dialog");
                    mPw.println("(k)ill: immediately kill app");
                    mPw.println("(w)ait: wait some more");
                    break;
            }
            mPw.println("(q)uit: finish monitoring");
        }

        void run() throws RemoteException {
            try {
                printMessageForState();
                mPw.flush();

                mInterface.setActivityController(this, mMonkey);
                mState = STATE_NORMAL;

                InputStreamReader converter = new InputStreamReader(mInput);
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
                            mPw.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_ANR_DIALOG);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_ANR_KILL);
                        } else if ("w".equals(line) || "wait".equals(line)) {
                            resumeController(RESULT_ANR_WAIT);
                        } else {
                            mPw.println("Invalid command: " + line);
                        }
                    } else if (mState == STATE_EARLY_ANR) {
                        if ("c".equals(line) || "continue".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_CONTINUE);
                        } else if ("k".equals(line) || "kill".equals(line)) {
                            resumeController(RESULT_EARLY_ANR_KILL);
                        } else {
                            mPw.println("Invalid command: " + line);
                        }
                    } else {
                        mPw.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            mPw.println("");
                        }
                        printMessageForState();
                        mPw.flush();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace(mPw);
                mPw.flush();
            } finally {
                mInterface.setActivityController(null, mMonkey);
            }
        }
    }

    int runMonitor(PrintWriter pw) throws RemoteException {
        String opt;
        String gdbPort = null;
        boolean monkey = false;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--gdb")) {
                gdbPort = getNextArgRequired();
            } else if (opt.equals("-m")) {
                monkey = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        MyActivityController controller = new MyActivityController(mInterface, pw,
                getRawInputStream(), gdbPort, monkey);
        controller.run();
        return 0;
    }

    static final class MyUidObserver extends IUidObserver.Stub
            implements ActivityManagerService.OomAdjObserver {
        final IActivityManager mInterface;
        final ActivityManagerService mInternal;
        final PrintWriter mPw;
        final InputStream mInput;
        final int mUid;

        static final int STATE_NORMAL = 0;

        int mState;

        MyUidObserver(ActivityManagerService service, PrintWriter pw, InputStream input, int uid) {
            mInterface = service;
            mInternal = service;
            mPw = pw;
            mInput = input;
            mUid = uid;
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq) throws RemoteException {
            synchronized (this) {
                mPw.print(uid);
                mPw.print(" procstate ");
                mPw.print(ProcessList.makeProcStateString(procState));
                mPw.print(" seq ");
                mPw.println(procStateSeq);
                mPw.flush();
            }
        }

        @Override
        public void onUidGone(int uid, boolean disabled) throws RemoteException {
            synchronized (this) {
                mPw.print(uid);
                mPw.print(" gone");
                if (disabled) {
                    mPw.print(" disabled");
                }
                mPw.println();
                mPw.flush();
            }
        }

        @Override
        public void onUidActive(int uid) throws RemoteException {
            synchronized (this) {
                mPw.print(uid);
                mPw.println(" active");
                mPw.flush();
            }
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) throws RemoteException {
            synchronized (this) {
                mPw.print(uid);
                mPw.print(" idle");
                if (disabled) {
                    mPw.print(" disabled");
                }
                mPw.println();
                mPw.flush();
            }
        }

        @Override
        public void onUidCachedChanged(int uid, boolean cached) throws RemoteException {
            synchronized (this) {
                mPw.print(uid);
                mPw.println(cached ? " cached" : " uncached");
                mPw.flush();
            }
        }

        @Override
        public void onOomAdjMessage(String msg) {
            synchronized (this) {
                mPw.print("# ");
                mPw.println(msg);
                mPw.flush();
            }
        }

        void printMessageForState() {
            switch (mState) {
                case STATE_NORMAL:
                    mPw.println("Watching uid states...  available commands:");
                    break;
            }
            mPw.println("(q)uit: finish watching");
        }

        void run() throws RemoteException {
            try {
                printMessageForState();
                mPw.flush();

                mInterface.registerUidObserver(this, ActivityManager.UID_OBSERVER_ACTIVE
                        | ActivityManager.UID_OBSERVER_GONE | ActivityManager.UID_OBSERVER_PROCSTATE
                        | ActivityManager.UID_OBSERVER_IDLE | ActivityManager.UID_OBSERVER_CACHED,
                        ActivityManager.PROCESS_STATE_UNKNOWN, null);
                if (mUid >= 0) {
                    mInternal.setOomAdjObserver(mUid, this);
                }
                mState = STATE_NORMAL;

                InputStreamReader converter = new InputStreamReader(mInput);
                BufferedReader in = new BufferedReader(converter);
                String line;

                while ((line = in.readLine()) != null) {
                    boolean addNewline = true;
                    if (line.length() <= 0) {
                        addNewline = false;
                    } else if ("q".equals(line) || "quit".equals(line)) {
                        break;
                    } else {
                        mPw.println("Invalid command: " + line);
                    }

                    synchronized (this) {
                        if (addNewline) {
                            mPw.println("");
                        }
                        printMessageForState();
                        mPw.flush();
                    }
                }

            } catch (IOException e) {
                e.printStackTrace(mPw);
                mPw.flush();
            } finally {
                if (mUid >= 0) {
                    mInternal.clearOomAdjObserver();
                }
                mInterface.unregisterUidObserver(this);
            }
        }
    }

    int runWatchUids(PrintWriter pw) throws RemoteException {
        String opt;
        int uid = -1;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--oom")) {
                uid = Integer.parseInt(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;

            }
        }

        MyUidObserver controller = new MyUidObserver(mInternal, pw, getRawInputStream(), uid);
        controller.run();
        return 0;
    }

    int runHang(PrintWriter pw) throws RemoteException {
        String opt;
        boolean allowRestart = false;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--allow-restart")) {
                allowRestart = true;
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        pw.println("Hanging the system...");
        pw.flush();
        mInterface.hang(new Binder(), allowRestart);
        return 0;
    }

    int runRestart(PrintWriter pw) throws RemoteException {
        String opt;
        while ((opt=getNextOption()) != null) {
            getErrPrintWriter().println("Error: Unknown option: " + opt);
            return -1;
        }

        pw.println("Restart the system...");
        pw.flush();
        mInterface.restart();
        return 0;
    }

    int runIdleMaintenance(PrintWriter pw) throws RemoteException {
        String opt;
        while ((opt=getNextOption()) != null) {
            getErrPrintWriter().println("Error: Unknown option: " + opt);
            return -1;
        }

        pw.println("Performing idle maintenance...");
        mInterface.sendIdleJobTrigger();
        return 0;
    }

    int runScreenCompat(PrintWriter pw) throws RemoteException {
        String mode = getNextArgRequired();
        boolean enabled;
        if ("on".equals(mode)) {
            enabled = true;
        } else if ("off".equals(mode)) {
            enabled = false;
        } else {
            getErrPrintWriter().println("Error: enabled mode must be 'on' or 'off' at " + mode);
            return -1;
        }

        String packageName = getNextArgRequired();
        do {
            try {
                mInterface.setPackageScreenCompatMode(packageName, enabled
                        ? ActivityManager.COMPAT_MODE_ENABLED
                        : ActivityManager.COMPAT_MODE_DISABLED);
            } catch (RemoteException e) {
            }
            packageName = getNextArg();
        } while (packageName != null);
        return 0;
    }

    int runPackageImportance(PrintWriter pw) throws RemoteException {
        String packageName = getNextArgRequired();
        int procState = mInterface.getPackageProcessState(packageName, "com.android.shell");
        pw.println(ActivityManager.RunningAppProcessInfo.procStateToImportance(procState));
        return 0;
    }

    int runToUri(PrintWriter pw, int flags) throws RemoteException {
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        pw.println(intent.toUri(flags));
        return 0;
    }

    int runSwitchUser(PrintWriter pw) throws RemoteException {
        String user = getNextArgRequired();
        mInterface.switchUser(Integer.parseInt(user));
        return 0;
    }

    int runGetCurrentUser(PrintWriter pw) throws RemoteException {
        UserInfo currentUser = Preconditions.checkNotNull(mInterface.getCurrentUser(),
                "Current user not set");
        pw.println(currentUser.id);
        return 0;
    }

    int runStartUser(PrintWriter pw) throws RemoteException {
        String user = getNextArgRequired();
        boolean success = mInterface.startUserInBackground(Integer.parseInt(user));
        if (success) {
            pw.println("Success: user started");
        } else {
            getErrPrintWriter().println("Error: could not start user");
        }
        return 0;
    }

    private static byte[] argToBytes(String arg) {
        if (arg.equals("!")) {
            return null;
        } else {
            return HexDump.hexStringToByteArray(arg);
        }
    }

    int runUnlockUser(PrintWriter pw) throws RemoteException {
        int userId = Integer.parseInt(getNextArgRequired());
        byte[] token = argToBytes(getNextArgRequired());
        byte[] secret = argToBytes(getNextArgRequired());
        boolean success = mInterface.unlockUser(userId, token, secret, null);
        if (success) {
            pw.println("Success: user unlocked");
        } else {
            getErrPrintWriter().println("Error: could not unlock user");
        }
        return 0;
    }

    static final class StopUserCallback extends IStopUserCallback.Stub {
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

    int runStopUser(PrintWriter pw) throws RemoteException {
        boolean wait = false;
        boolean force = false;
        String opt;
        while ((opt = getNextOption()) != null) {
            if ("-w".equals(opt)) {
                wait = true;
            } else if ("-f".equals(opt)) {
                force = true;
            } else {
                getErrPrintWriter().println("Error: unknown option: " + opt);
                return -1;
            }
        }
        int user = Integer.parseInt(getNextArgRequired());
        StopUserCallback callback = wait ? new StopUserCallback() : null;

        int res = mInterface.stopUser(user, force, callback);
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
            getErrPrintWriter().println("Switch failed: " + res + txt);
            return -1;
        } else if (callback != null) {
            callback.waitForFinish();
        }
        return 0;
    }

    int runIsUserStopped(PrintWriter pw) {
        int userId = UserHandle.parseUserArg(getNextArgRequired());
        boolean stopped = mInternal.isUserStopped(userId);
        pw.println(stopped);
        return 0;
    }

    int runGetStartedUserState(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "runGetStartedUserState()");
        final int userId = Integer.parseInt(getNextArgRequired());
        try {
            pw.println(mInternal.getStartedUserState(userId));
        } catch (NullPointerException e) {
            pw.println("User is not started: " + userId);
        }
        return 0;
    }

    int runTrackAssociations(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerUidObserver()");
        synchronized (mInternal) {
            if (!mInternal.mTrackingAssociations) {
                mInternal.mTrackingAssociations = true;
                pw.println("Association tracking started.");
            } else {
                pw.println("Association tracking already enabled.");
            }
        }
        return 0;
    }

    int runUntrackAssociations(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerUidObserver()");
        synchronized (mInternal) {
            if (mInternal.mTrackingAssociations) {
                mInternal.mTrackingAssociations = false;
                mInternal.mAssociations.clear();
                pw.println("Association tracking stopped.");
            } else {
                pw.println("Association tracking not running.");
            }
        }
        return 0;
    }

    int getUidState(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "getUidState()");
        int state = mInternal.getUidState(Integer.parseInt(getNextArgRequired()));
        pw.print(state);
        pw.print(" (");
        pw.printf(DebugUtils.valueToString(ActivityManager.class, "PROCESS_STATE_", state));
        pw.println(")");
        return 0;
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

    int runGetConfig(PrintWriter pw) throws RemoteException {
        int days = 14;
        String option = getNextOption();
        if (option != null) {
            if (!option.equals("--days")) {
                throw new IllegalArgumentException("unrecognized option " + option);
            }

            days = Integer.parseInt(getNextArgRequired());
            if (days <= 0) {
                throw new IllegalArgumentException("--days must be a positive integer");
            }
        }

        Configuration config = mInterface.getConfiguration();
        if (config == null) {
            getErrPrintWriter().println("Activity manager has no configuration");
            return -1;
        }

        pw.println("config: " + Configuration.resourceQualifierString(config));
        pw.println("abi: " + TextUtils.join(",", Build.SUPPORTED_ABIS));

        final List<Configuration> recentConfigs = getRecentConfigurations(days);
        final int recentConfigSize = recentConfigs.size();
        if (recentConfigSize > 0) {
            pw.println("recentConfigs:");
        }

        for (int i = 0; i < recentConfigSize; i++) {
            pw.println("  config: " + Configuration.resourceQualifierString(
                    recentConfigs.get(i)));
        }
        return 0;
    }

    int runSuppressResizeConfigChanges(PrintWriter pw) throws RemoteException {
        boolean suppress = Boolean.valueOf(getNextArgRequired());
        mInterface.suppressResizeConfigChanges(suppress);
        return 0;
    }

    int runSetInactive(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();
        String value = getNextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        usm.setAppInactive(packageName, Boolean.parseBoolean(value), userId);
        return 0;
    }

    int runGetInactive(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        String packageName = getNextArgRequired();

        IUsageStatsManager usm = IUsageStatsManager.Stub.asInterface(ServiceManager.getService(
                Context.USAGE_STATS_SERVICE));
        boolean isIdle = usm.isAppInactive(packageName, userId);
        pw.println("Idle=" + isIdle);
        return 0;
    }

    int runSendTrimMemory(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_CURRENT;
        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
                if (userId == UserHandle.USER_ALL) {
                    getErrPrintWriter().println("Error: Can't use user 'all'");
                    return -1;
                }
            } else {
                getErrPrintWriter().println("Error: Unknown option: " + opt);
                return -1;
            }
        }

        String proc = getNextArgRequired();
        String levelArg = getNextArgRequired();
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
                try {
                    level = Integer.parseInt(levelArg);
                } catch (NumberFormatException e) {
                    getErrPrintWriter().println("Error: Unknown level option: " + levelArg);
                    return -1;
                }
        }
        if (!mInterface.setProcessMemoryTrimLevel(proc, userId, level)) {
            getErrPrintWriter().println("Unknown error: failed to set trim level");
            return -1;
        }
        return 0;
    }

    int runDisplay(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        switch (op) {
            case "move-stack":
                return runDisplayMoveStack(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }

    int runStack(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        switch (op) {
            case "start":
                return runStackStart(pw);
            case "move-task":
                return runStackMoveTask(pw);
            case "resize":
                return runStackResize(pw);
            case "resize-animated":
                return runStackResizeAnimated(pw);
            case "resize-docked-stack":
                return runStackResizeDocked(pw);
            case "positiontask":
                return runStackPositionTask(pw);
            case "list":
                return runStackList(pw);
            case "info":
                return runStackInfo(pw);
            case "move-top-activity-to-pinned-stack":
                return runMoveTopActivityToPinnedStack(pw);
            case "size-docked-stack-test":
                return runStackSizeDockedStackTest(pw);
            case "remove":
                return runStackRemove(pw);
            default:
                getErrPrintWriter().println("Error: unknown command '" + op + "'");
                return -1;
        }
    }


    private Rect getBounds() {
        String leftStr = getNextArgRequired();
        int left = Integer.parseInt(leftStr);
        String topStr = getNextArgRequired();
        int top = Integer.parseInt(topStr);
        String rightStr = getNextArgRequired();
        int right = Integer.parseInt(rightStr);
        String bottomStr = getNextArgRequired();
        int bottom = Integer.parseInt(bottomStr);
        if (left < 0) {
            getErrPrintWriter().println("Error: bad left arg: " + leftStr);
            return null;
        }
        if (top < 0) {
            getErrPrintWriter().println("Error: bad top arg: " + topStr);
            return null;
        }
        if (right <= 0) {
            getErrPrintWriter().println("Error: bad right arg: " + rightStr);
            return null;
        }
        if (bottom <= 0) {
            getErrPrintWriter().println("Error: bad bottom arg: " + bottomStr);
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    int runDisplayMoveStack(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String displayIdStr = getNextArgRequired();
        int displayId = Integer.parseInt(displayIdStr);
        mInterface.moveStackToDisplay(stackId, displayId);
        return 0;
    }

    int runStackStart(PrintWriter pw) throws RemoteException {
        String displayIdStr = getNextArgRequired();
        int displayId = Integer.parseInt(displayIdStr);
        Intent intent;
        try {
            intent = makeIntent(UserHandle.USER_CURRENT);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e.getMessage(), e);
        }

        final int stackId = mInterface.createStackOnDisplay(displayId);
        if (stackId != INVALID_STACK_ID) {
            // TODO: Need proper support if this is used by test...
//            container.startActivity(intent);
//            ActivityOptions options = ActivityOptions.makeBasic();
//            options.setLaunchDisplayId(displayId);
//            options.setLaunchStackId(stackId);
//            mInterface.startAct
//            mInterface.startActivityAsUser(null, null, intent, mimeType,
//                    null, null, 0, mStartFlags, profilerInfo,
//                    options != null ? options.toBundle() : null, mUserId);
        }
        return 0;
    }

    int runStackMoveTask(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String toTopStr = getNextArgRequired();
        final boolean toTop;
        if ("true".equals(toTopStr)) {
            toTop = true;
        } else if ("false".equals(toTopStr)) {
            toTop = false;
        } else {
            getErrPrintWriter().println("Error: bad toTop arg: " + toTopStr);
            return -1;
        }

        mInterface.moveTaskToStack(taskId, stackId, toTop);
        return 0;
    }

    int runStackResize(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        final Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        return resizeStack(stackId, bounds, 0);
    }

    int runStackResizeAnimated(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        final Rect bounds;
        if ("null".equals(peekNextArg())) {
            bounds = null;
        } else {
            bounds = getBounds();
            if (bounds == null) {
                getErrPrintWriter().println("Error: invalid input bounds");
                return -1;
            }
        }
        return resizeStackUnchecked(stackId, bounds, 0, true);
    }

    int resizeStackUnchecked(int stackId, Rect bounds, int delayMs, boolean animate)
            throws RemoteException {
        try {
            mInterface.resizeStack(stackId, bounds, false, false, animate, -1);
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
        }
        return 0;
    }

    int runStackResizeDocked(PrintWriter pw) throws RemoteException {
        final Rect bounds = getBounds();
        final Rect taskBounds = getBounds();
        if (bounds == null || taskBounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        mInterface.resizeDockedStack(bounds, taskBounds, null, null, null);
        return 0;
    }

    int resizeStack(int stackId, Rect bounds, int delayMs) throws RemoteException {
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        return resizeStackUnchecked(stackId, bounds, delayMs, false);
    }

    int runStackPositionTask(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        int taskId = Integer.parseInt(taskIdStr);
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        String positionStr = getNextArgRequired();
        int position = Integer.parseInt(positionStr);

        mInterface.positionTaskInStack(taskId, stackId, position);
        return 0;
    }

    int runStackList(PrintWriter pw) throws RemoteException {
        List<ActivityManager.StackInfo> stacks = mInterface.getAllStackInfos();
        for (ActivityManager.StackInfo info : stacks) {
            pw.println(info);
        }
        return 0;
    }

    int runStackInfo(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        ActivityManager.StackInfo info = mInterface.getStackInfo(stackId);
        pw.println(info);
        return 0;
    }

    int runStackRemove(PrintWriter pw) throws RemoteException {
        String stackIdStr = getNextArgRequired();
        int stackId = Integer.parseInt(stackIdStr);
        mInterface.removeStack(stackId);
        return 0;
    }

    int runMoveTopActivityToPinnedStack(PrintWriter pw) throws RemoteException {
        int stackId = Integer.parseInt(getNextArgRequired());
        final Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }

        if (!mInterface.moveTopActivityToPinnedStack(stackId, bounds)) {
            getErrPrintWriter().println("Didn't move top activity to pinned stack.");
            return -1;
        }
        return 0;
    }

    int runStackSizeDockedStackTest(PrintWriter pw) throws RemoteException {
        final PrintWriter err = getErrPrintWriter();
        final int stepSize = Integer.parseInt(getNextArgRequired());
        final String side = getNextArgRequired();
        final String delayStr = getNextArg();
        final int delayMs = (delayStr != null) ? Integer.parseInt(delayStr) : 0;

        ActivityManager.StackInfo info = mInterface.getStackInfo(DOCKED_STACK_ID);
        if (info == null) {
            err.println("Docked stack doesn't exist");
            return -1;
        }
        if (info.bounds == null) {
            err.println("Docked stack doesn't have a bounds");
            return -1;
        }
        Rect bounds = info.bounds;

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
                err.println("Unknown growth side: " + side);
                return -1;
        }

        final int startPoint = currentPoint;
        final int minPoint = currentPoint - changeSize;
        final int maxPoint = currentPoint + changeSize;

        int maxChange;
        pw.println("Shrinking docked stack side=" + side);
        pw.flush();
        while (currentPoint > minPoint) {
            maxChange = Math.min(stepSize, currentPoint - minPoint);
            currentPoint -= maxChange;
            setBoundsSide(bounds, side, currentPoint);
            int res = resizeStack(DOCKED_STACK_ID, bounds, delayMs);
            if (res < 0) {
                return res;
            }
        }

        pw.println("Growing docked stack side=" + side);
        pw.flush();
        while (currentPoint < maxPoint) {
            maxChange = Math.min(stepSize, maxPoint - currentPoint);
            currentPoint += maxChange;
            setBoundsSide(bounds, side, currentPoint);
            int res = resizeStack(DOCKED_STACK_ID, bounds, delayMs);
            if (res < 0) {
                return res;
            }
        }

        pw.println("Back to Original size side=" + side);
        pw.flush();
        while (currentPoint > startPoint) {
            maxChange = Math.min(stepSize, currentPoint - startPoint);
            currentPoint -= maxChange;
            setBoundsSide(bounds, side, currentPoint);
            int res = resizeStack(DOCKED_STACK_ID, bounds, delayMs);
            if (res < 0) {
                return res;
            }
        }
        return 0;
    }

    void setBoundsSide(Rect bounds, String side, int value) {
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
                getErrPrintWriter().println("Unknown set side: " + side);
                break;
        }
    }

    int runTask(PrintWriter pw) throws RemoteException {
        String op = getNextArgRequired();
        if (op.equals("lock")) {
            return runTaskLock(pw);
        } else if (op.equals("resizeable")) {
            return runTaskResizeable(pw);
        } else if (op.equals("resize")) {
            return runTaskResize(pw);
        } else if (op.equals("drag-task-test")) {
            return runTaskDragTaskTest(pw);
        } else if (op.equals("size-task-test")) {
            return runTaskSizeTaskTest(pw);
        } else if (op.equals("focus")) {
            return runTaskFocus(pw);
        } else {
            getErrPrintWriter().println("Error: unknown command '" + op + "'");
            return -1;
        }
    }

    int runTaskLock(PrintWriter pw) throws RemoteException {
        String taskIdStr = getNextArgRequired();
        if (taskIdStr.equals("stop")) {
            mInterface.stopLockTaskMode();
        } else {
            int taskId = Integer.parseInt(taskIdStr);
            mInterface.startSystemLockTaskMode(taskId);
        }
        pw.println("Activity manager is " + (mInterface.isInLockTaskMode() ? "" : "not ") +
                "in lockTaskMode");
        return 0;
    }

    int runTaskResizeable(PrintWriter pw) throws RemoteException {
        final String taskIdStr = getNextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final String resizeableStr = getNextArgRequired();
        final int resizeableMode = Integer.parseInt(resizeableStr);
        mInterface.setTaskResizeable(taskId, resizeableMode);
        return 0;
    }

    int runTaskResize(PrintWriter pw) throws RemoteException {
        final String taskIdStr = getNextArgRequired();
        final int taskId = Integer.parseInt(taskIdStr);
        final Rect bounds = getBounds();
        if (bounds == null) {
            getErrPrintWriter().println("Error: invalid input bounds");
            return -1;
        }
        taskResize(taskId, bounds, 0, false);
        return 0;
    }

    void taskResize(int taskId, Rect bounds, int delay_ms, boolean pretendUserResize)
            throws RemoteException {
        final int resizeMode = pretendUserResize ? RESIZE_MODE_USER : RESIZE_MODE_SYSTEM;
        mInterface.resizeTask(taskId, bounds, resizeMode);
        try {
            Thread.sleep(delay_ms);
        } catch (InterruptedException e) {
        }
    }

    int runTaskDragTaskTest(PrintWriter pw) throws RemoteException {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final int stepSize = Integer.parseInt(getNextArgRequired());
        final String delayStr = getNextArg();
        final int delay_ms = (delayStr != null) ? Integer.parseInt(delayStr) : 0;
        final ActivityManager.StackInfo stackInfo;
        Rect taskBounds;
        stackInfo = mInterface.getStackInfo(mInterface.getFocusedStackId());
        taskBounds = mInterface.getTaskBounds(taskId);
        final Rect stackBounds = stackInfo.bounds;
        int travelRight = stackBounds.width() - taskBounds.width();
        int travelLeft = -travelRight;
        int travelDown = stackBounds.height() - taskBounds.height();
        int travelUp = -travelDown;
        int passes = 0;

        // We do 2 passes to get back to the original location of the task.
        while (passes < 2) {
            // Move right
            pw.println("Moving right...");
            pw.flush();
            travelRight = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelRight, MOVING_FORWARD, MOVING_HORIZONTALLY, delay_ms);
            pw.println("Still need to travel right by " + travelRight);

            // Move down
            pw.println("Moving down...");
            pw.flush();
            travelDown = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelDown, MOVING_FORWARD, !MOVING_HORIZONTALLY, delay_ms);
            pw.println("Still need to travel down by " + travelDown);

            // Move left
            pw.println("Moving left...");
            pw.flush();
            travelLeft = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelLeft, !MOVING_FORWARD, MOVING_HORIZONTALLY, delay_ms);
            pw.println("Still need to travel left by " + travelLeft);

            // Move up
            pw.println("Moving up...");
            pw.flush();
            travelUp = moveTask(taskId, taskBounds, stackBounds, stepSize,
                    travelUp, !MOVING_FORWARD, !MOVING_HORIZONTALLY, delay_ms);
            pw.println("Still need to travel up by " + travelUp);

            taskBounds = mInterface.getTaskBounds(taskId);
            passes++;
        }
        return 0;
    }

    int moveTask(int taskId, Rect taskRect, Rect stackRect, int stepSize,
            int maxToTravel, boolean movingForward, boolean horizontal, int delay_ms)
            throws RemoteException {
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

    int getStepSize(int current, int target, int inStepSize, boolean greaterThanTarget) {
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

    int runTaskSizeTaskTest(PrintWriter pw) throws RemoteException {
        final int taskId = Integer.parseInt(getNextArgRequired());
        final int stepSize = Integer.parseInt(getNextArgRequired());
        final String delayStr = getNextArg();
        final int delay_ms = (delayStr != null) ? Integer.parseInt(delayStr) : 0;
        final ActivityManager.StackInfo stackInfo;
        final Rect initialTaskBounds;
        stackInfo = mInterface.getStackInfo(mInterface.getFocusedStackId());
        initialTaskBounds = mInterface.getTaskBounds(taskId);
        final Rect stackBounds = stackInfo.bounds;
        stackBounds.inset(STACK_BOUNDS_INSET, STACK_BOUNDS_INSET);
        final Rect currentTaskBounds = new Rect(initialTaskBounds);

        // Size by top-left
        pw.println("Growing top-left");
        pw.flush();
        do {
            currentTaskBounds.top -= getStepSize(
                    currentTaskBounds.top, stackBounds.top, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.left -= getStepSize(
                    currentTaskBounds.left, stackBounds.left, stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.top < currentTaskBounds.top
                || stackBounds.left < currentTaskBounds.left);

        // Back to original size
        pw.println("Shrinking top-left");
        pw.flush();
        do {
            currentTaskBounds.top += getStepSize(
                    currentTaskBounds.top, initialTaskBounds.top, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.left += getStepSize(
                    currentTaskBounds.left, initialTaskBounds.left, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.top > currentTaskBounds.top
                || initialTaskBounds.left > currentTaskBounds.left);

        // Size by top-right
        pw.println("Growing top-right");
        pw.flush();
        do {
            currentTaskBounds.top -= getStepSize(
                    currentTaskBounds.top, stackBounds.top, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.right += getStepSize(
                    currentTaskBounds.right, stackBounds.right, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.top < currentTaskBounds.top
                || stackBounds.right > currentTaskBounds.right);

        // Back to original size
        pw.println("Shrinking top-right");
        pw.flush();
        do {
            currentTaskBounds.top += getStepSize(
                    currentTaskBounds.top, initialTaskBounds.top, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.right -= getStepSize(currentTaskBounds.right, initialTaskBounds.right,
                    stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.top > currentTaskBounds.top
                || initialTaskBounds.right < currentTaskBounds.right);

        // Size by bottom-left
        pw.println("Growing bottom-left");
        pw.flush();
        do {
            currentTaskBounds.bottom += getStepSize(
                    currentTaskBounds.bottom, stackBounds.bottom, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.left -= getStepSize(
                    currentTaskBounds.left, stackBounds.left, stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.bottom > currentTaskBounds.bottom
                || stackBounds.left < currentTaskBounds.left);

        // Back to original size
        pw.println("Shrinking bottom-left");
        pw.flush();
        do {
            currentTaskBounds.bottom -= getStepSize(currentTaskBounds.bottom,
                    initialTaskBounds.bottom, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.left += getStepSize(
                    currentTaskBounds.left, initialTaskBounds.left, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.bottom < currentTaskBounds.bottom
                || initialTaskBounds.left > currentTaskBounds.left);

        // Size by bottom-right
        pw.println("Growing bottom-right");
        pw.flush();
        do {
            currentTaskBounds.bottom += getStepSize(
                    currentTaskBounds.bottom, stackBounds.bottom, stepSize, !GREATER_THAN_TARGET);

            currentTaskBounds.right += getStepSize(
                    currentTaskBounds.right, stackBounds.right, stepSize, !GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (stackBounds.bottom > currentTaskBounds.bottom
                || stackBounds.right > currentTaskBounds.right);

        // Back to original size
        pw.println("Shrinking bottom-right");
        pw.flush();
        do {
            currentTaskBounds.bottom -= getStepSize(currentTaskBounds.bottom,
                    initialTaskBounds.bottom, stepSize, GREATER_THAN_TARGET);

            currentTaskBounds.right -= getStepSize(currentTaskBounds.right, initialTaskBounds.right,
                    stepSize, GREATER_THAN_TARGET);

            taskResize(taskId, currentTaskBounds, delay_ms, true);
        } while (initialTaskBounds.bottom < currentTaskBounds.bottom
                || initialTaskBounds.right < currentTaskBounds.right);
        return 0;
    }

    int runTaskFocus(PrintWriter pw) throws RemoteException {
        final int taskId = Integer.parseInt(getNextArgRequired());
        pw.println("Setting focus to task " + taskId);
        mInterface.setFocusedTask(taskId);
        return 0;
    }

    int runWrite(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerUidObserver()");
        mInternal.mRecentTasks.flush();
        pw.println("All tasks persisted.");
        return 0;
    }

    int runAttachAgent(PrintWriter pw) {
        // TODO: revisit the permissions required for attaching agents
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "attach-agent");
        String process = getNextArgRequired();
        String agent = getNextArgRequired();
        String opt;
        if ((opt = getNextArg()) != null) {
            pw.println("Error: Unknown option: " + opt);
            return -1;
        }
        mInternal.attachAgent(process, agent);
        return 0;
    }

    int runSupportsMultiwindow(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityManager.supportsMultiWindow(mInternal.mContext));
        return 0;
    }

    int runSupportsSplitScreenMultiwindow(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(ActivityManager.supportsSplitScreenMultiWindow(mInternal.mContext));
        return 0;
    }

    int runUpdateApplicationInfo(PrintWriter pw) throws RemoteException {
        int userid = UserHandle.parseUserArg(getNextArgRequired());
        ArrayList<String> packages = new ArrayList<>();
        packages.add(getNextArgRequired());
        String packageName;
        while ((packageName = getNextArg()) != null) {
            packages.add(packageName);
        }
        mInternal.scheduleApplicationInfoChanged(packages, userid);
        pw.println("Packages updated with most recent ApplicationInfos.");
        return 0;
    }

    int runNoHomeScreen(PrintWriter pw) throws RemoteException {
        final Resources res = getResources(pw);
        if (res == null) {
            return -1;
        }
        pw.println(res.getBoolean(com.android.internal.R.bool.config_noHomeScreen));
        return 0;
    }

    int runWaitForBroadcastIdle(PrintWriter pw) throws RemoteException {
        mInternal.waitForBroadcastIdle(pw);
        return 0;
    }

    private Resources getResources(PrintWriter pw) throws RemoteException {
        // system resources does not contain all the device configuration, construct it manually.
        Configuration config = mInterface.getConfiguration();
        if (config == null) {
            pw.println("Error: Activity manager has no configuration");
            return null;
        }

        final DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();

        return new Resources(AssetManager.getSystem(), metrics, config);
    }

    @Override
    public void onHelp() {
        PrintWriter pw = getOutPrintWriter();
        dumpHelp(pw, mDumping);
    }

    static void dumpHelp(PrintWriter pw, boolean dumping) {
        if (dumping) {
            pw.println("Activity manager dump options:");
            pw.println("  [-a] [-c] [-p PACKAGE] [-h] [WHAT] ...");
            pw.println("  WHAT may be one of:");
            pw.println("    a[ctivities]: activity stack state");
            pw.println("    r[recents]: recent activities state");
            pw.println("    b[roadcasts] [PACKAGE_NAME] [history [-s]]: broadcast state");
            pw.println("    broadcast-stats [PACKAGE_NAME]: aggregated broadcast statistics");
            pw.println("    i[ntents] [PACKAGE_NAME]: pending intent state");
            pw.println("    p[rocesses] [PACKAGE_NAME]: process state");
            pw.println("    o[om]: out of memory management");
            pw.println("    perm[issions]: URI permission grant state");
            pw.println("    prov[iders] [COMP_SPEC ...]: content provider state");
            pw.println("    provider [COMP_SPEC]: provider client-side state");
            pw.println("    s[ervices] [COMP_SPEC ...]: service state");
            pw.println("    as[sociations]: tracked app associations");
            pw.println("    settings: currently applied config settings");
            pw.println("    service [COMP_SPEC]: service client-side state");
            pw.println("    package [PACKAGE_NAME]: all state related to given package");
            pw.println("    all: dump all activities");
            pw.println("    top: dump the top activity");
            pw.println("  WHAT may also be a COMP_SPEC to dump activities.");
            pw.println("  COMP_SPEC may be a component name (com.foo/.myApp),");
            pw.println("    a partial substring in a component name, a");
            pw.println("    hex object identifier.");
            pw.println("  -a: include all available server state.");
            pw.println("  -c: include client state.");
            pw.println("  -p: limit output to given package.");
            pw.println("  --checkin: output checkin format, resetting data.");
            pw.println("  --C: output checkin format, not resetting data.");
        } else {
            pw.println("Activity manager (activity) commands:");
            pw.println("  help");
            pw.println("      Print this help text.");
            pw.println("  start-activity [-D] [-N] [-W] [-P <FILE>] [--start-profiler <FILE>]");
            pw.println("          [--sampling INTERVAL] [--streaming] [-R COUNT] [-S]");
            pw.println("          [--track-allocation] [--user <USER_ID> | current] <INTENT>");
            pw.println("      Start an Activity.  Options are:");
            pw.println("      -D: enable debugging");
            pw.println("      -N: enable native debugging");
            pw.println("      -W: wait for launch to complete");
            pw.println("      --start-profiler <FILE>: start profiler and send results to <FILE>");
            pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
            pw.println("          between samples (use with --start-profiler)");
            pw.println("      --streaming: stream the profiling output to the specified file");
            pw.println("          (use with --start-profiler)");
            pw.println("      -P <FILE>: like above, but profiling stops when app goes idle");
            pw.println("      --attach-agent <agent>: attach the given agent before binding");
            pw.println("      -R: repeat the activity launch <COUNT> times.  Prior to each repeat,");
            pw.println("          the top activity will be finished.");
            pw.println("      -S: force stop the target app before starting the activity");
            pw.println("      --track-allocation: enable tracking of object allocations");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("      --stack <STACK_ID>: Specify into which stack should the activity be put.");
            pw.println("  start-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Start a Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  start-foreground-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Start a foreground Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  stop-service [--user <USER_ID> | current] <INTENT>");
            pw.println("      Stop a Service.  Options are:");
            pw.println("      --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("          specified then run as the current user.");
            pw.println("  broadcast [--user <USER_ID> | all | current] <INTENT>");
            pw.println("      Send a broadcast Intent.  Options are:");
            pw.println("      --user <USER_ID> | all | current: Specify which user to send to; if not");
            pw.println("          specified then send to all users.");
            pw.println("      --receiver-permission <PERMISSION>: Require receiver to hold permission.");
            pw.println("  instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]");
            pw.println("          [--user <USER_ID> | current]");
            pw.println("          [--no-window-animation] [--abi <ABI>] <COMPONENT>");
            pw.println("      Start an Instrumentation.  Typically this target <COMPONENT> is in the");
            pw.println("      form <TEST_PACKAGE>/<RUNNER_CLASS> or only <TEST_PACKAGE> if there");
            pw.println("      is only one instrumentation.  Options are:");
            pw.println("      -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with");
            pw.println("          [-e perf true] to generate raw output for performance measurements.");
            pw.println("      -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a");
            pw.println("          common form is [-e <testrunner_flag> <value>[,<value>...]].");
            pw.println("      -p <FILE>: write profiling data to <FILE>");
            pw.println("      -m: Write output as protobuf (machine readable)");
            pw.println("      -w: wait for instrumentation to finish before returning.  Required for");
            pw.println("          test runners.");
            pw.println("      --user <USER_ID> | current: Specify user instrumentation runs in;");
            pw.println("          current user if not specified.");
            pw.println("      --no-window-animation: turn off window animations while running.");
            pw.println("      --abi <ABI>: Launch the instrumented process with the selected ABI.");
            pw.println("          This assumes that the process supports the selected ABI.");
            pw.println("  trace-ipc [start|stop] [--dump-file <FILE>]");
            pw.println("      Trace IPC transactions.");
            pw.println("      start: start tracing IPC transactions.");
            pw.println("      stop: stop tracing IPC transactions and dump the results to file.");
            pw.println("      --dump-file <FILE>: Specify the file the trace should be dumped to.");
            pw.println("  profile [start|stop] [--user <USER_ID> current] [--sampling INTERVAL]");
            pw.println("          [--streaming] <PROCESS> <FILE>");
            pw.println("      Start and stop profiler on a process.  The given <PROCESS> argument");
            pw.println("        may be either a process name or pid.  Options are:");
            pw.println("      --user <USER_ID> | current: When supplying a process name,");
            pw.println("          specify user of process to profile; uses current user if not specified.");
            pw.println("      --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
            pw.println("          between samples");
            pw.println("      --streaming: stream the profiling output to the specified file");
            pw.println("  dumpheap [--user <USER_ID> current] [-n] [-g] <PROCESS> <FILE>");
            pw.println("      Dump the heap of a process.  The given <PROCESS> argument may");
            pw.println("        be either a process name or pid.  Options are:");
            pw.println("      -n: dump native heap instead of managed heap");
            pw.println("      -g: force GC before dumping the heap");
            pw.println("      --user <USER_ID> | current: When supplying a process name,");
            pw.println("          specify user of process to dump; uses current user if not specified.");
            pw.println("  set-debug-app [-w] [--persistent] <PACKAGE>");
            pw.println("      Set application <PACKAGE> to debug.  Options are:");
            pw.println("      -w: wait for debugger when application starts");
            pw.println("      --persistent: retain this value");
            pw.println("  clear-debug-app");
            pw.println("      Clear the previously set-debug-app.");
            pw.println("  set-watch-heap <PROCESS> <MEM-LIMIT>");
            pw.println("      Start monitoring pss size of <PROCESS>, if it is at or");
            pw.println("      above <HEAP-LIMIT> then a heap dump is collected for the user to report.");
            pw.println("  clear-watch-heap");
            pw.println("      Clear the previously set-watch-heap.");
            pw.println("  bug-report [--progress | --telephony]");
            pw.println("      Request bug report generation; will launch a notification");
            pw.println("        when done to select where it should be delivered. Options are:");
            pw.println("     --progress: will launch a notification right away to show its progress.");
            pw.println("     --telephony: will dump only telephony sections.");
            pw.println("  force-stop [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Completely stop the given application package.");
            pw.println("  crash [--user <USER_ID>] <PACKAGE|PID>");
            pw.println("      Induce a VM crash in the specified package or process");
            pw.println("  kill [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      Kill all processes associated with the given application.");
            pw.println("  kill-all");
            pw.println("      Kill all processes that are safe to kill (cached, etc).");
            pw.println("  make-uid-idle [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("      If the given application's uid is in the background and waiting to");
            pw.println("      become idle (not allowing background services), do that now.");
            pw.println("  monitor [--gdb <port>]");
            pw.println("      Start monitoring for crashes or ANRs.");
            pw.println("      --gdb: start gdbserv on the given port at crash/ANR");
            pw.println("  watch-uids [--oom <uid>");
            pw.println("      Start watching for and reporting uid state changes.");
            pw.println("      --oom: specify a uid for which to report detailed change messages.");
            pw.println("  hang [--allow-restart]");
            pw.println("      Hang the system.");
            pw.println("      --allow-restart: allow watchdog to perform normal system restart");
            pw.println("  restart");
            pw.println("      Restart the user-space system.");
            pw.println("  idle-maintenance");
            pw.println("      Perform idle maintenance now.");
            pw.println("  screen-compat [on|off] <PACKAGE>");
            pw.println("      Control screen compatibility mode of <PACKAGE>.");
            pw.println("  package-importance <PACKAGE>");
            pw.println("      Print current importance of <PACKAGE>.");
            pw.println("  to-uri [INTENT]");
            pw.println("      Print the given Intent specification as a URI.");
            pw.println("  to-intent-uri [INTENT]");
            pw.println("      Print the given Intent specification as an intent: URI.");
            pw.println("  to-app-uri [INTENT]");
            pw.println("      Print the given Intent specification as an android-app: URI.");
            pw.println("  switch-user <USER_ID>");
            pw.println("      Switch to put USER_ID in the foreground, starting");
            pw.println("      execution of that user if it is currently stopped.");
            pw.println("  get-current-user");
            pw.println("      Returns id of the current foreground user.");
            pw.println("  start-user <USER_ID>");
            pw.println("      Start USER_ID in background if it is currently stopped;");
            pw.println("      use switch-user if you want to start the user in foreground");
            pw.println("  unlock-user <USER_ID> [TOKEN_HEX]");
            pw.println("      Attempt to unlock the given user using the given authorization token.");
            pw.println("  stop-user [-w] [-f] <USER_ID>");
            pw.println("      Stop execution of USER_ID, not allowing it to run any");
            pw.println("      code until a later explicit start or switch to it.");
            pw.println("      -w: wait for stop-user to complete.");
            pw.println("      -f: force stop even if there are related users that cannot be stopped.");
            pw.println("  is-user-stopped <USER_ID>");
            pw.println("      Returns whether <USER_ID> has been stopped or not.");
            pw.println("  get-started-user-state <USER_ID>");
            pw.println("      Gets the current state of the given started user.");
            pw.println("  track-associations");
            pw.println("      Enable association tracking.");
            pw.println("  untrack-associations");
            pw.println("      Disable and clear association tracking.");
            pw.println("  get-uid-state <UID>");
            pw.println("      Gets the process state of an app given its <UID>.");
            pw.println("  attach-agent <PROCESS> <FILE>");
            pw.println("    Attach an agent to the specified <PROCESS>, which may be either a process name or a PID.");
            pw.println("  get-config");
            pw.println("      Rtrieve the configuration and any recent configurations of the device.");
            pw.println("  supports-multiwindow");
            pw.println("      Returns true if the device supports multiwindow.");
            pw.println("  supports-split-screen-multi-window");
            pw.println("      Returns true if the device supports split screen multiwindow.");
            pw.println("  suppress-resize-config-changes <true|false>");
            pw.println("      Suppresses configuration changes due to user resizing an activity/task.");
            pw.println("  set-inactive [--user <USER_ID>] <PACKAGE> true|false");
            pw.println("      Sets the inactive state of an app.");
            pw.println("  get-inactive [--user <USER_ID>] <PACKAGE>");
            pw.println("      Returns the inactive state of an app.");
            pw.println("  send-trim-memory [--user <USER_ID>] <PROCESS>");
            pw.println("          [HIDDEN|RUNNING_MODERATE|BACKGROUND|RUNNING_LOW|MODERATE|RUNNING_CRITICAL|COMPLETE]");
            pw.println("      Send a memory trim event to a <PROCESS>.  May also supply a raw trim int level.");
            pw.println("  display [COMMAND] [...]: sub-commands for operating on displays.");
            pw.println("       move-stack <STACK_ID> <DISPLAY_ID>");
            pw.println("           Move <STACK_ID> from its current display to <DISPLAY_ID>.");
            pw.println("  stack [COMMAND] [...]: sub-commands for operating on activity stacks.");
            pw.println("       start <DISPLAY_ID> <INTENT>");
            pw.println("           Start a new activity on <DISPLAY_ID> using <INTENT>");
            pw.println("       move-task <TASK_ID> <STACK_ID> [true|false]");
            pw.println("           Move <TASK_ID> from its current stack to the top (true) or");
            pw.println("           bottom (false) of <STACK_ID>.");
            pw.println("       resize <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("           Change <STACK_ID> size and position to <LEFT,TOP,RIGHT,BOTTOM>.");
            pw.println("       resize-animated <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("           Same as resize, but allow animation.");
            pw.println("       resize-docked-stack <LEFT,TOP,RIGHT,BOTTOM> [<TASK_LEFT,TASK_TOP,TASK_RIGHT,TASK_BOTTOM>]");
            pw.println("           Change docked stack to <LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("           and supplying temporary different task bounds indicated by");
            pw.println("           <TASK_LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("       size-docked-stack-test: <STEP_SIZE> <l|t|r|b> [DELAY_MS]");
            pw.println("           Test command for sizing docked stack by");
            pw.println("           <STEP_SIZE> increments from the side <l>eft, <t>op, <r>ight, or <b>ottom");
            pw.println("           applying the optional [DELAY_MS] between each step.");
            pw.println("       move-top-activity-to-pinned-stack: <STACK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("           Moves the top activity from");
            pw.println("           <STACK_ID> to the pinned stack using <LEFT,TOP,RIGHT,BOTTOM> for the");
            pw.println("           bounds of the pinned stack.");
            pw.println("       positiontask <TASK_ID> <STACK_ID> <POSITION>");
            pw.println("           Place <TASK_ID> in <STACK_ID> at <POSITION>");
            pw.println("       list");
            pw.println("           List all of the activity stacks and their sizes.");
            pw.println("       info <STACK_ID>");
            pw.println("           Display the information about activity stack <STACK_ID>.");
            pw.println("       remove <STACK_ID>");
            pw.println("           Remove stack <STACK_ID>.");
            pw.println("  task [COMMAND] [...]: sub-commands for operating on activity tasks.");
            pw.println("       lock <TASK_ID>");
            pw.println("           Bring <TASK_ID> to the front and don't allow other tasks to run.");
            pw.println("       lock stop");
            pw.println("           End the current task lock.");
            pw.println("       resizeable <TASK_ID> [0|1|2|3]");
            pw.println("           Change resizeable mode of <TASK_ID> to one of the following:");
            pw.println("           0: unresizeable");
            pw.println("           1: crop_windows");
            pw.println("           2: resizeable");
            pw.println("           3: resizeable_and_pipable");
            pw.println("       resize <TASK_ID> <LEFT,TOP,RIGHT,BOTTOM>");
            pw.println("           Makes sure <TASK_ID> is in a stack with the specified bounds.");
            pw.println("           Forces the task to be resizeable and creates a stack if no existing stack");
            pw.println("           has the specified bounds.");
            pw.println("       drag-task-test <TASK_ID> <STEP_SIZE> [DELAY_MS]");
            pw.println("           Test command for dragging/moving <TASK_ID> by");
            pw.println("           <STEP_SIZE> increments around the screen applying the optional [DELAY_MS]");
            pw.println("           between each step.");
            pw.println("       size-task-test <TASK_ID> <STEP_SIZE> [DELAY_MS]");
            pw.println("           Test command for sizing <TASK_ID> by <STEP_SIZE>");
            pw.println("           increments within the screen applying the optional [DELAY_MS] between");
            pw.println("           each step.");
            pw.println("  update-appinfo <USER_ID> <PACKAGE_NAME> [<PACKAGE_NAME>...]");
            pw.println("      Update the ApplicationInfo objects of the listed packages for <USER_ID>");
            pw.println("      without restarting any processes.");
            pw.println("  write");
            pw.println("      Write all pending state to storage.");
            pw.println();
            Intent.printIntentArgsHelp(pw, "");
        }
    }
}
