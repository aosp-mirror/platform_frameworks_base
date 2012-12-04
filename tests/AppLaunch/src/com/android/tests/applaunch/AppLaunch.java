/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.tests.applaunch;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IActivityManager.WaitResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * This test is intended to measure the time it takes for the apps to start.
 * Names of the applications are passed in command line, and the
 * test starts each application, and reports the start up time in milliseconds.
 * The instrumentation expects the following key to be passed on the command line:
 * apps - A list of applications to start and their corresponding result keys
 * in the following format:
 * -e apps <app name>^<result key>|<app name>^<result key>
 */
public class AppLaunch extends InstrumentationTestCase {

    private static final int JOIN_TIMEOUT = 10000;
    private static final String TAG = "AppLaunch";
    private static final String KEY_APPS = "apps";

    private Map<String, Intent> mNameToIntent;
    private Map<String, String> mNameToProcess;
    private Map<String, String> mNameToResultKey;

    private IActivityManager mAm;

    public void testMeasureStartUpTime() throws RemoteException {
        InstrumentationTestRunner instrumentation =
                (InstrumentationTestRunner)getInstrumentation();
        Bundle args = instrumentation.getBundle();
        mAm = ActivityManagerNative.getDefault();

        createMappings();
        parseArgs(args);

        Bundle results = new Bundle();
        for (String app : mNameToResultKey.keySet()) {
            try {
                startApp(app, results);
                sleep(750);
                closeApp(app);
                sleep(2000);
            } catch (NameNotFoundException e) {
                Log.i(TAG, "Application " + app + " not found");
            }

        }
        instrumentation.sendStatus(0, results);
    }

    private void parseArgs(Bundle args) {
        mNameToResultKey = new LinkedHashMap<String, String>();
        String appList = args.getString(KEY_APPS);

        if (appList == null)
            return;

        String appNames[] = appList.split("\\|");
        for (String pair : appNames) {
            String[] parts = pair.split("\\^");
            if (parts.length != 2) {
                Log.e(TAG, "The apps key is incorectly formatted");
                fail();
            }

            mNameToResultKey.put(parts[0], parts[1]);
        }
    }

    private void createMappings() {
        mNameToIntent = new LinkedHashMap<String, Intent>();
        mNameToProcess = new LinkedHashMap<String, String>();

        PackageManager pm = getInstrumentation().getContext()
                .getPackageManager();
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);
        if (ris == null || ris.isEmpty()) {
            Log.i(TAG, "Could not find any apps");
        } else {
            for (ResolveInfo ri : ris) {
                Intent startIntent = new Intent(intentToResolve);
                startIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startIntent.setClassName(ri.activityInfo.packageName,
                        ri.activityInfo.name);
                mNameToIntent.put(ri.loadLabel(pm).toString(), startIntent);
                mNameToProcess.put(ri.loadLabel(pm).toString(),
                        ri.activityInfo.processName);
            }
        }
    }

    private void startApp(String appName, Bundle results)
            throws NameNotFoundException, RemoteException {
        Log.i(TAG, "Starting " + appName);

        Intent startIntent = mNameToIntent.get(appName);
        if (startIntent == null) {
            Log.w(TAG, "App does not exist: " + appName);
            return;
        }
        AppLaunchRunnable runnable = new AppLaunchRunnable(startIntent);
        Thread t = new Thread(runnable);
        t.start();
        try {
            t.join(JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            // ignore
        }
        WaitResult result = runnable.getResult();
        if(t.isAlive() || (result != null && result.result != ActivityManager.START_SUCCESS)) {
            Log.w(TAG, "Assuming app " + appName + " crashed.");
            reportError(appName, mNameToProcess.get(appName), results);
            return;
        }
        results.putString(mNameToResultKey.get(appName), String.valueOf(result.thisTime));
    }

    private void closeApp(String appName) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        getInstrumentation().getContext().startActivity(homeIntent);
        Intent startIntent = mNameToIntent.get(appName);
        if (startIntent != null) {
            String packageName = startIntent.getComponent().getPackageName();
            try {
                mAm.forceStopPackage(packageName, UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                Log.w(TAG, "Error closing app", e);
            }
        }
    }

    private void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void reportError(String appName, String processName, Bundle results) {
        ActivityManager am = (ActivityManager) getInstrumentation()
                .getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ProcessErrorStateInfo> crashes = am.getProcessesInErrorState();
        if (crashes != null) {
            for (ProcessErrorStateInfo crash : crashes) {
                if (!crash.processName.equals(processName))
                    continue;

                Log.w(TAG, appName + " crashed: " + crash.shortMsg);
                results.putString(mNameToResultKey.get(appName), crash.shortMsg);
                return;
            }
        }

        results.putString(mNameToResultKey.get(appName),
                "Crashed for unknown reason");
        Log.w(TAG, appName
                + " not found in process list, most likely it is crashed");
    }

    private class AppLaunchRunnable implements Runnable {
        private Intent mLaunchIntent;
        private IActivityManager.WaitResult mResult;
        public AppLaunchRunnable(Intent intent) {
            mLaunchIntent = intent;
        }

        public IActivityManager.WaitResult getResult() {
            return mResult;
        }

        public void run() {
            try {
                String packageName = mLaunchIntent.getComponent().getPackageName();
                mAm.forceStopPackage(packageName, UserHandle.USER_CURRENT);
                String mimeType = mLaunchIntent.getType();
                if (mimeType == null && mLaunchIntent.getData() != null
                        && "content".equals(mLaunchIntent.getData().getScheme())) {
                    mimeType = mAm.getProviderMimeType(mLaunchIntent.getData(),
                            UserHandle.USER_CURRENT);
                }

                mResult = mAm.startActivityAndWait(null, mLaunchIntent, mimeType,
                        null, null, 0, mLaunchIntent.getFlags(), null, null, null,
                        UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                Log.w(TAG, "Error launching app", e);
            }
        }
    }
}
