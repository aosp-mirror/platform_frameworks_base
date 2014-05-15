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
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Documentation pending.
 */
public class TouchFeedbackDrawable extends LayerDrawable {
    private static final String LOG_TAG = TouchFeedbackDrawable.class.getSimpleName();
    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(Mode.DST_IN);
    private static final PorterDuffXfermode SRC_OVER = new PorterDuffXfermode(Mode.SRC_OVER);

    /** The maximum number of ripples supported. */
    private static final int MAX_RIPPLES = 10;

    private final Rect mTempRect = new Rect();

    /** Current ripple effect bounds, used to constrain ripple effects. */
    private final Rect mHotspotBounds = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    private final TouchFeedbackState mState;

    /** Lazily-created map of touch hotspot IDs to ripples. */
    private SparseArray<Ripple> mRipples;

    /** Lazily-created array of actively animating ripples. */
    private Ripple[] mAnimatingRipples;
    private int mAnimatingRipplesCount = 0;

    /** Paint used to control appearance of ripples. */
    private Paint mRipplePaint;

    /** Paint used to control reveal layer masking. */
    private Paint mMaskingPaint;

    /** Target density of the display into which ripples are drawn. */
    private float mDensity = 1.0f;

    /** Whether bounds are being overridden. */
    private boolean mOverrideBounds;

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

        // TODO: Implicitly tie states to ripple IDs. For now, just clear
        // focused and pressed if they aren't in the state set.
        boolean hasFocused = false;
        boolean hasPressed = false;
        for (int i = 0; i < stateSet.length; i++) {
            if (stateSet[i] == R.attr.state_pressed) {
                hasPressed = true;
            } else if (stateSet[i] == R.attr.state_focused) {
                hasFocused = true;
            }
        }

        if (!hasPressed) {
            removeHotspot(R.attr.state_pressed);
        }

        if (!hasFocused) {
            removeHotspot(R.attr.state_focused);
        }

        if (mRipplePaint != null && mState.mTint != null) {
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

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (!mOverrideBounds) {
            mHotspotBounds.set(bounds);
        }

        onHotspotBoundsChange();
    }

    private void onHotspotBoundsChange() {
        final int x = mHotspotBounds.centerX();
        final int y = mHotspotBounds.centerY();
        final int N = mAnimatingRipplesCount;
        for (int i = 0; i < N; i++) {
            if (mState.mPinned) {
                mAnimatingRipples[i].move(x, y);
            }
            mAnimatingRipples[i].onBoundsChanged();
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        if (!visible) {
            clearHotspots();
        }

        return super.setVisible(visible, restart);
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
        return super.isStateful() || mState.mTint != null && mState.mTint.isStateful();
    }

    /**
     * Specifies a tint for drawing touch feedback ripples.
     *
     * @param tint Color state list to use for tinting touch feedback ripples,
     *        or null to clear the tint
     */
    public void setTint(ColorStateList tint) {
        if (mState.mTint != tint) {
            mState.mTint = tint;
            invalidateSelf();
        }
    }

    /**
     * Returns the tint color for touch feedback ripples.
     *
     * @return Color state list to use for tinting touch feedback ripples, or
     *         null if none set
     */
    public ColorStateList getTint() {
        return mState.mTint;
    }

    /**
     * Specifies the blending mode used to draw touch feedback ripples.
     *
     * @param tintMode A Porter-Duff blending mode
     */
    public void setTintMode(Mode tintMode) {
        mState.setTintMode(tintMode);
        invalidateSelf();
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

        // Find the mask
        final int N = getNumberOfLayers();
        for (int i = 0; i < N; i++) {
            if (mLayerState.mChildren[i].mId == R.id.mask) {
                mState.mMask = mLayerState.mChildren[i].mDrawable;
            }
        }
    }

    @Override
    public boolean setDrawableByLayerId(int id, Drawable drawable) {
        if (super.setDrawableByLayerId(id, drawable)) {
            if (id == R.id.mask) {
                mState.mMask = drawable;
            }

            return true;
        }

        return false;
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
        }

        if (themeAttrs == null || themeAttrs[R.styleable.TouchFeedbackDrawable_tintMode] == 0) {
            mState.setTintMode(Drawable.parseTintMode(
                    a.getInt(R.styleable.TouchFeedbackDrawable_tintMode, -1), Mode.SRC_ATOP));
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
                    themeAttrs, R.styleable.TouchFeedbackDrawable);
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
            mState.setTintMode(Drawable.parseTintMode(
                    a.getInt(R.styleable.TouchFeedbackDrawable_tintMode, -1), Mode.SRC_ATOP));
        }

        if (a.hasValue(R.styleable.TouchFeedbackDrawable_pinned)) {
            mState.mPinned = a.getBoolean(R.styleable.TouchFeedbackDrawable_pinned, false);
        }
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mState != null && mState.mTouchThemeAttrs != null;
    }

    @Override
    public boolean supportsHotspots() {
        return true;
    }

    @Override
    public void setHotspot(int id, float x, float y) {
        if (mRipples == null) {
            mRipples = new SparseArray<Ripple>();
            mAnimatingRipples = new Ripple[MAX_RIPPLES];
        }

        if (mAnimatingRipplesCount >= MAX_RIPPLES) {
            Log.e(LOG_TAG, "Max ripple count exceeded", new RuntimeException());
            return;
        }

        final Ripple ripple = mRipples.get(id);
        if (ripple == null) {
            final Rect bounds = mHotspotBounds;
            if (mState.mPinned) {
                x = bounds.exactCenterX();
                y = bounds.exactCenterY();
            }

            // TODO: Clean this up in the API.
            final boolean pulse = (id != R.attr.state_focused);
            final Ripple newRipple = new Ripple(this, bounds, mDensity, pulse);
            newRipple.move(x, y);

            mAnimatingRipples[mAnimatingRipplesCount++] = newRipple;
            mRipples.put(id, newRipple);
        } else if (mState.mPinned) {
            final Rect bounds = mHotspotBounds;
            x = bounds.exactCenterX();
            y = bounds.exactCenterY();
            ripple.move(x, y);
        } else {
            ripple.move(x, y);
        }
    }

    @Override
    public void removeHotspot(int id) {
        if (mRipples == null) {
            return;
        }

        final Ripple ripple = mRipples.get(id);
        if (ripple != null) {
            ripple.exit();

            mRipples.remove(id);
        }
    }

    @Override
    public void clearHotspots() {
        if (mRipples != null) {
            mRipples.clear();
        }

        final int count = mAnimatingRipplesCount;
        final Ripple[] ripples = mAnimatingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].cancel();
            ripples[i] = null;
        }

        mAnimatingRipplesCount = 0;
        invalidateSelf();
    }

    /**
     * @hide
     */
    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mOverrideBounds = true;
        mHotspotBounds.set(left, top, right, bottom);
        onHotspotBoundsChange();
    }

    @Override
    public void draw(Canvas canvas) {
        final int N = mLayerState.mNum;
        final Rect bounds = getBounds();
        final ChildDrawable[] array = mLayerState.mChildren;
        final boolean maskOnly = mState.mMask != null && N == 1;

        int restoreToCount = drawRippleLayer(canvas, bounds, maskOnly);

        if (restoreToCount >= 0) { 
            // We have a ripple layer that contains ripples. If we also have an
            // explicit mask drawable, apply it now using DST_IN blending.
            if (mState.mMask != null) {
                canvas.saveLayer(bounds.left, bounds.top, bounds.right,
                        bounds.bottom, getMaskingPaint(DST_IN));
                mState.mMask.draw(canvas);
                canvas.restoreToCount(restoreToCount);
                restoreToCount = -1;
            }

            // If there's more content, we need an extra masking layer to merge
            // the ripples over the content.
            if (!maskOnly) {
                final PorterDuffXfermode xfermode = mState.getTintXfermodeInverse();
                final int count = canvas.saveLayer(bounds.left, bounds.top,
                        bounds.right, bounds.bottom, getMaskingPaint(xfermode));
                if (restoreToCount < 0) {
                    restoreToCount = count;
                }
            }
        }

        // Draw everything except the mask.
        for (int i = 0; i < N; i++) {
            if (array[i].mId != R.id.mask) {
                array[i].mDrawable.draw(canvas);
            }
        }

        // Composite the layers if needed.
        if (restoreToCount >= 0) {
            canvas.restoreToCount(restoreToCount);
        }
    }

    private int drawRippleLayer(Canvas canvas, Rect bounds, boolean maskOnly) {
        final int count = mAnimatingRipplesCount;
        if (count == 0) {
            return -1;
        }

        final Ripple[] ripples = mAnimatingRipples;
        final boolean projected = isProjected();
        final Rect layerBounds = projected ? getDirtyBounds() : bounds;

        // Separate the ripple color and alpha channel. The alpha will be
        // applied when we merge the ripples down to the canvas.
        final int rippleColor;
        if (mState.mTint != null) {
            rippleColor = mState.mTint.getColorForState(getState(), Color.TRANSPARENT);
        } else {
            rippleColor = Color.TRANSPARENT;
        }
        final int rippleAlpha = Color.alpha(rippleColor);

        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
        }
        final Paint ripplePaint = mRipplePaint;
        ripplePaint.setColor(rippleColor);

        boolean drewRipples = false;
        int restoreToCount = -1;
        int animatingCount = 0;

        // Draw ripples and update the animating ripples array.
        for (int i = 0; i < count; i++) {
            final Ripple ripple = ripples[i];

            // Mark and skip finished ripples.
            if (ripple.isFinished()) {
                ripples[i] = null;
                continue;
            }

            // If we're masking the ripple layer, make sure we have a layer
            // first. This will merge SRC_OVER (directly) onto the canvas.
            if (restoreToCount < 0) {
                // If we're projecting or we only have a mask, we want to treat the
                // underlying canvas as our content and merge the ripple layer down
                // using the tint xfermode.
                final PorterDuffXfermode xfermode;
                if (projected || maskOnly) {
                    xfermode = mState.getTintXfermode();
                } else {
                    xfermode = SRC_OVER;
                }

                final Paint layerPaint = getMaskingPaint(xfermode);
                layerPaint.setAlpha(rippleAlpha);
                restoreToCount = canvas.saveLayer(layerBounds.left, layerBounds.top,
                        layerBounds.right, layerBounds.bottom, layerPaint);
                layerPaint.setAlpha(255);
            }

            drewRipples |= ripple.draw(canvas, ripplePaint);

            ripples[animatingCount] = ripples[i];
            animatingCount++;
        }

        mAnimatingRipplesCount = animatingCount;

        // If we created a layer with no content, merge it immediately.
        if (restoreToCount >= 0 && !drewRipples) {
            canvas.restoreToCount(restoreToCount);
            restoreToCount = -1;
        }

        return restoreToCount;
    }

    private Paint getMaskingPaint(PorterDuffXfermode xfermode) {
        if (mMaskingPaint == null) {
            mMaskingPaint = new Paint();
        }
        mMaskingPaint.setXfermode(xfermode);
        return mMaskingPaint;
    }

    @Override
    public Rect getDirtyBounds() {
        final Rect drawingBounds = mDrawingBounds;
        final Rect dirtyBounds = mDirtyBounds;
        dirtyBounds.set(drawingBounds);
        drawingBounds.setEmpty();

        final Rect rippleBounds = mTempRect;
        final Ripple[] activeRipples = mAnimatingRipples;
        final int N = mAnimatingRipplesCount;
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
        PorterDuffXfermode mTintXfermode;
        PorterDuffXfermode mTintXfermodeInverse;
        Drawable mMask;
        boolean mPinned;

        public TouchFeedbackState(
                TouchFeedbackState orig, TouchFeedbackDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mTouchThemeAttrs = orig.mTouchThemeAttrs;
                mTint = orig.mTint;
                mTintXfermode = orig.mTintXfermode;
                mTintXfermodeInverse = orig.mTintXfermodeInverse;
                mPinned = orig.mPinned;
                mMask = orig.mMask;
            }
        }

        public void setTintMode(Mode mode) {
            final Mode invertedMode = TouchFeedbackState.invertPorterDuffMode(mode);
            mTintXfermodeInverse = new PorterDuffXfermode(invertedMode);
            mTintXfermode = new PorterDuffXfermode(mode);
        }

        public PorterDuffXfermode getTintXfermode() {
            return mTintXfermode;
        }

        public PorterDuffXfermode getTintXfermodeInverse() {
            return mTintXfermodeInverse;
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

        /**
         * Inverts SRC and DST in PorterDuff blending modes.
         */
        private static Mode invertPorterDuffMode(Mode src) {
            switch (src) {
                case SRC_ATOP:
                    return Mode.DST_ATOP;
                case SRC_IN:
                    return Mode.DST_IN;
                case SRC_OUT:
                    return Mode.DST_OUT;
                case SRC_OVER:
                    return Mode.DST_OVER;
                case DST_ATOP:
                    return Mode.SRC_ATOP;
                case DST_IN:
                    return Mode.SRC_IN;
                case DST_OUT:
                    return Mode.SRC_OUT;
                case DST_OVER:
                    return Mode.SRC_OVER;
                default:
                    // Everything else is agnostic to SRC versus DST.
                    return src;
            }
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
            // We always need a new state since child drawables contain local
            // state but live within the parent's constant state.
            // TODO: Move child drawables into local state.
            ns = new TouchFeedbackState(state, this, res);
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
