/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.test.dynamic;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.VectorDrawable;
import android.os.Bundle;
import android.view.View;

public class BoundsCheckTest extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final BitmapsView view = new BitmapsView(this);
        setContentView(view);
    }

    static class BitmapsView extends View {
        private final BitmapDrawable mBitmap1;
        private final VectorDrawable mVector1;

        BitmapsView(Context c) {
            super(c);
            Resources res = c.getResources();
            mBitmap1 = (BitmapDrawable) res.getDrawable(R.drawable.icon);
            mVector1 = (VectorDrawable) res.getDrawable(R.drawable.vector_drawable28);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            mBitmap1.setBounds(100, 100, 400, 400);
            mBitmap1.draw(canvas);

            mVector1.setBounds(100, 100, 400, 400);
            mVector1.draw(canvas);
        }
    }
}