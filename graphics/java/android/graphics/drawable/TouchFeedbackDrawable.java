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
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * @hide
 */
public class TouchFeedbackDrawable extends DrawableWrapper {
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

    /** Paint used to control reveal layer masking. */
    private Paint mMaskingPaint;

    /** Target density of the display into which ripples are drawn. */
    private float mDensity = 1.0f;

    /** Whether the animation runnable has been posted. */
    private boolean mAnimating;

    /** The drawable to use as the mask. */
    private Drawable mMask;

    /* package */TouchFeedbackDrawable() {
        this(null, null);
    }

    TouchFeedbackDrawable(TouchFeedbackState state, Resources res) {
        mState = new TouchFeedbackState(state);
        
        setConstantState(mState, res);

        if (res != null) {
            mDensity = res.getDisplayMetrics().density;
        }
    }
    
    private void setConstantState(TouchFeedbackState wrapperState, Resources res) {
        super.setConstantState(wrapperState, res);

        // Load a new mask drawable from the constant state.
        if (wrapperState == null || wrapperState.mMaskState == null) {
            mMask = null;
        } else if (res != null) {
            mMask = wrapperState.mMaskState.newDrawable(res);
        } else {
            mMask = wrapperState.mMaskState.newDrawable();
        }
    }

    @Override
    public int getOpacity() {
        return mActiveRipples != null && !mActiveRipples.isEmpty() ?
                PixelFormat.TRANSLUCENT : PixelFormat.TRANSPARENT;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (mMask != null) {
            mMask.setBounds(bounds);
        }
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        super.onStateChange(stateSet);

        if (mRipplePaint != null) {
            final ColorStateList stateList = mState.mTint;
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
        return mState.mProjected;
    }

    @Override
    public boolean isStateful() {
        return super.isStateful() || mState.mTint.isStateful();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        final TypedArray a = r.obtainAttributes(attrs, R.styleable.TouchFeedbackDrawable);

        mState.mTint = a.getColorStateList(R.styleable.TouchFeedbackDrawable_tint);
        mState.mTintMode = Drawable.parseTintMode(
                a.getInt(R.styleable.TouchFeedbackDrawable_tintMode, -1), Mode.SRC_ATOP);
        mState.mPinned = a.getBoolean(R.styleable.TouchFeedbackDrawable_pinned, false);

        if (mState.mTint == null) {
            throw new XmlPullParserException(parser.getPositionDescription()
                    + ": <touch-feedback> tag requires a 'tint' attribute");
        }
        
        Drawable mask = a.getDrawable(R.styleable.TouchFeedbackDrawable_mask);
        final int drawableRes = a.getResourceId(R.styleable.TouchFeedbackDrawable_drawable, 0);
        a.recycle();

        final Drawable dr;
        if (drawableRes != 0) {
            dr = r.getDrawable(drawableRes);
        } else {
            int type;
            while ((type = parser.next()) == XmlPullParser.TEXT) {
                // Find the next non-text element.
            }

            if (type == XmlPullParser.START_TAG) {
                dr = Drawable.createFromXmlInner(r, parser, attrs);
            } else {
                dr = null;
            }
        }

        // If no mask is set, implicitly use the lower drawable.
        if (mask == null) {
            mask = dr;
        }

        // If neither a mask not a bottom layer was specified, assume we're
        // projecting onto a parent surface.
        mState.mProjected = mask == null && dr == null;

        if (dr != null) {
            setDrawable(dr, r);
        } else {
            // For now at least, we MUST have a wrapped drawable.
            setDrawable(new ColorDrawable(Color.TRANSPARENT), r);
        }

        setMaskDrawable(mask, r);
        setTargetDensity(r.getDisplayMetrics());
    }

    /**
     * Sets the wrapped drawable and update the constant state.
     *
     * @param drawable
     * @param res
     */
    void setMaskDrawable(Drawable drawable, Resources res) {
        mMask = drawable;

        if (drawable != null) {
            // Nobody cares if the mask has a callback.
            drawable.setCallback(null);

            mState.mMaskState = drawable.getConstantState();
        } else {
            mState.mMaskState = null;
        }
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
     * TODO: Maybe we should set hotspots for state/id combinations? So touch
     * would be state_pressed and the pointer ID.
     *
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
            final Rect bounds = getBounds();
            final Rect padding = mPaddingRect;
            getPadding(padding);

            if (mState.mPinned) {
                x = bounds.exactCenterX();
                y = bounds.exactCenterY();
            }

            final Ripple newRipple = new Ripple(bounds, padding, x, y, mDensity);
            newRipple.enter();

            mActiveRipples.add(newRipple);
            mTouchedRipples.put(id, newRipple);
        } else if (!mState.mPinned) {
            ripple.move(x, y);
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
        // The lower layer always draws normally.
        super.draw(canvas);

        if (mActiveRipples == null || mActiveRipples.size() == 0) {
            // No ripples to draw.
            return;
        }

        final ArrayList<Ripple> activeRipples = mActiveRipples;
        final Drawable mask = mMask;
        final Rect bounds = mask == null ? null : mask.getBounds();

        // Draw ripples into a layer that merges using SRC_IN.
        boolean hasRipples = false;
        int rippleRestoreCount = -1;
        int n = activeRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = activeRipples.get(i);
            if (!ripple.active()) {
                // TODO: Mark and sweep is more efficient.
                activeRipples.remove(i);
                i--;
                n--;
            } else {
                // If we're masking the ripple layer, make sure we have a layer first.
                if (mask != null && rippleRestoreCount < 0) {
                    rippleRestoreCount = canvas.saveLayer(bounds.left, bounds.top,
                            bounds.right, bounds.bottom, getMaskingPaint(SRC_ATOP), 0);
                    canvas.clipRect(bounds);
                }

                hasRipples |= ripple.draw(canvas, getRipplePaint());
            }
        }

        // If we have ripples, mask them.
        if (mask != null && hasRipples) {
            canvas.saveLayer(bounds.left, bounds.top, bounds.right,
                    bounds.bottom, getMaskingPaint(DST_IN), 0);
            mask.draw(canvas);
        }

        // Composite the layers if needed:
        // 1. Mask     DST_IN
        // 2. Ripples  SRC_ATOP
        // 3. Lower    n/a
        if (rippleRestoreCount > 0) {
            canvas.restoreToCount(rippleRestoreCount);
        }
    }

    private Paint getRipplePaint() {
        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
            
            final int color = mState.mTint.getColorForState(getState(), Color.TRANSPARENT);
            mRipplePaint.setColor(color);
        }
        return mRipplePaint;
    }
    
    private static final PorterDuffXfermode SRC_ATOP = new PorterDuffXfermode(Mode.SRC_ATOP);
    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(Mode.DST_IN);

    private Paint getMaskingPaint(PorterDuffXfermode mode) {
        if (mMaskingPaint == null) {
            mMaskingPaint = new Paint();
        }
        mMaskingPaint.setXfermode(mode);
        return mMaskingPaint;
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
        dirtyBounds.union(super.getDirtyBounds());
        return dirtyBounds;
    }

    @Override
    public ConstantState getConstantState() {
        // TODO: Can we just rely on super.getConstantState()?
        return mState;
    }

    static class TouchFeedbackState extends WrapperState {
        ConstantState mMaskState;
        ColorStateList mTint;
        Mode mTintMode;
        boolean mPinned;
        boolean mProjected;

        public TouchFeedbackState(TouchFeedbackState orig) {
            super(orig);

            if (orig != null) {
                mTint = orig.mTint;
                mTintMode = orig.mTintMode;
                mMaskState = orig.mMaskState;
                mPinned = orig.mPinned;
                mProjected = orig.mProjected;
            }
        }

        @Override
        public Drawable newDrawable() {
            return new TouchFeedbackDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new TouchFeedbackDrawable(this, res);
        }
    }
}
