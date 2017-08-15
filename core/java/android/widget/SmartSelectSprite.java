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
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.Shape;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOverlay;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;

import java.lang.annotation.Retention;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Stack;

/**
 * A utility class for creating and animating the Smart Select animation.
 */
// TODO Do not rely on ViewOverlays for drawing the Smart Select sprite
final class SmartSelectSprite {

    private static final int EXPAND_DURATION = 300;
    private static final int CORNER_DURATION = 150;
    private static final float STROKE_WIDTH_DP = 1.5F;
    private static final int POINTS_PER_LINE = 4;

    // GBLUE700
    @ColorInt
    private static final int DEFAULT_STROKE_COLOR = 0xFF3367D6;

    private final Interpolator mExpandInterpolator;
    private final Interpolator mCornerInterpolator;
    private final float mStrokeWidth;

    private final View mView;
    private Animator mActiveAnimator = null;
    @ColorInt
    private final int mStrokeColor;
    private Set<Drawable> mExistingAnimationDrawables = new HashSet<>();

    /**
     * Represents a set of points connected by lines.
     */
    private static final class PolygonShape extends Shape {

        private final float[] mLineCoordinates;

        private PolygonShape(final List<Pair<Float, Float>> points) {
            mLineCoordinates = new float[points.size() * POINTS_PER_LINE];

            int index = 0;
            Pair<Float, Float> currentPoint = points.get(0);
            for (final Pair<Float, Float> nextPoint : points) {
                mLineCoordinates[index] = currentPoint.first;
                mLineCoordinates[index + 1] = currentPoint.second;
                mLineCoordinates[index + 2] = nextPoint.first;
                mLineCoordinates[index + 3] = nextPoint.second;

                index += POINTS_PER_LINE;
                currentPoint = nextPoint;
            }
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            canvas.drawLines(mLineCoordinates, paint);
        }
    }

    /**
     * A rounded rectangle with a configurable corner radius and the ability to expand outside of
     * its bounding rectangle and clip against it.
     */
    private static final class RoundedRectangleShape extends Shape {

        private static final String PROPERTY_ROUND_PERCENTAGE = "roundPercentage";

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
        private float mRoundPercentage = 1.0f;
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
            mClipRect.top -= mStrokeWidth;
            mClipRect.bottom += mStrokeWidth;
            mClipRect.left -= mStrokeWidth;
            mClipRect.right += mStrokeWidth;
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

        public void setRoundPercentage(
                @FloatRange(from = 0.0, to = 1.0) final float newPercentage) {
            mRoundPercentage = newPercentage;
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
            return (getCornerRadius() * mRoundPercentage);
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

        private static final String PROPERTY_RIGHT_BOUNDARY = "rightBoundary";
        private static final String PROPERTY_LEFT_BOUNDARY = "leftBoundary";

        private final List<RoundedRectangleShape> mRectangles;
        private final List<RoundedRectangleShape> mReversedRectangles;

        private RectangleList(List<RoundedRectangleShape> rectangles) {
            mRectangles = new LinkedList<>(rectangles);
            mRectangles.sort((o1, o2) -> {
                if (o1.mBoundingRectangle.top == o2.mBoundingRectangle.top) {
                    return Float.compare(o1.mBoundingRectangle.left, o2.mBoundingRectangle.left);
                } else {
                    return Float.compare(o1.mBoundingRectangle.top, o2.mBoundingRectangle.top);
                }
            });
            mReversedRectangles = new LinkedList<>(rectangles);
            Collections.reverse(mReversedRectangles);
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

        private int getTotalWidth() {
            int sum = 0;
            for (RoundedRectangleShape rectangle : mRectangles) {
                sum += rectangle.getBoundingWidth();
            }
            return sum;
        }

        @Override
        public void draw(Canvas canvas, Paint paint) {
            for (RoundedRectangleShape rectangle : mRectangles) {
                rectangle.draw(canvas, paint);
            }
        }

    }

    SmartSelectSprite(final View view) {
        final Context context = view.getContext();
        mExpandInterpolator = AnimationUtils.loadInterpolator(
                context,
                android.R.interpolator.fast_out_slow_in);
        mCornerInterpolator = AnimationUtils.loadInterpolator(
                context,
                android.R.interpolator.fast_out_linear_in);
        mStrokeWidth = dpToPixel(context, STROKE_WIDTH_DP);
        mStrokeColor = getStrokeColor(context);
        mView = view;
    }

    private static boolean intersectsOrTouches(RectF a, RectF b) {
        return a.left <= b.right && b.left <= a.right && a.top <= b.bottom && b.top <= a.bottom;
    }

    private List<Drawable> mergeRectanglesToPolygonShape(
            final List<RectF> rectangles,
            final int color) {
        final List<Drawable> drawables = new LinkedList<>();
        final Set<List<Pair<Float, Float>>> mergedPaths = calculateMergedPolygonPoints(rectangles);

        for (List<Pair<Float, Float>> path : mergedPaths) {
            // Add the starting point to the end of the polygon so that it ends up closed.
            path.add(path.get(0));

            final PolygonShape shape = new PolygonShape(path);
            final ShapeDrawable drawable = new ShapeDrawable(shape);

            drawable.getPaint().setColor(color);
            drawable.getPaint().setStyle(Paint.Style.STROKE);
            drawable.getPaint().setStrokeWidth(mStrokeWidth);

            drawables.add(drawable);
        }

        return drawables;
    }

    private static Set<List<Pair<Float, Float>>> calculateMergedPolygonPoints(
            List<RectF> rectangles) {
        final Set<List<RectF>> partitions = new HashSet<>();
        final LinkedList<RectF> listOfRects = new LinkedList<>(rectangles);

        while (!listOfRects.isEmpty()) {
            final RectF candidate = listOfRects.removeFirst();
            final List<RectF> partition = new LinkedList<>();
            partition.add(candidate);

            final LinkedList<RectF> otherCandidates = new LinkedList<>();
            otherCandidates.addAll(listOfRects);

            while (!otherCandidates.isEmpty()) {
                final RectF otherCandidate = otherCandidates.removeFirst();
                for (RectF partitionElement : partition) {
                    if (intersectsOrTouches(partitionElement, otherCandidate)) {
                        partition.add(otherCandidate);
                        listOfRects.remove(otherCandidate);
                        break;
                    }
                }
            }

            partition.sort(Comparator.comparing(o -> o.top));
            partitions.add(partition);
        }

        final Set<List<Pair<Float, Float>>> result = new HashSet<>();
        for (List<RectF> partition : partitions) {
            final List<Pair<Float, Float>> points = new LinkedList<>();

            final Stack<RectF> rects = new Stack<>();
            for (RectF rect : partition) {
                points.add(new Pair<>(rect.right, rect.top));
                points.add(new Pair<>(rect.right, rect.bottom));
                rects.add(rect);
            }
            while (!rects.isEmpty()) {
                final RectF rect = rects.pop();
                points.add(new Pair<>(rect.left, rect.bottom));
                points.add(new Pair<>(rect.left, rect.top));
            }

            result.add(points);
        }

        return result;

    }

    /**
     * Performs the Smart Select animation on the view bound to this SmartSelectSprite.
     *
     * @param start                 The point from which the animation will start. Must be inside
     *                              destinationRectangles.
     * @param destinationRectangles The rectangles which the animation will fill out by its
     *                              "selection" and finally join them into a single polygon.
     * @param onAnimationEnd        The callback which will be invoked once the whole animation
     *                              completes.
     * @throws IllegalArgumentException if the given start point is not in any of the
     *                                  destinationRectangles.
     * @see #cancelAnimation()
     */
    public void startAnimation(
            final Pair<Float, Float> start,
            final List<RectF> destinationRectangles,
            final Runnable onAnimationEnd) throws IllegalArgumentException {
        cancelAnimation();

        final ValueAnimator.AnimatorUpdateListener updateListener =
                valueAnimator -> mView.invalidate();

        final List<RoundedRectangleShape> shapes = new LinkedList<>();
        final List<Animator> cornerAnimators = new LinkedList<>();

        final RectF centerRectangle = destinationRectangles
                .stream()
                .filter((r) -> r.contains(start.first, start.second))
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

        startingOffset += start.first - centerRectangle.left;

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

        addToOverlay(shapeDrawable);

        mActiveAnimator = createAnimator(mStrokeColor, destinationRectangles, rectangleList,
                startingOffsetLeft, startingOffsetRight, cornerAnimators, updateListener,
                onAnimationEnd);
        mActiveAnimator.start();
    }

    private Animator createAnimator(
            final @ColorInt int color,
            final List<RectF> destinationRectangles,
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

        setUpAnimatorListener(animatorSet, destinationRectangles, color, onAnimationEnd);

        return animatorSet;
    }

    private void setUpAnimatorListener(final Animator animator,
            final List<RectF> destinationRectangles,
            final @ColorInt int color,
            final Runnable onAnimationEnd) {
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animator) {
            }

            @Override
            public void onAnimationEnd(Animator animator) {
                removeExistingDrawables();

                final List<Drawable> polygonShapes = mergeRectanglesToPolygonShape(
                        destinationRectangles,
                        color);

                for (Drawable drawable : polygonShapes) {
                    addToOverlay(drawable);
                }

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
                RoundedRectangleShape.PROPERTY_ROUND_PERCENTAGE,
                1.0F, 0.0F);
        animator.setDuration(CORNER_DURATION);
        animator.addUpdateListener(listener);
        animator.setInterpolator(mCornerInterpolator);
        return animator;
    }

    private static @RoundedRectangleShape.ExpansionDirection int[] generateDirections(
            final RectF centerRectangle,
            final List<RectF> rectangles) throws IllegalArgumentException {
        final @RoundedRectangleShape.ExpansionDirection int[] result = new int[rectangles.size()];

        final int centerRectangleIndex = rectangles.indexOf(centerRectangle);

        for (int i = 0; i < centerRectangleIndex - 1; ++i) {
            result[i] = RoundedRectangleShape.ExpansionDirection.LEFT;
        }
        result[centerRectangleIndex] = RoundedRectangleShape.ExpansionDirection.CENTER;
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

    private void addToOverlay(final Drawable drawable) {
        mView.getOverlay().add(drawable);
        mExistingAnimationDrawables.add(drawable);
    }

    private void removeExistingDrawables() {
        final ViewOverlay overlay = mView.getOverlay();
        for (Drawable drawable : mExistingAnimationDrawables) {
            overlay.remove(drawable);
        }
        mExistingAnimationDrawables.clear();
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

}
