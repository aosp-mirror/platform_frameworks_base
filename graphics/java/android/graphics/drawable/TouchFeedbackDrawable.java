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
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Ripple.RippleAnimator;
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
 * Documentation pending.
 */
public class TouchFeedbackDrawable extends LayerDrawable {
    private static final PorterDuffXfermode DST_ATOP = new PorterDuffXfermode(Mode.DST_ATOP);
    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(Mode.DST_IN);

    /** The maximum number of ripples supported. */
    private static final int MAX_RIPPLES = 10;

    private final Rect mTempRect = new Rect();
    private final Rect mPaddingRect = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    private final TouchFeedbackState mState;

    /** Lazily-created map of touch hotspot IDs to ripples. */
    private SparseArray<Ripple> mTouchedRipples;

    /** Lazily-created array of actively animating ripples. */
    private Ripple[] mActiveRipples;
    private int mActiveRipplesCount = 0;

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

    TouchFeedbackDrawable() {
        this(new TouchFeedbackState(null, null, null), null, null);
    }

    @Override
    public int getOpacity() {
        // Worst-case scenario.
        return PixelFormat.TRANSLUCENT;
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
        return getNumberOfLayers() == 0;
    }

    @Override
    public boolean isStateful() {
        return super.isStateful() || mState.mTint.isStateful();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(
                r, theme, attrs, R.styleable.TouchFeedbackDrawable);
        inflateStateFromTypedArray(a);
        a.recycle();

        super.inflate(r, parser, attrs, theme);

        setTargetDensity(r.getDisplayMetrics());
    }

    /**
     * Initializes the constant state from the values in the typed array.
     */
    private void inflateStateFromTypedArray(TypedArray a) {
        final TouchFeedbackState state = mState;

        // Extract the theme attributes, if any.
        final int[] themeAttrs = a.extractThemeAttrs();
        state.mTouchThemeAttrs = themeAttrs;

        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_tint] == 0) {
            mState.mTint = a.getColorStateList(R.styleable.TouchFeedbackDrawable_tint);

            if (mState.mTint == null) {
                throw new RuntimeException("<touch-feedback> tag requires a 'tint' attribute");
            }
        }

        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_tintMode] == 0) {
            mState.mTintMode = Drawable.parseTintMode(
                    a.getInt(R.styleable.TouchFeedbackDrawable_tintMode, -1), Mode.SRC_ATOP);
        }

        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_pinned] == 0) {
            mState.mPinned = a.getBoolean(R.styleable.TouchFeedbackDrawable_pinned, false);
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

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final TouchFeedbackState state = mState;
        if (state == null) {
            throw new RuntimeException(
                    "Can't apply theme to <touch-feedback> with no constant state");
        }

        final int[] themeAttrs = state.mTouchThemeAttrs;
        if (themeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    themeAttrs, R.styleable.TouchFeedbackDrawable, 0, 0);
            updateStateFromTypedArray(a);
            a.recycle();
        }
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final TouchFeedbackState state = mState;

        if (a.hasValue(R.styleable.TouchFeedbackDrawable_tint)) {
            state.mTint = a.getColorStateList(R.styleable.TouchFeedbackDrawable_tint);
        }

        if (a.hasValue(R.styleable.TouchFeedbackDrawable_tintMode)) {
            mState.mTintMode = Drawable.parseTintMode(
                    a.getInt(R.styleable.TouchFeedbackDrawable_tintMode, -1), Mode.SRC_ATOP);
        }

        if (a.hasValue(R.styleable.TouchFeedbackDrawable_pinned)) {
            mState.mPinned = a.getBoolean(R.styleable.TouchFeedbackDrawable_pinned, false);
        }
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mState != null && mState.mTouchThemeAttrs != null;
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
            mActiveRipples = new Ripple[MAX_RIPPLES];
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
            newRipple.animate().enter();

            mActiveRipples[mActiveRipplesCount++] = newRipple;
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
            ripple.animate().exit();

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
            mTouchedRipples.valueAt(i).animate().exit();
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
        if (mActiveRipplesCount == 0) {
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
        final boolean projected = getNumberOfLayers() == 0;
        final Ripple[] activeRipples = mActiveRipples;
        final int ripplesCount = mActiveRipplesCount;
        final Rect bounds = getBounds();

        // Draw ripples.
        boolean drewRipples = false;
        int rippleRestoreCount = -1;
        int activeRipplesCount = 0;
        for (int i = 0; i < ripplesCount; i++) {
            final Ripple ripple = activeRipples[i];
            final RippleAnimator animator = ripple.animate();
            animator.update();
            if (!animator.isRunning()) {
                activeRipples[i] = null;
            } else {
                // If we're masking the ripple layer, make sure we have a layer
                // first. This will merge SRC_OVER (directly) onto the canvas.
                if (!projected && rippleRestoreCount < 0) {
                    rippleRestoreCount = canvas.saveLayer(bounds.left, bounds.top,
                            bounds.right, bounds.bottom, null, 0);
                    canvas.clipRect(bounds);
                }

                drewRipples |= ripple.draw(canvas, getRipplePaint());

                activeRipples[activeRipplesCount] = activeRipples[i];
                activeRipplesCount++;
            }
        }
        mActiveRipplesCount = activeRipplesCount;

        // TODO: Use the masking layer first, if there is one.

        // If we have ripples and content, we need a masking layer. This will
        // merge DST_ATOP onto (effectively under) the ripple layer.
        if (drewRipples && !projected && rippleRestoreCount >= 0) {
            canvas.saveLayer(bounds.left, bounds.top,
                    bounds.right, bounds.bottom, getMaskingPaint(DST_ATOP), 0);
        }

        Drawable mask = null;
        final ChildDrawable[] array = mLayerState.mChildren;
        final int N = mLayerState.mNum;
        for (int i = 0; i < N; i++) {
            if (array[i].mId != R.id.mask) {
                array[i].mDrawable.draw(canvas);
            } else {
                mask = array[i].mDrawable;
            }
        }

        // If we have ripples, mask them.
        if (mask != null && drewRipples) {
            // TODO: This will also mask the lower layer, which is bad.
            canvas.saveLayer(bounds.left, bounds.top, bounds.right,
                    bounds.bottom, getMaskingPaint(DST_IN), 0);
            mask.draw(canvas);
        }

        // Composite the layers if needed.
        if (rippleRestoreCount >= 0) {
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
        final Ripple[] activeRipples = mActiveRipples;
        final int N = mActiveRipplesCount;
        for (int i = 0; i < N; i++) {
            activeRipples[i].getBounds(rippleBounds);
            drawingBounds.union(rippleBounds);
        }

        dirtyBounds.union(drawingBounds);
        dirtyBounds.union(super.getDirtyBounds());
        return dirtyBounds;
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    static class TouchFeedbackState extends LayerState {
        int[] mTouchThemeAttrs;
        ColorStateList mTint;
        Mode mTintMode;
        boolean mPinned;

        public TouchFeedbackState(
                TouchFeedbackState orig, TouchFeedbackDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mTouchThemeAttrs = orig.mTouchThemeAttrs;
                mTint = orig.mTint;
                mTintMode = orig.mTintMode;
                mPinned = orig.mPinned;
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mTouchThemeAttrs != null || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new TouchFeedbackDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new TouchFeedbackDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new TouchFeedbackDrawable(this, res, theme);
        }
    }

    private TouchFeedbackDrawable(TouchFeedbackState state, Resources res, Theme theme) {
        boolean needsTheme = false;

        final TouchFeedbackState ns;
        if (theme != null && state != null && state.canApplyTheme()) {
            ns = new TouchFeedbackState(state, this, res);
            needsTheme = true;
        } else if (state == null) {
            ns = new TouchFeedbackState(null, this, res);
        } else {
            ns = state;
        }

        if (res != null) {
            mDensity = res.getDisplayMetrics().density;
        }

        mState = ns;
        mLayerState = ns;

        if (ns.mNum > 0) {
            ensurePadding();
        }

        if (needsTheme) {
            applyTheme(theme);
        }

        setPaddingMode(PADDING_MODE_STACK);
    }
}
