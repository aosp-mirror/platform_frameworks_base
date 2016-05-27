/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui.testing;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.drawable.Drawable;

public class TestDrawable extends Drawable {

    public float hotspotX;
    public float hotspotY;

    @Override
    public void setHotspot(float x, float y) {
        hotspotX = x;
        hotspotY = y;
    }

    @Override
    public void draw(Canvas canvas) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAlpha(int alpha) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getOpacity() {
        throw new UnsupportedOperationException();
    }
}
