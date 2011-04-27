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

package com.android.dumprendertree;

import com.android.dumprendertree.TestShellActivity.DumpDataType;
import com.android.dumprendertree.forwarder.AdbUtils;
import com.android.dumprendertree.forwarder.ForwardService;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Vector;

// TestRecorder creates four files ...
// - passing tests
// - failing tests
// - tests for which results are ignored
// - tests with no text results available
// TestRecorder does not have the ability to clear the results.
class MyTestRecorder {
    private BufferedOutputStream mBufferedOutputPassedStream;
    private BufferedOutputStream mBufferedOutputFailedStream;
    private BufferedOutputStream mBufferedOutputIgnoreResultStream;
    private BufferedOutputStream mBufferedOutputNoResultStream;

    public void passed(String layout_file) {
        try {
            mBufferedOutputPassedStream.write(layout_file.getBytes());
            mBufferedOutputPassedStream.write('\n');
            mBufferedOutputPassedStream.flush();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void failed(String layout_file) {
        try {
            mBufferedOutputFailedStream.write(layout_file.getBytes());
            mBufferedOutputFailedStream.write('\n');
            mBufferedOutputFailedStream.flush();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void ignoreResult(String layout_file) {
        try {
            mBufferedOutputIgnoreResultStream.write(layout_file.getBytes());
            mBufferedOutputIgnoreResultStream.write('\n');
            mBufferedOutputIgnoreResultStream.flush();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public void noResult(String layout_file) {
        try {
            mBufferedOutputNoResultStream.write(layout_file.getBytes());
            mBufferedOutputNoResultStream.write('\n');
            mBufferedOutputNoResultStream.flush();
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    public MyTestRecorder(boolean resume) {
        try {
            File externalDir = Environment.getExternalStorageDirectory();
            File resultsPassedFile = new File(externalDir, "layout_tests_passed.txt");
            File resultsFailedFile = new File(externalDir, "layout_tests_failed.txt");
            File resultsIgnoreResultFile = new File(externalDir, "layout_tests_ignored.txt");
            File noExpectedResultFile = new File(externalDir, "layout_tests_nontext.txt");

            mBufferedOutputPassedStream =
                new BufferedOutputStream(new FileOutputStream(resultsPassedFile, resume));
            mBufferedOutputFailedStream =
                new BufferedOutputStream(new FileOutputStream(resultsFailedFile, resume));
            mBufferedOutputIgnoreResultStream =
                new BufferedOutputStream(new FileOutputStream(resultsIgnoreResultFile, resume));
            mBufferedOutputNoResultStream =
                new BufferedOutputStream(new FileOutputStream(noExpectedResultFile, resume));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void close() {
        try {
            mBufferedOutputPassedStream.close();
            mBufferedOutputFailedStream.close();
            mBufferedOutputIgnoreResultStream.close();
            mBufferedOutputNoResultStream.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}


public class LayoutTestsAutoTest extends ActivityInstrumentationTestCase2<TestShellActivity> {

    private static final String LOGTAG = "LayoutTests";
    static final int DEFAULT_TIMEOUT_IN_MILLIS = 5000;

    static final String EXTERNAL_DIR = Environment.getExternalStorageDirectory().toString();
    static final String LAYOUT_TESTS_ROOT = EXTERNAL_DIR + "/webkit/layout_tests/";
    static final String LAYOUT_TESTS_RESULT_DIR = EXTERNAL_DIR + "/webkit/layout_tests_results/";
    static final String ANDROID_EXPECTED_RESULT_DIR = EXTERNAL_DIR + "/webkit/expected_results/";
    static final String LAYOUT_TESTS_LIST_FILE = EXTERNAL_DIR + "/webkit/layout_tests_list.txt";
    static final String TEST_STATUS_FILE = EXTERNAL_DIR + "/webkit/running_test.txt";
    static final String LAYOUT_TESTS_RESULTS_REFERENCE_FILES[] = {
          "results/layout_tests_passed.txt",
          "results/layout_tests_failed.txt",
          "results/layout_tests_nontext.txt",
          "results/layout_tests_crashed.txt",
          "run_layout_tests.py"
    };

    static final String LAYOUT_RESULTS_FAILED_RESULT_FILE = "results/layout_tests_failed.txt";
    static final String LAYOUT_RESULTS_NONTEXT_RESULT_FILE = "results/layout_tests_nontext.txt";
    static final String LAYOUT_RESULTS_CRASHED_RESULT_FILE = "results/layout_tests_crashed.txt";
    static final String LAYOUT_TESTS_RUNNER = "run_layout_tests.py";

    private MyTestRecorder mResultRecorder;
    private Vector<String> mTestList;
    // Whether we should ignore the result for the corresponding test. Ordered same as mTestList.
    private Vector<Boolean> mTestListIgnoreResult;
    private boolean mRebaselineResults;
    // The JavaScript engine currently in use. This determines which set of Android-specific
    // expected test results we use.
    private String mJsEngine;
    private String mTestPathPrefix;
    private boolean mFinished;
    private int mTestCount;
    private int mResumeIndex;

    public LayoutTestsAutoTest() {
      super(TestShellActivity.class);
    }

    private void getTestList() {
        // Read test list.
        try {
            BufferedReader inReader = new BufferedReader(new FileReader(LAYOUT_TESTS_LIST_FILE));
            String line = inReader.readLine();
            while (line != null) {
                if (line.startsWith(mTestPathPrefix)) {
                    String[] components = line.split(" ");
                    mTestList.add(components[0]);
                    mTestListIgnoreResult.add(components.length > 1 && components[1].equals("IGNORE_RESULT"));
                }
                line = inReader.readLine();
            }
            inReader.close();
            Log.v(LOGTAG, "Test list has " + mTestList.size() + " test(s).");
        } catch (Exception e) {
            Log.e(LOGTAG, "Error while reading test list : " + e.getMessage());
        }
        mTestCount = mTestList.size();
    }

    private void resumeTestList() {
        // read out the test name it stoped last time.
        try {
            String line = FsUtils.readTestStatus(TEST_STATUS_FILE);
            for (int i = 0; i < mTestList.size(); i++) {
                if (mTestList.elementAt(i).equals(line)) {
                    mTestList = new Vector<String>(mTestList.subList(i+1, mTestList.size()));
                    mTestListIgnoreResult = new Vector<Boolean>(mTestListIgnoreResult.subList(i+1, mTestListIgnoreResult.size()));
                    mResumeIndex = i + 1;
                    break;
                }
            }
        } catch (Exception e) {
            Log.e(LOGTAG, "Error reading " + TEST_STATUS_FILE);
        }
    }

    private void clearTestStatus() {
        // Delete TEST_STATUS_FILE
        try {
            File f = new File(TEST_STATUS_FILE);
            if (f.delete())
                Log.v(LOGTAG, "Deleted " + TEST_STATUS_FILE);
            else
                Log.e(LOGTAG, "Fail to delete " + TEST_STATUS_FILE);
        } catch (Exception e) {
            Log.e(LOGTAG, "Fail to delete " + TEST_STATUS_FILE + " : " + e.getMessage());
        }
    }

    private String getResultFile(String test) {
        String shortName = test.substring(0, test.lastIndexOf('.'));
        // Write actual results to result directory.
        return shortName.replaceFirst(LAYOUT_TESTS_ROOT, LAYOUT_TESTS_RESULT_DIR) + "-result.txt";
    }

    // Gets the file which contains WebKit's expected results for this test.
    private String getExpectedResultFile(String test) {
        // The generic result is at <path>/<name>-expected.txt
        // First try the Android-specific result at
        // platform/android-<js-engine>/<path>/<name>-expected.txt
        // then
        // platform/android/<path>/<name>-expected.txt
        int pos = test.lastIndexOf('.');
        if (pos == -1)
            return null;
        String genericExpectedResult = test.substring(0, pos) + "-expected.txt";
        String androidExpectedResultsDir = "platform/android-" + mJsEngine + "/";
        String androidExpectedResult = genericExpectedResult.replaceFirst(LAYOUT_TESTS_ROOT,
                LAYOUT_TESTS_ROOT + androidExpectedResultsDir);
        File f = new File(androidExpectedResult);
        if (f.exists())
            return androidExpectedResult;
        androidExpectedResultsDir = "platform/android/";
        androidExpectedResult = genericExpectedResult.replaceFirst(LAYOUT_TESTS_ROOT,
                LAYOUT_TESTS_ROOT + androidExpectedResultsDir);
        f = new File(androidExpectedResult);
        return f.exists() ? androidExpectedResult : genericExpectedResult;
    }

    // Gets the file which contains the actual results of running the test on
    // Android, generated by a previous run which set a new baseline.
    private String getAndroidExpectedResultFile(String expectedResultFile) {
        return expectedResultFile.replaceFirst(LAYOUT_TESTS_ROOT, ANDROID_EXPECTED_RESULT_DIR);
    }

    // Wrap up
    private void failedCase(String file) {
        Log.w("Layout test: ", file + " failed");
        mResultRecorder.failed(file);
    }

    private void passedCase(String file) {
        Log.v("Layout test:", file + " passed");
        mResultRecorder.passed(file);
    }

    private void ignoreResultCase(String file) {
        Log.v("Layout test:", file + " ignore result");
        mResultRecorder.ignoreResult(file);
    }

    private void noResultCase(String file) {
        Log.v("Layout test:", file + " no expected result");
        mResultRecorder.noResult(file);
    }

    private void processResult(String testFile, String actualResultFile, String expectedResultFile, boolean ignoreResult) {
        Log.v(LOGTAG, "  Processing result: " + testFile);

        if (ignoreResult) {
            ignoreResultCase(testFile);
            return;
        }

        File actual = new File(actualResultFile);
        File expected = new File(expectedResultFile);
        if (actual.exists() && expected.exists()) {
            try {
                if (FsUtils.diffIgnoreSpaces(actualResultFile, expectedResultFile)) {
                    passedCase(testFile);
                } else {
                    failedCase(testFile);
                }
            } catch (FileNotFoundException ex) {
                Log.e(LOGTAG, "File not found : " + ex.getMessage());
            } catch (IOException ex) {
                Log.e(LOGTAG, "IO Error : " + ex.getMessage());
            }
            return;
        }

        if (!expected.exists()) {
            noResultCase(testFile);
        }
    }

    private void runTestAndWaitUntilDone(TestShellActivity activity, String test, int timeout, boolean ignoreResult, int testNumber) {
        activity.setCallback(new TestShellCallback() {
            public void finished() {
                synchronized (LayoutTestsAutoTest.this) {
                    mFinished = true;
                    LayoutTestsAutoTest.this.notifyAll();
                }
            }

            public void timedOut(String url) {
                Log.v(LOGTAG, "layout timeout: " + url);
            }
        });

        String resultFile = getResultFile(test);
        if (resultFile == null) {
            // Simply ignore this test.
            return;
        }
        if (mRebaselineResults) {
            String expectedResultFile = getExpectedResultFile(test);
            File f = new File(expectedResultFile);
            if (f.exists()) {
                return;  // don't run test and don't overwrite default tests.
            }

            resultFile = getAndroidExpectedResultFile(expectedResultFile);
        }

        mFinished = false;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, TestShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, FsUtils.getTestUrl(test));
        intent.putExtra(TestShellActivity.RESULT_FILE, resultFile);
        intent.putExtra(TestShellActivity.TIMEOUT_IN_MILLIS, timeout);
        intent.putExtra(TestShellActivity.TOTAL_TEST_COUNT, mTestCount);
        intent.putExtra(TestShellActivity.CURRENT_TEST_NUMBER, testNumber);
        intent.putExtra(TestShellActivity.STOP_ON_REF_ERROR, true);
        activity.startActivity(intent);

        // Wait until done.
        synchronized (this) {
            while(!mFinished){
                try {
                    this.wait();
                } catch (InterruptedException e) { }
            }
        }

        if (!mRebaselineResults) {
            String expectedResultFile = getExpectedResultFile(test);
            File f = new File(expectedResultFile);
            if (!f.exists()) {
                expectedResultFile = getAndroidExpectedResultFile(expectedResultFile);
            }

            processResult(test, resultFile, expectedResultFile, ignoreResult);
        }
    }

    // Invokes running of layout tests
    // and waits till it has finished running.
    public void executeLayoutTests(boolean resume) {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();
        // A convenient method to be called by another activity.

        if (runner.mTestPath == null) {
            Log.e(LOGTAG, "No test specified");
            return;
        }

        this.mTestList = new Vector<String>();
        this.mTestListIgnoreResult = new Vector<Boolean>();

        // Read settings
        mTestPathPrefix = (new File(LAYOUT_TESTS_ROOT + runner.mTestPath)).getAbsolutePath();
        mRebaselineResults = runner.mRebaseline;
        // V8 is the default JavaScript engine.
        mJsEngine = runner.mJsEngine == null ? "v8" : runner.mJsEngine;

        int timeout = runner.mTimeoutInMillis;
        if (timeout <= 0) {
            timeout = DEFAULT_TIMEOUT_IN_MILLIS;
        }

        this.mResultRecorder = new MyTestRecorder(resume);

        if (!resume)
            clearTestStatus();

        getTestList();
        if (resume)
            resumeTestList();

        TestShellActivity activity = getActivity();
        activity.setDefaultDumpDataType(DumpDataType.EXT_REPR);

        // Run tests.
        for (int i = 0; i < mTestList.size(); i++) {
            String s = mTestList.elementAt(i);
            boolean ignoreResult = mTestListIgnoreResult.elementAt(i);
            FsUtils.updateTestStatus(TEST_STATUS_FILE, s);
            // Run tests
            // i is 0 based, but test count is 1 based so add 1 to i here.
            runTestAndWaitUntilDone(activity, s, runner.mTimeoutInMillis, ignoreResult,
                    i + 1 + mResumeIndex);
        }

        FsUtils.updateTestStatus(TEST_STATUS_FILE, "#DONE");
        ForwardService.getForwardService().stopForwardService();
        activity.finish();
    }

    private String getTestPath() {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();

        String test_path = LAYOUT_TESTS_ROOT;
        if (runner.mTestPath != null) {
            test_path += runner.mTestPath;
        }
        test_path = new File(test_path).getAbsolutePath();
        Log.v("LayoutTestsAutoTest", " Test path : " + test_path);

        return test_path;
    }

    public void generateTestList() {
        try {
            File tests_list = new File(LAYOUT_TESTS_LIST_FILE);
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tests_list, false));
            FsUtils.writeLayoutTestListRecursively(bos, getTestPath(), false); // Don't ignore results
            bos.flush();
            bos.close();
       } catch (Exception e) {
           Log.e(LOGTAG, "Error when creating test list: " + e.getMessage());
       }
    }

    // Running all the layout tests at once sometimes
    // causes the dumprendertree to run out of memory.
    // So, additional tests are added to run the tests
    // in chunks.
    public void startLayoutTests() {
        try {
            File tests_list = new File(LAYOUT_TESTS_LIST_FILE);
            if (!tests_list.exists())
              generateTestList();
        } catch (Exception e) {
            e.printStackTrace();
        }

        executeLayoutTests(false);
    }

    public void resumeLayoutTests() {
        executeLayoutTests(true);
    }

    public void copyResultsAndRunnerAssetsToCache() {
        try {
            Context targetContext = getInstrumentation().getTargetContext();
            File cacheDir = targetContext.getCacheDir();

            for( int i=0; i< LAYOUT_TESTS_RESULTS_REFERENCE_FILES.length; i++) {
                InputStream in = targetContext.getAssets().open(
                        LAYOUT_TESTS_RESULTS_REFERENCE_FILES[i]);
                OutputStream out = new FileOutputStream(new File(cacheDir,
                        LAYOUT_TESTS_RESULTS_REFERENCE_FILES[i]));

                byte[] buf = new byte[2048];
                int len;

                while ((len = in.read(buf)) >= 0 ) {
                    out.write(buf, 0, len);
                }
                out.close();
                in.close();
            }
        }catch (IOException e) {
          e.printStackTrace();
        }

    }

}
