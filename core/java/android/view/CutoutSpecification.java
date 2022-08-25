/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.view.Gravity.BOTTOM;
import static android.view.Gravity.LEFT;
import static android.view.Gravity.RIGHT;
import static android.view.Gravity.TOP;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.text.TextUtils;
import android.util.Log;
import android.util.PathParser;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Locale;
import java.util.Objects;

/**
 * In order to accept the cutout specification for all of edges in devices, the specification
 * parsing method is extracted from
 * {@link android.view.DisplayCutout#fromResourcesRectApproximation(Resources, int, int)} to be
 * the specified class for parsing the specification.
 * BNF definition:
 * <ul>
 *      <li>Cutouts Specification = ([Cutout Delimiter],Cutout Specification) {...}, [Dp] ; </li>
 *      <li>Cutout Specification  = [Vertical Position], (SVG Path Element), [Horizontal Position]
 *                                  [Bind Cutout] ;</li>
 *      <li>Vertical Position     = "@bottom" | "@center_vertical" ;</li>
 *      <li>Horizontal Position   = "@left" | "@right" ;</li>
 *      <li>Bind Cutout           = "@bind_left_cutout" | "@bind_right_cutout" ;</li>
 *      <li>Cutout Delimiter      = "@cutout" ;</li>
 *      <li>Dp                    = "@dp"</li>
 * </ul>
 *
 * <ul>
 *     <li>Vertical position is top by default if there is neither "@bottom" nor "@center_vertical"
 *     </li>
 *     <li>Horizontal position is center horizontal by default if there is neither "@left" nor
 *     "@right".</li>
 *     <li>@bottom make the cutout piece bind to bottom edge.</li>
 *     <li>both of @bind_left_cutout and @bind_right_cutout are use to claim the cutout belong to
 *     left or right edge cutout.</li>
 * </ul>
 *
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public class CutoutSpecification {
    private static final String TAG = "CutoutSpecification";
    private static final boolean DEBUG = false;

    private static final int MINIMAL_ACCEPTABLE_PATH_LENGTH = "H1V1Z".length();

    private static final char MARKER_START_CHAR = '@';
    private static final String DP_MARKER = MARKER_START_CHAR + "dp";

    private static final String BOTTOM_MARKER = MARKER_START_CHAR + "bottom";
    private static final String RIGHT_MARKER = MARKER_START_CHAR + "right";
    private static final String LEFT_MARKER = MARKER_START_CHAR + "left";
    private static final String CUTOUT_MARKER = MARKER_START_CHAR + "cutout";
    private static final String CENTER_VERTICAL_MARKER = MARKER_START_CHAR + "center_vertical";

    /* By default, it's top bound cutout. That's why TOP_BOUND_CUTOUT_MARKER is not defined */
    private static final String BIND_RIGHT_CUTOUT_MARKER = MARKER_START_CHAR + "bind_right_cutout";
    private static final String BIND_LEFT_CUTOUT_MARKER = MARKER_START_CHAR + "bind_left_cutout";

    private final Path mPath;
    private final Rect mLeftBound;
    private final Rect mTopBound;
    private final Rect mRightBound;
    private final Rect mBottomBound;
    private Insets mInsets;

    private CutoutSpecification(@NonNull Parser parser) {
        mPath = parser.mPath;
        mLeftBound = parser.mLeftBound;
        mTopBound = parser.mTopBound;
        mRightBound = parser.mRightBound;
        mBottomBound = parser.mBottomBound;
        mInsets = parser.mInsets;

        applyPhysicalPixelDisplaySizeRatio(parser.mPhysicalPixelDisplaySizeRatio);

        if (DEBUG) {
            Log.d(TAG, String.format(Locale.ENGLISH,
                    "left cutout = %s, top cutout = %s, right cutout = %s, bottom cutout = %s",
                    mLeftBound != null ? mLeftBound.toString() : "",
                    mTopBound != null ? mTopBound.toString() : "",
                    mRightBound != null ? mRightBound.toString() : "",
                    mBottomBound != null ? mBottomBound.toString() : ""));
        }
    }

    private void applyPhysicalPixelDisplaySizeRatio(float physicalPixelDisplaySizeRatio) {
        if (physicalPixelDisplaySizeRatio == 1f) {
            return;
        }

        if (mPath != null && !mPath.isEmpty()) {
            final Matrix matrix = new Matrix();
            matrix.postScale(physicalPixelDisplaySizeRatio, physicalPixelDisplaySizeRatio);
            mPath.transform(matrix);
        }

        scaleBounds(mLeftBound, physicalPixelDisplaySizeRatio);
        scaleBounds(mTopBound, physicalPixelDisplaySizeRatio);
        scaleBounds(mRightBound, physicalPixelDisplaySizeRatio);
        scaleBounds(mBottomBound, physicalPixelDisplaySizeRatio);
        mInsets = scaleInsets(mInsets, physicalPixelDisplaySizeRatio);
    }

    private void scaleBounds(Rect r, float ratio) {
        if (r != null && !r.isEmpty()) {
            r.scale(ratio);
        }
    }

    private Insets scaleInsets(Insets insets, float ratio) {
        return Insets.of(
                (int) (insets.left * ratio + 0.5f),
                (int) (insets.top * ratio + 0.5f),
                (int) (insets.right * ratio + 0.5f),
                (int) (insets.bottom * ratio + 0.5f));
    }

    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public Path getPath() {
        return mPath;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public Rect getLeftBound() {
        return mLeftBound;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public Rect getTopBound() {
        return mTopBound;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public Rect getRightBound() {
        return mRightBound;
    }

    @VisibleForTesting(visibility = PACKAGE)
    @Nullable
    public Rect getBottomBound() {
        return mBottomBound;
    }

    /**
     * To count the safe inset according to the cutout bounds and waterfall inset.
     *
     * @return the safe inset.
     */
    @VisibleForTesting(visibility = PACKAGE)
    @NonNull
    public Rect getSafeInset() {
        return mInsets.toRect();
    }

    private static int decideWhichEdge(boolean isTopEdgeShortEdge,
            boolean isShortEdge, boolean isStart) {
        return (isTopEdgeShortEdge)
                ? ((isShortEdge) ? (isStart ? TOP : BOTTOM) : (isStart ? LEFT : RIGHT))
                : ((isShortEdge) ? (isStart ? LEFT : RIGHT) : (isStart ? TOP : BOTTOM));
    }

    /**
     * The CutoutSpecification Parser.
     */
    @VisibleForTesting(visibility = PACKAGE)
    public static class Parser {
        private final boolean mIsShortEdgeOnTop;
        private final float mStableDensity;
        private final int mPhysicalDisplayWidth;
        private final int mPhysicalDisplayHeight;
        private final float mPhysicalPixelDisplaySizeRatio;
        private final Matrix mMatrix;
        private Insets mInsets;
        private int mSafeInsetLeft;
        private int mSafeInsetTop;
        private int mSafeInsetRight;
        private int mSafeInsetBottom;

        private final Rect mTmpRect = new Rect();
        private final RectF mTmpRectF = new RectF();

        private boolean mInDp;

        private Path mPath;
        private Rect mLeftBound;
        private Rect mTopBound;
        private Rect mRightBound;
        private Rect mBottomBound;

        private boolean mPositionFromLeft = false;
        private boolean mPositionFromRight = false;
        private boolean mPositionFromBottom = false;
        private boolean mPositionFromCenterVertical = false;

        private boolean mBindLeftCutout = false;
        private boolean mBindRightCutout = false;
        private boolean mBindBottomCutout = false;

        private boolean mIsTouchShortEdgeStart;
        private boolean mIsTouchShortEdgeEnd;
        private boolean mIsCloserToStartSide;

        @VisibleForTesting(visibility = PACKAGE)
        public Parser(float stableDensity, int physicalDisplayWidth,
                int physicalDisplayHeight) {
            this(stableDensity, physicalDisplayWidth, physicalDisplayHeight, 1f);
        }

        /**
         * The constructor of the CutoutSpecification parser to parse the specification of cutout.
         * @param stableDensity the display density.
         * @param physicalDisplayWidth the display width.
         * @param physicalDisplayHeight the display height.
         * @param physicalPixelDisplaySizeRatio the display size ratio based on stable display size.
         */
        Parser(float stableDensity, int physicalDisplayWidth, int physicalDisplayHeight,
                float physicalPixelDisplaySizeRatio) {
            mStableDensity = stableDensity;
            mPhysicalDisplayWidth = physicalDisplayWidth;
            mPhysicalDisplayHeight = physicalDisplayHeight;
            mPhysicalPixelDisplaySizeRatio = physicalPixelDisplaySizeRatio;
            mMatrix = new Matrix();
            mIsShortEdgeOnTop = mPhysicalDisplayWidth < mPhysicalDisplayHeight;
        }

        private void computeBoundsRectAndAddToRegion(Path p, Region inoutRegion, Rect inoutRect) {
            mTmpRectF.setEmpty();
            p.computeBounds(mTmpRectF, false /* unused */);
            mTmpRectF.round(inoutRect);
            inoutRegion.op(inoutRect, Region.Op.UNION);
        }

        private void resetStatus(StringBuilder sb) {
            sb.setLength(0);
            mPositionFromBottom = false;
            mPositionFromLeft = false;
            mPositionFromRight = false;
            mPositionFromCenterVertical = false;

            mBindLeftCutout = false;
            mBindRightCutout = false;
            mBindBottomCutout = false;
        }

        private void translateMatrix() {
            final float offsetX;
            if (mPositionFromRight) {
                offsetX = mPhysicalDisplayWidth;
            } else if (mPositionFromLeft) {
                offsetX = 0;
            } else {
                offsetX = mPhysicalDisplayWidth / 2f;
            }

            final float offsetY;
            if (mPositionFromBottom) {
                offsetY = mPhysicalDisplayHeight;
            } else if (mPositionFromCenterVertical) {
                offsetY = mPhysicalDisplayHeight / 2f;
            } else {
                offsetY = 0;
            }

            mMatrix.reset();
            if (mInDp) {
                mMatrix.postScale(mStableDensity, mStableDensity);
            }
            mMatrix.postTranslate(offsetX, offsetY);
        }

        private int computeSafeInsets(int gravity, Rect rect) {
            if (gravity == LEFT && rect.right > 0 && rect.right < mPhysicalDisplayWidth) {
                return rect.right;
            } else if (gravity == TOP && rect.bottom > 0 && rect.bottom < mPhysicalDisplayHeight) {
                return rect.bottom;
            } else if (gravity == RIGHT && rect.left > 0 && rect.left < mPhysicalDisplayWidth) {
                return mPhysicalDisplayWidth - rect.left;
            } else if (gravity == BOTTOM && rect.top > 0 && rect.top < mPhysicalDisplayHeight) {
                return mPhysicalDisplayHeight - rect.top;
            }
            return 0;
        }

        private void setSafeInset(int gravity, int inset) {
            if (gravity == LEFT) {
                mSafeInsetLeft = inset;
            } else if (gravity == TOP) {
                mSafeInsetTop = inset;
            } else if (gravity == RIGHT) {
                mSafeInsetRight = inset;
            } else if (gravity == BOTTOM) {
                mSafeInsetBottom = inset;
            }
        }

        private int getSafeInset(int gravity) {
            if (gravity == LEFT) {
                return mSafeInsetLeft;
            } else if (gravity == TOP) {
                return mSafeInsetTop;
            } else if (gravity == RIGHT) {
                return mSafeInsetRight;
            } else if (gravity == BOTTOM) {
                return mSafeInsetBottom;
            }
            return 0;
        }

        @NonNull
        private Rect onSetEdgeCutout(boolean isStart, boolean isShortEdge, @NonNull Rect rect) {
            final int gravity;
            if (isShortEdge) {
                gravity = decideWhichEdge(mIsShortEdgeOnTop, true, isStart);
            } else {
                if (mIsTouchShortEdgeStart && mIsTouchShortEdgeEnd) {
                    gravity = decideWhichEdge(mIsShortEdgeOnTop, false, isStart);
                } else if (mIsTouchShortEdgeStart || mIsTouchShortEdgeEnd) {
                    gravity = decideWhichEdge(mIsShortEdgeOnTop, true,
                            mIsCloserToStartSide);
                } else {
                    gravity = decideWhichEdge(mIsShortEdgeOnTop, isShortEdge, isStart);
                }
            }

            int oldSafeInset = getSafeInset(gravity);
            int newSafeInset = computeSafeInsets(gravity, rect);
            if (oldSafeInset < newSafeInset) {
                setSafeInset(gravity, newSafeInset);
            }

            return new Rect(rect);
        }

        private void setEdgeCutout(@NonNull Path newPath) {
            if (mBindRightCutout && mRightBound == null) {
                mRightBound = onSetEdgeCutout(false, !mIsShortEdgeOnTop, mTmpRect);
            } else if (mBindLeftCutout && mLeftBound == null) {
                mLeftBound = onSetEdgeCutout(true, !mIsShortEdgeOnTop, mTmpRect);
            } else if (mBindBottomCutout && mBottomBound == null) {
                mBottomBound = onSetEdgeCutout(false, mIsShortEdgeOnTop, mTmpRect);
            } else if (!(mBindBottomCutout || mBindLeftCutout || mBindRightCutout)
                    && mTopBound == null) {
                mTopBound = onSetEdgeCutout(true, mIsShortEdgeOnTop, mTmpRect);
            } else {
                return;
            }

            if (mPath != null) {
                mPath.addPath(newPath);
            } else {
                mPath = newPath;
            }
        }

        private void parseSvgPathSpec(Region region, String spec) {
            if (TextUtils.length(spec) < MINIMAL_ACCEPTABLE_PATH_LENGTH) {
                Log.e(TAG, "According to SVG definition, it shouldn't happen");
                return;
            }
            spec.trim();
            translateMatrix();

            final Path newPath = PathParser.createPathFromPathData(spec);
            newPath.transform(mMatrix);
            computeBoundsRectAndAddToRegion(newPath, region, mTmpRect);

            if (DEBUG) {
                Log.d(TAG, String.format(Locale.ENGLISH,
                        "hasLeft = %b, hasRight = %b, hasBottom = %b, hasCenterVertical = %b",
                        mPositionFromLeft, mPositionFromRight, mPositionFromBottom,
                        mPositionFromCenterVertical));
                Log.d(TAG, "region = " + region);
                Log.d(TAG, "spec = \"" + spec + "\" rect = " + mTmpRect + " newPath = " + newPath);
            }

            if (mTmpRect.isEmpty()) {
                return;
            }

            if (mIsShortEdgeOnTop) {
                mIsTouchShortEdgeStart = mTmpRect.top <= 0;
                mIsTouchShortEdgeEnd = mTmpRect.bottom >= mPhysicalDisplayHeight;
                mIsCloserToStartSide = mTmpRect.centerY() < mPhysicalDisplayHeight / 2;
            } else {
                mIsTouchShortEdgeStart = mTmpRect.left <= 0;
                mIsTouchShortEdgeEnd = mTmpRect.right >= mPhysicalDisplayWidth;
                mIsCloserToStartSide = mTmpRect.centerX() < mPhysicalDisplayWidth / 2;
            }

            setEdgeCutout(newPath);
        }

        private void parseSpecWithoutDp(@NonNull String specWithoutDp) {
            Region region = Region.obtain();
            StringBuilder sb = null;
            int currentIndex = 0;
            int lastIndex = 0;
            while ((currentIndex = specWithoutDp.indexOf(MARKER_START_CHAR, lastIndex)) != -1) {
                if (sb == null) {
                    sb = new StringBuilder(specWithoutDp.length());
                }
                sb.append(specWithoutDp, lastIndex, currentIndex);

                if (specWithoutDp.startsWith(LEFT_MARKER, currentIndex)) {
                    if (!mPositionFromRight) {
                        mPositionFromLeft = true;
                    }
                    currentIndex += LEFT_MARKER.length();
                } else if (specWithoutDp.startsWith(RIGHT_MARKER, currentIndex)) {
                    if (!mPositionFromLeft) {
                        mPositionFromRight = true;
                    }
                    currentIndex += RIGHT_MARKER.length();
                } else if (specWithoutDp.startsWith(BOTTOM_MARKER, currentIndex)) {
                    parseSvgPathSpec(region, sb.toString());
                    currentIndex += BOTTOM_MARKER.length();

                    /* prepare to parse the rest path */
                    resetStatus(sb);
                    mBindBottomCutout = true;
                    mPositionFromBottom = true;
                } else if (specWithoutDp.startsWith(CENTER_VERTICAL_MARKER, currentIndex)) {
                    parseSvgPathSpec(region, sb.toString());
                    currentIndex += CENTER_VERTICAL_MARKER.length();

                    /* prepare to parse the rest path */
                    resetStatus(sb);
                    mPositionFromCenterVertical = true;
                } else if (specWithoutDp.startsWith(CUTOUT_MARKER, currentIndex)) {
                    parseSvgPathSpec(region, sb.toString());
                    currentIndex += CUTOUT_MARKER.length();

                    /* prepare to parse the rest path */
                    resetStatus(sb);
                } else if (specWithoutDp.startsWith(BIND_LEFT_CUTOUT_MARKER, currentIndex)) {
                    mBindBottomCutout = false;
                    mBindRightCutout = false;
                    mBindLeftCutout = true;

                    currentIndex += BIND_LEFT_CUTOUT_MARKER.length();
                } else if (specWithoutDp.startsWith(BIND_RIGHT_CUTOUT_MARKER, currentIndex)) {
                    mBindBottomCutout = false;
                    mBindLeftCutout = false;
                    mBindRightCutout = true;

                    currentIndex += BIND_RIGHT_CUTOUT_MARKER.length();
                } else {
                    currentIndex += 1;
                }

                lastIndex = currentIndex;
            }

            if (sb == null) {
                parseSvgPathSpec(region, specWithoutDp);
            } else {
                sb.append(specWithoutDp, lastIndex, specWithoutDp.length());
                parseSvgPathSpec(region, sb.toString());
            }

            region.recycle();
        }

        /**
         * To parse specification string as the CutoutSpecification.
         *
         * @param originalSpec the specification string
         * @return the CutoutSpecification instance
         */
        @VisibleForTesting(visibility = PACKAGE)
        public CutoutSpecification parse(@NonNull String originalSpec) {
            Objects.requireNonNull(originalSpec);

            int dpIndex = originalSpec.lastIndexOf(DP_MARKER);
            mInDp = (dpIndex != -1);
            final String spec;
            if (dpIndex != -1) {
                spec = originalSpec.substring(0, dpIndex)
                        + originalSpec.substring(dpIndex + DP_MARKER.length());
            } else {
                spec = originalSpec;
            }

            parseSpecWithoutDp(spec);
            mInsets = Insets.of(mSafeInsetLeft, mSafeInsetTop, mSafeInsetRight, mSafeInsetBottom);
            return new CutoutSpecification(this);
        }
    }
}
