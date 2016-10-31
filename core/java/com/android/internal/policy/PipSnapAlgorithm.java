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
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.view.Gravity;
import android.view.ViewConfiguration;
import android.widget.Scroller;

import java.util.ArrayList;

/**
 * Calculates the snap targets and the snap position for the PIP given a position and a velocity.
 * All bounds are relative to the display top/left.
 */
public class PipSnapAlgorithm {

    // Allows snapping to the four corners
    private static final int SNAP_MODE_CORNERS_ONLY = 0;
    // Allows snapping to the four corners and the mid-points on the long edge in each orientation
    private static final int SNAP_MODE_CORNERS_AND_SIDES = 1;
    // Allows snapping to anywhere along the edge of the screen
    private static final int SNAP_MODE_EDGE = 2;

    private static final float SCROLL_FRICTION_MULTIPLIER = 8f;

    private final Context mContext;

    private final ArrayList<Integer> mSnapGravities = new ArrayList<>();
    private final int mSnapMode = SNAP_MODE_CORNERS_ONLY;

    private Scroller mScroller;
    private int mOrientation = Configuration.ORIENTATION_UNDEFINED;

    public PipSnapAlgorithm(Context context) {
        mContext = context;
        mOrientation = context.getResources().getConfiguration().orientation;
        calculateSnapTargets();
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
        if (mSnapMode == SNAP_MODE_EDGE) {
            // Find the closest edge to the given stack bounds and snap to it
            final int fromLeft = stackBounds.left - movementBounds.left;
            final int fromTop = stackBounds.top - movementBounds.top;
            final int fromRight = movementBounds.right - stackBounds.left;
            final int fromBottom = movementBounds.bottom - stackBounds.top;
            if (fromLeft <= fromTop && fromLeft <= fromRight && fromLeft <= fromBottom) {
                newBounds.offset(-fromLeft, 0);
            } else if (fromTop <= fromLeft && fromTop <= fromRight && fromTop <= fromBottom) {
                newBounds.offset(0, -fromTop);
            } else if (fromRight < fromLeft && fromRight < fromTop && fromRight < fromBottom) {
                newBounds.offset(fromRight, 0);
            } else {
                newBounds.offset(0, fromBottom);
            }
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
}
