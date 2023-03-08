/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

@SuppressWarnings({"UnusedDeclaration"})
public class ColorBitmapActivity extends Activity implements SurfaceHolder.Callback,
        AdapterView.OnItemSelectedListener {

    private static final int WIDTH = 512;
    private static final int HEIGHT = 512;

    private ImageView mImageView;
    private SurfaceView mSurfaceView;
    private HardwareBuffer mGradientBuffer;
    private ImageWriter mImageWriter;
    private ColorSpace mColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
    private String[] mColorNames = {"sRGB", "BT2020_HLG", "BT2020_PQ"};
    private String mCurrentColorName = "sRGB";

    private FutureTask<HardwareBuffer> authorGradientBuffer(HardwareBuffer buffer) {
        HardwareBufferRenderer renderer = new HardwareBufferRenderer(buffer);
        RenderNode node = new RenderNode("content");
        node.setPosition(0, 0, buffer.getWidth(), buffer.getHeight());

        Canvas canvas = node.beginRecording();
        LinearGradient gradient = new LinearGradient(
                0, 0, buffer.getWidth(), buffer.getHeight(), 0xFF000000,
                0xFFFFFFFF, Shader.TileMode.CLAMP);
        Paint paint = new Paint();
        paint.setShader(gradient);
        canvas.drawRect(0f, 0f, buffer.getWidth(), buffer.getHeight(), paint);
        node.endRecording();

        renderer.setContentRoot(node);

        ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        FutureTask<HardwareBuffer> resolvedBuffer = new FutureTask<>(() -> buffer);
        renderer.obtainRenderRequest()
                .setColorSpace(colorSpace)
                .draw(Executors.newSingleThreadExecutor(), result -> {
                    result.getFence().await(Duration.ofSeconds(3));
                    resolvedBuffer.run();
                });
        return resolvedBuffer;
    }

    private FutureTask<HardwareBuffer> getGradientBuffer() {
        HardwareBuffer buffer = HardwareBuffer.create(
                WIDTH, HEIGHT, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        return authorGradientBuffer(buffer);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {

            mColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, mColorNames);

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            Spinner spinner = new Spinner(this);
            spinner.setAdapter(adapter);
            spinner.setOnItemSelectedListener(this);

            mGradientBuffer = getGradientBuffer().get();

            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(LinearLayout.VERTICAL);

            mImageView = new ImageView(this);

            mSurfaceView = new SurfaceView(this);
            mSurfaceView.getHolder().addCallback(this);

            linearLayout.addView(spinner, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            linearLayout.addView(mImageView, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
            linearLayout.addView(mSurfaceView, new LinearLayout.LayoutParams(WIDTH, HEIGHT));

            setContentView(linearLayout);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ColorSpace getFromName(String name) {
        if (name.equals("sRGB")) {
            return ColorSpace.get(ColorSpace.Named.SRGB);
        } else if (name.equals("BT2020_HLG")) {
            return ColorSpace.get(ColorSpace.Named.BT2020_HLG);
        } else if (name.equals("BT2020_PQ")) {
            return ColorSpace.get(ColorSpace.Named.BT2020_PQ);
        }

        throw new RuntimeException("Unrecognized Colorspace!");
    }

    private void populateBuffers() {
        Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                mGradientBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
        Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
        copy.setColorSpace(mColorSpace);
        mImageView.setImageBitmap(copy);

        try (Image image = mImageWriter.dequeueInputImage()) {
            authorGradientBuffer(image.getHardwareBuffer()).get();
            image.setDataSpace(mColorSpace.getDataSpace());
            mImageWriter.queueInputImage(image);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mImageWriter = new ImageWriter.Builder(holder.getSurface())
                .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                        | HardwareBuffer.USAGE_COMPOSER_OVERLAY)
                .build();
        populateBuffers();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mImageWriter.close();
        mImageWriter = null;
    }


    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        mCurrentColorName = mColorNames[position];
        mColorSpace = getFromName(mCurrentColorName);
        populateBuffers();
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }
}
