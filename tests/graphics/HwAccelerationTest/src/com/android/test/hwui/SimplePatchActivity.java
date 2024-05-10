/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class SimplePatchActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(new PatchView(this));
    }

    private static class PatchView extends View {
        private final Drawable mDrawable;

        public PatchView(Context context) {
            super(context);
            setBackgroundColor(0xff000000);
            mDrawable = context.getResources().getDrawable(R.drawable.expander_ic_minimized);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            canvas.translate(200, 200);
            mDrawable.setBounds(3, 0, 33, 64);
            mDrawable.draw(canvas);
            mDrawable.setBounds(63, 0, 94, 64);
            mDrawable.draw(canvas);
            canvas.restore();
        }
    }
}
