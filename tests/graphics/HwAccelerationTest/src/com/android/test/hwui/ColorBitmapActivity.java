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
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareBufferRenderer;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

@SuppressWarnings({"UnusedDeclaration"})
public class ColorBitmapActivity extends Activity implements SurfaceHolder.Callback,
        TextureView.SurfaceTextureListener {

    private static final int WIDTH = 512;
    private static final int HEIGHT = 512;

    private ImageView mImageView;
    private SurfaceView mSurfaceView;
    private TextureView mTextureView;
    private HardwareBuffer mGradientBuffer;
    private Map<View, ImageWriter> mImageWriters = new HashMap<>();
    private ColorSpace mColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
    private String[] mColorNames = {"sRGB", "BT2020_HLG", "BT2020_PQ"};

    private int mGradientEndColor = 0xFFFFFFFF;

    private int[] mGradientEndColors = {0xFFFFFFFF, 0xFFFF0000, 0xFF00FF00, 0xFF0000FF};
    private String[] mGradientColorNames = {"Grayscale", "Red", "Green", "Blue"};

    private int mColorMode = ActivityInfo.COLOR_MODE_DEFAULT;
    private int[] mColorModes = {ActivityInfo.COLOR_MODE_DEFAULT, ActivityInfo.COLOR_MODE_HDR};
    private String[] mColorModeNames = {"DEFAULT", "HDR"};

    private final ExecutorService mBufferFenceExecutor = Executors.newFixedThreadPool(1);
    private final ExecutorService mBufferExecutor = Executors.newFixedThreadPool(1);

    private FutureTask<HardwareBuffer> authorGradientBuffer(
            HardwareBuffer buffer, int gradentEndColor) {
        HardwareBufferRenderer renderer = new HardwareBufferRenderer(buffer);
        RenderNode node = new RenderNode("content");
        node.setPosition(0, 0, buffer.getWidth(), buffer.getHeight());

        Canvas canvas = node.beginRecording();
        LinearGradient gradient = new LinearGradient(
                0, 0, buffer.getWidth(), buffer.getHeight(), 0xFF000000,
                gradentEndColor, Shader.TileMode.CLAMP);
        Paint paint = new Paint();
        paint.setShader(gradient);
        paint.setDither(true);
        canvas.drawRect(0f, 0f, buffer.getWidth(), buffer.getHeight(), paint);
        node.endRecording();

        renderer.setContentRoot(node);

        ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        FutureTask<HardwareBuffer> resolvedBuffer = new FutureTask<>(() -> buffer);
        renderer.obtainRenderRequest()
                .setColorSpace(colorSpace)
                .draw(mBufferFenceExecutor, result -> {
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
        return authorGradientBuffer(buffer, mGradientEndColor);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {

            mColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);

            ArrayAdapter<String> colorSpaceAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, mColorNames);

            colorSpaceAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            Spinner colorSpaceSpinner = new Spinner(this);
            colorSpaceSpinner.setAdapter(colorSpaceAdapter);
            colorSpaceSpinner.setOnItemSelectedListener(new ColorSpaceOnItemSelectedListener());

            ArrayAdapter<String> gradientColorAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, mGradientColorNames);

            gradientColorAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            Spinner gradientColorSpinner = new Spinner(this);
            gradientColorSpinner.setAdapter(gradientColorAdapter);
            gradientColorSpinner
                    .setOnItemSelectedListener(new GradientColorOnItemSelectedListener());

            ArrayAdapter<String> colorModeAdapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_spinner_item, mColorModeNames);

            colorModeAdapter
                    .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            Spinner colorModeSpinner = new Spinner(this);
            colorModeSpinner.setAdapter(colorModeAdapter);
            colorModeSpinner.setOnItemSelectedListener(new ColorModeOnItemSelectedListener());

            mGradientBuffer = getGradientBuffer().get();

            LinearLayout linearLayout = new LinearLayout(this);
            linearLayout.setOrientation(LinearLayout.VERTICAL);

            TextView imageViewText = new TextView(this);
            imageViewText.setText("ImageView");
            mImageView = new ImageView(this);

            TextView textureViewText = new TextView(this);
            textureViewText.setText("TextureView");
            mTextureView = new TextureView(this);
            mTextureView.setSurfaceTextureListener(this);

            TextView surfaceViewText = new TextView(this);
            surfaceViewText.setText("SurfaceView");
            mSurfaceView = new SurfaceView(this);
            mSurfaceView.getHolder().addCallback(this);

            LinearLayout spinnerLayout = new LinearLayout(this);
            spinnerLayout.setOrientation(LinearLayout.HORIZONTAL);

            spinnerLayout.addView(colorSpaceSpinner, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            spinnerLayout.addView(gradientColorSpinner, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            spinnerLayout.addView(colorModeSpinner, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            linearLayout.addView(spinnerLayout, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            linearLayout.addView(imageViewText,  new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            linearLayout.addView(mImageView, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
            linearLayout.addView(textureViewText,  new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            linearLayout.addView(mTextureView, new LinearLayout.LayoutParams(WIDTH, HEIGHT));
            linearLayout.addView(surfaceViewText,  new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            linearLayout.addView(mSurfaceView, new LinearLayout.LayoutParams(WIDTH, HEIGHT));

            setContentView(linearLayout);

            getWindow().setColorMode(mColorMode);
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
        try {
            Bitmap bitmap = Bitmap.wrapHardwareBuffer(
                    getGradientBuffer().get(), ColorSpace.get(ColorSpace.Named.SRGB));
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            copy.setColorSpace(mColorSpace);
            mImageView.setImageBitmap(copy);

            for (ImageWriter writer : mImageWriters.values()) {
                mBufferExecutor.execute(() -> {
                    try (Image image = writer.dequeueInputImage()) {
                        authorGradientBuffer(image.getHardwareBuffer(), mGradientEndColor).get();
                        image.setDataSpace(mColorSpace.getDataSpace());
                        writer.queueInputImage(image);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mImageWriters.put(mSurfaceView, new ImageWriter.Builder(holder.getSurface())
                .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                        | HardwareBuffer.USAGE_COMPOSER_OVERLAY)
                .build());
        populateBuffers();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mImageWriters.containsKey(mSurfaceView)) {
            mImageWriters.remove(mSurfaceView);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(
            @NonNull SurfaceTexture surface, int width, int height) {
        mImageWriters.put(mTextureView, new ImageWriter.Builder(new Surface(surface))
                .setUsage(HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                        | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
                .build());
        populateBuffers();
    }

    @Override
    public void onSurfaceTextureSizeChanged(
            @NonNull SurfaceTexture surface, int width, int height) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
        if (mImageWriters.containsKey(mTextureView)) {
            mImageWriters.remove(mTextureView);
        }
        return false;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

    }

    private final class ColorSpaceOnItemSelectedListener
            implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            ColorBitmapActivity.this.mColorSpace =
                    getFromName(ColorBitmapActivity.this.mColorNames[position]);
            ColorBitmapActivity.this.getMainExecutor()
                    .execute(ColorBitmapActivity.this::populateBuffers);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

    private final class GradientColorOnItemSelectedListener
            implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            ColorBitmapActivity.this.mGradientEndColor =
                    ColorBitmapActivity.this.mGradientEndColors[position];
            ColorBitmapActivity.this.getMainExecutor()
                    .execute(ColorBitmapActivity.this::populateBuffers);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }

    private final class ColorModeOnItemSelectedListener
            implements AdapterView.OnItemSelectedListener {

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            ColorBitmapActivity.this.mColorMode = ColorBitmapActivity.this.mColorModes[position];
            ColorBitmapActivity.this.getMainExecutor()
                    .execute(() -> {
                        ColorBitmapActivity.this.getWindow().setColorMode(mColorMode);
                    });
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {

        }
    }
}
