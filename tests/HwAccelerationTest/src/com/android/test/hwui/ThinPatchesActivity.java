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
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class ThinPatchesActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        FrameLayout layout = new FrameLayout(this);
        PatchView b = new PatchView(this);
        b.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        layout.addView(b);
        layout.setBackgroundColor(0xffffffff);
        
        setContentView(layout);
    }

    private class PatchView extends View {
        private Drawable mPatch1, mPatch2;

        public PatchView(Activity activity) {
            super(activity);

            final Resources resources = activity.getResources();
            mPatch1 = resources.getDrawable(R.drawable.patch);
            mPatch2 = resources.getDrawable(R.drawable.btn_toggle_on);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int width = 100;
            final int height = 60;

            final int left = (getWidth() - width) / 2;
            final int top  = (getHeight() - height) / 2;

            mPatch1.setBounds(left, top, left + width, top + height);
            mPatch1.draw(canvas);

            canvas.translate(0.0f, height + 20.0f);
            
            mPatch2.setBounds(left, top, left + width, top + height);
            mPatch2.draw(canvas);
        }
    }
}
