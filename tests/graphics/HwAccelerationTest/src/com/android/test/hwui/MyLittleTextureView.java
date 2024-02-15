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
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.widget.ImageView;

public class MyLittleTextureView extends Activity {
    private RenderNode mContent = new RenderNode("CustomRenderer");
    private HardwareRenderer mRenderer = new HardwareRenderer();
    private ImageView mImageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImageView = new ImageView(this);
        mImageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        setContentView(mImageView);

        ImageReader reader = ImageReader.newInstance(100, 100, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        mRenderer.setSurface(reader.getSurface());
        mRenderer.setLightSourceAlpha(0.0f, 1.0f);
        mRenderer.setLightSourceGeometry(100 / 2f, 0f, 800.0f, 20.0f);
        mContent.setLeftTopRightBottom(0, 0, 100, 100);

        Rect childRect = new Rect(25, 25, 65, 65);
        RenderNode childNode = new RenderNode("shadowCaster");
        childNode.setLeftTopRightBottom(childRect.left, childRect.top,
                childRect.right, childRect.bottom);
        Outline outline = new Outline();
        outline.setRect(new Rect(0, 0, childRect.width(), childRect.height()));
        outline.setAlpha(1f);
        childNode.setOutline(outline);
        {
            Canvas canvas = childNode.beginRecording();
            canvas.drawColor(Color.BLUE);
        }
        childNode.endRecording();
        childNode.setElevation(20f);

        {
            Canvas canvas = mContent.beginRecording();
            canvas.drawColor(Color.WHITE);
            canvas.enableZ();
            canvas.drawRenderNode(childNode);
            canvas.disableZ();
        }
        mContent.endRecording();
        mRenderer.setContentRoot(mContent);
        mRenderer.createRenderRequest()
                .setWaitForPresent(true)
                .syncAndDraw();
        Image image = reader.acquireNextImage();
        Bitmap bitmap = Bitmap.wrapHardwareBuffer(image.getHardwareBuffer(),
                ColorSpace.get(ColorSpace.Named.SRGB));
        mImageView.setImageBitmap(bitmap);
        image.close();
    }
}
