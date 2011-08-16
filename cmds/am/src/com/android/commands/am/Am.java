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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IInstrumentationWatcher;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.AndroidException;
import android.view.IWindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;

public class Am {

    private IActivityManager mAm;
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private boolean mDebugOption = false;
    private boolean mWaitOption = false;
    private boolean mStopOption = false;

    private String mProfileFile;
    private boolean mProfileAutoStop;

    // These are magic strings understood by the Eclipse plugin.
    private static final String FATAL_ERROR_CODE = "Error type 1";
    private static final String NO_SYSTEM_ERROR_CODE = "Error type 2";
    private static final String NO_CLASS_ERROR_CODE = "Error type 3";

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        try {
            (new Am()).run(args);
        } catch (IllegalArgumentException e) {
            showUsage();
            System.err.println("Error: " + e.getMessage());
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private void run(String[] args) throws Exception {
        if (args.length < 1) {
            showUsage();
            return;
        }

        mAm = ActivityManagerNative.getDefault();
        if (mAm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to activity manager; is the system running?");
        }

        mArgs = args;
        String op = args[0];
        mNextArg = 1;

        if (op.equals("start")) {
            runStart();
        } else if (op.equals("startservice")) {
            runStartService();
        } else if (op.equals("force-stop")) {
            runForceStop();
        } else if (op.equals("instrument")) {
            runInstrument();
        } else if (op.equals("broadcast")) {
            sendBroadcast();
        } else if (op.equals("profile")) {
            runProfile();
        } else if (op.equals("dumpheap")) {
            runDumpHeap();
        } else if (op.equals("monitor")) {
            runMonitor();
        } else if (op.equals("screen-compat")) {
            runScreenCompat();
        } else if (op.equals("display-size")) {
            runDisplaySize();
        } else {
            throw new IllegalArgumentException("Unknown command: " + op);
        }
    }

    private Intent makeIntent() throws URISyntaxException {
        Intent intent = new Intent();
        boolean hasIntentInfo = false;

        mDebugOption = false;
        mWaitOption = false;
        mStopOption = false;
        mProfileFile = null;
        Uri data = null;
        String type = null;

        String opt;
        while ((opt=nextOption()) != null) {
            if (opt.equals("-a")) {
                intent.setAction(nextArgRequired());
                hasIntentInfo = true;
            } else if (opt.equals("-d")) {
                data = Uri.parse(nextArgRequired());
                hasIntentInfo = true;
            } else if (opt.equals("-t")) {
                type = nextArgRequired();
                hasIntentInfo = true;
            } else if (opt.equals("-c")) {
                intent.addCategory(nextArgRequired());
                hasIntentInfo = true;
            } else if (opt.equals("-e") || opt.equals("--es")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                intent.putExtra(key, value);
                hasIntentInfo = true;
            } else if (opt.equals("--esn")) {
                String key = nextArgRequired();
                intent.putExtra(key, (String) null);
                hasIntentInfo = true;
            } else if (opt.equals("--ei")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                intent.putExtra(key, Integer.valueOf(value));
                hasIntentInfo = true;
            } else if (opt.equals("--eu")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                intent.putExtra(key, Uri.parse(value));
                hasIntentInfo = true;
            } else if (opt.equals("--eia")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                String[] strings = value.split(",");
                int[] list = new int[strings.length];
                for (int i = 0; i < strings.length; i++) {
                    list[i] = Integer.valueOf(strings[i]);
                }
                intent.putExtra(key, list);
                hasIntentInfo = true;
            } else if (opt.equals("--el")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                intent.putExtra(key, Long.valueOf(value));
                hasIntentInfo = true;
            } else if (opt.equals("--ela")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                String[] strings = value.split(",");
                long[] list = new long[strings.length];
                for (int i = 0; i < strings.length; i++) {
                    list[i] = Long.valueOf(strings[i]);
                }
                intent.putExtra(key, list);
                hasIntentInfo = true;
            } else if (opt.equals("--ez")) {
                String key = nextArgRequired();
                String value = nextArgRequired();
                intent.putExtra(key, Boolean.valueOf(value));
                hasIntentInfo = true;
            } else if (opt.equals("-n")) {
                String str = nextArgRequired();
                ComponentName cn = ComponentName.unflattenFromString(str);
                if (cn == null) throw new IllegalArgumentException("Bad component name: " + str);
                intent.setComponent(cn);
                hasIntentInfo = true;
            } else if (opt.equals("-f")) {
                String str = nextArgRequired();
                intent.setFlags(Integer.decode(str).intValue());
            } else if (opt.equals("--grant-read-uri-permission")) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else if (opt.equals("--grant-write-uri-permission")) {
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } else if (opt.equals("--exclude-stopped-packages")) {
                intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
            } else if (opt.equals("--include-stopped-packages")) {
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
            } else if (opt.equals("--debug-log-resolution")) {
                intent.addFlags(Intent.FLAG_DEBUG_LOG_RESOLUTION);
            } else if (opt.equals("--activity-brought-to-front")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
            } else if (opt.equals("--activity-clear-top")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            } else if (opt.equals("--activity-clear-when-task-reset")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
            } else if (opt.equals("--activity-exclude-from-recents")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            } else if (opt.equals("--activity-launched-from-history")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
            } else if (opt.equals("--activity-multiple-task")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            } else if (opt.equals("--activity-no-animation")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            } else if (opt.equals("--activity-no-history")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
            } else if (opt.equals("--activity-no-user-action")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            } else if (opt.equals("--activity-previous-is-top")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP);
            } else if (opt.equals("--activity-reorder-to-front")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            } else if (opt.equals("--activity-reset-task-if-needed")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            } else if (opt.equals("--activity-single-top")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            } else if (opt.equals("--activity-clear-task")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            } else if (opt.equals("--activity-task-on-home")) {
                intent.addFlags(Intent.FLAG_ACTIVITY_TASK_ON_HOME);
            } else if (opt.equals("--receiver-registered-only")) {
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            } else if (opt.equals("--receiver-replace-pending")) {
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            } else if (opt.equals("-D")) {
                mDebugOption = true;
            } else if (opt.equals("-W")) {
                mWaitOption = true;
            } else if (opt.equals("-P")) {
                mProfileFile = nextArgRequired();
                mProfileAutoStop = true;
            } else if (opt.equals("--start-profiler")) {
                mProfileFile = nextArgRequired();
                mProfileAutoStop = false;
            } else if (opt.equals("-S")) {
                mStopOption = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return null;
            }
        }
        intent.setDataAndType(data, type);

        String arg = nextArg();
        if (arg != null) {
            Intent baseIntent;
            if (arg.indexOf(':') >= 0) {
                // The argument is a URI.  Fully parse it, and use that result
                // to fill in any data not specified so far.
                baseIntent = Intent.parseUri(arg, Intent.URI_INTENT_SCHEME);
            } else if (arg.indexOf('/') >= 0) {
                // The argument is a component name.  Build an Intent to launch
                // it.
                baseIntent = new Intent(Intent.ACTION_MAIN);
                baseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                baseIntent.setComponent(ComponentName.unflattenFromString(arg));
            } else {
                // Assume the argument is a package name.
                baseIntent = new Intent(Intent.ACTION_MAIN);
                baseIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                baseIntent.setPackage(arg);
            }
            Bundle extras = intent.getExtras();
            intent.replaceExtras((Bundle)null);
            Bundle uriExtras = baseIntent.getExtras();
            baseIntent.replaceExtras((Bundle)null);
            if (intent.getAction() != null && baseIntent.getCategories() != null) {
                HashSet<String> cats = new HashSet<String>(baseIntent.getCategories());
                for (String c : cats) {
                    baseIntent.removeCategory(c);
                }
            }
            intent.fillIn(baseIntent, Intent.FILL_IN_COMPONENT);
            if (extras == null) {
                extras = uriExtras;
            } else if (uriExtras != null) {
                uriExtras.putAll(extras);
                extras = uriExtras;
            }
            intent.replaceExtras(extras);
            hasIntentInfo = true;
        }

        if (!hasIntentInfo) throw new IllegalArgumentException("No intent supplied");
        return intent;
    }

    private void runStartService() throws Exception {
        Intent intent = makeIntent();
        System.out.println("Starting service: " + intent);
        ComponentName cn = mAm.startService(null, intent, intent.getType());
        if (cn == null) {
            System.err.println("Error: Not found; no service started.");
        }
    }

    private void runStart() throws Exception {
        Intent intent = makeIntent();

        String mimeType = intent.getType();
        if (mimeType == null && intent.getData() != null
                && "content".equals(intent.getData().getScheme())) {
            mimeType = mAm.getProviderMimeType(intent.getData());
        }

        if (mStopOption) {
            String packageName;
            if (intent.getComponent() != null) {
                packageName = intent.getComponent().getPackageName();
            } else {
                IPackageManager pm = IPackageManager.Stub.asInterface(
                        ServiceManager.getService("package"));
                if (pm == null) {
                    System.err.println("Error: Package manager not running; aborting");
                    return;
                }
                List<ResolveInfo> activities = pm.queryIntentActivities(intent, mimeType, 0);
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
            mAm.forceStopPackage(packageName);
            Thread.sleep(250);
        }

        System.out.println("Starting: " + intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        ParcelFileDescriptor fd = null;

        if (mProfileFile != null) {
            try {
                fd = ParcelFileDescriptor.open(
                        new File(mProfileFile),
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE |
                        ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (FileNotFoundException e) {
                System.err.println("Error: Unable to open file: " + mProfileFile);
                return;
            }
        }

        IActivityManager.WaitResult result = null;
        int res;
        if (mWaitOption) {
            result = mAm.startActivityAndWait(null, intent, mimeType,
                        null, 0, null, null, 0, false, mDebugOption,
                        mProfileFile, fd, mProfileAutoStop);
            res = result.result;
        } else {
            res = mAm.startActivity(null, intent, mimeType,
                    null, 0, null, null, 0, false, mDebugOption,
                    mProfileFile, fd, mProfileAutoStop);
        }
        PrintStream out = mWaitOption ? System.out : System.err;
        boolean launched = false;
        switch (res) {
            case IActivityManager.START_SUCCESS:
                launched = true;
                break;
            case IActivityManager.START_SWITCHES_CANCELED:
                launched = true;
                out.println(
                        "Warning: Activity not started because the "
                        + " current activity is being kept for the user.");
                break;
            case IActivityManager.START_DELIVERED_TO_TOP:
                launched = true;
                out.println(
                        "Warning: Activity not started, intent has "
                        + "been delivered to currently running "
                        + "top-most instance.");
                break;
            case IActivityManager.START_RETURN_INTENT_TO_CALLER:
                launched = true;
                out.println(
                        "Warning: Activity not started because intent "
                        + "should be handled by the caller");
                break;
            case IActivityManager.START_TASK_TO_FRONT:
                launched = true;
                out.println(
                        "Warning: Activity not started, its current "
                        + "task has been brought to the front");
                break;
            case IActivityManager.START_INTENT_NOT_RESOLVED:
                out.println(
                        "Error: Activity not started, unable to "
                        + "resolve " + intent.toString());
                break;
            case IActivityManager.START_CLASS_NOT_FOUND:
                out.println(NO_CLASS_ERROR_CODE);
                out.println("Error: Activity class " +
                        intent.getComponent().toShortString()
                        + " does not exist.");
                break;
            case IActivityManager.START_FORWARD_AND_REQUEST_CONFLICT:
                out.println(
                        "Error: Activity not started, you requested to "
                        + "both forward and receive its result");
                break;
            case IActivityManager.START_PERMISSION_DENIED:
                out.println(
                        "Error: Activity not started, you do not "
                        + "have permission to access it.");
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
            System.out.println("Complete");
        }
    }

    private void runForceStop() throws Exception {
        mAm.forceStopPackage(nextArgRequired());
    }

    private void sendBroadcast() throws Exception {
        Intent intent = makeIntent();
        IntentReceiver receiver = new IntentReceiver();
        System.out.println("Broadcasting: " + intent);
        mAm.broadcastIntent(null, intent, null, receiver, 0, null, null, null, true, false);
        receiver.waitForFinish();
    }

    private void runInstrument() throws Exception {
        String profileFile = null;
        boolean wait = false;
        boolean rawMode = false;
        boolean no_window_animation = false;
        Bundle args = new Bundle();
        String argKey = null, argValue = null;
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

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
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return;
            }
        }

        String cnArg = nextArgRequired();
        ComponentName cn = ComponentName.unflattenFromString(cnArg);
        if (cn == null) throw new IllegalArgumentException("Bad component name: " + cnArg);

        InstrumentationWatcher watcher = null;
        if (wait) {
            watcher = new InstrumentationWatcher();
            watcher.setRawOutput(rawMode);
        }
        float[] oldAnims = null;
        if (no_window_animation) {
            oldAnims = wm.getAnimationScales();
            wm.setAnimationScale(0, 0.0f);
            wm.setAnimationScale(1, 0.0f);
        }

        if (!mAm.startInstrumentation(cn, profileFile, 0, args, watcher)) {
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
        int profileType = 0;
        
        String process = null;
        
        String cmd = nextArgRequired();
        if ("looper".equals(cmd)) {
            cmd = nextArgRequired();
            profileType = 1;
        }

        if ("start".equals(cmd)) {
            start = true;
            wall = "--wall".equals(nextOption());
            process = nextArgRequired();
        } else if ("stop".equals(cmd)) {
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
        
        ParcelFileDescriptor fd = null;

        if (start) {
            profileFile = nextArgRequired();
            try {
                fd = ParcelFileDescriptor.open(
                        new File(profileFile),
                        ParcelFileDescriptor.MODE_CREATE |
                        ParcelFileDescriptor.MODE_TRUNCATE |
                        ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (FileNotFoundException e) {
                System.err.println("Error: Unable to open file: " + profileFile);
                return;
            }
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
            if (!mAm.profileControl(process, start, profileFile, fd, profileType)) {
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
        boolean managed = !"-n".equals(nextOption());
        String process = nextArgRequired();
        String heapFile = nextArgRequired();
        ParcelFileDescriptor fd = null;

        try {
            fd = ParcelFileDescriptor.open(
                    new File(heapFile),
                    ParcelFileDescriptor.MODE_CREATE |
                    ParcelFileDescriptor.MODE_TRUNCATE |
                    ParcelFileDescriptor.MODE_READ_WRITE);
        } catch (FileNotFoundException e) {
            System.err.println("Error: Unable to open file: " + heapFile);
            return;
        }

        if (!mAm.dumpHeap(process, managed, heapFile, fd)) {
            throw new AndroidException("HEAP DUMP FAILED on process " + process);
        }
    }

    class MyActivityController extends IActivityController.Stub {
        final String mGdbPort;

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

        MyActivityController(String gdbPort) {
            mGdbPort = gdbPort;
        }

        @Override
        public boolean activityResuming(String pkg) throws RemoteException {
            synchronized (this) {
                System.out.println("** Activity resuming: " + pkg);
            }
            return true;
        }

        @Override
        public boolean activityStarting(Intent intent, String pkg) throws RemoteException {
            synchronized (this) {
                System.out.println("** Activity starting: " + pkg);
            }
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) throws RemoteException {
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
        public int appEarlyNotResponding(String processName, int pid, String annotation)
                throws RemoteException {
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
        public int appNotResponding(String processName, int pid, String processStats)
                throws RemoteException {
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

                mAm.setActivityController(this);
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
                mAm.setActivityController(null);
            }
        }
    }

    private void runMonitor() throws Exception {
        String opt;
        String gdbPort = null;
        while ((opt=nextOption()) != null) {
            if (opt.equals("--gdb")) {
                gdbPort = nextArgRequired();
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return;
            }
        }

        MyActivityController controller = new MyActivityController(gdbPort);
        controller.run();
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
            showUsage();
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

    private void runDisplaySize() throws Exception {
        String size = nextArgRequired();
        int m, n;
        if ("reset".equals(size)) {
            m = n = -1;
        } else {
            int div = size.indexOf('x');
            if (div <= 0 || div >= (size.length()-1)) {
                System.err.println("Error: bad size " + size);
                showUsage();
                return;
            }
            String mstr = size.substring(0, div);
            String nstr = size.substring(div+1);
            try {
                m = Integer.parseInt(mstr);
                n = Integer.parseInt(nstr);
            } catch (NumberFormatException e) {
                System.err.println("Error: bad number " + e);
                showUsage();
                return;
            }
        }

        if (m < n) {
            int tmp = m;
            m = n;
            n = tmp;
        }

        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.checkService(
                Context.WINDOW_SERVICE));
        if (wm == null) {
            System.err.println(NO_SYSTEM_ERROR_CODE);
            throw new AndroidException("Can't connect to window manager; is the system running?");
        }

        try {
            if (m >= 0 && n >= 0) {
                wm.setForcedDisplaySize(m, n);
            } else {
                wm.clearForcedDisplaySize();
            }
        } catch (RemoteException e) {
        }
    }

    private class IntentReceiver extends IIntentReceiver.Stub {
        private boolean mFinished = false;

        public synchronized void performReceive(
                Intent intent, int rc, String data, Bundle ext, boolean ord,
                boolean sticky) {
            String line = "Broadcast completed: result=" + rc;
            if (data != null) line = line + ", data=\"" + data + "\"";
            if (ext != null) line = line + ", extras: " + ext;
            System.out.println(line);
            mFinished = true;
            notifyAll();
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

    private String nextOption() {
        if (mCurArgData != null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("No argument expected after \"" + prev + "\"");
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        if (!arg.startsWith("-")) {
            return null;
        }
        mNextArg++;
        if (arg.equals("--")) {
            return null;
        }
        if (arg.length() > 1 && arg.charAt(1) != '-') {
            if (arg.length() > 2) {
                mCurArgData = arg.substring(2);
                return arg.substring(0, 2);
            } else {
                mCurArgData = null;
                return arg;
            }
        }
        mCurArgData = null;
        return arg;
    }

    private String nextArg() {
        if (mCurArgData != null) {
            String arg = mCurArgData;
            mCurArgData = null;
            return arg;
        } else if (mNextArg < mArgs.length) {
            return mArgs[mNextArg++];
        } else {
            return null;
        }
    }

    private String nextArgRequired() {
        String arg = nextArg();
        if (arg == null) {
            String prev = mArgs[mNextArg - 1];
            throw new IllegalArgumentException("Argument expected after \"" + prev + "\"");
        }
        return arg;
    }

    private static void showUsage() {
        System.err.println(
                "usage: am [subcommand] [options]\n" +
                "usage: am start [-D] [-W] [-P <FILE>] [--start-profiler <FILE>] [-S] <INTENT>\n" +
                "       am startservice <INTENT>\n" +
                "       am force-stop <PACKAGE>\n" +
                "       am broadcast <INTENT>\n" +
                "       am instrument [-r] [-e <NAME> <VALUE>] [-p <FILE>] [-w]\n" +
                "               [--no-window-animation] <COMPONENT>\n" +
                "       am profile [looper] start <PROCESS> <FILE>\n" +
                "       am profile [looper] stop [<PROCESS>]\n" +
                "       am dumpheap [flags] <PROCESS> <FILE>\n" +
                "       am monitor [--gdb <port>]\n" +
                "       am screen-compat [on|off] <PACKAGE>\n" +
                "       am display-size [reset|MxN]\n" +
                "\n" +
                "am start: start an Activity.  Options are:\n" +
                "    -D: enable debugging\n" +
                "    -W: wait for launch to complete\n" +
                "    --start-profiler <FILE>: start profiler and send results to <FILE>\n" +
                "    -P <FILE>: like above, but profiling stops when app goes idle\n" +
                "    -S: force stop the target app before starting the activity\n" +
                "\n" +
                "am startservice: start a Service.\n" +
                "\n" +
                "am force-stop: force stop everything associated with <PACKAGE>.\n" +
                "\n" +
                "am broadcast: send a broadcast Intent.\n" +
                "\n" +
                "am instrument: start an Instrumentation.  Typically this target <COMPONENT>\n" +
                "  is the form <TEST_PACKAGE>/<RUNNER_CLASS>.  Options are:\n" +
                "    -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT).  Use with\n" +
                "        [-e perf true] to generate raw output for performance measurements.\n" +
                "    -e <NAME> <VALUE>: set argument <NAME> to <VALUE>.  For test runners a\n" +
                "        common form is [-e <testrunner_flag> <value>[,<value>...]].\n" +
                "    -p <FILE>: write profiling data to <FILE>\n" +
                "    -w: wait for instrumentation to finish before returning.  Required for\n" +
                "        test runners.\n" +
                "    --no-window-animation: turn off window animations will running.\n" +
                "\n" +
                "am profile: start and stop profiler on a process.\n" +
                "\n" +
                "am dumpheap: dump the heap of a process.  Options are:\n" +
                "    -n: dump native heap instead of managed heap\n" +
                "\n" +
                "am monitor: start monitoring for crashes or ANRs.\n" +
                "    --gdb: start gdbserv on the given port at crash/ANR\n" +
                "\n" +
                "am screen-compat: control screen compatibility mode of <PACKAGE>.\n" +
                "\n" +
                "am display-size: override display size.\n" +
                "\n" +
                "<INTENT> specifications include these flags and arguments:\n" +
                "    [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>]\n" +
                "    [-c <CATEGORY> [-c <CATEGORY>] ...]\n" +
                "    [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]\n" +
                "    [--esn <EXTRA_KEY> ...]\n" +
                "    [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]\n" +
                "    [--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]\n" +
                "    [--el <EXTRA_KEY> <EXTRA_LONG_VALUE> ...]\n" +
                "    [--eu <EXTRA_KEY> <EXTRA_URI_VALUE> ...]\n" +
                "    [--eia <EXTRA_KEY> <EXTRA_INT_VALUE>[,<EXTRA_INT_VALUE...]]\n" +
                "    [--ela <EXTRA_KEY> <EXTRA_LONG_VALUE>[,<EXTRA_LONG_VALUE...]]\n" +
                "    [-n <COMPONENT>] [-f <FLAGS>]\n" +
                "    [--grant-read-uri-permission] [--grant-write-uri-permission]\n" +
                "    [--debug-log-resolution] [--exclude-stopped-packages]\n" +
                "    [--include-stopped-packages]\n" +
                "    [--activity-brought-to-front] [--activity-clear-top]\n" +
                "    [--activity-clear-when-task-reset] [--activity-exclude-from-recents]\n" +
                "    [--activity-launched-from-history] [--activity-multiple-task]\n" +
                "    [--activity-no-animation] [--activity-no-history]\n" +
                "    [--activity-no-user-action] [--activity-previous-is-top]\n" +
                "    [--activity-reorder-to-front] [--activity-reset-task-if-needed]\n" +
                "    [--activity-single-top] [--activity-clear-task]\n" +
                "    [--activity-task-on-home]\n" +
                "    [--receiver-registered-only] [--receiver-replace-pending]\n" +
                "    [<URI> | <PACKAGE> | <COMPONENT>]\n"
                );
    }
}
