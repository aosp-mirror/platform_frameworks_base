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
public class BitmapsSkewActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        setContentView(view);
    }

    static class BitmapsView extends View {
        private Paint mBitmapPaint;
        private final Bitmap mBitmap1;

        BitmapsView(Context c) {
            super(c);

            mBitmap1 = BitmapFactory.decodeResource(c.getResources(), R.drawable.sunset1);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(120.0f, 50.0f);
            canvas.skew(0.2f, 0.3f);
            canvas.drawBitmap(mBitmap1, 0.0f, 0.0f, mBitmapPaint);
        }
    }
}
