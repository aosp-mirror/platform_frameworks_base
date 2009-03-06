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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IWindowManager;

import java.util.Iterator;
import java.util.Set;

public class Am {

    private IActivityManager mAm;
    private String[] mArgs;
    private int mNextArg;
    private String mCurArgData;

    private boolean mDebugOption = false;

    /**
     * Command-line entry point.
     *
     * @param args The command-line arguments
     */
    public static void main(String[] args) {
        (new Am()).run(args);
    }

    private void run(String[] args) {
        if (args.length < 1) {
            showUsage();
            return;
        }

        mAm = ActivityManagerNative.getDefault();
        if (mAm == null) {
            System.err.println("Error type 2");
            System.err.println("Error: Unable to connect to activity manager; is the system running?");
            showUsage();
            return;
        }

        mArgs = args;

        String op = args[0];
        mNextArg = 1;
        if (op.equals("start")) {
            runStart();
        } else if (op.equals("instrument")) {
            runInstrument();
        } else if (op.equals("broadcast")) {
            sendBroadcast();
        } else if (op.equals("profile")) {
            runProfile();
        } else {
            System.err.println("Error: Unknown command: " + op);
            showUsage();
            return;
        }
    }

    private Intent makeIntent() {
        Intent intent = new Intent();
        boolean hasIntentInfo = false;

        mDebugOption = false;
        Uri data = null;
        String type = null;

        try {
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("-a")) {
                    intent.setAction(nextOptionData());
                    hasIntentInfo = true;
                } else if (opt.equals("-d")) {
                    data = Uri.parse(nextOptionData());
                    hasIntentInfo = true;
                } else if (opt.equals("-t")) {
                    type = nextOptionData();
                    hasIntentInfo = true;
                } else if (opt.equals("-c")) {
                    intent.addCategory(nextOptionData());
                    hasIntentInfo = true;
                } else if (opt.equals("-e") || opt.equals("--es")) {
                    String key = nextOptionData();
                    String value = nextOptionData();
                    intent.putExtra(key, value);
                    hasIntentInfo = true;
                } else if (opt.equals("--ei")) {
                    String key = nextOptionData();
                    String value = nextOptionData();
                    intent.putExtra(key, Integer.valueOf(value));
                    hasIntentInfo = true;
                } else if (opt.equals("--ez")) {
                    String key = nextOptionData();
                    String value = nextOptionData();
                    intent.putExtra(key, Boolean.valueOf(value));
                    hasIntentInfo = true;
                } else if (opt.equals("-n")) {
                    String str = nextOptionData();
                    ComponentName cn = ComponentName.unflattenFromString(str);
                    if (cn == null) {
                        System.err.println("Error: Bad component name: " + str);
                        showUsage();
                        return null;
                    }
                    intent.setComponent(cn);
                    hasIntentInfo = true;
                } else if (opt.equals("-f")) {
                    String str = nextOptionData();
                    intent.setFlags(Integer.decode(str).intValue());
                } else if (opt.equals("-D")) {
                    mDebugOption = true;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    showUsage();
                    return null;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            showUsage();
            return null;
        }
        intent.setDataAndType(data, type);

        String uri = nextArg();
        if (uri != null) {
            try {
                Intent oldIntent = intent;
                try {
                    intent = Intent.getIntent(uri);
                } catch (java.net.URISyntaxException ex) {
                    System.err.println("Bad URI: " + uri);
                    showUsage();
                    return null;
                }
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
            } catch (RuntimeException ex) {
                System.err.println("Error creating from URI: " + ex.toString());
                showUsage();
                return null;
            }
        } else if (!hasIntentInfo) {
            System.err.println("Error: No intent supplied");
            showUsage();
            return null;
        }

        return intent;
    }

    private void runStart() {
        Intent intent = makeIntent();
        
        if (intent != null) {
            System.out.println("Starting: " + intent);
            try {
                intent.addFlags(intent.FLAG_ACTIVITY_NEW_TASK);
                // XXX should do something to determine the MIME type.
                int res = mAm.startActivity(null, intent, intent.getType(),
                        null, 0, null, null, 0, false, mDebugOption);
                switch (res) {
                    case IActivityManager.START_SUCCESS:
                        break;
                    case IActivityManager.START_CLASS_NOT_FOUND:
                        System.err.println("Error type 3");
                        System.err.println("Error: Activity class " +
                                intent.getComponent().toShortString()
                                + " does not exist.");
                        break;
                    case IActivityManager.START_DELIVERED_TO_TOP:
                        System.err.println(
                                "Warning: Activity not started, intent has "
                                + "been delivered to currently running "
                                + "top-most instance.");
                        break;
                    case IActivityManager.START_FORWARD_AND_REQUEST_CONFLICT:
                        System.err.println(
                                "Error: Activity not started, you requested to "
                                + "both forward and receive its result");
                        break;
                    case IActivityManager.START_INTENT_NOT_RESOLVED:
                        System.err.println(
                                "Error: Activity not started, unable to "
                                + "resolve " + intent.toString());
                        break;
                    case IActivityManager.START_RETURN_INTENT_TO_CALLER:
                        System.err.println(
                                "Warning: Activity not started because intent "
                                + "should be handled by the caller");
                        break;
                    case IActivityManager.START_TASK_TO_FRONT:
                        System.err.println(
                                "Warning: Activity not started, its current "
                                + "task has been brought to the front");
                        break;
                    default:
                        System.err.println(
                                "Error: Activity not started, unknown error "
                                + "code " + res);
                        break;
                }
            } catch (RemoteException e) {
                System.err.println("Error type 1");
                System.err.println(
                        "Error: Activity not started, unable to "
                        + "call on to activity manager service");
            }
        }
    }

    private void sendBroadcast() {
        Intent intent = makeIntent();
        
        if (intent != null) {
            System.out.println("Broadcasting: " + intent);
            try {
                mAm.broadcastIntent(null, intent, null, null, 0, null, null,
                        null, true, false);
            } catch (RemoteException e) {
            }
        }
    }

    private void runInstrument() {
        String profileFile = null;
        boolean wait = false;
        boolean rawMode = false;
        boolean no_window_animation = false;
        Bundle args = new Bundle();
        String argKey = null, argValue = null;
        IWindowManager wm = IWindowManager.Stub.asInterface(ServiceManager.getService("window"));

        try {
            String opt;
            while ((opt=nextOption()) != null) {
                if (opt.equals("-p")) {
                    profileFile = nextOptionData();
                } else if (opt.equals("-w")) {
                    wait = true;
                } else if (opt.equals("-r")) {
                    rawMode = true;
                } else if (opt.equals("-e")) {
                    argKey = nextOptionData();
                    argValue = nextOptionData();
                    args.putString(argKey, argValue);
                } else if (opt.equals("--no_window_animation")) {
                    no_window_animation = true;
                } else {
                    System.err.println("Error: Unknown option: " + opt);
                    showUsage();
                    return;
                }
            }
        } catch (RuntimeException ex) {
            System.err.println("Error: " + ex.toString());
            showUsage();
            return;
        }

        String cnArg = nextArg();
        if (cnArg == null) {
            System.err.println("Error: No instrumentation component supplied");
            showUsage();
            return;
        }
        
        ComponentName cn = ComponentName.unflattenFromString(cnArg);
        if (cn == null) {
            System.err.println("Error: Bad component name: " + cnArg);
            showUsage();
            return;
        }

        InstrumentationWatcher watcher = null;
        if (wait) {
            watcher = new InstrumentationWatcher();
            watcher.setRawOutput(rawMode);
        }
        float[] oldAnims = null;
        if (no_window_animation) {
            try {
                oldAnims = wm.getAnimationScales();
                wm.setAnimationScale(0, 0.0f);
                wm.setAnimationScale(1, 0.0f);
            } catch (RemoteException e) {
            }
        }

        try {
            if (!mAm.startInstrumentation(cn, profileFile, 0, args, watcher)) {
                System.out.println("INSTRUMENTATION_FAILED: " +
                        cn.flattenToString());
                showUsage();
                return;
            }
        } catch (RemoteException e) {
        }

        if (watcher != null) {
            if (!watcher.waitForFinish()) {
                System.out.println("INSTRUMENTATION_ABORTED: System has crashed.");
            }
        }

        if (oldAnims != null) {
            try {
                wm.setAnimationScales(oldAnims);
            } catch (RemoteException e) {
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
                    }
                }
            }
            return true;
        }
    }

    private void runProfile() {
        String profileFile = null;
        boolean start = false;

        String process = nextArg();
        if (process == null) {
            System.err.println("Error: No profile process supplied");
            showUsage();
            return;
        }
        
        String cmd = nextArg();
        if ("start".equals(cmd)) {
            start = true;
            profileFile = nextArg();
            if (profileFile == null) {
                System.err.println("Error: No profile file path supplied");
                showUsage();
                return;
            }
        } else if (!"stop".equals(cmd)) {
            System.err.println("Error: Profile command " + cmd + " not valid");
            showUsage();
            return;
        }
        
        try {
            if (!mAm.profileControl(process, start, profileFile)) {
                System.out.println("PROFILE FAILED on process " + process);
                return;
            }
        } catch (IllegalArgumentException e) {
            System.out.println("PROFILE FAILED: " + e.getMessage());
            return;
        } catch (IllegalStateException e) {
            System.out.println("PROFILE FAILED: " + e.getMessage());
            return;
        } catch (RemoteException e) {
            System.out.println("PROFILE FAILED: activity manager gone");
            return;
        }
    }

    private String nextOption() {
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

    private String nextOptionData() {
        if (mCurArgData != null) {
            return mCurArgData;
        }
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String data = mArgs[mNextArg];
        mNextArg++;
        return data;
    }

    private String nextArg() {
        if (mNextArg >= mArgs.length) {
            return null;
        }
        String arg = mArgs[mNextArg];
        mNextArg++;
        return arg;
    }

    private void showUsage() {
        System.err.println("usage: am [start|broadcast|instrument|profile]");
        System.err.println("       am start -D INTENT");
        System.err.println("       am broadcast INTENT");
        System.err.println("       am instrument [-r] [-e <ARG_NAME> <ARG_VALUE>] [-p <PROF_FILE>]");
        System.err.println("                [-w] <COMPONENT> ");
        System.err.println("       am profile <PROCESS> [start <PROF_FILE>|stop]");
        System.err.println("");
        System.err.println("       INTENT is described with:");
        System.err.println("                [-a <ACTION>] [-d <DATA_URI>] [-t <MIME_TYPE>]");
        System.err.println("                [-c <CATEGORY> [-c <CATEGORY>] ...]");
        System.err.println("                [-e|--es <EXTRA_KEY> <EXTRA_STRING_VALUE> ...]");
        System.err.println("                [--ez <EXTRA_KEY> <EXTRA_BOOLEAN_VALUE> ...]");
        System.err.println("                [-e|--ei <EXTRA_KEY> <EXTRA_INT_VALUE> ...]");
        System.err.println("                [-n <COMPONENT>] [-f <FLAGS>] [<URI>]");
    }
}
