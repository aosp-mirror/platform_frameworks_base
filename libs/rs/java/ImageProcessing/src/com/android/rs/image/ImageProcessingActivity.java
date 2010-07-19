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
import java.lang.Math;

public class ImageProcessingActivity extends Activity
                                       implements SurfaceHolder.Callback,
                                       SeekBar.OnSeekBarChangeListener {
    private Bitmap mBitmapIn;
    private Bitmap mBitmapOut;
    private Bitmap mBitmapScratch;
    private ScriptC_Threshold mScript;
    private ScriptC_Vertical_blur mScriptVBlur;
    private ScriptC_Horizontal_blur mScriptHBlur;
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
    private Allocation mScratchPixelsAllocation;

    private SurfaceView mSurfaceView;
    private ImageView mDisplayView;

    class FilterCallback extends RenderScript.RSMessage {
        private Runnable mAction = new Runnable() {
            public void run() {
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

    private long javaFilter() {
        final int width = mBitmapIn.getWidth();
        final int height = mBitmapIn.getHeight();
        final int count = width * height;

        if (in == null) {
            in = new int[count];
            interm = new int[count];
            out = new int[count];
            gaussian = new float[MAX_RADIUS * 2 + 1];
            mBitmapIn.getPixels(in, 0, width, 0, 0, width, height);
        }

        long t = java.lang.System.currentTimeMillis();

        int w, h, r;

        float fRadius = (float)mRadius;
        int radius = (int)mRadius;

        // Compute gaussian weights for the blur
        // e is the euler's number
        float e = 2.718281828459045f;
        float pi = 3.1415926535897932f;
        // g(x) = ( 1 / sqrt( 2 * pi ) * sigma) * e ^ ( -x^2 / 2 * sigma^2 )
        // x is of the form [-radius .. 0 .. radius]
        // and sigma varies with radius.
        // Based on some experimental radius values and sigma's
        // we approximately fit sigma = f(radius) as
        // sigma = radius * 0.4  + 0.6
        // The larger the radius gets, the more our gaussian blur
        // will resemble a box blur since with large sigma
        // the gaussian curve begins to lose its shape
        float sigma = 0.4f * fRadius + 0.6f;
        // Now compute the coefficints
        // We will store some redundant values to save some math during
        // the blur calculations
        // precompute some values
        float coeff1 = 1.0f / (float)(Math.sqrt( 2.0f * pi ) * sigma);
        float coeff2 = - 1.0f / (2.0f * sigma * sigma);
        float normalizeFactor = 0.0f;
        float floatR = 0.0f;
        for(r = -radius; r <= radius; r ++) {
            floatR = (float)r;
            gaussian[r + radius] = coeff1 * (float)Math.pow(e, floatR * floatR * coeff2);
            normalizeFactor += gaussian[r + radius];
        }

        //Now we need to normalize the weights because all our coefficients need to add up to one
        normalizeFactor = 1.0f / normalizeFactor;
        for(r = -radius; r <= radius; r ++) {
            floatR = (float)r;
            gaussian[r + radius] *= normalizeFactor;
        }

        float blurredPixelR = 0.0f;
        float blurredPixelG = 0.0f;
        float blurredPixelB = 0.0f;
        float blurredPixelA = 0.0f;

        for(h = 0; h < height; h ++) {
            for(w = 0; w < width; w ++) {

                blurredPixelR = 0.0f;
                blurredPixelG = 0.0f;
                blurredPixelB = 0.0f;
                blurredPixelA = 0.0f;

                for(r = -radius; r <= radius; r ++) {
                    // Stepping left and right away from the pixel
                    int validW = w + r;
                    // Clamp to zero and width max() isn't exposed for ints yet
                    if(validW < 0) {
                        validW = 0;
                    }
                    if(validW > width - 1) {
                        validW = width - 1;
                    }

                    int input = in[h*width + validW];

                    int R = ((input >> 24) & 0xff);
                    int G = ((input >> 16) & 0xff);
                    int B = ((input >> 8) & 0xff);
                    int A = (input & 0xff);

                    float weight = gaussian[r + radius];

                    blurredPixelR += (float)(R)*weight;
                    blurredPixelG += (float)(G)*weight;
                    blurredPixelB += (float)(B)*weight;
                    blurredPixelA += (float)(A)*weight;
                }

                int R = (int)blurredPixelR;
                int G = (int)blurredPixelG;
                int B = (int)blurredPixelB;
                int A = (int)blurredPixelA;

                interm[h*width + w] = (R << 24) | (G << 16) | (B << 8) | (A);
            }
        }

        for(h = 0; h < height; h ++) {
            for(w = 0; w < width; w ++) {

                blurredPixelR = 0.0f;
                blurredPixelG = 0.0f;
                blurredPixelB = 0.0f;
                blurredPixelA = 0.0f;
                for(r = -radius; r <= radius; r ++) {
                    int validH = h + r;
                    // Clamp to zero and width
                    if(validH < 0) {
                        validH = 0;
                    }
                    if(validH > height - 1) {
                        validH = height - 1;
                    }

                    int input = interm[validH*width + w];

                    int R = ((input >> 24) & 0xff);
                    int G = ((input >> 16) & 0xff);
                    int B = ((input >> 8) & 0xff);
                    int A = (input & 0xff);

                    float weight = gaussian[r + radius];

                    blurredPixelR += (float)(R)*weight;
                    blurredPixelG += (float)(G)*weight;
                    blurredPixelB += (float)(B)*weight;
                    blurredPixelA += (float)(A)*weight;
                }

                int R = (int)blurredPixelR;
                int G = (int)blurredPixelG;
                int B = (int)blurredPixelB;
                int A = (int)blurredPixelA;

                out[h*width + w] = (R << 24) | (G << 16) | (B << 8) | (A);
            }
        }

        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("Img", "Java frame time ms " + t);
        mBitmapOut.setPixels(out, 0, width, 0, 0, width, height);
        return t;
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {

            if(seekBar == mRadiusSeekBar) {
                float fRadius = progress / 100.0f;
                fRadius *= (float)(MAX_RADIUS);
                mRadius = (int)fRadius;

                mScript.set_radius(mRadius);
            }
            else if(seekBar == mInBlackSeekBar) {
                mInBlack = (float)progress;
                mScript.set_inBlack(mInBlack);
            }
            else if(seekBar == mOutBlackSeekBar) {
                mOutBlack = (float)progress;
                mScript.set_outBlack(mOutBlack);
            }
            else if(seekBar == mInWhiteSeekBar) {
                mInWhite = (float)progress + 127.0f;
                mScript.set_inWhite(mInWhite);
            }
            else if(seekBar == mOutWhiteSeekBar) {
                mOutWhite = (float)progress + 127.0f;
                mScript.set_outWhite(mOutWhite);
            }
            else if(seekBar == mGammaSeekBar) {
                mGamma = (float)progress/100.0f;
                mGamma = Math.max(mGamma, 0.1f);
                mGamma = 1.0f / mGamma;
                mScript.set_gamma(mGamma);
            }
            else if(seekBar == mSaturationSeekBar) {
                mSaturation = (float)progress / 50.0f;
                mScript.set_saturation(mSaturation);
            }

            long t = java.lang.System.currentTimeMillis();
            if (true) {
                mScript.invoke_filter();
                mRS.finish();
            } else {
                javaFilter();
            }

            t = java.lang.System.currentTimeMillis() - t;
            android.util.Log.v("Img", "Renderscript frame time core ms " + t);

            mDisplayView.invalidate();
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

        mBitmapIn = loadBitmap(R.drawable.data);
        mBitmapOut = loadBitmap(R.drawable.data);
        mBitmapScratch = loadBitmap(R.drawable.data);

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
        mBenchmarkResult.setText("Benchmark no yet run");
    }

    public void surfaceCreated(SurfaceHolder holder) {
        createScript();
        mScript.invoke_filter();
        mRS.finish();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private void createScript() {
        mRS = RenderScript.create();
        mRS.mMessageCallback = new FilterCallback();

        mInPixelsAllocation = Allocation.createBitmapRef(mRS, mBitmapIn);
        mOutPixelsAllocation = Allocation.createBitmapRef(mRS, mBitmapOut);
        mScratchPixelsAllocation = Allocation.createBitmapRef(mRS, mBitmapScratch);

        mScriptVBlur = new ScriptC_Vertical_blur(mRS, getResources(), R.raw.vertical_blur_bc, false);
        mScriptHBlur = new ScriptC_Horizontal_blur(mRS, getResources(), R.raw.horizontal_blur_bc, false);

        mScript = new ScriptC_Threshold(mRS, getResources(), R.raw.threshold_bc, false);
        mScript.set_width(mBitmapIn.getWidth());
        mScript.set_height(mBitmapIn.getHeight());
        mScript.set_radius(mRadius);

        mScript.set_inBlack(mInBlack);
        mScript.set_outBlack(mOutBlack);
        mScript.set_inWhite(mInWhite);
        mScript.set_outWhite(mOutWhite);
        mScript.set_gamma(mGamma);
        mScript.set_saturation(mSaturation);

        mScript.bind_InPixel(mInPixelsAllocation);
        mScript.bind_OutPixel(mOutPixelsAllocation);
        mScript.bind_ScratchPixel(mScratchPixelsAllocation);

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
        android.util.Log.v("Img", "Benchmarking");
        int oldRadius = mRadius;
        mRadius = MAX_RADIUS;
        mScript.set_radius(mRadius);

        long t = java.lang.System.currentTimeMillis();

        mScript.invoke_filterBenchmark();
        mRS.finish();

        t = java.lang.System.currentTimeMillis() - t;
        android.util.Log.v("Img", "Renderscript frame time core ms " + t);

        long javaTime = javaFilter();
        mBenchmarkResult.setText("RS: " + t + " ms  Java: " + javaTime + " ms");
        //mBenchmarkResult.setText("RS: " + t + " ms");

        mRadius = oldRadius;
        mScript.set_radius(mRadius);

        mScript.invoke_filter();
        mRS.finish();
    }
}
