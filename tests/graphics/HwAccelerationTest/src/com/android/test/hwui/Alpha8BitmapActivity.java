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
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class Alpha8BitmapActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new BitmapsView(this));
    }

    static class BitmapsView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;
        private final float[] mVertices;

        BitmapsView(Context c) {
            super(c);

            Bitmap texture = BitmapFactory.decodeResource(c.getResources(), R.drawable.spot_mask);
            mBitmap1 = Bitmap.createBitmap(texture.getWidth(), texture.getHeight(),
                    Bitmap.Config.ALPHA_8);
            Canvas canvas = new Canvas(mBitmap1);
            canvas.drawBitmap(texture, 0.0f, 0.0f, null);

            texture = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            BitmapShader shader = new BitmapShader(texture,
                    Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);

            final float width = texture.getWidth() / 3.0f;
            final float height = texture.getHeight() / 3.0f;

            mVertices = new float[] {
                    0.0f, 0.0f, width, 0.0f, width * 2, 0.0f, width * 3, 0.0f,
                    0.0f, height, width, height, width * 2, height, width * 4, height,
                    0.0f, height * 2, width, height * 2, width * 2, height * 2, width * 3, height * 2,
                    0.0f, height * 4, width, height * 4, width * 2, height * 4, width * 4, height * 4,
            };

            mBitmapPaint = new Paint();
            mBitmapPaint.setFilterBitmap(true);
            mBitmapPaint.setShader(shader);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawColor(0xffffffff);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);

            Matrix matrix = new Matrix();
            matrix.setScale(2.0f, 2.0f);
            matrix.postTranslate(0.0f, mBitmap1.getHeight());
            canvas.drawBitmap(mBitmap1, matrix, mBitmapPaint);

            Rect src = new Rect(0, 0, mBitmap1.getWidth() / 2, mBitmap1.getHeight() / 2);
            Rect dst = new Rect(0, mBitmap1.getHeight() * 3, mBitmap1.getWidth(),
                    mBitmap1.getHeight() * 4);
            canvas.drawBitmap(mBitmap1, src, dst, mBitmapPaint);

            canvas.translate(0.0f, mBitmap1.getHeight() * 4);
            canvas.drawBitmapMesh(mBitmap1, 3, 3, mVertices, 0, null, 0, mBitmapPaint);

            invalidate();
        }
    }
}
