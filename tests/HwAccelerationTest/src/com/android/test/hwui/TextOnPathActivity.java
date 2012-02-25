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
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;

@SuppressWarnings({"UnusedDeclaration"})
public class TextOnPathActivity extends Activity {
    private Path mPath;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mPath = makePath();

        final TextOnPathView view = new TextOnPathView(this);
        setContentView(view);
    }

    private Path makePath() {
        Path path = new Path();
        buildPath(path);
        return path;
    }

    private void buildPath(Path path) {
        path.moveTo(0.0f, 0.0f);
        path.cubicTo(0.0f, 0.0f, 100.0f, 150.0f, 100.0f, 200.0f);
        path.cubicTo(100.0f, 200.0f, 50.0f, 300.0f, -80.0f, 200.0f);
        path.cubicTo(-80.0f, 200.0f, 100.0f, 200.0f, 200.0f, 0.0f);
    }

    public class TextOnPathView extends View {
        private static final String TEST_STRING = "Hello OpenGL renderer, text on path! ";

        private final Paint mPaint;
        private final String mText;

        public TextOnPathView(Context c) {
            super(c);

            mPaint = new Paint();
            mPaint.setAntiAlias(true);
            mPaint.setColor(0xff000000);

            StringBuilder builder = new StringBuilder(TEST_STRING.length() * 5);
            for (int i = 0; i < 5; i++) {
                builder.append(TEST_STRING);
            }
            mText = builder.toString();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            canvas.drawARGB(255, 255, 255, 255);

            canvas.translate(550.0f, 60.0f);
            canvas.drawTextOnPath(mText, mPath, 0.0f, 0.0f, mPaint);
        }
    }
}
