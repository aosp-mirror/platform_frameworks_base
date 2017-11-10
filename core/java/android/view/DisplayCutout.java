/*
 * Copyright 2017 The Android Open Source Project
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

import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a part of the display that is not functional for displaying content.
 *
 * <p>{@code DisplayCutout} is immutable.
 *
 * @hide will become API
 */
public final class DisplayCutout {

    private static final Rect ZERO_RECT = new Rect(0, 0, 0, 0);
    private static final ArrayList<Point> EMPTY_LIST = new ArrayList<>();

    /**
     * An instance where {@link #hasCutout()} returns {@code false}.
     *
     * @hide
     */
    public static final DisplayCutout NO_CUTOUT =
            new DisplayCutout(ZERO_RECT, ZERO_RECT, EMPTY_LIST);

    private final Rect mSafeInsets;
    private final Rect mBoundingRect;
    private final List<Point> mBoundingPolygon;

    /**
     * Creates a DisplayCutout instance.
     *
     * NOTE: the Rects passed into this instance are not copied and MUST remain unchanged.
     *
     * @hide
     */
    @VisibleForTesting
    public DisplayCutout(Rect safeInsets, Rect boundingRect, List<Point> boundingPolygon) {
        mSafeInsets = safeInsets != null ? safeInsets : ZERO_RECT;
        mBoundingRect = boundingRect != null ? boundingRect : ZERO_RECT;
        mBoundingPolygon = boundingPolygon != null ? boundingPolygon : EMPTY_LIST;
    }

    /**
     * Returns whether there is a cutout.
     *
     * If false, the safe insets will all return zero, and the bounding box or polygon will be
     * empty or outside the content view.
     *
     * @return {@code true} if there is a cutout, {@code false} otherwise
     */
    public boolean hasCutout() {
        return !mSafeInsets.equals(ZERO_RECT);
    }

    /** Returns the inset from the top which avoids the display cutout. */
    public int getSafeInsetTop() {
        return mSafeInsets.top;
    }

    /** Returns the inset from the bottom which avoids the display cutout. */
    public int getSafeInsetBottom() {
        return mSafeInsets.bottom;
    }

    /** Returns the inset from the left which avoids the display cutout. */
    public int getSafeInsetLeft() {
        return mSafeInsets.left;
    }

    /** Returns the inset from the right which avoids the display cutout. */
    public int getSafeInsetRight() {
        return mSafeInsets.right;
    }

    /**
     * Obtains the safe insets in a rect.
     *
     * @param out a rect which is set to the safe insets.
     * @hide
     */
    public void getSafeInsets(@NonNull Rect out) {
        out.set(mSafeInsets);
    }

    /**
     * Obtains the bounding rect of the cutout.
     *
     * @param outRect is filled with the bounding rect of the cutout. Coordinates are relative
     *         to the top-left corner of the content view.
     */
    public void getBoundingRect(@NonNull Rect outRect) {
        outRect.set(mBoundingRect);
    }

    /**
     * Obtains the bounding polygon of the cutout.
     *
     * @param outPolygon is filled with a list of points representing the corners of a convex
     *         polygon which covers the cutout. Coordinates are relative to the
     *         top-left corner of the content view.
     */
    public void getBoundingPolygon(List<Point> outPolygon) {
        outPolygon.clear();
        for (int i = 0; i < mBoundingPolygon.size(); i++) {
            outPolygon.add(new Point(mBoundingPolygon.get(i)));
        }
    }

    @Override
    public int hashCode() {
        int result = mSafeInsets.hashCode();
        result = result * 31 + mBoundingRect.hashCode();
        result = result * 31 + mBoundingPolygon.hashCode();
        return result;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayCutout) {
            DisplayCutout c = (DisplayCutout) o;
            return mSafeInsets.equals(c.mSafeInsets)
                    && mBoundingRect.equals(c.mBoundingRect)
                    && mBoundingPolygon.equals(c.mBoundingPolygon);
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayCutout{insets=" + mSafeInsets
                + " bounding=" + mBoundingRect
                + "}";
    }

    /**
     * Insets the reference frame of the cutout in the given directions.
     *
     * @return a copy of this instance which has been inset
     * @hide
     */
    public DisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        if (mBoundingRect.isEmpty()
                || insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0) {
            return this;
        }

        Rect safeInsets = new Rect(mSafeInsets);
        Rect boundingRect = new Rect(mBoundingRect);
        ArrayList<Point> boundingPolygon = new ArrayList<>();
        getBoundingPolygon(boundingPolygon);

        // Note: it's not really well defined what happens when the inset is negative, because we
        // don't know if the safe inset needs to expand in general.
        if (insetTop > 0 || safeInsets.top > 0) {
            safeInsets.top = atLeastZero(safeInsets.top - insetTop);
        }
        if (insetBottom > 0 || safeInsets.bottom > 0) {
            safeInsets.bottom = atLeastZero(safeInsets.bottom - insetBottom);
        }
        if (insetLeft > 0 || safeInsets.left > 0) {
            safeInsets.left = atLeastZero(safeInsets.left - insetLeft);
        }
        if (insetRight > 0 || safeInsets.right > 0) {
            safeInsets.right = atLeastZero(safeInsets.right - insetRight);
        }

        boundingRect.offset(-insetLeft, -insetTop);
        offset(boundingPolygon, -insetLeft, -insetTop);

        return new DisplayCutout(safeInsets, boundingRect, boundingPolygon);
    }

    /**
     * Calculates the safe insets relative to the given reference frame.
     *
     * @return a copy of this instance with the safe insets calculated
     * @hide
     */
    public DisplayCutout calculateRelativeTo(Rect frame) {
        if (mBoundingRect.isEmpty() || !Rect.intersects(frame, mBoundingRect)) {
            return NO_CUTOUT;
        }

        Rect boundingRect = new Rect(mBoundingRect);
        ArrayList<Point> boundingPolygon = new ArrayList<>();
        getBoundingPolygon(boundingPolygon);

        return DisplayCutout.calculateRelativeTo(frame, boundingRect, boundingPolygon);
    }

    private static DisplayCutout calculateRelativeTo(Rect frame, Rect boundingRect,
            ArrayList<Point> boundingPolygon) {
        Rect safeRect = new Rect();
        int bestArea = 0;
        int bestVariant = 0;
        for (int variant = ROTATION_0; variant <= ROTATION_270; variant++) {
            int area = calculateInsetVariantArea(frame, boundingRect, variant, safeRect);
            if (bestArea < area) {
                bestArea = area;
                bestVariant = variant;
            }
        }
        calculateInsetVariantArea(frame, boundingRect, bestVariant, safeRect);
        if (safeRect.isEmpty()) {
            // The entire frame overlaps with the cutout.
            safeRect.set(0, frame.height(), 0, 0);
        } else {
            // Convert safeRect to insets relative to frame. We're reusing the rect here to avoid
            // an allocation.
            safeRect.set(
                    Math.max(0, safeRect.left - frame.left),
                    Math.max(0, safeRect.top - frame.top),
                    Math.max(0, frame.right - safeRect.right),
                    Math.max(0, frame.bottom - safeRect.bottom));
        }

        boundingRect.offset(-frame.left, -frame.top);
        offset(boundingPolygon, -frame.left, -frame.top);

        return new DisplayCutout(safeRect, boundingRect, boundingPolygon);
    }

    private static int calculateInsetVariantArea(Rect frame, Rect boundingRect, int variant,
            Rect outSafeRect) {
        switch (variant) {
            case ROTATION_0:
                outSafeRect.set(frame.left, frame.top, frame.right, boundingRect.top);
                break;
            case ROTATION_90:
                outSafeRect.set(frame.left, frame.top, boundingRect.left, frame.bottom);
                break;
            case ROTATION_180:
                outSafeRect.set(frame.left, boundingRect.bottom, frame.right, frame.bottom);
                break;
            case ROTATION_270:
                outSafeRect.set(boundingRect.right, frame.top, frame.right, frame.bottom);
                break;
        }

        return outSafeRect.isEmpty() ? 0 : outSafeRect.width() * outSafeRect.height();
    }

    private static int atLeastZero(int value) {
        return value < 0 ? 0 : value;
    }

    private static void offset(ArrayList<Point> points, int dx, int dy) {
        for (int i = 0; i < points.size(); i++) {
            points.get(i).offset(dx, dy);
        }
    }

    /**
     * Creates an instance from a bounding polygon.
     *
     * @hide
     */
    public static DisplayCutout fromBoundingPolygon(List<Point> points) {
        Rect boundingRect = new Rect(Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE);
        ArrayList<Point> boundingPolygon = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            boundingRect.left = Math.min(boundingRect.left, point.x);
            boundingRect.right = Math.max(boundingRect.right, point.x);
            boundingRect.top = Math.min(boundingRect.top, point.y);
            boundingRect.bottom = Math.max(boundingRect.bottom, point.y);
            boundingPolygon.add(new Point(point));
        }

        return new DisplayCutout(ZERO_RECT, boundingRect, boundingPolygon);
    }

    /**
     * Helper class for passing {@link DisplayCutout} through binder.
     *
     * Needed, because {@code readFromParcel} cannot be used with immutable classes.
     *
     * @hide
     */
    public static final class ParcelableWrapper implements Parcelable {

        private DisplayCutout mInner;

        public ParcelableWrapper() {
            this(NO_CUTOUT);
        }

        public ParcelableWrapper(DisplayCutout cutout) {
            mInner = cutout;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            if (mInner == NO_CUTOUT) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                out.writeTypedObject(mInner.mSafeInsets, flags);
                out.writeTypedObject(mInner.mBoundingRect, flags);
                out.writeTypedList(mInner.mBoundingPolygon, flags);
            }
        }

        /**
         * Similar to {@link Creator#createFromParcel(Parcel)}, but reads into an existing
         * instance.
         *
         * Needed for AIDL out parameters.
         */
        public void readFromParcel(Parcel in) {
            mInner = readCutout(in);
        }

        public static final Creator<ParcelableWrapper> CREATOR = new Creator<ParcelableWrapper>() {
            @Override
            public ParcelableWrapper createFromParcel(Parcel in) {
                return new ParcelableWrapper(readCutout(in));
            }

            @Override
            public ParcelableWrapper[] newArray(int size) {
                return new ParcelableWrapper[size];
            }
        };

        private static DisplayCutout readCutout(Parcel in) {
            if (in.readInt() == 0) {
                return NO_CUTOUT;
            }

            ArrayList<Point> boundingPolygon = new ArrayList<>();

            Rect safeInsets = in.readTypedObject(Rect.CREATOR);
            Rect boundingRect = in.readTypedObject(Rect.CREATOR);
            in.readTypedList(boundingPolygon, Point.CREATOR);

            return new DisplayCutout(safeInsets, boundingRect, boundingPolygon);
        }

        public DisplayCutout get() {
            return mInner;
        }

        public void set(ParcelableWrapper cutout) {
            mInner = cutout.get();
        }

        public void set(DisplayCutout cutout) {
            mInner = cutout;
        }

        @Override
        public int hashCode() {
            return mInner.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParcelableWrapper
                    && mInner.equals(((ParcelableWrapper) o).mInner);
        }

        @Override
        public String toString() {
            return String.valueOf(mInner);
        }
    }
}
