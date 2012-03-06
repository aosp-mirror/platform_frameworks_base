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
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

public class SampleRSActivity extends Activity
                              implements TextureView.SurfaceTextureListener
{
    private final String TAG = "Img";
    private Bitmap mBitmapIn;
    private TextureView mDisplayView;

    private TextView mBenchmarkResult;

    private RenderScript mRS;
    private Allocation mInPixelsAllocation;
    private Allocation mOutPixelsAllocation;
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

        mOutPixelsAllocation = Allocation.createTyped(mRS, b.setX(32).setY(32).create(),
                                                      Allocation.USAGE_SCRIPT |
                                                      Allocation.USAGE_IO_OUTPUT);
        mDisplayView.setSurfaceTextureListener(this);

        mScript = new ScriptC_sample(mRS, getResources(), R.raw.sample);

        mScript.set_sourceAlloc(mInPixelsAllocation);
        mScript.set_destAlloc(mOutPixelsAllocation);
        mScript.set_wrapUV(Sampler.WRAP_LINEAR(mRS));
        mScript.set_clampUV(Sampler.CLAMP_LINEAR(mRS));
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

    private void filter() {
        long t = java.lang.System.currentTimeMillis();
        mScript.forEach_root(mOutPixelsAllocation);
        mOutPixelsAllocation.ioSendOutput();
        mRS.finish();
        t = java.lang.System.currentTimeMillis() - t;
        Log.i(TAG, "Filter time is: " + t + " ms");
    }

    public void benchmark(View v) {
        filter();
        long t = java.lang.System.currentTimeMillis();
        filter();
        t = java.lang.System.currentTimeMillis() - t;
        mDisplayView.invalidate();
        mBenchmarkResult.setText("Result: " + t + " ms");
    }


    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mOutPixelsAllocation.setSurfaceTexture(surface);
        filter();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        mOutPixelsAllocation.setSurfaceTexture(surface);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mOutPixelsAllocation.setSurfaceTexture(null);
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
