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
 * An immutable class to represent a rectangle {@code (x,y, width, height)} with an
 * additional weight component.
 *
 * </p>The rectangle is defined to be inclusive of the specified coordinates.</p>
 *
 * <p>When used with a {@link CaptureRequest}, the coordinate system is based on the active pixel
 * array, with {@code (0,0)} being the top-left pixel in the
 * {@link CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE active pixel array}, and
 * {@code (android.sensor.info.activeArraySize.width - 1,
 * android.sensor.info.activeArraySize.height - 1)}
 * being the bottom-right pixel in the active pixel array.
 * </p>
 *
 * <p>The metering weight is nonnegative.</p>
 */
public final class MeteringRectangle {

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
     * @param meteringWeight weight >= 0
     *
     * @throws IllegalArgumentException if any of the parameters were non-negative
     */
    public MeteringRectangle(int x, int y, int width, int height, int meteringWeight) {
        mX = checkArgumentNonnegative(x, "x must be nonnegative");
        mY = checkArgumentNonnegative(y, "y must be nonnegative");
        mWidth = checkArgumentNonnegative(width, "width must be nonnegative");
        mHeight = checkArgumentNonnegative(height, "height must be nonnegative");
        mWeight = checkArgumentNonnegative(meteringWeight, "meteringWeight must be nonnegative");
    }

    /**
     * Create a new metering rectangle.
     *
     * @param xy a non-{@code null} {@link Point} with both x,y >= 0
     * @param dimensions a non-{@code null} {@link android.util.Size Size} with width, height >= 0
     * @param meteringWeight weight >= 0
     *
     * @throws IllegalArgumentException if any of the parameters were non-negative
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
     * @param rect a non-{@code null} rectangle with all x,y,w,h dimensions >= 0
     * @param meteringWeight weight >= 0
     *
     * @throws IllegalArgumentException if any of the parameters were non-negative
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
     * @return {@code (x,y)} point with both x,y >= 0
     */
    public Point getUpperLeftPoint() {
        return new Point(mX, mY);
    }

    /**
     * Convenience method to create the size from this metering rectangle.
     *
     * <p>This strips away the X,Y,weight from the rectangle.</p>
     *
     * @return a Size with non-negative width and height
     */
    public Size getSize() {
        return new Size(mWidth, mHeight);
    }

    /**
     * Convenience method to create a {@link Rect} from this metering rectangle.
     *
     * <p>This strips away the weight from the rectangle.</p>
     *
     * @return a {@link Rect} with non-negative x1, y1, x2, y2
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
                && mWidth == other.mWidth);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return HashCodeHelpers.hashCode(mX, mY, mWidth, mHeight, mWeight);
    }
}
