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
import static android.view.Surface.ROTATION_0;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.DisplayUtils;
import android.util.Pair;
import android.util.RotationUtils;
import android.util.proto.ProtoOutputStream;
import android.view.Surface.Rotation;

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
    private static final CutoutPathParserInfo EMPTY_PARSER_INFO = new CutoutPathParserInfo(
            0 /* displayWidth */, 0 /* displayHeight */, 0f /* density */, "" /* cutoutSpec */,
            0 /* rotation */, 0f /* scale */);

    /**
     * An instance where {@link #isEmpty()} returns {@code true}.
     *
     * @hide
     */
    public static final DisplayCutout NO_CUTOUT = new DisplayCutout(
            ZERO_RECT, Insets.NONE, ZERO_RECT, ZERO_RECT, ZERO_RECT, ZERO_RECT, EMPTY_PARSER_INFO,
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
    @GuardedBy("CACHE_LOCK")
    private static Insets sCachedWaterfallInsets;

    @GuardedBy("CACHE_LOCK")
    private static CutoutPathParserInfo sCachedCutoutPathParserInfo;
    @GuardedBy("CACHE_LOCK")
    private static Path sCachedCutoutPath;

    private final Rect mSafeInsets;
    @NonNull
    private final Insets mWaterfallInsets;

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

        private void scale(float scale) {
            for (int i = 0; i < BOUNDS_POSITION_LENGTH; ++i) {
                mRects[i].scale(scale);
            }
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
        public boolean equals(@Nullable Object o) {
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
     * Stores all the needed info to create the cutout paths.
     *
     * @hide
     */
    public static class CutoutPathParserInfo {
        private final int mDisplayWidth;
        private final int mDisplayHeight;
        private final float mDensity;
        private final String mCutoutSpec;
        private final @Rotation int mRotation;
        private final float mScale;

        public CutoutPathParserInfo(int displayWidth, int displayHeight, float density,
                String cutoutSpec, @Rotation int rotation, float scale) {
            mDisplayWidth = displayWidth;
            mDisplayHeight = displayHeight;
            mDensity = density;
            mCutoutSpec = cutoutSpec == null ? "" : cutoutSpec;
            mRotation = rotation;
            mScale = scale;
        }

        public CutoutPathParserInfo(CutoutPathParserInfo cutoutPathParserInfo) {
            mDisplayWidth = cutoutPathParserInfo.mDisplayWidth;
            mDisplayHeight = cutoutPathParserInfo.mDisplayHeight;
            mDensity = cutoutPathParserInfo.mDensity;
            mCutoutSpec = cutoutPathParserInfo.mCutoutSpec;
            mRotation = cutoutPathParserInfo.mRotation;
            mScale = cutoutPathParserInfo.mScale;
        }

        public int getDisplayWidth() {
            return mDisplayWidth;
        }

        public int getDisplayHeight() {
            return mDisplayHeight;
        }

        public float getDensity() {
            return mDensity;
        }

        public @NonNull String getCutoutSpec() {
            return mCutoutSpec;
        }

        public int getRotation() {
            return mRotation;
        }

        public float getScale() {
            return mScale;
        }

        private boolean hasCutout() {
            return !mCutoutSpec.isEmpty();
        }

        @Override
        public int hashCode() {
            int result = 0;
            result = result * 48271 + Integer.hashCode(mDisplayWidth);
            result = result * 48271 + Integer.hashCode(mDisplayHeight);
            result = result * 48271 + Float.hashCode(mDensity);
            result = result * 48271 + mCutoutSpec.hashCode();
            result = result * 48271 + Integer.hashCode(mRotation);
            result = result * 48271 + Float.hashCode(mScale);
            return result;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o == this) {
                return true;
            }
            if (o instanceof CutoutPathParserInfo) {
                CutoutPathParserInfo c = (CutoutPathParserInfo) o;
                return mDisplayWidth == c.mDisplayWidth && mDisplayHeight == c.mDisplayHeight
                        && mDensity == c.mDensity && mCutoutSpec.equals(c.mCutoutSpec)
                        && mRotation == c.mRotation && mScale == c.mScale;
            }
            return false;
        }

        @Override
        public String toString() {
            return "CutoutPathParserInfo{displayWidth=" + mDisplayWidth
                    + " displayHeight=" + mDisplayHeight
                    + " density={" + mDensity + "}"
                    + " cutoutSpec={" + mCutoutSpec + "}"
                    + " rotation={" + mRotation + "}"
                    + " scale={" + mScale + "}"
                    + "}";
        }
    }

    private final @NonNull CutoutPathParserInfo mCutoutPathParserInfo;

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
        this(safeInsets.toRect(), Insets.NONE, boundLeft, boundTop, boundRight, boundBottom, null,
                true);
    }

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
     * @param waterfallInsets the insets for the curved areas in waterfall display.
     */
    public DisplayCutout(@NonNull Insets safeInsets, @Nullable Rect boundLeft,
            @Nullable Rect boundTop, @Nullable Rect boundRight, @Nullable Rect boundBottom,
            @NonNull Insets waterfallInsets) {
        this(safeInsets.toRect(), waterfallInsets, boundLeft, boundTop, boundRight, boundBottom,
                null, true);
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
        this(safeInsets, Insets.NONE, extractBoundsFromList(safeInsets, boundingRects), null,
                true /* copyArguments */);
    }

    /**
     * Creates a DisplayCutout instance.
     *
     * @param safeInsets the insets from each edge which avoid the display cutout as returned by
     *                   {@link #getSafeInsetTop()} etc.
     * @param waterfallInsets the insets for the curved areas in waterfall display.
     * @param boundLeft the left bounding rect of the display cutout in pixels. If null is passed,
     *                  it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundTop the top bounding rect of the display cutout in pixels.  If null is passed,
     *                 it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundRight the right bounding rect of the display cutout in pixels.  If null is
     *                   passed, it's treated as an empty rectangle (0,0)-(0,0).
     * @param boundBottom the bottom bounding rect of the display cutout in pixels.  If null is
     *                    passed, it's treated as an empty rectangle (0,0)-(0,0).
     * @param info the cutout path parser info.
     * @param copyArguments if true, create a copy of the arguments. If false, the passed arguments
     *                      are not copied and MUST remain unchanged forever.
     */
    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Rect boundLeft, Rect boundTop,
            Rect boundRight, Rect boundBottom, CutoutPathParserInfo info,
            boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = new Bounds(boundLeft, boundTop, boundRight, boundBottom, copyArguments);
        mCutoutPathParserInfo = info == null ? EMPTY_PARSER_INFO : info;
    }

    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Rect[] bounds,
            CutoutPathParserInfo info, boolean copyArguments) {
        mSafeInsets = getCopyOrRef(safeInsets, copyArguments);
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = new Bounds(bounds, copyArguments);
        mCutoutPathParserInfo = info == null ? EMPTY_PARSER_INFO : info;
    }

    private DisplayCutout(Rect safeInsets, Insets waterfallInsets, Bounds bounds,
            CutoutPathParserInfo info) {
        mSafeInsets = safeInsets;
        mWaterfallInsets = waterfallInsets == null ? Insets.NONE : waterfallInsets;
        mBounds = bounds;
        mCutoutPathParserInfo = info == null ? EMPTY_PARSER_INFO : info;
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
     * Returns the insets representing the curved areas of a waterfall display.
     *
     * A waterfall display has curved areas along the edges of the screen. Apps should be careful
     * when showing UI and handling touch input in those insets because the curve may impair
     * legibility and can frequently lead to unintended touch inputs.
     *
     * @return the insets for the curved areas of a waterfall display in pixels or {@code
     * Insets.NONE} if there are no curved areas or they don't overlap with the window.
     */
    public @NonNull Insets getWaterfallInsets() {
        return mWaterfallInsets;
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
            // There is at most one non-functional area per short edge of the device, but none
            // on the long edges, so either a) safeInsets.top and safeInsets.bottom is 0, or
            // b) safeInsets.left and safeInset.right is 0.
            final boolean topBottomInset = safeInsets.top > 0 || safeInsets.bottom > 0;
            for (Rect bound : boundingRects) {
                if (topBottomInset) {
                    if (bound.top == 0) {
                        sortedBounds[BOUNDS_POSITION_TOP] = bound;
                    } else {
                        sortedBounds[BOUNDS_POSITION_BOTTOM] = bound;
                    }
                } else {
                    if (bound.left == 0) {
                        sortedBounds[BOUNDS_POSITION_LEFT] = bound;
                    } else {
                        sortedBounds[BOUNDS_POSITION_RIGHT] = bound;
                    }
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

    /**
     * Returns the inset from the top which avoids the display cutout in pixels.
     *
     * @see WindowInsets.Type#displayCutout()
     */
    public int getSafeInsetTop() {
        return mSafeInsets.top;
    }

    /**
     * Returns the inset from the bottom which avoids the display cutout in pixels.
     *
     * @see WindowInsets.Type#displayCutout()
     */
    public int getSafeInsetBottom() {
        return mSafeInsets.bottom;
    }

    /**
     * Returns the inset from the left which avoids the display cutout in pixels.
     *
     * @see WindowInsets.Type#displayCutout()
     */
    public int getSafeInsetLeft() {
        return mSafeInsets.left;
    }

    /**
     * Returns the inset from the right which avoids the display cutout in pixels.
     *
     * @see WindowInsets.Type#displayCutout()
     */
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

    /**
     * Returns a {@link Path} that contains the cutout paths of all sides on the display.
     *
     * To get a cutout path for one specific side, apps can intersect the {@link Path} with the
     * {@link Rect} obtained from {@link #getBoundingRectLeft()}, {@link #getBoundingRectTop()},
     * {@link #getBoundingRectRight()} or {@link #getBoundingRectBottom()}.
     *
     * @return a {@link Path} contains all the cutout paths based on display coordinate. Returns
     * null if there is no cutout on the display.
     */
    public @Nullable Path getCutoutPath() {
        if (!mCutoutPathParserInfo.hasCutout()) {
            return null;
        }
        synchronized (CACHE_LOCK) {
            if (mCutoutPathParserInfo.equals(sCachedCutoutPathParserInfo)) {
                return sCachedCutoutPath;
            }
        }
        final CutoutSpecification cutoutSpec = new CutoutSpecification.Parser(
                mCutoutPathParserInfo.getDensity(), mCutoutPathParserInfo.getDisplayWidth(),
                mCutoutPathParserInfo.getDisplayHeight())
                .parse(mCutoutPathParserInfo.getCutoutSpec());

        final Path cutoutPath = cutoutSpec.getPath();
        if (cutoutPath == null || cutoutPath.isEmpty()) {
            return null;
        }
        final Matrix matrix = new Matrix();
        if (mCutoutPathParserInfo.getRotation() != ROTATION_0) {
            RotationUtils.transformPhysicalToLogicalCoordinates(
                    mCutoutPathParserInfo.getRotation(),
                    mCutoutPathParserInfo.getDisplayWidth(),
                    mCutoutPathParserInfo.getDisplayHeight(),
                    matrix
            );
        }
        matrix.postScale(mCutoutPathParserInfo.getScale(), mCutoutPathParserInfo.getScale());
        cutoutPath.transform(matrix);

        synchronized (CACHE_LOCK) {
            sCachedCutoutPathParserInfo = new CutoutPathParserInfo(mCutoutPathParserInfo);
            sCachedCutoutPath = cutoutPath;
        }
        return cutoutPath;
    }

    /**
     * @return the {@link CutoutPathParserInfo};
     *
     * @hide
     */
    public CutoutPathParserInfo getCutoutPathParserInfo() {
        return mCutoutPathParserInfo;
    }

    @Override
    public int hashCode() {
        int result = 0;
        result = 48271 * result + mSafeInsets.hashCode();
        result = 48271 * result + mBounds.hashCode();
        result = 48271 * result + mWaterfallInsets.hashCode();
        result = 48271 * result + mCutoutPathParserInfo.hashCode();
        return result;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (o == this) {
            return true;
        }
        if (o instanceof DisplayCutout) {
            DisplayCutout c = (DisplayCutout) o;
            return mSafeInsets.equals(c.mSafeInsets) && mBounds.equals(c.mBounds)
                    && mWaterfallInsets.equals(c.mWaterfallInsets)
                    && mCutoutPathParserInfo.equals(c.mCutoutPathParserInfo);
        }
        return false;
    }

    @Override
    public String toString() {
        return "DisplayCutout{insets=" + mSafeInsets
                + " waterfall=" + mWaterfallInsets
                + " boundingRect={" + mBounds + "}"
                + " cutoutPathParserInfo={" + mCutoutPathParserInfo + "}"
                + "}";
    }

    /**
     * @hide
     */
    public void dumpDebug(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        mSafeInsets.dumpDebug(proto, INSETS);
        mBounds.getRect(BOUNDS_POSITION_LEFT).dumpDebug(proto, BOUND_LEFT);
        mBounds.getRect(BOUNDS_POSITION_TOP).dumpDebug(proto, BOUND_TOP);
        mBounds.getRect(BOUNDS_POSITION_RIGHT).dumpDebug(proto, BOUND_RIGHT);
        mBounds.getRect(BOUNDS_POSITION_BOTTOM).dumpDebug(proto, BOUND_BOTTOM);
        mWaterfallInsets.toRect().dumpDebug(proto, INSETS);
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
                || (isBoundsEmpty() && mWaterfallInsets.equals(Insets.NONE))) {
            return this;
        }

        Rect safeInsets = insetInsets(insetLeft, insetTop, insetRight, insetBottom,
                new Rect(mSafeInsets));

        // If we are not cutting off part of the cutout by insetting it on bottom/right, and we also
        // don't move it around, we can avoid the allocation and copy of the instance.
        if (insetLeft == 0 && insetTop == 0 && mSafeInsets.equals(safeInsets)) {
            return this;
        }

        Rect waterfallInsets = insetInsets(insetLeft, insetTop, insetRight, insetBottom,
                mWaterfallInsets.toRect());

        Rect[] bounds = mBounds.getRects();
        for (int i = 0; i < bounds.length; ++i) {
            if (!bounds[i].equals(ZERO_RECT)) {
                bounds[i].offset(-insetLeft, -insetTop);
            }
        }

        return new DisplayCutout(safeInsets, Insets.of(waterfallInsets), bounds,
                mCutoutPathParserInfo, false /* copyArguments */);
    }

    private Rect insetInsets(int insetLeft, int insetTop, int insetRight, int insetBottom,
            Rect insets) {
        // Note: it's not really well defined what happens when the inset is negative, because we
        // don't know if the safe inset needs to expand in general.
        if (insetTop > 0 || insets.top > 0) {
            insets.top = atLeastZero(insets.top - insetTop);
        }
        if (insetBottom > 0 || insets.bottom > 0) {
            insets.bottom = atLeastZero(insets.bottom - insetBottom);
        }
        if (insetLeft > 0 || insets.left > 0) {
            insets.left = atLeastZero(insets.left - insetLeft);
        }
        if (insetRight > 0 || insets.right > 0) {
            insets.right = atLeastZero(insets.right - insetRight);
        }
        return insets;
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
        return new DisplayCutout(new Rect(safeInsets), mWaterfallInsets, mBounds,
                mCutoutPathParserInfo);
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
        return new DisplayCutout(ZERO_RECT, Insets.NONE, bounds, null, false /* copyArguments */);
    }

    /**
     * Creates an instance from bounds, waterfall insets and CutoutPathParserInfo.
     *
     * @hide
     */
    public static DisplayCutout constructDisplayCutout(Rect[] bounds, Insets waterfallInsets,
            CutoutPathParserInfo info) {
        return new DisplayCutout(ZERO_RECT, waterfallInsets, bounds, info,
                false /* copyArguments */);
    }

    /**
     * Creates an instance from a bounding {@link Path}.
     *
     * @hide
     */
    public static DisplayCutout fromBounds(Rect[] bounds) {
        return new DisplayCutout(ZERO_RECT, Insets.NONE, bounds, null /* cutoutPathParserInfo */,
                false /* copyArguments */);
    }

    /**
     * Gets the display cutout by the given display unique id.
     *
     * Loads the default config {@link R.string#config_mainBuiltInDisplayCutout) if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     */
    private static String getDisplayCutoutPath(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final String[] array = res.getStringArray(R.array.config_displayCutoutPathArray);
        if (index >= 0 && index < array.length) {
            return array[index];
        }
        return res.getString(R.string.config_mainBuiltInDisplayCutout);
    }

    /**
     * Gets the display cutout approximation rect by the given display unique id.
     *
     * Loads the default config {@link R.string#config_mainBuiltInDisplayCutoutRectApproximation} if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     */
    private static String getDisplayCutoutApproximationRect(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final String[] array = res.getStringArray(
                R.array.config_displayCutoutApproximationRectArray);
        if (index >= 0 && index < array.length) {
            return array[index];
        }
        return res.getString(R.string.config_mainBuiltInDisplayCutoutRectApproximation);
    }

    /**
     * Gets whether to mask a built-in display cutout of a display which is determined by the
     * given display unique id.
     *
     * Loads the default config {@link R.bool#config_maskMainBuiltInDisplayCutout} if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     *
     * @hide
     */
    public static boolean getMaskBuiltInDisplayCutout(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_maskBuiltInDisplayCutoutArray);
        boolean maskCutout;
        if (index >= 0 && index < array.length()) {
            maskCutout = array.getBoolean(index, false);
        } else {
            maskCutout = res.getBoolean(R.bool.config_maskMainBuiltInDisplayCutout);
        }
        array.recycle();
        return maskCutout;
    }

    /**
     * Gets whether to fill a built-in display cutout of a display which is determined by the
     * given display unique id.
     *
     * Loads the default config{@link R.bool#config_fillMainBuiltInDisplayCutout} if
     * {@link R.array#config_displayUniqueIdArray} is not set.
     *
     * @hide
     */
    public static boolean getFillBuiltInDisplayCutout(Resources res, String displayUniqueId) {
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_fillBuiltInDisplayCutoutArray);
        boolean fillCutout;
        if (index >= 0 && index < array.length()) {
            fillCutout = array.getBoolean(index, false);
        } else {
            fillCutout = res.getBoolean(R.bool.config_fillMainBuiltInDisplayCutout);
        }
        array.recycle();
        return fillCutout;
    }

    /**
     * Gets the waterfall cutout by the given display unique id.
     *
     * Loads the default waterfall dimens if {@link R.array#config_displayUniqueIdArray} is not set.
     * {@link R.dimen#waterfall_display_left_edge_size},
     * {@link R.dimen#waterfall_display_top_edge_size},
     * {@link R.dimen#waterfall_display_right_edge_size},
     * {@link R.dimen#waterfall_display_bottom_edge_size}
     */
    private static Insets getWaterfallInsets(Resources res, String displayUniqueId) {
        Insets insets;
        final int index = DisplayUtils.getDisplayUniqueIdConfigIndex(res, displayUniqueId);
        final TypedArray array = res.obtainTypedArray(R.array.config_waterfallCutoutArray);
        if (index >= 0 && index < array.length() && array.getResourceId(index, 0) > 0) {
            final int resourceId = array.getResourceId(index, 0);
            final TypedArray waterfall = res.obtainTypedArray(resourceId);
            insets = Insets.of(
                    waterfall.getDimensionPixelSize(0 /* waterfall left edge size */, 0),
                    waterfall.getDimensionPixelSize(1 /* waterfall top edge size */, 0),
                    waterfall.getDimensionPixelSize(2 /* waterfall right edge size */, 0),
                    waterfall.getDimensionPixelSize(3 /* waterfall bottom edge size */, 0));
            waterfall.recycle();
        } else {
            insets = loadWaterfallInset(res);
        }
        array.recycle();
        return insets;
    }

    /**
     * Creates the display cutout according to
     * @android:string/config_mainBuiltInDisplayCutoutRectApproximation, which is the closest
     * rectangle-base approximation of the cutout.
     *
     * @hide
     */
    public static DisplayCutout fromResourcesRectApproximation(Resources res,
            String displayUniqueId, int displayWidth, int displayHeight) {
        return pathAndDisplayCutoutFromSpec(getDisplayCutoutPath(res, displayUniqueId),
                getDisplayCutoutApproximationRect(res, displayUniqueId),
                displayWidth, displayHeight, DENSITY_DEVICE_STABLE / (float) DENSITY_DEFAULT,
                getWaterfallInsets(res, displayUniqueId)).second;
    }

    /**
     * Creates an instance according to @android:string/config_mainBuiltInDisplayCutout.
     *
     * @hide
     */
    public static Path pathFromResources(Resources res, String displayUniqueId, int displayWidth,
            int displayHeight) {
        return pathAndDisplayCutoutFromSpec(getDisplayCutoutPath(res, displayUniqueId), null,
                displayWidth, displayHeight, DENSITY_DEVICE_STABLE / (float) DENSITY_DEFAULT,
                getWaterfallInsets(res, displayUniqueId)).first;
    }

    /**
     * Creates an instance according to the supplied {@link android.util.PathParser.PathData} spec.
     *
     * @hide
     */
    @VisibleForTesting(visibility = PRIVATE)
    public static DisplayCutout fromSpec(String pathSpec, int displayWidth,
            int displayHeight, float density, Insets waterfallInsets) {
        return pathAndDisplayCutoutFromSpec(
                pathSpec, null, displayWidth, displayHeight, density, waterfallInsets)
                .second;
    }

    /**
     * Gets the cutout path and the corresponding DisplayCutout instance from the spec string.
     *
     * @param pathSpec the spec string read from config_mainBuiltInDisplayCutout.
     * @param rectSpec the spec string read from config_mainBuiltInDisplayCutoutRectApproximation.
     * @param displayWidth the display width.
     * @param displayHeight the display height.
     * @param density the display density.
     * @param waterfallInsets the waterfall insets of the display.
     * @return a Pair contains the cutout path and the corresponding DisplayCutout instance.
     */
    private static Pair<Path, DisplayCutout> pathAndDisplayCutoutFromSpec(
            String pathSpec, String rectSpec, int displayWidth, int displayHeight, float density,
            Insets waterfallInsets) {
        // Always use the rect approximation spec to create the cutout if it's not null because
        // transforming and sending a Region constructed from a path is very costly.
        String spec = rectSpec != null ? rectSpec : pathSpec;
        if (TextUtils.isEmpty(spec) && waterfallInsets.equals(Insets.NONE)) {
            return NULL_PAIR;
        }

        synchronized (CACHE_LOCK) {
            if (spec.equals(sCachedSpec) && sCachedDisplayWidth == displayWidth
                    && sCachedDisplayHeight == displayHeight
                    && sCachedDensity == density
                    && waterfallInsets.equals(sCachedWaterfallInsets)) {
                return sCachedCutout;
            }
        }

        spec = spec.trim();

        CutoutSpecification cutoutSpec = new CutoutSpecification.Parser(density,
                displayWidth, displayHeight).parse(spec);
        Rect safeInset = cutoutSpec.getSafeInset();
        final Rect boundLeft = cutoutSpec.getLeftBound();
        final Rect boundTop = cutoutSpec.getTopBound();
        final Rect boundRight = cutoutSpec.getRightBound();
        final Rect boundBottom = cutoutSpec.getBottomBound();


        if (!waterfallInsets.equals(Insets.NONE)) {
            safeInset.set(
                    Math.max(waterfallInsets.left, safeInset.left),
                    Math.max(waterfallInsets.top, safeInset.top),
                    Math.max(waterfallInsets.right, safeInset.right),
                    Math.max(waterfallInsets.bottom, safeInset.bottom));
        }

        final CutoutPathParserInfo cutoutPathParserInfo = new CutoutPathParserInfo(displayWidth,
                displayHeight, density, pathSpec.trim(), ROTATION_0, 1f /* scale */);

        final DisplayCutout cutout = new DisplayCutout(
                safeInset, waterfallInsets, boundLeft, boundTop, boundRight, boundBottom,
                cutoutPathParserInfo , false /* copyArguments */);
        final Pair<Path, DisplayCutout> result = new Pair<>(cutoutSpec.getPath(), cutout);
        synchronized (CACHE_LOCK) {
            sCachedSpec = spec;
            sCachedDisplayWidth = displayWidth;
            sCachedDisplayHeight = displayHeight;
            sCachedDensity = density;
            sCachedCutout = result;
            sCachedWaterfallInsets = waterfallInsets;
        }
        return result;
    }

    private static Insets loadWaterfallInset(Resources res) {
        return Insets.of(
                res.getDimensionPixelSize(R.dimen.waterfall_display_left_edge_size),
                res.getDimensionPixelSize(R.dimen.waterfall_display_top_edge_size),
                res.getDimensionPixelSize(R.dimen.waterfall_display_right_edge_size),
                res.getDimensionPixelSize(R.dimen.waterfall_display_bottom_edge_size));
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
                out.writeTypedObject(cutout.mWaterfallInsets, flags);
                out.writeInt(cutout.mCutoutPathParserInfo.getDisplayWidth());
                out.writeInt(cutout.mCutoutPathParserInfo.getDisplayHeight());
                out.writeFloat(cutout.mCutoutPathParserInfo.getDensity());
                out.writeString(cutout.mCutoutPathParserInfo.getCutoutSpec());
                out.writeInt(cutout.mCutoutPathParserInfo.getRotation());
                out.writeFloat(cutout.mCutoutPathParserInfo.getScale());
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
            Insets waterfallInsets = in.readTypedObject(Insets.CREATOR);
            int displayWidth = in.readInt();
            int displayHeight = in.readInt();
            float density = in.readFloat();
            String cutoutSpec = in.readString();
            int rotation = in.readInt();
            float scale = in.readFloat();
            final CutoutPathParserInfo info = new CutoutPathParserInfo(
                    displayWidth, displayHeight, density, cutoutSpec, rotation, scale);

            return new DisplayCutout(
                    safeInsets, waterfallInsets, bounds, info, false /* copyArguments */);
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

        public void scale(float scale) {
            final Rect safeInsets = mInner.getSafeInsets();
            safeInsets.scale(scale);
            final Bounds bounds = new Bounds(mInner.mBounds.mRects, true);
            bounds.scale(scale);
            final Rect waterfallInsets = mInner.mWaterfallInsets.toRect();
            waterfallInsets.scale(scale);
            final CutoutPathParserInfo info = new CutoutPathParserInfo(
                    mInner.mCutoutPathParserInfo.getDisplayWidth(),
                    mInner.mCutoutPathParserInfo.getDisplayHeight(),
                    mInner.mCutoutPathParserInfo.getDensity(),
                    mInner.mCutoutPathParserInfo.getCutoutSpec(),
                    mInner.mCutoutPathParserInfo.getRotation(),
                    scale);

            mInner = new DisplayCutout(safeInsets, Insets.of(waterfallInsets), bounds, info);
        }

        @Override
        public int hashCode() {
            return mInner.hashCode();
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return o instanceof ParcelableWrapper
                    && mInner.equals(((ParcelableWrapper) o).mInner);
        }

        @Override
        public String toString() {
            return String.valueOf(mInner);
        }
    }
}
