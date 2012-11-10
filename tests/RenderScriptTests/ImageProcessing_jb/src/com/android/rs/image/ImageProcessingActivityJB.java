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
import android.renderscript.ScriptC;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Script;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import java.lang.Math;

import android.os.Environment;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class ImageProcessingActivityJB extends Activity
                                       implements SeekBar.OnSeekBarChangeListener {
    private final String TAG = "Img";
    private final String RESULT_FILE = "image_processing_result.csv";

    Bitmap mBitmapIn;
    Bitmap mBitmapIn2;
    Bitmap mBitmapOut;
    String mTestNames[];

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
        @Override
        public void handleMessage(Message msg) {
            mTest.updateBitmap(mBitmapOut);
            mDisplayView.invalidate();

            android.util.Log.v("Img", "mRunCount hdl " + mRunCount);
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


    void changeTest(int testID) {
        if (mTest != null) {
            mTest.destroy();
        }
        switch(testID) {
        case 0:
            mTest = new LevelsV4(false, false);
            break;
        case 1:
            mTest = new LevelsV4(false, true);
            break;
        case 2:
            mTest = new LevelsV4(true, false);
            break;
        case 3:
            mTest = new LevelsV4(true, true);
            break;
        case 4:
            mTest = new Blur25();
            break;
        case 5:
            mTest = new Greyscale();
            break;
        case 6:
            mTest = new Grain();
            break;
        case 7:
            mTest = new Vignette(false);
            break;
        case 8:
            mTest = new Vignette(true);
            break;
        case 9:
            mTest = new Convolve3x3();
            break;
        case 10:
            mTest = new ColorMatrix(false);
            break;
        case 11:
            mTest = new Copy();
            break;
        case 12:
            mTest = new Convolve5x5();
            break;
        case 13:
            mTest = new Mandelbrot();
            break;
        }

        mTest.createBaseTest(this, mBitmapIn, mBitmapIn2);
        setupBars();

        mTest.runTest();
        updateDisplay();
        mBenchmarkResult.setText("Result: not run");
    }

    void setupTests() {
        mTestNames = new String[14];
        mTestNames[0] = "Levels Vec3 Relaxed";
        mTestNames[1] = "Levels Vec4 Relaxed";
        mTestNames[2] = "Levels Vec3 Full";
        mTestNames[3] = "Levels Vec4 Full";
        mTestNames[4] = "Blur radius 25";
        mTestNames[5] = "Greyscale";
        mTestNames[6] = "Grain";
        mTestNames[7] = "Vignette Full";
        mTestNames[8] = "Vignette Relaxed";
        mTestNames[9] = "Convolve 3x3";
        mTestNames[10] = "ColorMatrix";
        mTestNames[11] = "Copy";
        mTestNames[12] = "Convolve 5x5";
        mTestNames[13] = "Mandelbrot";

        mTestSpinner.setAdapter(new ArrayAdapter<String>(
            this, R.layout.spinner_layout, mTestNames));
    }

    private AdapterView.OnItemSelectedListener mTestSpinnerListener =
            new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                    changeTest(pos);
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

        setupTests();
        changeTest(0);
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
            for (int i = 0; i < mTestNames.length; i++ ) {
                changeTest(i);
                float t = getBenchmark();
                String s = new String("" + mTestNames[i] + ", " + t);
                rsWriter.write(s + "\n");
                Log.v(TAG, "Test " + s + "ms\n");
            }
            rsWriter.close();
        } catch (IOException e) {
            Log.v(TAG, "Unable to write result file " + e.getMessage());
        }
        changeTest(0);
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
