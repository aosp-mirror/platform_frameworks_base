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

package com.google.android.test.hwui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class HwUiActivity extends Activity {
    private static final String LOG_TAG = "HwUi";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new DirtyBitmapView(this));
    }
    
    @SuppressWarnings({"UnusedDeclaration"})
    static int dipToPx(Context c, int dip) {
        return (int) (c.getResources().getDisplayMetrics().density * dip + 0.5f);
    }

    static class DirtyBitmapView extends View {
        private final Paint mPaint;

        DirtyBitmapView(Context c) {
            super(c);
            mPaint = new Paint();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawRGB(255, 255, 255);

            mPaint.setColor(0xffff0000);
            canvas.drawRect(200.0f, 0.0f, 220.0f, 20.0f, mPaint);

            canvas.save();
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            canvas.restore();
            
            canvas.save();
            canvas.scale(2.0f, 2.0f);
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            canvas.restore();

            canvas.save();
            canvas.translate(20.0f, 20.0f);
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            canvas.restore();
            
            canvas.scale(2.0f, 2.0f);            
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);

            mPaint.setColor(0xff00ff00);
            canvas.drawRect(0.0f, 0.0f, 20.0f, 20.0f, mPaint);
            
            mPaint.setColor(0xff0000ff);
            canvas.drawRect(20.0f, 0.0f, 40.0f, 20.0f, mPaint);
        }
    }
}
