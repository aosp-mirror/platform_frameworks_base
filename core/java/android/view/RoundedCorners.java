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

import static android.view.RoundedCorner.POSITION_BOTTOM_LEFT;
import static android.view.RoundedCorner.POSITION_BOTTOM_RIGHT;
import static android.view.RoundedCorner.POSITION_TOP_LEFT;
import static android.view.RoundedCorner.POSITION_TOP_RIGHT;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Point;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.view.RoundedCorner.Position;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * A class to create & manage all the {@link RoundedCorner} on the display.
 *
 * @hide
 */
public class RoundedCorners implements Parcelable {

    public static final RoundedCorners NO_ROUNDED_CORNERS = new RoundedCorners(
            new RoundedCorner(POSITION_TOP_LEFT), new RoundedCorner(POSITION_TOP_RIGHT),
            new RoundedCorner(POSITION_BOTTOM_RIGHT), new RoundedCorner(POSITION_BOTTOM_LEFT));

    /**
     * The number of possible positions at which rounded corners can be located.
     */
    public static final int ROUNDED_CORNER_POSITION_LENGTH = 4;

    private static final Object CACHE_LOCK = new Object();

    @GuardedBy("CACHE_LOCK")
    private static int sCachedDisplayWidth;
    @GuardedBy("CACHE_LOCK")
    private static int sCachedDisplayHeight;
    @GuardedBy("CACHE_LOCK")
    private static Pair<Integer, Integer> sCachedRadii;
    @GuardedBy("CACHE_LOCK")
    private static RoundedCorners sCachedRoundedCorners;

    @VisibleForTesting
    public final RoundedCorner[] mRoundedCorners;

    public RoundedCorners(RoundedCorner[] roundedCorners) {
        mRoundedCorners = roundedCorners;
    }

    public RoundedCorners(RoundedCorner topLeft, RoundedCorner topRight, RoundedCorner bottomRight,
            RoundedCorner bottomLeft) {
        mRoundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        mRoundedCorners[POSITION_TOP_LEFT] = topLeft;
        mRoundedCorners[POSITION_TOP_RIGHT] = topRight;
        mRoundedCorners[POSITION_BOTTOM_RIGHT] = bottomRight;
        mRoundedCorners[POSITION_BOTTOM_LEFT] = bottomLeft;
    }

    public RoundedCorners(RoundedCorners roundedCorners) {
        mRoundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; ++i) {
            mRoundedCorners[i] = new RoundedCorner(roundedCorners.mRoundedCorners[i]);
        }
    }

    /**
     * Creates the rounded corners according to @android:dimen/rounded_corner_radius,
     * @android:dimen/rounded_corner_radius_top and @android:dimen/rounded_corner_radius_bottom
     */
    public static RoundedCorners fromResources(
            Resources res, int displayWidth, int displayHeight) {
        return fromRadii(loadRoundedCornerRadii(res), displayWidth, displayHeight);
    }

    /**
     * Creates the rounded corners from radius
     */
    @VisibleForTesting
    public static RoundedCorners fromRadii(Pair<Integer, Integer> radii, int displayWidth,
            int displayHeight) {
        if (radii == null) {
            return null;
        }

        synchronized (CACHE_LOCK) {
            if (radii.equals(sCachedRadii) && sCachedDisplayWidth == displayWidth
                    && sCachedDisplayHeight == displayHeight) {
                return sCachedRoundedCorners;
            }
        }

        final RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        final int topRadius = radii.first > 0 ? radii.first : 0;
        final int bottomRadius = radii.second > 0 ? radii.second : 0;
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; i++) {
            roundedCorners[i] = createRoundedCorner(
                    i,
                    i <= POSITION_TOP_RIGHT ? topRadius : bottomRadius,
                    displayWidth,
                    displayHeight);
        }

        final RoundedCorners result = new RoundedCorners(roundedCorners);
        synchronized (CACHE_LOCK) {
            sCachedDisplayWidth = displayWidth;
            sCachedDisplayHeight = displayHeight;
            sCachedRadii = radii;
            sCachedRoundedCorners = result;
        }
        return result;
    }

    /**
     * Loads the rounded corner radii from resources.
     *
     * @param res
     * @return a Pair of radius. The first is the top rounded corner radius and second is the
     * bottom corner radius.
     */
    @Nullable
    private static Pair<Integer, Integer> loadRoundedCornerRadii(Resources res) {
        final int radiusDefault = res.getDimensionPixelSize(R.dimen.rounded_corner_radius);
        final int radiusTop = res.getDimensionPixelSize(R.dimen.rounded_corner_radius_top);
        final int radiusBottom = res.getDimensionPixelSize(R.dimen.rounded_corner_radius_bottom);
        if (radiusDefault == 0 && radiusTop == 0 && radiusBottom == 0) {
            return null;
        }
        final Pair<Integer, Integer> radii = new Pair<>(
                        radiusTop > 0 ? radiusTop : radiusDefault,
                        radiusBottom > 0 ? radiusBottom : radiusDefault);
        return radii;
    }

    /**
     * Insets the reference frame of the rounded corners.
     *
     * @return a copy of this instance which has been inset
     */
    public RoundedCorners inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        final RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; i++) {
            roundedCorners[i] = insetRoundedCorner(i, insetLeft, insetTop, insetRight, insetBottom);
        }
        return new RoundedCorners(roundedCorners);
    }

    private RoundedCorner insetRoundedCorner(@Position int position, int insetLeft,
            int insetTop, int insetRight, int insetBottom) {
        if (mRoundedCorners[position].isEmpty()) {
            return new RoundedCorner(position);
        }

        final int radius = mRoundedCorners[position].getRadius();
        final Point center = mRoundedCorners[position].getCenter();
        boolean hasRoundedCorner;
        switch (position) {
            case POSITION_TOP_LEFT:
                hasRoundedCorner = radius > insetTop && radius > insetLeft;
                break;
            case POSITION_TOP_RIGHT:
                hasRoundedCorner = radius > insetTop && radius > insetRight;
                break;
            case POSITION_BOTTOM_RIGHT:
                hasRoundedCorner = radius > insetBottom && radius > insetRight;
                break;
            case POSITION_BOTTOM_LEFT:
                hasRoundedCorner = radius > insetBottom && radius > insetLeft;
                break;
            default:
                throw new IllegalArgumentException(
                        "The position is not one of the RoundedCornerPosition =" + position);
        }
        return new RoundedCorner(
                position, radius,
                hasRoundedCorner ? center.x - insetLeft : 0,
                hasRoundedCorner ? center.y - insetTop : 0);
    }

    /**
     * Returns the {@link RoundedCorner} of the given position if there is one.
     *
     * @param position the position of the rounded corner on the display.
     * @return the rounded corner of the given position. Returns {@code null} if
     * {@link RoundedCorner#isEmpty()} is {@code true}.
     */
    @Nullable
    public RoundedCorner getRoundedCorner(@Position int position) {
        return mRoundedCorners[position].isEmpty()
                ? null : new RoundedCorner(mRoundedCorners[position]);
    }

    /**
     * Sets the rounded corner of given position.
     *
     * @param position the position of this rounded corner
     * @param roundedCorner the rounded corner or null if there is none
     */
    public void setRoundedCorner(@Position int position, @Nullable RoundedCorner roundedCorner) {
        mRoundedCorners[position] = roundedCorner == null
                ? new RoundedCorner(position) : roundedCorner;
    }

    /**
     * Returns an array of {@link RoundedCorner}s. Ordinal value of RoundedCornerPosition is used
     * as an index of the array.
     *
     * @return an array of {@link RoundedCorner}s, one for each rounded corner area.
     */
    public RoundedCorner[] getAllRoundedCorners() {
        RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; ++i) {
            roundedCorners[i] = new RoundedCorner(roundedCorners[i]);
        }
        return roundedCorners;
    }

    /**
     * Returns a scaled RoundedCorners.
     */
    public RoundedCorners scale(float scale) {
        if (scale == 1f) {
            return this;
        }

        RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        for (int i = 0; i < ROUNDED_CORNER_POSITION_LENGTH; ++i) {
            final RoundedCorner roundedCorner = mRoundedCorners[i];
            roundedCorners[i] = new RoundedCorner(
                    i,
                    (int) (roundedCorner.getRadius() * scale),
                    (int) (roundedCorner.getCenter().x * scale),
                    (int) (roundedCorner.getCenter().y * scale));
        }
        return new RoundedCorners(roundedCorners);
    }

    /**
     * Returns a rotated RoundedCorners.
     */
    public RoundedCorners rotate(@Surface.Rotation int rotation, int initialDisplayWidth,
            int initialDisplayHeight) {
        if (rotation == ROTATION_0) {
            return this;
        }
        final boolean isSizeFlipped = rotation == ROTATION_90 || rotation == ROTATION_270;
        RoundedCorner[] newCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
        int newPosistion;
        for (int i = 0; i < mRoundedCorners.length; i++) {
            newPosistion = getRotatedIndex(i, rotation);
            newCorners[newPosistion] = createRoundedCorner(
                    newPosistion,
                    mRoundedCorners[i].getRadius(),
                    isSizeFlipped ? initialDisplayHeight : initialDisplayWidth,
                    isSizeFlipped ? initialDisplayWidth : initialDisplayHeight);
        }
        return new RoundedCorners(newCorners);
    }

    private static RoundedCorner createRoundedCorner(@Position int position,
            int radius, int displayWidth, int displayHeight) {
        switch (position) {
            case POSITION_TOP_LEFT:
                return new RoundedCorner(
                        POSITION_TOP_LEFT,
                        radius,
                        radius > 0 ? radius : 0,
                        radius > 0 ? radius : 0);
            case POSITION_TOP_RIGHT:
                return new RoundedCorner(
                        POSITION_TOP_RIGHT,
                        radius,
                        radius > 0 ? displayWidth - radius : 0,
                        radius > 0 ? radius : 0);
            case POSITION_BOTTOM_RIGHT:
                return new RoundedCorner(
                        POSITION_BOTTOM_RIGHT,
                        radius,
                        radius > 0 ? displayWidth - radius : 0,
                        radius > 0 ? displayHeight - radius : 0);
            case POSITION_BOTTOM_LEFT:
                return new RoundedCorner(
                        POSITION_BOTTOM_LEFT,
                        radius,
                        radius > 0 ? radius : 0,
                        radius > 0 ? displayHeight - radius  : 0);
            default:
                throw new IllegalArgumentException(
                        "The position is not one of the RoundedCornerPosition =" + position);
        }
    }

    private static int getRotatedIndex(int position, int rotation) {
        return (position - rotation + ROUNDED_CORNER_POSITION_LENGTH) % 4;
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (RoundedCorner roundedCorner : mRoundedCorners) {
            result = result * 31 + roundedCorner.hashCode();
        }
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof RoundedCorners) {
            RoundedCorners r = (RoundedCorners) o;
            return Arrays.deepEquals(mRoundedCorners, r.mRoundedCorners);
        }
        return false;
    }

    @Override
    public String toString() {
        return "RoundedCorners{" + Arrays.toString(mRoundedCorners) + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (equals(NO_ROUNDED_CORNERS)) {
            dest.writeInt(0);
        } else {
            dest.writeInt(1);
            dest.writeTypedArray(mRoundedCorners, flags);
        }
    }

    public static final @NonNull Creator<RoundedCorners> CREATOR = new Creator<RoundedCorners>() {
        @Override
        public RoundedCorners createFromParcel(Parcel in) {
            int variant = in.readInt();
            if (variant == 0) {
                return NO_ROUNDED_CORNERS;
            }
            RoundedCorner[] roundedCorners = new RoundedCorner[ROUNDED_CORNER_POSITION_LENGTH];
            in.readTypedArray(roundedCorners, RoundedCorner.CREATOR);
            return new RoundedCorners(roundedCorners);
        }

        @Override
        public RoundedCorners[] newArray(int size) {
            return new RoundedCorners[size];
        }
    };
}
