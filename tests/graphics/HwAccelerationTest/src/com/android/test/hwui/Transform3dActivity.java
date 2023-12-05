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
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class Transform3dActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Transform3dView view = new Transform3dView(this);
        setContentView(view);
    }

    static class Transform3dView extends View {
        private final Bitmap mBitmap1;
        private Camera mCamera;
        private Matrix mMatrix;

        Transform3dView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
            mCamera = new Camera();
            mMatrix = new Matrix();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            final float centerX = getWidth() / 2.0f - mBitmap1.getWidth() / 2.0f;
            final float centerY = getHeight() / 2.0f - mBitmap1.getHeight() / 2.0f;
            final Camera camera = mCamera;
            
            final Matrix matrix = mMatrix;

            rotate(centerX, centerY, camera, matrix, 32.0f);
            drawBitmap(canvas, centerX, centerY, 0.0f, matrix);

            rotate(centerX, centerY, camera, matrix, 12.0f);
            drawBitmap(canvas, centerX, centerY, -mBitmap1.getWidth(), matrix);
            
            rotate(centerX, centerY, camera, matrix, 52.0f);
            drawBitmap(canvas, centerX, centerY, mBitmap1.getWidth(), matrix);
            
            rotate(centerX, centerY, camera, matrix, 122.0f);
            drawBitmap(canvas, centerX, centerY, mBitmap1.getWidth() * 2.0f, matrix);
            
        }

        private void drawBitmap(Canvas canvas, float centerX, float centerY, float offset,
                Matrix matrix) {
            canvas.save();
            canvas.translate(offset, 0.0f);
            canvas.concat(matrix);
            canvas.drawBitmap(mBitmap1, centerX, centerY, null);
            canvas.restore();
        }

        private void rotate(float centerX, float centerY, Camera camera,
                Matrix matrix, float angle) {
            camera.save();
            camera.rotateY(angle);
            camera.getMatrix(matrix);
            camera.restore();

            matrix.preTranslate(-centerX, -centerY);
            matrix.postTranslate(centerX, centerY);
        }
    }
}
