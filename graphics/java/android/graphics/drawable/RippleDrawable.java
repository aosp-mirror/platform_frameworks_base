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
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.internal.R;

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
 * <pre>
 * <code>&lt!-- A red ripple masked against an opaque rectangle. --/>
 * &ltripple android:color="#ffff0000">
 *   &ltitem android:id="@android:id/mask"
 *         android:drawable="#ffffffff" />
 * &ltripple /></code>
 * </pre>
 * <p>
 * If a mask layer is set, the ripple effect will be masked against that layer
 * before it is drawn over the composite of the remaining child layers.
 * <p>
 * If no mask layer is set, the ripple effect is masked against the composite
 * of the child layers.
 * <pre>
 * <code>&lt!-- A blue ripple drawn atop a green rectangle. --/>
 * &ltripple android:color="#ff00ff00">
 *   &ltitem android:drawable="#ff0000ff" />
 * &ltripple />
 *
 * &lt!-- A red ripple drawn atop a drawable resource. --/>
 * &ltripple android:color="#ff00ff00">
 *   &ltitem android:drawable="@drawable/my_drawable" />
 * &ltripple /></code>
 * </pre>
 * <p>
 * If no child layers or mask is specified and the ripple is set as a View
 * background, the ripple will be drawn atop the first available parent
 * background within the View's hierarchy. In this case, the drawing region
 * may extend outside of the Drawable bounds.
 * <pre>
 * <code>&lt!-- An unbounded green ripple. --/>
 * &ltripple android:color="#ff0000ff" /></code>
 * </pre>
 *
 * @attr ref android.R.styleable#RippleDrawable_color
 */
public class RippleDrawable extends LayerDrawable {
    private static final String LOG_TAG = RippleDrawable.class.getSimpleName();
    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(Mode.DST_IN);
    private static final PorterDuffXfermode SRC_ATOP = new PorterDuffXfermode(Mode.SRC_ATOP);
    private static final PorterDuffXfermode SRC_OVER = new PorterDuffXfermode(Mode.SRC_OVER);

    /**
     * Constant for automatically determining the maximum ripple radius.
     *
     * @see #setMaxRadius(int)
     * @hide
     */
    public static final int RADIUS_AUTO = -1;

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

    /** The masking layer, e.g. the layer with id R.id.mask. */
    private Drawable mMask;

    /** The current hotspot. May be actively animating or pending entry. */
    private Ripple mHotspot;

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

    /** Whether the hotspot is currently active (e.g. focused or pressed). */
    private boolean mActive;

    RippleDrawable() {
        this(null, null);
    }

    /**
     * Creates a new ripple drawable with the specified content and mask
     * drawables.
     *
     * @param content The content drawable, may be {@code null}
     * @param mask The mask drawable, may be {@code null}
     */
    public RippleDrawable(Drawable content, Drawable mask) {
        this(new RippleState(null, null, null), null, null);

        if (content != null) {
            addLayer(content, null, 0, 0, 0, 0, 0);
        }

        if (mask != null) {
            addLayer(content, null, android.R.id.mask, 0, 0, 0, 0);
        }

        ensurePadding();
    }

    @Override
    public void setAlpha(int alpha) {
        super.setAlpha(alpha);

        // TODO: Should we support this?
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        super.setColorFilter(cf);

        // TODO: Should we support this?
    }

    @Override
    public int getOpacity() {
        // Worst-case scenario.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        super.onStateChange(stateSet);

        // TODO: This would make more sense in a StateListDrawable.
        boolean active = false;
        boolean enabled = false;
        final int N = stateSet.length;
        for (int i = 0; i < N; i++) {
            if (stateSet[i] == R.attr.state_enabled) {
                enabled = true;
            }
            if (stateSet[i] == R.attr.state_focused
                    || stateSet[i] == R.attr.state_pressed) {
                active = true;
            }
        }
        setActive(active && enabled);

        // Update the paint color. Only applicable when animated in software.
        if (mRipplePaint != null && mState.mColor != null) {
            final ColorStateList stateList = mState.mColor;
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

    private void setActive(boolean active) {
        if (mActive != active) {
            mActive = active;

            if (active) {
                activateHotspot();
            } else {
                removeHotspot();
            }
        }
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);

        if (!mOverrideBounds) {
            mHotspotBounds.set(bounds);
            onHotspotBoundsChanged();
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

    public void setColor(ColorStateList color) {
        mState.mColor = color;
        invalidateSelf();
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.RippleDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        // Force padding default to STACK before inflating.
        setPaddingMode(PADDING_MODE_STACK);

        super.inflate(r, parser, attrs, theme);

        setTargetDensity(r.getDisplayMetrics());
        initializeFromState();
    }

    @Override
    public boolean setDrawableByLayerId(int id, Drawable drawable) {
        if (super.setDrawableByLayerId(id, drawable)) {
            if (id == R.id.mask) {
                mMask = drawable;
            }

            return true;
        }

        return false;
    }

    /**
     * Specifies how layer padding should affect the bounds of subsequent
     * layers. The default and recommended value for RippleDrawable is
     * {@link #PADDING_MODE_STACK}.
     *
     * @param mode padding mode, one of:
     *            <ul>
     *            <li>{@link #PADDING_MODE_NEST} to nest each layer inside the
     *            padding of the previous layer
     *            <li>{@link #PADDING_MODE_STACK} to stack each layer directly
     *            atop the previous layer
     *            </ul>
     * @see #getPaddingMode()
     */
    @Override
    public void setPaddingMode(int mode) {
        super.setPaddingMode(mode);
    }

    /**
     * Initializes the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final RippleState state = mState;

        // Extract the theme attributes, if any.
        state.mTouchThemeAttrs = a.extractThemeAttrs();

        final ColorStateList color = a.getColorStateList(R.styleable.RippleDrawable_color);
        if (color != null) {
            mState.mColor = color;
        }

        // If we're not waiting on a theme, verify required attributes.
        if (state.mTouchThemeAttrs == null && mState.mColor == null) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    ": <ripple> requires a valid color attribute");
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

        final RippleState state = mState;
        if (state == null || state.mTouchThemeAttrs == null) {
            return;
        }

        final TypedArray a = t.resolveAttributes(state.mTouchThemeAttrs,
                R.styleable.RippleDrawable);
        try {
            updateStateFromTypedArray(a);
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } finally {
            a.recycle();
        }

        initializeFromState();
    }

    @Override
    public boolean canApplyTheme() {
        return super.canApplyTheme() || mState != null && mState.mTouchThemeAttrs != null;
    }

    @Override
    public void setHotspot(float x, float y) {
        if (mHotspot == null) {
            mHotspot = new Ripple(this, mHotspotBounds, x, y);

            if (mActive) {
                activateHotspot();
            }
        } else {
            mHotspot.move(x, y);
        }
    }

    /**
     * Creates an active hotspot at the specified location.
     */
    private void activateHotspot() {
        if (mAnimatingRipplesCount >= MAX_RIPPLES) {
            // This should never happen unless the user is tapping like a maniac
            // or there is a bug that's preventing ripples from being removed.
            Log.d(LOG_TAG, "Max ripple count exceeded", new RuntimeException());
            return;
        }

        if (mHotspot == null) {
            final float x = mHotspotBounds.exactCenterX();
            final float y = mHotspotBounds.exactCenterY();
            mHotspot = new Ripple(this, mHotspotBounds, x, y);
        }

        final int color = mState.mColor.getColorForState(getState(), Color.TRANSPARENT);
        mHotspot.setup(mState.mMaxRadius, color, mDensity);
        mHotspot.enter();

        if (mAnimatingRipples == null) {
            mAnimatingRipples = new Ripple[MAX_RIPPLES];
        }
        mAnimatingRipples[mAnimatingRipplesCount++] = mHotspot;
    }

    private void removeHotspot() {
        if (mHotspot != null) {
            mHotspot.exit();
            mHotspot = null;
        }
    }

    private void clearHotspots() {
        if (mHotspot != null) {
            mHotspot.cancel();
            mHotspot = null;
        }

        final int count = mAnimatingRipplesCount;
        final Ripple[] ripples = mAnimatingRipples;
        for (int i = 0; i < count; i++) {
            // Calling cancel may remove the ripple from the animating ripple
            // array, so cache the reference before nulling it out.
            final Ripple ripple = ripples[i];
            ripples[i] = null;
            ripple.cancel();
        }

        mAnimatingRipplesCount = 0;
        invalidateSelf();
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mOverrideBounds = true;
        mHotspotBounds.set(left, top, right, bottom);

        onHotspotBoundsChanged();
    }

    /**
     * Notifies all the animating ripples that the hotspot bounds have changed.
     */
    private void onHotspotBoundsChanged() {
        final int count = mAnimatingRipplesCount;
        final Ripple[] ripples = mAnimatingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].onHotspotBoundsChanged();
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final boolean isProjected = isProjected();
        final boolean hasMask = mMask != null;
        final boolean drawNonMaskContent = mLayerState.mNum > (hasMask ? 1 : 0);
        final boolean drawMask = hasMask && mMask.getOpacity() != PixelFormat.OPAQUE;
        final Rect bounds = isProjected ? getDirtyBounds() : getBounds();

        // If we have content, draw it into a layer first.
        final int contentLayer = drawNonMaskContent ?
                drawContentLayer(canvas, bounds, SRC_OVER) : -1;

        // Next, try to draw the ripples (into a layer if necessary). If we need
        // to mask against the underlying content, set the xfermode to SRC_ATOP.
        final PorterDuffXfermode xfermode = (hasMask || !drawNonMaskContent) ? SRC_OVER : SRC_ATOP;
        final int rippleLayer = drawRippleLayer(canvas, bounds, xfermode);

        // If we have ripples and a non-opaque mask, draw the masking layer.
        if (rippleLayer >= 0 && drawMask) {
            drawMaskingLayer(canvas, bounds, DST_IN);
        }

        // Composite the layers if needed.
        if (contentLayer >= 0) {
            canvas.restoreToCount(contentLayer);
        } else if (rippleLayer >= 0) {
            canvas.restoreToCount(rippleLayer);
        }
    }

    /**
     * Removes a ripple from the animating ripple list.
     *
     * @param ripple the ripple to remove
     */
    void removeRipple(Ripple ripple) {
        // Ripple ripple ripple ripple. Ripple ripple.
        final Ripple[] ripples = mAnimatingRipples;
        final int count = mAnimatingRipplesCount;
        final int index = getRippleIndex(ripple);
        if (index >= 0) {
            for (int i = index + 1; i < count; i++) {
                ripples[i - 1] = ripples[i];
            }
            ripples[count - 1] = null;
            mAnimatingRipplesCount--;
            invalidateSelf();
        }
    }

    private int getRippleIndex(Ripple ripple) {
        final Ripple[] ripples = mAnimatingRipples;
        final int count = mAnimatingRipplesCount;
        for (int i = 0; i < count; i++) {
            if (ripples[i] == ripple) {
                return i;
            }
        }
        return -1;
    }

    private int drawContentLayer(Canvas canvas, Rect bounds, PorterDuffXfermode mode) {
        final ChildDrawable[] array = mLayerState.mChildren;
        final int count = mLayerState.mNum;

        // We don't need a layer if we don't expect to draw any ripples, we have
        // an explicit mask, or if the non-mask content is all opaque.
        boolean needsLayer = false;
        if (mAnimatingRipplesCount > 0 && mMask == null) {
            for (int i = 0; i < count; i++) {
                if (array[i].mId != R.id.mask
                        && array[i].mDrawable.getOpacity() != PixelFormat.OPAQUE) {
                    needsLayer = true;
                    break;
                }
            }
        }

        final Paint maskingPaint = getMaskingPaint(mode);
        final int restoreToCount = needsLayer ? canvas.saveLayer(bounds.left, bounds.top,
                bounds.right, bounds.bottom, maskingPaint) : -1;

        // Draw everything except the mask.
        for (int i = 0; i < count; i++) {
            if (array[i].mId != R.id.mask) {
                array[i].mDrawable.draw(canvas);
            }
        }

        return restoreToCount;
    }

    private int drawRippleLayer(Canvas canvas, Rect bounds, PorterDuffXfermode mode) {
        final int count = mAnimatingRipplesCount;
        if (count == 0) {
            return -1;
        }

        // Separate the ripple color and alpha channel. The alpha will be
        // applied when we merge the ripples down to the canvas.
        final int rippleARGB;
        if (mState.mColor != null) {
            rippleARGB = mState.mColor.getColorForState(getState(), Color.TRANSPARENT);
        } else {
            rippleARGB = Color.TRANSPARENT;
        }

        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
        }

        final int rippleAlpha = Color.alpha(rippleARGB);
        final Paint ripplePaint = mRipplePaint;
        ripplePaint.setColor(rippleARGB);
        ripplePaint.setAlpha(0xFF);

        boolean drewRipples = false;
        int restoreToCount = -1;
        int restoreTranslate = -1;

        // Draw ripples and update the animating ripples array.
        final Ripple[] ripples = mAnimatingRipples;
        for (int i = 0; i < count; i++) {
            final Ripple ripple = ripples[i];

            // If we're masking the ripple layer, make sure we have a layer
            // first. This will merge SRC_OVER (directly) onto the canvas.
            if (restoreToCount < 0) {
                final Paint maskingPaint = getMaskingPaint(mode);
                maskingPaint.setAlpha(rippleAlpha);
                restoreToCount = canvas.saveLayer(bounds.left, bounds.top,
                        bounds.right, bounds.bottom, maskingPaint);

                restoreTranslate = canvas.save();
                // Translate the canvas to the current hotspot bounds.
                canvas.translate(mHotspotBounds.exactCenterX(), mHotspotBounds.exactCenterY());
            }

            drewRipples |= ripple.draw(canvas, ripplePaint);
        }

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

    private int drawMaskingLayer(Canvas canvas, Rect bounds, PorterDuffXfermode mode) {
        final int restoreToCount = canvas.saveLayer(bounds.left, bounds.top,
                bounds.right, bounds.bottom, getMaskingPaint(mode));

        // Ensure that DST_IN blends using the entire layer.
        canvas.drawColor(Color.TRANSPARENT);

        mMask.draw(canvas);

        return restoreToCount;
    }

    private Paint getMaskingPaint(PorterDuffXfermode xfermode) {
        if (mMaskingPaint == null) {
            mMaskingPaint = new Paint();
        }
        mMaskingPaint.setXfermode(xfermode);
        mMaskingPaint.setAlpha(0xFF);
        return mMaskingPaint;
    }

    @Override
    public Rect getDirtyBounds() {
        if (isProjected()) {
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
        } else {
            return getBounds();
        }
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    static class RippleState extends LayerState {
        int[] mTouchThemeAttrs;
        ColorStateList mColor = null;
        int mMaxRadius = RADIUS_AUTO;

        public RippleState(RippleState orig, RippleDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null) {
                mTouchThemeAttrs = orig.mTouchThemeAttrs;
                mColor = orig.mColor;
                mMaxRadius = orig.mMaxRadius;
            }
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
    }

    /**
     * Sets the maximum ripple radius in pixels. The default value of
     * {@link #RADIUS_AUTO} defines the radius as the distance from the center
     * of the drawable bounds (or hotspot bounds, if specified) to a corner.
     *
     * @param maxRadius the maximum ripple radius in pixels or
     *            {@link #RADIUS_AUTO} to automatically determine the maximum
     *            radius based on the bounds
     * @see #getMaxRadius()
     * @see #setHotspotBounds(int, int, int, int)
     * @hide
     */
    public void setMaxRadius(int maxRadius) {
        if (maxRadius != RADIUS_AUTO && maxRadius < 0) {
            throw new IllegalArgumentException("maxRadius must be RADIUS_AUTO or >= 0");
        }

        mState.mMaxRadius = maxRadius;
    }

    /**
     * @return the maximum ripple radius in pixels, or {@link #RADIUS_AUTO} if
     *         the radius is determined automatically
     * @see #setMaxRadius(int)
     * @hide
     */
    public int getMaxRadius() {
        return mState.mMaxRadius;
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

        initializeFromState();
    }

    private void initializeFromState() {
        // Initialize from constant state.
        mMask = findDrawableByLayerId(R.id.mask);
    }
}
