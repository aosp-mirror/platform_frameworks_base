/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.content.Context;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * This class encapsulates scrolling with the ability to overshoot the bounds
 * of a scrolling operation. This class attempts to be a drop-in replacement
 * for {@link android.widget.Scroller} in most cases.
 * 
 * @hide Pending API approval
 */
public class OverScroller {
    private static final int SPRINGBACK_DURATION = 150;
    private static final int OVERFLING_DURATION = 150;
    
    private static final int MODE_DEFAULT = 0;
    private static final int MODE_OVERFLING = 1;
    private static final int MODE_SPRINGBACK = 2;
    
    private Scroller mDefaultScroller;
    private Scroller mDecelScroller;
    private Scroller mAccelDecelScroller;
    private Scroller mCurrScroller;
    
    private int mScrollMode = MODE_DEFAULT;
    
    private int mMinimumX;
    private int mMinimumY;
    private int mMaximumX;
    private int mMaximumY;
    
    public OverScroller(Context context) {
        mDefaultScroller = new Scroller(context);
        mDecelScroller = new Scroller(context, new DecelerateInterpolator());
        mAccelDecelScroller = new Scroller(context, new AccelerateDecelerateInterpolator());
        mCurrScroller = mDefaultScroller;
    }
    
    /**
     * Call this when you want to know the new location.  If it returns true,
     * the animation is not yet finished.  loc will be altered to provide the
     * new location.
     */ 
    public boolean computeScrollOffset() {
        boolean inProgress = mCurrScroller.computeScrollOffset();
        
        switch (mScrollMode) {
        case MODE_OVERFLING:
            if (!inProgress) {
                // Overfling ended
                if (springback(mCurrScroller.getCurrX(), mCurrScroller.getCurrY(),
                        mMinimumX, mMaximumX, mMinimumY, mMaximumY, mAccelDecelScroller)) {
                    return mCurrScroller.computeScrollOffset();
                } else {
                    mCurrScroller = mDefaultScroller;
                    mScrollMode = MODE_DEFAULT;
                }
            }
            break;
            
        case MODE_SPRINGBACK:
            if (!inProgress) {
                mCurrScroller = mDefaultScroller;
                mScrollMode = MODE_DEFAULT;
            }
            break;
            
        case MODE_DEFAULT:
            // Fling/autoscroll - did we go off the edge?
            if (inProgress) {
                Scroller scroller = mCurrScroller;
                final int x = scroller.getCurrX();
                final int y = scroller.getCurrY();
                final int minX = mMinimumX;
                final int maxX = mMaximumX;
                final int minY = mMinimumY;
                final int maxY = mMaximumY;
                if (x < minX || x > maxX || y < minY || y > maxY) {
                    final int startx = scroller.getStartX();
                    final int starty = scroller.getStartY();
                    final int time = scroller.timePassed();
                    final float timeSecs = time / 1000.f;
                    final float xvel = ((x - startx) / timeSecs);
                    final float yvel = ((y - starty) / timeSecs);
                    
                    if ((x < minX && xvel > 0) || (y < minY && yvel > 0) ||
                            (x > maxX && xvel < 0) || (y > maxY && yvel < 0)) {
                        // If our velocity would take us back into valid areas,
                        // try to springback rather than overfling.
                        if (springback(x, y, minX, maxX, minY, maxY)) {
                            return mCurrScroller.computeScrollOffset();
                        }
                    } else {
                        overfling(x, y, xvel, yvel);
                        return mCurrScroller.computeScrollOffset();
                    }
                }
            }
            break;
        }
        
        return inProgress;
    }
    
    private void overfling(int startx, int starty, float xvel, float yvel) {
        Scroller scroller = mDecelScroller;
        final float durationSecs = (OVERFLING_DURATION / 1000.f);
        int dx = (int)(xvel * durationSecs) / 8;
        int dy = (int)(yvel * durationSecs) / 8;
        mCurrScroller.abortAnimation();
        scroller.startScroll(startx, starty, dx, dy, OVERFLING_DURATION);
        mCurrScroller = scroller;
        mScrollMode = MODE_OVERFLING;
    }
    
    /**
     * Call this when you want to 'spring back' into a valid coordinate range.
     *
     * @param startX Starting X coordinate
     * @param startY Starting Y coordinate
     * @param minX Minimum valid X value
     * @param maxX Maximum valid X value
     * @param minY Minimum valid Y value
     * @param maxY Minimum valid Y value
     * @return true if a springback was initiated, false if startX/startY was
     *          already within the valid range.
     */
    public boolean springback(int startX, int startY, int minX, int maxX,
            int minY, int maxY) {
        return springback(startX, startY, minX, maxX, minY, maxY, mDecelScroller);
    }
    
    private boolean springback(int startX, int startY, int minX, int maxX,
            int minY, int maxY, Scroller scroller) {
        int xoff = 0;
        int yoff = 0;
        if (startX < minX) {
            xoff = minX - startX;
        } else if (startX > maxX) {
            xoff = maxX - startX;
        }
        if (startY < minY) {
            yoff = minY - startY;
        } else if (startY > maxY) {
            yoff = maxY - startY;
        }
        
        if (xoff != 0 || yoff != 0) {
            mCurrScroller.abortAnimation();
            scroller.startScroll(startX, startY, xoff, yoff, SPRINGBACK_DURATION);
            mCurrScroller = scroller;
            mScrollMode = MODE_SPRINGBACK;
            return true;
        }
        
        return false;
    }

    /**
     * 
     * Returns whether the scroller has finished scrolling.
     * 
     * @return True if the scroller has finished scrolling, false otherwise.
     */
    public final boolean isFinished() {
        return mCurrScroller.isFinished();
    }

    /**
     * Returns the current X offset in the scroll. 
     * 
     * @return The new X offset as an absolute distance from the origin.
     */
    public final int getCurrX() {
        return mCurrScroller.getCurrX();
    }
    
    /**
     * Returns the current Y offset in the scroll. 
     * 
     * @return The new Y offset as an absolute distance from the origin.
     */
    public final int getCurrY() {
        return mCurrScroller.getCurrY();
    }
    
    /**
     * Stops the animation, resets any springback/overfling and completes
     * any standard flings/scrolls in progress.
     */
    public void abortAnimation() {
        mCurrScroller.abortAnimation();
        mCurrScroller = mDefaultScroller;
        mScrollMode = MODE_DEFAULT;
        mCurrScroller.abortAnimation();
    }
    
    /**
     * Start scrolling by providing a starting point and the distance to travel.
     * The scroll will use the default value of 250 milliseconds for the
     * duration. This version does not spring back to boundaries.
     * 
     * @param startX Starting horizontal scroll offset in pixels. Positive
     *        numbers will scroll the content to the left.
     * @param startY Starting vertical scroll offset in pixels. Positive numbers
     *        will scroll the content up.
     * @param dx Horizontal distance to travel. Positive numbers will scroll the
     *        content to the left.
     * @param dy Vertical distance to travel. Positive numbers will scroll the
     *        content up.
     */
    public void startScroll(int startX, int startY, int dx, int dy) {
        final int minX = Math.min(startX, startX + dx);
        final int maxX = Math.max(startX, startX + dx);
        final int minY = Math.min(startY, startY + dy);
        final int maxY = Math.max(startY, startY + dy);
        startScroll(startX, startY, dx, dy, minX, maxX, minY, maxY);
    }
    
    /**
     * Start scrolling by providing a starting point and the distance to travel.
     * The scroll will use the default value of 250 milliseconds for the
     * duration. This version will spring back to the provided boundaries if
     * the scroll value would take it too far.
     * 
     * @param startX Starting horizontal scroll offset in pixels. Positive
     *        numbers will scroll the content to the left.
     * @param startY Starting vertical scroll offset in pixels. Positive numbers
     *        will scroll the content up.
     * @param dx Horizontal distance to travel. Positive numbers will scroll the
     *        content to the left.
     * @param dy Vertical distance to travel. Positive numbers will scroll the
     *        content up.
     * @param minX Minimum X value. The scroller will not scroll past this
     *        point.
     * @param maxX Maximum X value. The scroller will not scroll past this
     *        point.
     * @param minY Minimum Y value. The scroller will not scroll past this
     *        point.
     * @param maxY Maximum Y value. The scroller will not scroll past this
     *        point.
     */
    public void startScroll(int startX, int startY, int dx, int dy,
            int minX, int maxX, int minY, int maxY) {
        mCurrScroller.abortAnimation();
        mCurrScroller = mDefaultScroller;
        mScrollMode = MODE_DEFAULT;
        mMinimumX = minX;
        mMaximumX = maxX; 
        mMinimumY = minY;
        mMaximumY = maxY;
        mCurrScroller.startScroll(startX, startY, dx, dy);
    }

    /**
     * Start scrolling by providing a starting point and the distance to travel.
     * 
     * @param startX Starting horizontal scroll offset in pixels. Positive
     *        numbers will scroll the content to the left.
     * @param startY Starting vertical scroll offset in pixels. Positive numbers
     *        will scroll the content up.
     * @param dx Horizontal distance to travel. Positive numbers will scroll the
     *        content to the left.
     * @param dy Vertical distance to travel. Positive numbers will scroll the
     *        content up.
     * @param duration Duration of the scroll in milliseconds.
     */
    public void startScroll(int startX, int startY, int dx, int dy, int duration) {
        mCurrScroller.abortAnimation();
        mCurrScroller = mDefaultScroller;
        mScrollMode = MODE_DEFAULT;
        mMinimumX = Math.min(startX, startX + dx);
        mMinimumY = Math.min(startY, startY + dy);
        mMaximumX = Math.max(startX, startX + dx);
        mMaximumY = Math.max(startY, startY + dy);
        mCurrScroller.startScroll(startX, startY, dx, dy, duration);
    }
    
    /**
     * Returns the duration of the active scroll in progress; standard, fling,
     * springback, or overfling. Does not account for any overflings or springback
     * that may result.
     */
    public int getDuration() {
        return mCurrScroller.getDuration();
    }

    /**
     * Start scrolling based on a fling gesture. The distance travelled will
     * depend on the initial velocity of the fling.
     * 
     * @param startX Starting point of the scroll (X)
     * @param startY Starting point of the scroll (Y)
     * @param velocityX Initial velocity of the fling (X) measured in pixels per
     *        second.
     * @param velocityY Initial velocity of the fling (Y) measured in pixels per
     *        second
     * @param minX Minimum X value. The scroller will not scroll past this
     *        point.
     * @param maxX Maximum X value. The scroller will not scroll past this
     *        point.
     * @param minY Minimum Y value. The scroller will not scroll past this
     *        point.
     * @param maxY Maximum Y value. The scroller will not scroll past this
     *        point.
     */
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY) {
        this.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY, 0, 0);
    }

    /**
     * Start scrolling based on a fling gesture. The distance travelled will
     * depend on the initial velocity of the fling.
     * 
     * @param startX Starting point of the scroll (X)
     * @param startY Starting point of the scroll (Y)
     * @param velocityX Initial velocity of the fling (X) measured in pixels per
     *        second.
     * @param velocityY Initial velocity of the fling (Y) measured in pixels per
     *        second
     * @param minX Minimum X value. The scroller will not scroll past this
     *        point unless overX > 0. If overfling is allowed, it will use minX
     *        as a springback boundary.
     * @param maxX Maximum X value. The scroller will not scroll past this
     *        point unless overX > 0. If overfling is allowed, it will use maxX
     *        as a springback boundary.
     * @param minY Minimum Y value. The scroller will not scroll past this
     *        point unless overY > 0. If overfling is allowed, it will use minY
     *        as a springback boundary.
     * @param maxY Maximum Y value. The scroller will not scroll past this
     *        point unless overY > 0. If overfling is allowed, it will use maxY
     *        as a springback boundary.
     * @param overX Overfling range. If > 0, horizontal overfling in either
     *        direction will be possible.
     * @param overY Overfling range. If > 0, vertical overfling in either
     *        direction will be possible.
     */
    public void fling(int startX, int startY, int velocityX, int velocityY,
            int minX, int maxX, int minY, int maxY, int overX, int overY) {
        mCurrScroller = mDefaultScroller;
        mScrollMode = MODE_DEFAULT;
        mMinimumX = minX;
        mMaximumX = maxX;
        mMinimumY = minY;
        mMaximumY = maxY;
        mCurrScroller.fling(startX, startY, velocityX, velocityY, 
                minX - overX, maxX + overX, minY - overY, maxY + overY);
    }

    /**
     * Returns where the scroll will end. Valid only for "fling" scrolls.
     * 
     * @return The final X offset as an absolute distance from the origin.
     */
    public int getFinalX() {
        return mCurrScroller.getFinalX();
    }
    
    /**
     * Returns where the scroll will end. Valid only for "fling" scrolls.
     * 
     * @return The final Y offset as an absolute distance from the origin.
     */
    public int getFinalY() {
        return mCurrScroller.getFinalY();
    }
    
    /**
     * @hide
     * Returns the current velocity.
     *
     * @return The original velocity less the deceleration. Result may be
     * negative.
     */
    public float getCurrVelocity() {
        return mCurrScroller.getCurrVelocity();
    }
    
    /**
     * Extend the scroll animation. This allows a running animation to scroll
     * further and longer, when used with {@link #setFinalX(int)} or {@link #setFinalY(int)}.
     *
     * @param extend Additional time to scroll in milliseconds.
     * @see #setFinalX(int)
     * @see #setFinalY(int)
     */
    public void extendDuration(int extend) {
        if (mScrollMode == MODE_DEFAULT) {
            mDefaultScroller.extendDuration(extend);
        }
    }
    
    /**
     * Sets the final position (X) for this scroller.
     *
     * @param newX The new X offset as an absolute distance from the origin.
     * @see #extendDuration(int)
     * @see #setFinalY(int)
     */
    public void setFinalX(int newX) {
        if (mScrollMode == MODE_DEFAULT) {
            if (newX < mMinimumX) {
                mMinimumX = newX;
            }
            if (newX > mMaximumX) {
                mMaximumX = newX;
            }
            mDefaultScroller.setFinalX(newX);
        }
    }
    
    /**
     * Sets the final position (Y) for this scroller.
     *
     * @param newY The new Y offset as an absolute distance from the origin.
     * @see #extendDuration(int)
     * @see #setFinalX(int)
     */
    public void setFinalY(int newY) {
        if (mScrollMode == MODE_DEFAULT) {
            if (newY < mMinimumY) {
                mMinimumY = newY;
            }
            if (newY > mMaximumY) {
                mMaximumY = newY;
            }
            mDefaultScroller.setFinalY(newY);
        }
    }
}
