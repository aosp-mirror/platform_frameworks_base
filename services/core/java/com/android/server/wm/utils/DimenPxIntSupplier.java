/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.wm.utils;

import android.annotation.DimenRes;
import android.annotation.NonNull;
import android.content.Context;

import java.util.function.IntSupplier;

/**
 * Cached version of IntSupplier customised to evaluate new dimen in pixels
 * when density changes.
 * @hide
 */
public class DimenPxIntSupplier implements IntSupplier {

    @NonNull
    private final Context mContext;

    private final int mResourceId;

    private float mLastDensity = Float.MIN_VALUE;
    private int mValue = 0;

    public DimenPxIntSupplier(@NonNull Context context, @DimenRes int resourceId) {
        mContext = context;
        mResourceId = resourceId;
    }

    @Override
    public int getAsInt() {
        final float newDensity = mContext.getResources().getDisplayMetrics().density;
        if (newDensity != mLastDensity) {
            mLastDensity = newDensity;
            mValue = mContext.getResources().getDimensionPixelSize(mResourceId);
        }
        return mValue;
    }
}
