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
import android.graphics.Paint;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings("UnusedDeclaration")
public class TJunctionActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new TJunctionView(this));
    }

    private class TJunctionView extends View {
        private final Paint mPaint;

        public TJunctionView(Context context) {
            super(context);

            setLayerType(LAYER_TYPE_HARDWARE, null);

            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            mPaint.setColor(0xffff0000);

            canvas.translate(10.0f, 10.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            mPaint.setColor(0xff00ff00);

            canvas.translate(50.0f, 50.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            mPaint.setColor(0xff0000ff);

            canvas.translate(-25.0f, 50.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            mPaint.setColor(0xffffffff);

            canvas.translate(150.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            canvas.translate(-50.0f, 75.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            canvas.translate(-75.0f, 50.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            canvas.translate(150.0f, 0.0f);
            canvas.drawRect(0.0f, 0.0f, 100.0f, 50.0f, mPaint);

            invalidate();
        }
    }
}
