/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class BitmapMeshActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapMeshView view = new BitmapMeshView(this);
        setContentView(view);
    }

    static class BitmapMeshView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private float[] mVertices;
        private int[] mColors;

        BitmapMeshView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);

            final float width = mBitmap1.getWidth() / 3.0f;
            final float height = mBitmap1.getHeight() / 3.0f;

            mVertices = new float[] {
                0.0f, 0.0f, width, 0.0f, width * 2, 0.0f, width * 3, 0.0f,
                0.0f, height, width, height, width * 2, height, width * 4, height,
                0.0f, height * 2, width, height * 2, width * 2, height * 2, width * 3, height * 2,
                0.0f, height * 4, width, height * 4, width * 2, height * 4, width * 4, height * 4,
            };
            
            mColors = new int[] {
                0xffff0000, 0xff00ff00, 0xff0000ff, 0xffff0000,
                0xff0000ff, 0xffff0000, 0xff00ff00, 0xff00ff00,
                0xff00ff00, 0xff0000ff, 0xffff0000, 0xff00ff00,
                0x00ff0000, 0x0000ff00, 0x000000ff, 0x00ff0000,
            };
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.translate(100, 100);
            canvas.drawBitmapMesh(mBitmap1, 3, 3, mVertices, 0, null, 0, null);

            canvas.save();
            canvas.translate(0, 400);
            canvas.clipRect(0.0f, 0.0f, 80.0f, 80.0f);
            canvas.drawBitmapMesh(mBitmap1, 3, 3, mVertices, 0, null, 0, null);
            canvas.restore();

            canvas.translate(400, 0);
            canvas.drawBitmapMesh(mBitmap1, 3, 3, mVertices, 0, mColors, 0, null);
        }
    }
}
