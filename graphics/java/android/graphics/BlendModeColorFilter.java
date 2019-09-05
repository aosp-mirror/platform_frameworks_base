/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics;

import android.annotation.ColorInt;
import android.annotation.NonNull;

/**
 * A color filter that can be used to tint the source pixels using a single
 * color and a specific {@link BlendMode}.
 */
public final class BlendModeColorFilter extends ColorFilter {

    @ColorInt final int mColor;
    private final BlendMode mMode;

    public BlendModeColorFilter(@ColorInt int color, @NonNull BlendMode mode) {
        mColor = color;
        mMode = mode;
    }


    /**
     * Returns the ARGB color used to tint the source pixels when this filter
     * is applied.
     *
     * @see Color
     *
     */
    @ColorInt
    public int getColor() {
        return mColor;
    }

    /**
     * Returns the Porter-Duff mode used to composite this color filter's
     * color with the source pixel when this filter is applied.
     *
     * @see BlendMode
     *
     */
    public BlendMode getMode() {
        return mMode;
    }

    @Override
    long createNativeInstance() {
        return native_CreateBlendModeFilter(mColor, mMode.getXfermode().porterDuffMode);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final BlendModeColorFilter other = (BlendModeColorFilter) object;
        return other.mMode == mMode;
    }

    @Override
    public int hashCode() {
        return 31 *  mMode.hashCode() + mColor;
    }

    private static native long native_CreateBlendModeFilter(int srcColor, int blendmode);

}
