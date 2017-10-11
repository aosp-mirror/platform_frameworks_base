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

package android.graphics.drawable;

import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A Drawable with a color gradient for buttons, backgrounds, etc.
 *
 * <p>It can be defined in an XML file with the <code>&lt;shape></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#GradientDrawable_visible
 * @attr ref android.R.styleable#GradientDrawable_shape
 * @attr ref android.R.styleable#GradientDrawable_innerRadiusRatio
 * @attr ref android.R.styleable#GradientDrawable_innerRadius
 * @attr ref android.R.styleable#GradientDrawable_thicknessRatio
 * @attr ref android.R.styleable#GradientDrawable_thickness
 * @attr ref android.R.styleable#GradientDrawable_useLevel
 * @attr ref android.R.styleable#GradientDrawableSize_width
 * @attr ref android.R.styleable#GradientDrawableSize_height
 * @attr ref android.R.styleable#GradientDrawableGradient_startColor
 * @attr ref android.R.styleable#GradientDrawableGradient_centerColor
 * @attr ref android.R.styleable#GradientDrawableGradient_endColor
 * @attr ref android.R.styleable#GradientDrawableGradient_useLevel
 * @attr ref android.R.styleable#GradientDrawableGradient_angle
 * @attr ref android.R.styleable#GradientDrawableGradient_type
 * @attr ref android.R.styleable#GradientDrawableGradient_centerX
 * @attr ref android.R.styleable#GradientDrawableGradient_centerY
 * @attr ref android.R.styleable#GradientDrawableGradient_gradientRadius
 * @attr ref android.R.styleable#GradientDrawableSolid_color
 * @attr ref android.R.styleable#GradientDrawableStroke_width
 * @attr ref android.R.styleable#GradientDrawableStroke_color
 * @attr ref android.R.styleable#GradientDrawableStroke_dashWidth
 * @attr ref android.R.styleable#GradientDrawableStroke_dashGap
 * @attr ref android.R.styleable#GradientDrawablePadding_left
 * @attr ref android.R.styleable#GradientDrawablePadding_top
 * @attr ref android.R.styleable#GradientDrawablePadding_right
 * @attr ref android.R.styleable#GradientDrawablePadding_bottom
 */
public class GradientDrawable extends Drawable {
    /**
     * Shape is a rectangle, possibly with rounded corners
     */
    public static final int RECTANGLE = 0;

    /**
     * Shape is an ellipse
     */
    public static final int OVAL = 1;

    /**
     * Shape is a line
     */
    public static final int LINE = 2;

    /**
     * Shape is a ring.
     */
    public static final int RING = 3;

    /** @hide */
    @IntDef({RECTANGLE, OVAL, LINE, RING})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Shape {}

    /**
     * Gradient is linear (default.)
     */
    public static final int LINEAR_GRADIENT = 0;

    /**
     * Gradient is circular.
     */
    public static final int RADIAL_GRADIENT = 1;

    /**
     * Gradient is a sweep.
     */
    public static final int SWEEP_GRADIENT  = 2;

    /** @hide */
    @IntDef({LINEAR_GRADIENT, RADIAL_GRADIENT, SWEEP_GRADIENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface GradientType {}

    /** Radius is in pixels. */
    private static final int RADIUS_TYPE_PIXELS = 0;

    /** Radius is a fraction of the base size. */
    private static final int RADIUS_TYPE_FRACTION = 1;

    /** Radius is a fraction of the bounds size. */
    private static final int RADIUS_TYPE_FRACTION_PARENT = 2;

    /** @hide */
    @IntDef({RADIUS_TYPE_PIXELS, RADIUS_TYPE_FRACTION, RADIUS_TYPE_FRACTION_PARENT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RadiusType {}

    private static final float DEFAULT_INNER_RADIUS_RATIO = 3.0f;
    private static final float DEFAULT_THICKNESS_RATIO = 9.0f;

    private GradientState mGradientState;

    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Rect mPadding;
    private Paint mStrokePaint;   // optional, set by the caller
    private ColorFilter mColorFilter;   // optional, set by the caller
    private PorterDuffColorFilter mTintFilter;
    private int mAlpha = 0xFF;  // modified by the caller

    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private Paint mLayerPaint;    // internal, used if we use saveLayer()
    private boolean mGradientIsDirty;
    private boolean mMutated;
    private Path mRingPath;
    private boolean mPathIsDirty = true;

    /** Current gradient radius, valid when {@link #mGradientIsDirty} is false. */
    private float mGradientRadius;

    /**
     * Controls how the gradient is oriented relative to the drawable's bounds
     */
    public enum Orientation {
        /** draw the gradient from the top to the bottom */
        TOP_BOTTOM,
        /** draw the gradient from the top-right to the bottom-left */
        TR_BL,
        /** draw the gradient from the right to the left */
        RIGHT_LEFT,
        /** draw the gradient from the bottom-right to the top-left */
        BR_TL,
        /** draw the gradient from the bottom to the top */
        BOTTOM_TOP,
        /** draw the gradient from the bottom-left to the top-right */
        BL_TR,
        /** draw the gradient from the left to the right */
        LEFT_RIGHT,
        /** draw the gradient from the top-left to the bottom-right */
        TL_BR,
    }

    public GradientDrawable() {
        this(new GradientState(Orientation.TOP_BOTTOM, null), null);
    }

    /**
     * Create a new gradient drawable given an orientation and an array
     * of colors for the gradient.
     */
    public GradientDrawable(Orientation orientation, @ColorInt int[] colors) {
        this(new GradientState(orientation, colors), null);
    }

    @Override
    public boolean getPadding(Rect padding) {
        if (mPadding != null) {
            padding.set(mPadding);
            return true;
        } else {
            return super.getPadding(padding);
        }
    }

    /**
     * Specifies radii for each of the 4 corners. For each corner, the array
     * contains 2 values, <code>[X_radius, Y_radius]</code>. The corners are
     * ordered top-left, top-right, bottom-right, bottom-left. This property
     * is honored only when the shape is of type {@link #RECTANGLE}.
     * <p>
     * <strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param radii an array of length >= 8 containing 4 pairs of X and Y
     *              radius for each corner, specified in pixels
     *
     * @see #mutate()
     * @see #setShape(int)
     * @see #setCornerRadius(float)
     */
    public void setCornerRadii(@Nullable float[] radii) {
        mGradientState.setCornerRadii(radii);
        mPathIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the radii for each of the 4 corners. For each corner, the array
     * contains 2 values, <code>[X_radius, Y_radius]</code>. The corners are
     * ordered top-left, top-right, bottom-right, bottom-left.
     * <p>
     * If the radius was previously set with {@link #setCornerRadius(float)},
     * or if the corners are not rounded, this method will return {@code null}.
     *
     * @return an array containing the radii for each of the 4 corners, or
     *         {@code null}
     * @see #setCornerRadii(float[])
     */
    @Nullable
    public float[] getCornerRadii() {
        return mGradientState.mRadiusArray.clone();
    }

    /**
     * Specifies the radius for the corners of the gradient. If this is > 0,
     * then the drawable is drawn in a round-rectangle, rather than a
     * rectangle. This property is honored only when the shape is of type
     * {@link #RECTANGLE}.
     * <p>
     * <strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param radius The radius in pixels of the corners of the rectangle shape
     *
     * @see #mutate()
     * @see #setCornerRadii(float[])
     * @see #setShape(int)
     */
    public void setCornerRadius(float radius) {
        mGradientState.setCornerRadius(radius);
        mPathIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the radius for the corners of the gradient, that was previously set with
     * {@link #setCornerRadius(float)}.
     * <p>
     * If the radius was previously cleared via passing {@code null}
     * to {@link #setCornerRadii(float[])}, this method will return 0.
     *
     * @return the radius in pixels of the corners of the rectangle shape, or 0
     * @see #setCornerRadius
     */
    public float getCornerRadius() {
        return mGradientState.mRadius;
    }

    /**
     * <p>Set the stroke width and color for the drawable. If width is zero,
     * then no stroke is drawn.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param width The width in pixels of the stroke
     * @param color The color of the stroke
     *
     * @see #mutate()
     * @see #setStroke(int, int, float, float)
     */
    public void setStroke(int width, @ColorInt int color) {
        setStroke(width, color, 0, 0);
    }

    /**
     * <p>Set the stroke width and color state list for the drawable. If width
     * is zero, then no stroke is drawn.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param width The width in pixels of the stroke
     * @param colorStateList The color state list of the stroke
     *
     * @see #mutate()
     * @see #setStroke(int, ColorStateList, float, float)
     */
    public void setStroke(int width, ColorStateList colorStateList) {
        setStroke(width, colorStateList, 0, 0);
    }

    /**
     * <p>Set the stroke width and color for the drawable. If width is zero,
     * then no stroke is drawn. This method can also be used to dash the stroke.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param width The width in pixels of the stroke
     * @param color The color of the stroke
     * @param dashWidth The length in pixels of the dashes, set to 0 to disable dashes
     * @param dashGap The gap in pixels between dashes
     *
     * @see #mutate()
     * @see #setStroke(int, int)
     */
    public void setStroke(int width, @ColorInt int color, float dashWidth, float dashGap) {
        mGradientState.setStroke(width, ColorStateList.valueOf(color), dashWidth, dashGap);
        setStrokeInternal(width, color, dashWidth, dashGap);
    }

    /**
     * <p>Set the stroke width and color state list for the drawable. If width
     * is zero, then no stroke is drawn. This method can also be used to dash
     * the stroke.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param width The width in pixels of the stroke
     * @param colorStateList The color state list of the stroke
     * @param dashWidth The length in pixels of the dashes, set to 0 to disable dashes
     * @param dashGap The gap in pixels between dashes
     *
     * @see #mutate()
     * @see #setStroke(int, ColorStateList)
     */
    public void setStroke(
            int width, ColorStateList colorStateList, float dashWidth, float dashGap) {
        mGradientState.setStroke(width, colorStateList, dashWidth, dashGap);
        final int color;
        if (colorStateList == null) {
            color = Color.TRANSPARENT;
        } else {
            final int[] stateSet = getState();
            color = colorStateList.getColorForState(stateSet, 0);
        }
        setStrokeInternal(width, color, dashWidth, dashGap);
    }

    private void setStrokeInternal(int width, int color, float dashWidth, float dashGap) {
        if (mStrokePaint == null)  {
            mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mStrokePaint.setStyle(Paint.Style.STROKE);
        }
        mStrokePaint.setStrokeWidth(width);
        mStrokePaint.setColor(color);

        DashPathEffect e = null;
        if (dashWidth > 0) {
            e = new DashPathEffect(new float[] { dashWidth, dashGap }, 0);
        }
        mStrokePaint.setPathEffect(e);
        invalidateSelf();
    }


    /**
     * <p>Sets the size of the shape drawn by this drawable.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param width The width of the shape used by this drawable
     * @param height The height of the shape used by this drawable
     *
     * @see #mutate()
     * @see #setGradientType(int)
     */
    public void setSize(int width, int height) {
        mGradientState.setSize(width, height);
        mPathIsDirty = true;
        invalidateSelf();
    }

    /**
     * <p>Sets the type of shape used to draw the gradient.</p>
     * <p><strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.</p>
     *
     * @param shape The desired shape for this drawable: {@link #LINE},
     *              {@link #OVAL}, {@link #RECTANGLE} or {@link #RING}
     *
     * @see #mutate()
     */
    public void setShape(@Shape int shape) {
        mRingPath = null;
        mPathIsDirty = true;
        mGradientState.setShape(shape);
        invalidateSelf();
    }

    /**
     * Returns the type of shape used by this drawable, one of {@link #LINE},
     * {@link #OVAL}, {@link #RECTANGLE} or {@link #RING}.
     *
     * @return the type of shape used by this drawable
     * @see #setShape(int)
     */
    @Shape
    public int getShape() {
        return mGradientState.mShape;
    }

    /**
     * Sets the type of gradient used by this drawable.
     * <p>
     * <strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param gradient The type of the gradient: {@link #LINEAR_GRADIENT},
     *                 {@link #RADIAL_GRADIENT} or {@link #SWEEP_GRADIENT}
     *
     * @see #mutate()
     * @see #getGradientType()
     */
    public void setGradientType(@GradientType int gradient) {
        mGradientState.setGradientType(gradient);
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the type of gradient used by this drawable, one of
     * {@link #LINEAR_GRADIENT}, {@link #RADIAL_GRADIENT}, or
     * {@link #SWEEP_GRADIENT}.
     *
     * @return the type of gradient used by this drawable
     * @see #setGradientType(int)
     */
    @GradientType
    public int getGradientType() {
        return mGradientState.mGradient;
    }

    /**
     * Sets the position of the center of the gradient as a fraction of the
     * width and height.
     * <p>
     * The default value is (0.5, 0.5).
     * <p>
     * <strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param x the X-position of the center of the gradient
     * @param y the Y-position of the center of the gradient
     *
     * @see #mutate()
     * @see #setGradientType(int)
     * @see #getGradientCenterX()
     * @see #getGradientCenterY()
     */
    public void setGradientCenter(float x, float y) {
        mGradientState.setGradientCenter(x, y);
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the X-position of the center of the gradient as a fraction of
     * the width.
     *
     * @return the X-position of the center of the gradient
     * @see #setGradientCenter(float, float)
     */
    public float getGradientCenterX() {
        return mGradientState.mCenterX;
    }

    /**
     * Returns the Y-position of the center of this gradient as a fraction of
     * the height.
     *
     * @return the Y-position of the center of the gradient
     * @see #setGradientCenter(float, float)
     */
    public float getGradientCenterY() {
        return mGradientState.mCenterY;
    }

    /**
     * Sets the radius of the gradient. The radius is honored only when the
     * gradient type is set to {@link #RADIAL_GRADIENT}.
     * <p>
     * <strong>Note</strong>: changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param gradientRadius the radius of the gradient in pixels
     *
     * @see #mutate()
     * @see #setGradientType(int)
     * @see #getGradientRadius()
     */
    public void setGradientRadius(float gradientRadius) {
        mGradientState.setGradientRadius(gradientRadius, TypedValue.COMPLEX_UNIT_PX);
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the radius of the gradient in pixels. The radius is valid only
     * when the gradient type is set to {@link #RADIAL_GRADIENT}.
     *
     * @return the radius of the gradient in pixels
     * @see #setGradientRadius(float)
     */
    public float getGradientRadius() {
        if (mGradientState.mGradient != RADIAL_GRADIENT) {
            return 0;
        }

        ensureValidRect();
        return mGradientRadius;
    }

    /**
     * Sets whether this drawable's {@code level} property will be used to
     * scale the gradient. If a gradient is not used, this property has no
     * effect.
     * <p>
     * Scaling behavior varies based on gradient type:
     * <ul>
     *     <li>{@link #LINEAR_GRADIENT} adjusts the ending position along the
     *         gradient's axis of orientation (see {@link #getOrientation()})
     *     <li>{@link #RADIAL_GRADIENT} adjusts the outer radius
     *     <li>{@link #SWEEP_GRADIENT} adjusts the ending angle
     * <ul>
     * <p>
     * The default value for this property is {@code false}.
     * <p>
     * <strong>Note</strong>: This property corresponds to the
     * {@code android:useLevel} attribute on the inner {@code &lt;gradient&gt;}
     * tag, NOT the {@code android:useLevel} attribute on the outer
     * {@code &lt;shape&gt;} tag. For example,
     * <pre>{@code
     * <shape ...>
     *     <gradient
     *         ...
     *         android:useLevel="true" />
     * </shape>
     * }</pre><p>
     * <strong>Note</strong>: Changing this property will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing this property.
     *
     * @param useLevel {@code true} if the gradient should be scaled based on
     *                 level, {@code false} otherwise
     *
     * @see #mutate()
     * @see #setLevel(int)
     * @see #getLevel()
     * @see #getUseLevel()
     * @attr ref android.R.styleable#GradientDrawableGradient_useLevel
     */
    public void setUseLevel(boolean useLevel) {
        mGradientState.mUseLevel = useLevel;
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns whether this drawable's {@code level} property will be used to
     * scale the gradient.
     *
     * @return {@code true} if the gradient should be scaled based on level,
     *         {@code false} otherwise
     * @see #setUseLevel(boolean)
     * @attr ref android.R.styleable#GradientDrawableGradient_useLevel
     */
    public boolean getUseLevel() {
        return mGradientState.mUseLevel;
    }

    private int modulateAlpha(int alpha) {
        int scale = mAlpha + (mAlpha >> 7);
        return alpha * scale >> 8;
    }

    /**
     * Returns the orientation of the gradient defined in this drawable.
     *
     * @return the orientation of the gradient defined in this drawable
     * @see #setOrientation(Orientation)
     */
    public Orientation getOrientation() {
        return mGradientState.mOrientation;
    }

    /**
     * Sets the orientation of the gradient defined in this drawable.
     * <p>
     * <strong>Note</strong>: changing orientation will affect all instances
     * of a drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing the orientation.
     *
     * @param orientation the desired orientation (angle) of the gradient
     *
     * @see #mutate()
     * @see #getOrientation()
     */
    public void setOrientation(Orientation orientation) {
        mGradientState.mOrientation = orientation;
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Sets the colors used to draw the gradient.
     * <p>
     * Each color is specified as an ARGB integer and the array must contain at
     * least 2 colors.
     * <p>
     * <strong>Note</strong>: changing colors will affect all instances of a
     * drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing the colors.
     *
     * @param colors an array containing 2 or more ARGB colors
     * @see #mutate()
     * @see #setColor(int)
     */
    public void setColors(@ColorInt int[] colors) {
        mGradientState.setGradientColors(colors);
        mGradientIsDirty = true;
        invalidateSelf();
    }

    /**
     * Returns the colors used to draw the gradient, or {@code null} if the
     * gradient is drawn using a single color or no colors.
     *
     * @return the colors used to draw the gradient, or {@code null}
     * @see #setColors(int[] colors)
     */
    @Nullable
    public int[] getColors() {
        return mGradientState.mGradientColors == null ?
                null : mGradientState.mGradientColors.clone();
    }

    @Override
    public void draw(Canvas canvas) {
        if (!ensureValidRect()) {
            // nothing to draw
            return;
        }

        // remember the alpha values, in case we temporarily overwrite them
        // when we modulate them with mAlpha
        final int prevFillAlpha = mFillPaint.getAlpha();
        final int prevStrokeAlpha = mStrokePaint != null ? mStrokePaint.getAlpha() : 0;
        // compute the modulate alpha values
        final int currFillAlpha = modulateAlpha(prevFillAlpha);
        final int currStrokeAlpha = modulateAlpha(prevStrokeAlpha);

        final boolean haveStroke = currStrokeAlpha > 0 && mStrokePaint != null &&
                mStrokePaint.getStrokeWidth() > 0;
        final boolean haveFill = currFillAlpha > 0;
        final GradientState st = mGradientState;
        final ColorFilter colorFilter = mColorFilter != null ? mColorFilter : mTintFilter;

        /*  we need a layer iff we're drawing both a fill and stroke, and the
            stroke is non-opaque, and our shapetype actually supports
            fill+stroke. Otherwise we can just draw the stroke (if any) on top
            of the fill (if any) without worrying about blending artifacts.
         */
        final boolean useLayer = haveStroke && haveFill && st.mShape != LINE &&
                 currStrokeAlpha < 255 && (mAlpha < 255 || colorFilter != null);

        /*  Drawing with a layer is slower than direct drawing, but it
            allows us to apply paint effects like alpha and colorfilter to
            the result of multiple separate draws. In our case, if the user
            asks for a non-opaque alpha value (via setAlpha), and we're
            stroking, then we need to apply the alpha AFTER we've drawn
            both the fill and the stroke.
        */
        if (useLayer) {
            if (mLayerPaint == null) {
                mLayerPaint = new Paint();
            }
            mLayerPaint.setDither(st.mDither);
            mLayerPaint.setAlpha(mAlpha);
            mLayerPaint.setColorFilter(colorFilter);

            float rad = mStrokePaint.getStrokeWidth();
            canvas.saveLayer(mRect.left - rad, mRect.top - rad,
                             mRect.right + rad, mRect.bottom + rad,
                             mLayerPaint);

            // don't perform the filter in our individual paints
            // since the layer will do it for us
            mFillPaint.setColorFilter(null);
            mStrokePaint.setColorFilter(null);
        } else {
            /*  if we're not using a layer, apply the dither/filter to our
                individual paints
            */
            mFillPaint.setAlpha(currFillAlpha);
            mFillPaint.setDither(st.mDither);
            mFillPaint.setColorFilter(colorFilter);
            if (colorFilter != null && st.mSolidColors == null) {
                mFillPaint.setColor(mAlpha << 24);
            }
            if (haveStroke) {
                mStrokePaint.setAlpha(currStrokeAlpha);
                mStrokePaint.setDither(st.mDither);
                mStrokePaint.setColorFilter(colorFilter);
            }
        }

        switch (st.mShape) {
            case RECTANGLE:
                if (st.mRadiusArray != null) {
                    buildPathIfDirty();
                    canvas.drawPath(mPath, mFillPaint);
                    if (haveStroke) {
                        canvas.drawPath(mPath, mStrokePaint);
                    }
                } else if (st.mRadius > 0.0f) {
                    // since the caller is only giving us 1 value, we will force
                    // it to be square if the rect is too small in one dimension
                    // to show it. If we did nothing, Skia would clamp the rad
                    // independently along each axis, giving us a thin ellipse
                    // if the rect were very wide but not very tall
                    float rad = Math.min(st.mRadius,
                            Math.min(mRect.width(), mRect.height()) * 0.5f);
                    canvas.drawRoundRect(mRect, rad, rad, mFillPaint);
                    if (haveStroke) {
                        canvas.drawRoundRect(mRect, rad, rad, mStrokePaint);
                    }
                } else {
                    if (mFillPaint.getColor() != 0 || colorFilter != null ||
                            mFillPaint.getShader() != null) {
                        canvas.drawRect(mRect, mFillPaint);
                    }
                    if (haveStroke) {
                        canvas.drawRect(mRect, mStrokePaint);
                    }
                }
                break;
            case OVAL:
                canvas.drawOval(mRect, mFillPaint);
                if (haveStroke) {
                    canvas.drawOval(mRect, mStrokePaint);
                }
                break;
            case LINE: {
                RectF r = mRect;
                float y = r.centerY();
                if (haveStroke) {
                    canvas.drawLine(r.left, y, r.right, y, mStrokePaint);
                }
                break;
            }
            case RING:
                Path path = buildRing(st);
                canvas.drawPath(path, mFillPaint);
                if (haveStroke) {
                    canvas.drawPath(path, mStrokePaint);
                }
                break;
        }

        if (useLayer) {
            canvas.restore();
        } else {
            mFillPaint.setAlpha(prevFillAlpha);
            if (haveStroke) {
                mStrokePaint.setAlpha(prevStrokeAlpha);
            }
        }
    }

    private void buildPathIfDirty() {
        final GradientState st = mGradientState;
        if (mPathIsDirty) {
            ensureValidRect();
            mPath.reset();
            mPath.addRoundRect(mRect, st.mRadiusArray, Path.Direction.CW);
            mPathIsDirty = false;
        }
    }

    private Path buildRing(GradientState st) {
        if (mRingPath != null && (!st.mUseLevelForShape || !mPathIsDirty)) return mRingPath;
        mPathIsDirty = false;

        float sweep = st.mUseLevelForShape ? (360.0f * getLevel() / 10000.0f) : 360f;

        RectF bounds = new RectF(mRect);

        float x = bounds.width() / 2.0f;
        float y = bounds.height() / 2.0f;

        float thickness = st.mThickness != -1 ?
                st.mThickness : bounds.width() / st.mThicknessRatio;
        // inner radius
        float radius = st.mInnerRadius != -1 ?
                st.mInnerRadius : bounds.width() / st.mInnerRadiusRatio;

        RectF innerBounds = new RectF(bounds);
        innerBounds.inset(x - radius, y - radius);

        bounds = new RectF(innerBounds);
        bounds.inset(-thickness, -thickness);

        if (mRingPath == null) {
            mRingPath = new Path();
        } else {
            mRingPath.reset();
        }

        final Path ringPath = mRingPath;
        // arcTo treats the sweep angle mod 360, so check for that, since we
        // think 360 means draw the entire oval
        if (sweep < 360 && sweep > -360) {
            ringPath.setFillType(Path.FillType.EVEN_ODD);
            // inner top
            ringPath.moveTo(x + radius, y);
            // outer top
            ringPath.lineTo(x + radius + thickness, y);
            // outer arc
            ringPath.arcTo(bounds, 0.0f, sweep, false);
            // inner arc
            ringPath.arcTo(innerBounds, sweep, -sweep, false);
            ringPath.close();
        } else {
            // add the entire ovals
            ringPath.addOval(bounds, Path.Direction.CW);
            ringPath.addOval(innerBounds, Path.Direction.CCW);
        }

        return ringPath;
    }

    /**
     * Changes this drawable to use a single color instead of a gradient.
     * <p>
     * <strong>Note</strong>: changing color will affect all instances of a
     * drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing the color.
     *
     * @param argb The color used to fill the shape
     *
     * @see #mutate()
     * @see #setColors(int[])
     * @see #getColor
     */
    public void setColor(@ColorInt int argb) {
        mGradientState.setSolidColors(ColorStateList.valueOf(argb));
        mFillPaint.setColor(argb);
        invalidateSelf();
    }

    /**
     * Changes this drawable to use a single color state list instead of a
     * gradient. Calling this method with a null argument will clear the color
     * and is equivalent to calling {@link #setColor(int)} with the argument
     * {@link Color#TRANSPARENT}.
     * <p>
     * <strong>Note</strong>: changing color will affect all instances of a
     * drawable loaded from a resource. It is recommended to invoke
     * {@link #mutate()} before changing the color.</p>
     *
     * @param colorStateList The color state list used to fill the shape
     *
     * @see #mutate()
     * @see #getColor
     */
    public void setColor(@Nullable ColorStateList colorStateList) {
        mGradientState.setSolidColors(colorStateList);
        final int color;
        if (colorStateList == null) {
            color = Color.TRANSPARENT;
        } else {
            final int[] stateSet = getState();
            color = colorStateList.getColorForState(stateSet, 0);
        }
        mFillPaint.setColor(color);
        invalidateSelf();
    }

    /**
     * Returns the color state list used to fill the shape, or {@code null} if
     * the shape is filled with a gradient or has no fill color.
     *
     * @return the color state list used to fill this gradient, or {@code null}
     *
     * @see #setColor(int)
     * @see #setColor(ColorStateList)
     */
    @Nullable
    public ColorStateList getColor() {
        return mGradientState.mSolidColors;
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        boolean invalidateSelf = false;

        final GradientState s = mGradientState;
        final ColorStateList solidColors = s.mSolidColors;
        if (solidColors != null) {
            final int newColor = solidColors.getColorForState(stateSet, 0);
            final int oldColor = mFillPaint.getColor();
            if (oldColor != newColor) {
                mFillPaint.setColor(newColor);
                invalidateSelf = true;
            }
        }

        final Paint strokePaint = mStrokePaint;
        if (strokePaint != null) {
            final ColorStateList strokeColors = s.mStrokeColors;
            if (strokeColors != null) {
                final int newColor = strokeColors.getColorForState(stateSet, 0);
                final int oldColor = strokePaint.getColor();
                if (oldColor != newColor) {
                    strokePaint.setColor(newColor);
                    invalidateSelf = true;
                }
            }
        }

        if (s.mTint != null && s.mTintMode != null) {
            mTintFilter = updateTintFilter(mTintFilter, s.mTint, s.mTintMode);
            invalidateSelf = true;
        }

        if (invalidateSelf) {
            invalidateSelf();
            return true;
        }

        return false;
    }

    @Override
    public boolean isStateful() {
        final GradientState s = mGradientState;
        return super.isStateful()
                || (s.mSolidColors != null && s.mSolidColors.isStateful())
                || (s.mStrokeColors != null && s.mStrokeColors.isStateful())
                || (s.mTint != null && s.mTint.isStateful());
    }

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        final GradientState s = mGradientState;
        return (s.mSolidColors != null && s.mSolidColors.hasFocusStateSpecified())
                || (s.mStrokeColors != null && s.mStrokeColors.hasFocusStateSpecified())
                || (s.mTint != null && s.mTint.hasFocusStateSpecified());
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mGradientState.getChangingConfigurations();
    }

    @Override
    public void setAlpha(int alpha) {
        if (alpha != mAlpha) {
            mAlpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mAlpha;
    }

    @Override
    public void setDither(boolean dither) {
        if (dither != mGradientState.mDither) {
            mGradientState.mDither = dither;
            invalidateSelf();
        }
    }

    @Override
    @Nullable
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (colorFilter != mColorFilter) {
            mColorFilter = colorFilter;
            invalidateSelf();
        }
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        mGradientState.mTint = tint;
        mTintFilter = updateTintFilter(mTintFilter, tint, mGradientState.mTintMode);
        invalidateSelf();
    }

    @Override
    public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
        mGradientState.mTintMode = tintMode;
        mTintFilter = updateTintFilter(mTintFilter, mGradientState.mTint, tintMode);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return (mAlpha == 255 && mGradientState.mOpaqueOverBounds && isOpaqueForState()) ?
                PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
    }

    @Override
    protected void onBoundsChange(Rect r) {
        super.onBoundsChange(r);
        mRingPath = null;
        mPathIsDirty = true;
        mGradientIsDirty = true;
    }

    @Override
    protected boolean onLevelChange(int level) {
        super.onLevelChange(level);
        mGradientIsDirty = true;
        mPathIsDirty = true;
        invalidateSelf();
        return true;
    }

    /**
     * This checks mGradientIsDirty, and if it is true, recomputes both our drawing
     * rectangle (mRect) and the gradient itself, since it depends on our
     * rectangle too.
     * @return true if the resulting rectangle is not empty, false otherwise
     */
    private boolean ensureValidRect() {
        if (mGradientIsDirty) {
            mGradientIsDirty = false;

            Rect bounds = getBounds();
            float inset = 0;

            if (mStrokePaint != null) {
                inset = mStrokePaint.getStrokeWidth() * 0.5f;
            }

            final GradientState st = mGradientState;

            mRect.set(bounds.left + inset, bounds.top + inset,
                      bounds.right - inset, bounds.bottom - inset);

            final int[] gradientColors = st.mGradientColors;
            if (gradientColors != null) {
                final RectF r = mRect;
                final float x0, x1, y0, y1;

                if (st.mGradient == LINEAR_GRADIENT) {
                    final float level = st.mUseLevel ? getLevel() / 10000.0f : 1.0f;
                    switch (st.mOrientation) {
                    case TOP_BOTTOM:
                        x0 = r.left;            y0 = r.top;
                        x1 = x0;                y1 = level * r.bottom;
                        break;
                    case TR_BL:
                        x0 = r.right;           y0 = r.top;
                        x1 = level * r.left;    y1 = level * r.bottom;
                        break;
                    case RIGHT_LEFT:
                        x0 = r.right;           y0 = r.top;
                        x1 = level * r.left;    y1 = y0;
                        break;
                    case BR_TL:
                        x0 = r.right;           y0 = r.bottom;
                        x1 = level * r.left;    y1 = level * r.top;
                        break;
                    case BOTTOM_TOP:
                        x0 = r.left;            y0 = r.bottom;
                        x1 = x0;                y1 = level * r.top;
                        break;
                    case BL_TR:
                        x0 = r.left;            y0 = r.bottom;
                        x1 = level * r.right;   y1 = level * r.top;
                        break;
                    case LEFT_RIGHT:
                        x0 = r.left;            y0 = r.top;
                        x1 = level * r.right;   y1 = y0;
                        break;
                    default:/* TL_BR */
                        x0 = r.left;            y0 = r.top;
                        x1 = level * r.right;   y1 = level * r.bottom;
                        break;
                    }

                    mFillPaint.setShader(new LinearGradient(x0, y0, x1, y1,
                            gradientColors, st.mPositions, Shader.TileMode.CLAMP));
                } else if (st.mGradient == RADIAL_GRADIENT) {
                    x0 = r.left + (r.right - r.left) * st.mCenterX;
                    y0 = r.top + (r.bottom - r.top) * st.mCenterY;

                    float radius = st.mGradientRadius;
                    if (st.mGradientRadiusType == RADIUS_TYPE_FRACTION) {
                        // Fall back to parent width or height if intrinsic
                        // size is not specified.
                        final float width = st.mWidth >= 0 ? st.mWidth : r.width();
                        final float height = st.mHeight >= 0 ? st.mHeight : r.height();
                        radius *= Math.min(width, height);
                    } else if (st.mGradientRadiusType == RADIUS_TYPE_FRACTION_PARENT) {
                        radius *= Math.min(r.width(), r.height());
                    }

                    if (st.mUseLevel) {
                        radius *= getLevel() / 10000.0f;
                    }

                    mGradientRadius = radius;

                    if (radius <= 0) {
                        // We can't have a shader with non-positive radius, so
                        // let's have a very, very small radius.
                        radius = 0.001f;
                    }

                    mFillPaint.setShader(new RadialGradient(
                            x0, y0, radius, gradientColors, null, Shader.TileMode.CLAMP));
                } else if (st.mGradient == SWEEP_GRADIENT) {
                    x0 = r.left + (r.right - r.left) * st.mCenterX;
                    y0 = r.top + (r.bottom - r.top) * st.mCenterY;

                    int[] tempColors = gradientColors;
                    float[] tempPositions = null;

                    if (st.mUseLevel) {
                        tempColors = st.mTempColors;
                        final int length = gradientColors.length;
                        if (tempColors == null || tempColors.length != length + 1) {
                            tempColors = st.mTempColors = new int[length + 1];
                        }
                        System.arraycopy(gradientColors, 0, tempColors, 0, length);
                        tempColors[length] = gradientColors[length - 1];

                        tempPositions = st.mTempPositions;
                        final float fraction = 1.0f / (length - 1);
                        if (tempPositions == null || tempPositions.length != length + 1) {
                            tempPositions = st.mTempPositions = new float[length + 1];
                        }

                        final float level = getLevel() / 10000.0f;
                        for (int i = 0; i < length; i++) {
                            tempPositions[i] = i * fraction * level;
                        }
                        tempPositions[length] = 1.0f;

                    }
                    mFillPaint.setShader(new SweepGradient(x0, y0, tempColors, tempPositions));
                }

                // If we don't have a solid color, the alpha channel must be
                // maxed out so that alpha modulation works correctly.
                if (st.mSolidColors == null) {
                    mFillPaint.setColor(Color.BLACK);
                }
            }
        }
        return !mRect.isEmpty();
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        mGradientState.setDensity(Drawable.resolveDensity(r, 0));

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        inflateChildElements(r, parser, attrs, theme);

        updateLocalState(r);
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final GradientState state = mGradientState;
        if (state == null) {
            return;
        }

        state.setDensity(Drawable.resolveDensity(t.getResources(), 0));

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(
                    state.mThemeAttrs, R.styleable.GradientDrawable);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        if (state.mSolidColors != null && state.mSolidColors.canApplyTheme()) {
            state.mSolidColors = state.mSolidColors.obtainForTheme(t);
        }

        if (state.mStrokeColors != null && state.mStrokeColors.canApplyTheme()) {
            state.mStrokeColors = state.mStrokeColors.obtainForTheme(t);
        }

        applyThemeChildElements(t);

        updateLocalState(t.getResources());
    }

    /**
     * Updates the constant state from the values in the typed array.
     */
    private void updateStateFromTypedArray(TypedArray a) {
        final GradientState state = mGradientState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mShape = a.getInt(R.styleable.GradientDrawable_shape, state.mShape);
        state.mDither = a.getBoolean(R.styleable.GradientDrawable_dither, state.mDither);

        if (state.mShape == RING) {
            state.mInnerRadius = a.getDimensionPixelSize(
                    R.styleable.GradientDrawable_innerRadius, state.mInnerRadius);

            if (state.mInnerRadius == -1) {
                state.mInnerRadiusRatio = a.getFloat(
                        R.styleable.GradientDrawable_innerRadiusRatio, state.mInnerRadiusRatio);
            }

            state.mThickness = a.getDimensionPixelSize(
                    R.styleable.GradientDrawable_thickness, state.mThickness);

            if (state.mThickness == -1) {
                state.mThicknessRatio = a.getFloat(
                        R.styleable.GradientDrawable_thicknessRatio, state.mThicknessRatio);
            }

            state.mUseLevelForShape = a.getBoolean(
                    R.styleable.GradientDrawable_useLevel, state.mUseLevelForShape);
        }

        final int tintMode = a.getInt(R.styleable.GradientDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mTintMode = Drawable.parseTintMode(tintMode, PorterDuff.Mode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.GradientDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }

        final int insetLeft = a.getDimensionPixelSize(
                R.styleable.GradientDrawable_opticalInsetLeft, state.mOpticalInsets.left);
        final int insetTop = a.getDimensionPixelSize(
                R.styleable.GradientDrawable_opticalInsetTop, state.mOpticalInsets.top);
        final int insetRight = a.getDimensionPixelSize(
                R.styleable.GradientDrawable_opticalInsetRight, state.mOpticalInsets.right);
        final int insetBottom = a.getDimensionPixelSize(
                R.styleable.GradientDrawable_opticalInsetBottom, state.mOpticalInsets.bottom);
        state.mOpticalInsets = Insets.of(insetLeft, insetTop, insetRight, insetBottom);
    }

    @Override
    public boolean canApplyTheme() {
        return (mGradientState != null && mGradientState.canApplyTheme()) || super.canApplyTheme();
    }

    private void applyThemeChildElements(Theme t) {
        final GradientState st = mGradientState;

        if (st.mAttrSize != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrSize, R.styleable.GradientDrawableSize);
            updateGradientDrawableSize(a);
            a.recycle();
        }

        if (st.mAttrGradient != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrGradient, R.styleable.GradientDrawableGradient);
            try {
                updateGradientDrawableGradient(t.getResources(), a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        if (st.mAttrSolid != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrSolid, R.styleable.GradientDrawableSolid);
            updateGradientDrawableSolid(a);
            a.recycle();
        }

        if (st.mAttrStroke != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrStroke, R.styleable.GradientDrawableStroke);
            updateGradientDrawableStroke(a);
            a.recycle();
        }

        if (st.mAttrCorners != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrCorners, R.styleable.DrawableCorners);
            updateDrawableCorners(a);
            a.recycle();
        }

        if (st.mAttrPadding != null) {
            final TypedArray a = t.resolveAttributes(
                    st.mAttrPadding, R.styleable.GradientDrawablePadding);
            updateGradientDrawablePadding(a);
            a.recycle();
        }
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        TypedArray a;
        int type;

        final int innerDepth = parser.getDepth() + 1;
        int depth;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && ((depth=parser.getDepth()) >= innerDepth
                       || type != XmlPullParser.END_TAG)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if (depth > innerDepth) {
                continue;
            }

            String name = parser.getName();

            if (name.equals("size")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawableSize);
                updateGradientDrawableSize(a);
                a.recycle();
            } else if (name.equals("gradient")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawableGradient);
                updateGradientDrawableGradient(r, a);
                a.recycle();
            } else if (name.equals("solid")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawableSolid);
                updateGradientDrawableSolid(a);
                a.recycle();
            } else if (name.equals("stroke")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawableStroke);
                updateGradientDrawableStroke(a);
                a.recycle();
            } else if (name.equals("corners")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.DrawableCorners);
                updateDrawableCorners(a);
                a.recycle();
            } else if (name.equals("padding")) {
                a = obtainAttributes(r, theme, attrs, R.styleable.GradientDrawablePadding);
                updateGradientDrawablePadding(a);
                a.recycle();
            } else {
                Log.w("drawable", "Bad element under <shape>: " + name);
            }
        }
    }

    private void updateGradientDrawablePadding(TypedArray a) {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrPadding = a.extractThemeAttrs();

        if (st.mPadding == null) {
            st.mPadding = new Rect();
        }

        final Rect pad = st.mPadding;
        pad.set(a.getDimensionPixelOffset(R.styleable.GradientDrawablePadding_left, pad.left),
                a.getDimensionPixelOffset(R.styleable.GradientDrawablePadding_top, pad.top),
                a.getDimensionPixelOffset(R.styleable.GradientDrawablePadding_right, pad.right),
                a.getDimensionPixelOffset(R.styleable.GradientDrawablePadding_bottom, pad.bottom));
        mPadding = pad;
    }

    private void updateDrawableCorners(TypedArray a) {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrCorners = a.extractThemeAttrs();

        final int radius = a.getDimensionPixelSize(
                R.styleable.DrawableCorners_radius, (int) st.mRadius);
        setCornerRadius(radius);

        // TODO: Update these to be themeable.
        final int topLeftRadius = a.getDimensionPixelSize(
                R.styleable.DrawableCorners_topLeftRadius, radius);
        final int topRightRadius = a.getDimensionPixelSize(
                R.styleable.DrawableCorners_topRightRadius, radius);
        final int bottomLeftRadius = a.getDimensionPixelSize(
                R.styleable.DrawableCorners_bottomLeftRadius, radius);
        final int bottomRightRadius = a.getDimensionPixelSize(
                R.styleable.DrawableCorners_bottomRightRadius, radius);
        if (topLeftRadius != radius || topRightRadius != radius ||
                bottomLeftRadius != radius || bottomRightRadius != radius) {
            // The corner radii are specified in clockwise order (see Path.addRoundRect())
            setCornerRadii(new float[] {
                    topLeftRadius, topLeftRadius,
                    topRightRadius, topRightRadius,
                    bottomRightRadius, bottomRightRadius,
                    bottomLeftRadius, bottomLeftRadius
            });
        }
    }

    private void updateGradientDrawableStroke(TypedArray a) {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrStroke = a.extractThemeAttrs();

        // We have an explicit stroke defined, so the default stroke width
        // must be at least 0 or the current stroke width.
        final int defaultStrokeWidth = Math.max(0, st.mStrokeWidth);
        final int width = a.getDimensionPixelSize(
                R.styleable.GradientDrawableStroke_width, defaultStrokeWidth);
        final float dashWidth = a.getDimension(
                R.styleable.GradientDrawableStroke_dashWidth, st.mStrokeDashWidth);

        ColorStateList colorStateList = a.getColorStateList(
                R.styleable.GradientDrawableStroke_color);
        if (colorStateList == null) {
            colorStateList = st.mStrokeColors;
        }

        if (dashWidth != 0.0f) {
            final float dashGap = a.getDimension(
                    R.styleable.GradientDrawableStroke_dashGap, st.mStrokeDashGap);
            setStroke(width, colorStateList, dashWidth, dashGap);
        } else {
            setStroke(width, colorStateList);
        }
    }

    private void updateGradientDrawableSolid(TypedArray a) {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrSolid = a.extractThemeAttrs();

        final ColorStateList colorStateList = a.getColorStateList(
                R.styleable.GradientDrawableSolid_color);
        if (colorStateList != null) {
            setColor(colorStateList);
        }
    }

    private void updateGradientDrawableGradient(Resources r, TypedArray a)
            throws XmlPullParserException {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrGradient = a.extractThemeAttrs();

        st.mCenterX = getFloatOrFraction(
                a, R.styleable.GradientDrawableGradient_centerX, st.mCenterX);
        st.mCenterY = getFloatOrFraction(
                a, R.styleable.GradientDrawableGradient_centerY, st.mCenterY);
        st.mUseLevel = a.getBoolean(
                R.styleable.GradientDrawableGradient_useLevel, st.mUseLevel);
        st.mGradient = a.getInt(
                R.styleable.GradientDrawableGradient_type, st.mGradient);

        // TODO: Update these to be themeable.
        final int startColor = a.getColor(
                R.styleable.GradientDrawableGradient_startColor, 0);
        final boolean hasCenterColor = a.hasValue(
                R.styleable.GradientDrawableGradient_centerColor);
        final int centerColor = a.getColor(
                R.styleable.GradientDrawableGradient_centerColor, 0);
        final int endColor = a.getColor(
                R.styleable.GradientDrawableGradient_endColor, 0);

        if (hasCenterColor) {
            st.mGradientColors = new int[3];
            st.mGradientColors[0] = startColor;
            st.mGradientColors[1] = centerColor;
            st.mGradientColors[2] = endColor;

            st.mPositions = new float[3];
            st.mPositions[0] = 0.0f;
            // Since 0.5f is default value, try to take the one that isn't 0.5f
            st.mPositions[1] = st.mCenterX != 0.5f ? st.mCenterX : st.mCenterY;
            st.mPositions[2] = 1f;
        } else {
            st.mGradientColors = new int[2];
            st.mGradientColors[0] = startColor;
            st.mGradientColors[1] = endColor;
        }

        if (st.mGradient == LINEAR_GRADIENT) {
            int angle = (int) a.getFloat(R.styleable.GradientDrawableGradient_angle, st.mAngle);
            angle %= 360;

            if (angle % 45 != 0) {
                throw new XmlPullParserException(a.getPositionDescription()
                        + "<gradient> tag requires 'angle' attribute to "
                        + "be a multiple of 45");
            }

            st.mAngle = angle;

            switch (angle) {
                case 0:
                    st.mOrientation = Orientation.LEFT_RIGHT;
                    break;
                case 45:
                    st.mOrientation = Orientation.BL_TR;
                    break;
                case 90:
                    st.mOrientation = Orientation.BOTTOM_TOP;
                    break;
                case 135:
                    st.mOrientation = Orientation.BR_TL;
                    break;
                case 180:
                    st.mOrientation = Orientation.RIGHT_LEFT;
                    break;
                case 225:
                    st.mOrientation = Orientation.TR_BL;
                    break;
                case 270:
                    st.mOrientation = Orientation.TOP_BOTTOM;
                    break;
                case 315:
                    st.mOrientation = Orientation.TL_BR;
                    break;
            }
        } else {
            final TypedValue tv = a.peekValue(R.styleable.GradientDrawableGradient_gradientRadius);
            if (tv != null) {
                final float radius;
                final @RadiusType int radiusType;
                if (tv.type == TypedValue.TYPE_FRACTION) {
                    radius = tv.getFraction(1.0f, 1.0f);

                    final int unit = (tv.data >> TypedValue.COMPLEX_UNIT_SHIFT)
                            & TypedValue.COMPLEX_UNIT_MASK;
                    if (unit == TypedValue.COMPLEX_UNIT_FRACTION_PARENT) {
                        radiusType = RADIUS_TYPE_FRACTION_PARENT;
                    } else {
                        radiusType = RADIUS_TYPE_FRACTION;
                    }
                } else if (tv.type == TypedValue.TYPE_DIMENSION) {
                    radius = tv.getDimension(r.getDisplayMetrics());
                    radiusType = RADIUS_TYPE_PIXELS;
                } else {
                    radius = tv.getFloat();
                    radiusType = RADIUS_TYPE_PIXELS;
                }

                st.mGradientRadius = radius;
                st.mGradientRadiusType = radiusType;
            } else if (st.mGradient == RADIAL_GRADIENT) {
                throw new XmlPullParserException(
                        a.getPositionDescription()
                        + "<gradient> tag requires 'gradientRadius' "
                        + "attribute with radial type");
            }
        }
    }

    private void updateGradientDrawableSize(TypedArray a) {
        final GradientState st = mGradientState;

        // Account for any configuration changes.
        st.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        st.mAttrSize = a.extractThemeAttrs();

        st.mWidth = a.getDimensionPixelSize(R.styleable.GradientDrawableSize_width, st.mWidth);
        st.mHeight = a.getDimensionPixelSize(R.styleable.GradientDrawableSize_height, st.mHeight);
    }

    private static float getFloatOrFraction(TypedArray a, int index, float defaultValue) {
        TypedValue tv = a.peekValue(index);
        float v = defaultValue;
        if (tv != null) {
            boolean vIsFraction = tv.type == TypedValue.TYPE_FRACTION;
            v = vIsFraction ? tv.getFraction(1.0f, 1.0f) : tv.getFloat();
        }
        return v;
    }

    @Override
    public int getIntrinsicWidth() {
        return mGradientState.mWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mGradientState.mHeight;
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        return mGradientState.mOpticalInsets;
    }

    @Override
    public ConstantState getConstantState() {
        mGradientState.mChangingConfigurations = getChangingConfigurations();
        return mGradientState;
    }

    private boolean isOpaqueForState() {
        if (mGradientState.mStrokeWidth >= 0 && mStrokePaint != null
                && !isOpaque(mStrokePaint.getColor())) {
            return false;
        }

        // Don't check opacity if we're using a gradient, as we've already
        // checked the gradient opacity in mOpaqueOverShape.
        if (mGradientState.mGradientColors == null && !isOpaque(mFillPaint.getColor())) {
            return false;
        }

        return true;
    }

    @Override
    public void getOutline(Outline outline) {
        final GradientState st = mGradientState;
        final Rect bounds = getBounds();
        // only report non-zero alpha if shape being drawn has consistent opacity over shape. Must
        // either not have a stroke, or have same stroke/fill opacity
        boolean useFillOpacity = st.mOpaqueOverShape && (mGradientState.mStrokeWidth <= 0
                || mStrokePaint == null
                || mStrokePaint.getAlpha() == mFillPaint.getAlpha());
        outline.setAlpha(useFillOpacity
                ? modulateAlpha(mFillPaint.getAlpha()) / 255.0f
                : 0.0f);

        switch (st.mShape) {
            case RECTANGLE:
                if (st.mRadiusArray != null) {
                    buildPathIfDirty();
                    outline.setConvexPath(mPath);
                    return;
                }

                float rad = 0;
                if (st.mRadius > 0.0f) {
                    // clamp the radius based on width & height, matching behavior in draw()
                    rad = Math.min(st.mRadius,
                            Math.min(bounds.width(), bounds.height()) * 0.5f);
                }
                outline.setRoundRect(bounds, rad);
                return;
            case OVAL:
                outline.setOval(bounds);
                return;
            case LINE:
                // Hairlines (0-width stroke) must have a non-empty outline for
                // shadows to draw correctly, so we'll use a very small width.
                final float halfStrokeWidth = mStrokePaint == null ?
                        0.0001f : mStrokePaint.getStrokeWidth() * 0.5f;
                final float centerY = bounds.centerY();
                final int top = (int) Math.floor(centerY - halfStrokeWidth);
                final int bottom = (int) Math.ceil(centerY + halfStrokeWidth);

                outline.setRect(bounds.left, top, bounds.right, bottom);
                return;
            default:
                // TODO: support more complex shapes
        }
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mGradientState = new GradientState(mGradientState, null);
            updateLocalState(null);
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mMutated = false;
    }

    final static class GradientState extends ConstantState {
        public @Config int mChangingConfigurations;
        public @Shape int mShape = RECTANGLE;
        public @GradientType int mGradient = LINEAR_GRADIENT;
        public int mAngle = 0;
        public Orientation mOrientation;
        public ColorStateList mSolidColors;
        public ColorStateList mStrokeColors;
        public @ColorInt int[] mGradientColors;
        public @ColorInt int[] mTempColors; // no need to copy
        public float[] mTempPositions; // no need to copy
        public float[] mPositions;
        public int mStrokeWidth = -1; // if >= 0 use stroking.
        public float mStrokeDashWidth = 0.0f;
        public float mStrokeDashGap = 0.0f;
        public float mRadius = 0.0f; // use this if mRadiusArray is null
        public float[] mRadiusArray = null;
        public Rect mPadding = null;
        public int mWidth = -1;
        public int mHeight = -1;
        public float mInnerRadiusRatio = DEFAULT_INNER_RADIUS_RATIO;
        public float mThicknessRatio = DEFAULT_THICKNESS_RATIO;
        public int mInnerRadius = -1;
        public int mThickness = -1;
        public boolean mDither = false;
        public Insets mOpticalInsets = Insets.NONE;

        float mCenterX = 0.5f;
        float mCenterY = 0.5f;
        float mGradientRadius = 0.5f;
        @RadiusType int mGradientRadiusType = RADIUS_TYPE_PIXELS;
        boolean mUseLevel = false;
        boolean mUseLevelForShape = true;

        boolean mOpaqueOverBounds;
        boolean mOpaqueOverShape;

        ColorStateList mTint = null;
        PorterDuff.Mode mTintMode = DEFAULT_TINT_MODE;

        int mDensity = DisplayMetrics.DENSITY_DEFAULT;

        int[] mThemeAttrs;
        int[] mAttrSize;
        int[] mAttrGradient;
        int[] mAttrSolid;
        int[] mAttrStroke;
        int[] mAttrCorners;
        int[] mAttrPadding;

        public GradientState(Orientation orientation, int[] gradientColors) {
            mOrientation = orientation;
            setGradientColors(gradientColors);
        }

        public GradientState(@NonNull GradientState orig, @Nullable Resources res) {
            mChangingConfigurations = orig.mChangingConfigurations;
            mShape = orig.mShape;
            mGradient = orig.mGradient;
            mAngle = orig.mAngle;
            mOrientation = orig.mOrientation;
            mSolidColors = orig.mSolidColors;
            if (orig.mGradientColors != null) {
                mGradientColors = orig.mGradientColors.clone();
            }
            if (orig.mPositions != null) {
                mPositions = orig.mPositions.clone();
            }
            mStrokeColors = orig.mStrokeColors;
            mStrokeWidth = orig.mStrokeWidth;
            mStrokeDashWidth = orig.mStrokeDashWidth;
            mStrokeDashGap = orig.mStrokeDashGap;
            mRadius = orig.mRadius;
            if (orig.mRadiusArray != null) {
                mRadiusArray = orig.mRadiusArray.clone();
            }
            if (orig.mPadding != null) {
                mPadding = new Rect(orig.mPadding);
            }
            mWidth = orig.mWidth;
            mHeight = orig.mHeight;
            mInnerRadiusRatio = orig.mInnerRadiusRatio;
            mThicknessRatio = orig.mThicknessRatio;
            mInnerRadius = orig.mInnerRadius;
            mThickness = orig.mThickness;
            mDither = orig.mDither;
            mOpticalInsets = orig.mOpticalInsets;
            mCenterX = orig.mCenterX;
            mCenterY = orig.mCenterY;
            mGradientRadius = orig.mGradientRadius;
            mGradientRadiusType = orig.mGradientRadiusType;
            mUseLevel = orig.mUseLevel;
            mUseLevelForShape = orig.mUseLevelForShape;
            mOpaqueOverBounds = orig.mOpaqueOverBounds;
            mOpaqueOverShape = orig.mOpaqueOverShape;
            mTint = orig.mTint;
            mTintMode = orig.mTintMode;
            mThemeAttrs = orig.mThemeAttrs;
            mAttrSize = orig.mAttrSize;
            mAttrGradient = orig.mAttrGradient;
            mAttrSolid = orig.mAttrSolid;
            mAttrStroke = orig.mAttrStroke;
            mAttrCorners = orig.mAttrCorners;
            mAttrPadding = orig.mAttrPadding;

            mDensity = Drawable.resolveDensity(res, orig.mDensity);
            if (orig.mDensity != mDensity) {
                applyDensityScaling(orig.mDensity, mDensity);
            }
        }

        /**
         * Sets the constant state density.
         * <p>
         * If the density has been previously set, dispatches the change to
         * subclasses so that density-dependent properties may be scaled as
         * necessary.
         *
         * @param targetDensity the new constant state density
         */
        public final void setDensity(int targetDensity) {
            if (mDensity != targetDensity) {
                final int sourceDensity = mDensity;
                mDensity = targetDensity;

                applyDensityScaling(sourceDensity, targetDensity);
            }
        }

        private void applyDensityScaling(int sourceDensity, int targetDensity) {
            if (mInnerRadius > 0) {
                mInnerRadius = Drawable.scaleFromDensity(
                        mInnerRadius, sourceDensity, targetDensity, true);
            }
            if (mThickness > 0) {
                mThickness = Drawable.scaleFromDensity(
                        mThickness, sourceDensity, targetDensity, true);
            }
            if (mOpticalInsets != Insets.NONE) {
                final int left = Drawable.scaleFromDensity(
                        mOpticalInsets.left, sourceDensity, targetDensity, true);
                final int top = Drawable.scaleFromDensity(
                        mOpticalInsets.top, sourceDensity, targetDensity, true);
                final int right = Drawable.scaleFromDensity(
                        mOpticalInsets.right, sourceDensity, targetDensity, true);
                final int bottom = Drawable.scaleFromDensity(
                        mOpticalInsets.bottom, sourceDensity, targetDensity, true);
                mOpticalInsets = Insets.of(left, top, right, bottom);
            }
            if (mPadding != null) {
                mPadding.left = Drawable.scaleFromDensity(
                        mPadding.left, sourceDensity, targetDensity, false);
                mPadding.top = Drawable.scaleFromDensity(
                        mPadding.top, sourceDensity, targetDensity, false);
                mPadding.right = Drawable.scaleFromDensity(
                        mPadding.right, sourceDensity, targetDensity, false);
                mPadding.bottom = Drawable.scaleFromDensity(
                        mPadding.bottom, sourceDensity, targetDensity, false);
            }
            if (mRadius > 0) {
                mRadius = Drawable.scaleFromDensity(mRadius, sourceDensity, targetDensity);
            }
            if (mRadiusArray != null) {
                mRadiusArray[0] = Drawable.scaleFromDensity(
                        (int) mRadiusArray[0], sourceDensity, targetDensity, true);
                mRadiusArray[1] = Drawable.scaleFromDensity(
                        (int) mRadiusArray[1], sourceDensity, targetDensity, true);
                mRadiusArray[2] = Drawable.scaleFromDensity(
                        (int) mRadiusArray[2], sourceDensity, targetDensity, true);
                mRadiusArray[3] = Drawable.scaleFromDensity(
                        (int) mRadiusArray[3], sourceDensity, targetDensity, true);
            }
            if (mStrokeWidth > 0) {
                mStrokeWidth = Drawable.scaleFromDensity(
                        mStrokeWidth, sourceDensity, targetDensity, true);
            }
            if (mStrokeDashWidth > 0) {
                mStrokeDashWidth = Drawable.scaleFromDensity(
                        mStrokeDashGap, sourceDensity, targetDensity);
            }
            if (mStrokeDashGap > 0) {
                mStrokeDashGap = Drawable.scaleFromDensity(
                        mStrokeDashGap, sourceDensity, targetDensity);
            }
            if (mGradientRadiusType == RADIUS_TYPE_PIXELS) {
                mGradientRadius = Drawable.scaleFromDensity(
                        mGradientRadius, sourceDensity, targetDensity);
            }
            if (mWidth > 0) {
                mWidth = Drawable.scaleFromDensity(mWidth, sourceDensity, targetDensity, true);
            }
            if (mHeight > 0) {
                mHeight = Drawable.scaleFromDensity(mHeight, sourceDensity, targetDensity, true);
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || mAttrSize != null || mAttrGradient != null
                    || mAttrSolid != null || mAttrStroke != null
                    || mAttrCorners != null || mAttrPadding != null
                    || (mTint != null && mTint.canApplyTheme())
                    || (mStrokeColors != null && mStrokeColors.canApplyTheme())
                    || (mSolidColors != null && mSolidColors.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new GradientDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(@Nullable Resources res) {
            // If this drawable is being created for a different density,
            // just create a new constant state and call it a day.
            final GradientState state;
            final int density = Drawable.resolveDensity(res, mDensity);
            if (density != mDensity) {
                state = new GradientState(this, res);
            } else {
                state = this;
            }

            return new GradientDrawable(state, res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mStrokeColors != null ? mStrokeColors.getChangingConfigurations() : 0)
                    | (mSolidColors != null ? mSolidColors.getChangingConfigurations() : 0)
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }

        public void setShape(@Shape int shape) {
            mShape = shape;
            computeOpacity();
        }

        public void setGradientType(@GradientType int gradient) {
            mGradient = gradient;
        }

        public void setGradientCenter(float x, float y) {
            mCenterX = x;
            mCenterY = y;
        }

        public void setGradientColors(@Nullable int[] colors) {
            mGradientColors = colors;
            mSolidColors = null;
            computeOpacity();
        }

        public void setSolidColors(@Nullable ColorStateList colors) {
            mGradientColors = null;
            mSolidColors = colors;
            computeOpacity();
        }

        private void computeOpacity() {
            mOpaqueOverBounds = false;
            mOpaqueOverShape = false;

            if (mGradientColors != null) {
                for (int i = 0; i < mGradientColors.length; i++) {
                    if (!isOpaque(mGradientColors[i])) {
                        return;
                    }
                }
            }

            // An unfilled shape is not opaque over bounds or shape
            if (mGradientColors == null && mSolidColors == null) {
                return;
            }

            // Colors are opaque, so opaqueOverShape=true,
            mOpaqueOverShape = true;
            // and opaqueOverBounds=true if shape fills bounds
            mOpaqueOverBounds = mShape == RECTANGLE
                    && mRadius <= 0
                    && mRadiusArray == null;
        }

        public void setStroke(int width, @Nullable ColorStateList colors, float dashWidth,
                float dashGap) {
            mStrokeWidth = width;
            mStrokeColors = colors;
            mStrokeDashWidth = dashWidth;
            mStrokeDashGap = dashGap;
            computeOpacity();
        }

        public void setCornerRadius(float radius) {
            if (radius < 0) {
                radius = 0;
            }
            mRadius = radius;
            mRadiusArray = null;
        }

        public void setCornerRadii(float[] radii) {
            mRadiusArray = radii;
            if (radii == null) {
                mRadius = 0;
            }
        }

        public void setSize(int width, int height) {
            mWidth = width;
            mHeight = height;
        }

        public void setGradientRadius(float gradientRadius, @RadiusType int type) {
            mGradientRadius = gradientRadius;
            mGradientRadiusType = type;
        }
    }

    static boolean isOpaque(int color) {
        return ((color >> 24) & 0xff) == 0xff;
    }

    /**
     * Creates a new themed GradientDrawable based on the specified constant state.
     * <p>
     * The resulting drawable is guaranteed to have a new constant state.
     *
     * @param state Constant state from which the drawable inherits
     */
    private GradientDrawable(@NonNull GradientState state, @Nullable Resources res) {
        mGradientState = state;

        updateLocalState(res);
    }

    private void updateLocalState(Resources res) {
        final GradientState state = mGradientState;

        if (state.mSolidColors != null) {
            final int[] currentState = getState();
            final int stateColor = state.mSolidColors.getColorForState(currentState, 0);
            mFillPaint.setColor(stateColor);
        } else if (state.mGradientColors == null) {
            // If we don't have a solid color and we don't have a gradient,
            // the app is stroking the shape, set the color to the default
            // value of state.mSolidColor
            mFillPaint.setColor(0);
        } else {
            // Otherwise, make sure the fill alpha is maxed out.
            mFillPaint.setColor(Color.BLACK);
        }

        mPadding = state.mPadding;

        if (state.mStrokeWidth >= 0) {
            mStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mStrokePaint.setStyle(Paint.Style.STROKE);
            mStrokePaint.setStrokeWidth(state.mStrokeWidth);

            if (state.mStrokeColors != null) {
                final int[] currentState = getState();
                final int strokeStateColor = state.mStrokeColors.getColorForState(
                        currentState, 0);
                mStrokePaint.setColor(strokeStateColor);
            }

            if (state.mStrokeDashWidth != 0.0f) {
                final DashPathEffect e = new DashPathEffect(
                        new float[] { state.mStrokeDashWidth, state.mStrokeDashGap }, 0);
                mStrokePaint.setPathEffect(e);
            }
        }

        mTintFilter = updateTintFilter(mTintFilter, state.mTint, state.mTintMode);
        mGradientIsDirty = true;

        state.computeOpacity();
    }
}
