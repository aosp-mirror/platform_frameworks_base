/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.ColorSpace.Named;
import android.graphics.HardwareBufferRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.time.Duration;
import java.util.concurrent.Executors;

public class HardwareBufferRendererActivity extends Activity {

    private ImageView mImageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImageView = new ImageView(this);
        mImageView.setBackgroundColor(Color.MAGENTA);
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.CYAN);
        layout.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        layout.addView(mImageView, new FrameLayout.LayoutParams(100, 100));
        setContentView(layout);

        HardwareBuffer buffer = HardwareBuffer.create(100, 100, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareBufferRenderer renderer = new HardwareBufferRenderer(buffer);
        RenderNode node = new RenderNode("content");
        node.setPosition(0, 0, 100, 100);

        Canvas canvas = node.beginRecording();
        canvas.drawColor(Color.BLUE);

        Paint paint = new Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(0f, 0f, 50f, 50f, paint);
        node.endRecording();

        renderer.setContentRoot(node);

        ColorSpace colorSpace = ColorSpace.get(Named.SRGB);
        Handler handler = new Handler(Looper.getMainLooper());
        renderer.obtainRenderRequest()
                .setColorSpace(colorSpace)
                .draw(Executors.newSingleThreadExecutor(), result -> {
                    result.getFence().await(Duration.ofMillis(3000));
                    handler.post(() -> {
                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
                        Bitmap copy = bitmap.copy(Config.ARGB_8888, false);
                        mImageView.setImageBitmap(copy);
                    });
                });
    }
}
