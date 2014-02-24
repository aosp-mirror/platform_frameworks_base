/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * An extension of LayerDrawable that is intended to react to touch hotspots
 * and reveal the second layer atop the first.
 * <p>
 * It can be defined in an XML file with the <code>&lt;reveal&gt;</code> element.
 * Each Drawable in the transition is defined in a nested <code>&lt;item&gt;</code>.
 * For more information, see the guide to <a href="{@docRoot}
 * guide/topics/resources/drawable-resource.html">Drawable Resources</a>.
 *
 * @attr ref android.R.styleable#LayerDrawableItem_left
 * @attr ref android.R.styleable#LayerDrawableItem_top
 * @attr ref android.R.styleable#LayerDrawableItem_right
 * @attr ref android.R.styleable#LayerDrawableItem_bottom
 * @attr ref android.R.styleable#LayerDrawableItem_drawable
 * @attr ref android.R.styleable#LayerDrawableItem_id
 * @hide
 */
public class TouchFeedbackDrawable extends Drawable {
    private final Rect mTempRect = new Rect();
    private final Rect mPaddingRect = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    private final TouchFeedbackState mState;

    /** Lazily-created map of touch hotspot IDs to ripples. */
    private SparseArray<Ripple> mTouchedRipples;

    /** Lazily-created list of actively animating ripples. */
    private ArrayList<Ripple> mActiveRipples;

    /** Lazily-created runnable for scheduling invalidation. */
    private Runnable mAnimationRunnable;

    /** Paint used to control appearance of ripples. */
    private Paint mRipplePaint;

    /** Target density of the display into which ripples are drawn. */
    private float mDensity = 1.0f;

    /** Whether the animation runnable has been posted. */
    private boolean mAnimating;

    TouchFeedbackDrawable() {
        this(new TouchFeedbackState(null), null);
    }

    TouchFeedbackDrawable(TouchFeedbackState state, Resources res) {
        if (res != null) {
            mDensity = res.getDisplayMetrics().density;
        }

        mState = state;
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        // Not supported.
    }

    @Override
    public void setAlpha(int alpha) {
        // Not supported.
    }

    @Override
    public int getOpacity() {
        return mActiveRipples != null && !mActiveRipples.isEmpty() ?
                PixelFormat.TRANSLUCENT : PixelFormat.TRANSPARENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final ColorStateList stateList = mState.mColorStateList;
        if (stateList != null && mRipplePaint != null) {
            final int newColor = stateList.getColorForState(stateSet, 0);
            final int oldColor = mRipplePaint.getColor();
            if (oldColor != newColor) {
                mRipplePaint.setColor(newColor);
                invalidateSelf();
                return true;
            }
        }

        return false;
    }

    /**
     * @hide
     */
    @Override
    public boolean isProjected() {
        return true;
    }

    @Override
    public boolean isStateful() {
        return mState.mColorStateList != null && mState.mColorStateList.isStateful();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        final TypedArray a = r.obtainAttributes(
                attrs, com.android.internal.R.styleable.ColorDrawable);
        mState.mColorStateList = a.getColorStateList(
                com.android.internal.R.styleable.ColorDrawable_color);
        a.recycle();

        setTargetDensity(r.getDisplayMetrics());
    }

    /**
     * Set the density at which this drawable will be rendered.
     *
     * @param metrics The display metrics for this drawable.
     */
    private void setTargetDensity(DisplayMetrics metrics) {
        if (mDensity != metrics.density) {
            mDensity = metrics.density;
            invalidateSelf();
        }
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public boolean supportsHotspots() {
        return true;
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void setHotspot(int id, float x, float y) {
        if (mTouchedRipples == null) {
            mTouchedRipples = new SparseArray<Ripple>();
            mActiveRipples = new ArrayList<Ripple>();
        }

        final Ripple ripple = mTouchedRipples.get(id);
        if (ripple == null) {
            final Rect padding = mPaddingRect;
            getPadding(padding);

            final Rect bounds = getBounds();
            final Ripple newRipple = new Ripple(bounds, padding, bounds.exactCenterX(),
                    bounds.exactCenterY(), mDensity);
            newRipple.enter();

            mActiveRipples.add(newRipple);
            mTouchedRipples.put(id, newRipple);
        } else {
            // TODO: How do we want to respond to movement?
            //ripple.move(x, y);
        }

        scheduleAnimation();
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void removeHotspot(int id) {
        if (mTouchedRipples == null) {
            return;
        }

        final Ripple ripple = mTouchedRipples.get(id);
        if (ripple != null) {
            ripple.exit();

            mTouchedRipples.remove(id);
            scheduleAnimation();
        }
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void clearHotspots() {
        if (mTouchedRipples == null) {
            return;
        }

        final int n = mTouchedRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = mTouchedRipples.valueAt(i);
            ripple.exit();
        }

        if (n > 0) {
            mTouchedRipples.clear();
            scheduleAnimation();
        }
    }

    /**
     * Schedules the next animation, if necessary.
     */
    private void scheduleAnimation() {
        if (mActiveRipples == null || mActiveRipples.isEmpty()) {
            mAnimating = false;
        } else if (!mAnimating) {
            mAnimating = true;

            if (mAnimationRunnable == null) {
                mAnimationRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mAnimating = false;
                        scheduleAnimation();
                        invalidateSelf();
                    }
                };
            }

            scheduleSelf(mAnimationRunnable, SystemClock.uptimeMillis() + 1000 / 60);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final ArrayList<Ripple> activeRipples = mActiveRipples;
        if (activeRipples == null || activeRipples.isEmpty()) {
            // Nothing to draw, we're done here.
            return;
        }

        final ColorStateList stateList = mState.mColorStateList;
        if (stateList == null) {
            // No color, we're done here.
            return;
        }

        final int color = stateList.getColorForState(getState(), Color.TRANSPARENT);
        if (color == Color.TRANSPARENT) {
            // No color, we're done here.
            return;
        }

        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
        }

        mRipplePaint.setColor(color);

        final int restoreCount = canvas.save();

        // Draw ripples directly onto the canvas.
        int n = activeRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = activeRipples.get(i);
            if (!ripple.active()) {
                activeRipples.remove(i);
                i--;
                n--;
            } else {
                ripple.draw(canvas, mRipplePaint);
            }
        }

        canvas.restoreToCount(restoreCount);
    }

    @Override
    public Rect getDirtyBounds() {
        final Rect dirtyBounds = mDirtyBounds;
        final Rect drawingBounds = mDrawingBounds;
        dirtyBounds.set(drawingBounds);
        drawingBounds.setEmpty();

        final Rect rippleBounds = mTempRect;
        final ArrayList<Ripple> activeRipples = mActiveRipples;
        if (activeRipples != null) {
           final int N = activeRipples.size();
           for (int i = 0; i < N; i++) {
               activeRipples.get(i).getBounds(rippleBounds);
               drawingBounds.union(rippleBounds);
           }
        }

        dirtyBounds.union(drawingBounds);
        return dirtyBounds;
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    static class TouchFeedbackState extends ConstantState {
        ColorStateList mColorStateList;

        public TouchFeedbackState(TouchFeedbackState orig) {
            if (orig != null) {
                mColorStateList = orig.mColorStateList;
            }
        }

        @Override
        public int getChangingConfigurations() {
            return 0;
        }

        @Override
        public Drawable newDrawable() {
            return newDrawable(null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new TouchFeedbackDrawable(this, res);
        }
    }
}
