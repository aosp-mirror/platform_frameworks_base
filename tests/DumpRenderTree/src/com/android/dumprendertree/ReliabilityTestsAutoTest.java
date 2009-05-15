package com.android.dumprendertree;

import com.android.dumprendertree.TestShellActivity.DumpDataType;

import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Vector;

public class ReliabilityTestsAutoTest extends ActivityInstrumentationTestCase2<TestShellActivity> {

    private static final String LOGTAG = "ReliabilityTests";
    private static final String TEST_LIST_FILE = "/sdcard/android/reliability_tests_list.txt";
    private static final String TEST_STATUS_FILE = "/sdcard/android/reliability_running_test.txt";
    private static final String TEST_TIMEOUT_FILE = "/sdcard/android/reliability_timeout_test.txt";
    static final String RELIABILITY_TEST_RUNNER_FILES[] = {
        "run_reliability_tests.py"
    };

    private boolean finished;
    private List<String> testList; 

    public ReliabilityTestsAutoTest() {
        super("com.android.dumprendertree", TestShellActivity.class);
    }

    private void getTestList() {
        // Read test list.
        testList = new Vector<String>();
        try {
            BufferedReader inReader = new BufferedReader(new FileReader(TEST_LIST_FILE));
            String line;
            while ((line = inReader.readLine()) != null) {
                testList.add(line);
            }
            inReader.close();
            Log.v(LOGTAG, "Test list has " + testList.size() + " test(s).");
        } catch (Exception e) {
            Log.e(LOGTAG, "Error while reading test list : " + e.getMessage());
        }
    }

    private void resumeTestList() {
        // read out the test name it stopped last time.
        try {
            BufferedReader inReader = new BufferedReader(new FileReader(TEST_STATUS_FILE));
            String line = inReader.readLine();
            for (int i = 0; i < testList.size(); i++) {
                if (testList.get(i).equals(line)) {
                    testList = new Vector<String>(testList.subList(i+1, testList.size()));
                    break;
                }
            }
            inReader.close();
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

    private void clearTestTimeout() {
        // Delete TEST_TIMEOUT_FILE
        try {
            File f = new File(TEST_TIMEOUT_FILE);
            if (f.delete())
                Log.v(LOGTAG, "Deleted " + TEST_TIMEOUT_FILE);
            else
                Log.e(LOGTAG, "Fail to delete " + TEST_TIMEOUT_FILE);
        } catch (Exception e) {
            Log.e(LOGTAG, "Fail to delete " + TEST_TIMEOUT_FILE + " : " + e.getMessage());
        }
    }

    private void updateTestStatus(String s) {
        // Write TEST_STATUS_FILE
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(TEST_STATUS_FILE));
            bos.write(s.getBytes());
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_STATUS_FILE);
        }
    }

    private void writeTimeoutFile(String s) {
        // Write TEST_TIMEOUT_FILE
        try {
            BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(TEST_TIMEOUT_FILE, true));
            bos.write(s.getBytes());
            bos.write('\n');
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_TIMEOUT_FILE);
        }
    }

    private void runReliabilityTest(boolean resume) {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();

        getTestList();
        if(!resume)
            clearTestStatus();
        else
            resumeTestList();

        TestShellActivity activity = getActivity();
        activity.setDefaultDumpDataType(DumpDataType.NO_OP);
        // Run tests.
        for (int i = 0; i < testList.size(); i++) {
            String s = testList.get(i);
            updateTestStatus(s);
            // Run tests
            runTestAndWaitUntilDone(activity, s, runner.mTimeoutInMillis);
        }

        updateTestStatus("#DONE");

        activity.finish();
    }

    private void runTestAndWaitUntilDone(TestShellActivity activity, String url, int timeout) {
        activity.setCallback(new TestShellCallback() {
            public void finished() {
                synchronized (ReliabilityTestsAutoTest.this) {
                    finished = true;
                    ReliabilityTestsAutoTest.this.notifyAll();
                }
            }

            public void timedOut(String url) {
                writeTimeoutFile(url);
            }
        });

        finished = false;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, TestShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, url);
        intent.putExtra(TestShellActivity.TIMEOUT_IN_MILLIS, timeout);
        activity.startActivity(intent);

        // Wait until done.
        synchronized (this) {
            while(!finished){
                try {
                    this.wait();
                } catch (InterruptedException e) { }
            }
        }
    }

    public void startReliabilityTests() {
        clearTestTimeout();
        runReliabilityTest(false);
    }

    public void resumeReliabilityTests() {
        runReliabilityTest(true);
    }

    public void copyRunnerAssetsToCache() {
        try {
            String out_dir = getActivity().getApplicationContext()
            .getCacheDir().getPath() + "/";

            for( int i=0; i< RELIABILITY_TEST_RUNNER_FILES.length; i++) {
                InputStream in = getActivity().getAssets().open(
                        RELIABILITY_TEST_RUNNER_FILES[i]);
                OutputStream out = new FileOutputStream(
                        out_dir + RELIABILITY_TEST_RUNNER_FILES[i]);

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
