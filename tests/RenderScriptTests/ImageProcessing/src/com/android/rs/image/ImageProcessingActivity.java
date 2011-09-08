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

package com.android.rs.image;

import android.app.Activity;
import android.os.Bundle;
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
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.view.View;
import android.util.Log;
import java.lang.Math;

public class ImageProcessingActivity extends Activity
                                       implements SurfaceHolder.Callback,
                                       SeekBar.OnSeekBarChangeListener {
    private final String TAG = "Img";
    private Bitmap mBitmapIn;
    private Bitmap mBitmapOut;
    private ScriptC_threshold mScript;
    private ScriptC_vertical_blur mScriptVBlur;
    private ScriptC_horizontal_blur mScriptHBlur;
    private int mRadius = 0;
    private SeekBar mRadiusSeekBar;

    private float mInBlack = 0.0f;
    private SeekBar mInBlackSeekBar;
    private float mOutBlack = 0.0f;
    private SeekBar mOutBlackSeekBar;
    private float mInWhite = 255.0f;
    private SeekBar mInWhiteSeekBar;
    private float mOutWhite = 255.0f;
    private SeekBar mOutWhiteSeekBar;
    private float mGamma = 1.0f;
    private SeekBar mGammaSeekBar;

    private float mSaturation = 1.0f;
    private SeekBar mSaturationSeekBar;

    private TextView mBenchmarkResult;

    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript mRS;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Type mPixelType;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mInPixelsAllocation;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mOutPixelsAllocation;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mScratchPixelsAllocation1;
    private Allocation mScratchPixelsAllocation2;

    private SurfaceView mSurfaceView;
    private ImageView mDisplayView;

    private boolean mIsProcessing;

    class FilterCallback extends RenderScript.RSMessageHandler {
        private Runnable mAction = new Runnable() {
            public void run() {

                synchronized (mDisplayView) {
                    mIsProcessing = false;
                }

                mOutPixelsAllocation.copyTo(mBitmapOut);
                mDisplayView.invalidate();
            }
        };

        @Override
        public void run() {
            mSurfaceView.removeCallbacks(mAction);
            mSurfaceView.post(mAction);
        }
    }

    int in[];
    int interm[];
    int out[];
    int MAX_RADIUS = 25;
    // Store our coefficients here
    float gaussian[];

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {

            if (seekBar == mRadiusSeekBar) {
                float fRadius = progress / 100.0f;
                fRadius *= (float)(MAX_RADIUS);
                mRadius = (int)fRadius;

                mScript.set_radius(mRadius);
            } else if (seekBar == mInBlackSeekBar) {
                mInBlack = (float)progress;
                mScriptVBlur.invoke_setLevels(mInBlack, mOutBlack, mInWhite, mOutWhite);
            } else if (seekBar == mOutBlackSeekBar) {
                mOutBlack = (float)progress;
                mScriptVBlur.invoke_setLevels(mInBlack, mOutBlack, mInWhite, mOutWhite);
            } else if (seekBar == mInWhiteSeekBar) {
                mInWhite = (float)progress + 127.0f;
                mScriptVBlur.invoke_setLevels(mInBlack, mOutBlack, mInWhite, mOutWhite);
            } else if (seekBar == mOutWhiteSeekBar) {
                mOutWhite = (float)progress + 127.0f;
                mScriptVBlur.invoke_setLevels(mInBlack, mOutBlack, mInWhite, mOutWhite);
            } else if (seekBar == mGammaSeekBar) {
                mGamma = (float)progress/100.0f;
                mGamma = Math.max(mGamma, 0.1f);
                mGamma = 1.0f / mGamma;
                mScriptVBlur.invoke_setGamma(mGamma);
            } else if (seekBar == mSaturationSeekBar) {
                mSaturation = (float)progress / 50.0f;
                mScriptVBlur.invoke_setSaturation(mSaturation);
            }

            synchronized (mDisplayView) {
                if (mIsProcessing) {
                    return;
                }
                mIsProcessing = true;
            }

            mScript.invoke_filter();
        }
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mBitmapIn = loadBitmap(R.drawable.city);
        mBitmapOut = loadBitmap(R.drawable.city);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);

        mDisplayView = (ImageView) findViewById(R.id.display);
        mDisplayView.setImageBitmap(mBitmapOut);

        mRadiusSeekBar = (SeekBar) findViewById(R.id.radius);
        mRadiusSeekBar.setOnSeekBarChangeListener(this);

        mInBlackSeekBar = (SeekBar)findViewById(R.id.inBlack);
        mInBlackSeekBar.setOnSeekBarChangeListener(this);
        mInBlackSeekBar.setMax(128);
        mInBlackSeekBar.setProgress(0);
        mOutBlackSeekBar = (SeekBar)findViewById(R.id.outBlack);
        mOutBlackSeekBar.setOnSeekBarChangeListener(this);
        mOutBlackSeekBar.setMax(128);
        mOutBlackSeekBar.setProgress(0);

        mInWhiteSeekBar = (SeekBar)findViewById(R.id.inWhite);
        mInWhiteSeekBar.setOnSeekBarChangeListener(this);
        mInWhiteSeekBar.setMax(128);
        mInWhiteSeekBar.setProgress(128);
        mOutWhiteSeekBar = (SeekBar)findViewById(R.id.outWhite);
        mOutWhiteSeekBar.setOnSeekBarChangeListener(this);
        mOutWhiteSeekBar.setMax(128);
        mOutWhiteSeekBar.setProgress(128);

        mGammaSeekBar = (SeekBar)findViewById(R.id.inGamma);
        mGammaSeekBar.setOnSeekBarChangeListener(this);
        mGammaSeekBar.setMax(150);
        mGammaSeekBar.setProgress(100);

        mSaturationSeekBar = (SeekBar)findViewById(R.id.inSaturation);
        mSaturationSeekBar.setOnSeekBarChangeListener(this);
        mSaturationSeekBar.setProgress(50);

        mBenchmarkResult = (TextView) findViewById(R.id.benchmarkText);
        mBenchmarkResult.setText("Result: not run");
    }

    public void surfaceCreated(SurfaceHolder holder) {
        createScript();
        mScript.invoke_filter();
        mOutPixelsAllocation.copyTo(mBitmapOut);
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void createScript() {
        mRS = RenderScript.create(this);
        mRS.setMessageHandler(new FilterCallback());

        mInPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapIn,
                                                          Allocation.MipmapControl.MIPMAP_NONE,
                                                          Allocation.USAGE_SCRIPT);
        mOutPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapOut,
                                                           Allocation.MipmapControl.MIPMAP_NONE,
                                                           Allocation.USAGE_SCRIPT);

        Type.Builder tb = new Type.Builder(mRS, Element.F32_4(mRS));
        tb.setX(mBitmapIn.getWidth());
        tb.setY(mBitmapIn.getHeight());
        mScratchPixelsAllocation1 = Allocation.createTyped(mRS, tb.create());
        mScratchPixelsAllocation2 = Allocation.createTyped(mRS, tb.create());

        mScriptVBlur = new ScriptC_vertical_blur(mRS, getResources(), R.raw.vertical_blur);
        mScriptHBlur = new ScriptC_horizontal_blur(mRS, getResources(), R.raw.horizontal_blur);

        mScript = new ScriptC_threshold(mRS, getResources(), R.raw.threshold);
        mScript.set_width(mBitmapIn.getWidth());
        mScript.set_height(mBitmapIn.getHeight());
        mScript.set_radius(mRadius);

        mScriptVBlur.invoke_setLevels(mInBlack, mOutBlack, mInWhite, mOutWhite);
        mScriptVBlur.invoke_setGamma(mGamma);
        mScriptVBlur.invoke_setSaturation(mSaturation);

        mScript.bind_InPixel(mInPixelsAllocation);
        mScript.bind_OutPixel(mOutPixelsAllocation);
        mScript.bind_ScratchPixel1(mScratchPixelsAllocation1);
        mScript.bind_ScratchPixel2(mScratchPixelsAllocation2);

        mScript.set_vBlurScript(mScriptVBlur);
        mScript.set_hBlurScript(mScriptHBlur);
    }

    private Bitmap loadBitmap(int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        return copyBitmap(BitmapFactory.decodeResource(getResources(), resource, options));
    }

    private static Bitmap copyBitmap(Bitmap source) {
        Bitmap b = Bitmap.createBitmap(source.getWidth(), source.getHeight(), source.getConfig());
        Canvas c = new Canvas(b);
        c.drawBitmap(source, 0, 0, null);
        source.recycle();
        return b;
    }

    // button hook
    public void benchmark(View v) {
        long t = getBenchmark();
        //long javaTime = javaFilter();
        //mBenchmarkResult.setText("RS: " + t + " ms  Java: " + javaTime + " ms");
        mBenchmarkResult.setText("Result: " + t + " ms");
    }

    // For benchmark test
    public long getBenchmark() {
        Log.v(TAG, "Benchmarking");
        int oldRadius = mRadius;
        mRadius = MAX_RADIUS;
        mScript.set_radius(mRadius);

        long t = java.lang.System.currentTimeMillis();

        mScript.invoke_filter();
        mOutPixelsAllocation.copyTo(mBitmapOut);

        t = java.lang.System.currentTimeMillis() - t;
        Log.v(TAG, "getBenchmark: Renderscript frame time core ms " + t);
        mRadius = oldRadius;
        mScript.set_radius(mRadius);

        mScript.invoke_filter();
        mOutPixelsAllocation.copyTo(mBitmapOut);
        return t;
    }
}
