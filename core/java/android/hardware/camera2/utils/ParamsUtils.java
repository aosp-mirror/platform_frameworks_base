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

package android.hardware.camera2.utils;

import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.camera2.CaptureRequest;
import android.util.Rational;
import android.util.Size;

import static com.android.internal.util.Preconditions.*;

/**
 * Various assortment of params utilities.
 */
public class ParamsUtils {

    /** Arbitrary denominator used to estimate floats as rationals */
    private static final int RATIONAL_DENOMINATOR = 1000000; // 1million

    /**
     * Create a {@link Rect} from a {@code Size} by creating a new rectangle with
     * left, top = {@code (0, 0)} and right, bottom = {@code (width, height)}
     *
     * @param size a non-{@code null} size
     *
     * @return a {@code non-null} rectangle
     *
     * @throws NullPointerException if {@code size} was {@code null}
     */
    public static Rect createRect(Size size) {
        checkNotNull(size, "size must not be null");

        return new Rect(/*left*/0, /*top*/0, size.getWidth(), size.getHeight());
    }

    /**
     * Create a {@link Rect} from a {@code RectF} by creating a new rectangle with
     * each corner (left, top, right, bottom) rounded towards the nearest integer bounding box.
     *
     * <p>In particular (left, top) is floored, and (right, bottom) is ceiled.</p>
     *
     * @param size a non-{@code null} rect
     *
     * @return a {@code non-null} rectangle
     *
     * @throws NullPointerException if {@code rect} was {@code null}
     */
    public static Rect createRect(RectF rect) {
        checkNotNull(rect, "rect must not be null");

        Rect r = new Rect();
        rect.roundOut(r);

        return r;
    }

    /**
     * Map the rectangle in {@code rect} with the transform in {@code transform} into
     * a new rectangle, with each corner (left, top, right, bottom) rounded towards the nearest
     * integer bounding box.
     *
     * <p>None of the arguments are mutated.</p>
     *
     * @param transform a non-{@code null} transformation matrix
     * @param rect a non-{@code null} rectangle
     * @return a new rectangle that was transformed by {@code transform}
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public static Rect mapRect(Matrix transform, Rect rect) {
        checkNotNull(transform, "transform must not be null");
        checkNotNull(rect, "rect must not be null");

        RectF rectF = new RectF(rect);
        transform.mapRect(rectF);
        return createRect(rectF);
    }

    /**
     * Create a {@link Size} from a {@code Rect} by creating a new size whose width
     * and height are the same as the rectangle's width and heights.
     *
     * @param rect a non-{@code null} rectangle
     *
     * @return a {@code non-null} size
     *
     * @throws NullPointerException if {@code rect} was {@code null}
     */
    public static Size createSize(Rect rect) {
        checkNotNull(rect, "rect must not be null");

        return new Size(rect.width(), rect.height());
    }

    /**
     * Create a {@link Rational} value by approximating the float value as a rational.
     *
     * <p>Floating points too large to be represented as an integer will be converted to
     * to {@link Integer#MAX_VALUE}; floating points too small to be represented as an integer
     * will be converted to {@link Integer#MIN_VALUE}.</p>
     *
     * @param value a floating point value
     * @return the rational representation of the float
     */
    public static Rational createRational(float value) {
        if (Float.isNaN(value)) {
            return Rational.NaN;
        } else if (value == Float.POSITIVE_INFINITY) {
            return Rational.POSITIVE_INFINITY;
        } else if (value == Float.NEGATIVE_INFINITY) {
            return Rational.NEGATIVE_INFINITY;
        } else if (value == 0.0f) {
            return Rational.ZERO;
        }

        // normal finite value: approximate it

        /*
         * Start out trying to approximate with denominator = 1million,
         * but if the numerator doesn't fit into an Int then keep making the denominator
         * smaller until it does.
         */
        int den = RATIONAL_DENOMINATOR;
        float numF;
        do {
            numF = value * den;

            if ((numF > Integer.MIN_VALUE && numF < Integer.MAX_VALUE) || (den == 1)) {
                break;
            }

            den /= 10;
        } while (true);

        /*
         *  By float -> int narrowing conversion in JLS 5.1.3, this will automatically become
         *  MIN_VALUE or MAX_VALUE if numF is too small/large to be represented by an integer
         */
        int num = (int) numF;

        return new Rational(num, den);
     }

    /**
     * Convert an integral rectangle ({@code source}) to a floating point rectangle
     * ({@code destination}) in-place.
     *
     * @param source the originating integer rectangle will be read from here
     * @param destination the resulting floating point rectangle will be written out to here
     *
     * @throws NullPointerException if {@code rect} was {@code null}
     */
    public static void convertRectF(Rect source, RectF destination) {
        checkNotNull(source, "source must not be null");
        checkNotNull(destination, "destination must not be null");

        destination.left = source.left;
        destination.right = source.right;
        destination.bottom = source.bottom;
        destination.top = source.top;
    }

    /**
     * Return the value set by the key, or the {@code defaultValue} if no value was set.
     *
     * @throws NullPointerException if any of the args were {@code null}
     */
    public static <T> T getOrDefault(CaptureRequest r, CaptureRequest.Key<T> key, T defaultValue) {
        checkNotNull(r, "r must not be null");
        checkNotNull(key, "key must not be null");
        checkNotNull(defaultValue, "defaultValue must not be null");

        T value = r.get(key);
        if (value == null) {
            return defaultValue;
        } else {
            return value;
        }
    }

    private ParamsUtils() {
        throw new AssertionError();
    }
}
