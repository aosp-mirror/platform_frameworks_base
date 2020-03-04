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

import static android.util.DisplayMetrics.DENSITY_DEFAULT;
import static android.util.DisplayMetrics.DENSITY_DEVICE_STABLE;
import static android.view.DisplayCutoutProto.BOUND_BOTTOM;
import static android.view.DisplayCutoutProto.BOUND_LEFT;
import static android.view.DisplayCutoutProto.BOUND_RIGHT;
import static android.view.DisplayCutoutProto.BOUND_TOP;
import static android.view.DisplayCutoutProto.INSETS;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Region.Op;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static final String LEFT_MARKER = "@left";

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

    /**
     * An instance where {@link #isEmpty()} returns {@code true}.
     *
     * @hide
     */
    public static final DisplayCutout NO_CUTOUT = new DisplayCutout(
            ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT,
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


    /**
     * The bound is at the left of the screen.
     * @hide
     */
    public static final int BOUNDS_POSITION_LEFT = 0;

    /**
     * The bound is at the top of the screen.
     * @hide
     */
    public static final int BOUNDS_POSITION_TOP = 1;

    /**
     * The bound is at the right of the screen.
     * @hide
     */
    public static final int BOUNDS_POSITION_RIGHT = 2;

    /**
     * The bound is at the bottom of the screen.
     * @hide
     */
    public static final int BOUNDS_POSITION_BOTTOM = 3;

    /**
     * The number of possible positions at which bounds can be located.
     * @hide
     */
    public static final int BOUNDS_POSITION_LENGTH = 4;

    /** @hide */
    @IntDef(prefix = { "BOUNDS_POSITION_" }, value = {
            BOUNDS_POSITION_LEFT,
            BOUNDS_POSITION_TOP,
            BOUNDS_POSITION_RIGHT,
            BOUNDS_POSITION_BOTTOM
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BoundsPosition {}

    private static class Bounds {
        private final Rect[] mRects;

        private Bounds(Rect left, Rect top, Rect right, Rect bottom, boolean copyArguments) {
            mRects = new Rect[BOUNDS_POSITION_LENGTH];
            mRects[BOUNDS_POSITION_LEFT] = getCopyOrRef(left, copyArguments);
            mRects[BOUNDS_POSITION_TOP] = getCopyOrRef(top, copyArguments);
            mRects[BOUNDS_POSITION_RIGHT] = getCopyOrRef(right, copyArguments);
            mRects[BOUNDS_POSITION_BOTTOM] = getCopyOrRef(bottom, copyArguments);

        }

        private Bounds(Rect[] rects, boolean copyArguments) {
            if (rects.length != BOUNDS_POSITION_LENGTH) {
                throw new IllegalArgumentException(
                        "rects must have exactly 4 elements: rects=" + Arrays.toString(rects));
            }
            if (copyArguments) {
                mRects = new Rect[BOUNDS_POSITION_LENGTH];
                for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
                    mRects[i] = new Rect(rects[i]);
                }
            } else {
                for (Rect rect : rects) {
                    if (rect == null) {
                        throw new IllegalArgumentException(
                                "rects must have non-null elements: rects="
                                        + Arrays.toString(rects));
                    }
                }
                mRects = rects;
            }
        }

        private boolean isEmpty() {
            for (Rect rect : mRects) {
                if (!rect.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private Rect getRect(@BoundsPosition int pos) {
            return new Rect(mRects[pos]);
        }

        private Rect[] getRects() {
            Rect[] rects = new Rect[BOUNDS_POSITION_LENGTH];
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
                rects[i] = new Rect(mRects[i]);
            }
            return rects;
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (Rect rect : mRects) {
                result = result * 48271 + rect.hashCode();
            }
            return result;
        }
        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof Bounds) {
                Bounds b = (Bounds) o;
                return Arrays.deepEquals(mRects, b.mRects);
            }
            return false;
        }

        @Override
        public String toString() {
            return "Bounds=" + Arrays.toString(mRects);
        }

    }

    private final Bounds mBounds;

    /**
     * Creates a DisplayCutout instance.
     *
     * <p>Note that this is only useful for tests. For production code, developers should always
     * use a {@link DisplayCutout} obtained from the system.</p>
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param boundLeft the left bounding rect of the display cutout in pixels. If null is passed,
     *                  it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundTop the top bounding rect of the display cutout in pixels.  If null is passed,
     *                  it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundRight the right bounding rect of the display cutout in pixels.  If null is
     *                  passed, it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundBottom the bottom bounding rect of the display cutout in pixels.  If null is
     *                   passed, it's treated as an empty rectangle (0,0)-(0,0).
     */
    // TODO(b/73953958): @VisibleForTesting(visibility = PRIVATE)
    public DisplayCutout(@NonNull Insets safeInsets, @Nullable Rect boundLeft,
            @Nullable Rect boundTop, @Nullable Rect boundRight, @Nullable Rect boundBottom) {
        this(safeInsets.toRect(), boundLeft, boundTop, boundRight, boundBottom, true);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * <p>Note that this is only useful for tests. For production code, developers should always
     * use a {@link DisplayCutout} obtained from the system.</p>
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param boundingRects the bounding rects of the display cutouts as returned by
     *               {@link #getBoundingRects()} ()}.
     * @deprecated Use {@link DisplayCutout#DisplayCutout(Insets, Rect, Rect, Rect, Rect)} instead.
     */
    // TODO(b/73953958): @VisibleForTesting(visibility = PRIVATE)
    @Deprecated
    public DisplayCutout(@Nullable Rect safeInsets, @Nullable List<Rect> boundingRects) {
        this(safeInsets, extractBoundsFromList(safeInsets, boundingRects),
                true /* copyArguments */);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param copyArguments if true, create a copy of the arguments. If false, the passed arguments
     *                      are not copied and MUST remain unchanged forever.
     */
    private DisplayCutout(Rect safeInsets, Rect boundLeft, Rect boundTop, Rect boundRight,
                         Rect boundBottom, boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mBounds = new Bounds(boundLeft, boundTop, boundRight, boundBottom, copyArguments);
    }

    private DisplayCutout(Rect safeInsets, Rect[] bounds, boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mBounds = new Bounds(bounds, copyArguments);
    }

    private DisplayCutout(Rect safeInsets, Bounds bounds) {
        mSafeInsets = safeInsets;
        mBounds = bounds;

    }

    private static Rect getCopyOrRef(Rect r, boolean copyArguments) {
        if (r == null) {
            return ZERO_RECT;
        } else if (copyArguments) {
            return new Rect(r);
        } else {
            return r;
        }
    }

    /**
     * Find the position of the bounding rect, and create an array of Rect whose index represents
     * the position (= BoundsPosition).
     *
     * @hide
     */
    public static Rect[] extractBoundsFromList(Rect safeInsets, List<Rect> boundingRects) {
        Rect[] sortedBounds = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < sortedBounds.length; ++i) {
            sortedBounds[i] = ZERO_RECT;
        }
        if (safeInsets != null && boundingRects != null) {
            for (Rect bound : boundingRects) {
                // There is at most one non-functional area per short edge of the device, but none
                // on the long edges, so either safeInsets.right or safeInsets.bottom must be 0.
                // TODO(b/117199965): Refine the logic to handle edge cases.
                if (bound.left == 0) {
                    sortedBounds[BOUNDS_POSITION_LEFT] = bound;
                } else if (bound.top == 0) {
                    sortedBounds[BOUNDS_POSITION_TOP] = bound;
                } else if (safeInsets.right > 0) {
                    sortedBounds[BOUNDS_POSITION_RIGHT] = bound;
                } else if (safeInsets.bottom > 0) {
                    sortedBounds[BOUNDS_POSITION_BOTTOM] = bound;
                }
            }
        }
        return sortedBounds;
    }

    /**
     * Returns true if there is no cutout, i.e. the bounds are empty.
     *
     * @hide
     */
    public boolean isBoundsEmpty() {
        return mBounds.isEmpty();
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
     * Returns a list of {@code Rect}s, each of which is the bounding rectangle for a non-functional
     * area on the display.
     *
     * There will be at most one non-functional area per short edge of the device, and none on
     * the long edges.
     *
     * @return a list of bounding {@code Rect}s, one for each display cutout area. No empty Rect is
     * returned.
     */
    @NonNull
    public List<Rect> getBoundingRects() {
        List<Rect> result = new ArrayList<>();
        for (Rect bound : getBoundingRectsAll()) {
            if (!bound.isEmpty()) {
                result.add(new Rect(bound));
            }
        }
        return result;
    }

    /**
     * Returns an array of {@code Rect}s, each of which is the bounding rectangle for a non-
     * functional area on the display. Ordinal value of BoundPosition is used as an index of
     * the array.
     *
     * There will be at most one non-functional area per short edge of the device, and none on
     * the long edges.
     *
     * @return an array of bounding {@code Rect}s, one for each display cutout area. This might
     * contain ZERO_RECT, which means there is no cutout area at the position.
     *
     * @hide
     */
    public Rect[] getBoundingRectsAll() {
        return mBounds.getRects();
    }

    /**
     * Returns a bounding rectangle for a non-functional area on the display which is located on
     * the left of the screen.
     *
     * @return bounding rectangle in pixels. In case of no bounding rectangle, an empty rectangle
     * is returned.
     */
    public @NonNull Rect getBoundingRectLeft() {
        return mBounds.getRect(BOUNDS_POSITION_LEFT);
    }

    /**
     * Returns a bounding rectangle for a non-functional area on the display which is located on
     * the top of the screen.
     *
     * @return bounding rectangle in pixels. In case of no bounding rectangle, an empty rectangle
     * is returned.
     */
    public @NonNull Rect getBoundingRectTop() {
        return mBounds.getRect(BOUNDS_POSITION_TOP);
    }

    /**
     * Returns a bounding rectangle for a non-functional area on the display which is located on
     * the right of the screen.
     *
     * @return bounding rectangle in pixels. In case of no bounding rectangle, an empty rectangle
     * is returned.
     */
    public @NonNull Rect getBoundingRectRight() {
        return mBounds.getRect(BOUNDS_POSITION_RIGHT);
    }

    /**
     * Returns a bounding rectangle for a non-functional area on the display which is located on
     * the bottom of the screen.
     *
     * @return bounding rectangle in pixels. In case of no bounding rectangle, an empty rectangle
     * is returned.
     */
    public @NonNull Rect getBoundingRectBottom() {
        return mBounds.getRect(BOUNDS_POSITION_BOTTOM);
    }

    @Override
    public int hashCode() {
        return mSafeInsets.hashCode() * 48271 + mBounds.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayCutout) {
            DisplayCutout c = (DisplayCutout) o;
            return mSafeInsets.equals(c.mSafeInsets) && mBounds.equals(c.mBounds);
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayCutout{insets=" + mSafeInsets
                + " boundingRect={" + mBounds + "}"
                + "}";
    }

    /**
     * @hide
     */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mSafeInsets.writeToProto(proto, INSETS);
        mBounds.getRect(BOUNDS_POSITION_LEFT).writeToProto(proto, BOUND_LEFT);
        mBounds.getRect(BOUNDS_POSITION_TOP).writeToProto(proto, BOUND_TOP);
        mBounds.getRect(BOUNDS_POSITION_RIGHT).writeToProto(proto, BOUND_RIGHT);
        mBounds.getRect(BOUNDS_POSITION_BOTTOM).writeToProto(proto, BOUND_BOTTOM);
        proto.end(token);
    }

    /**
     * Insets the reference frame of the cutout in the given directions.
     *
     * @return a copy of this instance which has been inset
     * @hide
     */
    public DisplayCutout inset(int insetLeft, int insetTop, int insetRight, int insetBottom) {
        if (insetLeft == 0 && insetTop == 0 && insetRight == 0 && insetBottom == 0
                || isBoundsEmpty()) {
            return this;
        }

        Rect safeInsets = new Rect(mSafeInsets);

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

        // If we are not cutting off part of the cutout by insetting it on bottom/right, and we also
        // don't move it around, we can avoid the allocation and copy of the instance.
        if (insetLeft == 0 && insetTop == 0 && mSafeInsets.equals(safeInsets)) {
            return this;
        }

        Rect[] bounds = mBounds.getRects();
        for (int i = 0; i < bounds.length; ++i) {
            if (!bounds[i].equals(ZERO_RECT)) {
                bounds[i].offset(-insetLeft, -insetTop);
            }
        }

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
        return new DisplayCutout(new Rect(safeInsets), mBounds);
    }

    private static int atLeastZero(int value) {
        return value < 0 ? 0 : value;
    }


    /**
     * Creates an instance from a bounding rect.
     *
     * @hide
     */
    @VisibleForTesting
    public static DisplayCutout fromBoundingRect(
            int left, int top, int right, int bottom, @BoundsPosition int pos) {
        Rect[] bounds = new Rect[BOUNDS_POSITION_LENGTH];
        for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
            bounds[i] = (pos == i) ? new Rect(left, top, right, bottom) : new Rect();
        }
        return new DisplayCutout(ZERO_RECT, bounds, false /* copyArguments */);
    }

    /**
     * Creates an instance from a bounding {@link Path}.
     *
     * @hide
     */
    public static DisplayCutout fromBounds(Rect[] bounds) {
        return new DisplayCutout(ZERO_RECT, bounds, false /* copyArguments */);
    }

    /**
     * Creates the display cutout according to
     * @android:string/config_mainBuiltInDisplayCutoutRectApproximation, which is the closest
     * rectangle-base approximation of the cutout.
     *
     * @hide
     */
    public static DisplayCutout fromResourcesRectApproximation(Resources res, int displayWidth, int displayHeight) {
        return fromSpec(res.getString(R.string.config_mainBuiltInDisplayCutoutRectApproximation),
                displayWidth, displayHeight, DENSITY_DEVICE_STABLE / (float) DENSITY_DEFAULT);
    }

    /**
     * Creates an instance according to @android:string/config_mainBuiltInDisplayCutout.
     *
     * @hide
     */
    public static Path pathFromResources(Resources res, int displayWidth, int displayHeight) {
        return pathAndDisplayCutoutFromSpec(
                res.getString(R.string.config_mainBuiltInDisplayCutout),
                displayWidth, displayHeight, DENSITY_DEVICE_STABLE / (float) DENSITY_DEFAULT).first;
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
        } else if (spec.endsWith(LEFT_MARKER)) {
            offsetX = 0;
            spec = spec.substring(0, spec.length() - LEFT_MARKER.length()).trim();
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
        final Region r = Region.obtain();
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

        Rect boundTop = new Rect();
        toRectAndAddToRegion(p, r, boundTop);
        final int topInset = boundTop.bottom;

        Rect boundBottom = null;
        final int bottomInset;
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
            boundBottom = new Rect();
            toRectAndAddToRegion(bottomPath, r, boundBottom);
            bottomInset = displayHeight - boundBottom.top;
        } else {
            bottomInset = 0;
        }

        Rect safeInset = new Rect(0, topInset, 0, bottomInset);
        final DisplayCutout cutout = new DisplayCutout(
                safeInset, null /* boundLeft */, boundTop, null /* boundRight */, boundBottom,
                false /* copyArguments */);

        final Pair<Path, DisplayCutout> result = new Pair<>(p, cutout);
        synchronized (CACHE_LOCK) {
            sCachedSpec = spec;
            sCachedDisplayWidth = displayWidth;
            sCachedDisplayHeight = displayHeight;
            sCachedDensity = density;
            sCachedCutout = result;
        }
        return result;
    }

    private static void toRectAndAddToRegion(Path p, Region inoutRegion, Rect inoutRect) {
        final RectF rectF = new RectF();
        p.computeBounds(rectF, false /* unused */);
        rectF.round(inoutRect);
        inoutRegion.op(inoutRect, Op.UNION);
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
                out.writeTypedArray(cutout.mBounds.getRects(), flags);
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

        public static final @android.annotation.NonNull Creator<ParcelableWrapper> CREATOR = new Creator<ParcelableWrapper>() {
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
            Rect[] bounds = new Rect[BOUNDS_POSITION_LENGTH];
            in.readTypedArray(bounds, Rect.CREATOR);

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
