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
import static com.android.internal.util.Preconditions.checkNotNull;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Immutable class for describing width and height dimensions in some arbitrary
 * unit.
 * <p>
 * Width and height are finite values stored as a floating point representation.
 * </p>
 */
public final class SizeF implements Parcelable {
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

    private static NumberFormatException invalidSizeF(String s) {
        throw new NumberFormatException("Invalid SizeF: \"" + s + "\"");
    }

    /**
     * Parses the specified string as a size value.
     * <p>
     * The ASCII characters {@code \}{@code u002a} ('*') and
     * {@code \}{@code u0078} ('x') are recognized as separators between
     * the width and height.</p>
     * <p>
     * For any {@code SizeF s}: {@code SizeF.parseSizeF(s.toString()).equals(s)}.
     * However, the method also handles sizes expressed in the
     * following forms:</p>
     * <p>
     * "<i>width</i>{@code x}<i>height</i>" or
     * "<i>width</i>{@code *}<i>height</i>" {@code => new SizeF(width, height)},
     * where <i>width</i> and <i>height</i> are string floats potentially
     * containing a sign, such as "-10.3", "+7" or "5.2", but not containing
     * an {@code 'x'} (such as a float in hexadecimal string format).</p>
     *
     * <pre>{@code
     * SizeF.parseSizeF("3.2*+6").equals(new SizeF(3.2f, 6.0f)) == true
     * SizeF.parseSizeF("-3x-6").equals(new SizeF(-3.0f, -6.0f)) == true
     * SizeF.parseSizeF("4 by 3") => throws NumberFormatException
     * }</pre>
     *
     * @param string the string representation of a size value.
     * @return the size value represented by {@code string}.
     *
     * @throws NumberFormatException if {@code string} cannot be parsed
     * as a size value.
     * @throws NullPointerException if {@code string} was {@code null}
     */
    public static SizeF parseSizeF(String string)
            throws NumberFormatException {
        checkNotNull(string, "string must not be null");

        int sep_ix = string.indexOf('*');
        if (sep_ix < 0) {
            sep_ix = string.indexOf('x');
        }
        if (sep_ix < 0) {
            throw invalidSizeF(string);
        }
        try {
            return new SizeF(Float.parseFloat(string.substring(0, sep_ix)),
                    Float.parseFloat(string.substring(sep_ix + 1)));
        } catch (NumberFormatException e) {
            throw invalidSizeF(string);
        } catch (IllegalArgumentException e) {
            throw invalidSizeF(string);
        }
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

    /**
     * Parcelable interface methods
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Write this size to the specified parcel. To restore a size from a parcel, use the
     * {@link #CREATOR}.
     * @param out The parcel to write the point's coordinates into
     */
    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeFloat(mWidth);
        out.writeFloat(mHeight);
    }

    public static final @NonNull Creator<SizeF> CREATOR = new Creator<SizeF>() {
        /**
         * Return a new size from the data in the specified parcel.
         */
        @Override
        public @NonNull SizeF createFromParcel(@NonNull Parcel in) {
            float width = in.readFloat();
            float height = in.readFloat();
            return new SizeF(width, height);
        }

        /**
         * Return an array of sizes of the specified size.
         */
        @Override
        public @NonNull SizeF[] newArray(int size) {
            return new SizeF[size];
        }
    };
}
