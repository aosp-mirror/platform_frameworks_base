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

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.animation.ValueAnimator;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.CanvasProperty;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
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
 * <code>&lt;!-- A red ripple masked against an opaque rectangle. -->
 * &lt;ripple android:color="#ffff0000">
 *   &lt;item android:id="@android:id/mask"
 *         android:drawable="@android:color/white" />
 * &lt;/ripple></code>
 * </pre>
 * <p>
 * If a mask layer is set, the ripple effect will be masked against that layer
 * before it is drawn over the composite of the remaining child layers.
 * <p>
 * If no mask layer is set, the ripple effect is masked against the composite
 * of the child layers.
 * <pre>
 * <code>&lt;!-- A green ripple drawn atop a black rectangle. -->
 * &lt;ripple android:color="#ff00ff00">
 *   &lt;item android:drawable="@android:color/black" />
 * &lt;/ripple>
 *
 * &lt;!-- A blue ripple drawn atop a drawable resource. -->
 * &lt;ripple android:color="#ff0000ff">
 *   &lt;item android:drawable="@drawable/my_drawable" />
 * &lt;/ripple></code>
 * </pre>
 * <p>
 * If no child layers or mask is specified and the ripple is set as a View
 * background, the ripple will be drawn atop the first available parent
 * background within the View's hierarchy. In this case, the drawing region
 * may extend outside of the Drawable bounds.
 * <pre>
 * <code>&lt;!-- An unbounded red ripple. -->
 * &lt;ripple android:color="#ffff0000" /></code>
 * </pre>
 *
 * @attr ref android.R.styleable#RippleDrawable_color
 */
public class RippleDrawable extends LayerDrawable {
    private static final String TAG = "RippleDrawable";
    /**
     * Radius value that specifies the ripple radius should be computed based
     * on the size of the ripple's container.
     */
    public static final int RADIUS_AUTO = -1;

    /**
     * Ripple style where a solid circle is drawn. This is also the default style
     * @see #setRippleStyle(int)
     * @hide
     */
    public static final int STYLE_SOLID = 0;
    /**
     * Ripple style where a circle shape with a patterned,
     * noisy interior expands from the hotspot to the bounds".
     * @see #setRippleStyle(int)
     * @hide
     */
    public static final int STYLE_PATTERNED = 1;

    /**
     * Ripple drawing style
     * @hide
     */
    @Retention(SOURCE)
    @Target({PARAMETER, METHOD, LOCAL_VARIABLE, FIELD})
    @IntDef({STYLE_SOLID, STYLE_PATTERNED})
    public @interface RippleStyle {
    }

    private static final int BACKGROUND_OPACITY_DURATION = 80;
    private static final int MASK_UNKNOWN = -1;
    private static final int MASK_NONE = 0;
    private static final int MASK_CONTENT = 1;
    private static final int MASK_EXPLICIT = 2;

    /** The maximum number of ripples supported. */
    private static final int MAX_RIPPLES = 10;
    private static final LinearInterpolator LINEAR_INTERPOLATOR = new LinearInterpolator();
    private static final int DEFAULT_EFFECT_COLOR = 0x8dffffff;
    /** Temporary flag for teamfood. **/
    private static final boolean FORCE_PATTERNED_STYLE = true;

    private final Rect mTempRect = new Rect();

    /** Current ripple effect bounds, used to constrain ripple effects. */
    private final Rect mHotspotBounds = new Rect();

    /** Current drawing bounds, used to compute dirty region. */
    private final Rect mDrawingBounds = new Rect();

    /** Current dirty bounds, union of current and previous drawing bounds. */
    private final Rect mDirtyBounds = new Rect();

    /** Mirrors mLayerState with some extra information. */
    @UnsupportedAppUsage(trackingBug = 175939224)
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
    private PorterDuffColorFilter mFocusColorFilter;
    private boolean mHasValidMask;

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
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private int mDensity;

    /** Whether bounds are being overridden. */
    private boolean mOverrideBounds;

    /**
     * If set, force all ripple animations to not run on RenderThread, even if it would be
     * available.
     */
    private boolean mForceSoftware;

    // Patterned
    private boolean mAddRipple = false;
    private float mTargetBackgroundOpacity;
    private ValueAnimator mBackgroundAnimation;
    private float mBackgroundOpacity;
    private boolean mRunBackgroundAnimation;
    private boolean mExitingAnimation;
    private ArrayList<RippleAnimationSession> mRunningAnimations = new ArrayList<>();

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
            mBackground.jumpToFinal();
        }

        cancelExitingRipples();
        endPatternedAnimations();
    }

    private void endPatternedAnimations() {
        for (int i = 0; i < mRunningAnimations.size(); i++) {
            RippleAnimationSession session = mRunningAnimations.get(i);
            session.end();
        }
        mRunningAnimations.clear();
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
        boolean windowFocused = false;

        for (int state : stateSet) {
            if (state == R.attr.state_enabled) {
                enabled = true;
            } else if (state == R.attr.state_focused) {
                focused = true;
            } else if (state == R.attr.state_pressed) {
                pressed = true;
            } else if (state == R.attr.state_hovered) {
                hovered = true;
            } else if (state == R.attr.state_window_focused) {
                windowFocused = true;
            }
        }
        setRippleActive(enabled && pressed);
        setBackgroundActive(hovered, focused, pressed, windowFocused);

        return changed;
    }

    private void setRippleActive(boolean active) {
        if (mRippleActive != active) {
            mRippleActive = active;
            if (mState.mRippleStyle == STYLE_SOLID) {
                if (active) {
                    tryRippleEnter();
                } else {
                    tryRippleExit();
                }
            } else {
                if (active) {
                    startPatternedAnimation();
                } else {
                    exitPatternedAnimation();
                }
            }
        }
    }

    private void setBackgroundActive(boolean hovered, boolean focused, boolean pressed,
            boolean windowFocused) {
        if (mState.mRippleStyle == STYLE_SOLID) {
            if (mBackground == null && (hovered || focused)) {
                mBackground = new RippleBackground(this, mHotspotBounds, isBounded());
                mBackground.setup(mState.mMaxRadius, mDensity);
            }
            if (mBackground != null) {
                mBackground.setState(focused, hovered, pressed);
            }
        } else {
            if (focused || hovered) {
                if (!pressed) {
                    enterPatternedBackgroundAnimation(focused, hovered, windowFocused);
                }
            } else {
                exitPatternedBackgroundAnimation();
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

        final int count = mExitingRipplesCount;
        final RippleForeground[] ripples = mExitingRipples;
        for (int i = 0; i < count; i++) {
            ripples[i].onBoundsChange();
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
                if (mState.mRippleStyle == STYLE_SOLID) {
                    tryRippleEnter();
                } else {
                    invalidateSelf();
                }
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

    @Override
    public boolean hasFocusStateSpecified() {
        return true;
    }

    /**
     * Sets the ripple color.
     *
     * @param color Ripple color as a color state list.
     *
     * @attr ref android.R.styleable#RippleDrawable_color
     */
    public void setColor(@NonNull ColorStateList color) {
        if (color == null) {
            throw new IllegalArgumentException("color cannot be null");
        }
        mState.mColor = color;
        invalidateSelf(false);
    }

    /**
     * Sets the ripple effect color.
     *
     * @param color Ripple color as a color state list.
     *
     * @attr ref android.R.styleable#RippleDrawable_effectColor
     */
    public void setEffectColor(@NonNull ColorStateList color) {
        if (color == null) {
            throw new IllegalArgumentException("color cannot be null");
        }
        mState.mEffectColor = color;
        invalidateSelf(false);
    }

    /**
     * @return The ripple effect color as a color state list.
     */
    public @NonNull ColorStateList getEffectColor() {
        return mState.mEffectColor;
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

        final ColorStateList effectColor =
                a.getColorStateList(R.styleable.RippleDrawable_effectColor);
        if (effectColor != null) {
            mState.mEffectColor = effectColor;
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
        mPendingX = x;
        mPendingY = y;
        if (mRipple == null || mBackground == null) {
            mHasPending = true;
        }

        if (mRipple != null) {
            mRipple.move(x, y);
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

            mRipple = new RippleForeground(this, mHotspotBounds, x, y, mForceSoftware);
        }

        mRipple.setup(mState.mMaxRadius, mDensity);
        mRipple.enter();
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
            mBackground.setState(false, false, false);
        }

        cancelExitingRipples();
        endPatternedAnimations();
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
     * Notifies all the animating ripples that the hotspot bounds have changed and modify sessions.
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
        float newRadius = Math.round(getComputedRadius());
        for (int i = 0; i < mRunningAnimations.size(); i++) {
            RippleAnimationSession s = mRunningAnimations.get(i);
            s.setRadius(newRadius);
            s.getProperties().getShader()
                    .setResolution(mHotspotBounds.width(), mHotspotBounds.height());
            float cx = mHotspotBounds.centerX(), cy = mHotspotBounds.centerY();
            s.getProperties().getShader().setOrigin(cx, cy);
            s.getProperties().setOrigin(cx, cy);
            if (!s.isForceSoftware()) {
                s.getCanvasProperties()
                        .setOrigin(CanvasProperty.createFloat(cx), CanvasProperty.createFloat(cy));
            }
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
        final int N = state.mNumChildren;
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
        if (mState.mRippleStyle == STYLE_SOLID) {
            drawSolid(canvas);
        } else {
            drawPatterned(canvas);
        }
    }

    private void drawSolid(Canvas canvas) {
        pruneRipples();

        // Clip to the dirty bounds, which will be the drawable bounds if we
        // have a mask or content and the ripple bounds if we're projecting.
        final Rect bounds = getDirtyBounds();
        final int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        if (isBounded()) {
            canvas.clipRect(bounds);
        }

        drawContent(canvas);
        drawBackgroundAndRipples(canvas);

        canvas.restoreToCount(saveCount);
    }

    private void exitPatternedBackgroundAnimation() {
        mTargetBackgroundOpacity = 0;
        if (mBackgroundAnimation != null) mBackgroundAnimation.cancel();
        // after cancel
        mRunBackgroundAnimation = true;
        invalidateSelf(false);
    }

    private void startPatternedAnimation() {
        mAddRipple = true;
        invalidateSelf(false);
    }

    private void exitPatternedAnimation() {
        mExitingAnimation = true;
        invalidateSelf(false);
    }

    private void enterPatternedBackgroundAnimation(boolean focused, boolean hovered,
            boolean windowFocused) {
        mBackgroundOpacity = 0;
        if (focused) {
            mTargetBackgroundOpacity = windowFocused ? .6f : .2f;
        } else {
            mTargetBackgroundOpacity = hovered ? .2f : 0f;
        }
        if (mBackgroundAnimation != null) mBackgroundAnimation.cancel();
        // after cancel
        mRunBackgroundAnimation = true;
        invalidateSelf(false);
    }

    private void startBackgroundAnimation() {
        mRunBackgroundAnimation = false;
        if (Looper.myLooper() == null) {
            Log.w(TAG, "Thread doesn't have a looper. Skipping animation.");
            return;
        }
        mBackgroundAnimation = ValueAnimator.ofFloat(mBackgroundOpacity, mTargetBackgroundOpacity);
        mBackgroundAnimation.setInterpolator(LINEAR_INTERPOLATOR);
        mBackgroundAnimation.setDuration(BACKGROUND_OPACITY_DURATION);
        mBackgroundAnimation.addUpdateListener(update -> {
            mBackgroundOpacity = (float) update.getAnimatedValue();
            invalidateSelf(false);
        });
        mBackgroundAnimation.start();
    }

    private void drawPatterned(@NonNull Canvas canvas) {
        final Rect bounds = mHotspotBounds;
        final int saveCount = canvas.save(Canvas.CLIP_SAVE_FLAG);
        boolean useCanvasProps = !mForceSoftware;
        if (isBounded()) {
            canvas.clipRect(getDirtyBounds());
        }
        final float x, y, cx, cy, w, h;
        boolean addRipple = mAddRipple;
        cx = bounds.centerX();
        cy = bounds.centerY();
        boolean shouldExit = mExitingAnimation;
        mExitingAnimation = false;
        mAddRipple = false;
        if (mRunningAnimations.size() > 0 && !addRipple) {
            // update paint when view is invalidated
            updateRipplePaint();
        }
        drawContent(canvas);
        drawPatternedBackground(canvas, cx, cy);
        if (addRipple && mRunningAnimations.size() <= MAX_RIPPLES) {
            if (mHasPending) {
                x = mPendingX;
                y = mPendingY;
                mHasPending = false;
            } else {
                x = bounds.exactCenterX();
                y = bounds.exactCenterY();
            }
            h = bounds.height();
            w = bounds.width();
            RippleAnimationSession.AnimationProperties<Float, Paint> properties =
                    createAnimationProperties(x, y, cx, cy, w, h);
            mRunningAnimations.add(new RippleAnimationSession(properties, !useCanvasProps)
                    .setOnAnimationUpdated(() -> invalidateSelf(false))
                    .setOnSessionEnd(session -> {
                        mRunningAnimations.remove(session);
                    })
                    .setForceSoftwareAnimation(!useCanvasProps)
                    .enter(canvas));
        }
        if (shouldExit) {
            for (int i = 0; i < mRunningAnimations.size(); i++) {
                RippleAnimationSession s = mRunningAnimations.get(i);
                s.exit(canvas);
            }
        }
        for (int i = 0; i < mRunningAnimations.size(); i++) {
            RippleAnimationSession s = mRunningAnimations.get(i);
            if (!canvas.isHardwareAccelerated()) {
                Log.e(TAG, "The RippleDrawable.STYLE_PATTERNED animation is not supported for a "
                        + "non-hardware accelerated Canvas. Skipping animation.");
                break;
            } else if (useCanvasProps) {
                RippleAnimationSession.AnimationProperties<CanvasProperty<Float>,
                        CanvasProperty<Paint>>
                        p = s.getCanvasProperties();
                RecordingCanvas can = (RecordingCanvas) canvas;
                can.drawRipple(p.getX(), p.getY(), p.getMaxRadius(), p.getPaint(),
                        p.getProgress(), p.getNoisePhase(), p.getColor(), p.getShader());
            } else {
                RippleAnimationSession.AnimationProperties<Float, Paint> p =
                        s.getProperties();
                float radius = p.getMaxRadius();
                canvas.drawCircle(p.getX(), p.getY(), radius, p.getPaint());
            }
        }
        canvas.restoreToCount(saveCount);
    }

    private void drawPatternedBackground(Canvas c, float cx, float cy) {
        if (mRunBackgroundAnimation) {
            startBackgroundAnimation();
        }
        if (mBackgroundOpacity == 0) return;
        Paint p = updateRipplePaint();
        float newOpacity = mBackgroundOpacity;
        final int origAlpha = p.getAlpha();
        final int alpha = Math.min((int) (origAlpha * newOpacity + 0.5f), 255);
        if (alpha > 0) {
            ColorFilter origFilter = p.getColorFilter();
            p.setColorFilter(mFocusColorFilter);
            p.setAlpha(alpha);
            c.drawCircle(cx, cy, getComputedRadius(), p);
            p.setAlpha(origAlpha);
            p.setColorFilter(origFilter);
        }
    }

    private float computeRadius() {
        final float halfWidth = mHotspotBounds.width() / 2.0f;
        final float halfHeight = mHotspotBounds.height() / 2.0f;
        return (float) Math.sqrt(halfWidth * halfWidth + halfHeight * halfHeight);
    }

    private int getComputedRadius() {
        if (mState.mMaxRadius >= 0) return mState.mMaxRadius;
        return (int) computeRadius();
    }

    @NonNull
    private RippleAnimationSession.AnimationProperties<Float, Paint> createAnimationProperties(
            float x, float y, float cx, float cy, float w, float h) {
        Paint p = new Paint(updateRipplePaint());
        float radius = getComputedRadius();
        RippleAnimationSession.AnimationProperties<Float, Paint> properties;
        RippleShader shader = new RippleShader();
        // Grab the color for the current state and cut the alpha channel in
        // half so that the ripple and background together yield full alpha.
        final int color = clampAlpha(mMaskColorFilter == null
                ? mState.mColor.getColorForState(getState(), Color.BLACK)
                : mMaskColorFilter.getColor());
        final int effectColor = mState.mEffectColor.getColorForState(getState(), Color.MAGENTA);
        final float noisePhase = AnimationUtils.currentAnimationTimeMillis();
        shader.setColor(color, effectColor);
        shader.setOrigin(cx, cy);
        shader.setTouch(x, y);
        shader.setResolution(w, h);
        shader.setNoisePhase(noisePhase);
        shader.setRadius(radius);
        shader.setProgress(.0f);
        properties = new RippleAnimationSession.AnimationProperties<>(
                cx, cy, radius, noisePhase, p, 0f, color, shader);
        if (mMaskShader == null) {
            shader.setShader(null);
        } else {
            shader.setShader(mMaskShader);
        }
        p.setShader(shader);
        p.setColorFilter(null);
        p.setColor(color);
        return properties;
    }

    private int clampAlpha(@ColorInt int color) {
        if (Color.alpha(color) < 128) {
            return  (color & 0x00FFFFFF) | 0x80000000;
        }
        return color;
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
            mFocusColorFilter = new PorterDuffColorFilter(0, PorterDuff.Mode.SRC_IN);
        }

        // Draw the appropriate mask anchored to (0,0).
        final int saveCount = mMaskCanvas.save();
        final int left = bounds.left;
        final int top = bounds.top;
        mMaskCanvas.translate(-left, -top);
        if (maskType == MASK_EXPLICIT) {
            drawMask(mMaskCanvas);
        } else if (maskType == MASK_CONTENT) {
            drawContent(mMaskCanvas);
        }
        mMaskCanvas.restoreToCount(saveCount);
    }

    private int getMaskType() {
        if (mRipple == null && mExitingRipplesCount <= 0
                && (mBackground == null || !mBackground.isVisible())
                && mState.mRippleStyle == STYLE_SOLID) {
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
        final int count = mLayerState.mNumChildren;
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
        final int count = mLayerState.mNumChildren;
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

        final Paint p = updateRipplePaint();

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

    @UnsupportedAppUsage
    Paint updateRipplePaint() {
        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
            mRipplePaint.setStyle(Paint.Style.FILL);
        }

        final float x = mHotspotBounds.exactCenterX();
        final float y = mHotspotBounds.exactCenterY();

        updateMaskShaderIfNeeded();

        // Position the shader to account for canvas translation.
        if (mMaskShader != null) {
            final Rect bounds = getBounds();
            if (mState.mRippleStyle == STYLE_PATTERNED) {
                mMaskMatrix.setTranslate(bounds.left, bounds.top);
            } else {
                mMaskMatrix.setTranslate(bounds.left - x, bounds.top - y);
            }
            mMaskShader.setLocalMatrix(mMaskMatrix);

            if (mState.mRippleStyle == STYLE_PATTERNED) {
                for (int i = 0; i < mRunningAnimations.size(); i++) {
                    mRunningAnimations.get(i).getProperties().getShader().setShader(mMaskShader);
                }
            }
        }

        // Grab the color for the current state and cut the alpha channel in
        // half so that the ripple and background together yield full alpha.
        final int color = clampAlpha(mState.mColor.getColorForState(getState(), Color.BLACK));
        final Paint p = mRipplePaint;

        if (mMaskColorFilter != null) {
            // The ripple timing depends on the paint's alpha value, so we need
            // to push just the alpha channel into the paint and let the filter
            // handle the full-alpha color.
            int maskColor = mState.mRippleStyle == STYLE_PATTERNED ? color : color | 0xFF000000;
            if (mMaskColorFilter.getColor() != maskColor) {
                mMaskColorFilter = new PorterDuffColorFilter(maskColor, mMaskColorFilter.getMode());
                mFocusColorFilter = new PorterDuffColorFilter(color | 0xFF000000,
                        mFocusColorFilter.getMode());
            }
            p.setColor(color & 0xFF000000);
            p.setColorFilter(mMaskColorFilter);
            p.setShader(mMaskShader);
        } else {
            p.setColor(color);
            p.setColorFilter(null);
            p.setShader(null);
        }

        return p;
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
    @UnsupportedAppUsage
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
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        ColorStateList mColor = ColorStateList.valueOf(Color.MAGENTA);
        ColorStateList mEffectColor = ColorStateList.valueOf(DEFAULT_EFFECT_COLOR);
        int mMaxRadius = RADIUS_AUTO;
        int mRippleStyle = FORCE_PATTERNED_STYLE ? STYLE_PATTERNED : STYLE_SOLID;

        public RippleState(LayerState orig, RippleDrawable owner, Resources res) {
            super(orig, owner, res);

            if (orig != null && orig instanceof RippleState) {
                final RippleState origs = (RippleState) orig;
                mTouchThemeAttrs = origs.mTouchThemeAttrs;
                mColor = origs.mColor;
                mMaxRadius = origs.mMaxRadius;
                mRippleStyle = origs.mRippleStyle;
                mEffectColor = origs.mEffectColor;

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

        if (mState.mNumChildren > 0) {
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
