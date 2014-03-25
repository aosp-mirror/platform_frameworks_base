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

    TouchFeedbackDrawable() {
        this(new TouchFeedbackState(null), null, null);
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

        if (res != null) {
            mDensity = res.getDisplayMetrics().density;
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
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(
                r, theme, attrs, R.styleable.TouchFeedbackDrawable);
        inflateStateFromTypedArray(r, a);
        a.recycle();
        
        inflateChildElements(r, parser, attrs, theme);

        setTargetDensity(r.getDisplayMetrics());
    }
    
    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        int type;
        while ((type = parser.next()) == XmlPullParser.TEXT) {
            // Find the next non-text element.
        }

        if (type == XmlPullParser.START_TAG) {
            final Drawable dr = Drawable.createFromXmlInner(r, parser, attrs);
            setDrawable(dr, r);
        }
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
     * Initializes the constant state from the values in the typed array.
     */
    private void inflateStateFromTypedArray(Resources r, TypedArray a) {
        final TouchFeedbackState state = mState;

        // Extract the theme attributes, if any.
        final int[] themeAttrs = a.extractThemeAttrs();
        state.mThemeAttrs = themeAttrs;

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

        Drawable mask = mMask;
        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_mask] == 0) {
            mask = a.getDrawable(R.styleable.TouchFeedbackDrawable_mask);
        }

        Drawable dr = super.getDrawable();
        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_drawable] == 0) {
            final int drawableRes = a.getResourceId(R.styleable.TouchFeedbackDrawable_drawable, 0);
            if (drawableRes != 0) {
                dr = r.getDrawable(drawableRes);
            }
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

        if (mask != null) {
            setMaskDrawable(mask, r);
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

        final int[] themeAttrs = state.mThemeAttrs;
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

        Drawable mask = mMask;
        if (a.hasValue(R.styleable.TouchFeedbackDrawable_mask)) {
            mask = a.getDrawable(R.styleable.TouchFeedbackDrawable_mask);
        }

        Drawable dr = super.getDrawable();
        if (a.hasValue(R.styleable.TouchFeedbackDrawable_drawable)) {
            final int drawableRes = a.getResourceId(R.styleable.TouchFeedbackDrawable_drawable, 0);
            if (drawableRes != 0) {
                dr = a.getResources().getDrawable(drawableRes);
            }
        }

        // If neither a mask not a bottom layer was specified, assume we're
        // projecting onto a parent surface.
        mState.mProjected = mask == null && dr == null;

        if (dr != null) {
            setDrawable(dr, a.getResources());
        } else {
            // For now at least, we MUST have a wrapped drawable.
            setDrawable(new ColorDrawable(Color.TRANSPARENT), a.getResources());
        }

        if (mask != null) {
            setMaskDrawable(mask, a.getResources());
        }
    }

    @Override
    public boolean canApplyTheme() {
        return mState != null && mState.mThemeAttrs != null;
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
        final Drawable mask = mMask == null && !mState.mProjected ? getDrawable() : null;
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
        int[] mThemeAttrs;
        ConstantState mMaskState;
        ColorStateList mTint;
        Mode mTintMode;
        boolean mPinned;
        boolean mProjected;

        public TouchFeedbackState(TouchFeedbackState orig) {
            super(orig);

            if (orig != null) {
                mThemeAttrs = orig.mThemeAttrs;
                mTint = orig.mTint;
                mTintMode = orig.mTintMode;
                mMaskState = orig.mMaskState;
                mPinned = orig.mPinned;
                mProjected = orig.mProjected;
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null;
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
        if (theme != null && state.canApplyTheme()) {
            mState = new TouchFeedbackState(state);
            applyTheme(theme);
        } else {
            mState = state;
        }

        setConstantState(state, res);
    }
}
