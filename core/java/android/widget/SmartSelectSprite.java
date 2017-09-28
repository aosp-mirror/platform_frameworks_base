/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.widget;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.Animator;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.ColorInt;
import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.Shape;
import android.util.TypedValue;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

/**
 * A utility class for creating and animating the Smart Select animation.
 */
final class SmartSelectSprite {

    private static final int EXPAND_DURATION = 300;
    private static final int CORNER_DURATION = 150;
    private static final float STROKE_WIDTH_DP = 1.5F;

    // GBLUE700
    @ColorInt
    private static final int DEFAULT_STROKE_COLOR = 0xFF3367D6;

    private final Interpolator mExpandInterpolator;
    private final Interpolator mCornerInterpolator;
    private final float mStrokeWidth;

    private Animator mActiveAnimator = null;
    private final Runnable mInvalidator;
    @ColorInt
    private final int mStrokeColor;

    static final Comparator<RectF> RECTANGLE_COMPARATOR = Comparator
            .<RectF>comparingDouble(e -> e.bottom)
            .thenComparingDouble(e -> e.left);

    private Drawable mExistingDrawable = null;
    private RectangleList mExistingRectangleList = null;

    /**
     * A rounded rectangle with a configurable corner radius and the ability to expand outside of
     * its bounding rectangle and clip against it.
     */
    private static final class RoundedRectangleShape extends Shape {

        private static final String PROPERTY_ROUND_RATIO = "roundRatio";

        @Retention(SOURCE)
        @IntDef({ExpansionDirection.LEFT, ExpansionDirection.CENTER, ExpansionDirection.RIGHT})
        private @interface ExpansionDirection {
        int LEFT = 0;
        int CENTER = 1;
        int RIGHT = 2;
        }

        @Retention(SOURCE)
        @IntDef({RectangleBorderType.FIT, RectangleBorderType.OVERSHOOT})
        private @interface RectangleBorderType {
        /** A rectangle which, fully expanded, fits inside of its bounding rectangle. */
        int FIT = 0;
        /**
         * A rectangle which, when fully expanded, clips outside of its bounding rectangle so that
         * its edges no longer appear rounded.
         */
        int OVERSHOOT = 1;
        }

        private final float mStrokeWidth;
        private final RectF mBoundingRectangle;
        private float mRoundRatio = 1.0f;
        private final @ExpansionDirection int mExpansionDirection;
        private final @RectangleBorderType int mRectangleBorderType;

        private final RectF mDrawRect = new RectF();
        private final RectF mClipRect = new RectF();
        private final Path mClipPath = new Path();

        /** How far offset the left edge of the rectangle is from the bounding box. */
        private float mLeftBoundary = 0;
        /** How far offset the right edge of the rectangle is from the bounding box. */
        private float mRightBoundary = 0;

        private RoundedRectangleShape(
                final RectF boundingRectangle,
                final @ExpansionDirection int expansionDirection,
                final @RectangleBorderType int rectangleBorderType,
                final float strokeWidth) {
            mBoundingRectangle = new RectF(boundingRectangle);
            mExpansionDirection = expansionDirection;
            mRectangleBorderType = rectangleBorderType;
            mStrokeWidth = strokeWidth;

            if (boundingRectangle.height() > boundingRectangle.width()) {
                setRoundRatio(0.0f);
            } else {
                setRoundRatio(1.0f);
            }
        }

        /*
         * In order to achieve the "rounded rectangle hits the wall" effect, the drawing needs to be
         * done in two passes. In this context, the wall is the bounding rectangle and in the first
         * pass we need to draw the rounded rectangle (expanded and with a corner radius as per
         * object properties) clipped by the bounding box. If the rounded rectangle expands outside
         * of the bounding box, one more pass needs to be done, as there will now be a hole in the
         * rounded rectangle where it "flattened" against the bounding box. In order to fill just
         * this hole, we need to draw the bounding box, but clip it with the rounded rectangle and
         * this will connect the missing pieces.
         */
        @Override
        public void draw(Canvas canvas, Paint paint) {
            final float cornerRadius = getCornerRadius();
            final float adjustedCornerRadius = getAdjustedCornerRadius();

            mDrawRect.set(mBoundingRectangle);
            mDrawRect.left = mBoundingRectangle.left + mLeftBoundary;
            mDrawRect.right = mBoundingRectangle.left + mRightBoundary;

            if (mRectangleBorderType == RectangleBorderType.OVERSHOOT) {
                mDrawRect.left -= cornerRadius / 2;
                mDrawRect.right -= cornerRadius / 2;
            } else {
                switch (mExpansionDirection) {
                    case ExpansionDirection.CENTER:
                        break;
                    case ExpansionDirection.LEFT:
                        mDrawRect.right += cornerRadius;
                        break;
                    case ExpansionDirection.RIGHT:
                        mDrawRect.left -= cornerRadius;
                        break;
                }
            }

            canvas.save();
            mClipRect.set(mBoundingRectangle);
            mClipRect.inset(-mStrokeWidth, -mStrokeWidth);
            canvas.clipRect(mClipRect);
            canvas.drawRoundRect(mDrawRect, adjustedCornerRadius, adjustedCornerRadius, paint);
            canvas.restore();

            canvas.save();
            mClipPath.reset();
            mClipPath.addRoundRect(
                    mDrawRect,
                    adjustedCornerRadius,
                    adjustedCornerRadius,
                    Path.Direction.CW);
            canvas.clipPath(mClipPath);
            canvas.drawRect(mBoundingRectangle, paint);
            canvas.restore();
        }

        public void setRoundRatio(@FloatRange(from = 0.0, to = 1.0) final float roundRatio) {
            mRoundRatio = roundRatio;
        }

        public float getRoundRatio() {
            return mRoundRatio;
        }

        private void setLeftBoundary(final float leftBoundary) {
            mLeftBoundary = leftBoundary;
        }

        private void setRightBoundary(final float rightBoundary) {
            mRightBoundary = rightBoundary;
        }

        private float getCornerRadius() {
            return Math.min(mBoundingRectangle.width(), mBoundingRectangle.height());
        }

        private float getAdjustedCornerRadius() {
            return (getCornerRadius() * mRoundRatio);
        }

        private float getBoundingWidth() {
            if (mRectangleBorderType == RectangleBorderType.OVERSHOOT) {
                return (int) (mBoundingRectangle.width() + getCornerRadius());
            } else {
                return mBoundingRectangle.width();
            }
        }

    }

    /**
     * A collection of {@link RoundedRectangleShape}s that abstracts them to a single shape whose
     * collective left and right boundary can be manipulated.
     */
    private static final class RectangleList extends Shape {

        @Retention(SOURCE)
        @IntDef({DisplayType.RECTANGLES, DisplayType.POLYGON})
        private @interface DisplayType {
            int RECTANGLES = 0;
            int POLYGON = 1;
        }

        private static final String PROPERTY_RIGHT_BOUNDARY = "rightBoundary";
        private static final String PROPERTY_LEFT_BOUNDARY = "leftBoundary";

        private final List<RoundedRectangleShape> mRectangles;
        private final List<RoundedRectangleShape> mReversedRectangles;

        private final Path mOutlinePolygonPath;
        private @DisplayType int mDisplayType = DisplayType.RECTANGLES;

        private RectangleList(final List<RoundedRectangleShape> rectangles) {
            mRectangles = new LinkedList<>(rectangles);
            mReversedRectangles = new LinkedList<>(rectangles);
            Collections.reverse(mReversedRectangles);
            mOutlinePolygonPath = generateOutlinePolygonPath(rectangles);
        }

        private void setLeftBoundary(final float leftBoundary) {
            float boundarySoFar = getTotalWidth();
            for (RoundedRectangleShape rectangle : mReversedRectangles) {
                final float rectangleLeftBoundary = boundarySoFar - rectangle.getBoundingWidth();
                if (leftBoundary < rectangleLeftBoundary) {
                    rectangle.setLeftBoundary(0);
                } else if (leftBoundary > boundarySoFar) {
                    rectangle.setLeftBoundary(rectangle.getBoundingWidth());
                } else {
                    rectangle.setLeftBoundary(
                            rectangle.getBoundingWidth() - boundarySoFar + leftBoundary);
                }

                boundarySoFar = rectangleLeftBoundary;
            }
        }

        private void setRightBoundary(final float rightBoundary) {
            float boundarySoFar = 0;
            for (RoundedRectangleShape rectangle : mRectangles) {
                final float rectangleRightBoundary = rectangle.getBoundingWidth() + boundarySoFar;
                if (rectangleRightBoundary < rightBoundary) {
                    rectangle.setRightBoundary(rectangle.getBoundingWidth());
                } else if (boundarySoFar > rightBoundary) {
                    rectangle.setRightBoundary(0);
                } else {
                    rectangle.setRightBoundary(rightBoundary - boundarySoFar);
                }

                boundarySoFar = rectangleRightBoundary;
            }
        }

        void setDisplayType(@DisplayType int displayType) {
            mDisplayType = displayType;
        }

        private int getTotalWidth() {
            int sum = 0;
            for (RoundedRectangleShape rectangle : mRectangles) {
                sum += rectangle.getBoundingWidth();
            }
            return sum;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            if (mDisplayType == DisplayType.POLYGON) {
                drawPolygon(canvas, paint);
            } else {
                drawRectangles(canvas, paint);
            }
        }

        private void drawRectangles(final Canvas canvas, final Paint paint) {
            for (RoundedRectangleShape rectangle : mRectangles) {
                rectangle.draw(canvas, paint);
            }
        }

        private void drawPolygon(final Canvas canvas, final Paint paint) {
            canvas.drawPath(mOutlinePolygonPath, paint);
        }

        private static Path generateOutlinePolygonPath(
                final List<RoundedRectangleShape> rectangles) {
            final Path path = new Path();
            for (final RoundedRectangleShape shape : rectangles) {
                final Path rectanglePath = new Path();
                rectanglePath.addRect(shape.mBoundingRectangle, Path.Direction.CW);
                path.op(rectanglePath, Path.Op.UNION);
            }
            return path;
        }

    }

    /**
     * @param context     The {@link Context} in which the animation will run
     * @param invalidator A {@link Runnable} which will be called every time the animation updates,
     *                    indicating that the view drawing the animation should invalidate itself
     */
    SmartSelectSprite(final Context context, final Runnable invalidator) {
        mExpandInterpolator = AnimationUtils.loadInterpolator(
                context,
                android.R.interpolator.fast_out_slow_in);
        mCornerInterpolator = AnimationUtils.loadInterpolator(
                context,
                android.R.interpolator.fast_out_linear_in);
        mStrokeWidth = dpToPixel(context, STROKE_WIDTH_DP);
        mStrokeColor = getStrokeColor(context);
        mInvalidator = Preconditions.checkNotNull(invalidator);
    }

    /**
     * Performs the Smart Select animation on the view bound to this SmartSelectSprite.
     *
     * @param start                 The point from which the animation will start. Must be inside
     *                              destinationRectangles.
     * @param destinationRectangles The rectangles which the animation will fill out by its
     *                              "selection" and finally join them into a single polygon. In
     *                              order to get the correct visual behavior, these rectangles
     *                              should be sorted according to {@link #RECTANGLE_COMPARATOR}.
     * @param onAnimationEnd        The callback which will be invoked once the whole animation
     *                              completes.
     * @throws IllegalArgumentException if the given start point is not in any of the
     *                                  destinationRectangles.
     * @see #cancelAnimation()
     */
    public void startAnimation(
            final PointF start,
            final List<RectF> destinationRectangles,
            final Runnable onAnimationEnd) {
        cancelAnimation();

        final ValueAnimator.AnimatorUpdateListener updateListener =
                valueAnimator -> mInvalidator.run();

        final List<RoundedRectangleShape> shapes = new LinkedList<>();
        final List<Animator> cornerAnimators = new LinkedList<>();

        final RectF centerRectangle = destinationRectangles
                .stream()
                .filter((r) -> contains(r, start))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Center point is not inside any of the rectangles!"));

        int startingOffset = 0;
        for (RectF rectangle : destinationRectangles) {
            if (rectangle.equals(centerRectangle)) {
                break;
            }
            startingOffset += rectangle.width();
        }

        startingOffset += start.x - centerRectangle.left;

        final float centerRectangleHalfHeight = centerRectangle.height() / 2;
        final float startingOffsetLeft = startingOffset - centerRectangleHalfHeight;
        final float startingOffsetRight = startingOffset + centerRectangleHalfHeight;

        final @RoundedRectangleShape.ExpansionDirection int[] expansionDirections =
                generateDirections(centerRectangle, destinationRectangles);

        final @RoundedRectangleShape.RectangleBorderType int[] rectangleBorderTypes =
                generateBorderTypes(destinationRectangles);

        int index = 0;

        for (RectF rectangle : destinationRectangles) {
            final RoundedRectangleShape shape = new RoundedRectangleShape(
                    rectangle,
                    expansionDirections[index],
                    rectangleBorderTypes[index],
                    mStrokeWidth);
            cornerAnimators.add(createCornerAnimator(shape, updateListener));
            shapes.add(shape);
            index++;
        }

        final RectangleList rectangleList = new RectangleList(shapes);
        final ShapeDrawable shapeDrawable = new ShapeDrawable(rectangleList);

        final Paint paint = shapeDrawable.getPaint();
        paint.setColor(mStrokeColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(mStrokeWidth);

        mExistingRectangleList = rectangleList;
        mExistingDrawable = shapeDrawable;

        mActiveAnimator = createAnimator(rectangleList, startingOffsetLeft, startingOffsetRight,
                cornerAnimators, updateListener,
                onAnimationEnd);
        mActiveAnimator.start();
    }

    private Animator createAnimator(
            final RectangleList rectangleList,
            final float startingOffsetLeft,
            final float startingOffsetRight,
            final List<Animator> cornerAnimators,
            final ValueAnimator.AnimatorUpdateListener updateListener,
            final Runnable onAnimationEnd) {
        final ObjectAnimator rightBoundaryAnimator = ObjectAnimator.ofFloat(
                rectangleList,
                RectangleList.PROPERTY_RIGHT_BOUNDARY,
                startingOffsetRight,
                rectangleList.getTotalWidth());

        final ObjectAnimator leftBoundaryAnimator = ObjectAnimator.ofFloat(
                rectangleList,
                RectangleList.PROPERTY_LEFT_BOUNDARY,
                startingOffsetLeft,
                0);

        rightBoundaryAnimator.setDuration(EXPAND_DURATION);
        leftBoundaryAnimator.setDuration(EXPAND_DURATION);

        rightBoundaryAnimator.addUpdateListener(updateListener);
        leftBoundaryAnimator.addUpdateListener(updateListener);

        rightBoundaryAnimator.setInterpolator(mExpandInterpolator);
        leftBoundaryAnimator.setInterpolator(mExpandInterpolator);

        final AnimatorSet cornerAnimator = new AnimatorSet();
        cornerAnimator.playTogether(cornerAnimators);

        final AnimatorSet boundaryAnimator = new AnimatorSet();
        boundaryAnimator.playTogether(leftBoundaryAnimator, rightBoundaryAnimator);

        final AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playSequentially(boundaryAnimator, cornerAnimator);

        setUpAnimatorListener(animatorSet, onAnimationEnd);

        return animatorSet;
    }

    private void setUpAnimatorListener(final Animator animator, final Runnable onAnimationEnd) {
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                mExistingRectangleList.setDisplayType(RectangleList.DisplayType.POLYGON);
                mInvalidator.run();

                onAnimationEnd.run();
            }

            @Override
            public void onAnimationCancel(Animator animator) {
            }

            @Override
            public void onAnimationRepeat(Animator animator) {
            }
        });
    }

    private ObjectAnimator createCornerAnimator(
            final RoundedRectangleShape shape,
            final ValueAnimator.AnimatorUpdateListener listener) {
        final ObjectAnimator animator = ObjectAnimator.ofFloat(
                shape,
                RoundedRectangleShape.PROPERTY_ROUND_RATIO,
                shape.getRoundRatio(), 0.0F);
        animator.setDuration(CORNER_DURATION);
        animator.addUpdateListener(listener);
        animator.setInterpolator(mCornerInterpolator);
        return animator;
    }

    private static @RoundedRectangleShape.ExpansionDirection int[] generateDirections(
            final RectF centerRectangle, final List<RectF> rectangles) {
        final @RoundedRectangleShape.ExpansionDirection int[] result = new int[rectangles.size()];

        final int centerRectangleIndex = rectangles.indexOf(centerRectangle);

        for (int i = 0; i < centerRectangleIndex - 1; ++i) {
            result[i] = RoundedRectangleShape.ExpansionDirection.LEFT;
        }

        if (rectangles.size() == 1) {
            result[centerRectangleIndex] = RoundedRectangleShape.ExpansionDirection.CENTER;
        } else if (centerRectangleIndex == 0) {
            result[centerRectangleIndex] = RoundedRectangleShape.ExpansionDirection.LEFT;
        } else if (centerRectangleIndex == rectangles.size() - 1) {
            result[centerRectangleIndex] = RoundedRectangleShape.ExpansionDirection.RIGHT;
        } else {
            result[centerRectangleIndex] = RoundedRectangleShape.ExpansionDirection.CENTER;
        }

        for (int i = centerRectangleIndex + 1; i < result.length; ++i) {
            result[i] = RoundedRectangleShape.ExpansionDirection.RIGHT;
        }

        return result;
    }

    private static @RoundedRectangleShape.RectangleBorderType int[] generateBorderTypes(
            final List<RectF> rectangles) {
        final @RoundedRectangleShape.RectangleBorderType int[] result = new int[rectangles.size()];

        for (int i = 1; i < result.length - 1; ++i) {
            result[i] = RoundedRectangleShape.RectangleBorderType.OVERSHOOT;
        }

        result[0] = RoundedRectangleShape.RectangleBorderType.FIT;
        result[result.length - 1] = RoundedRectangleShape.RectangleBorderType.FIT;
        return result;
    }

    private static float dpToPixel(final Context context, final float dp) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                context.getResources().getDisplayMetrics());
    }

    @ColorInt
    private static int getStrokeColor(final Context context) {
        final TypedValue typedValue = new TypedValue();
        final TypedArray array = context.obtainStyledAttributes(typedValue.data, new int[]{
                android.R.attr.colorControlActivated});
        final int result = array.getColor(0, DEFAULT_STROKE_COLOR);
        array.recycle();
        return result;
    }

    /**
     * A variant of {@link RectF#contains(float, float)} that also allows the point to reside on
     * the right boundary of the rectangle.
     *
     * @param rectangle the rectangle inside which the point should be to be considered "contained"
     * @param point     the point which will be tested
     * @return whether the point is inside the rectangle (or on it's right boundary)
     */
    private static boolean contains(final RectF rectangle, final PointF point) {
        final float x = point.x;
        final float y = point.y;
        return x >= rectangle.left && x <= rectangle.right && y >= rectangle.top
                && y <= rectangle.bottom;
    }

    private void removeExistingDrawables() {
        mExistingDrawable = null;
        mExistingRectangleList = null;
        mInvalidator.run();
    }

    /**
     * Cancels any active Smart Select animation that might be in progress.
     */
    public void cancelAnimation() {
        if (mActiveAnimator != null) {
            mActiveAnimator.cancel();
            mActiveAnimator = null;
            removeExistingDrawables();
        }
    }

    public void draw(Canvas canvas) {
        if (mExistingDrawable != null) {
            mExistingDrawable.draw(canvas);
        }
    }

}
