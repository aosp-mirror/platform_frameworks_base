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

package android.hardware.camera2.params;

import android.util.Size;
import static com.android.internal.util.Preconditions.*;

import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.utils.HashCodeHelpers;

/**
 * An immutable class to represent a rectangle {@code (x, y, width, height)} with an additional
 * weight component.
 * <p>
 * The rectangle is defined to be inclusive of the specified coordinates.
 * </p>
 * <p>
 * When used with a {@link CaptureRequest}, the coordinate system is based on the active pixel
 * array, with {@code (0,0)} being the top-left pixel in the
 * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE active pixel array}, and
 * {@code (android.sensor.info.activeArraySize.width - 1,
 * android.sensor.info.activeArraySize.height - 1)} being the bottom-right pixel in the active pixel
 * array.
 * </p>
 * <p>
 * The weight must range from {@value #METERING_WEIGHT_MIN} to {@value #METERING_WEIGHT_MAX}
 * inclusively, and represents a weight for every pixel in the area. This means that a large
 * metering area with the same weight as a smaller area will have more effect in the metering
 * result. Metering areas can partially overlap and the camera device will add the weights in the
 * overlap rectangle.
 * </p>
 * <p>
 * If all rectangles have 0 weight, then no specific metering area needs to be used by the camera
 * device. If the metering rectangle is outside the used android.scaler.cropRegion returned in
 * capture result metadata, the camera device will ignore the sections outside the rectangle and
 * output the used sections in the result metadata.
 * </p>
 */
public final class MeteringRectangle {
    /**
     * The minimum value of valid metering weight.
     */
    public static final int METERING_WEIGHT_MIN = 0;

    /**
     * The maximum value of valid metering weight.
     */
    public static final int METERING_WEIGHT_MAX = 1000;

    /**
     * Weights set to this value will cause the camera device to ignore this rectangle.
     * If all metering rectangles are weighed with 0, the camera device will choose its own metering
     * rectangles.
     */
    public static final int METERING_WEIGHT_DONT_CARE = 0;

    private final int mX;
    private final int mY;
    private final int mWidth;
    private final int mHeight;
    private final int mWeight;

    /**
     * Create a new metering rectangle.
     *
     * @param x coordinate >= 0
     * @param y coordinate >= 0
     * @param width width >= 0
     * @param height height >= 0
     * @param meteringWeight weight between {@value #METERING_WEIGHT_MIN} and
     *        {@value #METERING_WEIGHT_MAX} inclusively
     * @throws IllegalArgumentException if any of the parameters were negative
     */
    public MeteringRectangle(int x, int y, int width, int height, int meteringWeight) {
        mX = checkArgumentNonnegative(x, "x must be nonnegative");
        mY = checkArgumentNonnegative(y, "y must be nonnegative");
        mWidth = checkArgumentNonnegative(width, "width must be nonnegative");
        mHeight = checkArgumentNonnegative(height, "height must be nonnegative");
        mWeight = checkArgumentInRange(
                meteringWeight, METERING_WEIGHT_MIN, METERING_WEIGHT_MAX, "meteringWeight");
    }

    /**
     * Create a new metering rectangle.
     *
     * <p>The point {@code xy}'s data is copied; the reference is not retained.</p>
     *
     * @param xy a non-{@code null} {@link Point} with both x,y >= 0
     * @param dimensions a non-{@code null} {@link android.util.Size Size} with width, height >= 0
     * @param meteringWeight weight >= 0
     *
     * @throws IllegalArgumentException if any of the parameters were negative
     * @throws NullPointerException if any of the arguments were null
     */
    public MeteringRectangle(Point xy, Size dimensions, int meteringWeight) {
        checkNotNull(xy, "xy must not be null");
        checkNotNull(dimensions, "dimensions must not be null");

        mX = checkArgumentNonnegative(xy.x, "x must be nonnegative");
        mY = checkArgumentNonnegative(xy.y, "y must be nonnegative");
        mWidth = checkArgumentNonnegative(dimensions.getWidth(), "width must be nonnegative");
        mHeight = checkArgumentNonnegative(dimensions.getHeight(), "height must be nonnegative");
        mWeight = checkArgumentNonnegative(meteringWeight, "meteringWeight must be nonnegative");
    }

    /**
     * Create a new metering rectangle.
     *
     * <p>The rectangle data is copied; the reference is not retained.</p>
     *
     * @param rect a non-{@code null} rectangle with all x,y,w,h dimensions >= 0
     * @param meteringWeight weight >= 0
     *
     * @throws IllegalArgumentException if any of the parameters were negative
     * @throws NullPointerException if any of the arguments were null
     */
    public MeteringRectangle(Rect rect, int meteringWeight) {
        checkNotNull(rect, "rect must not be null");

        mX = checkArgumentNonnegative(rect.left, "rect.left must be nonnegative");
        mY = checkArgumentNonnegative(rect.top, "rect.top must be nonnegative");
        mWidth = checkArgumentNonnegative(rect.width(), "rect.width must be nonnegative");
        mHeight = checkArgumentNonnegative(rect.height(), "rect.height must be nonnegative");
        mWeight = checkArgumentNonnegative(meteringWeight, "meteringWeight must be nonnegative");
    }

    /**
     * Return the X coordinate of the left side of the rectangle.
     *
     * @return x coordinate >= 0
     */
    public int getX() {
        return mX;
    }

    /**
     * Return the Y coordinate of the upper side of the rectangle.
     *
     * @return y coordinate >= 0
     */
    public int getY() {
        return mY;
    }

    /**
     * Return the width of the rectangle.
     *
     * @return width >= 0
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Return the height of the rectangle.
     *
     * @return height >= 0
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Return the metering weight of the rectangle.
     *
     * @return weight >= 0
     */
    public int getMeteringWeight() {
        return mWeight;
    }

    /**
     * Convenience method to create the upper-left (X,Y) coordinate as a {@link Point}.
     *
     * @return a new {@code (x,y)} {@link Point} with both x,y >= 0
     */
    public Point getUpperLeftPoint() {
        return new Point(mX, mY);
    }

    /**
     * Convenience method to create the size from this metering rectangle.
     *
     * <p>This strips away the X,Y,weight from the rectangle.</p>
     *
     * @return a new {@link Size} with non-negative width and height
     */
    public Size getSize() {
        return new Size(mWidth, mHeight);
    }

    /**
     * Convenience method to create a {@link Rect} from this metering rectangle.
     *
     * <p>This strips away the weight from the rectangle.</p>
     *
     * @return a new {@link Rect} with non-negative x1, y1, x2, y2
     */
    public Rect getRect() {
        return new Rect(mX, mY, mX + mWidth, mY + mHeight);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals(final Object other) {
        return other instanceof MeteringRectangle && equals((MeteringRectangle)other);
    }

    /**
     * Compare two metering rectangles to see if they are equal.
     *
     * Two weighted rectangles are only considered equal if each of their components
     * (x, y, width, height, weight) is respectively equal.
     *
     * @param other Another MeteringRectangle
     *
     * @return {@code true} if the metering rectangles are equal, {@code false} otherwise
     */
    public boolean equals(final MeteringRectangle other) {
        if (other == null) {
            return false;
        }

        return (mX == other.mX
                && mY == other.mY
                && mWidth == other.mWidth
                && mHeight == other.mHeight
                && mWeight == other.mWeight);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mX, mY, mWidth, mHeight, mWeight);
    }

    /**
     * Return the metering rectangle as a string representation
     * {@code "(x:%d, y:%d, w:%d, h:%d, wt:%d)"} where each {@code %d} respectively represents
     * the x, y, width, height, and weight points.
     *
     * @return string representation of the metering rectangle
     */
    @Override
    public String toString() {
        return String.format("(x:%d, y:%d, w:%d, h:%d, wt:%d)", mX, mY, mWidth, mHeight, mWeight);
    }
}
