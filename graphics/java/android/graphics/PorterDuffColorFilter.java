/*
 * Copyright (C) 2006 The Android Open Source Project
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
 * color and a specific {@link PorterDuff Porter-Duff composite mode}.
 */
public class PorterDuffColorFilter extends ColorFilter {
    private int mColor;
    private PorterDuff.Mode mMode;

    /**
     * Create a color filter that uses the specified color and Porter-Duff mode.
     *
     * @param color The ARGB source color used with the specified Porter-Duff mode
     * @param mode The porter-duff mode that is applied
     *
     * @see Color
     * @see #setColor(int)
     * @see #setMode(android.graphics.PorterDuff.Mode)
     */
    public PorterDuffColorFilter(@ColorInt int color, @NonNull PorterDuff.Mode mode) {
        mColor = color;
        mMode = mode;
        update();
    }

    /**
     * Returns the ARGB color used to tint the source pixels when this filter
     * is applied.
     *
     * @see Color
     * @see #setColor(int)
     *
     * @hide
     */
    public int getColor() {
        return mColor;
    }

    /**
     * Specifies the color to tint the source pixels with when this color
     * filter is applied.
     *
     * @param color An ARGB {@link Color color}
     *
     * @see Color
     * @see #getColor()
     * @see #getMode()
     *
     * @hide
     */
    public void setColor(int color) {
        mColor = color;
        update();
    }

    /**
     * Returns the Porter-Duff mode used to composite this color filter's
     * color with the source pixel when this filter is applied.
     *
     * @see PorterDuff
     * @see #setMode(android.graphics.PorterDuff.Mode)
     *
     * @hide
     */
    public PorterDuff.Mode getMode() {
        return mMode;
    }

    /**
     * Specifies the Porter-Duff mode to use when compositing this color
     * filter's color with the source pixel at draw time.
     *
     * @see PorterDuff
     * @see #getMode()
     * @see #getColor()
     *
     * @hide
     */
    public void setMode(@NonNull PorterDuff.Mode mode) {
        mMode = mode;
        update();
    }

    private void update() {
        destroyFilter(native_instance);
        native_instance = native_CreatePorterDuffFilter(mColor, mMode.nativeInt);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final PorterDuffColorFilter other = (PorterDuffColorFilter) object;
        if (mColor != other.mColor || mMode != other.mMode) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return 31 *  mMode.hashCode() + mColor;
    }

    private static native long native_CreatePorterDuffFilter(int srcColor, int porterDuffMode);
}
