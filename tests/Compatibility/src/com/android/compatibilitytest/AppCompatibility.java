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

package com.android.compatibilitytest;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.app.UiModeManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManager.RunningTaskInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Configuration;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.util.Log;

import junit.framework.Assert;

import java.util.Collection;
import java.util.List;

/**
 * Application Compatibility Test that launches an application and detects
 * crashes.
 */
public class AppCompatibility extends InstrumentationTestCase {

    private static final String TAG = AppCompatibility.class.getSimpleName();
    private static final String PACKAGE_TO_LAUNCH = "package_to_launch";
    private static final String APP_LAUNCH_TIMEOUT_MSECS = "app_launch_timeout_ms";
    private static final String WORKSPACE_LAUNCH_TIMEOUT_MSECS = "workspace_launch_timeout_ms";

    private int mAppLaunchTimeout = 7000;
    private int mWorkspaceLaunchTimeout = 2000;

    private Context mContext;
    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private AppCompatibilityRunner mRunner;
    private Bundle mArgs;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mRunner = (AppCompatibilityRunner) getInstrumentation();
        assertNotNull("Could not fetch InstrumentationTestRunner.", mRunner);

        mContext = mRunner.getTargetContext();
        Assert.assertNotNull("Could not get the Context", mContext);

        mActivityManager = (ActivityManager)
                mContext.getSystemService(Context.ACTIVITY_SERVICE);
        Assert.assertNotNull("Could not get Activity Manager", mActivityManager);

        mPackageManager = mContext.getPackageManager();
        Assert.assertNotNull("Missing Package Manager", mPackageManager);

        mArgs = mRunner.getBundle();

        // Parse optional inputs.
        String appLaunchTimeoutMsecs = mArgs.getString(APP_LAUNCH_TIMEOUT_MSECS);
        if (appLaunchTimeoutMsecs != null) {
            mAppLaunchTimeout = Integer.parseInt(appLaunchTimeoutMsecs);
        }
        String workspaceLaunchTimeoutMsecs = mArgs.getString(WORKSPACE_LAUNCH_TIMEOUT_MSECS);
        if (workspaceLaunchTimeoutMsecs != null) {
            mWorkspaceLaunchTimeout = Integer.parseInt(workspaceLaunchTimeoutMsecs);
        }
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);
    }

    @Override
    protected void tearDown() throws Exception {
        getInstrumentation().getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
        super.tearDown();
    }

    /**
     * Actual test case that launches the package and throws an exception on the
     * first error.
     *
     * @throws Exception
     */
    public void testAppStability() throws Exception {
        String packageName = mArgs.getString(PACKAGE_TO_LAUNCH);
        if (packageName != null) {
            Log.d(TAG, "Launching app " + packageName);
            Intent intent = getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.w(TAG, String.format("Skipping %s; no launch intent", packageName));
                return;
            }
            ProcessErrorStateInfo err = launchActivity(packageName, intent);
            // Make sure there are no errors when launching the application,
            // otherwise raise an
            // exception with the first error encountered.
            assertNull(getStackTrace(err), err);
            try {
                assertTrue("App crashed after launch.", processStillUp(packageName));
            } finally {
                returnHome();
            }
        } else {
            Log.d(TAG, "Missing argument, use " + PACKAGE_TO_LAUNCH +
                    " to specify the package to launch");
        }
    }

    /**
     * Gets the stack trace for the error.
     *
     * @param in {@link ProcessErrorStateInfo} to parse.
     * @return {@link String} the long message of the error.
     */
    private String getStackTrace(ProcessErrorStateInfo in) {
        if (in == null) {
            return null;
        } else {
            return in.stackTrace;
        }
    }

    /**
     * Returns the process name that the package is going to use.
     *
     * @param packageName name of the package
     * @return process name of the package
     */
    private String getProcessName(String packageName) {
        try {
            PackageInfo pi = mPackageManager.getPackageInfo(packageName, 0);
            return pi.applicationInfo.processName;
        } catch (NameNotFoundException e) {
            return packageName;
        }
    }

    private void returnHome() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Send the "home" intent and wait 2 seconds for us to get there
        mContext.startActivity(homeIntent);
        try {
            Thread.sleep(mWorkspaceLaunchTimeout);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private Intent getLaunchIntentForPackage(String packageName) {
        UiModeManager umm = (UiModeManager)
                getInstrumentation().getContext().getSystemService(Context.UI_MODE_SERVICE);
        boolean isLeanback = umm.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        Intent intent = null;
        if (isLeanback) {
            intent = mPackageManager.getLeanbackLaunchIntentForPackage(packageName);
        } else {
            intent = mPackageManager.getLaunchIntentForPackage(packageName);
        }
        return intent;
    }

    /**
     * Launches and activity and queries for errors.
     *
     * @param packageName {@link String} the package name of the application to
     *            launch.
     * @return {@link Collection} of {@link ProcessErrorStateInfo} detected
     *         during the app launch.
     */
    private ProcessErrorStateInfo launchActivity(String packageName, Intent intent) {
        Log.d(TAG, String.format("launching package \"%s\" with intent: %s",
                packageName, intent.toString()));

        String processName = getProcessName(packageName);

        // Launch Activity
        mContext.startActivity(intent);

        try {
            Thread.sleep(mAppLaunchTimeout);
        } catch (InterruptedException e) {
            // ignore
        }

        // See if there are any errors. We wait until down here to give ANRs as much time as
        // possible to occur.
        final Collection<ProcessErrorStateInfo> postErr =
                mActivityManager.getProcessesInErrorState();

        if (postErr == null) {
            return null;
        }
        for (ProcessErrorStateInfo error : postErr) {
            if (error.processName.equals(processName)) {
                return error;
            }
        }
        return null;
    }

    /**
     * Determine if a given package is still running.
     *
     * @param packageName {@link String} package to look for
     * @return True if package is running, false otherwise.
     */
    private boolean processStillUp(String packageName) {
        @SuppressWarnings("deprecation")
        List<RunningTaskInfo> infos = mActivityManager.getRunningTasks(100);
        for (RunningTaskInfo info : infos) {
            if (info.baseActivity.getPackageName().equals(packageName)) {
                return true;
            }
        }
        return false;
    }
}
