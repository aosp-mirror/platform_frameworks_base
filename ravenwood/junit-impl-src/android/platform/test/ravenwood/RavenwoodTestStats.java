/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.platform.test.ravenwood;

import android.util.Log;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Collect test result stats and write them into a CSV file containing the test results.
 *
 * The output file is created as `/tmp/Ravenwood-stats_[TEST-MODULE=NAME]_[TIMESTAMP].csv`.
 * A symlink to the latest result will be created as
 * `/tmp/Ravenwood-stats_[TEST-MODULE=NAME]_latest.csv`.
 */
public class RavenwoodTestStats {
    private static final String TAG = com.android.ravenwood.common.RavenwoodCommonUtils.TAG;
    private static final String HEADER = "Module,Class,OuterClass,Passed,Failed,Skipped";

    private static RavenwoodTestStats sInstance;

    /**
     * @return a singleton instance.
     */
    public static RavenwoodTestStats getInstance() {
        if (sInstance == null) {
            sInstance = new RavenwoodTestStats();
        }
        return sInstance;
    }

    /**
     * Represents a test result.
     */
    public enum Result {
        Passed,
        Failed,
        Skipped,
    }

    private final File mOutputFile;
    private final PrintWriter mOutputWriter;
    private final String mTestModuleName;

    public final Map<String, Map<String, Result>> mStats = new LinkedHashMap<>();

    /** Ctor */
    public RavenwoodTestStats() {
        mTestModuleName = guessTestModuleName();

        var basename = "Ravenwood-stats_" + mTestModuleName + "_";

        // Get the current time
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

        var tmpdir = System.getProperty("java.io.tmpdir");
        mOutputFile = new File(tmpdir, basename + now.format(fmt) + ".csv");

        try {
            mOutputWriter = new PrintWriter(mOutputFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create logfile. File=" + mOutputFile, e);
        }

        // Create the "latest" symlink.
        Path symlink = Paths.get(tmpdir, basename + "latest.csv");
        try {
            Files.deleteIfExists(symlink);
            Files.createSymbolicLink(symlink, Paths.get(mOutputFile.getName()));

        } catch (IOException e) {
            throw new RuntimeException("Failed to create logfile. File=" + mOutputFile, e);
        }

        Log.i(TAG, "Test result stats file: " + mOutputFile);

        // Print the header.
        mOutputWriter.println(HEADER);
        mOutputWriter.flush();
    }

    private String guessTestModuleName() {
        // Assume the current directory name is the test module name.
        File cwd;
        try {
            cwd = new File(".").getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException("Failed to get the current directory", e);
        }
        return cwd.getName();
    }

    private void addResult(String className, String methodName,
            Result result) {
        mStats.compute(className, (className_, value) -> {
            if (value == null) {
                value = new LinkedHashMap<>();
            }
            // If the result is already set, don't overwrite it.
            if (!value.containsKey(methodName)) {
                value.put(methodName, result);
            }
            return value;
        });
    }

    /**
     * Call it when a test method is finished.
     */
    private void onTestFinished(String className, String testName, Result result) {
        addResult(className, testName, result);
    }

    /**
     * Dump all the results and clear it.
     */
    private void dumpAllAndClear() {
        for (var entry : mStats.entrySet()) {
            int passed = 0;
            int skipped = 0;
            int failed = 0;
            var className = entry.getKey();

            for (var e : entry.getValue().values()) {
                switch (e) {
                    case Passed:
                        passed++;
                        break;
                    case Skipped:
                        skipped++;
                        break;
                    case Failed:
                        failed++;
                        break;
                }
            }

            mOutputWriter.printf("%s,%s,%s,%d,%d,%d\n",
                    mTestModuleName, className, getOuterClassName(className),
                    passed, failed, skipped);
        }
        mOutputWriter.flush();
        mStats.clear();
    }

    private static String getOuterClassName(String className) {
        // Just delete the '$', because I'm not sure if the className we get here is actaully a
        // valid class name that does exist. (it might have a parameter name, etc?)
        int p = className.indexOf('$');
        if (p < 0) {
            return className;
        }
        return className.substring(0, p);
    }

    public void attachToRunNotifier(RunNotifier notifier) {
        notifier.addListener(mRunListener);
    }

    private final RunListener mRunListener = new RunListener() {
        @Override
        public void testSuiteStarted(Description description) {
            Log.d(TAG, "testSuiteStarted: " + description);
        }

        @Override
        public void testSuiteFinished(Description description) {
            Log.d(TAG, "testSuiteFinished: " + description);
        }

        @Override
        public void testRunStarted(Description description) {
            Log.d(TAG, "testRunStarted: " + description);
        }

        @Override
        public void testRunFinished(org.junit.runner.Result result) {
            Log.d(TAG, "testRunFinished: " + result);

            dumpAllAndClear();
        }

        @Override
        public void testStarted(Description description) {
            Log.d(TAG, "  testStarted: " + description);
        }

        @Override
        public void testFinished(Description description) {
            Log.d(TAG, "  testFinished: " + description);

            // Send "Passed", but if there's already another result sent for this, this won't
            // override it.
            onTestFinished(description.getClassName(), description.getMethodName(), Result.Passed);
        }

        @Override
        public void testFailure(Failure failure) {
            Log.d(TAG, "    testFailure: " + failure);

            var description = failure.getDescription();
            onTestFinished(description.getClassName(), description.getMethodName(), Result.Failed);
        }

        @Override
        public void testAssumptionFailure(Failure failure) {
            Log.d(TAG, "    testAssumptionFailure: " + failure);
            var description = failure.getDescription();
            onTestFinished(description.getClassName(), description.getMethodName(), Result.Skipped);
        }

        @Override
        public void testIgnored(Description description) {
            Log.d(TAG, "    testIgnored: " + description);
            onTestFinished(description.getClassName(), description.getMethodName(), Result.Skipped);
        }
    };
}
