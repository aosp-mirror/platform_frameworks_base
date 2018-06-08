/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.graphics.GraphicBuffer.USAGE_HW_TEXTURE;
import static android.graphics.GraphicBuffer.USAGE_SW_READ_NEVER;
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_NEVER;
import static android.graphics.GraphicBuffer.USAGE_SW_WRITE_RARELY;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.GraphicBuffer;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.DisplayListCanvas;
import android.view.RenderNode;
import android.view.Surface;
import android.view.ThreadedRenderer;
import android.widget.ImageView;

public class DrawIntoHwBitmapActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        setContentView(view);
        view.setImageBitmap(createBitmap());
    }

    Bitmap createBitmap() {
        Picture picture = new Picture();
        Canvas canvas = picture.beginRecording(500, 500);
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setTextSize(20 * getResources().getDisplayMetrics().density);
        canvas.drawColor(0xFF2196F3);
        p.setColor(0xFFBBDEFB);
        canvas.drawRect(0, 0, 500, 100, p);
        p.setColor(Color.BLACK);
        canvas.drawText("Hello, World!", 0, 90, p);
        picture.endRecording();
        return Bitmap.createBitmap(picture);
    }
}
