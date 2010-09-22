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

import dalvik.system.VMRuntime;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Environment;
import android.os.Process;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

public class LoadTestsAutoTest extends ActivityInstrumentationTestCase2<TestShellActivity> {

    private final static String LOGTAG = "LoadTest";
    private final static String LOAD_TEST_RESULT =
        Environment.getExternalStorageDirectory() + "/load_test_result.txt";
    private boolean mFinished;
    static final String LOAD_TEST_RUNNER_FILES[] = {
        "run_page_cycler.py"
    };

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
    public void runPageCyclerTest() {
        LayoutTestsAutoRunner runner = (LayoutTestsAutoRunner) getInstrumentation();

        if (runner.mTestPath == null) {
            Log.e(LOGTAG, "No test specified");
            return;
        }

        TestShellActivity activity = (TestShellActivity) getActivity();

        Log.v(LOGTAG, "About to run tests, calling gc first...");
        freeMem();

        // Run tests
        runTestAndWaitUntilDone(activity, runner.mTestPath, runner.mTimeoutInMillis,
                runner.mGetDrawTime, runner.mSaveImagePath);

        activity.clearCache();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
        }
        dumpMemoryInfo();

        // Kill activity
        activity.finish();
    }

    private void freeMem() {
        Log.v(LOGTAG, "freeMem: calling gc/finalization...");
        final VMRuntime runtime = VMRuntime.getRuntime();

        runtime.gcSoftReferences();
        runtime.runFinalizationSync();
        runtime.gcSoftReferences();
        runtime.runFinalizationSync();
        runtime.gcSoftReferences();
        runtime.runFinalizationSync();
        Runtime.getRuntime().runFinalization();
        Runtime.getRuntime().gc();
        Runtime.getRuntime().gc();

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
    private void runTestAndWaitUntilDone(TestShellActivity activity, String url, int timeout,
            boolean getDrawTime, String saveImagePath) {
        activity.setCallback(new TestShellCallback() {
            public void finished() {
                synchronized (LoadTestsAutoTest.this) {
                    mFinished = true;
                    LoadTestsAutoTest.this.notifyAll();
                }
            }

            public void timedOut(String url) {
            }
        });

        mFinished = false;
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setClass(activity, TestShellActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(TestShellActivity.TEST_URL, url);
        intent.putExtra(TestShellActivity.TIMEOUT_IN_MILLIS, timeout);
        intent.putExtra(TestShellActivity.RESULT_FILE, LOAD_TEST_RESULT);
        intent.putExtra(TestShellActivity.GET_DRAW_TIME, getDrawTime);
        if (saveImagePath != null)
            intent.putExtra(TestShellActivity.SAVE_IMAGE, saveImagePath);
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
            String out_dir = getActivity().getApplicationContext()
                .getCacheDir().getPath() + "/";

            for( int i=0; i< LOAD_TEST_RUNNER_FILES.length; i++) {
                InputStream in = getActivity().getAssets().open(
                        LOAD_TEST_RUNNER_FILES[i]);
                OutputStream out = new FileOutputStream(
                        out_dir + LOAD_TEST_RUNNER_FILES[i]);

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
