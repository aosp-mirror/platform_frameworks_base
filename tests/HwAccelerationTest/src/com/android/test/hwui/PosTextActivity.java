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

@SuppressWarnings({"UnusedDeclaration"})
public class PosTextActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(new CustomTextView(this));
    }

    static class CustomTextView extends View {
        private final Paint mLargePaint;
        private final String mText;
        private final float[] mPos;

        CustomTextView(Context c) {
            super(c);

            mText = c.getResources().getString(R.string.complex_string);
            mPos = new float[mText.length() * 2];
            for (int i = 0; i < mPos.length; i += 2) {
                mPos[i] = i * 30.0f;
                mPos[i + 1] = i * 10.0f;
            }

            mLargePaint = new Paint();
            mLargePaint.setAntiAlias(true);
            mLargePaint.setTextSize(36.0f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRGB(255, 255, 255);
            
            canvas.save();
            canvas.translate(100.0f, 100.0f);
            canvas.drawPosText(mText, mPos, mLargePaint);
            canvas.restore();
        }
    }
}
