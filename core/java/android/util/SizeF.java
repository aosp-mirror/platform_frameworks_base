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

package android.util;

import static com.android.internal.util.Preconditions.checkArgumentFinite;

/**
 * Immutable class for describing width and height dimensions in some arbitrary
 * unit.
 * <p>
 * Width and height are finite values stored as a floating point representation.
 * </p>
 */
public final class SizeF {
    /**
     * Create a new immutable SizeF instance.
     *
     * <p>Both the {@code width} and the {@code height} must be a finite number.
     * In particular, {@code NaN} and positive/negative infinity are illegal values.</p>
     *
     * @param width The width of the size
     * @param height The height of the size
     *
     * @throws IllegalArgumentException
     *             if either {@code width} or {@code height} was not finite.
     */
    public SizeF(final float width, final float height) {
        mWidth = checkArgumentFinite(width, "width");
        mHeight = checkArgumentFinite(height, "height");
    }

    /**
     * Get the width of the size (as an arbitrary unit).
     * @return width
     */
    public float getWidth() {
        return mWidth;
    }

    /**
     * Get the height of the size (as an arbitrary unit).
     * @return height
     */
    public float getHeight() {
        return mHeight;
    }

    /**
     * Check if this size is equal to another size.
     *
     * <p>Two sizes are equal if and only if both their widths and heights are the same.</p>
     *
     * <p>For this purpose, the width/height float values are considered to be the same if and only
     * if the method {@link Float#floatToIntBits(float)} returns the identical {@code int} value
     * when applied to each.</p>
     *
     * @return {@code true} if the objects were equal, {@code false} otherwise
     */
    @Override
    public boolean equals(final Object obj) {
        if (obj == null) {
            return false;
        }
        if (this == obj) {
            return true;
        }
        if (obj instanceof SizeF) {
            final SizeF other = (SizeF) obj;
            return mWidth == other.mWidth && mHeight == other.mHeight;
        }
        return false;
    }

    /**
     * Return the size represented as a string with the format {@code "WxH"}
     *
     * @return string representation of the size
     */
    @Override
    public String toString() {
        return mWidth + "x" + mHeight;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return Float.floatToIntBits(mWidth) ^ Float.floatToIntBits(mHeight);
    }

    private final float mWidth;
    private final float mHeight;
}
