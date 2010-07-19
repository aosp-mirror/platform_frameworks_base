/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.app.Activity;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
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
    private static final String EXTERNAL_DIR =
        Environment.getExternalStorageDirectory().toString();
    private static final String TEST_LIST_FILE = EXTERNAL_DIR +
        "/android/reliability_tests_list.txt";
    private static final String TEST_STATUS_FILE = EXTERNAL_DIR +
        "/android/reliability_running_test.txt";
    private static final String TEST_TIMEOUT_FILE = EXTERNAL_DIR +
        "/android/reliability_timeout_test.txt";
    private static final String TEST_LOAD_TIME_FILE = EXTERNAL_DIR +
        "/android/reliability_load_time.txt";
    private static final String TEST_DONE = "#DONE";
    static final String RELIABILITY_TEST_RUNNER_FILES[] = {
        "run_reliability_tests.py"
    };

    public ReliabilityTest() {
        super(PKG_NAME, ReliabilityTestActivity.class);
    }

    public void runReliabilityTest() throws Throwable {
//        ReliabilityTestActivity activity = getActivity();
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner)getInstrumentation();

        File testListFile = new File(TEST_LIST_FILE);
        if(!testListFile.exists())
            throw new FileNotFoundException("test list file not found.");

        BufferedReader listReader = new BufferedReader(
                new FileReader(testListFile));

        //always try to resume first, hence cleaning up status will be the
        //responsibility of driver scripts
        String lastUrl = FsUtils.readTestStatus(TEST_STATUS_FILE);
        if(lastUrl != null && !TEST_DONE.equals(lastUrl))
            fastForward(listReader, lastUrl);

        String url = null;
        Handler handler = null;
        boolean timeoutFlag = false;
        long start, elapsed;

        Intent intent = new Intent(runner.getContext(), ReliabilityTestActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ReliabilityTestActivity activity = (ReliabilityTestActivity)runner.startActivitySync(
            intent);
        //read from BufferedReader instead of populating a list in advance,
        //this will avoid excessive memory usage in case of a large list
        while((url = listReader.readLine()) != null) {
            url = url.trim();
            if(url.length() == 0)
                continue;
            start = System.currentTimeMillis();
            Log.v(LOGTAG, "Testing URL: " + url);
            FsUtils.updateTestStatus(TEST_STATUS_FILE, url);
            activity.reset();
            //use message to send new URL to avoid interacting with
            //WebView in non-UI thread
            handler = activity.getHandler();
            Message msg = handler.obtainMessage(
                    ReliabilityTestActivity.MSG_NAVIGATE,
                    runner.mTimeoutInMillis, runner.mDelay);
            msg.getData().putString(ReliabilityTestActivity.MSG_NAV_URL, url);
            msg.getData().putBoolean(ReliabilityTestActivity.MSG_NAV_LOGTIME,
                    runner.mLogtime);
            handler.sendMessage(msg);
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
            if(runner.mLogtime) {
                writeLoadTime(url, activity.getPageLoadTime());
            }
            System.runFinalization();
            System.gc();
            System.gc();
        }
        activity.finish();
        FsUtils.updateTestStatus(TEST_STATUS_FILE, TEST_DONE);
//        activity.finish();
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

    private void writeLoadTime(String s, long time) {
        //append to the file containing the list of timeout urls
        try {
            BufferedOutputStream bos = new BufferedOutputStream(
                    new FileOutputStream(TEST_LOAD_TIME_FILE, true));
            bos.write((s + '|' + time + '\n').getBytes());
            bos.close();
        } catch (Exception e) {
            Log.e(LOGTAG, "Cannot update file " + TEST_LOAD_TIME_FILE, e);
        }
    }
}
