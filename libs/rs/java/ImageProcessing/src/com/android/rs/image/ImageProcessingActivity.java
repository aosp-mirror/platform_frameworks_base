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
    private Bitmap mBitmap;
    private Params mParams;
    private Script.Invokable mInvokable;
    private int[] mInData;
    private int[] mOutData;

    @SuppressWarnings({"FieldCanBeLocal"})
    private RenderScript mRS;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Type mParamsType;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mParamsAllocation;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Type mPixelType;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mInPixelsAllocation;
    @SuppressWarnings({"FieldCanBeLocal"})
    private Allocation mOutPixelsAllocation;

    private SurfaceView mSurfaceView;
    private ImageView mDisplayView;

    static class Params {
        public int inWidth;
        public int outWidth;
        public int inHeight;
        public int outHeight;

        public float threshold;
    }

    static class Pixel {
        public byte a;
        public byte r;
        public byte g;
        public byte b;
    }

    class FilterCallback extends RenderScript.RSMessage {
        private Runnable mAction = new Runnable() {
            public void run() {
                mOutPixelsAllocation.readData(mOutData);
                mBitmap.setPixels(mOutData, 0, mParams.outWidth, 0, 0,
                        mParams.outWidth, mParams.outHeight);
                mDisplayView.invalidate();
            }
        };

        @Override
        public void run() {
            mSurfaceView.removeCallbacks(mAction);
            mSurfaceView.post(mAction);
        }
    }

    private void javaFilter() {
        long t = java.lang.System.currentTimeMillis();
        int count = mParams.inWidth * mParams.inHeight;
        float threshold = mParams.threshold * 255.f;

        for (int i = 0; i < count; i++) {
            final float r = (float)((mInData[i] >> 0) & 0xff);
            final float g = (float)((mInData[i] >> 8) & 0xff);
            final float b = (float)((mInData[i] >> 16) & 0xff);

            final float luminance = 0.2125f * r +
                              0.7154f * g +
                              0.0721f * b;
            if (luminance > threshold) {
                mOutData[i] = mInData[i];
            } else {
                mOutData[i] = mInData[i] & 0xff000000;
            }
        }

        t = java.lang.System.currentTimeMillis() - t;

        android.util.Log.v("Img", "frame time ms " + t);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mBitmap = loadBitmap(R.drawable.data);

        mSurfaceView = (SurfaceView) findViewById(R.id.surface);
        mSurfaceView.getHolder().addCallback(this);

        mDisplayView = (ImageView) findViewById(R.id.display);
        mDisplayView.setImageBitmap(mBitmap);

        ((SeekBar) findViewById(R.id.threshold)).setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    mParams.threshold = progress / 100.0f;
                    mParamsAllocation.data(mParams);

                    if (true) {
                        mInvokable.execute();
                    } else {
                        javaFilter();
                        mBitmap.setPixels(mOutData, 0, mParams.outWidth, 0, 0,
                                mParams.outWidth, mParams.outHeight);
                        mDisplayView.invalidate();
                    }
                }
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    public void surfaceCreated(SurfaceHolder holder) {
        mParams = createParams();
        mInvokable = createScript();

        mInvokable.execute();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    private Script.Invokable createScript() {
        mRS = RenderScript.create();
        mRS.mMessageCallback = new FilterCallback();

        mParamsType = Type.createFromClass(mRS, Params.class, 1, "Parameters");
        mParamsAllocation = Allocation.createTyped(mRS, mParamsType);
        mParamsAllocation.data(mParams);

        final int pixelCount = mParams.inWidth * mParams.inHeight;

        mPixelType = Type.createFromClass(mRS, Pixel.class, 1, "Pixel");
        mInPixelsAllocation = Allocation.createSized(mRS,
                Element.createUser(mRS, Element.DataType.SIGNED_32),
                pixelCount);
        mOutPixelsAllocation = Allocation.createSized(mRS,
                Element.createUser(mRS, Element.DataType.SIGNED_32),
                pixelCount);

        mInData = new int[pixelCount];
        mBitmap.getPixels(mInData, 0, mParams.inWidth, 0, 0, mParams.inWidth, mParams.inHeight);
        mInPixelsAllocation.data(mInData);

        mOutData = new int[pixelCount];
        mOutPixelsAllocation.data(mOutData);

        ScriptC.Builder sb = new ScriptC.Builder(mRS);
        sb.setType(mParamsType, "Params", 0);
        sb.setType(mPixelType, "InPixel", 1);
        sb.setType(mPixelType, "OutPixel", 2);
        sb.setType(true, 2);
        Script.Invokable invokable = sb.addInvokable("main");
        sb.setScript(getResources(), R.raw.threshold);
        //sb.setRoot(true);

        ScriptC script = sb.create();
        script.bindAllocation(mParamsAllocation, 0);
        script.bindAllocation(mInPixelsAllocation, 1);
        script.bindAllocation(mOutPixelsAllocation, 2);

        return invokable;
    }

    private Params createParams() {
        final Params params = new Params();
        params.inWidth = params.outWidth = mBitmap.getWidth();
        params.inHeight = params.outHeight = mBitmap.getHeight();
        params.threshold = 0.5f;
        return params;
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
