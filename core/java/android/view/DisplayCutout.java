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

import static android.view.DisplayCutoutProto.BOUNDS;
import static android.view.DisplayCutoutProto.INSETS;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;

import java.util.List;

/**
 * Represents a part of the display that is not functional for displaying content.
 *
 * <p>{@code DisplayCutout} is immutable.
 */
public final class DisplayCutout {

    private static final String TAG = "DisplayCutout";
    private static final String DP_MARKER = "@dp";

    /**
     * Category for overlays that allow emulating a display cutout on devices that don't have
     * one.
     *
     * @see android.content.om.IOverlayManager
     * @hide
     */
    public static final String EMULATION_OVERLAY_CATEGORY =
            "com.android.internal.display_cutout_emulation";

    private static final Rect ZERO_RECT = new Rect();
    private static final Region EMPTY_REGION = new Region();

    /**
     * An instance where {@link #isEmpty()} returns {@code true}.
     *
     * @hide
     */
    public static final DisplayCutout NO_CUTOUT = new DisplayCutout(ZERO_RECT, EMPTY_REGION);

    private final Rect mSafeInsets;
    private final Region mBounds;

    /**
     * Creates a DisplayCutout instance.
     *
     * NOTE: the Rects passed into this instance are not copied and MUST remain unchanged.
     *
     * @hide
     */
    @VisibleForTesting
    public DisplayCutout(Rect safeInsets, Region bounds) {
        mSafeInsets = safeInsets != null ? safeInsets : ZERO_RECT;
        mBounds = bounds != null ? bounds : Region.obtain();
    }

    /**
     * Returns true if there is no cutout or it is outside of the content view.
     *
     * @hide
     */
    public boolean isEmpty() {
        return mSafeInsets.equals(ZERO_RECT);
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
     * Returns the safe insets in a rect.
     *
     * @return a rect which is set to the safe insets.
     * @hide
     */
    public Rect getSafeInsets() {
        return new Rect(mSafeInsets);
    }

    /**
     * Returns the bounding region of the cutout.
     *
     * @return the bounding region of the cutout. Coordinates are relative
     *         to the top-left corner of the content view.
     */
    public Region getBounds() {
        return Region.obtain(mBounds);
    }

    /**
     * Returns the bounding rect of the cutout.
     *
     * @return the bounding rect of the cutout. Coordinates are relative
     *         to the top-left corner of the content view.
     * @hide
     */
    public Rect getBoundingRect() {
        // TODO(roosa): Inline.
        return mBounds.getBounds();
    }

    @Override
    public int hashCode() {
        int result = mSafeInsets.hashCode();
        result = result * 31 + mBounds.getBounds().hashCode();
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
                    && mBounds.equals(c.mBounds);
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayCutout{insets=" + mSafeInsets
                + " boundingRect=" + getBoundingRect()
                + "}";
    }

    /**
     * @hide
     */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mSafeInsets.writeToProto(proto, INSETS);
        mBounds.getBounds().writeToProto(proto, BOUNDS);
        proto.end(token);
    }

    /**
     * Insets the reference frame of the cutout in the given directions.
     *
     * @return a copy of this instance which has been inset
     * @hide
     */
    public DisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        if (mBounds.isEmpty()
                || insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0) {
            return this;
        }

        Rect safeInsets = new Rect(mSafeInsets);
        Region bounds = Region.obtain(mBounds);

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

        bounds.translate(-insetLeft, -insetTop);

        return new DisplayCutout(safeInsets, bounds);
    }

    /**
     * Calculates the safe insets relative to the given reference frame.
     *
     * @return a copy of this instance with the safe insets calculated
     * @hide
     */
    public DisplayCutout calculateRelativeTo(Rect frame) {
        if (mBounds.isEmpty() || !Rect.intersects(frame, mBounds.getBounds())) {
            return NO_CUTOUT;
        }

        return DisplayCutout.calculateRelativeTo(frame, Region.obtain(mBounds));
    }

    private static DisplayCutout calculateRelativeTo(Rect frame, Region bounds) {
        Rect boundingRect = bounds.getBounds();
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

        bounds.translate(-frame.left, -frame.top);

        return new DisplayCutout(safeRect, bounds);
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


    /**
     * Creates an instance from a bounding polygon.
     *
     * @hide
     */
    public static DisplayCutout fromBoundingPolygon(List<Point> points) {
        Path path = new Path();
        path.reset();
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            if (i == 0) {
                path.moveTo(point.x, point.y);
            } else {
                path.lineTo(point.x, point.y);
            }
        }
        path.close();
        return fromBounds(path);
    }

    /**
     * Creates an instance from a bounding {@link Path}.
     *
     * @hide
     */
    public static DisplayCutout fromBounds(Path path) {
        RectF clipRect = new RectF();
        path.computeBounds(clipRect, false /* unused */);
        Region clipRegion = Region.obtain();
        clipRegion.set((int) clipRect.left, (int) clipRect.top,
                (int) clipRect.right, (int) clipRect.bottom);

        Region bounds = new Region();
        bounds.setPath(path, clipRegion);
        clipRegion.recycle();
        return new DisplayCutout(ZERO_RECT, bounds);
    }

    /**
     * Creates an instance according to @android:string/config_mainBuiltInDisplayCutout.
     *
     * @hide
     */
    public static DisplayCutout fromResources(Resources res, int displayWidth) {
        String spec = res.getString(R.string.config_mainBuiltInDisplayCutout);
        if (TextUtils.isEmpty(spec)) {
            return null;
        }
        spec = spec.trim();
        final boolean inDp = spec.endsWith(DP_MARKER);
        if (inDp) {
            spec = spec.substring(0, spec.length() - DP_MARKER.length());
        }

        Path p;
        try {
            p = PathParser.createPathFromPathData(spec);
        } catch (Throwable e) {
            Log.wtf(TAG, "Could not inflate cutout: ", e);
            return null;
        }

        final Matrix m = new Matrix();
        if (inDp) {
            final float dpToPx = res.getDisplayMetrics().density;
            m.postScale(dpToPx, dpToPx);
        }
        m.postTranslate(displayWidth / 2f, 0);
        p.transform(m);
        return fromBounds(p);
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
            writeCutoutToParcel(mInner, out, flags);
        }

        /**
         * Writes a DisplayCutout to a {@link Parcel}.
         *
         * @see #readCutoutFromParcel(Parcel)
         */
        public static void writeCutoutToParcel(DisplayCutout cutout, Parcel out, int flags) {
            if (cutout == null) {
                out.writeInt(-1);
            } else if (cutout == NO_CUTOUT) {
                out.writeInt(0);
            } else {
                out.writeInt(1);
                out.writeTypedObject(cutout.mSafeInsets, flags);
                out.writeTypedObject(cutout.mBounds, flags);
            }
        }

        /**
         * Similar to {@link Creator#createFromParcel(Parcel)}, but reads into an existing
         * instance.
         *
         * Needed for AIDL out parameters.
         */
        public void readFromParcel(Parcel in) {
            mInner = readCutoutFromParcel(in);
        }

        public static final Creator<ParcelableWrapper> CREATOR = new Creator<ParcelableWrapper>() {
            @Override
            public ParcelableWrapper createFromParcel(Parcel in) {
                return new ParcelableWrapper(readCutoutFromParcel(in));
            }

            @Override
            public ParcelableWrapper[] newArray(int size) {
                return new ParcelableWrapper[size];
            }
        };

        /**
         * Reads a DisplayCutout from a {@link Parcel}.
         *
         * @see #writeCutoutToParcel(DisplayCutout, Parcel, int)
         */
        public static DisplayCutout readCutoutFromParcel(Parcel in) {
            int variant = in.readInt();
            if (variant == -1) {
                return null;
            }
            if (variant == 0) {
                return NO_CUTOUT;
            }

            Rect safeInsets = in.readTypedObject(Rect.CREATOR);
            Region bounds = in.readTypedObject(Region.CREATOR);

            return new DisplayCutout(safeInsets, bounds);
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
