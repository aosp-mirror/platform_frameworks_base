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
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class AlphaLayersActivity extends Activity {
    private static final String LOG_TAG = "HwUi";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        DirtyBitmapView container = new DirtyBitmapView(this);

        ColorView color = new ColorView(this);
        container.addView(color, new DirtyBitmapView.LayoutParams(
                dipToPx(this, 100), dipToPx(this, 100), Gravity.CENTER));

        AlphaAnimation a = new AlphaAnimation(1.0f, 0.0f);
        a.setDuration(2000);
        a.setRepeatCount(Animation.INFINITE);
        a.setRepeatMode(Animation.REVERSE);
        color.startAnimation(a);

        setContentView(container);
    }

    @SuppressWarnings({"UnusedDeclaration"})
    static int dipToPx(Context c, int dip) {
        return (int) (c.getResources().getDisplayMetrics().density * dip + 0.5f);
    }

    static class ColorView extends View {
        ColorView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(0, 255, 0);
        }
    }

    static class DirtyBitmapView extends FrameLayout {
        private final Paint mPaint;

        DirtyBitmapView(Context c) {
            super(c);
            mPaint = new Paint();
        }

        @Override
        public void dispatchDraw(Canvas canvas) {
            canvas.drawRGB(255, 255, 255);

            mPaint.setColor(0xffff0000);
            canvas.drawRect(200.0f, 0.0f, 220.0f, 20.0f, mPaint);

            canvas.save();
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(100.0f, 100.0f, 110.0f, 110.0f));
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(25.0f, 5.0f, 30.0f, 10.0f));
            canvas.restore();

            canvas.save();
            canvas.scale(2.0f, 2.0f);
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(50.0f, 50.0f, 60.0f, 60.0f));
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(25.0f, 5.0f, 30.0f, 10.0f));
            canvas.restore();

            canvas.save();
            canvas.translate(20.0f, 20.0f);
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);
            Log.d(LOG_TAG, "clipRect = " + canvas.getClipBounds());
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(80.0f, 80.0f, 90.0f, 90.0f));
            Log.d(LOG_TAG, "rejected = " + canvas.quickReject(25.0f, 5.0f, 30.0f, 10.0f));
            canvas.restore();

            canvas.save();
            canvas.scale(2.0f, 2.0f);            
            canvas.clipRect(20.0f, 0.0f, 40.0f, 20.0f);

            mPaint.setColor(0xff00ff00);
            canvas.drawRect(0.0f, 0.0f, 20.0f, 20.0f, mPaint);
            
            mPaint.setColor(0xff0000ff);
            canvas.drawRect(20.0f, 0.0f, 40.0f, 20.0f, mPaint);
            
            canvas.restore();

            final int restoreTo = canvas.save();
            canvas.saveLayerAlpha(0.0f, 100.0f, getWidth(), 150.0f, 127,
                    Canvas.HAS_ALPHA_LAYER_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG);
            mPaint.setColor(0xff0000ff);
            canvas.drawRect(0.0f, 100.0f, 40.0f, 150.0f, mPaint);
            mPaint.setColor(0xff00ffff);
            canvas.drawRect(40.0f, 100.0f, 140.0f, 150.0f, mPaint);
            mPaint.setColor(0xffff00ff);
            canvas.drawRect(140.0f, 100.0f, 240.0f, 150.0f, mPaint);
            canvas.restoreToCount(restoreTo);

            super.dispatchDraw(canvas);
        }
    }
}
