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
import java.lang.Math;

public class ImageProcessingActivity extends Activity implements SurfaceHolder.Callback {
    private Bitmap mBitmapIn;
    private Bitmap mBitmapOut;
    private ScriptC_Threshold mScript;
    private float mThreshold = 0.5f;

    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript mRS;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Type mPixelType;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mInPixelsAllocation;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mOutPixelsAllocation;

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
    int out[];
    private void javaFilter() {
        final int w = mBitmapIn.getWidth();
        final int h = mBitmapIn.getHeight();
        final int count = w * h;

        if (in == null) {
            in = new int[count];
            out = new int[count];
            mBitmapIn.getPixels(in, 0, w, 0, 0, w, h);
        }

        int threshold = (int)(mThreshold * 255.f) * 255;
        //long t = java.lang.System.currentTimeMillis();

        for (int i = 0; i < count; i++) {
            final int luminance = 54 * ((in[i] >> 0) & 0xff) +
                                  182* ((in[i] >> 8) & 0xff) +
                                  18 * ((in[i] >> 16) & 0xff);
            if (luminance > threshold) {
                out[i] = in[i];
            } else {
                out[i] = in[i] & 0xff000000;
            }
        }
        //t = java.lang.System.currentTimeMillis() - t;
        //android.util.Log.v("Img", "frame time ms " + t);
        mBitmapOut.setPixels(out, 0, w, 0, 0, w, h);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mBitmapIn = loadBitmap(R.drawable.data);
        mBitmapOut = loadBitmap(R.drawable.data);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);

        mDisplayView = (ImageView) findViewById(R.id.display);
        mDisplayView.setImageBitmap(mBitmapOut);

        ((SeekBar) findViewById(R.id.threshold)).setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mThreshold = progress / 100.0f;
                    mScript.set_threshold(mThreshold);

                    long t = java.lang.System.currentTimeMillis();
                    if (true) {
                        mScript.invokable_Filter();
                    } else {
                        javaFilter();
                        mDisplayView.invalidate();
                    }
                    t = java.lang.System.currentTimeMillis() - t;
                    android.util.Log.v("Img", "frame time core ms " + t);
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    public void surfaceCreated(SurfaceHolder holder) {
        createScript();
        mScript.invokable_Filter();
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

        mScript = new ScriptC_Threshold(mRS, getResources(), false);
        mScript.set_width(mBitmapIn.getWidth());
        mScript.set_height(mBitmapIn.getHeight());
        mScript.set_threshold(mThreshold);
        mScript.bind_InPixel(mInPixelsAllocation);
        mScript.bind_OutPixel(mOutPixelsAllocation);
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
}
