/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;

/**
 * A drawable that forces to update the bounds {@link #onBoundsChange(Rect)} immediately after
 * {@link #setLayerInset} dynamically.
 */
public class InstantInsetLayerDrawable extends LayerDrawable {
    public InstantInsetLayerDrawable(Drawable[] layers) {
        super(layers);
    }

    @Override
    public void setLayerInset(int index, int l, int t, int r, int b) {
        super.setLayerInset(index, l, t, r, b);
        onBoundsChange(getBounds());
    }
}
