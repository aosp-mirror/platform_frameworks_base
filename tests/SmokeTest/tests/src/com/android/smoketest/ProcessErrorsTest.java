/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.smoketest;

import android.app.ActivityManager;
import android.app.ActivityManager.ProcessErrorStateInfo;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.test.AndroidTestCase;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * This smoke test is designed to check for crashes and ANRs in an attempt to quickly determine if
 * all minimal functionality in the build is working properly.
 */
public class ProcessErrorsTest extends AndroidTestCase {

    private static final String TAG = "ProcessErrorsTest";

    private final Intent mHomeIntent;

    protected ActivityManager mActivityManager;
    protected PackageManager mPackageManager;

    /**
     * Used to buffer asynchronously-caused crashes and ANRs so that we can have a big fail-party
     * in the catch-all testCase.
     */
    private static final Collection<ProcessError> mAsyncErrors =
            Collections.synchronizedSet(new LinkedHashSet<ProcessError>());

    public ProcessErrorsTest() {
        mHomeIntent = new Intent(Intent.ACTION_MAIN);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();
        // First, make sure we have a Context
        assertNotNull("getContext() returned null!", getContext());

        mActivityManager = (ActivityManager)
                getContext().getSystemService(Context.ACTIVITY_SERVICE);
        mPackageManager = getContext().getPackageManager();
    }

    public void testSetUpConditions() throws Exception {
        assertNotNull(mActivityManager);
        assertNotNull(mPackageManager);
    }

    public void testNoProcessErrorsAfterBoot() throws Exception {
        final String reportMsg = checkForProcessErrors();
        if (reportMsg != null) {
            Log.w(TAG, reportMsg);
        }

        // report a non-empty list back to the test framework
        assertNull(reportMsg, reportMsg);
    }

    /**
     * A test that runs all Launcher-launchable activities and verifies that no ANRs or crashes
     * happened while doing so.
     */
    public void testRunAllActivities() throws Exception {
        final Set<ProcessError> errSet = new LinkedHashSet<ProcessError>();

        for (ResolveInfo app : getLauncherActivities(mPackageManager)) {
            final Collection<ProcessError> errProcs = runOneActivity(app);
            if (errProcs != null) {
                errSet.addAll(errProcs);
            }
        }

        if (!errSet.isEmpty()) {
            fail(String.format("Got %d errors:\n%s", errSet.size(),
                    reportWrappedListContents(errSet)));
        }
    }

    /**
     * This test checks for asynchronously-caused errors (crashes or ANRs) and fails in case any
     * were found.  This prevents us from needing to fail unrelated testcases when, for instance
     * a background thread causes a crash or ANR.
     * <p />
     * Because this behavior depends on the contents of static member {@link mAsyncErrors}, we clear
     * that state here as a side-effect so that if two successive runs happen in the same process,
     * the asynchronous errors in the second test run won't include errors produced during the first
     * run.
     */
    public void testZZReportAsyncErrors() throws Exception {
        try {
            if (!mAsyncErrors.isEmpty()) {
                fail(String.format("Got %d asynchronous errors:\n%s", mAsyncErrors.size(),
                        reportWrappedListContents(mAsyncErrors)));
            }
        } finally {
            // Reset state just in case we should get another set of runs in the same process
            mAsyncErrors.clear();
        }
    }


    /**
     * A method to run the specified Activity and return a {@link Collection} of the Activities that
     * were in an error state, as listed by {@link ActivityManager.getProcessesInErrorState()}.
     * <p />
     * The method will launch the app, wait for 7 seconds, check for apps in the error state, send
     * the Home intent, wait for 2 seconds, and then return.
     */
    public Collection<ProcessError> runOneActivity(ResolveInfo app) {
        final long appLaunchWait = 7000;
        final long homeLaunchWait = 2000;

        Log.i(TAG, String.format("Running activity %s/%s", app.activityInfo.packageName,
                app.activityInfo.name));

        // We check for any Crash or ANR dialogs that are already up, and we ignore them.  This is
        // so that we don't report crashes that were caused by prior apps (which those particular
        // tests should have caught and reported already).
        final Collection<ProcessError> preErrProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());

        // launch app, and wait 7 seconds for it to start/settle
        final Intent intent = intentForActivity(app);
        if (intent == null) {
            Log.i(TAG, String.format("Activity %s/%s is disabled, skipping",
                    app.activityInfo.packageName, app.activityInfo.name));
            return Collections.EMPTY_LIST;
        }
        getContext().startActivity(intent);
        try {
            Thread.sleep(appLaunchWait);
        } catch (InterruptedException e) {
            // ignore
        }

        // Send the "home" intent and wait 2 seconds for us to get there
        getContext().startActivity(mHomeIntent);
        try {
            Thread.sleep(homeLaunchWait);
        } catch (InterruptedException e) {
            // ignore
        }

        // See if there are any errors.  We wait until down here to give ANRs as much time as
        // possible to occur.
        final Collection<ProcessError> errProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());

        // Distinguish the asynchronous crashes/ANRs from the synchronous ones by checking the
        // crash package name against the package name for {@code app}
        if (errProcs != null) {
            Iterator<ProcessError> errIter = errProcs.iterator();
            while (errIter.hasNext()) {
                ProcessError err = errIter.next();
                if (!packageMatches(app, err)) {
                    // async!  Drop into mAsyncErrors and don't report now
                    mAsyncErrors.add(err);
                    errIter.remove();
                }
            }
        }
        // Take the difference between the remaining current error processes and the ones that were
        // present when we started.  The result is guaranteed to be:
        // 1) Errors that are pertinent to this app's package
        // 2) Errors that are pertinent to this particular app invocation
        if (errProcs != null && preErrProcs != null) {
            errProcs.removeAll(preErrProcs);
        }

        return errProcs;
    }

    private String checkForProcessErrors() throws Exception {
        List<ProcessErrorStateInfo> errList;
        errList = mActivityManager.getProcessesInErrorState();

        // note: this contains information about each process that is currently in an error
        // condition.  if the list is empty (null) then "we're good".

        // if the list is non-empty, then it's useful to report the contents of the list
        final String reportMsg = reportListContents(errList);
        return reportMsg;
    }

    /**
     * A helper function that checks whether the specified error could have been caused by the
     * specified app.
     *
     * @param app The app to check against
     * @param err The error that we're considering
     */
    private static boolean packageMatches(ResolveInfo app, ProcessError err) {
        final String appPkg = app.activityInfo.packageName;
        final String errPkg = err.info.processName;
        Log.d(TAG, String.format("packageMatches(%s, %s)", appPkg, errPkg));
        return appPkg.equals(errPkg);
    }

    /**
     * A helper function to query the provided {@link PackageManager} for a list of Activities that
     * can be launched from Launcher.
     */
    static List<ResolveInfo> getLauncherActivities(PackageManager pm) {
        final Intent launchable = new Intent(Intent.ACTION_MAIN);
        launchable.addCategory(Intent.CATEGORY_LAUNCHER);
        final List<ResolveInfo> activities = pm.queryIntentActivities(launchable, 0);
        return activities;
    }

    /**
     * A helper function to create an {@link Intent} to run, given a {@link ResolveInfo} specifying
     * an activity to be launched.
     * 
     * @return the {@link Intent} or <code>null</code> if given app is disabled
     */
    Intent intentForActivity(ResolveInfo app) {
        final ComponentName component = new ComponentName(app.activityInfo.packageName,
                app.activityInfo.name);
        if (getContext().getPackageManager().getComponentEnabledSetting(component) == 
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
            return null;
        }
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    /**
     * Report error reports for {@link ProcessErrorStateInfo} instances that are wrapped inside of
     * {@link ProcessError} instances.  Just unwraps and calls
     * {@see reportListContents(Collection<ProcessErrorStateInfo>)}.
     */
    static String reportWrappedListContents(Collection<ProcessError> errList) {
        List<ProcessErrorStateInfo> newList = new ArrayList<ProcessErrorStateInfo>(errList.size());
        for (ProcessError err : errList) {
            newList.add(err.info);
        }
        return reportListContents(newList);
    }

    /**
     * This helper function will dump the actual error reports.
     * 
     * @param errList The error report containing one or more error records.
     * @return Returns a string containing all of the errors.
     */
    private static String reportListContents(Collection<ProcessErrorStateInfo> errList) {
        if (errList == null) return null;

        StringBuilder builder = new StringBuilder();

        Iterator<ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ProcessErrorStateInfo entry = iter.next();

            String condition;
            switch (entry.condition) {
            case ActivityManager.ProcessErrorStateInfo.CRASHED:
                condition = "a CRASH";
                break;
            case ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING:
                condition = "an ANR";
                break;
            default:
                condition = "an unknown error";
                break;
            }

            builder.append(String.format("Process %s encountered %s (%s)", entry.processName,
                    condition, entry.shortMsg));
            if (entry.condition == ActivityManager.ProcessErrorStateInfo.CRASHED) {
                builder.append(String.format(" with stack trace:\n%s\n", entry.stackTrace));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    /**
     * A {@link ProcessErrorStateInfo} wrapper class that hashes how we want (so that equivalent
     * crashes are considered equal).
     */
    static class ProcessError {
        public final ProcessErrorStateInfo info;

        public ProcessError(ProcessErrorStateInfo newInfo) {
            info = newInfo;
        }

        public static Collection<ProcessError> fromCollection(Collection<ProcessErrorStateInfo> in)
                {
            if (in == null) {
                return null;
            }

            List<ProcessError> out = new ArrayList<ProcessError>(in.size());
            for (ProcessErrorStateInfo info : in) {
                out.add(new ProcessError(info));
            }
            return out;
        }

        private boolean strEquals(String a, String b) {
            if ((a == null) && (b == null)) {
                return true;
            } else if ((a == null) || (b == null)) {
                return false;
            } else {
                return a.equals(b);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (other == null) return false;
            if (!(other instanceof ProcessError)) return false;
            ProcessError peOther = (ProcessError) other;

            return (info.condition == peOther.info.condition)
                    && strEquals(info.longMsg, peOther.info.longMsg)
                    && (info.pid == peOther.info.pid)
                    && strEquals(info.processName, peOther.info.processName)
                    && strEquals(info.shortMsg, peOther.info.shortMsg)
                    && strEquals(info.stackTrace, peOther.info.stackTrace)
                    && strEquals(info.tag, peOther.info.tag)
                    && (info.uid == peOther.info.uid);
        }

        private int hash(Object obj) {
            if (obj == null) {
                return 13;
            } else {
                return obj.hashCode();
            }
        }

        @Override
        public int hashCode() {
            int code = 17;
            code += info.condition;
            code *= hash(info.longMsg);
            code += info.pid;
            code *= hash(info.processName);
            code *= hash(info.shortMsg);
            code *= hash(info.stackTrace);
            code *= hash(info.tag);
            code += info.uid;
            return code;
        }
    }
}
