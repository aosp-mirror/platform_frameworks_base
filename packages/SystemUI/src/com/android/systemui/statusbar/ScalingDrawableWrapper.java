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

package com.android.systemui.statusbar;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.DrawableWrapper;

/**
 * An extension of {@link DrawableWrapper} that will take a given Drawable and scale it by
 * the given factor.
 */
public class ScalingDrawableWrapper extends DrawableWrapper {
    private float mScaleFactor;

    public ScalingDrawableWrapper(Drawable drawable, float scaleFactor) {
        super(drawable);
        mScaleFactor = scaleFactor;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) (super.getIntrinsicWidth() * mScaleFactor);
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) (super.getIntrinsicHeight() * mScaleFactor);
    }
}
