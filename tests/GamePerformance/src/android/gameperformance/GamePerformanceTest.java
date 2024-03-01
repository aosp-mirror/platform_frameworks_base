/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.app.Activity;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;

import androidx.test.filters.SmallTest;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public class GamePerformanceTest extends
        ActivityInstrumentationTestCase2<GamePerformanceActivity> {
    private final static String TAG = "GamePerformanceTest";

    private final static int GRAPHIC_BUFFER_WARMUP_LOOP_CNT = 60;

    public GamePerformanceTest() {
        super(GamePerformanceActivity.class);
    }

    @SmallTest
    public void testGraphicBufferMetrics() throws IOException, InterruptedException {
        Bundle status = new Bundle();

        for (int i = 0; i < 2; ++i) {
            if (i == 0) {
                getActivity().attachSurfaceView();
            } else {
                getActivity().attachOpenGLView();
            }

            // Perform warm-up.
            Thread.sleep(2000);

            // Once atrace is done, this one is triggered.
            CountDownLatch latch = new CountDownLatch(1);

            final String passTag = i == 0 ? "surface" : "opengl";
            final String output = (new File(getInstrumentation().getContext().getFilesDir(),
                    "atrace_" + passTag + ".log")).getAbsolutePath();
            Log.i(TAG, "Collecting traces to " + output);
            new ATraceRunner(getInstrumentation(), output, 5, "gfx", new ATraceRunner.Delegate() {
                @Override
                public void onProcessed(boolean success) {
                    latch.countDown();
                }
            }).execute();

            // Reset frame times and perform invalidation loop while atrace is running.
            getActivity().resetFrameTimes();
            latch.await();

            // Copy results.
            final Map<String, Double> metrics =
                    GraphicBufferMetrics.processGraphicBufferResult(output, passTag);
            for (Map.Entry<String, Double> metric : metrics.entrySet()) {
                status.putDouble(metric.getKey(), metric.getValue());
            }
            // Also record FPS.
            status.putDouble(passTag + "_fps", getActivity().getFps());
        }

        getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    @SmallTest
    public void testPerformanceMetricsWithoutExtraLoad() throws IOException, InterruptedException {
        final Bundle status = runPerformanceTests("no_extra_load_");
        getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    @SmallTest
    public void testPerformanceMetricsWithExtraLoad() throws IOException, InterruptedException {
        // Start CPU ballast threads first.
        CPULoadThread[] cpuLoadThreads = new CPULoadThread[2];
        for (int i = 0; i < cpuLoadThreads.length; ++i) {
            cpuLoadThreads[i] = new CPULoadThread();
            cpuLoadThreads[i].start();
        }

        final Bundle status = runPerformanceTests("extra_load_");

        for (int i = 0; i < cpuLoadThreads.length; ++i) {
            cpuLoadThreads[i].issueStopRequest();
            cpuLoadThreads[i].join();
        }

        getInstrumentation().sendStatus(Activity.RESULT_OK, status);
    }

    @NonNull
    private Bundle runPerformanceTests(@NonNull String prefix) {
        final Bundle status = new Bundle();

        final GamePerformanceActivity activity = getActivity();

        final List<BaseTest> tests = new ArrayList<>();
        tests.add(new TriangleCountOpenGLTest(activity));
        tests.add(new FillRateOpenGLTest(activity, false /* testBlend */));
        tests.add(new FillRateOpenGLTest(activity, true /* testBlend */));
        tests.add(new DeviceCallsOpenGLTest(activity));
        tests.add(new ControlsTest(activity));

        for (BaseTest test : tests) {
            final double result = test.run();
            status.putDouble(prefix + test.getName(), result);
        }

        return status;
    }
}
