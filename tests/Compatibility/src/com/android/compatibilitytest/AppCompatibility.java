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
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
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

    private static final String TAG = "AppCompability";
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
    }

    @Override
    protected void tearDown() throws Exception {
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
            Collection<ProcessErrorStateInfo> err = launchActivity(packageName);
            // Make sure there are no errors when launching the application,
            // otherwise raise an
            // exception with the first error encountered.
            assertNull(getFirstError(err), err);
            assertTrue("App crashed after launch.", processStillUp(packageName));
        } else {
            Log.d(TAG, "Missing argument, use " + PACKAGE_TO_LAUNCH +
                    " to specify the package to launch");
        }
    }

    /**
     * Gets the first error in collection and return the long message for it.
     *
     * @param in {@link Collection} of {@link ProcessErrorStateInfo} to parse.
     * @return {@link String} the long message of the error.
     */
    private String getFirstError(Collection<ProcessErrorStateInfo> in) {
        if (in == null) {
            return null;
        }
        ProcessErrorStateInfo err = in.iterator().next();
        if (err != null) {
            return err.stackTrace;
        }
        return null;
    }

    /**
     * Launches and activity and queries for errors.
     *
     * @param packageName {@link String} the package name of the application to
     *            launch.
     * @return {@link Collection} of {@link ProcessErrorStateInfo} detected
     *         during the app launch.
     */
    private Collection<ProcessErrorStateInfo> launchActivity(String packageName) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent intent = mPackageManager.getLaunchIntentForPackage(packageName);
        // Skip if the apk does not have a launch intent.
        if (intent == null) {
            Log.d(TAG, "Skipping " + packageName + "; missing launch intent");
            return null;
        }

        // We check for any Crash or ANR dialogs that are already up, and we
        // ignore them. This is
        // so that we don't report crashes that were caused by prior apps (which
        // those particular
        // tests should have caught and reported already). Otherwise, test
        // failures would cascade
        // from the initial broken app to many/all of the tests following that
        // app's launch.
        final Collection<ProcessErrorStateInfo> preErr =
                mActivityManager.getProcessesInErrorState();

        // Launch Activity
        mContext.startActivity(intent);

        try {
            Thread.sleep(mAppLaunchTimeout);
        } catch (InterruptedException e) {
            // ignore
        }

        // Send the "home" intent and wait 2 seconds for us to get there
        mContext.startActivity(homeIntent);
        try {
            Thread.sleep(mWorkspaceLaunchTimeout);
        } catch (InterruptedException e) {
            // ignore
        }

        // See if there are any errors. We wait until down here to give ANRs as
        // much time as
        // possible to occur.
        final Collection<ProcessErrorStateInfo> postErr =
                mActivityManager.getProcessesInErrorState();
        // Take the difference between the error processes we see now, and the
        // ones that were
        // present when we started
        if (preErr != null && postErr != null) {
            postErr.removeAll(preErr);
        }
        return postErr;
    }

    /**
     * Determine if a given package is still running.
     *
     * @param packageName {@link String} package to look for
     * @return True if package is running, false otherwise.
     */
    private boolean processStillUp(String packageName) {
        try {
            PackageInfo packageInfo = mPackageManager.getPackageInfo(packageName, 0);
            String processName = packageInfo.applicationInfo.processName;
            List<RunningAppProcessInfo> runningApps = mActivityManager.getRunningAppProcesses();
            for (RunningAppProcessInfo app : runningApps) {
                if (app.processName.equalsIgnoreCase(processName)) {
                    Log.d(TAG, "Found process " + app.processName);
                    return true;
                }
            }
            Log.d(TAG, "Failed to find process " + processName + " with package name "
                    + packageName);
        } catch (NameNotFoundException e) {
            Log.w(TAG, "Failed to find package " + packageName);
            return false;
        }
        return false;
    }
}
