/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.IActivityManager.WaitResult;
import android.app.UiAutomation;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private static final String TAG = AppLaunch.class.getSimpleName();
    private static final String KEY_APPS = "apps";
    private static final String KEY_LAUNCH_ITERATIONS = "launch_iterations";
    // optional parameter: comma separated list of required account types before proceeding
    // with the app launch
    private static final String KEY_REQUIRED_ACCOUNTS = "required_accounts";
    private static final int INITIAL_LAUNCH_IDLE_TIMEOUT = 7500; //7.5s to allow app to idle
    private static final int POST_LAUNCH_IDLE_TIMEOUT = 750; //750ms idle for non initial launches
    private static final int BETWEEN_LAUNCH_SLEEP_TIMEOUT = 2000; //2s between launching apps

    private Map<String, Intent> mNameToIntent;
    private Map<String, String> mNameToProcess;
    private Map<String, String> mNameToResultKey;
    private Map<String, Long> mNameToLaunchTime;
    private IActivityManager mAm;
    private int mLaunchIterations = 10;
    private Bundle mResult = new Bundle();
    private Set<String> mRequiredAccounts;

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

    public void testMeasureStartUpTime() throws RemoteException, NameNotFoundException {
        InstrumentationTestRunner instrumentation =
                (InstrumentationTestRunner)getInstrumentation();
        Bundle args = instrumentation.getArguments();
        mAm = ActivityManagerNative.getDefault();

        createMappings();
        parseArgs(args);
        checkAccountSignIn();

        // do initial app launch, without force stopping
        for (String app : mNameToResultKey.keySet()) {
            long launchTime = startApp(app, false);
            if (launchTime <= 0) {
                mNameToLaunchTime.put(app, -1L);
                // simply pass the app if launch isn't successful
                // error should have already been logged by startApp
                continue;
            } else {
                mNameToLaunchTime.put(app, launchTime);
            }
            sleep(INITIAL_LAUNCH_IDLE_TIMEOUT);
            closeApp(app, false);
            sleep(BETWEEN_LAUNCH_SLEEP_TIMEOUT);
        }
        // do the real app launch now
        for (int i = 0; i < mLaunchIterations; i++) {
            for (String app : mNameToResultKey.keySet()) {
                long prevLaunchTime = mNameToLaunchTime.get(app);
                long launchTime = 0;
                if (prevLaunchTime < 0) {
                    // skip if the app has previous failures
                    continue;
                }
                launchTime = startApp(app, true);
                if (launchTime <= 0) {
                    // if it fails once, skip the rest of the launches
                    mNameToLaunchTime.put(app, -1L);
                    continue;
                }
                // keep the min launch time
                if (launchTime < prevLaunchTime) {
                    mNameToLaunchTime.put(app, launchTime);
                }
                sleep(POST_LAUNCH_IDLE_TIMEOUT);
                closeApp(app, true);
                sleep(BETWEEN_LAUNCH_SLEEP_TIMEOUT);
            }
        }
        for (String app : mNameToResultKey.keySet()) {
            long launchTime = mNameToLaunchTime.get(app);
            if (launchTime != -1) {
                mResult.putLong(mNameToResultKey.get(app), launchTime);
            }
        }
        instrumentation.sendStatus(0, mResult);
    }

    private void parseArgs(Bundle args) {
        mNameToResultKey = new LinkedHashMap<String, String>();
        mNameToLaunchTime = new HashMap<String, Long>();
        String launchIterations = args.getString(KEY_LAUNCH_ITERATIONS);
        if (launchIterations != null) {
            mLaunchIterations = Integer.parseInt(launchIterations);
        }
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
            mNameToLaunchTime.put(parts[0], 0L);
        }
        String requiredAccounts = args.getString(KEY_REQUIRED_ACCOUNTS);
        if (requiredAccounts != null) {
            mRequiredAccounts = new HashSet<String>();
            for (String accountType : requiredAccounts.split(",")) {
                mRequiredAccounts.add(accountType);
            }
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
                String appName = ri.loadLabel(pm).toString();
                if (appName != null) {
                    mNameToIntent.put(appName, startIntent);
                    mNameToProcess.put(appName, ri.activityInfo.processName);
                }
            }
        }
    }

    private long startApp(String appName, boolean forceStopBeforeLaunch)
            throws NameNotFoundException, RemoteException {
        Log.i(TAG, "Starting " + appName);

        Intent startIntent = mNameToIntent.get(appName);
        if (startIntent == null) {
            Log.w(TAG, "App does not exist: " + appName);
            mResult.putString(mNameToResultKey.get(appName), "App does not exist");
            return -1;
        }
        AppLaunchRunnable runnable = new AppLaunchRunnable(startIntent, forceStopBeforeLaunch);
        Thread t = new Thread(runnable);
        t.start();
        try {
            t.join(JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            // ignore
        }
        WaitResult result = runnable.getResult();
        // report error if any of the following is true:
        // * launch thread is alive
        // * result is not null, but:
        //   * result is not START_SUCESS
        //   * or in case of no force stop, result is not TASK_TO_FRONT either
        if (t.isAlive() || (result != null
                && ((result.result != ActivityManager.START_SUCCESS)
                        && (!forceStopBeforeLaunch
                                && result.result != ActivityManager.START_TASK_TO_FRONT)))) {
            Log.w(TAG, "Assuming app " + appName + " crashed.");
            reportError(appName, mNameToProcess.get(appName));
            return -1;
        }
        return result.thisTime;
    }

    private void checkAccountSignIn() {
        // ensure that the device has the required account types before starting test
        // e.g. device must have a valid Google account sign in to measure a meaningful launch time
        // for Gmail
        if (mRequiredAccounts == null || mRequiredAccounts.isEmpty()) {
            return;
        }
        final AccountManager am =
                (AccountManager) getInstrumentation().getTargetContext().getSystemService(
                        Context.ACCOUNT_SERVICE);
        Account[] accounts = am.getAccounts();
        // use set here in case device has multiple accounts of the same type
        Set<String> foundAccounts = new HashSet<String>();
        for (Account account : accounts) {
            if (mRequiredAccounts.contains(account.type)) {
                foundAccounts.add(account.type);
            }
        }
        // check if account type matches, if not, fail test with message on what account types
        // are missing
        if (mRequiredAccounts.size() != foundAccounts.size()) {
            mRequiredAccounts.removeAll(foundAccounts);
            StringBuilder sb = new StringBuilder("Device missing these accounts:");
            for (String account : mRequiredAccounts) {
                sb.append(' ');
                sb.append(account);
            }
            fail(sb.toString());
        }
    }

    private void closeApp(String appName, boolean forceStopApp) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        getInstrumentation().getContext().startActivity(homeIntent);
        sleep(POST_LAUNCH_IDLE_TIMEOUT);
        if (forceStopApp) {
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
    }

    private void sleep(int time) {
        try {
            Thread.sleep(time);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void reportError(String appName, String processName) {
        ActivityManager am = (ActivityManager) getInstrumentation()
                .getContext().getSystemService(Context.ACTIVITY_SERVICE);
        List<ProcessErrorStateInfo> crashes = am.getProcessesInErrorState();
        if (crashes != null) {
            for (ProcessErrorStateInfo crash : crashes) {
                if (!crash.processName.equals(processName))
                    continue;

                Log.w(TAG, appName + " crashed: " + crash.shortMsg);
                mResult.putString(mNameToResultKey.get(appName), crash.shortMsg);
                return;
            }
        }

        mResult.putString(mNameToResultKey.get(appName),
                "Crashed for unknown reason");
        Log.w(TAG, appName
                + " not found in process list, most likely it is crashed");
    }

    private class AppLaunchRunnable implements Runnable {
        private Intent mLaunchIntent;
        private IActivityManager.WaitResult mResult;
        private boolean mForceStopBeforeLaunch;

        public AppLaunchRunnable(Intent intent, boolean forceStopBeforeLaunch) {
            mLaunchIntent = intent;
            mForceStopBeforeLaunch = forceStopBeforeLaunch;
        }

        public IActivityManager.WaitResult getResult() {
            return mResult;
        }

        public void run() {
            try {
                String packageName = mLaunchIntent.getComponent().getPackageName();
                if (mForceStopBeforeLaunch) {
                    mAm.forceStopPackage(packageName, UserHandle.USER_CURRENT);
                }
                String mimeType = mLaunchIntent.getType();
                if (mimeType == null && mLaunchIntent.getData() != null
                        && "content".equals(mLaunchIntent.getData().getScheme())) {
                    mimeType = mAm.getProviderMimeType(mLaunchIntent.getData(),
                            UserHandle.USER_CURRENT);
                }

                mResult = mAm.startActivityAndWait(null, null, mLaunchIntent, mimeType,
                        null, null, 0, mLaunchIntent.getFlags(), null, null,
                        UserHandle.USER_CURRENT);
            } catch (RemoteException e) {
                Log.w(TAG, "Error launching app", e);
            }
        }
    }
}
