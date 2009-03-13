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

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Instrumentation.ActivityMonitor;
import android.content.Intent;

import android.util.Log;

import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;

import com.android.dumprendertree.TestShellActivity;
import com.android.dumprendertree.TestShellCallback;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.IOException;

class StreamPipe extends Thread {
    InputStream in;
    OutputStream out;
    
    StreamPipe(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }
    
    public void run() {
        try {
            byte[] buf = new byte[1024];
            int nofb = this.in.read(buf);
            while (nofb != -1) {
                this.out.write(buf, 0, nofb);
                nofb = this.in.read(buf);
            }          
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

public class LoadTestsAutoTest extends ActivityInstrumentationTestCase2<TestShellActivity> {

    private final static String LOGTAG = "LoadTest";
    private final static String LOAD_TEST_RESULT = "/sdcard/load_test_result.txt";
    
    public LoadTestsAutoTest() {
        super("com.android.dumprendertree", TestShellActivity.class);
    }

    // This function writes the result of the layout test to
    // Am status so that it can be picked up from a script.
    public void passOrFailCallback(String file, boolean result) {
        Instrumentation inst = getInstrumentation();
        Bundle bundle = new Bundle();
        bundle.putBoolean(file, result);
        inst.sendStatus(0, bundle);
    }
    
    // Invokes running of layout tests
    // and waits till it has finished running.
    public void runTest() {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();

        if (runner.mTestPath == null) {
            Log.e(LOGTAG, "No test specified");
            return;
        }
        
        TestShellActivity activity = (TestShellActivity) getActivity();

        // Run tests
        runTestAndWaitUntilDone(activity, runner.mTestPath, runner.mTimeoutInMillis);

        // TODO(fqian): let am instrumentation pass in the command line, currently
        // am instrument does not allow spaces in the command.
        runPostShellCommand("/system/bin/dumpsys meminfo");
        
        // Kill activity
        activity.finish();
    }

    private void runPostShellCommand(String cmd) {
        if (cmd == null || cmd.length() == 0)
            return;
        
        try {
            // Call dumpsys meminfo
            Process proc = Runtime.getRuntime().exec(cmd);
            // Append output to LOAD_TEST_RESULT
            InputStream input = proc.getInputStream();
            InputStream error = proc.getErrorStream();
            FileOutputStream out = new FileOutputStream(LOAD_TEST_RESULT, true);

            StreamPipe p_in = new StreamPipe(input, out);
            StreamPipe p_err = new StreamPipe(error, System.err);
            
            p_in.start();
            p_err.start();
            
            proc.waitFor();
        } catch (IOException e) {
            Log.e(LOGTAG, e.getMessage());
        } catch (InterruptedException e) {
            Log.e(LOGTAG, e.getMessage());
        }      
    }
    
    // A convenient method to be called by another activity.
    private void runTestAndWaitUntilDone(TestShellActivity activity, String url, int timeout) {
        activity.setCallback(new TestShellCallback() {
            public void finished() {
                synchronized (LoadTestsAutoTest.this) {
                    LoadTestsAutoTest.this.notifyAll();
                }
            }         
        });
        
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, TestShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, url);
        intent.putExtra(TestShellActivity.TIMEOUT_IN_MILLIS, timeout);
        intent.putExtra(TestShellActivity.RESULT_FILE, LOAD_TEST_RESULT);
        activity.startActivity(intent);
        
        // Wait until done.
        synchronized (this) {
            try {
                this.wait();
            } catch (InterruptedException e) { }
        }
    }   
}
