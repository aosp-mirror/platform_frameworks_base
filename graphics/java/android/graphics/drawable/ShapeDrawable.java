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

import android.graphics.*;
import android.graphics.drawable.shapes.Shape;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * A Drawable object that draws primitive shapes. 
 * A ShapeDrawable takes a {@link android.graphics.drawable.shapes.Shape}
 * object and manages its presence on the screen. If no Shape is given, then
 * the ShapeDrawable will default to a 
 * {@link android.graphics.drawable.shapes.RectShape}.
 *
 * <p>It can be defined in an XML file with the <code>&lt;shape></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
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
    private ShapeState mShapeState;
    private boolean mMutated;

    /**
     * ShapeDrawable constructor.
     */
    public ShapeDrawable() {
        this((ShapeState) null);
    }
    
    /**
     * Creates a ShapeDrawable with a specified Shape.
     * 
     * @param s the Shape that this ShapeDrawable should be
     */
    public ShapeDrawable(Shape s) {
        this((ShapeState) null);
        
        mShapeState.mShape = s;
    }
    
    private ShapeDrawable(ShapeState state) {
        mShapeState = new ShapeState(state);
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
     * @param left    padding for the left side (in pixels)
     * @param top     padding for the top (in pixels)
     * @param right   padding for the right side (in pixels)
     * @param bottom  padding for the bottom (in pixels)
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
    }
    
    /**
     * Sets padding for this shape, defined by a Rect object.
     * Define the padding in the Rect object as: left, top, right, bottom.
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
    }
    
    /**
     * Sets the intrinsic (default) width for this shape.
     * 
     * @param width the intrinsic width (in pixels)
     */
    public void setIntrinsicWidth(int width) {
        mShapeState.mIntrinsicWidth = width;
    }
    
    /**
     * Sets the intrinsic (default) height for this shape.
     * 
     * @param height the intrinsic height (in pixels)
     */
    public void setIntrinsicHeight(int height) {
        mShapeState.mIntrinsicHeight = height;
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
        int scale = alpha + (alpha >>> 7);  // convert to 0..256
        return paintAlpha * scale >>> 8;
    }

    /**
     * Called from the drawable's draw() method after the canvas has been set
     * to draw the shape at (0,0). Subclasses can override for special effects
     * such as multiple layers, stroking, etc.
     */
    protected void onDraw(Shape shape, Canvas canvas, Paint paint) {
        shape.draw(canvas, paint);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect r = getBounds();
        Paint paint = mShapeState.mPaint;

        int prevAlpha = paint.getAlpha();
        paint.setAlpha(modulateAlpha(prevAlpha, mShapeState.mAlpha));

        if (mShapeState.mShape != null) {
            // need the save both for the translate, and for the (unknown) Shape
            int count = canvas.save();
            canvas.translate(r.left, r.top);
            onDraw(mShapeState.mShape, canvas, paint);
            canvas.restoreToCount(count);
        } else {
            canvas.drawRect(r, paint);
        }
        
        // restore
        paint.setAlpha(prevAlpha);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mShapeState.mChangingConfigurations;
    }
    
    /**
     * Set the alpha level for this drawable [0..255]. Note that this drawable
     * also has a color in its paint, which has an alpha as well. These two
     * values are automatically combined during drawing. Thus if the color's
     * alpha is 75% (i.e. 192) and the drawable's alpha is 50% (i.e. 128), then
     * the combined alpha that will be used during drawing will be 37.5%
     * (i.e. 96).
     */
    @Override public void setAlpha(int alpha) {
        mShapeState.mAlpha = alpha;
    }
    
    @Override
    public void setColorFilter(ColorFilter cf) {
        mShapeState.mPaint.setColorFilter(cf);
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
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        updateShape();
    }

    /**
     * Subclasses override this to parse custom subelements.
     * If you handle it, return true, else return <em>super.inflateTag(...)</em>.
     */
    protected boolean inflateTag(String name, Resources r, XmlPullParser parser,
            AttributeSet attrs) {

        if (name.equals("padding")) {
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
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
                        throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        TypedArray a = r.obtainAttributes(attrs, com.android.internal.R.styleable.ShapeDrawable);

        int color = mShapeState.mPaint.getColor();
        color = a.getColor(com.android.internal.R.styleable.ShapeDrawable_color, color);
        mShapeState.mPaint.setColor(color);
            
        setIntrinsicWidth((int)
                a.getDimension(com.android.internal.R.styleable.ShapeDrawable_width, 0f));
        setIntrinsicHeight((int)
                a.getDimension(com.android.internal.R.styleable.ShapeDrawable_height, 0f));

        a.recycle();

        int type;
        final int outerDepth = parser.getDepth();
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
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
    }
    
    @Override
    public ConstantState getConstantState() {
        mShapeState.mChangingConfigurations = getChangingConfigurations();
        return mShapeState;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mShapeState.mPaint = new Paint(mShapeState.mPaint);
            mShapeState.mPadding = new Rect(mShapeState.mPadding);
            try {
                mShapeState.mShape = mShapeState.mShape.clone();
            } catch (CloneNotSupportedException e) {
                return null;
            }
            mMutated = true;
        }
        return this;
    }

    /**
     * Defines the intrinsic properties of this ShapeDrawable's Shape.
     */
    final static class ShapeState extends ConstantState {
        int mChangingConfigurations;
        Paint mPaint;
        Shape mShape;
        Rect mPadding;
        int mIntrinsicWidth;
        int mIntrinsicHeight;
        int mAlpha = 255;
        ShaderFactory mShaderFactory;
        
        ShapeState(ShapeState orig) {
            if (orig != null) {
                mPaint = orig.mPaint;
                mShape = orig.mShape;
                mPadding = orig.mPadding;
                mIntrinsicWidth = orig.mIntrinsicWidth;
                mIntrinsicHeight = orig.mIntrinsicHeight;
                mAlpha = orig.mAlpha;
                mShaderFactory = orig.mShaderFactory;
            } else {
                mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            }
        }
        
        @Override
        public Drawable newDrawable() {
            return new ShapeDrawable(this);
        }
        
        @Override
        public Drawable newDrawable(Resources res) {
            return new ShapeDrawable(this);
        }
        
        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }
    }
    
    /**
     * Base class defines a factory object that is called each time the drawable
     * is resized (has a new width or height). Its resize() method returns a
     * corresponding shader, or null.
     * Implement this class if you'd like your ShapeDrawable to use a special
     * {@link android.graphics.Shader}, such as a 
     * {@link android.graphics.LinearGradient}. 
     * 
     */
    public static abstract class ShaderFactory {
        /**
         * Returns the Shader to be drawn when a Drawable is drawn.
         * The dimensions of the Drawable are passed because they may be needed
         * to adjust how the Shader is configured for drawing.
         * This is called by ShapeDrawable.setShape().
         * 
         * @param width  the width of the Drawable being drawn
         * @param height the heigh of the Drawable being drawn
         * @return       the Shader to be drawn
         */
        public abstract Shader resize(int width, int height);
    }
    
    // other subclass could wack the Shader's localmatrix based on the
    // resize params (e.g. scaletofit, etc.). This could be used to scale
    // a bitmap to fill the bounds without needing any other special casing.
}

