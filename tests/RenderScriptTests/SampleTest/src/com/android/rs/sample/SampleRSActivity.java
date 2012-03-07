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

package com.android.rs.sample;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Matrix3f;
import android.renderscript.RenderScript;
import android.renderscript.Sampler;
import android.renderscript.Type;
import android.renderscript.Type.Builder;
import android.util.Log;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SampleRSActivity extends Activity {
    class TextureViewUpdater implements TextureView.SurfaceTextureListener {
        private Allocation mOutPixelsAllocation;
        private Sampler mSampler;

        TextureViewUpdater(Allocation outAlloc, Sampler sampler) {
            mOutPixelsAllocation = outAlloc;
            mSampler = sampler;
        }

        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }

        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            mOutPixelsAllocation.setSurfaceTexture(surface);
        }

        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            mOutPixelsAllocation.setSurfaceTexture(surface);
            filterAlloc(mOutPixelsAllocation, mSampler);
        }

        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            mOutPixelsAllocation.setSurfaceTexture(null);
            return true;
        }
    }

    private final String TAG = "Img";
    private Bitmap mBitmapIn;
    private TextureView mDisplayView;

    private TextView mBenchmarkResult;

    private RenderScript mRS;
    private Allocation mInPixelsAllocation;
    private ScriptC_sample mScript;

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.rs);

        mBitmapIn = loadBitmap(R.drawable.twobytwo);
        mDisplayView = (TextureView) findViewById(R.id.display);

        mBenchmarkResult = (TextView) findViewById(R.id.benchmarkText);
        mBenchmarkResult.setText("Result: not run");

        mRS = RenderScript.create(this);
        mInPixelsAllocation = Allocation.createFromBitmap(mRS, mBitmapIn,
                                                          Allocation.MipmapControl.MIPMAP_NONE,
                                                          Allocation.USAGE_SCRIPT);

        Type.Builder b = new Type.Builder(mRS, Element.RGBA_8888(mRS));

        int usage = Allocation.USAGE_SCRIPT | Allocation.USAGE_IO_OUTPUT;

        int outX = 32;
        int outY = 32;

        // Wrap Linear
        Allocation outAlloc = Allocation.createTyped(mRS, b.setX(outX).setY(outY).create(), usage);
        TextureViewUpdater updater = new TextureViewUpdater(outAlloc, Sampler.WRAP_LINEAR(mRS));
        TextureView displayView = (TextureView) findViewById(R.id.display);
        displayView.setSurfaceTextureListener(updater);

        // Clamp Linear
        outAlloc = Allocation.createTyped(mRS, b.setX(outX).setY(outY).create(), usage);
        updater = new TextureViewUpdater(outAlloc, Sampler.CLAMP_LINEAR(mRS));
        displayView = (TextureView) findViewById(R.id.display2);
        displayView.setSurfaceTextureListener(updater);

        // Wrap Nearest
        outAlloc = Allocation.createTyped(mRS, b.setX(outX).setY(outY).create(), usage);
        updater = new TextureViewUpdater(outAlloc, Sampler.WRAP_NEAREST(mRS));
        displayView = (TextureView) findViewById(R.id.display3);
        displayView.setSurfaceTextureListener(updater);

        // Clamp Nearest
        outAlloc = Allocation.createTyped(mRS, b.setX(outX).setY(outY).create(), usage);
        updater = new TextureViewUpdater(outAlloc, Sampler.CLAMP_NEAREST(mRS));
        displayView = (TextureView) findViewById(R.id.display4);
        displayView.setSurfaceTextureListener(updater);

        mScript = new ScriptC_sample(mRS, getResources(), R.raw.sample);
    }

    private Bitmap loadBitmap(int resource) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap b = BitmapFactory.decodeResource(getResources(), resource, options);
        Bitmap b2 = Bitmap.createBitmap(b.getWidth(), b.getHeight(), b.getConfig());
        Canvas c = new Canvas(b2);
        c.drawBitmap(b, 0, 0, null);
        b.recycle();
        return b2;
    }

    private synchronized void filterAlloc(Allocation alloc, Sampler sampler) {
        long t = java.lang.System.currentTimeMillis();
        mScript.invoke_setSampleData(alloc, mInPixelsAllocation, sampler);
        mScript.forEach_root(alloc);
        alloc.ioSendOutput();
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        Log.i(TAG, "Filter time is: " + t + " ms");
    }

    public void benchmark(View v) {
        /*filterAlloc();
        long t = java.lang.System.currentTimeMillis();
        filterAlloc();
        t = java.lang.System.currentTimeMillis() - t;
        mDisplayView.invalidate();
        mBenchmarkResult.setText("Result: " + t + " ms");*/
    }
}
