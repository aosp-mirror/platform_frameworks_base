/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.pm.ActivityInfo.Config;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Xfermode;
import android.graphics.drawable.shapes.Shape;
import android.util.AttributeSet;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * A Drawable object that draws primitive shapes. A ShapeDrawable takes a
 * {@link android.graphics.drawable.shapes.Shape} object and manages its
 * presence on the screen. If no Shape is given, then the ShapeDrawable will
 * default to a {@link android.graphics.drawable.shapes.RectShape}.
 * <p>
 * This object can be defined in an XML file with the <code>&lt;shape></code>
 * element.
 * </p>
 * <div class="special reference"> <h3>Developer Guides</h3>
 * <p>
 * For more information about how to use ShapeDrawable, read the <a
 * href="{@docRoot}guide/topics/graphics/2d-graphics.html#shape-drawable">
 * Canvas and Drawables</a> document. For more information about defining a
 * ShapeDrawable in XML, read the
 * <a href="{@docRoot}guide/topics/resources/drawable-resource.html#Shape">
 * Drawable Resources</a> document.
 * </p>
 * </div>
 *
 * @attr ref android.R.styleable#ShapeDrawablePadding_left
 * @attr ref android.R.styleable#ShapeDrawablePadding_top
 * @attr ref android.R.styleable#ShapeDrawablePadding_right
 * @attr ref android.R.styleable#ShapeDrawablePadding_bottom
 * @attr ref android.R.styleable#ShapeDrawable_color
 * @attr ref android.R.styleable#ShapeDrawable_width
 * @attr ref android.R.styleable#ShapeDrawable_height
 */
public class ShapeDrawable extends Drawable {
    private @NonNull ShapeState mShapeState;
    private BlendModeColorFilter mBlendModeColorFilter;
    private boolean mMutated;

    /**
     * ShapeDrawable constructor.
     */
    public ShapeDrawable() {
        this(new ShapeState(), null);
    }

    /**
     * Creates a ShapeDrawable with a specified Shape.
     *
     * @param s the Shape that this ShapeDrawable should be
     */
    public ShapeDrawable(Shape s) {
        this(new ShapeState(), null);

        mShapeState.mShape = s;
    }

    /**
     * Returns the Shape of this ShapeDrawable.
     */
    public Shape getShape() {
        return mShapeState.mShape;
    }

    /**
     * Sets the Shape of this ShapeDrawable.
     */
    public void setShape(Shape s) {
        mShapeState.mShape = s;
        updateShape();
    }

    /**
     * Sets a ShaderFactory to which requests for a
     * {@link android.graphics.Shader} object will be made.
     *
     * @param fact an instance of your ShaderFactory implementation
     */
    public void setShaderFactory(ShaderFactory fact) {
        mShapeState.mShaderFactory = fact;
    }

    /**
     * Returns the ShaderFactory used by this ShapeDrawable for requesting a
     * {@link android.graphics.Shader}.
     */
    public ShaderFactory getShaderFactory() {
        return mShapeState.mShaderFactory;
    }

    /**
     * Returns the Paint used to draw the shape.
     */
    public Paint getPaint() {
        return mShapeState.mPaint;
    }

    /**
     * Sets padding for the shape.
     *
     * @param left padding for the left side (in pixels)
     * @param top padding for the top (in pixels)
     * @param right padding for the right side (in pixels)
     * @param bottom padding for the bottom (in pixels)
     */
    public void setPadding(int left, int top, int right, int bottom) {
        if ((left | top | right | bottom) == 0) {
            mShapeState.mPadding = null;
        } else {
            if (mShapeState.mPadding == null) {
                mShapeState.mPadding = new Rect();
            }
            mShapeState.mPadding.set(left, top, right, bottom);
        }
        invalidateSelf();
    }

    /**
     * Sets padding for this shape, defined by a Rect object. Define the padding
     * in the Rect object as: left, top, right, bottom.
     */
    public void setPadding(Rect padding) {
        if (padding == null) {
            mShapeState.mPadding = null;
        } else {
            if (mShapeState.mPadding == null) {
                mShapeState.mPadding = new Rect();
            }
            mShapeState.mPadding.set(padding);
        }
        invalidateSelf();
    }

    /**
     * Sets the intrinsic (default) width for this shape.
     *
     * @param width the intrinsic width (in pixels)
     */
    public void setIntrinsicWidth(int width) {
        mShapeState.mIntrinsicWidth = width;
        invalidateSelf();
    }

    /**
     * Sets the intrinsic (default) height for this shape.
     *
     * @param height the intrinsic height (in pixels)
     */
    public void setIntrinsicHeight(int height) {
        mShapeState.mIntrinsicHeight = height;
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return mShapeState.mIntrinsicWidth;
    }

    @Override
    public int getIntrinsicHeight() {
        return mShapeState.mIntrinsicHeight;
    }

    @Override
    public boolean getPadding(Rect padding) {
        if (mShapeState.mPadding != null) {
            padding.set(mShapeState.mPadding);
            return true;
        } else {
            return super.getPadding(padding);
        }
    }

    private static int modulateAlpha(int paintAlpha, int alpha) {
        int scale = alpha + (alpha >>> 7); // convert to 0..256
        return paintAlpha * scale >>> 8;
    }

    /**
     * Called from the drawable's draw() method after the canvas has been set to
     * draw the shape at (0,0). Subclasses can override for special effects such
     * as multiple layers, stroking, etc.
     */
    protected void onDraw(Shape shape, Canvas canvas, Paint paint) {
        shape.draw(canvas, paint);
    }

    @Override
    public void draw(Canvas canvas) {
        final Rect r = getBounds();
        final ShapeState state = mShapeState;
        final Paint paint = state.mPaint;

        final int prevAlpha = paint.getAlpha();
        paint.setAlpha(modulateAlpha(prevAlpha, state.mAlpha));

        // only draw shape if it may affect output
        if (paint.getAlpha() != 0 || paint.getXfermode() != null || paint.hasShadowLayer()) {
            final boolean clearColorFilter;
            if (mBlendModeColorFilter != null && paint.getColorFilter() == null) {
                paint.setColorFilter(mBlendModeColorFilter);
                clearColorFilter = true;
            } else {
                clearColorFilter = false;
            }

            if (state.mShape != null) {
                // need the save both for the translate, and for the (unknown)
                // Shape
                final int count = canvas.save();
                canvas.translate(r.left, r.top);
                onDraw(state.mShape, canvas, paint);
                canvas.restoreToCount(count);
            } else {
                canvas.drawRect(r, paint);
            }

            if (clearColorFilter) {
                paint.setColorFilter(null);
            }
        }

        // restore
        paint.setAlpha(prevAlpha);
    }

    @Override
    public @Config int getChangingConfigurations() {
        return super.getChangingConfigurations() | mShapeState.getChangingConfigurations();
    }

    /**
     * Set the alpha level for this drawable [0..255]. Note that this drawable
     * also has a color in its paint, which has an alpha as well. These two
     * values are automatically combined during drawing. Thus if the color's
     * alpha is 75% (i.e. 192) and the drawable's alpha is 50% (i.e. 128), then
     * the combined alpha that will be used during drawing will be 37.5% (i.e.
     * 96).
     */
    @Override
    public void setAlpha(int alpha) {
        mShapeState.mAlpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return mShapeState.mAlpha;
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mShapeState.mTint = tint;
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, tint,
                mShapeState.mBlendMode);
        invalidateSelf();
    }

    @Override
    public void setTintBlendMode(@NonNull BlendMode blendMode) {
        mShapeState.mBlendMode = blendMode;
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, mShapeState.mTint,
                blendMode);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mShapeState.mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    /**
     * @hide
     */
    @Override
    @TestApi
    public void setXfermode(@Nullable Xfermode mode) {
        mShapeState.mPaint.setXfermode(mode);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        if (mShapeState.mShape == null) {
            final Paint p = mShapeState.mPaint;
            if (p.getXfermode() == null) {
                final int alpha = p.getAlpha();
                if (alpha == 0) {
                    return PixelFormat.TRANSPARENT;
                }
                if (alpha == 255) {
                    return PixelFormat.OPAQUE;
                }
            }
        }
        // not sure, so be safe
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void setDither(boolean dither) {
        mShapeState.mPaint.setDither(dither);
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        updateShape();
    }

    @Override
    protected boolean onStateChange(int[] stateSet) {
        final ShapeState state = mShapeState;
        if (state.mTint != null && state.mBlendMode != null) {
            mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, state.mTint,
                    state.mBlendMode);
            return true;
        }
        return false;
    }

    @Override
    public boolean isStateful() {
        final ShapeState s = mShapeState;
        return super.isStateful() || (s.mTint != null && s.mTint.isStateful());
    }

    /** @hide */
    @Override
    public boolean hasFocusStateSpecified() {
        return mShapeState.mTint != null && mShapeState.mTint.hasFocusStateSpecified();
    }

    /**
     * Subclasses override this to parse custom subelements. If you handle it,
     * return true, else return <em>super.inflateTag(...)</em>.
     */
    protected boolean inflateTag(String name, Resources r, XmlPullParser parser,
            AttributeSet attrs) {

        if ("padding".equals(name)) {
            TypedArray a = r.obtainAttributes(attrs,
                    com.android.internal.R.styleable.ShapeDrawablePadding);
            setPadding(
                    a.getDimensionPixelOffset(
                            com.android.internal.R.styleable.ShapeDrawablePadding_left, 0),
                    a.getDimensionPixelOffset(
                            com.android.internal.R.styleable.ShapeDrawablePadding_top, 0),
                    a.getDimensionPixelOffset(
                            com.android.internal.R.styleable.ShapeDrawablePadding_right, 0),
                    a.getDimensionPixelOffset(
                            com.android.internal.R.styleable.ShapeDrawablePadding_bottom, 0));
            a.recycle();
            return true;
        }

        return false;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ShapeDrawable);
        updateStateFromTypedArray(a);
        a.recycle();

        int type;
        final int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            final String name = parser.getName();
            // call our subclass
            if (!inflateTag(name, r, parser, attrs)) {
                android.util.Log.w("drawable", "Unknown element: " + name +
                        " for ShapeDrawable " + this);
            }
        }

        // Update local properties.
        updateLocalState();
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final ShapeState state = mShapeState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.ShapeDrawable);
            updateStateFromTypedArray(a);
            a.recycle();
        }

        // Apply theme to contained color state list.
        if (state.mTint != null && state.mTint.canApplyTheme()) {
            state.mTint = state.mTint.obtainForTheme(t);
        }

        // Update local properties.
        updateLocalState();
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final ShapeState state = mShapeState;
        final Paint paint = state.mPaint;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        int color = paint.getColor();
        color = a.getColor(R.styleable.ShapeDrawable_color, color);
        paint.setColor(color);

        boolean dither = paint.isDither();
        dither = a.getBoolean(R.styleable.ShapeDrawable_dither, dither);
        paint.setDither(dither);

        state.mIntrinsicWidth = (int) a.getDimension(
                R.styleable.ShapeDrawable_width, state.mIntrinsicWidth);
        state.mIntrinsicHeight = (int) a.getDimension(
                R.styleable.ShapeDrawable_height, state.mIntrinsicHeight);

        final int tintMode = a.getInt(R.styleable.ShapeDrawable_tintMode, -1);
        if (tintMode != -1) {
            state.mBlendMode = Drawable.parseBlendMode(tintMode, BlendMode.SRC_IN);
        }

        final ColorStateList tint = a.getColorStateList(R.styleable.ShapeDrawable_tint);
        if (tint != null) {
            state.mTint = tint;
        }
    }

    private void updateShape() {
        if (mShapeState.mShape != null) {
            final Rect r = getBounds();
            final int w = r.width();
            final int h = r.height();

            mShapeState.mShape.resize(w, h);
            if (mShapeState.mShaderFactory != null) {
                mShapeState.mPaint.setShader(mShapeState.mShaderFactory.resize(w, h));
            }
        }
        invalidateSelf();
    }

    @Override
    public void getOutline(Outline outline) {
        if (mShapeState.mShape != null) {
            mShapeState.mShape.getOutline(outline);
            outline.setAlpha(getAlpha() / 255.0f);
        }
    }

    @Override
    public ConstantState getConstantState() {
        mShapeState.mChangingConfigurations = getChangingConfigurations();
        return mShapeState;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mShapeState = new ShapeState(mShapeState);
            updateLocalState();
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

    /**
     * Defines the intrinsic properties of this ShapeDrawable's Shape.
     */
    static final class ShapeState extends ConstantState {
        final @NonNull Paint mPaint;

        @Config int mChangingConfigurations;
        int[] mThemeAttrs;
        Shape mShape;
        ColorStateList mTint;
        BlendMode mBlendMode = DEFAULT_BLEND_MODE;
        Rect mPadding;
        int mIntrinsicWidth;
        int mIntrinsicHeight;
        int mAlpha = 255;
        ShaderFactory mShaderFactory;

        /**
         * Constructs a new ShapeState.
         */
        ShapeState() {
            mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        /**
         * Constructs a new ShapeState that contains a deep copy of the
         * specified ShapeState.
         *
         * @param orig the state to create a deep copy of
         */
        ShapeState(@NonNull ShapeState orig) {
            mChangingConfigurations = orig.mChangingConfigurations;
            mPaint = new Paint(orig.mPaint);
            mThemeAttrs = orig.mThemeAttrs;
            if (orig.mShape != null) {
                try {
                    mShape = orig.mShape.clone();
                } catch (CloneNotSupportedException e) {
                    // Well, at least we tried.
                    mShape = orig.mShape;
                }
            }
            mTint = orig.mTint;
            mBlendMode = orig.mBlendMode;
            if (orig.mPadding != null) {
                mPadding = new Rect(orig.mPadding);
            }
            mIntrinsicWidth = orig.mIntrinsicWidth;
            mIntrinsicHeight = orig.mIntrinsicHeight;
            mAlpha = orig.mAlpha;

            // We don't have any way to clone a shader factory, so hopefully
            // this class doesn't contain any local state.
            mShaderFactory = orig.mShaderFactory;
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mTint != null && mTint.canApplyTheme());
        }

        @Override
        public Drawable newDrawable() {
            return new ShapeDrawable(new ShapeState(this), null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ShapeDrawable(new ShapeState(this), res);
        }

        @Override
        public @Config int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mTint != null ? mTint.getChangingConfigurations() : 0);
        }
    }

    /**
     * The one constructor to rule them all. This is called by all public
     * constructors to set the state and initialize local properties.
     */
    private ShapeDrawable(ShapeState state, Resources res) {
        mShapeState = state;

        updateLocalState();
    }

    /**
     * Initializes local dynamic properties from state. This should be called
     * after significant state changes, e.g. from the One True Constructor and
     * after inflating or applying a theme.
     */
    private void updateLocalState() {
        mBlendModeColorFilter = updateBlendModeFilter(mBlendModeColorFilter, mShapeState.mTint,
                mShapeState.mBlendMode);
    }

    /**
     * Base class defines a factory object that is called each time the drawable
     * is resized (has a new width or height). Its resize() method returns a
     * corresponding shader, or null. Implement this class if you'd like your
     * ShapeDrawable to use a special {@link android.graphics.Shader}, such as a
     * {@link android.graphics.LinearGradient}.
     */
    public static abstract class ShaderFactory {
        /**
         * Returns the Shader to be drawn when a Drawable is drawn. The
         * dimensions of the Drawable are passed because they may be needed to
         * adjust how the Shader is configured for drawing. This is called by
         * ShapeDrawable.setShape().
         *
         * @param width the width of the Drawable being drawn
         * @param height the heigh of the Drawable being drawn
         * @return the Shader to be drawn
         */
        public abstract Shader resize(int width, int height);
    }
}
