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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This smoke test is designed to quickly sniff for any error conditions
 * encountered after initial startup.
 */
public class ProcessErrorsTest extends AndroidTestCase {
    
    private static final String TAG = "ProcessErrorsTest";

    private final Intent mHomeIntent;

    protected ActivityManager mActivityManager;
    protected PackageManager mPackageManager;

    public ProcessErrorsTest() {
        mHomeIntent = new Intent(Intent.ACTION_MAIN);
        mHomeIntent.addCategory(Intent.CATEGORY_HOME);
        mHomeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
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
     */
    static Intent intentForActivity(ResolveInfo app) {
        // build an Intent to launch the specified app
        final ComponentName component = new ComponentName(app.activityInfo.packageName,
                app.activityInfo.name);
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(component);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
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
        // tests should have caught and reported already).  Otherwise, test failures would cascade
        // from the initial broken app to many/all of the tests following that app's launch.
        final Collection<ProcessError> preErrProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());

        // launch app, and wait 7 seconds for it to start/settle
        final Intent intent = intentForActivity(app);
        getContext().startActivity(intent);
        try {
            Thread.sleep(appLaunchWait);
        } catch (InterruptedException e) {
            // ignore
        }

        // See if there are any errors
        final Collection<ProcessError> errProcs =
                ProcessError.fromCollection(mActivityManager.getProcessesInErrorState());
        // Take the difference between the error processes we see now, and the ones that were
        // present when we started
        if (errProcs != null && preErrProcs != null) {
            errProcs.removeAll(preErrProcs);
        }

        // Send the "home" intent and wait 2 seconds for us to get there
        getContext().startActivity(mHomeIntent);
        try {
            Thread.sleep(homeLaunchWait);
        } catch (InterruptedException e) {
            // ignore
        }

        return errProcs;
    }

    /**
     * A test that runs all Launcher-launchable activities and verifies that no ANRs or crashes
     * happened while doing so.
     * <p />
     * FIXME: Doesn't detect multiple crashing apps properly, since the crash dialog for the
     * FIXME: first app doesn't go away.
     */
    public void testRunAllActivities() throws Exception {
        final Set<ProcessError> errSet = new HashSet<ProcessError>();

        for (ResolveInfo app : getLauncherActivities(mPackageManager)) {
            final Collection<ProcessError> errProcs = runOneActivity(app);
            if (errProcs != null) {
                errSet.addAll(errProcs);
            }
        }

        if (!errSet.isEmpty()) {
            fail(String.format("Got %d errors: %s", errSet.size(),
                    reportWrappedListContents(errSet)));
        }
    }

    String reportWrappedListContents(Collection<ProcessError> errList) {
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
    private String reportListContents(Collection<ProcessErrorStateInfo> errList) {
        if (errList == null) return null;

        StringBuilder builder = new StringBuilder();

        Iterator<ProcessErrorStateInfo> iter = errList.iterator();
        while (iter.hasNext()) {
            ProcessErrorStateInfo entry = iter.next();

            String condition;
            switch (entry.condition) {
            case ActivityManager.ProcessErrorStateInfo.CRASHED:
                condition = "CRASHED";
                break;
            case ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING:
                condition = "ANR";
                break;
            default:
                condition = "<unknown>";
                break;
            }

            builder.append("Process error ").append(condition).append(" ");
            builder.append(" ").append(entry.shortMsg);
            builder.append(" detected in ").append(entry.processName).append(" ").append(entry.tag);
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
