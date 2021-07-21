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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents a rounded corner of the display.
 * <p>
 * <img src="{@docRoot}reference/android/images/rounded_corner/rounded-corner-info.png" height="120"
 * alt="A figure to describe what the rounded corner radius and the center point are. "/>
 * </p>
 *
 * <p>Note: The rounded corner formed by the radius and the center is an approximation.</p>
 *
 * <p>{@link RoundedCorner} is immutable.</p>
 */
public final class RoundedCorner implements Parcelable {

    /**
     * The rounded corner is at the top-left of the screen.
     */
    public static final int POSITION_TOP_LEFT = 0;
    /**
     * The rounded corner is at the top-right of the screen.
     */
    public static final int POSITION_TOP_RIGHT = 1;
    /**
     * The rounded corner is at the bottom-right of the screen.
     */
    public static final int POSITION_BOTTOM_RIGHT = 2;
    /**
     * The rounded corner is at the bottom-left of the screen.
     */
    public static final int POSITION_BOTTOM_LEFT = 3;

    /**
     * @hide
     */
    @IntDef(prefix = { "POSITION_" }, value = {
            POSITION_TOP_LEFT,
            POSITION_TOP_RIGHT,
            POSITION_BOTTOM_RIGHT,
            POSITION_BOTTOM_LEFT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Position {}

    private final @Position int mPosition;
    private final int mRadius;
    @NonNull
    private final Point mCenter;

    /**
     * Creates an empty {@link RoundedCorner} on the given position.
     * @hide
     */
    @VisibleForTesting
    public RoundedCorner(@Position int position) {
        mPosition = position;
        mRadius = 0;
        mCenter = new Point(0, 0);
    }

    /**
     * Creates a {@link RoundedCorner}.
     *
     * <p>Note that this is only useful for tests. For production code, developers should always
     * use a {@link RoundedCorner} obtained from the system via
     * {@link WindowInsets#getRoundedCorner} or {@link Display#getRoundedCorner}.</p>
     *
     * @param position the position of the rounded corner.
     * @param radius the radius of the rounded corner.
     * @param centerX the x of center point of the rounded corner.
     * @param centerY the y of center point of the rounded corner.
     *
     */
    public RoundedCorner(@Position int position, int radius, int centerX,
            int centerY) {
        mPosition = position;
        mRadius = radius;
        mCenter = new Point(centerX, centerY);
    }

    /**
     * Creates a {@link RoundedCorner} from a passed in {@link RoundedCorner}.
     *
     * @hide
     */
    RoundedCorner(RoundedCorner rc) {
        mPosition = rc.getPosition();
        mRadius = rc.getRadius();
        mCenter = new Point(rc.getCenter());
    }

    /**
     * Get the position of this {@link RoundedCorner}.
     *
     * @see #POSITION_TOP_LEFT
     * @see #POSITION_TOP_RIGHT
     * @see #POSITION_BOTTOM_RIGHT
     * @see #POSITION_BOTTOM_LEFT
     */
    public @Position int getPosition() {
        return mPosition;
    }

    /**
     * Returns the radius of a quarter circle approximation of this {@link RoundedCorner}.
     *
     * @return the rounded corner radius of this {@link RoundedCorner}. Returns 0 if there is no
     * rounded corner.
     */
    public int getRadius() {
        return mRadius;
    }

    /**
     * Returns the circle center of a quarter circle approximation of this {@link RoundedCorner}.
     *
     * @return the center point of this {@link RoundedCorner} in the application's coordinate.
     */
    @NonNull
    public Point getCenter() {
        return new Point(mCenter);
    }

    /**
     * Checks whether this {@link RoundedCorner} exists and is inside the application's bounds.
     *
     * @return {@code false} if there is a rounded corner and is contained in the application's
     *                       bounds. Otherwise return {@code true}.
     *
     * @hide
     */
    public boolean isEmpty() {
        return mRadius == 0 || mCenter.x <= 0 || mCenter.y <= 0;
    }

    private String getPositionString(@Position int position) {
        switch (position) {
            case POSITION_TOP_LEFT:
                return "TopLeft";
            case POSITION_TOP_RIGHT:
                return "TopRight";
            case POSITION_BOTTOM_RIGHT:
                return "BottomRight";
            case POSITION_BOTTOM_LEFT:
                return "BottomLeft";
            default:
                return "Invalid";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof RoundedCorner) {
            RoundedCorner r = (RoundedCorner) o;
            return mPosition == r.mPosition && mRadius == r.mRadius
                    && mCenter.equals(r.mCenter);
        }
        return false;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + mPosition;
        result = 31 * result + mRadius;
        result = 31 * result + mCenter.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "RoundedCorner{"
                + "position=" + getPositionString(mPosition)
                + ", radius=" + mRadius
                + ", center=" + mCenter
                + '}';
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeInt(mPosition);
        out.writeInt(mRadius);
        out.writeInt(mCenter.x);
        out.writeInt(mCenter.y);
    }

    public static final @NonNull Creator<RoundedCorner> CREATOR = new Creator<RoundedCorner>() {
        @Override
        public RoundedCorner createFromParcel(Parcel in) {
            return new RoundedCorner(in.readInt(), in.readInt(), in.readInt(), in.readInt());
        }

        @Override
        public RoundedCorner[] newArray(int size) {
            return new RoundedCorner[size];
        }
    };
}
