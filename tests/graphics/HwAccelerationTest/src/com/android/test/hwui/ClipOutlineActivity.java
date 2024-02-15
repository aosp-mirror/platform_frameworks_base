/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.TextView;

public class ClipOutlineActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final RegionView group = new RegionView(this);

        final TextView text = new TextView(this);
        text.setText(buildText());
        group.addView(text);

        setContentView(group);

        ObjectAnimator animator = ObjectAnimator.ofFloat(group, "clipPosition", 0.0f, 1.0f);
        animator.setDuration(3000);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setRepeatMode(ValueAnimator.REVERSE);
        animator.start();
    }

    private static CharSequence buildText() {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < 10; i++) {
            buffer.append(LOREM_IPSUM);
        }
        return buffer;
    }

    public static class RegionView extends FrameLayout {
        private float mClipPosition = 0.0f;
        private Rect mRect = new Rect();

        public RegionView(Context c) {
            super(c);
            setOutlineProvider(new ViewOutlineProvider() {

                @Override
                public void getOutline(View view, Outline outline) {
                    int w = getWidth() / 2;
                    int h = getHeight() / 2;

                    mRect.set(0, 0, w, h);
                    mRect.offset((int) (mClipPosition * w), getHeight() / 4);

                    outline.setRoundRect(mRect, w / 2);
                }
            });
            setClipToOutline(true);
        }

        public float getClipPosition() {
            return mClipPosition;
        }

        public void setClipPosition(float clipPosition) {
            mClipPosition = clipPosition;
            invalidateOutline();
        }
    }

    private static final String LOREM_IPSUM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Sed sagittis molestie aliquam. Donec metus metus, laoreet nec sagittis vitae, ultricies sit amet eros. Suspendisse sed massa sit amet felis consectetur gravida. In vitae erat mi, in egestas nisl. Phasellus quis ipsum massa, at scelerisque arcu. Nam lectus est, pellentesque eget lacinia non, congue vitae augue. Aliquam erat volutpat. Pellentesque bibendum tincidunt viverra. Aliquam erat volutpat. Maecenas pretium vulputate placerat. Nulla varius elementum rutrum. Aenean mollis blandit imperdiet. Pellentesque interdum fringilla ligula.";
}
