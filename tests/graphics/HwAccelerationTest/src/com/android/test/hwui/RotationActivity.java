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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class RotationActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DrawingView container = new DrawingView(this);

        setContentView(container);
    }
    
    @SuppressWarnings({"UnusedDeclaration"})
    static int dipToPx(Context c, int dip) {
        return (int) (c.getResources().getDisplayMetrics().density * dip + 0.5f);
    }

    static class DrawingView extends View {
        private final Paint mPaint;

        DrawingView(Context c) {
            super(c);
            mPaint = new Paint();
            mPaint.setAntiAlias(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.save();
            canvas.translate(dipToPx(getContext(), 400), dipToPx(getContext(), 200));
            canvas.rotate(45.0f);
            canvas.drawRGB(255, 255, 255);
            mPaint.setColor(0xffff0000);
            canvas.drawRect(-80.0f, -80.0f, 80.0f, 80.0f, mPaint);
            canvas.drawRect(0.0f, 0.0f, 220.0f, 220.0f, mPaint);            
            canvas.restore();
        }
    }
}
