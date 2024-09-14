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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Collect test result stats and write them into a CSV file containing the test results.
 *
 * The output file is created as `/tmp/Ravenwood-stats_[TEST-MODULE=NAME]_[TIMESTAMP].csv`.
 * A symlink to the latest result will be created as
 * `/tmp/Ravenwood-stats_[TEST-MODULE=NAME]_latest.csv`.
 */
public class RavenwoodTestStats {
    private static final String TAG = "RavenwoodTestStats";
    private static final String HEADER = "Module,Class,ClassDesc,Passed,Failed,Skipped";

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

    public final Map<Description, Map<Description, Result>> mStats = new HashMap<>();

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
            throw new RuntimeException("Failed to crete logfile. File=" + mOutputFile, e);
        }

        // Crete the "latest" symlink.
        Path symlink = Paths.get(tmpdir, basename + "latest.csv");
        try {
            if (Files.exists(symlink)) {
                Files.delete(symlink);
            }
            Files.createSymbolicLink(symlink, Paths.get(mOutputFile.getName()));

        } catch (IOException e) {
            throw new RuntimeException("Failed to crete logfile. File=" + mOutputFile, e);
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

    private void addResult(Description classDescription, Description methodDescription,
            Result result) {
        mStats.compute(classDescription, (classDesc, value) -> {
            if (value == null) {
                value = new HashMap<>();
            }
            value.put(methodDescription, result);
            return value;
        });
    }

    /**
     * Call it when a test class is skipped.
     */
    public void onClassSkipped(Description classDescription) {
        addResult(classDescription, Description.EMPTY, Result.Skipped);
        onClassFinished(classDescription);
    }

    /**
     * Call it when a test method is finished.
     */
    public void onTestFinished(Description classDescription, Description testDescription,
            Result result) {
        addResult(classDescription, testDescription, result);
    }

    /**
     * Call it when a test class is finished.
     */
    public void onClassFinished(Description classDescription) {
        int passed = 0;
        int skipped = 0;
        int failed = 0;
        var stats = mStats.get(classDescription);
        if (stats == null) {
            return;
        }
        for (var e : stats.values()) {
            switch (e) {
                case Passed: passed++; break;
                case Skipped: skipped++; break;
                case Failed: failed++; break;
            }
        }

        var testClass = extractTestClass(classDescription);

        mOutputWriter.printf("%s,%s,%s,%d,%d,%d\n",
                mTestModuleName, (testClass == null ? "?" : testClass.getCanonicalName()),
                classDescription, passed, failed, skipped);
        mOutputWriter.flush();
    }

    /**
     * Try to extract the class from a description, which is needed because
     * ParameterizedAndroidJunit4's description doesn't contain a class.
     */
    private Class<?> extractTestClass(Description desc) {
        if (desc.getTestClass() != null) {
            return desc.getTestClass();
        }
        // Look into the children.
        for (var child : desc.getChildren()) {
            var fromChild = extractTestClass(child);
            if (fromChild != null) {
                return fromChild;
            }
        }
        return null;
    }
}
