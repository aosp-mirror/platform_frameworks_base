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

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IInstrumentationWatcher;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.AndroidException;
import android.view.IWindowManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Set;

public class Am {

    private IActivityManager mAm;
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private boolean mDebugOption = false;
    private boolean mWaitOption = false;

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
            System.err.println(e.toString());
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
        } else if (op.equals("instrument")) {
            runInstrument();
        } else if (op.equals("broadcast")) {
            sendBroadcast();
        } else if (op.equals("profile")) {
            runProfile();
        } else if (op.equals("dumpheap")) {
            runDumpHeap();
        } else {
            throw new IllegalArgumentException("Unknown command: " + op);
        }
    }

    private Intent makeIntent() throws URISyntaxException {
        Intent intent = new Intent();
        boolean hasIntentInfo = false;

        mDebugOption = false;
        mWaitOption = false;
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
            } else if (opt.equals("--receiver-registered-only")) {
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            } else if (opt.equals("--receiver-replace-pending")) {
                intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
            } else if (opt.equals("-D")) {
                mDebugOption = true;
            } else if (opt.equals("-W")) {
                mWaitOption = true;
            } else {
                System.err.println("Error: Unknown option: " + opt);
                showUsage();
                return null;
            }
        }
        intent.setDataAndType(data, type);

        String uri = nextArg();
        if (uri != null) {
            Intent oldIntent = intent;
            intent = Intent.parseUri(uri, 0);
            if (oldIntent.getAction() != null) {
                intent.setAction(oldIntent.getAction());
            }
            if (oldIntent.getData() != null || oldIntent.getType() != null) {
                intent.setDataAndType(oldIntent.getData(), oldIntent.getType());
            }
            Set cats = oldIntent.getCategories();
            if (cats != null) {
                Iterator it = cats.iterator();
                while (it.hasNext()) {
                    intent.addCategory((String)it.next());
                }
            }
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
        System.out.println("Starting: " + intent);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // XXX should do something to determine the MIME type.
        IActivityManager.WaitResult result = null;
        int res;
        if (mWaitOption) {
            result = mAm.startActivityAndWait(null, intent, intent.getType(),
                        null, 0, null, null, 0, false, mDebugOption);
            res = result.result;
        } else {
            res = mAm.startActivity(null, intent, intent.getType(),
                    null, 0, null, null, 0, false, mDebugOption);
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
            } else if (opt.equals("--no_window_animation")) {
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

    private void runProfile() throws Exception {
        String profileFile = null;
        boolean start = false;
        String process = nextArgRequired();
        ParcelFileDescriptor fd = null;

        String cmd = nextArgRequired();
        if ("start".equals(cmd)) {
            start = true;
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
        } else if (!"stop".equals(cmd)) {
            throw new IllegalArgumentException("Profile command " + cmd + " not valid");
        }

        if (!mAm.profileControl(process, start, profileFile, fd)) {
            throw new AndroidException("PROFILE FAILED on process " + process);
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
                "\n" +
                "    start an Activity: am start [-D] [-W] <INTENT>\n" +
                "        -D: enable debugging\n" +
                "        -W: wait for launch to complete\n" +
                "\n" +
                "    start a Service: am startservice <INTENT>\n" +
                "\n" +
                "    send a broadcast Intent: am broadcast <INTENT>\n" +
                "\n" +
                "    start an Instrumentation: am instrument [flags] <COMPONENT>\n" +
                "        -r: print raw results (otherwise decode REPORT_KEY_STREAMRESULT)\n" +
                "        -e <NAME> <VALUE>: set argument <NAME> to <VALUE>\n" +
                "        -p <FILE>: write profiling data to <FILE>\n" +
                "        -w: wait for instrumentation to finish before returning\n" +
                "\n" +
                "    start profiling: am profile <PROCESS> start <FILE>\n" +
                "    stop profiling: am profile <PROCESS> stop\n" +
                "    dump heap: am dumpheap [flags] <PROCESS> <FILE>\n" +
                "        -n: dump native heap instead of managed heap\n" +
                "\n" +
                "    <INTENT> specifications include these flags:\n" +
                "        [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>]\n" +
                "        [-c <CATEGORY> [-c <CATEGORY>] ...]\n" +
                "        [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]\n" +
                "        [--esn <EXTRA_KEY> ...]\n" +
                "        [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]\n" +
                "        [--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]\n" +
                "        [-n <COMPONENT>] [-f <FLAGS>]\n" +
                "        [--grant-read-uri-permission] [--grant-write-uri-permission]\n" +
                "        [--debug-log-resolution]\n" +
                "        [--activity-brought-to-front] [--activity-clear-top]\n" +
                "        [--activity-clear-when-task-reset] [--activity-exclude-from-recents]\n" +
                "        [--activity-launched-from-history] [--activity-multiple-task]\n" +
                "        [--activity-no-animation] [--activity-no-history]\n" +
                "        [--activity-no-user-action] [--activity-previous-is-top]\n" +
                "        [--activity-reorder-to-front] [--activity-reset-task-if-needed]\n" +
                "        [--activity-single-top]\n" +
                "        [--receiver-registered-only] [--receiver-replace-pending]\n" +
                "        [<URI>]\n"
                );
    }
}
