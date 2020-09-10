/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.gameperformance;

import java.text.DecimalFormat;
import java.util.concurrent.TimeUnit;

import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

/**
 * Base class for a test that performs bisection to determine maximum
 * performance of a metric test measures.
 */
public abstract class BaseTest  {
    private final static String TAG = "BaseTest";

    // Time to wait for render warm up. No statistics is collected during this pass.
    private final static long WARM_UP_TIME = TimeUnit.SECONDS.toMillis(5);

    // Perform pass to probe the configuration using iterations. After each iteration current FPS is
    // checked and if it looks obviously bad, pass gets stopped earlier. Once all iterations are
    // done and final FPS is above PASS_THRESHOLD pass to probe is considered successful.
    private final static long TEST_ITERATION_TIME = TimeUnit.SECONDS.toMillis(12);
    private final static int TEST_ITERATION_COUNT = 5;

    // FPS pass test threshold, in ratio from ideal FPS, that matches device
    // refresh rate.
    private final static double PASS_THRESHOLD = 0.95;
    // FPS threshold, in ratio from ideal FPS, to identify that current pass to probe is obviously
    // bad and to stop pass earlier.
    private final static double OBVIOUS_BAD_THRESHOLD = 0.90;

    private static DecimalFormat DOUBLE_FORMATTER = new DecimalFormat("#.##");

    private final GamePerformanceActivity mActivity;

    // Device's refresh rate.
    private final double mRefreshRate;

    public BaseTest(@NonNull GamePerformanceActivity activity) {
        mActivity = activity;
        mRefreshRate = activity.getDisplay().getRefreshRate();
    }

    @NonNull
    public Context getContext() {
        return mActivity;
    }

    @NonNull
    public GamePerformanceActivity getActivity() {
        return mActivity;
    }

    // Returns name of the test.
    public abstract String getName();

    // Returns unit name.
    public abstract String getUnitName();

    // Returns number of measured units per one bisection unit.
    public abstract double getUnitScale();

    // Initializes test.
    public abstract void initUnits(double unitCount);

    // Initializes probe pass.
    protected abstract void initProbePass(int probe);

    // Frees probe pass.
    protected abstract void freeProbePass();

    /**
     * Performs the test and returns maximum number of measured units achieved. Unit is test
     * specific and name is returned by getUnitName. Returns 0 in case of failure.
     */
    public double run() {
        try {
            Log.i(TAG, "Test started " + getName());

            final double passFps = PASS_THRESHOLD * mRefreshRate;
            final double obviousBadFps = OBVIOUS_BAD_THRESHOLD * mRefreshRate;

            // Bisection bounds. Probe value is taken as middle point. Then it used to initialize
            // test with probe * getUnitScale units. In case probe passed, lowLimit is updated to
            // probe, otherwise upLimit is updated to probe. lowLimit contains probe that passes
            // and upLimit contains the probe that fails. Each iteration narrows the range.
            // Iterations continue until range is collapsed and lowLimit contains actual test
            // result.
            int lowLimit = 0;  // Initially 0, that is recognized as failure.
            int upLimit = 250;

            while (true) {
                int probe = (lowLimit + upLimit) / 2;
                if (probe == lowLimit) {
                    Log.i(TAG, "Test done: " + DOUBLE_FORMATTER.format(probe * getUnitScale()) +
                               " " + getUnitName());
                    return probe * getUnitScale();
                }

                Log.i(TAG, "Start probe: " + DOUBLE_FORMATTER.format(probe * getUnitScale()) + " " +
                           getUnitName());
                initProbePass(probe);

                Thread.sleep(WARM_UP_TIME);

                getActivity().resetFrameTimes();

                double fps = 0.0f;
                for (int i = 0; i < TEST_ITERATION_COUNT; ++i) {
                    Thread.sleep(TEST_ITERATION_TIME);
                    fps = getActivity().getFps();
                    if (fps < obviousBadFps) {
                        // Stop test earlier, we could not fit the loading.
                        break;
                    }
                }

                freeProbePass();

                Log.i(TAG, "Finish probe: " + DOUBLE_FORMATTER.format(probe * getUnitScale()) +
                           " " + getUnitName() + " - " + DOUBLE_FORMATTER.format(fps) + " FPS.");
                if (fps < passFps) {
                    upLimit = probe;
                } else {
                    lowLimit = probe;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}