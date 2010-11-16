/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.util.Slog;

public class FixedSizeDrawable extends Drawable {
    Drawable mDrawable;
    int mLeft;
    int mTop;
    int mRight;
    int mBottom;

    public FixedSizeDrawable(Drawable that) {
        mDrawable = that;
    }

    public void setFixedBounds(int l, int t, int r, int b) {
        mLeft = l;
        mTop = t;
        mRight = r;
        mBottom = b;
    }

    public void setBounds(Rect bounds) {
        mDrawable.setBounds(mLeft, mTop, mRight, mBottom);
    }

    public void setBounds(int l, int t, int r, int b) {
        mDrawable.setBounds(mLeft, mTop, mRight, mBottom);
    }

    public void draw(Canvas canvas) {
        mDrawable.draw(canvas);
    }

    public int getOpacity() {
        return mDrawable.getOpacity();
    }

    public void setAlpha(int alpha) {
        mDrawable.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter cf) {
        mDrawable.setColorFilter(cf);
    }
}
