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
package com.android.internal.widget;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.util.AttributeSet;
import android.widget.ImageView;
import android.widget.RemoteViews;

/**
 * ImageView which applies a saturation image filter, used by AppWidgets to represent disabled icon
 */
@RemoteViews.RemoteView
public class DisableImageView extends ImageView {

    public DisableImageView(Context context) {
        this(context, null, 0, 0);
    }

    public DisableImageView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0, 0);
    }

    public DisableImageView(Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DisableImageView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        // Apply the disabled filter
        ColorMatrix brightnessMatrix = new ColorMatrix();
        float brightnessF = 0.5f;
        int brightnessI = (int) (255 * brightnessF);
        // Brightness: C-new = C-old*(1-amount) + amount
        float scale = 1f - brightnessF;
        float[] mat = brightnessMatrix.getArray();
        mat[0] = scale;
        mat[6] = scale;
        mat[12] = scale;
        mat[4] = brightnessI;
        mat[9] = brightnessI;
        mat[14] = brightnessI;

        ColorMatrix filterMatrix = new ColorMatrix();
        filterMatrix.setSaturation(0);
        filterMatrix.preConcat(brightnessMatrix);
        setColorFilter(new ColorMatrixColorFilter(filterMatrix));
    }
}
