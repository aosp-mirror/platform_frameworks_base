package com.android.dumprendertree;

import android.os.Handler;
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

public class ReliabilityTest extends ActivityInstrumentationTestCase2<ReliabilityTestActivity> {
    
    private static final String LOGTAG = "ReliabilityTest";
    private static final String PKG_NAME = "com.android.dumprendertree";
    private static final String TEST_LIST_FILE = "/sdcard/android/reliability_tests_list.txt";
    private static final String TEST_STATUS_FILE = "/sdcard/android/reliability_running_test.txt";
    private static final String TEST_TIMEOUT_FILE = "/sdcard/android/reliability_timeout_test.txt";
    private static final String TEST_DONE = "#DONE";
    static final String RELIABILITY_TEST_RUNNER_FILES[] = {
        "run_reliability_tests.py"
    };

    public ReliabilityTest() {
        super(PKG_NAME, ReliabilityTestActivity.class);
    }
    
    @Override
    protected void runTest() throws Throwable {
        ReliabilityTestActivity activity = getActivity();
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner)getInstrumentation();
        
        File testListFile = new File(TEST_LIST_FILE);
        if(!testListFile.exists())
            throw new FileNotFoundException("test list file not found.");
        
        BufferedReader listReader = new BufferedReader(
                new FileReader(testListFile));
        
        //always try to resume first, hence cleaning up status will be the
        //responsibility of driver scripts
        String lastUrl = readTestStatus();
        if(lastUrl != null && !TEST_DONE.equals(lastUrl))
            fastForward(listReader, lastUrl);
        
        String url = null;
        Handler handler = null;
        boolean timeoutFlag = false;
        long start, elapsed;
        //read from BufferedReader instead of populating a list in advance,
        //this will avoid excessive memory usage in case of a large list
        while((url = listReader.readLine()) != null) {
            start = System.currentTimeMillis();
            Log.v(LOGTAG, "Testing URL: " + url);
            updateTestStatus(url);
            activity.reset();
            //use message to send new URL to avoid interacting with
            //WebView in non-UI thread
            handler = activity.getHandler();
            handler.sendMessage(handler.obtainMessage(
                    ReliabilityTestActivity.MSG_NAVIGATE, 
                    runner.mTimeoutInMillis, 0, url));
            timeoutFlag = activity.waitUntilDone();
            elapsed = System.currentTimeMillis() - start;
            if(elapsed < 1000) {
                Log.w(LOGTAG, "Page load finished in " + elapsed
                        + "ms, too soon?");
            } else {
                Log.v(LOGTAG, "Page load finished in " + elapsed + "ms");
            }
            if(timeoutFlag) {
                writeTimeoutFile(url);
            }
            System.runFinalization();
            System.gc();
            System.gc();
        }
        updateTestStatus(TEST_DONE);
        activity.finish();
        listReader.close();
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
            Log.e(LOGTAG, "Cannot extract scripts for testing.", e);
        }
    }
    
    private void updateTestStatus(String s) {
        // write last tested url into status file
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(TEST_STATUS_FILE));
            bos.write(s.getBytes());
            bos.close();
        } catch (IOException e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_STATUS_FILE, e);
        }
    }
    
    private String readTestStatus() {
        // read out the test name it stopped last time.
        String status = null;
        File testStatusFile = new File(TEST_STATUS_FILE);
        if(testStatusFile.exists()) {
            try {
                BufferedReader inReader = new BufferedReader(
                        new FileReader(testStatusFile));
                status = inReader.readLine();
                inReader.close();
            } catch (IOException e) {
                Log.e(LOGTAG, "Error reading test status.", e);
            }
        }
        return status;
    }
    
    private void fastForward(BufferedReader testListReader, String lastUrl) {
        //fastforward the BufferedReader to the position right after last url
        if(lastUrl == null)
            return;
        
        String line = null;
        try {
            while((line = testListReader.readLine()) != null) {
                if(lastUrl.equals(line))
                    return;
            }
        } catch (IOException ioe) {
            Log.e(LOGTAG, "Error while reading test list.", ioe);
            return;
        }
    }

    private void writeTimeoutFile(String s) {
        //append to the file containing the list of timeout urls 
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(TEST_TIMEOUT_FILE, true));
            bos.write(s.getBytes());
            bos.write('\n');
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_TIMEOUT_FILE, e);
        }
    }
}
