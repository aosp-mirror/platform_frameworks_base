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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy;

import android.annotation.Nullable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;

/**
 * Drawable for {@link KeyButtonView}s which contains an asset for both normal mode and light
 * navigation bar mode.
 */
public class KeyButtonDrawable extends LayerDrawable {

    private final boolean mHasDarkDrawable;

    public static KeyButtonDrawable create(Drawable lightDrawable,
            @Nullable Drawable darkDrawable) {
        if (darkDrawable != null) {
            return new KeyButtonDrawable(
                    new Drawable[] { lightDrawable.mutate(), darkDrawable.mutate() });
        } else {
            return new KeyButtonDrawable(new Drawable[] { lightDrawable.mutate() });
        }
    }

    private KeyButtonDrawable(Drawable[] drawables) {
        super(drawables);
        for (int i = 0; i < drawables.length; i++) {
            setLayerGravity(i, Gravity.CENTER);
        }
        mutate();
        mHasDarkDrawable = drawables.length > 1;
        setDarkIntensity(0f);
    }

    public void setDarkIntensity(float intensity) {
        if (!mHasDarkDrawable) {
            return;
        }
        getDrawable(0).setAlpha((int) ((1 - intensity) * 255f));
        getDrawable(1).setAlpha((int) (intensity * 255f));
        invalidateSelf();
    }
}
