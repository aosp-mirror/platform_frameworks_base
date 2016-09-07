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
import android.app.IActivityManager;
import android.app.ProfilerInfo;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.DebugUtils;

import java.io.PrintWriter;
import java.net.URISyntaxException;
import java.util.List;

import static android.app.ActivityManager.StackId.INVALID_STACK_ID;

class ActivityManagerShellCommand extends ShellCommand {
    public static final String NO_CLASS_ERROR_CODE = "Error type 3";

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
    private int mStackId;

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
                    return 1; //runStartService(pw);
                case "stopservice":
                case "stop-service":
                    return 1; //runStopService(pw);
                case "force-stop":
                    return runForceStop(pw);
                case "kill":
                    return runKill(pw);
                case "kill-all":
                    return runKillAll(pw);
                case "write":
                    return runWrite(pw);
                case "track-associations":
                    return runTrackAssociations(pw);
                case "untrack-associations":
                    return runUntrackAssociations(pw);
                case "is-user-stopped":
                    return runIsUserStopped(pw);
                case "lenient-background-check":
                    return runLenientBackgroundCheck(pw);
                case "get-uid-state":
                    return getUidState(pw);
                case "get-started-user-state":
                    return getStartedUserState(pw);
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
        mUserId = defUser;
        mStackId = INVALID_STACK_ID;

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
                } else if (opt.equals("--stack")) {
                    mStackId = Integer.parseInt(getNextArgRequired());
                } else {
                    return false;
                }
                return true;
            }
        });
    }

    ParcelFileDescriptor openOutputFile(String path) {
        try {
            ParcelFileDescriptor pfd = getShellCallback().openOutputFile(path,
                    "u:r:system_server:s0");
            if (pfd != null) {
                return pfd;
            }
        } catch (RuntimeException e) {
            getErrPrintWriter().println("Failure opening file: " + e.getMessage());
        }
        getErrPrintWriter().println("Error: Unable to open file: " + path);
        getErrPrintWriter().println("Consider using a file under /data/local/tmp/");
        return null;
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
                mInterface.forceStopPackage(packageName, mUserId);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                }
            }

            ProfilerInfo profilerInfo = null;

            if (mProfileFile != null) {
                ParcelFileDescriptor fd = openOutputFile(mProfileFile);
                if (fd == null) {
                    return 1;
                }
                profilerInfo = new ProfilerInfo(mProfileFile, fd, mSamplingInterval, mAutoStop);
            }

            pw.println("Starting: " + intent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            IActivityManager.WaitResult result = null;
            int res;
            final long startTime = SystemClock.uptimeMillis();
            ActivityOptions options = null;
            if (mStackId != INVALID_STACK_ID) {
                options = ActivityOptions.makeBasic();
                options.setLaunchStackId(mStackId);
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
            if (mWaitOption && launched) {
                if (result == null) {
                    result = new IActivityManager.WaitResult();
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
            }
            mRepeat--;
            if (mRepeat > 0) {
                mInterface.unhandledBack();
            }
        } while (mRepeat > 0);
        return 0;
    }

    int runIsUserStopped(PrintWriter pw) {
        int userId = UserHandle.parseUserArg(getNextArgRequired());
        boolean stopped = mInternal.isUserStopped(userId);
        pw.println(stopped);
        return 0;
    }

    int runForceStop(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt = getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                pw.println("Error: Unknown option: " + opt);
                return -1;
            }
        }
        mInterface.forceStopPackage(getNextArgRequired(), userId);
        return 0;
    }

    int runKill(PrintWriter pw) throws RemoteException {
        int userId = UserHandle.USER_ALL;

        String opt;
        while ((opt=getNextOption()) != null) {
            if (opt.equals("--user")) {
                userId = UserHandle.parseUserArg(getNextArgRequired());
            } else {
                pw.println("Error: Unknown option: " + opt);
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

    int runWrite(PrintWriter pw) {
        mInternal.enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "registerUidObserver()");
        mInternal.mRecentTasks.flush();
        pw.println("All tasks persisted.");
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

    int runLenientBackgroundCheck(PrintWriter pw) throws RemoteException {
        String arg = getNextArg();
        if (arg != null) {
            boolean state = Boolean.valueOf(arg) || "1".equals(arg);
            mInterface.setLenientBackgroundCheck(state);
        }
        synchronized (mInternal) {
            if (mInternal.mLenientBackgroundCheck) {
                pw.println("Lenient background check enabled");
            } else {
                pw.println("Lenient background check disabled");
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

    int getStartedUserState(PrintWriter pw) throws RemoteException {
        mInternal.enforceCallingPermission(android.Manifest.permission.DUMP,
                "getStartedUserState()");
        final int userId = Integer.parseInt(getNextArgRequired());
        try {
            pw.println(mInternal.getStartedUserState(userId));
        } catch (NullPointerException e) {
            pw.println("User is not started: " + userId);
        }
        return 0;
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
            pw.println("    Print this help text.");
            pw.println("  start-activity [-D] [-N] [-W] [-P <FILE>] [--start-profiler <FILE>]");
            pw.println("          [--sampling INTERVAL] [-R COUNT] [-S]");
            pw.println("          [--track-allocation] [--user <USER_ID> | current] <INTENT>");
            pw.println("    Start an Activity.  Options are:");
            pw.println("    -D: enable debugging");
            pw.println("    -N: enable native debugging");
            pw.println("    -W: wait for launch to complete");
            pw.println("    --start-profiler <FILE>: start profiler and send results to <FILE>");
            pw.println("    --sampling INTERVAL: use sample profiling with INTERVAL microseconds");
            pw.println("        between samples (use with --start-profiler)");
            pw.println("    -P <FILE>: like above, but profiling stops when app goes idle");
            pw.println("    -R: repeat the activity launch <COUNT> times.  Prior to each repeat,");
            pw.println("        the top activity will be finished.");
            pw.println("    -S: force stop the target app before starting the activity");
            pw.println("    --track-allocation: enable tracking of object allocations");
            pw.println("    --user <USER_ID> | current: Specify which user to run as; if not");
            pw.println("        specified then run as the current user.");
            pw.println("    --stack <STACK_ID>: Specify into which stack should the activity be put.");
            pw.println("  force-stop [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("    Completely stop the given application package.");
            pw.println("  kill [--user <USER_ID> | all | current] <PACKAGE>");
            pw.println("    Kill all processes associated with the given application.");
            pw.println("  kill-all");
            pw.println("    Kill all processes that are safe to kill (cached, etc).");
            pw.println("  write");
            pw.println("    Write all pending state to storage.");
            pw.println("  track-associations");
            pw.println("    Enable association tracking.");
            pw.println("  untrack-associations");
            pw.println("    Disable and clear association tracking.");
            pw.println("  is-user-stopped <USER_ID>");
            pw.println("    Returns whether <USER_ID> has been stopped or not.");
            pw.println("  lenient-background-check [<true|false>]");
            pw.println("    Optionally controls lenient background check mode, returns current mode.");
            pw.println("  get-uid-state <UID>");
            pw.println("    Gets the process state of an app given its <UID>.");
            pw.println("  get-started-user-state <USER_ID>");
            pw.println("    Gets the current state of the given started user.");
            pw.println();
            Intent.printIntentArgsHelp(pw, "");
        }
    }
}
