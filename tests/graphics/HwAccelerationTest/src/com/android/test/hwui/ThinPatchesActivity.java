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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.RectF;
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
        private Drawable mPatch1, mPatch2, mPatch3;
        private Bitmap mTexture;

        public PatchView(Activity activity) {
            super(activity);

            final Resources resources = activity.getResources();
            mPatch1 = resources.getDrawable(R.drawable.patch);
            mPatch2 = resources.getDrawable(R.drawable.btn_toggle_on);
            mPatch3 = resources.getDrawable(R.drawable.patch2);

            mTexture = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
            mTexture.setPixel(0, 0, 0xffff0000);
            mTexture.setPixel(1, 0, 0xffffffff);
            mTexture.setPixel(2, 0, 0xff000000);
            mTexture.setPixel(3, 0, 0xffff0000);
            mTexture.setPixel(0, 1, 0xffff0000);
            mTexture.setPixel(1, 1, 0xff000000);
            mTexture.setPixel(2, 1, 0xffffffff);
            mTexture.setPixel(3, 1, 0xffff0000);
            mTexture.setPixel(0, 2, 0xffff0000);
            mTexture.setPixel(1, 2, 0xffff0000);
            mTexture.setPixel(2, 2, 0xffff0000);
            mTexture.setPixel(3, 2, 0xffff0000);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int width = 100;
            final int height = 60;

            final int left = (getWidth() - width) / 2;
            final int top  = (getHeight() - height) / 2;

            canvas.save();
            canvas.translate(0.0f, -height * 2 - 20.0f);

            mPatch3.setBounds(left, top, left + height, top + width);
            mPatch3.draw(canvas);
            
            canvas.restore();
            
            mPatch1.setBounds(left, top, left + width, top + height);
            mPatch1.draw(canvas);

            canvas.save();
            canvas.translate(0.0f, height + 20.0f);
            
            mPatch2.setBounds(left, top, left + width, top + height);
            mPatch2.draw(canvas);

            canvas.restore();

//            Rect src = new Rect(1, 0, 3, 2);
//            RectF dst = new RectF(0, 0, getWidth(), getHeight());
//            canvas.drawBitmap(mTexture, src, dst, null);
        }
    }
}
