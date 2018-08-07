/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.benchmark.synthetic;

import android.view.View;
import android.widget.TextView;

import org.apache.commons.math.stat.StatUtils;
import org.apache.commons.math.stat.descriptive.SummaryStatistics;

import java.util.LinkedList;
import java.util.Queue;


public class TestInterface {
    native long nInit(long options);
    native long nDestroy(long b);
    native float nGetData(long b, float[] data);
    native boolean nRunPowerManagementTest(long b, long options);
    native boolean nRunCPUHeatSoakTest(long b, long options);

    native boolean nMemTestStart(long b);
    native float nMemTestBandwidth(long b, long size);
    native float nMemTestLatency(long b, long size);
    native void nMemTestEnd(long b);

    native float nGFlopsTest(long b, long opt);

    public static class TestResultCallback {
        void onTestResult(int command, float result) { }
    }

    static {
        System.loadLibrary("nativebench");
    }

    float[] mLinesLow;
    float[] mLinesHigh;
    float[] mLinesValue;
    TextView mTextStatus;
    TextView mTextMin;
    TextView mTextMax;
    TextView mTextTypical;

    private View mViewToUpdate;

    private LooperThread mLT;

    TestInterface(View v, int runtimeSeconds, TestResultCallback callback) {
        int buckets = runtimeSeconds * 1000;
        mLinesLow = new float[buckets * 4];
        mLinesHigh = new float[buckets * 4];
        mLinesValue = new float[buckets * 4];
        mViewToUpdate = v;

        mLT = new LooperThread(this, callback);
        mLT.start();
    }

    static class LooperThread extends Thread {
        public static final int CommandExit = 1;
        public static final int TestPowerManagement = 2;
        public static final int TestMemoryBandwidth = 3;
        public static final int TestMemoryLatency = 4;
        public static final int TestHeatSoak = 5;
        public static final int TestGFlops = 6;

        private volatile boolean mRun = true;
        private TestInterface mTI;
        private TestResultCallback mCallback;

        Queue<Integer> mCommandQueue = new LinkedList<Integer>();

        LooperThread(TestInterface ti, TestResultCallback callback) {
            super("BenchmarkTestThread");
            mTI = ti;
            mCallback = callback;
        }

        void runCommand(int command) {
            Integer i = Integer.valueOf(command);

            synchronized (this) {
                mCommandQueue.add(i);
                notifyAll();
            }
        }

        public void run() {
            long b = mTI.nInit(0);
            if (b == 0) {
                return;
            }

            while (mRun) {
                int command = 0;
                synchronized (this) {
                    if (mCommandQueue.isEmpty()) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                        }
                    }

                    if (!mCommandQueue.isEmpty()) {
                        command = mCommandQueue.remove();
                    }
                }

                switch (command) {
                    case CommandExit:
                        mRun = false;
                        break;
                    case TestPowerManagement:
                        float score = mTI.testPowerManagement(b);
                        mCallback.onTestResult(command, 0);
                        break;
                    case TestMemoryBandwidth:
                        mTI.testCPUMemoryBandwidth(b);
                        break;
                    case TestMemoryLatency:
                        mTI.testCPUMemoryLatency(b);
                        break;
                    case TestHeatSoak:
                        mTI.testCPUHeatSoak(b);
                        break;
                    case TestGFlops:
                        mTI.testCPUGFlops(b);
                        break;

                }

                //mViewToUpdate.post(new Runnable() {
                  //  public void run() {
                   //     mViewToUpdate.invalidate();
                    //}
                //});
            }

            mTI.nDestroy(b);
        }

        void exit() {
            mRun = false;
        }
    }

    void postTextToView(TextView v, String s) {
        final TextView tv = v;
        final String ts = s;

        v.post(new Runnable() {
            public void run() {
                tv.setText(ts);
            }
        });

    }

    float calcAverage(float[] data) {
        float total = 0.f;
        for (int ct=0; ct < data.length; ct++) {
            total += data[ct];
        }
        return total / data.length;
    }

    void makeGraph(float[] data, float[] lines) {
        for (int ct = 0; ct < data.length; ct++) {
            lines[ct * 4 + 0] = (float)ct;
            lines[ct * 4 + 1] = 500.f - data[ct];
            lines[ct * 4 + 2] = (float)ct;
            lines[ct * 4 + 3] = 500.f;
        }
    }

    float testPowerManagement(long b) {
        float[] dat = new float[mLinesLow.length / 4];
        postTextToView(mTextStatus, "Running single-threaded");
        nRunPowerManagementTest(b, 1);
        nGetData(b, dat);
        makeGraph(dat, mLinesLow);
        mViewToUpdate.postInvalidate();
        float avgMin = calcAverage(dat);

        postTextToView(mTextMin, "Single threaded " + avgMin + " per second");

        postTextToView(mTextStatus, "Running multi-threaded");
        nRunPowerManagementTest(b, 4);
        nGetData(b, dat);
        makeGraph(dat, mLinesHigh);
        mViewToUpdate.postInvalidate();
        float avgMax = calcAverage(dat);
        postTextToView(mTextMax, "Multi threaded " + avgMax + " per second");

        postTextToView(mTextStatus, "Running typical");
        nRunPowerManagementTest(b, 0);
        nGetData(b, dat);
        makeGraph(dat, mLinesValue);
        mViewToUpdate.postInvalidate();
        float avgTypical = calcAverage(dat);

        float ofIdeal = avgTypical / (avgMax + avgMin) * 200.f;
        postTextToView(mTextTypical, String.format("Typical mix (50/50) %%%2.0f of ideal", ofIdeal));
        return ofIdeal * (avgMax + avgMin);
    }

    float testCPUHeatSoak(long b) {
        float[] dat = new float[1000];
        postTextToView(mTextStatus, "Running heat soak test");
        for (int t = 0; t < 1000; t++) {
            mLinesLow[t * 4 + 0] = (float)t;
            mLinesLow[t * 4 + 1] = 498.f;
            mLinesLow[t * 4 + 2] = (float)t;
            mLinesLow[t * 4 + 3] = 500.f;
        }

        float peak = 0.f;
        float total = 0.f;
        float dThroughput = 0;
        float prev = 0;
        SummaryStatistics stats = new SummaryStatistics();
        for (int t = 0; t < 1000; t++) {
            nRunCPUHeatSoakTest(b, 1);
            nGetData(b, dat);

            float p = calcAverage(dat);
            if (prev != 0) {
                dThroughput += (prev - p);
            }

            prev = p;

            mLinesLow[t * 4 + 1] = 499.f - p;
            if (peak < p) {
                peak = p;
            }
            for (float f : dat) {
                stats.addValue(f);
            }

            total += p;

            mViewToUpdate.postInvalidate();
            postTextToView(mTextMin, "Peak " + peak + " per second");
            postTextToView(mTextMax, "Current " + p + " per second");
            postTextToView(mTextTypical, "Average " + (total / (t + 1)) + " per second");
        }


        float decreaseOverTime = dThroughput / 1000;

        System.out.println("dthroughput/dt: " + decreaseOverTime);

        float score = (float) (stats.getMean() / (stats.getStandardDeviation() * decreaseOverTime));

        postTextToView(mTextStatus, "Score: " + score);
        return score;
    }

    void testCPUMemoryBandwidth(long b) {
        int[] sizeK = {1, 2, 3, 4, 5, 6, 7,
                    8, 10, 12, 14, 16, 20, 24, 28,
                    32, 40, 48, 56, 64, 80, 96, 112,
                    128, 160, 192, 224, 256, 320, 384, 448,
                    512, 640, 768, 896, 1024, 1280, 1536, 1792,
                    2048, 2560, 3584, 4096, 5120, 6144, 7168,
                    8192, 10240, 12288, 14336, 16384
        };
        final int subSteps = 15;
        float[] results = new float[sizeK.length * subSteps];

        nMemTestStart(b);

        float[] dat = new float[1000];
        postTextToView(mTextStatus, "Running Memory Bandwidth test");
        for (int t = 0; t < 1000; t++) {
            mLinesLow[t * 4 + 0] = (float)t;
            mLinesLow[t * 4 + 1] = 498.f;
            mLinesLow[t * 4 + 2] = (float)t;
            mLinesLow[t * 4 + 3] = 500.f;
        }

        for (int i = 0; i < sizeK.length; i++) {
            postTextToView(mTextStatus, "Running " + sizeK[i] + " K");

            float rtot = 0.f;
            for (int j = 0; j < subSteps; j++) {
                float ret = nMemTestBandwidth(b, sizeK[i] * 1024);
                rtot += ret;
                results[i * subSteps + j] = ret;
                mLinesLow[(i * subSteps + j) * 4 + 1] = 499.f - (results[i*15+j] * 20.f);
                mViewToUpdate.postInvalidate();
            }
            rtot /= subSteps;

            if (sizeK[i] == 2) {
                postTextToView(mTextMin, "2K " + rtot + " GB/s");
            }
            if (sizeK[i] == 128) {
                postTextToView(mTextMax, "128K " + rtot + " GB/s");
            }
            if (sizeK[i] == 8192) {
                postTextToView(mTextTypical, "8M " + rtot + " GB/s");
            }

        }

        nMemTestEnd(b);
        postTextToView(mTextStatus, "Done");
    }

    void testCPUMemoryLatency(long b) {
        int[] sizeK = {1, 2, 3, 4, 5, 6, 7,
                8, 10, 12, 14, 16, 20, 24, 28,
                32, 40, 48, 56, 64, 80, 96, 112,
                128, 160, 192, 224, 256, 320, 384, 448,
                512, 640, 768, 896, 1024, 1280, 1536, 1792,
                2048, 2560, 3584, 4096, 5120, 6144, 7168,
                8192, 10240, 12288, 14336, 16384
        };
        final int subSteps = 15;
        float[] results = new float[sizeK.length * subSteps];

        nMemTestStart(b);

        float[] dat = new float[1000];
        postTextToView(mTextStatus, "Running Memory Latency test");
        for (int t = 0; t < 1000; t++) {
            mLinesLow[t * 4 + 0] = (float)t;
            mLinesLow[t * 4 + 1] = 498.f;
            mLinesLow[t * 4 + 2] = (float)t;
            mLinesLow[t * 4 + 3] = 500.f;
        }

        for (int i = 0; i < sizeK.length; i++) {
            postTextToView(mTextStatus, "Running " + sizeK[i] + " K");

            float rtot = 0.f;
            for (int j = 0; j < subSteps; j++) {
                float ret = nMemTestLatency(b, sizeK[i] * 1024);
                rtot += ret;
                results[i * subSteps + j] = ret;

                if (ret > 400.f) ret = 400.f;
                if (ret < 0.f) ret = 0.f;
                mLinesLow[(i * subSteps + j) * 4 + 1] = 499.f - ret;
                //android.util.Log.e("bench", "test bw " + sizeK[i] + " - " + ret);
                mViewToUpdate.postInvalidate();
            }
            rtot /= subSteps;

            if (sizeK[i] == 2) {
                postTextToView(mTextMin, "2K " + rtot + " ns");
            }
            if (sizeK[i] == 128) {
                postTextToView(mTextMax, "128K " + rtot + " ns");
            }
            if (sizeK[i] == 8192) {
                postTextToView(mTextTypical, "8M " + rtot + " ns");
            }

        }

        nMemTestEnd(b);
        postTextToView(mTextStatus, "Done");
    }

    void testCPUGFlops(long b) {
        int[] sizeK = {1, 2, 3, 4, 5, 6, 7
        };
        final int subSteps = 15;
        float[] results = new float[sizeK.length * subSteps];

        nMemTestStart(b);

        float[] dat = new float[1000];
        postTextToView(mTextStatus, "Running Memory Latency test");
        for (int t = 0; t < 1000; t++) {
            mLinesLow[t * 4 + 0] = (float)t;
            mLinesLow[t * 4 + 1] = 498.f;
            mLinesLow[t * 4 + 2] = (float)t;
            mLinesLow[t * 4 + 3] = 500.f;
        }

        for (int i = 0; i < sizeK.length; i++) {
            postTextToView(mTextStatus, "Running " + sizeK[i] + " K");

            float rtot = 0.f;
            for (int j = 0; j < subSteps; j++) {
                float ret = nGFlopsTest(b, sizeK[i] * 1024);
                rtot += ret;
                results[i * subSteps + j] = ret;

                if (ret > 400.f) ret = 400.f;
                if (ret < 0.f) ret = 0.f;
                mLinesLow[(i * subSteps + j) * 4 + 1] = 499.f - ret;
                mViewToUpdate.postInvalidate();
            }
            rtot /= subSteps;

            if (sizeK[i] == 2) {
                postTextToView(mTextMin, "2K " + rtot + " ns");
            }
            if (sizeK[i] == 128) {
                postTextToView(mTextMax, "128K " + rtot + " ns");
            }
            if (sizeK[i] == 8192) {
                postTextToView(mTextTypical, "8M " + rtot + " ns");
            }

        }

        nMemTestEnd(b);
        postTextToView(mTextStatus, "Done");
    }

    public void runPowerManagement() {
        mLT.runCommand(mLT.TestPowerManagement);
    }

    public void runMemoryBandwidth() {
        mLT.runCommand(mLT.TestMemoryBandwidth);
    }

    public void runMemoryLatency() {
        mLT.runCommand(mLT.TestMemoryLatency);
    }

    public void runCPUHeatSoak() {
        mLT.runCommand(mLT.TestHeatSoak);
    }

    public void runCPUGFlops() {
        mLT.runCommand(mLT.TestGFlops);
    }
}
