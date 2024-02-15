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
import android.graphics.Path;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class ClipRegion3Activity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final RegionView group = new RegionView(this);

        final TextView text = new TextView(this);
        text.setText(buildText());
        group.addView(text);

        setContentView(group);
    }

    private static CharSequence buildText() {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < 10; i++) {
            buffer.append(LOREM_IPSUM);
        }
        return buffer;
    }

    public static class RegionView extends FrameLayout {
        private final Path mClipPath = new Path();
        private float mClipPosition = 0.5f;

        public RegionView(Context c) {
            super(c);
            setAlpha(0.5f);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            mClipPath.reset();
            mClipPath.addCircle(0.0f, 0.0f, getWidth() / 4.0f, Path.Direction.CW);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            canvas.drawARGB(255, 255, 255, 255);

            canvas.save(Canvas.MATRIX_SAVE_FLAG);
            canvas.translate(mClipPosition * getWidth(), getHeight() / 2.0f);
            canvas.clipPath(mClipPath);
            canvas.restore();

            super.dispatchDraw(canvas);

            invalidate();
        }
    }

    private static final String LOREM_IPSUM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed sagittis molestie aliquam. Donec metus metus, laoreet nec sagittis vitae, ultricies sit amet eros. Suspendisse sed massa sit amet felis consectetur gravida. In vitae erat mi, in egestas nisl. Phasellus quis ipsum massa, at scelerisque arcu. Nam lectus est, pellentesque eget lacinia non, congue vitae augue. Aliquam erat volutpat. Pellentesque bibendum tincidunt viverra. Aliquam erat volutpat. Maecenas pretium vulputate placerat. Nulla varius elementum rutrum. Aenean mollis blandit imperdiet. Pellentesque interdum fringilla ligula.";
}
