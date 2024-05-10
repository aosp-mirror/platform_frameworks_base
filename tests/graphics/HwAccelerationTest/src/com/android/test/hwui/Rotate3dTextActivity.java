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
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class Rotate3dTextActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        Rotate3dTextView view = new Rotate3dTextView(this);
        layout.addView(view, makeLayoutParams());

        view = new Rotate3dTextView(this);

        FrameLayout container = new FrameLayout(this);
        container.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        container.addView(view);

        layout.addView(container, makeLayoutParams());

        setContentView(layout);
    }

    private static LinearLayout.LayoutParams makeLayoutParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0);
        lp.weight = 1.0f;
        return lp;
    }

    public static class Rotate3dTextView extends View {
        private static final String TEXT = "Hello libhwui! ";

        private final Paint mPaint;

        public Rotate3dTextView(Context c) {
            super(c);

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setTextSize(50.0f);
            mPaint.setTextAlign(Paint.Align.CENTER);

            setRotationY(45.0f);
            setScaleX(2.0f);
            setScaleY(2.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawText(TEXT, getWidth() / 2.0f, getHeight() / 2.0f, mPaint);
        }
    }
}
