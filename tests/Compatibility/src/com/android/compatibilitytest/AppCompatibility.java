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
import android.app.ActivityManager.RunningTaskInfo;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.DropBoxManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Application Compatibility Test that launches an application and detects
 * crashes.
 */
@RunWith(AndroidJUnit4.class)
public class AppCompatibility {

    private static final String TAG = AppCompatibility.class.getSimpleName();
    private static final String PACKAGE_TO_LAUNCH = "package_to_launch";
    private static final String APP_LAUNCH_TIMEOUT_MSECS = "app_launch_timeout_ms";
    private static final String WORKSPACE_LAUNCH_TIMEOUT_MSECS = "workspace_launch_timeout_ms";
    private static final Set<String> DROPBOX_TAGS = new HashSet<>();
    static {
        DROPBOX_TAGS.add("SYSTEM_TOMBSTONE");
        DROPBOX_TAGS.add("system_app_anr");
        DROPBOX_TAGS.add("system_app_native_crash");
        DROPBOX_TAGS.add("system_app_crash");
        DROPBOX_TAGS.add("data_app_anr");
        DROPBOX_TAGS.add("data_app_native_crash");
        DROPBOX_TAGS.add("data_app_crash");
    }
    private static final int MAX_CRASH_SNIPPET_LINES = 20;
    private static final int MAX_NUM_CRASH_SNIPPET = 3;

    // time waiting for app to launch
    private int mAppLaunchTimeout = 7000;
    // time waiting for launcher home screen to show up
    private int mWorkspaceLaunchTimeout = 2000;

    private Context mContext;
    private ActivityManager mActivityManager;
    private PackageManager mPackageManager;
    private Bundle mArgs;
    private Instrumentation mInstrumentation;
    private String mLauncherPackageName;
    private IActivityController mCrashSupressor = new CrashSuppressor();
    private Map<String, List<String>> mAppErrors = new HashMap<>();

    @Before
    public void setUp() throws Exception {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mContext = InstrumentationRegistry.getTargetContext();
        mActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = mContext.getPackageManager();
        mArgs = InstrumentationRegistry.getArguments();

        // resolve launcher package name
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = mPackageManager.resolveActivity(
                intent, PackageManager.MATCH_DEFAULT_ONLY);
        mLauncherPackageName = resolveInfo.activityInfo.packageName;
        Assert.assertNotNull("failed to resolve package name for launcher", mLauncherPackageName);
        Log.v(TAG, "Using launcher package name: " + mLauncherPackageName);

        // Parse optional inputs.
        String appLaunchTimeoutMsecs = mArgs.getString(APP_LAUNCH_TIMEOUT_MSECS);
        if (appLaunchTimeoutMsecs != null) {
            mAppLaunchTimeout = Integer.parseInt(appLaunchTimeoutMsecs);
        }
        String workspaceLaunchTimeoutMsecs = mArgs.getString(WORKSPACE_LAUNCH_TIMEOUT_MSECS);
        if (workspaceLaunchTimeoutMsecs != null) {
            mWorkspaceLaunchTimeout = Integer.parseInt(workspaceLaunchTimeoutMsecs);
        }
        mInstrumentation.getUiAutomation().setRotation(UiAutomation.ROTATION_FREEZE_0);

        // set activity controller to suppress crash dialogs and collects them by process name
        mAppErrors.clear();
        IActivityManager.Stub.asInterface(ServiceManager.checkService(Context.ACTIVITY_SERVICE))
            .setActivityController(mCrashSupressor, false);
    }

    @After
    public void tearDown() throws Exception {
        // unset activity controller
        IActivityManager.Stub.asInterface(ServiceManager.checkService(Context.ACTIVITY_SERVICE))
            .setActivityController(null, false);
        mInstrumentation.getUiAutomation().setRotation(UiAutomation.ROTATION_UNFREEZE);
    }

    /**
     * Actual test case that launches the package and throws an exception on the
     * first error.
     *
     * @throws Exception
     */
    @Test
    public void testAppStability() throws Exception {
        String packageName = mArgs.getString(PACKAGE_TO_LAUNCH);
        if (packageName != null) {
            Log.d(TAG, "Launching app " + packageName);
            Intent intent = getLaunchIntentForPackage(packageName);
            if (intent == null) {
                Log.w(TAG, String.format("Skipping %s; no launch intent", packageName));
                return;
            }
            long startTime = System.currentTimeMillis();
            launchActivity(packageName, intent);
            try {
                checkDropbox(startTime, packageName);
                if (mAppErrors.containsKey(packageName)) {
                    StringBuilder message = new StringBuilder("Error(s) detected for package: ")
                            .append(packageName);
                    List<String> errors = mAppErrors.get(packageName);
                    for (int i = 0; i < MAX_NUM_CRASH_SNIPPET && i < errors.size(); i++) {
                        String err = errors.get(i);
                        message.append("\n\n");
                        // limit the size of each crash snippet
                        message.append(truncate(err, MAX_CRASH_SNIPPET_LINES));
                    }
                    if (errors.size() > MAX_NUM_CRASH_SNIPPET) {
                        message.append(String.format("\n... %d more errors omitted ...",
                                errors.size() - MAX_NUM_CRASH_SNIPPET));
                    }
                    Assert.fail(message.toString());
                }
                // last check: see if app process is still running
                Assert.assertTrue("app package \"" + packageName + "\" no longer found in running "
                    + "tasks, but no explicit crashes were detected; check logcat for details",
                    processStillUp(packageName));
            } finally {
                returnHome();
            }
        } else {
            Log.d(TAG, "Missing argument, use " + PACKAGE_TO_LAUNCH +
                    " to specify the package to launch");
        }
    }

    /**
     * Truncate the text to at most the specified number of lines, and append a marker at the end
     * when truncated
     * @param text
     * @param maxLines
     * @return
     */
    private static String truncate(String text, int maxLines) {
        String[] lines = text.split("\\r?\\n");
        StringBuilder ret = new StringBuilder();
        for (int i = 0; i < maxLines && i < lines.length; i++) {
            ret.append(lines[i]);
            ret.append('\n');
        }
        if (lines.length > maxLines) {
            ret.append("... ");
            ret.append(lines.length - maxLines);
            ret.append(" more lines truncated ...\n");
        }
        return ret.toString();
    }

    /**
     * Check dropbox for entries of interest regarding the specified process
     * @param startTime if not 0, only check entries with timestamp later than the start time
     * @param processName the process name to check for
     */
    private void checkDropbox(long startTime, String processName) {
        DropBoxManager dropbox = (DropBoxManager) mContext
                .getSystemService(Context.DROPBOX_SERVICE);
        DropBoxManager.Entry entry = null;
        while (null != (entry = dropbox.getNextEntry(null, startTime))) {
            try {
                // only check entries with tag that's of interest
                String tag = entry.getTag();
                if (DROPBOX_TAGS.contains(tag)) {
                    String content = entry.getText(4096);
                    if (content != null) {
                        if (content.contains(processName)) {
                            addProcessError(processName, "dropbox:" + tag, content);
                        }
                    }
                }
                startTime = entry.getTimeMillis();
            } finally {
                entry.close();
            }
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
        UiModeManager umm = (UiModeManager) mContext.getSystemService(Context.UI_MODE_SERVICE);
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
    private void launchActivity(String packageName, Intent intent) {
        Log.d(TAG, String.format("launching package \"%s\" with intent: %s",
                packageName, intent.toString()));

        // Launch Activity
        mContext.startActivity(intent);

        try {
            // artificial delay: in case app crashes after doing some work during launch
            Thread.sleep(mAppLaunchTimeout);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void addProcessError(String processName, String errorType, String errorInfo) {
        // parse out the package name if necessary, for apps with multiple proceses
        String pkgName = processName.split(":", 2)[0];
        List<String> errors;
        if (mAppErrors.containsKey(pkgName)) {
            errors = mAppErrors.get(pkgName);
        }  else {
            errors = new ArrayList<>();
        }
        errors.add(String.format("### Type: %s, Details:\n%s", errorType, errorInfo));
        mAppErrors.put(pkgName, errors);
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

    /**
     * An {@link IActivityController} that instructs framework to kill processes hitting crashes
     * directly without showing crash dialogs
     *
     */
    private class CrashSuppressor extends IActivityController.Stub {

        @Override
        public boolean activityStarting(Intent intent, String pkg) throws RemoteException {
            Log.d(TAG, "activity starting: " + intent.getComponent().toShortString());
            return true;
        }

        @Override
        public boolean activityResuming(String pkg) throws RemoteException {
            Log.d(TAG, "activity resuming: " + pkg);
            return true;
        }

        @Override
        public boolean appCrashed(String processName, int pid, String shortMsg, String longMsg,
                long timeMillis, String stackTrace) throws RemoteException {
            Log.d(TAG, "app crash: " + processName);
            addProcessError(processName, "crash", stackTrace);
            // don't show dialog
            return false;
        }

        @Override
        public int appEarlyNotResponding(String processName, int pid, String annotation)
                throws RemoteException {
            // ignore
            return 0;
        }

        @Override
        public int appNotResponding(String processName, int pid, String processStats)
                throws RemoteException {
            Log.d(TAG, "app ANR: " + processName);
            addProcessError(processName, "ANR", processStats);
            // don't show dialog
            return -1;
        }

        @Override
        public int systemNotResponding(String msg) throws RemoteException {
            // ignore
            return -1;
        }
    }
}
