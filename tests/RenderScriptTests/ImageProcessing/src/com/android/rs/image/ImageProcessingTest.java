/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.rs.image;

import com.android.rs.image.ImageProcessingTestRunner;

import android.os.Bundle;
import com.android.rs.image.ImageProcessingActivity.TestName;

import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;

/**
 * ImageProcessing benchmark test.
 * To run the test, please use command
 *
 * adb shell am instrument -e iteration <n> -w com.android.rs.image/.ImageProcessingTestRunner
 *
 */
public class ImageProcessingTest extends ActivityInstrumentationTestCase2<ImageProcessingActivity> {
    private final String TAG = "ImageProcessingTest";
    private final String TEST_NAME = "Testname";
    private final String ITERATIONS = "Iterations";
    private final String BENCHMARK = "Benchmark";
    private static int INSTRUMENTATION_IN_PROGRESS = 2;
    private int mIteration;
    private ImageProcessingActivity mActivity;

    public ImageProcessingTest() {
        super(ImageProcessingActivity.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        setActivityInitialTouchMode(false);
        mActivity = getActivity();
        ImageProcessingTestRunner mRunner = (ImageProcessingTestRunner) getInstrumentation();
        mIteration = mRunner.mIteration;
        assertTrue("please enter a valid iteration value", mIteration > 0);
   }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    class TestAction implements Runnable {
        TestName mTestName;
        float mResult;
        public TestAction(TestName testName) {
            mTestName = testName;
        }
        public void run() {
            mActivity.changeTest(mTestName);
            mResult = mActivity.getBenchmark();
            Log.v(TAG, "Benchmark for test \"" + mTestName.toString() + "\" is: " + mResult);
            synchronized(this) {
                this.notify();
            }
        }
        public float getBenchmark() {
            return mResult;
        }
    }

    // Set the benchmark thread to run on ui thread
    // Synchronized the thread such that the test will wait for the benchmark thread to finish
    public void runOnUiThread(Runnable action) {
        synchronized(action) {
            mActivity.runOnUiThread(action);
            try {
                action.wait();
            } catch (InterruptedException e) {
                Log.v(TAG, "waiting for action running on UI thread is interrupted: " +
                        e.toString());
            }
        }
    }

    public void runTest(TestAction ta, String testName) {
        float sum = 0;
        for (int i = 0; i < mIteration; i++) {
            runOnUiThread(ta);
            float bmValue = ta.getBenchmark();
            Log.v(TAG, "results for iteration " + i + " is " + bmValue);
            sum += bmValue;
        }
        float avgResult = sum/mIteration;

        // post result to INSTRUMENTATION_STATUS
        Bundle results = new Bundle();
        results.putString(TEST_NAME, testName);
        results.putInt(ITERATIONS, mIteration);
        results.putFloat(BENCHMARK, avgResult);
        getInstrumentation().sendStatus(INSTRUMENTATION_IN_PROGRESS, results);
    }

    // Test case 0: Levels Vec3 Relaxed
    @LargeTest
    public void testLevelsVec3Relaxed() {
        TestAction ta = new TestAction(TestName.LEVELS_VEC3_RELAXED);
        runTest(ta, TestName.LEVELS_VEC3_RELAXED.name());
    }

    // Test case 1: Levels Vec4 Relaxed
    @LargeTest
    public void testLevelsVec4Relaxed() {
        TestAction ta = new TestAction(TestName.LEVELS_VEC4_RELAXED);
        runTest(ta, TestName.LEVELS_VEC4_RELAXED.name());
    }

    // Test case 2: Levels Vec3 Full
    @LargeTest
    public void testLevelsVec3Full() {
        TestAction ta = new TestAction(TestName.LEVELS_VEC3_FULL);
        runTest(ta, TestName.LEVELS_VEC3_FULL.name());
    }

    // Test case 3: Levels Vec4 Full
    @LargeTest
    public void testLevelsVec4Full() {
        TestAction ta = new TestAction(TestName.LEVELS_VEC4_FULL);
        runTest(ta, TestName.LEVELS_VEC4_FULL.name());
    }

    // Test case 4: Blur Radius 25
    @LargeTest
    public void testBlurRadius25() {
        TestAction ta = new TestAction(TestName.BLUR_RADIUS_25);
        runTest(ta, TestName.BLUR_RADIUS_25.name());
    }

    // Test case 5: Intrinsic Blur Radius 25
    @LargeTest
    public void testIntrinsicBlurRadius25() {
        TestAction ta = new TestAction(TestName.INTRINSIC_BLUE_RADIUS_25);
        runTest(ta, TestName.INTRINSIC_BLUE_RADIUS_25.name());
    }

    // Test case 6: Greyscale
    @LargeTest
    public void testGreyscale() {
        TestAction ta = new TestAction(TestName.GREYSCALE);
        runTest(ta, TestName.GREYSCALE.name());
    }

    // Test case 7: Grain
    @LargeTest
    public void testGrain() {
        TestAction ta = new TestAction(TestName.GRAIN);
        runTest(ta, TestName.GRAIN.name());
    }

    // Test case 8: Fisheye Full
    @LargeTest
    public void testFisheyeFull() {
        TestAction ta = new TestAction(TestName.FISHEYE_FULL);
        runTest(ta, TestName.FISHEYE_FULL.name());
    }

    // Test case 9: Fisheye Relaxed
    @LargeTest
    public void testFishEyeRelaxed() {
        TestAction ta = new TestAction(TestName.FISHEYE_RELAXED);
        runTest(ta, TestName.FISHEYE_RELAXED.name());
    }

    // Test case 10: Fisheye Approximate Full
    @LargeTest
    public void testFisheyeApproximateFull() {
        TestAction ta = new TestAction(TestName.FISHEYE_APPROXIMATE_FULL);
        runTest(ta, TestName.FISHEYE_APPROXIMATE_FULL.name());
    }

    // Test case 11: Fisheye Approximate Relaxed
    @LargeTest
    public void testFisheyeApproximateRelaxed() {
        TestAction ta = new TestAction(TestName.FISHEYE_APPROXIMATE_RELAXED);
        runTest(ta, TestName.FISHEYE_APPROXIMATE_RELAXED.name());
    }

    // Test case 12: Vignette Full
    @LargeTest
    public void testVignetteFull() {
        TestAction ta = new TestAction(TestName.VIGNETTE_FULL);
        runTest(ta, TestName.VIGNETTE_FULL.name());
    }

    // Test case 13: Vignette Relaxed
    @LargeTest
    public void testVignetteRelaxed() {
        TestAction ta = new TestAction(TestName.VIGNETTE_RELAXED);
        runTest(ta, TestName.VIGNETTE_RELAXED.name());
    }

    // Test case 14: Vignette Approximate Full
    @LargeTest
    public void testVignetteApproximateFull() {
        TestAction ta = new TestAction(TestName.VIGNETTE_APPROXIMATE_FULL);
        runTest(ta, TestName.VIGNETTE_APPROXIMATE_FULL.name());
    }

    // Test case 15: Vignette Approximate Relaxed
    @LargeTest
    public void testVignetteApproximateRelaxed() {
        TestAction ta = new TestAction(TestName.VIGNETTE_APPROXIMATE_RELAXED);
        runTest(ta, TestName.VIGNETTE_APPROXIMATE_RELAXED.name());
    }

    // Test case 16: Group Test (emulated)
    @LargeTest
    public void testGroupTestEmulated() {
        TestAction ta = new TestAction(TestName.GROUP_TEST_EMULATED);
        runTest(ta, TestName.GROUP_TEST_EMULATED.name());
    }

    // Test case 17: Group Test (native)
    @LargeTest
    public void testGroupTestNative() {
        TestAction ta = new TestAction(TestName.GROUP_TEST_NATIVE);
        runTest(ta, TestName.GROUP_TEST_NATIVE.name());
    }

    // Test case 18: Convolve 3x3
    @LargeTest
    public void testConvolve3x3() {
        TestAction ta = new TestAction(TestName.CONVOLVE_3X3);
        runTest(ta, TestName.CONVOLVE_3X3.name());
    }

    // Test case 19: Intrinsics Convolve 3x3
    @LargeTest
    public void testIntrinsicsConvolve3x3() {
        TestAction ta = new TestAction(TestName.INTRINSICS_CONVOLVE_3X3);
        runTest(ta, TestName.INTRINSICS_CONVOLVE_3X3.name());
    }

    // Test case 20: ColorMatrix
    @LargeTest
    public void testColorMatrix() {
        TestAction ta = new TestAction(TestName.COLOR_MATRIX);
        runTest(ta, TestName.COLOR_MATRIX.name());
    }

    // Test case 21: Intrinsics ColorMatrix
    @LargeTest
    public void testIntrinsicsColorMatrix() {
        TestAction ta = new TestAction(TestName.INTRINSICS_COLOR_MATRIX);
        runTest(ta, TestName.INTRINSICS_COLOR_MATRIX.name());
    }

    // Test case 22: Intrinsics ColorMatrix Grey
    @LargeTest
    public void testIntrinsicsColorMatrixGrey() {
        TestAction ta = new TestAction(TestName.INTRINSICS_COLOR_MATRIX_GREY);
        runTest(ta, TestName.INTRINSICS_COLOR_MATRIX_GREY.name());
    }

    // Test case 23: Copy
    @LargeTest
    public void testCopy() {
        TestAction ta = new TestAction(TestName.COPY);
        runTest(ta, TestName.COPY.name());
    }

    // Test case 24: CrossProcess (using LUT)
    @LargeTest
    public void testCrossProcessUsingLUT() {
        TestAction ta = new TestAction(TestName.CROSS_PROCESS_USING_LUT);
        runTest(ta, TestName.CROSS_PROCESS_USING_LUT.name());
    }

    // Test case 25: Convolve 5x5
    @LargeTest
    public void testConvolve5x5() {
        TestAction ta = new TestAction(TestName.CONVOLVE_5X5);
        runTest(ta, TestName.CONVOLVE_5X5.name());
    }

    // Test case 26: Intrinsics Convolve 5x5
    @LargeTest
    public void testIntrinsicsConvolve5x5() {
        TestAction ta = new TestAction(TestName.INTRINSICS_CONVOLVE_5X5);
        runTest(ta, TestName.INTRINSICS_CONVOLVE_5X5.name());
    }

    // Test case 27: Mandelbrot
    @LargeTest
    public void testMandelbrot() {
        TestAction ta = new TestAction(TestName.MANDELBROT);
        runTest(ta, TestName.MANDELBROT.name());
    }

    // Test case 28
    @LargeTest
    public void testIntrinsicsBlend() {
        TestAction ta = new TestAction(TestName.INTRINSICS_BLEND);
        runTest(ta, TestName.INTRINSICS_BLEND.name());
    }
}
