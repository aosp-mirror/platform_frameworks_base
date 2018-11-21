/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.bubbles;

import static com.android.systemui.Interpolators.FAST_OUT_SLOW_IN;

import android.animation.Animator.AnimatorListener;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Point;
import android.view.View;
import android.view.WindowManager;

import com.android.systemui.bubbles.BubbleTouchHandler.FloatingView;

import java.util.Arrays;

/**
 * Math and animators to move bubbles around the screen.
 *
 * TODO: straight up copy paste from old prototype -- consider physics, see if bubble & pip
 * movements can be unified maybe?
 */
public class BubbleMovementHelper {

    private static final int MAGNET_ANIM_TIME = 150;
    public static final int EDGE_OVERLAP = 0;

    private Context mContext;
    private Point mDisplaySize;

    public BubbleMovementHelper(Context context) {
        mContext = context;
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mDisplaySize = new Point();
        wm.getDefaultDisplay().getSize(mDisplaySize);
    }

    /**
     * @return the distance between the two provided points.
     */
    static double distance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p2.x - p1.x, 2) + Math.pow(p2.y - p1.y, 2));
    }

    /**
     * @return the y value of a line defined by y = mx+b
     */
    static float findY(float m, float b, float x) {
        return (m * x) + b;
    }

    /**
     * @return the x value of a line defined by y = mx+b
     */
    static float findX(float m, float b, float y) {
        return (y - b) / m;
    }

    /**
     * Determines a point on the edge of the screen based on the velocity and position.
     */
    public Point getPointOnEdge(View bv, Point p, float velX, float velY) {
        // Find the slope and the y-intercept
        velX = velX == 0 ? 1 : velX;
        final float m = velY / velX;
        final float b = p.y - m * p.x;

        // There are two lines it can intersect, find the two points
        Point pointHoriz = new Point();
        Point pointVert = new Point();

        if (velX > 0) {
            // right
            pointHoriz.x = mDisplaySize.x;
            pointHoriz.y = (int) findY(m, b, mDisplaySize.x);
        } else {
            // left
            pointHoriz.x = EDGE_OVERLAP;
            pointHoriz.y = (int) findY(m, b, 0);
        }
        if (velY > 0) {
            // bottom
            pointVert.x = (int) findX(m, b, mDisplaySize.y);
            pointVert.y = mDisplaySize.y - getNavBarHeight();
        } else {
            // top
            pointVert.x = (int) findX(m, b, 0);
            pointVert.y = EDGE_OVERLAP;
        }

        // Use the point that's closest to the start position
        final double distanceToVertPoint = distance(p, pointVert);
        final double distanceToHorizPoint = distance(p, pointHoriz);
        boolean useVert = distanceToVertPoint < distanceToHorizPoint;
        // Check if we're being flung along the current edge, use opposite point in this case
        // XXX: on*Edge methods should actually use 'down' position of view and compare 'up' but
        // this works well enough for now
        if (onSideEdge(bv, p) && Math.abs(velY) > Math.abs(velX)) {
            // Flinging along left or right edge, favor vert edge
            useVert = true;

        } else if (onTopBotEdge(bv, p) && Math.abs(velX) > Math.abs(velY)) {
            // Flinging along top or bottom edge
            useVert = false;
        }

        if (useVert) {
            pointVert.x = capX(pointVert.x, bv);
            pointVert.y = capY(pointVert.y, bv);
            return pointVert;

        }
        pointHoriz.x = capX(pointHoriz.x, bv);
        pointHoriz.y = capY(pointHoriz.y, bv);
        return pointHoriz;
    }

    /**
     * @return whether the view is on a side edge of the screen (i.e. left or right).
     */
    public boolean onSideEdge(View fv, Point p) {
        return p.x + fv.getWidth() + EDGE_OVERLAP <= mDisplaySize.x
                - EDGE_OVERLAP
                || p.x >= EDGE_OVERLAP;
    }

    /**
     * @return whether the view is on a top or bottom edge of the screen.
     */
    public boolean onTopBotEdge(View bv, Point p) {
        return p.y >= getStatusBarHeight() + EDGE_OVERLAP
                || p.y + bv.getHeight() + EDGE_OVERLAP <= mDisplaySize.y
                - EDGE_OVERLAP;
    }

    /**
     * @return constrained x value based on screen size and how much a view can overlap with a side
     *         edge.
     */
    public int capX(float x, View bv) {
        // Floating things can't stick to top or bottom edges, so figure out if it's closer to
        // left or right and just use that side + the overlap.
        final float centerX = x + bv.getWidth() / 2;
        if (centerX > mDisplaySize.x / 2) {
            // Right side
            return mDisplaySize.x - bv.getWidth() - EDGE_OVERLAP;
        } else {
            // Left side
            return EDGE_OVERLAP;
        }
    }

    /**
     * @return constrained y value based on screen size and how much a view can overlap with a top
     *         or bottom edge.
     */
    public int capY(float y, View bv) {
        final int height = bv.getHeight();
        if (y < getStatusBarHeight() + EDGE_OVERLAP) {
            return getStatusBarHeight() + EDGE_OVERLAP;
        }
        if (y + height + EDGE_OVERLAP > mDisplaySize.y - EDGE_OVERLAP) {
            return mDisplaySize.y - height - EDGE_OVERLAP;
        }
        return (int) y;
    }

    /**
     * Animation to translate the provided view.
     */
    public AnimatorSet animateMagnetTo(final BubbleStackView bv) {
        Point pos = bv.getPosition();

        // Find the distance to each edge
        final int leftDistance = pos.x;
        final int rightDistance = mDisplaySize.x - leftDistance;
        final int topDistance = pos.y;
        final int botDistance = mDisplaySize.y - topDistance;

        int smallest;
        // Find the closest one
        int[] distances = {
                leftDistance, rightDistance, topDistance, botDistance
        };
        Arrays.sort(distances);
        smallest = distances[0];

        // Animate to the closest edge
        Point p = new Point();
        if (smallest == leftDistance) {
            p.x = capX(EDGE_OVERLAP, bv);
            p.y = capY(topDistance, bv);
        }
        if (smallest == rightDistance) {
            p.x = capX(mDisplaySize.x, bv);
            p.y = capY(topDistance, bv);
        }
        if (smallest == topDistance) {
            p.x = capX(leftDistance, bv);
            p.y = capY(0, bv);
        }
        if (smallest == botDistance) {
            p.x = capX(leftDistance, bv);
            p.y = capY(mDisplaySize.y, bv);
        }
        return getTranslateAnim(bv, p, MAGNET_ANIM_TIME);
    }

    /**
     * Animation to fling the provided view.
     */
    public AnimatorSet animateFlingTo(final BubbleStackView bv, float velX, float velY) {
        Point pos = bv.getPosition();
        Point endPos = getPointOnEdge(bv, pos, velX, velY);
        endPos = new Point(capX(endPos.x, bv), capY(endPos.y, bv));
        final double distance = Math.sqrt(Math.pow(endPos.x - pos.x, 2)
                + Math.pow(endPos.y - pos.y, 2));
        final float sumVel = Math.abs(velX) + Math.abs(velY);
        final int duration = Math.max(Math.min(200, (int) (distance * 1000f / (sumVel / 2))), 50);
        return getTranslateAnim(bv, endPos, duration);
    }

    /**
     * Animation to translate the provided view.
     */
    public AnimatorSet getTranslateAnim(final FloatingView v, Point p, int duration) {
        return getTranslateAnim(v, p, duration, 0);
    }

    /**
     * Animation to translate the provided view.
     */
    public AnimatorSet getTranslateAnim(final FloatingView v, Point p,
            int duration, int startDelay) {
        return getTranslateAnim(v, p, duration, startDelay, null);
    }

    /**
     * Animation to translate the provided view.
     *
     * @param v the view to translate.
     * @param p the point to translate to.
     * @param duration the duration of the animation.
     * @param startDelay the start delay of the animation.
     * @param listener the listener to add to the animation.
     *
     * @return the animation.
     */
    public static AnimatorSet getTranslateAnim(final FloatingView v, Point p, int duration,
            int startDelay, AnimatorListener listener) {
        Point curPos = v.getPosition();
        final ValueAnimator animX = ValueAnimator.ofFloat(curPos.x, p.x);
        animX.setDuration(duration);
        animX.setStartDelay(startDelay);
        animX.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                v.setPositionX((int) value);
            }
        });

        final ValueAnimator animY = ValueAnimator.ofFloat(curPos.y, p.y);
        animY.setDuration(duration);
        animY.setStartDelay(startDelay);
        animY.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float value = (float) animation.getAnimatedValue();
                v.setPositionY((int) value);
            }
        });
        if (listener != null) {
            animY.addListener(listener);
        }

        AnimatorSet set = new AnimatorSet();
        set.playTogether(animX, animY);
        set.setInterpolator(FAST_OUT_SLOW_IN);
        return set;
    }


    // TODO -- now that this is in system we should be able to get these better, but ultimately
    // makes more sense to move to movement bounds style a la PIP
    /**
     * Returns the status bar height.
     */
    public int getStatusBarHeight() {
        Resources res = mContext.getResources();
        int resourceId = res.getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return res.getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    /**
     * Returns the status bar height.
     */
    public int getNavBarHeight() {
        Resources res = mContext.getResources();
        int resourceId = res.getIdentifier("navigation_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return res.getDimensionPixelSize(resourceId);
        }
        return 0;
    }
}
