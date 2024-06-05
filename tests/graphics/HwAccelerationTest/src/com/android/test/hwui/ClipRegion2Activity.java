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
import android.graphics.Region;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.TextView;

@SuppressWarnings({"UnusedDeclaration"})
public class ClipRegion2Activity extends Activity {
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
        private Region mRegion = new Region();
        private float mClipPosition = 0.0f;

        public RegionView(Context c) {
            super(c);
        }

        public float getClipPosition() {
            return mClipPosition;
        }

        public void setClipPosition(float clipPosition) {
            mClipPosition = clipPosition;
            invalidate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {

            canvas.save();

            mRegion.set(0, 0, getWidth(), getHeight());
            mRegion.op(getWidth() / 4, getHeight() / 4, 3 * getWidth() / 4, 3 * getHeight() / 4,
                    Region.Op.DIFFERENCE);

            canvas.clipRegion(mRegion);
            super.dispatchDraw(canvas);

            canvas.restore();
        }
    }

    private static final String LOREM_IPSUM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed sagittis molestie aliquam. Donec metus metus, laoreet nec sagittis vitae, ultricies sit amet eros. Suspendisse sed massa sit amet felis consectetur gravida. In vitae erat mi, in egestas nisl. Phasellus quis ipsum massa, at scelerisque arcu. Nam lectus est, pellentesque eget lacinia non, congue vitae augue. Aliquam erat volutpat. Pellentesque bibendum tincidunt viverra. Aliquam erat volutpat. Maecenas pretium vulputate placerat. Nulla varius elementum rutrum. Aenean mollis blandit imperdiet. Pellentesque interdum fringilla ligula.";
}
