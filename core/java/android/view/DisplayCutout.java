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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.util.PathParser;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents the area of the display that is not functional for displaying content.
 *
 * <p>{@code DisplayCutout} is immutable.
 */
public final class DisplayCutout {

    private static final String TAG = "DisplayCutout";
    private static final String BOTTOM_MARKER = "@bottom";
    private static final String DP_MARKER = "@dp";
    private static final String RIGHT_MARKER = "@right";

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
    public static final DisplayCutout NO_CUTOUT = new DisplayCutout(ZERO_RECT, EMPTY_REGION,
            false /* copyArguments */);


    private static final Pair<Path, DisplayCutout> NULL_PAIR = new Pair<>(null, null);
    private static final Object CACHE_LOCK = new Object();

    @GuardedBy("CACHE_LOCK")
    private static String sCachedSpec;
    @GuardedBy("CACHE_LOCK")
    private static int sCachedDisplayWidth;
    @GuardedBy("CACHE_LOCK")
    private static int sCachedDisplayHeight;
    @GuardedBy("CACHE_LOCK")
    private static float sCachedDensity;
    @GuardedBy("CACHE_LOCK")
    private static Pair<Path, DisplayCutout> sCachedCutout = NULL_PAIR;

    private final Rect mSafeInsets;
    private final Region mBounds;

    /**
     * Creates a DisplayCutout instance.
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param boundingRects the bounding rects of the display cutouts as returned by
     *               {@link #getBoundingRects()} ()}.
     */
    // TODO(b/73953958): @VisibleForTesting(visibility = PRIVATE)
    public DisplayCutout(Rect safeInsets, List<Rect> boundingRects) {
        this(safeInsets != null ? new Rect(safeInsets) : ZERO_RECT,
                boundingRectsToRegion(boundingRects),
                true /* copyArguments */);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * @param copyArguments if true, create a copy of the arguments. If false, the passed arguments
     *                      are not copied and MUST remain unchanged forever.
     */
    private DisplayCutout(Rect safeInsets, Region bounds, boolean copyArguments) {
        mSafeInsets = safeInsets == null ? ZERO_RECT :
                (copyArguments ? new Rect(safeInsets) : safeInsets);
        mBounds = bounds == null ? Region.obtain() :
                (copyArguments ? Region.obtain(bounds) : bounds);
    }

    /**
     * Returns true if the safe insets are empty (and therefore the current view does not
     * overlap with the cutout or cutout area).
     *
     * @hide
     */
    public boolean isEmpty() {
        return mSafeInsets.equals(ZERO_RECT);
    }

    /**
     * Returns true if there is no cutout, i.e. the bounds are empty.
     *
     * @hide
     */
    public boolean isBoundsEmpty() {
        return mBounds.isEmpty();
    }

    /** Returns the inset from the top which avoids the display cutout in pixels. */
    public int getSafeInsetTop() {
        return mSafeInsets.top;
    }

    /** Returns the inset from the bottom which avoids the display cutout in pixels. */
    public int getSafeInsetBottom() {
        return mSafeInsets.bottom;
    }

    /** Returns the inset from the left which avoids the display cutout in pixels. */
    public int getSafeInsetLeft() {
        return mSafeInsets.left;
    }

    /** Returns the inset from the right which avoids the display cutout in pixels. */
    public int getSafeInsetRight() {
        return mSafeInsets.right;
    }

    /**
     * Returns the safe insets in a rect in pixel units.
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
     * <p>
     * <strong>Note:</strong> There may be more than one cutout, in which case the returned
     * {@code Region} will be non-contiguous and its bounding rect will be meaningless without
     * intersecting it first.
     *
     * Example:
     * <pre>
     *     // Getting the bounding rectangle of the top display cutout
     *     Region bounds = displayCutout.getBounds();
     *     bounds.op(0, 0, Integer.MAX_VALUE, displayCutout.getSafeInsetTop(), Region.Op.INTERSECT);
     *     Rect topDisplayCutout = bounds.getBoundingRect();
     * </pre>
     *
     * @return the bounding region of the cutout. Coordinates are relative
     *         to the top-left corner of the content view and in pixel units.
     * @hide
     */
    public Region getBounds() {
        return Region.obtain(mBounds);
    }

    /**
     * Returns a list of {@code Rect}s, each of which is the bounding rectangle for a non-functional
     * area on the display.
     *
     * There will be at most one non-functional area per short edge of the device, and none on
     * the long edges.
     *
     * @return a list of bounding {@code Rect}s, one for each display cutout area.
     */
    public List<Rect> getBoundingRects() {
        List<Rect> result = new ArrayList<>();
        Region bounds = Region.obtain();
        // top
        bounds.set(mBounds);
        bounds.op(0, 0, Integer.MAX_VALUE, getSafeInsetTop(), Region.Op.INTERSECT);
        if (!bounds.isEmpty()) {
            result.add(bounds.getBounds());
        }
        // left
        bounds.set(mBounds);
        bounds.op(0, 0, getSafeInsetLeft(), Integer.MAX_VALUE, Region.Op.INTERSECT);
        if (!bounds.isEmpty()) {
            result.add(bounds.getBounds());
        }
        // right & bottom
        bounds.set(mBounds);
        bounds.op(getSafeInsetLeft() + 1, getSafeInsetTop() + 1,
                Integer.MAX_VALUE, Integer.MAX_VALUE, Region.Op.INTERSECT);
        if (!bounds.isEmpty()) {
            result.add(bounds.getBounds());
        }
        bounds.recycle();
        return result;
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
                + " boundingRect=" + mBounds.getBounds()
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
        return new DisplayCutout(safeInsets, bounds, false /* copyArguments */);
    }

    /**
     * Returns a copy of this instance with the safe insets replaced with the parameter.
     *
     * @param safeInsets the new safe insets in pixels
     * @return a copy of this instance with the safe insets replaced with the argument.
     *
     * @hide
     */
    public DisplayCutout replaceSafeInsets(Rect safeInsets) {
        return new DisplayCutout(new Rect(safeInsets), mBounds, false /* copyArguments */);
    }

    private static int atLeastZero(int value) {
        return value < 0 ? 0 : value;
    }


    /**
     * Creates an instance from a bounding rect.
     *
     * @hide
     */
    public static DisplayCutout fromBoundingRect(int left, int top, int right, int bottom) {
        Path path = new Path();
        path.reset();
        path.moveTo(left, top);
        path.lineTo(left, bottom);
        path.lineTo(right, bottom);
        path.lineTo(right, top);
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
        return new DisplayCutout(ZERO_RECT, bounds, false /* copyArguments */);
    }

    /**
     * Creates the bounding path according to @android:string/config_mainBuiltInDisplayCutout.
     *
     * @hide
     */
    public static DisplayCutout fromResources(Resources res, int displayWidth, int displayHeight) {
        return fromSpec(res.getString(R.string.config_mainBuiltInDisplayCutout),
                displayWidth, displayHeight, res.getDisplayMetrics().density);
    }

    /**
     * Creates an instance according to @android:string/config_mainBuiltInDisplayCutout.
     *
     * @hide
     */
    public static Path pathFromResources(Resources res, int displayWidth, int displayHeight) {
        return pathAndDisplayCutoutFromSpec(res.getString(R.string.config_mainBuiltInDisplayCutout),
                displayWidth, displayHeight, res.getDisplayMetrics().density).first;
    }

    /**
     * Creates an instance according to the supplied {@link android.util.PathParser.PathData} spec.
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public static DisplayCutout fromSpec(String spec, int displayWidth, int displayHeight,
            float density) {
        return pathAndDisplayCutoutFromSpec(spec, displayWidth, displayHeight, density).second;
    }

    private static Pair<Path, DisplayCutout> pathAndDisplayCutoutFromSpec(String spec,
            int displayWidth, int displayHeight, float density) {
        if (TextUtils.isEmpty(spec)) {
            return NULL_PAIR;
        }
        synchronized (CACHE_LOCK) {
            if (spec.equals(sCachedSpec) && sCachedDisplayWidth == displayWidth
                    && sCachedDisplayHeight == displayHeight
                    && sCachedDensity == density) {
                return sCachedCutout;
            }
        }
        spec = spec.trim();
        final float offsetX;
        if (spec.endsWith(RIGHT_MARKER)) {
            offsetX = displayWidth;
            spec = spec.substring(0, spec.length() - RIGHT_MARKER.length()).trim();
        } else {
            offsetX = displayWidth / 2f;
        }
        final boolean inDp = spec.endsWith(DP_MARKER);
        if (inDp) {
            spec = spec.substring(0, spec.length() - DP_MARKER.length());
        }

        String bottomSpec = null;
        if (spec.contains(BOTTOM_MARKER)) {
            String[] splits = spec.split(BOTTOM_MARKER, 2);
            spec = splits[0].trim();
            bottomSpec = splits[1].trim();
        }

        final Path p;
        try {
            p = PathParser.createPathFromPathData(spec);
        } catch (Throwable e) {
            Log.wtf(TAG, "Could not inflate cutout: ", e);
            return NULL_PAIR;
        }

        final Matrix m = new Matrix();
        if (inDp) {
            m.postScale(density, density);
        }
        m.postTranslate(offsetX, 0);
        p.transform(m);

        if (bottomSpec != null) {
            final Path bottomPath;
            try {
                bottomPath = PathParser.createPathFromPathData(bottomSpec);
            } catch (Throwable e) {
                Log.wtf(TAG, "Could not inflate bottom cutout: ", e);
                return NULL_PAIR;
            }
            // Keep top transform
            m.postTranslate(0, displayHeight);
            bottomPath.transform(m);
            p.addPath(bottomPath);
        }

        final Pair<Path, DisplayCutout> result = new Pair<>(p, fromBounds(p));
        synchronized (CACHE_LOCK) {
            sCachedSpec = spec;
            sCachedDisplayWidth = displayWidth;
            sCachedDisplayHeight = displayHeight;
            sCachedDensity = density;
            sCachedCutout = result;
        }
        return result;
    }

    private static Region boundingRectsToRegion(List<Rect> rects) {
        Region result = Region.obtain();
        if (rects != null) {
            for (Rect r : rects) {
                result.op(r, Region.Op.UNION);
            }
        }
        return result;
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

            return new DisplayCutout(safeInsets, bounds, false /* copyArguments */);
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
