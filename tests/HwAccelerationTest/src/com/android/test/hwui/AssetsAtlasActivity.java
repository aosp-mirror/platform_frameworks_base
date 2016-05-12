/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import com.android.internal.R;

@SuppressWarnings({"UnusedDeclaration"})
public class AssetsAtlasActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new BitmapsView(this));
    }

    static class BitmapsView extends View {
        private final Bitmap mBitmap;

        BitmapsView(Context c) {
            super(c);

            Drawable d = c.getResources().getDrawable(R.drawable.star_big_on);
            mBitmap = ((BitmapDrawable) d).getBitmap();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            final Matrix matrix = new Matrix();
            matrix.setScale(0.5f, 0.5f);

            final Rect src = new Rect(0, 0, mBitmap.getWidth() / 2, mBitmap.getHeight() / 2);
            final Rect dst = new Rect(0, 0, mBitmap.getWidth(), mBitmap.getHeight());

            canvas.drawBitmap(mBitmap, 0.0f, 0.0f, null);
            canvas.translate(0.0f, mBitmap.getHeight());
            canvas.drawBitmap(mBitmap, matrix, null);
            canvas.translate(0.0f, mBitmap.getHeight());
            canvas.drawBitmap(mBitmap, src, dst, null);
        }
    }
}
