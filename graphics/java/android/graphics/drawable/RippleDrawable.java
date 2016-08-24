/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;

import java.io.IOException;
import java.util.Arrays;

/**
 * Drawable that shows a ripple effect in response to state changes. The
 * anchoring position of the ripple for a given state may be specified by
 * calling {@link #setHotspot(float, float)} with the corresponding state
 * attribute identifier.
 * <p>
 * A touch feedback drawable may contain multiple child layers, including a
 * special mask layer that is not drawn to the screen. A single layer may be
 * set as the mask from XML by specifying its {@code android:id} value as
 * {@link android.R.id#mask}. At run time, a single layer may be set as the
 * mask using {@code setId(..., android.R.id.mask)} or an existing mask layer
 * may be replaced using {@code setDrawableByLayerId(android.R.id.mask, ...)}.
 * <pre>
 * <code>&lt!-- A red ripple masked against an opaque rectangle. --/>
 * &ltripple android:color="#ffff0000">
 *   &ltitem android:id="@android:id/mask"
 *         android:drawable="@android:color/white" />
 * &lt/ripple></code>
 * </pre>
 * <p>
 * If a mask layer is set, the ripple effect will be masked against that layer
 * before it is drawn over the composite of the remaining child layers.
 * <p>
 * If no mask layer is set, the ripple effect is masked against the composite
 * of the child layers.
 * <pre>
 * <code>&lt!-- A green ripple drawn atop a black rectangle. --/>
 * &ltripple android:color="#ff00ff00">
 *   &ltitem android:drawable="@android:color/black" />
 * &lt/ripple>
 *
 * &lt!-- A blue ripple drawn atop a drawable resource. --/>
 * &ltripple android:color="#ff0000ff">
 *   &ltitem android:drawable="@drawable/my_drawable" />
 * &lt/ripple></code>
 * </pre>
 * <p>
 * If no child layers or mask is specified and the ripple is set as a View
 * background, the ripple will be drawn atop the first available parent
 * background within the View's hierarchy. In this case, the drawing region
 * may extend outside of the Drawable bounds.
 * <pre>
 * <code>&lt!-- An unbounded red ripple. --/>
 * &ltripple android:color="#ffff0000" /></code>
 * </pre>
 *
 * @attr ref android.R.styleable#RippleDrawable_color
 */
public class RippleDrawable extends LayerDrawable {
    /**
     * Radius value that specifies the ripple radius should be computed based
     * on the size of the ripple's container.
     */
    public static final int RADIUS_AUTO = -1;

    private static final int MASK_UNKNOWN = -1;
    private static final int MASK_NONE = 0;
    private static final int MASK_CONTENT = 1;
    private static final int MASK_EXPLICIT = 2;

    /** The maximum number of ripples supported. */
    private static final int MAX_RIPPLES = 10;

    private final Rect mTempRect = new Rect();

    /** Current ripple effect bounds, used to constrain ripple effects. */
    private final Rect mHotspotBounds = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    /** Mirrors mLayerState with some extra information. */
    private RippleState mState;

    /** The masking layer, e.g. the layer with id R.id.mask. */
    private Drawable mMask;

    /** The current background. May be actively animating or pending entry. */
    private RippleBackground mBackground;

    private Bitmap mMaskBuffer;
    private BitmapShader mMaskShader;
    private Canvas mMaskCanvas;
    private Matrix mMaskMatrix;
    private PorterDuffColorFilter mMaskColorFilter;
    private boolean mHasValidMask;

    /** Whether we expect to draw a background when visible. */
    private boolean mBackgroundActive;

    /** The current ripple. May be actively animating or pending entry. */
    private RippleForeground mRipple;

    /** Whether we expect to draw a ripple when visible. */
    private boolean mRippleActive;

    // Hotspot coordinates that are awaiting activation.
    private float mPendingX;
    private float mPendingY;
    private boolean mHasPending;

    /**
     * Lazily-created array of actively animating ripples. Inactive ripples are
     * pruned during draw(). The locations of these will not change.
     */
    private RippleForeground[] mExitingRipples;
    private int mExitingRipplesCount = 0;

    /** Paint used to control appearance of ripples. */
    private Paint mRipplePaint;

    /** Target density of the display into which ripples are drawn. */
    private int mDensity;

    /** Whether bounds are being overridden. */
    private boolean mOverrideBounds;

    /**
     * If set, force all ripple animations to not run on RenderThread, even if it would be
     * available.
     */
    private boolean mForceSoftware;

    /**
     * Constructor used for drawable inflation.
     */
    RippleDrawable() {
        this(new RippleState(null, null, null), null);
    }

    /**
     * Creates a new ripple drawable with the specified ripple color and
     * optional content and mask drawables.
     *
     * @param color The ripple color
     * @param content The content drawable, may be {@code null}
     * @param mask The mask drawable, may be {@code null}
     */
    public RippleDrawable(@NonNull ColorStateList color, @Nullable Drawable content,
            @Nullable Drawable mask) {
        this(new RippleState(null, null, null), null);

        if (color == null) {
            throw new IllegalArgumentException("RippleDrawable requires a non-null color");
        }

        if (content != null) {
            addLayer(content, null, 0, 0, 0, 0, 0);
        }

        if (mask != null) {
            addLayer(mask, null, android.R.id.mask, 0, 0, 0, 0);
        }

        setColor(color);
        ensurePadding();
        refreshPadding();
        updateLocalState();
    }

    @Override
    public void jumpToCurrentState() {
        super.jumpToCurrentState();

        if (mRipple != null) {
            mRipple.end();
        }

        if (mBackground != null) {
            mBackground.end();
        }

        cancelExitingRipples();
    }

    private void cancelExitingRipples() {
        final int count = mExitingRipplesCount;
        final RippleForeground[] ripples = mExitingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].end();
        }

        if (ripples != null) {
            Arrays.fill(ripples, 0, count, null);
        }
        mExitingRipplesCount = 0;

        // Always draw an additional "clean" frame after canceling animations.
        invalidateSelf(false);
    }

    @Override
    public int getOpacity() {
        // Worst-case scenario.
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final boolean changed = super.onStateChange(stateSet);

        boolean enabled = false;
        boolean pressed = false;
        boolean focused = false;
        boolean hovered = false;

        for (int state : stateSet) {
            if (state == R.attr.state_enabled) {
                enabled = true;
            } else if (state == R.attr.state_focused) {
                focused = true;
            } else if (state == R.attr.state_pressed) {
                pressed = true;
            } else if (state == R.attr.state_hovered) {
                hovered = true;
            }
        }

        setRippleActive(enabled && pressed);
        setBackgroundActive(hovered || focused || (enabled && pressed), focused || hovered);

        return changed;
    }

    private void setRippleActive(boolean active) {
        if (mRippleActive != active) {
            mRippleActive = active;
            if (active) {
                tryRippleEnter();
            } else {
                tryRippleExit();
            }
        }
    }

    private void setBackgroundActive(boolean active, boolean focused) {
        if (mBackgroundActive != active) {
            mBackgroundActive = active;
            if (active) {
                tryBackgroundEnter(focused);
            } else {
                tryBackgroundExit();
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

        if (mBackground != null) {
            mBackground.onBoundsChange();
        }

        if (mRipple != null) {
            mRipple.onBoundsChange();
        }

        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean changed = super.setVisible(visible, restart);

        if (!visible) {
            clearHotspots();
        } else if (changed) {
            // If we just became visible, ensure the background and ripple
            // visibilities are consistent with their internal states.
            if (mRippleActive) {
                tryRippleEnter();
            }

            if (mBackgroundActive) {
                tryBackgroundEnter(false);
            }

            // Skip animations, just show the correct final states.
            jumpToCurrentState();
        }

        return changed;
    }

    /**
     * @hide
     */
    @Override
    public boolean isProjected() {
        // If the layer is bounded, then we don't need to project.
        if (isBounded()) {
            return false;
        }

        // Otherwise, if the maximum radius is contained entirely within the
        // bounds then we don't need to project. This is sort of a hack to
        // prevent check box ripples from being projected across the edges of
        // scroll views. It does not impact rendering performance, and it can
        // be removed once we have better handling of projection in scrollable
        // views.
        final int radius = mState.mMaxRadius;
        final Rect drawableBounds = getBounds();
        final Rect hotspotBounds = mHotspotBounds;
        if (radius != RADIUS_AUTO
                && radius <= hotspotBounds.width() / 2
                && radius <= hotspotBounds.height() / 2
                && (drawableBounds.equals(hotspotBounds)
                        || drawableBounds.contains(hotspotBounds))) {
            return false;
        }

        return true;
    }

    private boolean isBounded() {
        return getNumberOfLayers() > 0;
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    /**
     * Sets the ripple color.
     *
     * @param color Ripple color as a color state list.
     *
     * @attr ref android.R.styleable#RippleDrawable_color
     */
    public void setColor(ColorStateList color) {
        mState.mColor = color;
        invalidateSelf(false);
    }

    /**
     * Sets the radius in pixels of the fully expanded ripple.
     *
     * @param radius ripple radius in pixels, or {@link #RADIUS_AUTO} to
     *               compute the radius based on the container size
     * @attr ref android.R.styleable#RippleDrawable_radius
     */
    public void setRadius(int radius) {
        mState.mMaxRadius = radius;
        invalidateSelf(false);
    }

    /**
     * @return the radius in pixels of the fully expanded ripple if an explicit
     *         radius has been set, or {@link #RADIUS_AUTO} if the radius is
     *         computed based on the container size
     * @attr ref android.R.styleable#RippleDrawable_radius
     */
    public int getRadius() {
        return mState.mMaxRadius;
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.RippleDrawable);

        // Force padding default to STACK before inflating.
        setPaddingMode(PADDING_MODE_STACK);

        // Inflation will advance the XmlPullParser and AttributeSet.
        super.inflate(r, parser, attrs, theme);

        updateStateFromTypedArray(a);
        verifyRequiredAttributes(a);
        a.recycle();

        updateLocalState();
    }

    @Override
    public boolean setDrawableByLayerId(int id, Drawable drawable) {
        if (super.setDrawableByLayerId(id, drawable)) {
            if (id == R.id.mask) {
                mMask = drawable;
                mHasValidMask = false;
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
    private void updateStateFromTypedArray(@NonNull TypedArray a) throws XmlPullParserException {
        final RippleState state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mTouchThemeAttrs = a.extractThemeAttrs();

        final ColorStateList color = a.getColorStateList(R.styleable.RippleDrawable_color);
        if (color != null) {
            mState.mColor = color;
        }

        mState.mMaxRadius = a.getDimensionPixelSize(
                R.styleable.RippleDrawable_radius, mState.mMaxRadius);
    }

    private void verifyRequiredAttributes(@NonNull TypedArray a) throws XmlPullParserException {
        if (mState.mColor == null && (mState.mTouchThemeAttrs == null
                || mState.mTouchThemeAttrs[R.styleable.RippleDrawable_color] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription() +
                    ": <ripple> requires a valid color attribute");
        }
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final RippleState state = mState;
        if (state == null) {
            return;
        }

        if (state.mTouchThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mTouchThemeAttrs,
                    R.styleable.RippleDrawable);
            try {
                updateStateFromTypedArray(a);
                verifyRequiredAttributes(a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        if (state.mColor != null && state.mColor.canApplyTheme()) {
            state.mColor = state.mColor.obtainForTheme(t);
        }

        updateLocalState();
    }

    @Override
    public boolean canApplyTheme() {
        return (mState != null && mState.canApplyTheme()) || super.canApplyTheme();
    }

    @Override
    public void setHotspot(float x, float y) {
        if (mRipple == null || mBackground == null) {
            mPendingX = x;
            mPendingY = y;
            mHasPending = true;
        }

        if (mRipple != null) {
            mRipple.move(x, y);
        }
    }

    /**
     * Creates an active hotspot at the specified location.
     */
    private void tryBackgroundEnter(boolean focused) {
        if (mBackground == null) {
            final boolean isBounded = isBounded();
            mBackground = new RippleBackground(this, mHotspotBounds, isBounded, mForceSoftware);
        }

        mBackground.setup(mState.mMaxRadius, mDensity);
        mBackground.enter(focused);
    }

    private void tryBackgroundExit() {
        if (mBackground != null) {
            // Don't null out the background, we need it to draw!
            mBackground.exit();
        }
    }

    /**
     * Attempts to start an enter animation for the active hotspot. Fails if
     * there are too many animating ripples.
     */
    private void tryRippleEnter() {
        if (mExitingRipplesCount >= MAX_RIPPLES) {
            // This should never happen unless the user is tapping like a maniac
            // or there is a bug that's preventing ripples from being removed.
            return;
        }

        if (mRipple == null) {
            final float x;
            final float y;
            if (mHasPending) {
                mHasPending = false;
                x = mPendingX;
                y = mPendingY;
            } else {
                x = mHotspotBounds.exactCenterX();
                y = mHotspotBounds.exactCenterY();
            }

            final boolean isBounded = isBounded();
            mRipple = new RippleForeground(this, mHotspotBounds, x, y, isBounded, mForceSoftware);
        }

        mRipple.setup(mState.mMaxRadius, mDensity);
        mRipple.enter(false);
    }

    /**
     * Attempts to start an exit animation for the active hotspot. Fails if
     * there is no active hotspot.
     */
    private void tryRippleExit() {
        if (mRipple != null) {
            if (mExitingRipples == null) {
                mExitingRipples = new RippleForeground[MAX_RIPPLES];
            }
            mExitingRipples[mExitingRipplesCount++] = mRipple;
            mRipple.exit();
            mRipple = null;
        }
    }

    /**
     * Cancels and removes the active ripple, all exiting ripples, and the
     * background. Nothing will be drawn after this method is called.
     */
    private void clearHotspots() {
        if (mRipple != null) {
            mRipple.end();
            mRipple = null;
            mRippleActive = false;
        }

        if (mBackground != null) {
            mBackground.end();
            mBackground = null;
            mBackgroundActive = false;
        }

        cancelExitingRipples();
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mOverrideBounds = true;
        mHotspotBounds.set(left, top, right, bottom);

        onHotspotBoundsChanged();
    }

    @Override
    public void getHotspotBounds(Rect outRect) {
        outRect.set(mHotspotBounds);
    }

    /**
     * Notifies all the animating ripples that the hotspot bounds have changed.
     */
    private void onHotspotBoundsChanged() {
        final int count = mExitingRipplesCount;
        final RippleForeground[] ripples = mExitingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].onHotspotBoundsChanged();
        }

        if (mRipple != null) {
            mRipple.onHotspotBoundsChanged();
        }

        if (mBackground != null) {
            mBackground.onHotspotBoundsChanged();
        }
    }

    /**
     * Populates <code>outline</code> with the first available layer outline,
     * excluding the mask layer.
     *
     * @param outline Outline in which to place the first available layer outline
     */
    @Override
    public void getOutline(@NonNull Outline outline) {
        final LayerState state = mLayerState;
        final ChildDrawable[] children = state.mChildren;
        final int N = state.mNum;
        for (int i = 0; i < N; i++) {
            if (children[i].mId != R.id.mask) {
                children[i].mDrawable.getOutline(outline);
                if (!outline.isEmpty()) return;
            }
        }
    }

    /**
     * Optimized for drawing ripples with a mask layer and optional content.
     */
    @Override
    public void draw(@NonNull Canvas canvas) {
        pruneRipples();

        // Clip to the dirty bounds, which will be the drawable bounds if we
        // have a mask or content and the ripple bounds if we're projecting.
        final Rect bounds = getDirtyBounds();
        final int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        canvas.clipRect(bounds);

        drawContent(canvas);
        drawBackgroundAndRipples(canvas);

        canvas.restoreToCount(saveCount);
    }

    @Override
    public void invalidateSelf() {
        invalidateSelf(true);
    }

    void invalidateSelf(boolean invalidateMask) {
        super.invalidateSelf();

        if (invalidateMask) {
            // Force the mask to update on the next draw().
            mHasValidMask = false;
        }

    }

    private void pruneRipples() {
        int remaining = 0;

        // Move remaining entries into pruned spaces.
        final RippleForeground[] ripples = mExitingRipples;
        final int count = mExitingRipplesCount;
        for (int i = 0; i < count; i++) {
            if (!ripples[i].hasFinishedExit()) {
                ripples[remaining++] = ripples[i];
            }
        }

        // Null out the remaining entries.
        for (int i = remaining; i < count; i++) {
            ripples[i] = null;
        }

        mExitingRipplesCount = remaining;
    }

    /**
     * @return whether we need to use a mask
     */
    private void updateMaskShaderIfNeeded() {
        if (mHasValidMask) {
            return;
        }

        final int maskType = getMaskType();
        if (maskType == MASK_UNKNOWN) {
            return;
        }

        mHasValidMask = true;

        final Rect bounds = getBounds();
        if (maskType == MASK_NONE || bounds.isEmpty()) {
            if (mMaskBuffer != null) {
                mMaskBuffer.recycle();
                mMaskBuffer = null;
                mMaskShader = null;
                mMaskCanvas = null;
            }
            mMaskMatrix = null;
            mMaskColorFilter = null;
            return;
        }

        // Ensure we have a correctly-sized buffer.
        if (mMaskBuffer == null
                || mMaskBuffer.getWidth() != bounds.width()
                || mMaskBuffer.getHeight() != bounds.height()) {
            if (mMaskBuffer != null) {
                mMaskBuffer.recycle();
            }

            mMaskBuffer = Bitmap.createBitmap(
                    bounds.width(), bounds.height(), Bitmap.Config.ALPHA_8);
            mMaskShader = new BitmapShader(mMaskBuffer,
                    Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            mMaskCanvas = new Canvas(mMaskBuffer);
        } else {
            mMaskBuffer.eraseColor(Color.TRANSPARENT);
        }

        if (mMaskMatrix == null) {
            mMaskMatrix = new Matrix();
        } else {
            mMaskMatrix.reset();
        }

        if (mMaskColorFilter == null) {
            mMaskColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_IN);
        }

        // Draw the appropriate mask anchored to (0,0).
        final int left = bounds.left;
        final int top = bounds.top;
        mMaskCanvas.translate(-left, -top);
        if (maskType == MASK_EXPLICIT) {
            drawMask(mMaskCanvas);
        } else if (maskType == MASK_CONTENT) {
            drawContent(mMaskCanvas);
        }
        mMaskCanvas.translate(left, top);
    }

    private int getMaskType() {
        if (mRipple == null && mExitingRipplesCount <= 0
                && (mBackground == null || !mBackground.isVisible())) {
            // We might need a mask later.
            return MASK_UNKNOWN;
        }

        if (mMask != null) {
            if (mMask.getOpacity() == PixelFormat.OPAQUE) {
                // Clipping handles opaque explicit masks.
                return MASK_NONE;
            } else {
                return MASK_EXPLICIT;
            }
        }

        // Check for non-opaque, non-mask content.
        final ChildDrawable[] array = mLayerState.mChildren;
        final int count = mLayerState.mNum;
        for (int i = 0; i < count; i++) {
            if (array[i].mDrawable.getOpacity() != PixelFormat.OPAQUE) {
                return MASK_CONTENT;
            }
        }

        // Clipping handles opaque content.
        return MASK_NONE;
    }

    private void drawContent(Canvas canvas) {
        // Draw everything except the mask.
        final ChildDrawable[] array = mLayerState.mChildren;
        final int count = mLayerState.mNum;
        for (int i = 0; i < count; i++) {
            if (array[i].mId != R.id.mask) {
                array[i].mDrawable.draw(canvas);
            }
        }
    }

    private void drawBackgroundAndRipples(Canvas canvas) {
        final RippleForeground active = mRipple;
        final RippleBackground background = mBackground;
        final int count = mExitingRipplesCount;
        if (active == null && count <= 0 && (background == null || !background.isVisible())) {
            // Move along, nothing to draw here.
            return;
        }

        final float x = mHotspotBounds.exactCenterX();
        final float y = mHotspotBounds.exactCenterY();
        canvas.translate(x, y);

        updateMaskShaderIfNeeded();

        // Position the shader to account for canvas translation.
        if (mMaskShader != null) {
            final Rect bounds = getBounds();
            mMaskMatrix.setTranslate(bounds.left - x, bounds.top - y);
            mMaskShader.setLocalMatrix(mMaskMatrix);
        }

        // Grab the color for the current state and cut the alpha channel in
        // half so that the ripple and background together yield full alpha.
        final int color = mState.mColor.getColorForState(getState(), Color.BLACK);
        final int halfAlpha = (Color.alpha(color) / 2) << 24;
        final Paint p = getRipplePaint();

        if (mMaskColorFilter != null) {
            // The ripple timing depends on the paint's alpha value, so we need
            // to push just the alpha channel into the paint and let the filter
            // handle the full-alpha color.
            final int fullAlphaColor = color | (0xFF << 24);
            mMaskColorFilter.setColor(fullAlphaColor);

            p.setColor(halfAlpha);
            p.setColorFilter(mMaskColorFilter);
            p.setShader(mMaskShader);
        } else {
            final int halfAlphaColor = (color & 0xFFFFFF) | halfAlpha;
            p.setColor(halfAlphaColor);
            p.setColorFilter(null);
            p.setShader(null);
        }

        if (background != null && background.isVisible()) {
            background.draw(canvas, p);
        }

        if (count > 0) {
            final RippleForeground[] ripples = mExitingRipples;
            for (int i = 0; i < count; i++) {
                ripples[i].draw(canvas, p);
            }
        }

        if (active != null) {
            active.draw(canvas, p);
        }

        canvas.translate(-x, -y);
    }

    private void drawMask(Canvas canvas) {
        mMask.draw(canvas);
    }

    private Paint getRipplePaint() {
        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
            mRipplePaint.setStyle(Paint.Style.FILL);
        }
        return mRipplePaint;
    }

    @Override
    public Rect getDirtyBounds() {
        if (!isBounded()) {
            final Rect drawingBounds = mDrawingBounds;
            final Rect dirtyBounds = mDirtyBounds;
            dirtyBounds.set(drawingBounds);
            drawingBounds.setEmpty();

            final int cX = (int) mHotspotBounds.exactCenterX();
            final int cY = (int) mHotspotBounds.exactCenterY();
            final Rect rippleBounds = mTempRect;

            final RippleForeground[] activeRipples = mExitingRipples;
            final int N = mExitingRipplesCount;
            for (int i = 0; i < N; i++) {
                activeRipples[i].getBounds(rippleBounds);
                rippleBounds.offset(cX, cY);
                drawingBounds.union(rippleBounds);
            }

            final RippleBackground background = mBackground;
            if (background != null) {
                background.getBounds(rippleBounds);
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

    /**
     * Sets whether to disable RenderThread animations for this ripple.
     *
     * @param forceSoftware true if RenderThread animations should be disabled, false otherwise
     * @hide
     */
    public void setForceSoftware(boolean forceSoftware) {
        mForceSoftware = forceSoftware;
    }

    @Override
    public ConstantState getConstantState() {
        return mState;
    }

    @Override
    public Drawable mutate() {
        super.mutate();

        // LayerDrawable creates a new state using createConstantState, so
        // this should always be a safe cast.
        mState = (RippleState) mLayerState;

        // The locally cached drawable may have changed.
        mMask = findDrawableByLayerId(R.id.mask);

        return this;
    }

    @Override
    RippleState createConstantState(LayerState state, Resources res) {
        return new RippleState(state, this, res);
    }

    static class RippleState extends LayerState {
        int[] mTouchThemeAttrs;
        ColorStateList mColor = ColorStateList.valueOf(Color.MAGENTA);
        int mMaxRadius = RADIUS_AUTO;

        public RippleState(LayerState orig, RippleDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null && orig instanceof RippleState) {
                final RippleState origs = (RippleState) orig;
                mTouchThemeAttrs = origs.mTouchThemeAttrs;
                mColor = origs.mColor;
                mMaxRadius = origs.mMaxRadius;

                if (origs.mDensity != mDensity) {
                    applyDensityScaling(orig.mDensity, mDensity);
                }
            }
        }

        @Override
        protected void onDensityChanged(int sourceDensity, int targetDensity) {
            super.onDensityChanged(sourceDensity, targetDensity);

            applyDensityScaling(sourceDensity, targetDensity);
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            if (mMaxRadius != RADIUS_AUTO) {
                mMaxRadius = Drawable.scaleFromDensity(
                        mMaxRadius, sourceDensity, targetDensity, true);
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mTouchThemeAttrs != null
                    || (mColor != null && mColor.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new RippleDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new RippleDrawable(this, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return super.getChangingConfigurations()
                    | (mColor != null ? mColor.getChangingConfigurations() : 0);
        }
    }

    private RippleDrawable(RippleState state, Resources res) {
        mState = new RippleState(state, this, res);
        mLayerState = mState;
        mDensity = Drawable.resolveDensity(res, mState.mDensity);

        if (mState.mNum > 0) {
            ensurePadding();
            refreshPadding();
        }

        updateLocalState();
    }

    private void updateLocalState() {
        // Initialize from constant state.
        mMask = findDrawableByLayerId(R.id.mask);
    }
}
