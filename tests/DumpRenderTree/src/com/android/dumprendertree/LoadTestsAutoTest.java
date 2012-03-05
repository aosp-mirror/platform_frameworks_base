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

import com.android.dumprendertree.forwarder.AdbUtils;
import com.android.dumprendertree.forwarder.ForwardServer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LoadTestsAutoTest extends ActivityInstrumentationTestCase2<TestShellActivity> {

    private final static String LOGTAG = "LoadTest";
    private final static String LOAD_TEST_RESULT =
        Environment.getExternalStorageDirectory() + "/load_test_result.txt";
    private final static int MAX_GC_WAIT_SEC = 10;
    private final static int LOCAL_PORT = 17171;
    private boolean mFinished;
    static final String LOAD_TEST_RUNNER_FILES[] = {
        "run_page_cycler.py"
    };
    private ForwardServer mForwardServer;

    public LoadTestsAutoTest() {
        super(TestShellActivity.class);
    }

    // This function writes the result of the layout test to
    // Am status so that it can be picked up from a script.
    public void passOrFailCallback(String file, boolean result) {
        Instrumentation inst = getInstrumentation();
        Bundle bundle = new Bundle();
        bundle.putBoolean(file, result);
        inst.sendStatus(0, bundle);
    }

    private String setUpForwarding(String forwardInfo, String suite, String iteration) throws IOException {
        // read forwarding information first
        Pattern forwardPattern = Pattern.compile("(.*):(\\d+)/(.*)/");
        Matcher matcher = forwardPattern.matcher(forwardInfo);
        if (!matcher.matches()) {
            throw new RuntimeException("Invalid forward information");
        }
        String host = matcher.group(1);
        int port = Integer.parseInt(matcher.group(2));
        mForwardServer = new ForwardServer(LOCAL_PORT, AdbUtils.resolve(host), port);
        mForwardServer.start();
        return String.format("http://127.0.0.1:%d/%s/%s/start.html?auto=1&iterations=%s",
                LOCAL_PORT, matcher.group(3), suite, iteration);
    }

    // Invokes running of layout tests
    // and waits till it has finished running.
    public void runPageCyclerTest() throws IOException {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();

        if (runner.mPageCyclerSuite != null) {
            // start forwarder to use page cycler suites hosted on external web server
            if (runner.mPageCyclerForwardHost == null) {
                throw new RuntimeException("no forwarder information provided");
            }
            runner.mTestPath = setUpForwarding(runner.mPageCyclerForwardHost,
                    runner.mPageCyclerSuite, runner.mPageCyclerIteration);
            Log.d(LOGTAG, "using path: " + runner.mTestPath);
        }

        if (runner.mTestPath == null) {
            throw new RuntimeException("No test specified");
        }

        final TestShellActivity activity = (TestShellActivity) getActivity();

        Log.v(LOGTAG, "About to run tests, calling gc first...");
        freeMem();

        // Run tests
        runTestAndWaitUntilDone(activity, runner.mTestPath, runner.mTimeoutInMillis);

        getInstrumentation().runOnMainSync(new Runnable() {

            @Override
            public void run() {
                activity.clearCache();
            }
        });
        if (mForwardServer != null) {
            mForwardServer.stop();
            mForwardServer = null;
        }
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }
        dumpMemoryInfo();

        // Kill activity
        activity.finish();
    }

    private void freeMem() {
        Log.v(LOGTAG, "freeMem: calling gc...");
        final CountDownLatch latch = new CountDownLatch(1);
        @SuppressWarnings("unused")
        Object dummy = new Object() {
            // this object instance is used to track gc
            @Override
            protected void finalize() throws Throwable {
                latch.countDown();
                super.finalize();
            }
        };
        dummy = null;
        System.gc();
        try {
            if (!latch.await(MAX_GC_WAIT_SEC, TimeUnit.SECONDS)) {
                Log.w(LOGTAG, "gc did not happen in 10s");
            }
        } catch (InterruptedException e) {
            //ignore
        }
    }

    private void printRow(PrintStream ps, String format, Object...objs) {
        ps.println(String.format(format, objs));
    }

    private void dumpMemoryInfo() {
        try {
            freeMem();
            Log.v(LOGTAG, "Dumping memory information.");

            FileOutputStream out = new FileOutputStream(LOAD_TEST_RESULT, true);
            PrintStream ps = new PrintStream(out);

            ps.print("\n\n\n");
            ps.println("** MEMINFO in pid " + Process.myPid()
                    + " [com.android.dumprendertree] **");
            String formatString = "%17s %8s %8s %8s %8s";

            long nativeMax = Debug.getNativeHeapSize() / 1024;
            long nativeAllocated = Debug.getNativeHeapAllocatedSize() / 1024;
            long nativeFree = Debug.getNativeHeapFreeSize() / 1024;
            Runtime runtime = Runtime.getRuntime();
            long dalvikMax = runtime.totalMemory() / 1024;
            long dalvikFree = runtime.freeMemory() / 1024;
            long dalvikAllocated = dalvikMax - dalvikFree;


            Debug.MemoryInfo memInfo = new Debug.MemoryInfo();
            Debug.getMemoryInfo(memInfo);

            final int nativeShared = memInfo.nativeSharedDirty;
            final int dalvikShared = memInfo.dalvikSharedDirty;
            final int otherShared = memInfo.otherSharedDirty;

            final int nativePrivate = memInfo.nativePrivateDirty;
            final int dalvikPrivate = memInfo.dalvikPrivateDirty;
            final int otherPrivate = memInfo.otherPrivateDirty;

            printRow(ps, formatString, "", "native", "dalvik", "other", "total");
            printRow(ps, formatString, "size:", nativeMax, dalvikMax, "N/A", nativeMax + dalvikMax);
            printRow(ps, formatString, "allocated:", nativeAllocated, dalvikAllocated, "N/A",
                    nativeAllocated + dalvikAllocated);
            printRow(ps, formatString, "free:", nativeFree, dalvikFree, "N/A",
                    nativeFree + dalvikFree);

            printRow(ps, formatString, "(Pss):", memInfo.nativePss, memInfo.dalvikPss,
                    memInfo.otherPss, memInfo.nativePss + memInfo.dalvikPss + memInfo.otherPss);

            printRow(ps, formatString, "(shared dirty):", nativeShared, dalvikShared, otherShared,
                    nativeShared + dalvikShared + otherShared);
            printRow(ps, formatString, "(priv dirty):", nativePrivate, dalvikPrivate, otherPrivate,
                    nativePrivate + dalvikPrivate + otherPrivate);
            ps.print("\n\n\n");
            ps.flush();
            ps.close();
            out.flush();
            out.close();
        } catch (IOException e) {
            Log.e(LOGTAG, e.getMessage());
        }
    }

    // A convenient method to be called by another activity.
    private void runTestAndWaitUntilDone(TestShellActivity activity, String url, int timeout) {
        activity.setCallback(new TestShellCallback() {
            @Override
            public void finished() {
                synchronized (LoadTestsAutoTest.this) {
                    mFinished = true;
                    LoadTestsAutoTest.this.notifyAll();
                }
            }

            @Override
            public void timedOut(String url) {
            }

            @Override
            public void dumpResult(String webViewDump) {
                String lines[] = webViewDump.split("\\r?\\n");
                for (String line : lines) {
                    line = line.trim();
                    // parse for a line like this:
                    // totals:   9620.00 11947.00    10099.75    380.38
                    // and return the 3rd number, which is mean
                    if (line.startsWith("totals:")) {
                        line = line.substring(7).trim(); // strip "totals:"
                        String[] numbers = line.split("\\s+");
                        if (numbers.length == 4) {
                            Bundle b = new Bundle();
                            b.putString("mean", numbers[2]);
                            getInstrumentation().sendStatus(Activity.RESULT_FIRST_USER, b);
                        }
                    }
                }
            }
        });

        mFinished = false;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, TestShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, url);
        intent.putExtra(TestShellActivity.TIMEOUT_IN_MILLIS, timeout);
        intent.putExtra(TestShellActivity.RESULT_FILE, LOAD_TEST_RESULT);
        activity.startActivity(intent);

        // Wait until done.
        synchronized (this) {
            while(!mFinished) {
                try {
                    this.wait();
                } catch (InterruptedException e) { }
            }
        }
    }

    public void copyRunnerAssetsToCache() {
        try {
            Context targetContext = getInstrumentation().getTargetContext();
            File cacheDir = targetContext.getCacheDir();

            for( int i=0; i< LOAD_TEST_RUNNER_FILES.length; i++) {
                InputStream in = targetContext.getAssets().open(
                        LOAD_TEST_RUNNER_FILES[i]);
                OutputStream out = new FileOutputStream(
                        new File(cacheDir, LOAD_TEST_RUNNER_FILES[i]));

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
