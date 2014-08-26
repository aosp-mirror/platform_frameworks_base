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
package com.android.tests.memoryusage;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Debug.MemoryInfo;
import android.os.RemoteException;
import android.os.UserHandle;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This test is intended to measure the amount of memory applications use when
 * they start. Names of the applications are passed in command line, and the
 * test starts each application, waits until its memory usage is stabilized and
 * reports the total PSS in kilobytes of each processes.
 * The instrumentation expects the following key to be passed on the command line:
 * apps - A list of applications to start and their corresponding result keys
 * in the following format:
 * -e apps <app name>^<result key>|<app name>^<result key>
 */
public class MemoryUsageTest extends InstrumentationTestCase {

    private static final int SLEEP_TIME = 1000;
    private static final int THRESHOLD = 1024;
    private static final int MAX_ITERATIONS = 20;
    private static final int MIN_ITERATIONS = 6;
    private static final int JOIN_TIMEOUT = 10000;

    private static final String TAG = "MemoryUsageInstrumentation";
    private static final String KEY_APPS = "apps";
    private static final String KEY_PROCS = "persistent";
    private static final String LAUNCHER_KEY = "launcher";
    private Map<String, Intent> mNameToIntent;
    private Map<String, String> mNameToProcess;
    private Map<String, String> mNameToResultKey;
    private Set<String> mPersistentProcesses;
    private IActivityManager mAm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
    }

    @Override
    protected void tearDown() throws Exception {
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
        super.tearDown();
    }

    public void testMemory() {
        MemoryUsageInstrumentation instrumentation =
                (MemoryUsageInstrumentation) getInstrumentation();
        Bundle args = instrumentation.getBundle();
        mAm = ActivityManagerNative.getDefault();

        createMappings();
        parseArgs(args);

        Bundle results = new Bundle();
        for (String app : mNameToResultKey.keySet()) {
            if (!mPersistentProcesses.contains(app)) {
                String processName;
                try {
                    processName = startApp(app);
                    measureMemory(app, processName, results);
                    closeApp();
                } catch (NameNotFoundException e) {
                    Log.i(TAG, "Application " + app + " not found");
                }
            } else {
                measureMemory(app, app, results);
            }
        }
        instrumentation.sendStatus(0, results);
    }

    private String getLauncherPackageName() {
      Intent intent = new Intent(Intent.ACTION_MAIN);
      intent.addCategory(Intent.CATEGORY_HOME);
      ResolveInfo resolveInfo = getInstrumentation().getContext().
          getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
      return resolveInfo.activityInfo.packageName;
    }

    private Map<String, String> parseListToMap(String list) {
        Map<String, String> map = new HashMap<String, String>();
        String names[] = list.split("\\|");
        for (String pair : names) {
            String[] parts = pair.split("\\^");
            if (parts.length != 2) {
                Log.e(TAG, "The apps key is incorectly formatted");
                fail();
            }
            map.put(parts[0], parts[1]);
        }
        return map;
    }

    private void parseArgs(Bundle args) {
        mNameToResultKey = new HashMap<String, String>();
        mPersistentProcesses = new HashSet<String>();
        String appList = args.getString(KEY_APPS);
        String procList = args.getString(KEY_PROCS);
        String mLauncherPackageName = getLauncherPackageName();
        mPersistentProcesses.add(mLauncherPackageName);
        mNameToResultKey.put(mLauncherPackageName, LAUNCHER_KEY);
        if (appList == null && procList == null)
            return;
        if (appList != null) {
            mNameToResultKey.putAll(parseListToMap(appList));
        }
        if (procList != null) {
            Map<String, String> procMap = parseListToMap(procList);
            mPersistentProcesses.addAll(procMap.keySet());
            mNameToResultKey.putAll(procMap);
        }
    }

    private void createMappings() {
        mNameToIntent = new HashMap<String, Intent>();
        mNameToProcess = new HashMap<String, String>();

        PackageManager pm = getInstrumentation().getContext()
                .getPackageManager();
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);
        if (ris == null || ris.isEmpty()) {
            Log.i(TAG, "Could not find any apps");
        } else {
            for (ResolveInfo ri : ris) {
                Log.i(TAG, "Name: " + ri.loadLabel(pm).toString()
                        + " package: " + ri.activityInfo.packageName
                        + " name: " + ri.activityInfo.name);
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

    private String startApp(String appName) throws NameNotFoundException {
        Log.i(TAG, "Starting " + appName);

        if (!mNameToProcess.containsKey(appName))
            throw new NameNotFoundException("Could not find: " + appName);

        String process = mNameToProcess.get(appName);
        Intent startIntent = mNameToIntent.get(appName);

        AppLaunchRunnable runnable = new AppLaunchRunnable(startIntent);
        Thread t = new Thread(runnable);
        t.start();
        try {
            t.join(JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            // ignore
        }

        return process;
    }

    private void closeApp() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        getInstrumentation().getContext().startActivity(homeIntent);
        sleep(3000);
    }

    private void measureMemory(String appName, String processName,
            Bundle results) {
        List<Integer> pssData = new ArrayList<Integer>();
        int pss = 0;
        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            sleep(SLEEP_TIME);
            pss = getPss(processName);
            Log.i(TAG, appName + "=" + pss);
            if (pss < 0) {
                reportError(appName, processName, results);
                return;
            }
            pssData.add(pss);
            if (iteration >= MIN_ITERATIONS && stabilized(pssData)) {
                results.putInt(mNameToResultKey.get(appName), pss);
                return;
            }
            iteration++;
        }

        Log.w(TAG, appName + " memory usage did not stabilize");
        results.putInt(mNameToResultKey.get(appName), average(pssData));
    }

    private int average(List<Integer> pssData) {
        int sum = 0;
        for (int sample : pssData) {
            sum += sample;
        }

        return sum / pssData.size();
    }

    private boolean stabilized(List<Integer> pssData) {
        if (pssData.size() < 3)
            return false;
        int diff1 = Math.abs(pssData.get(pssData.size() - 1) - pssData.get(pssData.size() - 2));
        int diff2 = Math.abs(pssData.get(pssData.size() - 2) - pssData.get(pssData.size() - 3));

        Log.i(TAG, "diff1=" + diff1 + " diff2=" + diff2);

        return (diff1 + diff2) < THRESHOLD;
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

    private int getPss(String processName) {
        ActivityManager am = (ActivityManager) getInstrumentation()
                .getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<RunningAppProcessInfo> apps = am.getRunningAppProcesses();

        for (RunningAppProcessInfo proc : apps) {
            if (!proc.processName.equals(processName)) {
                continue;
            }

            int[] pids = {
                    proc.pid };

            MemoryInfo meminfo = am.getProcessMemoryInfo(pids)[0];
            return meminfo.getTotalPss();

        }
        return -1;
    }

    private class AppLaunchRunnable implements Runnable {
        private Intent mLaunchIntent;

        public AppLaunchRunnable(Intent intent) {
            mLaunchIntent = intent;
        }

        public void run() {
            try {
                String mimeType = mLaunchIntent.getType();
                if (mimeType == null && mLaunchIntent.getData() != null
                        && "content".equals(mLaunchIntent.getData().getScheme())) {
                    mimeType = mAm.getProviderMimeType(mLaunchIntent.getData(),
                            UserHandle.USER_CURRENT);
                }

                mAm.startActivityAndWait(null, null, mLaunchIntent, mimeType,
                        null, null, 0, mLaunchIntent.getFlags(), null, null,
                        UserHandle.USER_CURRENT_OR_SELF);
            } catch (RemoteException e) {
                Log.w(TAG, "Error launching app", e);
            }
        }
    }
}
