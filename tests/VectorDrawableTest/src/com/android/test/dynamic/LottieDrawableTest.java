/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.LottieDrawable;
import android.os.Bundle;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;

@SuppressWarnings({"UnusedDeclaration"})
public class LottieDrawableTest extends Activity {
    private static final String TAG = "LottieDrawableTest";
    static final int BACKGROUND = 0xFFF44336;

    class LottieDrawableView extends View {
        private Rect mLottieBounds;

        private LottieDrawable mLottie;

        LottieDrawableView(Context context, InputStream is) {
            super(context);
            Scanner s = new Scanner(is).useDelimiter("\\A");
            String json = s.hasNext() ? s.next() : "";
            try {
                mLottie = LottieDrawable.makeLottieDrawable(json);
            } catch (IOException e) {
                throw new RuntimeException(TAG + ": error parsing test Lottie");
            }
            mLottie.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawColor(BACKGROUND);

            mLottie.setBounds(mLottieBounds);
            mLottie.draw(canvas);
        }

        public void setLottieSize(Rect bounds) {
            mLottieBounds = bounds;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        InputStream is = getResources().openRawResource(R.raw.lottie);

        LottieDrawableView view = new LottieDrawableView(this, is);
        view.setLottieSize(new Rect(0, 0, 900, 900));
        setContentView(view);
    }
}
