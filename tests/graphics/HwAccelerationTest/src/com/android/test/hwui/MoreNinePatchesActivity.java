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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class MoreNinePatchesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        FrameLayout layout = new FrameLayout(this);
        PatchView b = new PatchView (this);
        b.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        layout.addView(b);
        layout.setBackgroundColor(0xffffffff);
        
        setContentView(layout);
    }

    private class PatchView extends View {
        private final Drawable mDrawable1;
        private final Drawable mDrawable2;
        private final Drawable mDrawable3;

        private PatchView(Context context) {
            super(context);
            Resources res = context.getResources();
            mDrawable1 = res.getDrawable(R.drawable.progress_vertical_holo_dark);
            mDrawable2 = res.getDrawable(R.drawable.scrubber_progress_vertical_holo_dark);
            mDrawable3 = res.getDrawable(R.drawable.scrubber_vertical_primary_holo);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.translate(100, 100);
            mDrawable1.setBounds(0, 0, 33, 120);
            mDrawable1.setLevel(5000);
            mDrawable1.draw(canvas);

            canvas.translate(20, 0);
            mDrawable2.setBounds(0, 0, 33, 120);
            mDrawable2.setLevel(5000);
            mDrawable2.draw(canvas);

            canvas.translate(20, 0);
            mDrawable3.setBounds(0, 0, 33, 120);
            mDrawable3.draw(canvas);            
        }
    }
}
