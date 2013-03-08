/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.rs.imagejb;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.graphics.BitmapFactory;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.SurfaceView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;

import android.os.Environment;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ImageProcessingActivityJB extends Activity
                                       implements SeekBar.OnSeekBarChangeListener {
    private final String TAG = "Img";
    public final String RESULT_FILE = "image_processing_result.csv";

    RenderScript mRS;
    Allocation mInPixelsAllocation;
    Allocation mInPixelsAllocation2;
    Allocation mOutPixelsAllocation;

    /**
     * Define enum type for test names
     */
    public enum TestName {
        // totally there are 38 test cases
        LEVELS_VEC3_RELAXED ("Levels Vec3 Relaxed"),
        LEVELS_VEC4_RELAXED ("Levels Vec4 Relaxed"),
        LEVELS_VEC3_FULL ("Levels Vec3 Full"),
        LEVELS_VEC4_FULL ("Levels Vec4 Full"),
        BLUR_RADIUS_25 ("Blur radius 25"),
        INTRINSIC_BLUE_RADIUS_25 ("Intrinsic Blur radius 25"),
        GREYSCALE ("Greyscale"),
        GRAIN ("Grain"),
        FISHEYE_FULL ("Fisheye Full"),
        FISHEYE_RELAXED ("Fisheye Relaxed"),
        FISHEYE_APPROXIMATE_FULL ("Fisheye Approximate Full"),
        FISHEYE_APPROXIMATE_RELAXED ("Fisheye Approximate Relaxed"),
        VIGNETTE_FULL ("Vignette Full"),
        VIGNETTE_RELAXED ("Vignette Relaxed"),
        VIGNETTE_APPROXIMATE_FULL ("Vignette Approximate Full"),
        VIGNETTE_APPROXIMATE_RELAXED ("Vignette Approximate Relaxed"),
        GROUP_TEST_EMULATED ("Group Test (emulated)"),
        GROUP_TEST_NATIVE ("Group Test (native)"),
        CONVOLVE_3X3 ("Convolve 3x3"),
        INTRINSICS_CONVOLVE_3X3 ("Intrinsics Convolve 3x3"),
        COLOR_MATRIX ("ColorMatrix"),
        INTRINSICS_COLOR_MATRIX ("Intrinsics ColorMatrix"),
        INTRINSICS_COLOR_MATRIX_GREY ("Intrinsics ColorMatrix Grey"),
        COPY ("Copy"),
        CROSS_PROCESS_USING_LUT ("CrossProcess (using LUT)"),
        CONVOLVE_5X5 ("Convolve 5x5"),
        INTRINSICS_CONVOLVE_5X5 ("Intrinsics Convolve 5x5"),
        MANDELBROT ("Mandelbrot"),
        INTRINSICS_BLEND ("Intrinsics Blend"),
        VIBRANCE ("Vibrance"),
        BW_FILTER ("BW Filter"),
        SHADOWS ("Shadows"),
        CONTRAST ("Contrast"),
        EXPOSURE ("Exposure"),
        WHITE_BALANCE ("White Balance");


        private final String name;

        private TestName(String s) {
            name = s;
        }

        // return quoted string as displayed test name
        public String toString() {
            return name;
        }
    }

    Bitmap mBitmapIn;
    Bitmap mBitmapIn2;
    Bitmap mBitmapOut;

    private Spinner mSpinner;
    private SeekBar mBar1;
    private SeekBar mBar2;
    private SeekBar mBar3;
    private SeekBar mBar4;
    private SeekBar mBar5;
    private TextView mText1;
    private TextView mText2;
    private TextView mText3;
    private TextView mText4;
    private TextView mText5;

    private float mSaturation = 1.0f;

    private TextView mBenchmarkResult;
    private Spinner mTestSpinner;

    private SurfaceView mSurfaceView;
    private ImageView mDisplayView;

    private boolean mDoingBenchmark;

    private TestBase mTest;
    private int mRunCount;

    public void updateDisplay() {
        mHandler.sendMessage(Message.obtain());
    }

    private Handler mHandler = new Handler() {
        // Allow the filter to complete without blocking the UI
        // thread.  When the message arrives that the op is complete
        // we will either mark completion or start a new filter if
        // more work is ready.  Either way, display the result.
        @Override
        public void handleMessage(Message msg) {
            mTest.updateBitmap(mBitmapOut);
            mDisplayView.invalidate();

            boolean doTest = false;
            synchronized(this) {
                if (mRunCount > 0) {
                    mRunCount--;
                    if (mRunCount > 0) {
                        doTest = true;
                    }
                }
            }
            if (doTest) {
                mTest.runTestSendMessage();
            }
        }

    };

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {

            if (seekBar == mBar1) {
                mTest.onBar1Changed(progress);
            } else if (seekBar == mBar2) {
                mTest.onBar2Changed(progress);
            } else if (seekBar == mBar3) {
                mTest.onBar3Changed(progress);
            } else if (seekBar == mBar4) {
                mTest.onBar4Changed(progress);
            } else if (seekBar == mBar5) {
                mTest.onBar5Changed(progress);
            }

            boolean doTest = false;
            synchronized(this) {
                if (mRunCount == 0) {
                    doTest = true;
                    mRunCount = 1;
                } else {
                    mRunCount = 2;
                }
            }
            if (doTest) {
                mTest.runTestSendMessage();
            }
        }
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    void setupBars() {
        mSpinner.setVisibility(View.VISIBLE);
        mTest.onSpinner1Setup(mSpinner);

        mBar1.setVisibility(View.VISIBLE);
        mText1.setVisibility(View.VISIBLE);
        mTest.onBar1Setup(mBar1, mText1);

        mBar2.setVisibility(View.VISIBLE);
        mText2.setVisibility(View.VISIBLE);
        mTest.onBar2Setup(mBar2, mText2);

        mBar3.setVisibility(View.VISIBLE);
        mText3.setVisibility(View.VISIBLE);
        mTest.onBar3Setup(mBar3, mText3);

        mBar4.setVisibility(View.VISIBLE);
        mText4.setVisibility(View.VISIBLE);
        mTest.onBar4Setup(mBar4, mText4);

        mBar5.setVisibility(View.VISIBLE);
        mText5.setVisibility(View.VISIBLE);
        mTest.onBar5Setup(mBar5, mText5);
    }


    void changeTest(TestName testName) {
        if (mTest != null) {
            mTest.destroy();
        }
        switch(testName) {
        case LEVELS_VEC3_RELAXED:
            mTest = new LevelsV4(false, false);
            break;
        case LEVELS_VEC4_RELAXED:
            mTest = new LevelsV4(false, true);
            break;
        case LEVELS_VEC3_FULL:
            mTest = new LevelsV4(true, false);
            break;
        case LEVELS_VEC4_FULL:
            mTest = new LevelsV4(true, true);
            break;
        case BLUR_RADIUS_25:
            mTest = new Blur25(false);
            break;
        case INTRINSIC_BLUE_RADIUS_25:
            mTest = new Blur25(true);
            break;
        case GREYSCALE:
            mTest = new Greyscale();
            break;
        case GRAIN:
            mTest = new Grain();
            break;
        case FISHEYE_FULL:
            mTest = new Fisheye(false, false);
            break;
        case FISHEYE_RELAXED:
            mTest = new Fisheye(false, true);
            break;
        case FISHEYE_APPROXIMATE_FULL:
            mTest = new Fisheye(true, false);
            break;
        case FISHEYE_APPROXIMATE_RELAXED:
            mTest = new Fisheye(true, true);
            break;
        case VIGNETTE_FULL:
            mTest = new Vignette(false, false);
            break;
        case VIGNETTE_RELAXED:
            mTest = new Vignette(false, true);
            break;
        case VIGNETTE_APPROXIMATE_FULL:
            mTest = new Vignette(true, false);
            break;
        case VIGNETTE_APPROXIMATE_RELAXED:
            mTest = new Vignette(true, true);
            break;
        case GROUP_TEST_EMULATED:
            mTest = new GroupTest(false);
            break;
        case GROUP_TEST_NATIVE:
            mTest = new GroupTest(true);
            break;
        case CONVOLVE_3X3:
            mTest = new Convolve3x3(false);
            break;
        case INTRINSICS_CONVOLVE_3X3:
            mTest = new Convolve3x3(true);
            break;
        case COLOR_MATRIX:
            mTest = new ColorMatrix(false, false);
            break;
        case INTRINSICS_COLOR_MATRIX:
            mTest = new ColorMatrix(true, false);
            break;
        case INTRINSICS_COLOR_MATRIX_GREY:
            mTest = new ColorMatrix(true, true);
            break;
        case COPY:
            mTest = new Copy();
            break;
        case CROSS_PROCESS_USING_LUT:
            mTest = new CrossProcess();
            break;
        case CONVOLVE_5X5:
            mTest = new Convolve5x5(false);
            break;
        case INTRINSICS_CONVOLVE_5X5:
            mTest = new Convolve5x5(true);
            break;
        case MANDELBROT:
            mTest = new Mandelbrot();
            break;
        case INTRINSICS_BLEND:
            mTest = new Blend();
            break;
        case VIBRANCE:
            mTest = new Vibrance();
            break;
        case BW_FILTER:
            mTest = new BWFilter();
            break;
        case SHADOWS:
            mTest = new Shadows();
            break;
        case CONTRAST:
            mTest = new Contrast();
            break;
        case EXPOSURE:
            mTest = new Exposure();
            break;
        case WHITE_BALANCE:
            mTest = new WhiteBalance();
            break;
        }

        mTest.createBaseTest(this, mBitmapIn, mBitmapIn2, mBitmapOut);
        setupBars();

        mTest.runTest();
        updateDisplay();
        mBenchmarkResult.setText("Result: not run");
    }

    void setupTests() {
        mTestSpinner.setAdapter(new ArrayAdapter<TestName>(
            this, R.layout.spinner_layout, TestName.values()));
    }

    private AdapterView.OnItemSelectedListener mTestSpinnerListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    changeTest(TestName.values()[pos]);
                }

                public void onNothingSelected(AdapterView parent) {

                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mBitmapIn = loadBitmap(R.drawable.img1600x1067);
        mBitmapIn2 = loadBitmap(R.drawable.img1600x1067b);
        mBitmapOut = Bitmap.createBitmap(mBitmapIn.getWidth(), mBitmapIn.getHeight(),
                                         mBitmapIn.getConfig());

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);

        mDisplayView = (ImageView) findViewById(R.id.display);
        mDisplayView.setImageBitmap(mBitmapOut);

        mSpinner = (Spinner) findViewById(R.id.spinner1);

        mBar1 = (SeekBar) findViewById(R.id.slider1);
        mBar2 = (SeekBar) findViewById(R.id.slider2);
        mBar3 = (SeekBar) findViewById(R.id.slider3);
        mBar4 = (SeekBar) findViewById(R.id.slider4);
        mBar5 = (SeekBar) findViewById(R.id.slider5);

        mBar1.setOnSeekBarChangeListener(this);
        mBar2.setOnSeekBarChangeListener(this);
        mBar3.setOnSeekBarChangeListener(this);
        mBar4.setOnSeekBarChangeListener(this);
        mBar5.setOnSeekBarChangeListener(this);

        mText1 = (TextView) findViewById(R.id.slider1Text);
        mText2 = (TextView) findViewById(R.id.slider2Text);
        mText3 = (TextView) findViewById(R.id.slider3Text);
        mText4 = (TextView) findViewById(R.id.slider4Text);
        mText5 = (TextView) findViewById(R.id.slider5Text);

        mTestSpinner = (Spinner) findViewById(R.id.filterselection);
        mTestSpinner.setOnItemSelectedListener(mTestSpinnerListener);

        mBenchmarkResult = (TextView) findViewById(R.id.benchmarkText);
        mBenchmarkResult.setText("Result: not run");


        mRS = RenderScript.create(this);
        mInPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapIn);
        mInPixelsAllocation2 = Allocation.createFromBitmap(mRS, mBitmapIn2);
        mOutPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapOut);


        setupTests();
        changeTest(TestName.LEVELS_VEC3_RELAXED);
    }


    private Bitmap loadBitmap(int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return BitmapFactory.decodeResource(getResources(), resource, options);
    }

    // button hook
    public void benchmark(View v) {
        float t = getBenchmark();
        //long javaTime = javaFilter();
        //mBenchmarkResult.setText("RS: " + t + " ms  Java: " + javaTime + " ms");
        mBenchmarkResult.setText("Result: " + t + " ms");
        Log.v(TAG, "getBenchmark: Renderscript frame time core ms " + t);
    }

    public void benchmark_all(View v) {
        // write result into a file
        File externalStorage = Environment.getExternalStorageDirectory();
        if (!externalStorage.canWrite()) {
            Log.v(TAG, "sdcard is not writable");
            return;
        }
        File resultFile = new File(externalStorage, RESULT_FILE);
        resultFile.setWritable(true, false);
        try {
            BufferedWriter rsWriter = new BufferedWriter(new FileWriter(resultFile));
            Log.v(TAG, "Saved results in: " + resultFile.getAbsolutePath());
            for (TestName tn: TestName.values()) {
                changeTest(tn);
                float t = getBenchmark();
                String s = new String("" + tn.toString() + ", " + t);
                rsWriter.write(s + "\n");
                Log.v(TAG, "Test " + s + "ms\n");
            }
            rsWriter.close();
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
        changeTest(TestName.LEVELS_VEC3_RELAXED);
    }

    // For benchmark test
    public float getBenchmark() {
        mDoingBenchmark = true;

        mTest.setupBenchmark();
        long result = 0;

        //Log.v(TAG, "Warming");
        long t = java.lang.System.currentTimeMillis() + 250;
        do {
            mTest.runTest();
            mTest.finish();
        } while (t > java.lang.System.currentTimeMillis());

        //Log.v(TAG, "Benchmarking");
        int ct = 0;
        t = java.lang.System.currentTimeMillis();
        do {
            mTest.runTest();
            mTest.finish();
            ct++;
        } while ((t+1000) > java.lang.System.currentTimeMillis());
        t = java.lang.System.currentTimeMillis() - t;
        float ft = (float)t;
        ft /= ct;

        mTest.exitBenchmark();
        mDoingBenchmark = false;

        return ft;
    }
}
