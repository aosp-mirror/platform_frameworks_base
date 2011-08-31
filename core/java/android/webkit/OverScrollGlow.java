/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.webkit;

import com.android.internal.R;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.EdgeEffect;

/**
 * This class manages the edge glow effect when a WebView is flung or pulled beyond the edges.
 * @hide
 */
public class OverScrollGlow {
    private WebView mHostView;

    private EdgeEffect mEdgeGlowTop;
    private EdgeEffect mEdgeGlowBottom;
    private EdgeEffect mEdgeGlowLeft;
    private EdgeEffect mEdgeGlowRight;

    private int mOverScrollDeltaX;
    private int mOverScrollDeltaY;

    public OverScrollGlow(WebView host) {
        mHostView = host;
        Context context = host.getContext();
        mEdgeGlowTop = new EdgeEffect(context);
        mEdgeGlowBottom = new EdgeEffect(context);
        mEdgeGlowLeft = new EdgeEffect(context);
        mEdgeGlowRight = new EdgeEffect(context);
    }

    /**
     * Pull leftover touch scroll distance into one of the edge glows as appropriate.
     *
     * @param x Current X scroll offset
     * @param y Current Y scroll offset
     * @param oldX Old X scroll offset
     * @param oldY Old Y scroll offset
     * @param maxX Maximum range for horizontal scrolling
     * @param maxY Maximum range for vertical scrolling
     */
    public void pullGlow(int x, int y, int oldX, int oldY, int maxX, int maxY) {
        // Only show overscroll bars if there was no movement in any direction
        // as a result of scrolling.
        if (oldX == mHostView.getScrollX() && oldY == mHostView.getScrollY()) {
            // Don't show left/right glows if we fit the whole content.
            // Also don't show if there was vertical movement.
            if (maxX > 0) {
                final int pulledToX = oldX + mOverScrollDeltaX;
                if (pulledToX < 0) {
                    mEdgeGlowLeft.onPull((float) mOverScrollDeltaX / mHostView.getWidth());
                    if (!mEdgeGlowRight.isFinished()) {
                        mEdgeGlowRight.onRelease();
                    }
                } else if (pulledToX > maxX) {
                    mEdgeGlowRight.onPull((float) mOverScrollDeltaX / mHostView.getWidth());
                    if (!mEdgeGlowLeft.isFinished()) {
                        mEdgeGlowLeft.onRelease();
                    }
                }
                mOverScrollDeltaX = 0;
            }

            if (maxY > 0 || mHostView.getOverScrollMode() == View.OVER_SCROLL_ALWAYS) {
                final int pulledToY = oldY + mOverScrollDeltaY;
                if (pulledToY < 0) {
                    mEdgeGlowTop.onPull((float) mOverScrollDeltaY / mHostView.getHeight());
                    if (!mEdgeGlowBottom.isFinished()) {
                        mEdgeGlowBottom.onRelease();
                    }
                } else if (pulledToY > maxY) {
                    mEdgeGlowBottom.onPull((float) mOverScrollDeltaY / mHostView.getHeight());
                    if (!mEdgeGlowTop.isFinished()) {
                        mEdgeGlowTop.onRelease();
                    }
                }
                mOverScrollDeltaY = 0;
            }
        }
    }

    /**
     * Set touch delta values indicating the current amount of overscroll.
     *
     * @param deltaX
     * @param deltaY
     */
    public void setOverScrollDeltas(int deltaX, int deltaY) {
        mOverScrollDeltaX = deltaX;
        mOverScrollDeltaY = deltaY;
    }

    /**
     * Absorb leftover fling velocity into one of the edge glows as appropriate.
     *
     * @param x Current X scroll offset
     * @param y Current Y scroll offset
     * @param oldX Old X scroll offset
     * @param oldY Old Y scroll offset
     * @param rangeX Maximum range for horizontal scrolling
     * @param rangeY Maximum range for vertical scrolling
     */
    public void absorbGlow(int x, int y, int oldX, int oldY, int rangeX, int rangeY) {
        if (rangeY > 0 || mHostView.getOverScrollMode() == View.OVER_SCROLL_ALWAYS) {
            if (y < 0 && oldY >= 0) {
                mEdgeGlowTop.onAbsorb((int) mHostView.mScroller.getCurrVelocity());
                if (!mEdgeGlowBottom.isFinished()) {
                    mEdgeGlowBottom.onRelease();
                }
            } else if (y > rangeY && oldY <= rangeY) {
                mEdgeGlowBottom.onAbsorb((int) mHostView.mScroller.getCurrVelocity());
                if (!mEdgeGlowTop.isFinished()) {
                    mEdgeGlowTop.onRelease();
                }
            }
        }

        if (rangeX > 0) {
            if (x < 0 && oldX >= 0) {
                mEdgeGlowLeft.onAbsorb((int) mHostView.mScroller.getCurrVelocity());
                if (!mEdgeGlowRight.isFinished()) {
                    mEdgeGlowRight.onRelease();
                }
            } else if (x > rangeX && oldX <= rangeX) {
                mEdgeGlowRight.onAbsorb((int) mHostView.mScroller.getCurrVelocity());
                if (!mEdgeGlowLeft.isFinished()) {
                    mEdgeGlowLeft.onRelease();
                }
            }
        }
    }

    /**
     * Draw the glow effect along the sides of the widget. mEdgeGlow* must be non-null.
     *
     * @param canvas Canvas to draw into, transformed into view coordinates.
     * @return true if glow effects are still animating and the view should invalidate again.
     */
    public boolean drawEdgeGlows(Canvas canvas) {
        final int scrollX = mHostView.getScrollX();
        final int scrollY = mHostView.getScrollY();
        final int width = mHostView.getWidth();
        int height = mHostView.getHeight();

        boolean invalidateForGlow = false;
        if (!mEdgeGlowTop.isFinished()) {
            final int restoreCount = canvas.save();

            canvas.translate(scrollX, mHostView.getVisibleTitleHeight() + Math.min(0, scrollY));
            mEdgeGlowTop.setSize(width, height);
            invalidateForGlow |= mEdgeGlowTop.draw(canvas);
            canvas.restoreToCount(restoreCount);
        }
        if (!mEdgeGlowBottom.isFinished()) {
            final int restoreCount = canvas.save();

            canvas.translate(-width + scrollX, Math.max(mHostView.computeMaxScrollY(), scrollY)
                    + height);
            canvas.rotate(180, width, 0);
            mEdgeGlowBottom.setSize(width, height);
            invalidateForGlow |= mEdgeGlowBottom.draw(canvas);
            canvas.restoreToCount(restoreCount);
        }
        if (!mEdgeGlowLeft.isFinished()) {
            final int restoreCount = canvas.save();

            canvas.rotate(270);
            canvas.translate(-height - scrollY, Math.min(0, scrollX));
            mEdgeGlowLeft.setSize(height, width);
            invalidateForGlow |= mEdgeGlowLeft.draw(canvas);
            canvas.restoreToCount(restoreCount);
        }
        if (!mEdgeGlowRight.isFinished()) {
            final int restoreCount = canvas.save();

            canvas.rotate(90);
            canvas.translate(scrollY,
                    -(Math.max(mHostView.computeMaxScrollX(), scrollX) + width));
            mEdgeGlowRight.setSize(height, width);
            invalidateForGlow |= mEdgeGlowRight.draw(canvas);
            canvas.restoreToCount(restoreCount);
        }
        return invalidateForGlow;
    }

    /**
     * @return True if any glow is still animating
     */
    public boolean isAnimating() {
        return (!mEdgeGlowTop.isFinished() || !mEdgeGlowBottom.isFinished() ||
                !mEdgeGlowLeft.isFinished() || !mEdgeGlowRight.isFinished());
    }

    /**
     * Release all glows from any touch pulls in progress.
     */
    public void releaseAll() {
        mEdgeGlowTop.onRelease();
        mEdgeGlowBottom.onRelease();
        mEdgeGlowLeft.onRelease();
        mEdgeGlowRight.onRelease();
    }
}
