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
import android.app.IActivityManager;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.UserHandle;
import android.support.test.rule.logging.AtraceLogger;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
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

    // optional parameter: comma separated list of required account types before proceeding
    // with the app launch
    private static final String KEY_REQUIRED_ACCOUNTS = "required_accounts";
    private static final String KEY_APPS = "apps";
    private static final String KEY_TRIAL_LAUNCH = "trial_launch";
    private static final String KEY_LAUNCH_ITERATIONS = "launch_iterations";
    private static final String KEY_LAUNCH_ORDER = "launch_order";
    private static final String KEY_DROP_CACHE = "drop_cache";
    private static final String KEY_SIMPLEPERF_CMD = "simpleperf_cmd";
    private static final String KEY_SIMPLEPERF_APP = "simpleperf_app";
    private static final String KEY_TRACE_ITERATIONS = "trace_iterations";
    private static final String KEY_LAUNCH_DIRECTORY = "launch_directory";
    private static final String KEY_TRACE_DIRECTORY = "trace_directory";
    private static final String KEY_TRACE_CATEGORY = "trace_categories";
    private static final String KEY_TRACE_BUFFERSIZE = "trace_bufferSize";
    private static final String KEY_TRACE_DUMPINTERVAL = "tracedump_interval";
    private static final String KEY_COMPILER_FILTERS = "compiler_filters";

    private static final String SIMPLEPERF_APP_CMD =
            "simpleperf --log fatal stat --csv -e cpu-cycles,major-faults --app %s & %s";
    private static final String WEARABLE_ACTION_GOOGLE =
            "com.google.android.wearable.action.GOOGLE";
    private static final int INITIAL_LAUNCH_IDLE_TIMEOUT = 5000; // 5s to allow app to idle
    private static final int POST_LAUNCH_IDLE_TIMEOUT = 750; // 750ms idle for non initial launches
    private static final int BETWEEN_LAUNCH_SLEEP_TIMEOUT = 5000; // 5s between launching apps
    private static final String LAUNCH_SUB_DIRECTORY = "launch_logs";
    private static final String LAUNCH_FILE = "applaunch.txt";
    private static final String TRACE_SUB_DIRECTORY = "atrace_logs";
    private static final String DEFAULT_TRACE_CATEGORIES =
            "sched,freq,gfx,view,dalvik,webview,input,wm,disk,am,wm";
    private static final String DEFAULT_TRACE_BUFFER_SIZE = "20000";
    private static final String DEFAULT_TRACE_DUMP_INTERVAL = "10";
    private static final String TRIAL_LAUNCH = "TRIAL_LAUNCH";
    private static final String DELIMITER = ",";
    private static final String DROP_CACHE_SCRIPT = "/data/local/tmp/dropCache.sh";
    private static final String APP_LAUNCH_CMD = "am start -W -n";
    private static final String SUCCESS_MESSAGE = "Status: ok";
    private static final String WARNING_MESSAGE = "Warning: Activity not started";
    private static final String COMPILE_SUCCESS = "Success";
    private static final String THIS_TIME = "ThisTime:";
    private static final String LAUNCH_ITERATION = "LAUNCH_ITERATION - %d";
    private static final String TRACE_ITERATION = "TRACE_ITERATION-%d";
    private static final String LAUNCH_ITERATION_PREFIX = "LAUNCH_ITERATION";
    private static final String TRACE_ITERATION_PREFIX = "TRACE_ITERATION";
    private static final String LAUNCH_ORDER_CYCLIC = "cyclic";
    private static final String LAUNCH_ORDER_SEQUENTIAL = "sequential";
    private static final String COMPILE_CMD = "cmd package compile -f -m %s %s";
    private static final String SPEED_PROFILE_FILTER = "speed-profile";
    private static final String VERIFY_FILTER = "verify";
    private static final String LAUNCH_SCRIPT_NAME = "appLaunch";
    private static final String WEARABLE_HOME_PACKAGE = "com.google.android.wearable.app";

    private Map<String, Intent> mNameToIntent;
    private List<LaunchOrder> mLaunchOrderList = new ArrayList<LaunchOrder>();
    private Map<String, String> mNameToResultKey;
    private Map<String, Map<String, List<AppLaunchResult>>> mNameToLaunchTime;
    private IActivityManager mAm;
    private String mSimplePerfCmd = null;
    private String mLaunchOrder = null;
    private boolean mDropCache = false;
    private int mLaunchIterations = 10;
    private int mTraceLaunchCount = 0;
    private String mTraceDirectoryStr = null;
    private Bundle mResult = new Bundle();
    private Set<String> mRequiredAccounts;
    private boolean mTrialLaunch = false;
    private BufferedWriter mBufferedWriter = null;
    private boolean mSimplePerfAppOnly = false;
    private String[] mCompilerFilters = null;

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

    private void addLaunchResult(LaunchOrder launch, AppLaunchResult result) {
        mNameToLaunchTime.get(launch.getApp()).get(launch.getCompilerFilter()).add(result);
    }

    private boolean hasFailureOnFirstLaunch(LaunchOrder launch) {
        List<AppLaunchResult> results =
            mNameToLaunchTime.get(launch.getApp()).get(launch.getCompilerFilter());
        return (results.size() > 0) && (results.get(0).mLaunchTime < 0);
    }

    public void testMeasureStartUpTime() throws RemoteException, NameNotFoundException,
            IOException, InterruptedException {
        InstrumentationTestRunner instrumentation =
                (InstrumentationTestRunner)getInstrumentation();
        Bundle args = instrumentation.getArguments();
        mAm = ActivityManager.getService();
        String launchDirectory = args.getString(KEY_LAUNCH_DIRECTORY);

        createMappings();
        parseArgs(args);
        checkAccountSignIn();

        // Root directory for applaunch file to log the app launch output
        // Will be useful in case of simpleperf command is used
        File launchRootDir = null;
        if (null != launchDirectory && !launchDirectory.isEmpty()) {
            launchRootDir = new File(launchDirectory);
            if (!launchRootDir.exists() && !launchRootDir.mkdirs()) {
                throw new IOException("Unable to create the destination directory");
            }
        }

        try {
            File launchSubDir = new File(launchRootDir, LAUNCH_SUB_DIRECTORY);

            if (!launchSubDir.exists() && !launchSubDir.mkdirs()) {
                throw new IOException("Unable to create the lauch file sub directory");
            }
            File file = new File(launchSubDir, LAUNCH_FILE);
            FileOutputStream outputStream = new FileOutputStream(file);
            mBufferedWriter = new BufferedWriter(new OutputStreamWriter(
                    outputStream));

            // Root directory for trace file during the launches
            File rootTrace = null;
            File rootTraceSubDir = null;
            int traceBufferSize = 0;
            int traceDumpInterval = 0;
            Set<String> traceCategoriesSet = null;
            if (null != mTraceDirectoryStr && !mTraceDirectoryStr.isEmpty()) {
                rootTrace = new File(mTraceDirectoryStr);
                if (!rootTrace.exists() && !rootTrace.mkdirs()) {
                    throw new IOException("Unable to create the trace directory");
                }
                rootTraceSubDir = new File(rootTrace, TRACE_SUB_DIRECTORY);
                if (!rootTraceSubDir.exists() && !rootTraceSubDir.mkdirs()) {
                    throw new IOException("Unable to create the trace sub directory");
                }
                assertNotNull("Trace iteration parameter is mandatory",
                        args.getString(KEY_TRACE_ITERATIONS));
                mTraceLaunchCount = Integer.parseInt(args.getString(KEY_TRACE_ITERATIONS));
                String traceCategoriesStr = args
                        .getString(KEY_TRACE_CATEGORY, DEFAULT_TRACE_CATEGORIES);
                traceBufferSize = Integer.parseInt(args.getString(KEY_TRACE_BUFFERSIZE,
                        DEFAULT_TRACE_BUFFER_SIZE));
                traceDumpInterval = Integer.parseInt(args.getString(KEY_TRACE_DUMPINTERVAL,
                        DEFAULT_TRACE_DUMP_INTERVAL));
                traceCategoriesSet = new HashSet<String>();
                if (!traceCategoriesStr.isEmpty()) {
                    String[] traceCategoriesSplit = traceCategoriesStr.split(DELIMITER);
                    for (int i = 0; i < traceCategoriesSplit.length; i++) {
                        traceCategoriesSet.add(traceCategoriesSplit[i]);
                    }
                }
            }

            // Get the app launch order based on launch order, trial launch,
            // launch iterations and trace iterations
            setLaunchOrder();

            for (LaunchOrder launch : mLaunchOrderList) {
                if (mNameToIntent.get(launch.getApp()) == null) {
                    continue;
                }
                dropCache();
                String appPkgName = mNameToIntent.get(launch.getApp())
                        .getComponent().getPackageName();
                Log.v(TAG, String.format("\nApp name: %s", launch.getApp()));
                Log.v(TAG, String.format("Adding app package name: %s", appPkgName));
                // App launch times for trial launch will not be used for final
                // launch time calculations.
                if (launch.getLaunchReason().equals(TRIAL_LAUNCH)) {
                    // In the "applaunch.txt" file, trail launches is referenced using
                    // "TRIAL_LAUNCH"
                    Log.v(TAG, "Trial Launch");
                    if (SPEED_PROFILE_FILTER.equals(launch.getCompilerFilter())) {
                        assertTrue(String.format("Not able to compile the app : %s", appPkgName),
                              compileApp(VERIFY_FILTER, appPkgName));
                    } else if (launch.getCompilerFilter() != null) {
                        assertTrue(String.format("Not able to compile the app : %s", appPkgName),
                              compileApp(launch.getCompilerFilter(), appPkgName));
                    }
                    // We only need to run a trial for the speed-profile filter, but we always
                    // run one for "applaunch.txt" consistency.
                    AppLaunchResult launchResult = null;
                    if (appPkgName.contains(WEARABLE_HOME_PACKAGE)) {
                        Log.v(TAG, "Home package detected. Not killing app");
                        launchResult = startApp(launch.getApp(), false, launch.getLaunchReason());
                    } else {
                        Log.v(TAG, "Will kill app before launch");
                        launchResult = startApp(launch.getApp(), true, launch.getLaunchReason());
                    }
                    if (launchResult.mLaunchTime < 0) {
                        addLaunchResult(launch, new AppLaunchResult());
                        // simply pass the app if launch isn't successful
                        // error should have already been logged by startApp
                        continue;
                    }
                    sleep(INITIAL_LAUNCH_IDLE_TIMEOUT);
                    if (SPEED_PROFILE_FILTER.equals(launch.getCompilerFilter())) {
                        // Send SIGUSR1 to force dumping a profile.
                        String sendSignalCommand =
                            String.format("killall -s SIGUSR1 %s", appPkgName);
                        getInstrumentation().getUiAutomation().executeShellCommand(
                            sendSignalCommand);
                        assertTrue(String.format("Not able to compile the app : %s", appPkgName),
                              compileApp(launch.getCompilerFilter(), appPkgName));
                    }
                }

                // App launch times used for final calculation
                else if (launch.getLaunchReason().contains(LAUNCH_ITERATION_PREFIX)) {
                    Log.v(TAG, "Launch iteration prefix.");
                    AppLaunchResult launchResults = null;
                    if (hasFailureOnFirstLaunch(launch)) {
                        // skip if the app has failures while launched first
                        continue;
                    }
                    // In the "applaunch.txt" file app launches are referenced using
                    // "LAUNCH_ITERATION - ITERATION NUM"
                    if (appPkgName.contains(WEARABLE_HOME_PACKAGE)) {
                        Log.v(TAG, "Home package detected. Not killing app");
                        launchResults = startApp(launch.getApp(), false, launch.getLaunchReason());
                    } else {
                        Log.v(TAG, "Will kill app before launch");
                        launchResults = startApp(launch.getApp(), true, launch.getLaunchReason());
                    }
                    if (launchResults.mLaunchTime < 0) {
                        addLaunchResult(launch, new AppLaunchResult());
                        // if it fails once, skip the rest of the launches
                        continue;
                    } else {
                        addLaunchResult(launch, launchResults);
                    }
                    sleep(POST_LAUNCH_IDLE_TIMEOUT);
                }

                // App launch times for trace launch will not be used for final
                // launch time calculations.
                else if (launch.getLaunchReason().contains(TRACE_ITERATION_PREFIX)) {
                    Log.v(TAG, "Trace iteration prefix");
                    AtraceLogger atraceLogger = AtraceLogger
                            .getAtraceLoggerInstance(getInstrumentation());
                    // Start the trace
                    try {
                        atraceLogger.atraceStart(traceCategoriesSet, traceBufferSize,
                                traceDumpInterval, rootTraceSubDir,
                                String.format("%s-%s", launch.getApp(), launch.getLaunchReason()));
                        if (appPkgName.contains(WEARABLE_HOME_PACKAGE)) {
                            Log.v(TAG, "Home package detected. Not killing app");
                            startApp(launch.getApp(), false, launch.getLaunchReason());
                        } else {
                            Log.v(TAG, "Will kill app before launch");
                            startApp(launch.getApp(), true, launch.getLaunchReason());
                        }
                        sleep(POST_LAUNCH_IDLE_TIMEOUT);
                    } finally {
                        // Stop the trace
                        atraceLogger.atraceStop();
                    }
                }
                closeApp(launch.getApp(), true);
                sleep(BETWEEN_LAUNCH_SLEEP_TIMEOUT);
            }
        } finally {
            if (null != mBufferedWriter) {
                mBufferedWriter.close();
            }
        }

        for (String app : mNameToResultKey.keySet()) {
            for (String compilerFilter : mCompilerFilters) {
                StringBuilder launchTimes = new StringBuilder();
                StringBuilder cpuCycles = new StringBuilder();
                StringBuilder majorFaults = new StringBuilder();
                for (AppLaunchResult result : mNameToLaunchTime.get(app).get(compilerFilter)) {
                    launchTimes.append(result.mLaunchTime);
                    launchTimes.append(",");
                    if (mSimplePerfAppOnly) {
                        cpuCycles.append(result.mCpuCycles);
                        cpuCycles.append(",");
                        majorFaults.append(result.mMajorFaults);
                        majorFaults.append(",");
                    }
                }
                String filterName = (compilerFilter == null) ? "" : ("-" + compilerFilter);
                mResult.putString(mNameToResultKey.get(app) + filterName, launchTimes.toString());
                if (mSimplePerfAppOnly) {
                    mResult.putString(mNameToResultKey.get(app) + filterName + "-cpuCycles",
                        cpuCycles.toString());
                    mResult.putString(mNameToResultKey.get(app) + filterName + "-majorFaults",
                        majorFaults.toString());
                }
            }
        }
        instrumentation.sendStatus(0, mResult);
    }

    /**
     * Compile the app package using compilerFilter and return true or false
     * based on status of the compilation command.
     */
    private boolean compileApp(String compilerFilter, String appPkgName) throws IOException {
        try (ParcelFileDescriptor result = getInstrumentation().getUiAutomation().
                executeShellCommand(String.format(COMPILE_CMD, compilerFilter, appPkgName));
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(result.getFileDescriptor())))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                if (line.contains(COMPILE_SUCCESS)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * If launch order is "cyclic" then apps will be launched one after the
     * other for each iteration count.
     * If launch order is "sequential" then each app will be launched for given number
     * iterations at once before launching the other apps.
     */
    private void setLaunchOrder() {
        if (LAUNCH_ORDER_CYCLIC.equalsIgnoreCase(mLaunchOrder)) {
            for (String compilerFilter : mCompilerFilters) {
                if (mTrialLaunch) {
                    for (String app : mNameToResultKey.keySet()) {
                        mLaunchOrderList.add(new LaunchOrder(app, compilerFilter, TRIAL_LAUNCH));
                    }
                }
                for (int launchCount = 0; launchCount < mLaunchIterations; launchCount++) {
                    for (String app : mNameToResultKey.keySet()) {
                        mLaunchOrderList.add(new LaunchOrder(app, compilerFilter,
                                  String.format(LAUNCH_ITERATION, launchCount)));
                    }
                }
                if (mTraceDirectoryStr != null && !mTraceDirectoryStr.isEmpty()) {
                    for (int traceCount = 0; traceCount < mTraceLaunchCount; traceCount++) {
                        for (String app : mNameToResultKey.keySet()) {
                            mLaunchOrderList.add(new LaunchOrder(app, compilerFilter,
                                      String.format(TRACE_ITERATION, traceCount)));
                        }
                    }
                }
            }
        } else if (LAUNCH_ORDER_SEQUENTIAL.equalsIgnoreCase(mLaunchOrder)) {
            for (String compilerFilter : mCompilerFilters) {
                for (String app : mNameToResultKey.keySet()) {
                    if (mTrialLaunch) {
                        mLaunchOrderList.add(new LaunchOrder(app, compilerFilter, TRIAL_LAUNCH));
                    }
                    for (int launchCount = 0; launchCount < mLaunchIterations; launchCount++) {
                        mLaunchOrderList.add(new LaunchOrder(app, compilerFilter,
                                String.format(LAUNCH_ITERATION, launchCount)));
                    }
                    if (mTraceDirectoryStr != null && !mTraceDirectoryStr.isEmpty()) {
                        for (int traceCount = 0; traceCount < mTraceLaunchCount; traceCount++) {
                            mLaunchOrderList.add(new LaunchOrder(app, compilerFilter,
                                    String.format(TRACE_ITERATION, traceCount)));
                        }
                    }
                }
            }
        } else {
            assertTrue("Launch order is not valid parameter", false);
        }
    }

    private void dropCache() {
        if (mDropCache) {
            assertNotNull("Issue in dropping the cache",
                    getInstrumentation().getUiAutomation()
                            .executeShellCommand(DROP_CACHE_SCRIPT));
        }
    }

    private void parseArgs(Bundle args) {
        mNameToResultKey = new LinkedHashMap<String, String>();
        mNameToLaunchTime = new HashMap<>();
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
                Log.e(TAG, "The apps key is incorrectly formatted");
                fail();
            }

            mNameToResultKey.put(parts[0], parts[1]);
            mNameToLaunchTime.put(parts[0], null);
        }
        String requiredAccounts = args.getString(KEY_REQUIRED_ACCOUNTS);
        if (requiredAccounts != null) {
            mRequiredAccounts = new HashSet<String>();
            for (String accountType : requiredAccounts.split(",")) {
                mRequiredAccounts.add(accountType);
            }
        }

        String compilerFilterList = args.getString(KEY_COMPILER_FILTERS);
        if (compilerFilterList != null) {
            // If a compiler filter is passed, we make a trial launch to force compilation
            // of the apps.
            mTrialLaunch = true;
            mCompilerFilters = compilerFilterList.split("\\|");
        } else {
            // Just pass a null compiler filter to use the current state of the app.
            mCompilerFilters = new String[1];
        }

        // Pre-populate the results map to avoid null checks.
        for (String app : mNameToLaunchTime.keySet()) {
            HashMap<String, List<AppLaunchResult>> map = new HashMap<>();
            mNameToLaunchTime.put(app, map);
            for (String compilerFilter : mCompilerFilters) {
                map.put(compilerFilter, new ArrayList<>());
            }
        }

        mTraceDirectoryStr = args.getString(KEY_TRACE_DIRECTORY);
        mDropCache = Boolean.parseBoolean(args.getString(KEY_DROP_CACHE));
        mSimplePerfCmd = args.getString(KEY_SIMPLEPERF_CMD);
        mLaunchOrder = args.getString(KEY_LAUNCH_ORDER, LAUNCH_ORDER_CYCLIC);
        mSimplePerfAppOnly = Boolean.parseBoolean(args.getString(KEY_SIMPLEPERF_APP));
        mTrialLaunch = mTrialLaunch || Boolean.parseBoolean(args.getString(KEY_TRIAL_LAUNCH));

        if (mSimplePerfCmd != null && mSimplePerfAppOnly) {
            Log.w(TAG, String.format("Passing both %s and %s is not supported, ignoring %s",
                KEY_SIMPLEPERF_CMD, KEY_SIMPLEPERF_APP));
        }
    }

    private boolean hasLeanback(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    private void createMappings() {
        mNameToIntent = new LinkedHashMap<String, Intent>();

        PackageManager pm = getInstrumentation().getContext()
                .getPackageManager();
        Intent intentToResolve = new Intent(Intent.ACTION_MAIN);
        intentToResolve.addCategory(hasLeanback(getInstrumentation().getContext()) ?
                Intent.CATEGORY_LEANBACK_LAUNCHER :
                Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(intentToResolve, 0);
        resolveLoop(ris, intentToResolve, pm);
        // For Wear
        intentToResolve = new Intent(WEARABLE_ACTION_GOOGLE);
        ris = pm.queryIntentActivities(intentToResolve, 0);
        resolveLoop(ris, intentToResolve, pm);
    }

    private void resolveLoop(List<ResolveInfo> ris, Intent intentToResolve, PackageManager pm) {
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
                    // Support launching intent using package name or app name
                    mNameToIntent.put(ri.activityInfo.packageName, startIntent);
                    mNameToIntent.put(appName, startIntent);
                }
            }
        }
    }

    private AppLaunchResult startApp(String appName, boolean forceStopBeforeLaunch,
            String launchReason) throws NameNotFoundException, RemoteException {
        Log.i(TAG, "Starting " + appName);

        Intent startIntent = mNameToIntent.get(appName);
        if (startIntent == null) {
            Log.w(TAG, "App does not exist: " + appName);
            mResult.putString(mNameToResultKey.get(appName), "App does not exist");
            return new AppLaunchResult();
        }
        AppLaunchRunnable runnable = new AppLaunchRunnable(startIntent, forceStopBeforeLaunch,
                launchReason);
        Thread t = new Thread(runnable);
        t.start();
        try {
            t.join(JOIN_TIMEOUT);
        } catch (InterruptedException e) {
            // ignore
        }
        return runnable.getResult();
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

    private class LaunchOrder {
        private String mApp;
        private String mCompilerFilter;
        private String mLaunchReason;

        LaunchOrder(String app, String compilerFilter, String launchReason){
            mApp = app;
            mCompilerFilter = compilerFilter;
            mLaunchReason = launchReason;
        }

        public String getApp() {
            return mApp;
        }

        public void setApp(String app) {
            mApp = app;
        }

        public String getCompilerFilter() {
            return mCompilerFilter;
        }

        public String getLaunchReason() {
            return mLaunchReason;
        }

        public void setLaunchReason(String launchReason) {
            mLaunchReason = launchReason;
        }
    }

    private class AppLaunchResult {
        long mLaunchTime;
        long mCpuCycles;
        long mMajorFaults;

        AppLaunchResult() {
            mLaunchTime = -1L;
            mCpuCycles = -1L;
            mMajorFaults = -1L;
        }

        AppLaunchResult(String launchTime, String cpuCycles, String majorFaults) {
            try {
                mLaunchTime = Long.parseLong(launchTime, 10);
                mCpuCycles = Long.parseLong(cpuCycles, 10);
                mMajorFaults = Long.parseLong(majorFaults, 10);
            } catch (NumberFormatException e) {
                Log.e(TAG, "Error parsing result", e);
            }
        }
    }

    private class AppLaunchRunnable implements Runnable {
        private Intent mLaunchIntent;
        private AppLaunchResult mLaunchResult;
        private boolean mForceStopBeforeLaunch;
        private String mLaunchReason;

        public AppLaunchRunnable(Intent intent, boolean forceStopBeforeLaunch,
                String launchReason) {
            mLaunchIntent = intent;
            mForceStopBeforeLaunch = forceStopBeforeLaunch;
            mLaunchReason = launchReason;
            mLaunchResult = new AppLaunchResult();
        }

        public AppLaunchResult getResult() {
            return mLaunchResult;
        }

        public void run() {
            File launchFile = null;
            try {
                String packageName = mLaunchIntent.getComponent().getPackageName();
                String componentName = mLaunchIntent.getComponent().flattenToShortString();
                if (mForceStopBeforeLaunch) {
                    Log.v(TAG, "Stopping app before launch");
                    mAm.forceStopPackage(packageName, UserHandle.USER_CURRENT);
                } else {
                    Log.v(TAG, "Not killing app. Going to Home Screen.");
                    ParcelFileDescriptor goHome = getInstrumentation().getUiAutomation()
                        .executeShellCommand("input keyevent 3");
                }
                String launchCmd = String.format("%s %s", APP_LAUNCH_CMD, componentName);
                if (mSimplePerfAppOnly) {
                    try {
                        // executeShellCommand cannot handle shell specific actions, like '&'.
                        // Therefore, we create a file containing the command and make that
                        // the command to launch.
                        launchFile = File.createTempFile(LAUNCH_SCRIPT_NAME, ".sh");
                        launchFile.setExecutable(true);
                        try (FileOutputStream stream = new FileOutputStream(launchFile);
                             BufferedWriter writer =
                                new BufferedWriter(new OutputStreamWriter(stream))) {
                            String cmd = String.format(SIMPLEPERF_APP_CMD, packageName, launchCmd);
                            writer.write(cmd);
                        }
                        launchCmd = launchFile.getAbsolutePath();
                    } catch (IOException e) {
                        Log.w(TAG, "Error writing the launch command", e);
                        return;
                    }
                } else if (null != mSimplePerfCmd) {
                    launchCmd = String.format("%s %s", mSimplePerfCmd, launchCmd);
                }
                Log.v(TAG, "Final launch cmd:" + launchCmd);
                ParcelFileDescriptor parcelDesc = getInstrumentation().getUiAutomation()
                        .executeShellCommand(launchCmd);
                mLaunchResult = parseLaunchTimeAndWrite(parcelDesc, String.format
                        ("App Launch :%s %s", componentName, mLaunchReason));
            } catch (RemoteException e) {
                Log.w(TAG, "Error launching app", e);
            } finally {
                if (launchFile != null) {
                    launchFile.delete();
                }
            }
        }

        /**
         * Method to parse the launch time info and write the result to file
         *
         * @param parcelDesc
         * @return
         */
        private AppLaunchResult parseLaunchTimeAndWrite(ParcelFileDescriptor parcelDesc,
                String headerInfo) {
            String launchTime = "-1";
            String cpuCycles = "-1";
            String majorFaults = "-1";
            boolean launchSuccess = false;
            try {
                InputStream inputStream = new FileInputStream(parcelDesc.getFileDescriptor());
                /* SAMPLE OUTPUT :
                Starting: Intent { cmp=com.google.android.calculator/com.android.calculator2.Calculator }
                Status: ok
                Activity: com.google.android.calculator/com.android.calculator2.Calculator
                ThisTime: 357
                TotalTime: 357
                WaitTime: 377
                Complete*/
                /* WHEN NOT KILLING HOME :
                Starting: Intent { cmp=com.google.android.wearable.app/
                    com.google.android.clockwork.home.calendar.AgendaActivity }
                Warning: Activity not started, its current task has been brought to the front
                Status: ok
                Activity: com.google.android.wearable.app/
                    com.google.android.clockwork.home.calendar.AgendaActivity
                ThisTime: 209
                TotalTime: 209
                WaitTime: 285
                Complete*/
                /* WITH SIMPLEPERF :
                Performance counter statistics,
                6595722690,cpu-cycles,4.511040,GHz,(100%),
                0,major-faults,0.000,/sec,(100%),
                Total test time,1.462129,seconds,*/
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(
                        inputStream));
                String line = null;
                int lineCount = 1;
                int addLineForWarning = 0;
                mBufferedWriter.newLine();
                mBufferedWriter.write(headerInfo);
                mBufferedWriter.newLine();
                while ((line = bufferedReader.readLine()) != null) {
                    if (lineCount == 2 && line.contains(WARNING_MESSAGE)) {
                        addLineForWarning = 1;
                    }
                    if (lineCount == (2 + addLineForWarning) && line.contains(SUCCESS_MESSAGE)) {
                        launchSuccess = true;
                    }
                    // Parse TotalTime which is the launch time
                    if (launchSuccess && lineCount == (5 + addLineForWarning)) {
                        String launchSplit[] = line.split(":");
                        launchTime = launchSplit[1].trim();
                    }

                    if (mSimplePerfAppOnly) {
                        // Parse simpleperf output.
                        if (lineCount == (9 + addLineForWarning)) {
                            if (!line.contains("cpu-cycles")) {
                                Log.e(TAG, "Error in simpleperf output");
                            } else {
                                cpuCycles = line.split(",")[0].trim();
                            }
                        } else if (lineCount == (10 + addLineForWarning)) {
                            if (!line.contains("major-faults")) {
                                Log.e(TAG, "Error in simpleperf output");
                            } else {
                                majorFaults = line.split(",")[0].trim();
                            }
                        }
                    }
                    mBufferedWriter.write(line);
                    mBufferedWriter.newLine();
                    lineCount++;
                }
                mBufferedWriter.flush();
                inputStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Error writing the launch file", e);
            }
            return new AppLaunchResult(launchTime, cpuCycles, majorFaults);
        }

    }
}
