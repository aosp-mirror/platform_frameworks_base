/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.policy;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Calculates the snap targets and the snap position for the PIP given a position and a velocity.
 * All bounds are relative to the display top/left.
 */
public class PipSnapAlgorithm {

    // The below SNAP_MODE_* constants correspond to the config resource value
    // config_pictureInPictureSnapMode and should not be changed independently.
    // Allows snapping to the four corners
    private static final int SNAP_MODE_CORNERS_ONLY = 0;
    // Allows snapping to the four corners and the mid-points on the long edge in each orientation
    private static final int SNAP_MODE_CORNERS_AND_SIDES = 1;
    // Allows snapping to anywhere along the edge of the screen
    private static final int SNAP_MODE_EDGE = 2;
    // Allows snapping anywhere along the edge of the screen and magnets towards corners
    private static final int SNAP_MODE_EDGE_MAGNET_CORNERS = 3;
    // Allows snapping on the long edge in each orientation and magnets towards corners
    private static final int SNAP_MODE_LONG_EDGE_MAGNET_CORNERS = 4;

    // The friction multiplier to control how slippery the PIP is when flung
    private static final float SCROLL_FRICTION_MULTIPLIER = 8f;

    // Threshold to magnet to a corner
    private static final float CORNER_MAGNET_THRESHOLD = 0.3f;

    private final Context mContext;

    private final ArrayList<Integer> mSnapGravities = new ArrayList<>();
    private final int mDefaultSnapMode = SNAP_MODE_EDGE_MAGNET_CORNERS;
    private int mSnapMode = mDefaultSnapMode;

    private final float mDefaultSizePercent;
    private final float mMinAspectRatioForMinSize;
    private final float mMaxAspectRatioForMinSize;

    private Scroller mScroller;
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    private final int mMinimizedVisibleSize;
    private boolean mIsMinimized;

    public PipSnapAlgorithm(Context context) {
        Resources res = context.getResources();
        mContext = context;
        mMinimizedVisibleSize = res.getDimensionPixelSize(
                com.android.internal.R.dimen.pip_minimized_visible_size);
        mDefaultSizePercent = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureDefaultSizePercent);
        mMaxAspectRatioForMinSize = res.getFloat(
                com.android.internal.R.dimen.config_pictureInPictureAspectRatioLimitForMinSize);
        mMinAspectRatioForMinSize = 1f / mMaxAspectRatioForMinSize;
        onConfigurationChanged();
    }

    /**
     * Updates the snap algorithm when the configuration changes.
     */
    public void onConfigurationChanged() {
        Resources res = mContext.getResources();
        mOrientation = res.getConfiguration().orientation;
        mSnapMode = res.getInteger(com.android.internal.R.integer.config_pictureInPictureSnapMode);
        calculateSnapTargets();
    }

    /**
     * Sets the PIP's minimized state.
     */
    public void setMinimized(boolean isMinimized) {
        mIsMinimized = isMinimized;
    }

    /**
     * @return the closest absolute snap stack bounds for the given {@param stackBounds} moving at
     * the given {@param velocityX} and {@param velocityY}.  The {@param movementBounds} should be
     * those for the given {@param stackBounds}.
     */
    public Rect findClosestSnapBounds(Rect movementBounds, Rect stackBounds, float velocityX,
            float velocityY) {
        final Rect finalStackBounds = new Rect(stackBounds);
        if (mScroller == null) {
            final ViewConfiguration viewConfig = ViewConfiguration.get(mContext);
            mScroller = new Scroller(mContext);
            mScroller.setFriction(viewConfig.getScrollFriction() * SCROLL_FRICTION_MULTIPLIER);
        }
        mScroller.fling(stackBounds.left, stackBounds.top,
                (int) velocityX, (int) velocityY,
                movementBounds.left, movementBounds.right,
                movementBounds.top, movementBounds.bottom);
        finalStackBounds.offsetTo(mScroller.getFinalX(), mScroller.getFinalY());
        mScroller.abortAnimation();
        return findClosestSnapBounds(movementBounds, finalStackBounds);
    }

    /**
     * @return the closest absolute snap stack bounds for the given {@param stackBounds}.  The
     * {@param movementBounds} should be those for the given {@param stackBounds}.
     */
    public Rect findClosestSnapBounds(Rect movementBounds, Rect stackBounds) {
        final Rect pipBounds = new Rect(movementBounds.left, movementBounds.top,
                movementBounds.right + stackBounds.width(),
                movementBounds.bottom + stackBounds.height());
        final Rect newBounds = new Rect(stackBounds);
        if (mSnapMode == SNAP_MODE_LONG_EDGE_MAGNET_CORNERS
                || mSnapMode == SNAP_MODE_EDGE_MAGNET_CORNERS) {
            final Rect tmpBounds = new Rect();
            final Point[] snapTargets = new Point[mSnapGravities.size()];
            for (int i = 0; i < mSnapGravities.size(); i++) {
                Gravity.apply(mSnapGravities.get(i), stackBounds.width(), stackBounds.height(),
                        pipBounds, 0, 0, tmpBounds);
                snapTargets[i] = new Point(tmpBounds.left, tmpBounds.top);
            }
            Point snapTarget = findClosestPoint(stackBounds.left, stackBounds.top, snapTargets);
            float distance = distanceToPoint(snapTarget, stackBounds.left, stackBounds.top);
            final float thresh = Math.max(stackBounds.width(), stackBounds.height())
                    * CORNER_MAGNET_THRESHOLD;
            if (distance < thresh) {
                newBounds.offsetTo(snapTarget.x, snapTarget.y);
            } else {
                snapRectToClosestEdge(stackBounds, movementBounds, newBounds);
            }
        } else if (mSnapMode == SNAP_MODE_EDGE) {
            // Find the closest edge to the given stack bounds and snap to it
            snapRectToClosestEdge(stackBounds, movementBounds, newBounds);
        } else {
            // Find the closest snap point
            final Rect tmpBounds = new Rect();
            final Point[] snapTargets = new Point[mSnapGravities.size()];
            for (int i = 0; i < mSnapGravities.size(); i++) {
                Gravity.apply(mSnapGravities.get(i), stackBounds.width(), stackBounds.height(),
                        pipBounds, 0, 0, tmpBounds);
                snapTargets[i] = new Point(tmpBounds.left, tmpBounds.top);
            }
            Point snapTarget = findClosestPoint(stackBounds.left, stackBounds.top, snapTargets);
            newBounds.offsetTo(snapTarget.x, snapTarget.y);
        }
        return newBounds;
    }

    /**
     * Applies the offset to the {@param stackBounds} to adjust it to a minimized state.
     */
    public void applyMinimizedOffset(Rect stackBounds, Rect movementBounds, Point displaySize,
            Rect stableInsets) {
        if (stackBounds.left <= movementBounds.centerX()) {
            stackBounds.offsetTo(stableInsets.left + mMinimizedVisibleSize - stackBounds.width(),
                    stackBounds.top);
        } else {
            stackBounds.offsetTo(displaySize.x - stableInsets.right - mMinimizedVisibleSize,
                    stackBounds.top);
        }
    }

    /**
     * @return returns a fraction that describes where along the {@param movementBounds} the
     *         {@param stackBounds} are. If the {@param stackBounds} are not currently on the
     *         {@param movementBounds} exactly, then they will be snapped to the movement bounds.
     *
     *         The fraction is defined in a clockwise fashion against the {@param movementBounds}:
     *
     *            0   1
     *          4 +---+ 1
     *            |   |
     *          3 +---+ 2
     *            3   2
     */
    public float getSnapFraction(Rect stackBounds, Rect movementBounds) {
        final Rect tmpBounds = new Rect();
        snapRectToClosestEdge(stackBounds, movementBounds, tmpBounds);
        final float widthFraction = (float) (tmpBounds.left - movementBounds.left) /
                movementBounds.width();
        final float heightFraction = (float) (tmpBounds.top - movementBounds.top) /
                movementBounds.height();
        if (tmpBounds.top == movementBounds.top) {
            return widthFraction;
        } else if (tmpBounds.left == movementBounds.right) {
            return 1f + heightFraction;
        } else if (tmpBounds.top == movementBounds.bottom) {
            return 2f + (1f - widthFraction);
        } else {
            return 3f + (1f - heightFraction);
        }
    }

    /**
     * Moves the {@param stackBounds} along the {@param movementBounds} to the given snap fraction.
     * See {@link #getSnapFraction(Rect, Rect)}.
     *
     * The fraction is define in a clockwise fashion against the {@param movementBounds}:
     *
     *    0   1
     *  4 +---+ 1
     *    |   |
     *  3 +---+ 2
     *    3   2
     */
    public void applySnapFraction(Rect stackBounds, Rect movementBounds, float snapFraction) {
        if (snapFraction < 1f) {
            int offset = movementBounds.left + (int) (snapFraction * movementBounds.width());
            stackBounds.offsetTo(offset, movementBounds.top);
        } else if (snapFraction < 2f) {
            snapFraction -= 1f;
            int offset = movementBounds.top + (int) (snapFraction * movementBounds.height());
            stackBounds.offsetTo(movementBounds.right, offset);
        } else if (snapFraction < 3f) {
            snapFraction -= 2f;
            int offset = movementBounds.left + (int) ((1f - snapFraction) * movementBounds.width());
            stackBounds.offsetTo(offset, movementBounds.bottom);
        } else {
            snapFraction -= 3f;
            int offset = movementBounds.top + (int) ((1f - snapFraction) * movementBounds.height());
            stackBounds.offsetTo(movementBounds.left, offset);
        }
    }

    /**
     * Adjusts {@param movementBoundsOut} so that it is the movement bounds for the given
     * {@param stackBounds}.
     */
    public void getMovementBounds(Rect stackBounds, Rect insetBounds, Rect movementBoundsOut,
            int imeHeight) {
        // Adjust the right/bottom to ensure the stack bounds never goes offscreen
        movementBoundsOut.set(insetBounds);
        movementBoundsOut.right = Math.max(insetBounds.left, insetBounds.right -
                stackBounds.width());
        movementBoundsOut.bottom = Math.max(insetBounds.top, insetBounds.bottom -
                stackBounds.height());
        movementBoundsOut.bottom -= imeHeight;
    }

    /**
     * @return the size of the PiP at the given {@param aspectRatio}, ensuring that the minimum edge
     * is at least {@param minEdgeSize}.
     */
    public Size getSizeForAspectRatio(float aspectRatio, float minEdgeSize, int displayWidth,
            int displayHeight) {
        final int smallestDisplaySize = Math.min(displayWidth, displayHeight);
        final int minSize = (int) Math.max(minEdgeSize, smallestDisplaySize * mDefaultSizePercent);

        final int width;
        final int height;
        if (aspectRatio <= mMinAspectRatioForMinSize || aspectRatio > mMaxAspectRatioForMinSize) {
            // Beyond these points, we can just use the min size as the shorter edge
            if (aspectRatio <= 1) {
                // Portrait, width is the minimum size
                width = minSize;
                height = Math.round(width / aspectRatio);
            } else {
                // Landscape, height is the minimum size
                height = minSize;
                width = Math.round(height * aspectRatio);
            }
        } else {
            // Within these points, we ensure that the bounds fit within the radius of the limits
            // at the points
            final float widthAtMaxAspectRatioForMinSize = mMaxAspectRatioForMinSize * minSize;
            final float radius = PointF.length(widthAtMaxAspectRatioForMinSize, minSize);
            height = (int) Math.round(Math.sqrt((radius * radius) /
                    (aspectRatio * aspectRatio + 1)));
            width = Math.round(height * aspectRatio);
        }
        return new Size(width, height);
    }

    /**
     * @return the closest point in {@param points} to the given {@param x} and {@param y}.
     */
    private Point findClosestPoint(int x, int y, Point[] points) {
        Point closestPoint = null;
        float minDistance = Float.MAX_VALUE;
        for (Point p : points) {
            float distance = distanceToPoint(p, x, y);
            if (distance < minDistance) {
                closestPoint = p;
                minDistance = distance;
            }
        }
        return closestPoint;
    }

    /**
     * Snaps the {@param stackBounds} to the closest edge of the {@param movementBounds} and writes
     * the new bounds out to {@param boundsOut}.
     */
    private void snapRectToClosestEdge(Rect stackBounds, Rect movementBounds, Rect boundsOut) {
        // If the stackBounds are minimized, then it should only be snapped back horizontally
        final int boundedLeft = Math.max(movementBounds.left, Math.min(movementBounds.right,
                stackBounds.left));
        final int boundedTop = Math.max(movementBounds.top, Math.min(movementBounds.bottom,
                stackBounds.top));
        boundsOut.set(stackBounds);
        if (mIsMinimized) {
            boundsOut.offsetTo(boundedLeft, boundedTop);
            return;
        }

        // Otherwise, just find the closest edge
        final int fromLeft = Math.abs(stackBounds.left - movementBounds.left);
        final int fromTop = Math.abs(stackBounds.top - movementBounds.top);
        final int fromRight = Math.abs(movementBounds.right - stackBounds.left);
        final int fromBottom = Math.abs(movementBounds.bottom - stackBounds.top);
        int shortest;
        if (mSnapMode == SNAP_MODE_LONG_EDGE_MAGNET_CORNERS) {
            // Only check longest edges
            shortest = (mOrientation == Configuration.ORIENTATION_LANDSCAPE)
                    ? Math.min(fromTop, fromBottom)
                    : Math.min(fromLeft, fromRight);
        } else {
            shortest = Math.min(Math.min(fromLeft, fromRight), Math.min(fromTop, fromBottom));
        }
        if (shortest == fromLeft) {
            boundsOut.offsetTo(movementBounds.left, boundedTop);
        } else if (shortest == fromTop) {
            boundsOut.offsetTo(boundedLeft, movementBounds.top);
        } else if (shortest == fromRight) {
            boundsOut.offsetTo(movementBounds.right, boundedTop);
        } else {
            boundsOut.offsetTo(boundedLeft, movementBounds.bottom);
        }
    }

    /**
     * @return the distance between point {@param p} and the given {@param x} and {@param y}.
     */
    private float distanceToPoint(Point p, int x, int y) {
        return PointF.length(p.x - x, p.y - y);
    }

    /**
     * Calculate the snap targets for the discrete snap modes.
     */
    private void calculateSnapTargets() {
        mSnapGravities.clear();
        switch (mSnapMode) {
            case SNAP_MODE_CORNERS_AND_SIDES:
                if (mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
                    mSnapGravities.add(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                    mSnapGravities.add(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                } else {
                    mSnapGravities.add(Gravity.CENTER_VERTICAL | Gravity.LEFT);
                    mSnapGravities.add(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
                }
                // Fall through
            case SNAP_MODE_CORNERS_ONLY:
            case SNAP_MODE_EDGE_MAGNET_CORNERS:
            case SNAP_MODE_LONG_EDGE_MAGNET_CORNERS:
                mSnapGravities.add(Gravity.TOP | Gravity.LEFT);
                mSnapGravities.add(Gravity.TOP | Gravity.RIGHT);
                mSnapGravities.add(Gravity.BOTTOM | Gravity.LEFT);
                mSnapGravities.add(Gravity.BOTTOM | Gravity.RIGHT);
                break;
            default:
                // Skip otherwise
                break;
        }
    }

    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + PipSnapAlgorithm.class.getSimpleName());
        pw.println(innerPrefix + "mSnapMode=" + mSnapMode);
        pw.println(innerPrefix + "mOrientation=" + mOrientation);
        pw.println(innerPrefix + "mMinimizedVisibleSize=" + mMinimizedVisibleSize);
        pw.println(innerPrefix + "mIsMinimized=" + mIsMinimized);
    }
}
