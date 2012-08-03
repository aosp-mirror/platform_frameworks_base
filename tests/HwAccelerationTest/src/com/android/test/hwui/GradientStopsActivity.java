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

package com.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings("UnusedDeclaration")
public class GradientStopsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(new GradientView(this));
    }

    private class GradientView extends View {
        public GradientView(Context context) {
            super(context);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int[] colors = new int[] { 0xffff0000, 0xff0000ff };
            float[] positions = new float[] { 0.3f, 0.6f };
            LinearGradient gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, positions, Shader.TileMode.CLAMP);
            
            Paint paint = new Paint();
            paint.setShader(gradient);

            canvas.drawRect(0.0f, 0.0f, 256.0f, 50.0f, paint);

            colors = new int[] { 0xffff0000, 0xff0000ff, 0xff00ff00 };
            positions = new float[] { 0.3f, 0.6f, 1.0f };
            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, positions, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 256.0f, 50.0f, paint);

            colors = new int[] { 0xffff0000, 0xff0000ff, 0xff00ff00 };
            positions = new float[] { 0.0f, 0.3f, 0.6f };
            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, positions, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 256.0f, 50.0f, paint);

            colors = new int[] { 0xff000000, 0xffffffff };
            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, null, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 256.0f, 50.0f, paint);

            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, null, Shader.TileMode.REPEAT);
            
            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 768.0f, 50.0f, paint);

            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, null, Shader.TileMode.MIRROR);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 768.0f, 50.0f, paint);

            gradient = new LinearGradient(0.0f, 0.0f, 256.0f, 0.0f,
                    colors, null, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 768.0f, 50.0f, paint);

            gradient = new LinearGradient(0.0f, 0.0f, 768.0f, 0.0f,
                    colors, null, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 768.0f, 50.0f, paint);

            gradient = new LinearGradient(0.0f, 0.0f, 512.0f, 0.0f,
                    colors, null, Shader.TileMode.CLAMP);

            paint.setShader(gradient);

            canvas.translate(0.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 512.0f, 50.0f, paint);
        }
    }
}
