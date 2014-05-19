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
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.org.bouncycastle.util.Arrays;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Drawable that shows a ripple effect in response to state changes. The
 * anchoring position of the ripple for a given state may be specified by
 * calling {@link #setHotspot(float, float)} with the corresponding state
 * attribute identifier.
 * <p>
 * A touch feedback drawable may contain multiple child layers, including a
 * special mask layer that is not drawn to the screen. A single layer may be set
 * as the mask by specifying its android:id value as {@link android.R.id#mask}.
 * <p>
 * If a mask layer is set, the ripple effect will be masked against that layer
 * before it is blended onto the composite of the remaining child layers.
 * <p>
 * If no mask layer is set, the ripple effect is simply blended onto the
 * composite of the child layers using the specified
 * {@link android.R.styleable#RippleDrawable_tintMode}.
 * <p>
 * If no child layers or mask is specified and the ripple is set as a View
 * background, the ripple will be blended onto the first available parent
 * background within the View's hierarchy using the specified
 * {@link android.R.styleable#RippleDrawable_tintMode}. In this case, the
 * drawing region may extend outside of the Drawable bounds.
 *
 * @attr ref android.R.styleable#DrawableStates_state_focused
 * @attr ref android.R.styleable#DrawableStates_state_pressed
 */
public class RippleDrawable extends LayerDrawable {
    private static final String LOG_TAG = RippleDrawable.class.getSimpleName();
    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(Mode.DST_IN);
    private static final PorterDuffXfermode DST_ATOP = new PorterDuffXfermode(Mode.DST_ATOP);
    private static final PorterDuffXfermode SRC_ATOP = new PorterDuffXfermode(Mode.SRC_ATOP);
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

    private final RippleState mState;

    /**
     * Lazily-created map of pending hotspot locations. These may be modified by
     * calls to {@link #setHotspot(float, float)}.
     */
    private SparseArray<PointF> mPendingHotspots;

    /**
     * Lazily-created map of active hotspot locations. These may be modified by
     * calls to {@link #setHotspot(float, float)}.
     */
    private SparseArray<Ripple> mActiveHotspots;

    /**
     * Lazily-created array of actively animating ripples. Inactive ripples are
     * pruned during draw(). The locations of these will not change.
     */
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

    RippleDrawable() {
        this(new RippleState(null, null, null), null, null);
    }

    @Override
    public void setAlpha(int alpha) {
        
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        
    }

    @Override
    public int getOpacity() {
        // Worst-case scenario.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        super.onStateChange(stateSet);

        final boolean pressed = Arrays.contains(stateSet, R.attr.state_pressed);
        if (!pressed) {
            removeHotspot(R.attr.state_pressed);
        } else {
            activateHotspot(R.attr.state_pressed);
        }

        final boolean focused = Arrays.contains(stateSet, R.attr.state_focused);
        if (!focused) {
            removeHotspot(R.attr.state_focused);
        } else {
            activateHotspot(R.attr.state_focused);
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

        invalidateSelf();
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
        return true;
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
                r, theme, attrs, R.styleable.RippleDrawable);
        updateStateFromTypedArray(a);
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
    private void updateStateFromTypedArray(TypedArray a) {
        final RippleState state = mState;

        // Extract the theme attributes, if any.
        state.mTouchThemeAttrs = a.extractThemeAttrs();

        final ColorStateList tint = a.getColorStateList(R.styleable.RippleDrawable_tint);
        if (tint != null) {
            mState.mTint = tint;
        }

        final int tintMode = a.getInt(R.styleable.RippleDrawable_tintMode, -1);
        if (tintMode != -1) {
            mState.setTintMode(Drawable.parseTintMode(tintMode, Mode.SRC_ATOP));
        }

        mState.mPinned = a.getBoolean(R.styleable.RippleDrawable_pinned, mState.mPinned);
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

        final RippleState state = mState;
        if (state == null || state.mTouchThemeAttrs == null) {
            return;
        }

        final TypedArray a = t.resolveAttributes(state.mTouchThemeAttrs,
                R.styleable.RippleDrawable);
        updateStateFromTypedArray(a);
        a.recycle();
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mState != null && mState.mTouchThemeAttrs != null;
    }

    @Override
    public void setHotspot(float x, float y) {
        if (mState.mPinned && !circleContains(mHotspotBounds, x, y)) {
            x = mHotspotBounds.exactCenterX();
            y = mHotspotBounds.exactCenterY();
        }

        // TODO: We should only have a single pending/active hotspot.
        final int id = R.attr.state_pressed;
        final int[] stateSet = getState();
        if (!Arrays.contains(stateSet, id)) {
            // The hotspot is not active, so just modify the pending location.
            getOrCreatePendingHotspot(id).set(x, y);
            return;
        }

        if (mAnimatingRipplesCount >= MAX_RIPPLES) {
            // This should never happen unless the user is tapping like a maniac
            // or there is a bug that's preventing ripples from being removed.
            Log.d(LOG_TAG, "Max ripple count exceeded", new RuntimeException());
            return;
        }

        if (mActiveHotspots == null) {
            mActiveHotspots = new SparseArray<Ripple>();
            mAnimatingRipples = new Ripple[MAX_RIPPLES];
        }

        final Ripple ripple = mActiveHotspots.get(id);
        if (ripple != null) {
            // The hotspot is active, but we can't move it because it's probably
            // busy animating the center position.
            return;
        }

        // The hotspot needs to be made active.
        createActiveHotspot(id, x, y);
    }

    private boolean circleContains(Rect bounds, float x, float y) {
        final float pX = bounds.exactCenterX() - x;
        final float pY = bounds.exactCenterY() - y;
        final double pointRadius = Math.sqrt(pX * pX + pY * pY);

        final float bX = bounds.width() / 2.0f;
        final float bY = bounds.height() / 2.0f;
        final double boundsRadius = Math.sqrt(bX * bX + bY * bY);

        return pointRadius < boundsRadius;
    }

    private PointF getOrCreatePendingHotspot(int id) {
        final PointF p;
        if (mPendingHotspots == null) {
            mPendingHotspots = new SparseArray<>(2);
            p = null;
        } else {
            p = mPendingHotspots.get(id);
        }

        if (p == null) {
            final PointF newPoint = new PointF();
            mPendingHotspots.put(id, newPoint);
            return newPoint;
        } else {
            return p;
        }
    }

    /**
     * Moves a hotspot from pending to active.
     */
    private void activateHotspot(int id) {
        final SparseArray<PointF> pendingHotspots = mPendingHotspots;
        if (pendingHotspots != null) {
            final int index = pendingHotspots.indexOfKey(id);
            if (index >= 0) {
                final PointF hotspot = pendingHotspots.valueAt(index);
                pendingHotspots.removeAt(index);
                createActiveHotspot(id, hotspot.x, hotspot.y);
            }
        }
    }

    /**
     * Creates an active hotspot at the specified location.
     */
    private void createActiveHotspot(int id, float x, float y) {
        final int color = mState.mTint.getColorForState(getState(), Color.TRANSPARENT);
        final Ripple newRipple = new Ripple(this, mHotspotBounds, color);
        newRipple.enter(x, y);

        if (mAnimatingRipples == null) {
            mAnimatingRipples = new Ripple[MAX_RIPPLES];
        }
        mAnimatingRipples[mAnimatingRipplesCount++] = newRipple;

        if (mActiveHotspots == null) {
            mActiveHotspots = new SparseArray<Ripple>();
        }
        mActiveHotspots.put(id, newRipple);
    }

    private void removeHotspot(int id) {
        if (mActiveHotspots == null) {
            return;
        }

        final Ripple ripple = mActiveHotspots.get(id);
        if (ripple != null) {
            ripple.exit();

            mActiveHotspots.remove(id);
        }
    }

    private void clearHotspots() {
        if (mActiveHotspots != null) {
            mActiveHotspots.clear();
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
    }

    @Override
    public void draw(Canvas canvas) {
        final int N = mLayerState.mNum;
        final Rect bounds = getBounds();
        final ChildDrawable[] array = mLayerState.mChildren;
        final boolean maskOnly = mState.mMask != null && N == 1;

        int restoreToCount = drawRippleLayer(canvas, maskOnly);

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

    private int drawRippleLayer(Canvas canvas, boolean maskOnly) {
        final int count = mAnimatingRipplesCount;
        if (count == 0) {
            return -1;
        }

        final Ripple[] ripples = mAnimatingRipples;
        final boolean projected = isProjected();
        final Rect layerBounds = projected ? getDirtyBounds() : getBounds();

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
        int restoreTranslate = -1;
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

                restoreTranslate = canvas.save();
                // Translate the canvas to the current hotspot bounds.
                canvas.translate(mHotspotBounds.exactCenterX(), mHotspotBounds.exactCenterY());
            }

            drewRipples |= ripple.draw(canvas, ripplePaint);

            ripples[animatingCount] = ripples[i];
            animatingCount++;
        }

        mAnimatingRipplesCount = animatingCount;

        // Always restore the translation.
        if (restoreTranslate >= 0) {
            canvas.restoreToCount(restoreTranslate);
        }

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

        final int cX = (int) mHotspotBounds.exactCenterX();
        final int cY = (int) mHotspotBounds.exactCenterY();
        final Rect rippleBounds = mTempRect;
        final Ripple[] activeRipples = mAnimatingRipples;
        final int N = mAnimatingRipplesCount;
        for (int i = 0; i < N; i++) {
            activeRipples[i].getBounds(rippleBounds);
            rippleBounds.offset(cX, cY);
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

    static class RippleState extends LayerState {
        int[] mTouchThemeAttrs;
        ColorStateList mTint = null;
        PorterDuffXfermode mTintXfermode = SRC_ATOP;
        PorterDuffXfermode mTintXfermodeInverse = DST_ATOP;
        Drawable mMask;
        boolean mPinned = false;

        public RippleState(
                RippleState orig, RippleDrawable owner, Resources res) {
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
            final Mode invertedMode = RippleState.invertPorterDuffMode(mode);
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
            return new RippleDrawable(this, null, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new RippleDrawable(this, res, null);
        }

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new RippleDrawable(this, res, theme);
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

    private RippleDrawable(RippleState state, Resources res, Theme theme) {
        boolean needsTheme = false;

        final RippleState ns;
        if (theme != null && state != null && state.canApplyTheme()) {
            ns = new RippleState(state, this, res);
            needsTheme = true;
        } else if (state == null) {
            ns = new RippleState(null, this, res);
        } else {
            // We always need a new state since child drawables contain local
            // state but live within the parent's constant state.
            // TODO: Move child drawables into local state.
            ns = new RippleState(state, this, res);
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
