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

package android.graphics;

import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import java.util.Locale;

/**
 * The Paint class holds the style and color information about how to draw
 * geometries, text and bitmaps.
 */
public class Paint {

    /**
     * @hide
     */
    public int mNativePaint;

    private ColorFilter mColorFilter;
    private MaskFilter  mMaskFilter;
    private PathEffect  mPathEffect;
    private Rasterizer  mRasterizer;
    private Shader      mShader;
    private Typeface    mTypeface;
    private Xfermode    mXfermode;

    private boolean     mHasCompatScaling;
    private float       mCompatScaling;
    private float       mInvCompatScaling;

    private Locale      mLocale;

    /**
     * @hide
     */
    public boolean hasShadow;
    /**
     * @hide
     */
    public float shadowDx;
    /**
     * @hide
     */
    public float shadowDy;
    /**
     * @hide
     */
    public float shadowRadius;
    /**
     * @hide
     */
    public int shadowColor;

    /**
     * @hide
     */
    public  int         mBidiFlags = BIDI_DEFAULT_LTR;
    
    static final Style[] sStyleArray = {
        Style.FILL, Style.STROKE, Style.FILL_AND_STROKE
    };
    static final Cap[] sCapArray = {
        Cap.BUTT, Cap.ROUND, Cap.SQUARE
    };
    static final Join[] sJoinArray = {
        Join.MITER, Join.ROUND, Join.BEVEL
    };
    static final Align[] sAlignArray = {
        Align.LEFT, Align.CENTER, Align.RIGHT
    };

    /** bit mask for the flag enabling antialiasing */
    public static final int ANTI_ALIAS_FLAG     = 0x01;
    /** bit mask for the flag enabling bitmap filtering */
    public static final int FILTER_BITMAP_FLAG  = 0x02;
    /** bit mask for the flag enabling dithering */
    public static final int DITHER_FLAG         = 0x04;
    /** bit mask for the flag enabling underline text */
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    /** bit mask for the flag enabling strike-thru text */
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    /** bit mask for the flag enabling fake-bold text */
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    /** bit mask for the flag enabling linear-text (no caching) */
    public static final int LINEAR_TEXT_FLAG    = 0x40;
    /** bit mask for the flag enabling subpixel-text */
    public static final int SUBPIXEL_TEXT_FLAG  = 0x80;
    /** bit mask for the flag enabling device kerning for text */
    public static final int DEV_KERN_TEXT_FLAG  = 0x100;

    // we use this when we first create a paint
    static final int DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG;

    /**
     * Option for {@link #setHinting}: disable hinting.
     */
    public static final int HINTING_OFF = 0x0;

    /**
     * Option for {@link #setHinting}: enable hinting.
     */
    public static final int HINTING_ON = 0x1;

    /**
     * Bidi flag to set LTR paragraph direction.
     * 
     * @hide
     */
    public static final int BIDI_LTR = 0x0;

    /**
     * Bidi flag to set RTL paragraph direction.
     * 
     * @hide
     */
    public static final int BIDI_RTL = 0x1;

    /**
     * Bidi flag to detect paragraph direction via heuristics, defaulting to
     * LTR.
     * 
     * @hide
     */
    public static final int BIDI_DEFAULT_LTR = 0x2;

    /**
     * Bidi flag to detect paragraph direction via heuristics, defaulting to
     * RTL.
     * 
     * @hide
     */
    public static final int BIDI_DEFAULT_RTL = 0x3;

    /**
     * Bidi flag to override direction to all LTR (ignore bidi).
     * 
     * @hide
     */
    public static final int BIDI_FORCE_LTR = 0x4;

    /**
     * Bidi flag to override direction to all RTL (ignore bidi).
     * 
     * @hide
     */
    public static final int BIDI_FORCE_RTL = 0x5;

    /**
     * Maximum Bidi flag value.
     * @hide
     */
    private static final int BIDI_MAX_FLAG_VALUE = BIDI_FORCE_RTL;

    /**
     * Mask for bidi flags.
     * @hide
     */
    private static final int BIDI_FLAG_MASK = 0x7;

    /**
     * Flag for getTextRunAdvances indicating left-to-right run direction.
     * @hide
     */
    public static final int DIRECTION_LTR = 0;

    /**
     * Flag for getTextRunAdvances indicating right-to-left run direction.
     * @hide
     */
    public static final int DIRECTION_RTL = 1;

    /**
     * Option for getTextRunCursor to compute the valid cursor after
     * offset or the limit of the context, whichever is less.
     * @hide
     */
    public static final int CURSOR_AFTER = 0;

    /**
     * Option for getTextRunCursor to compute the valid cursor at or after
     * the offset or the limit of the context, whichever is less.
     * @hide
     */
    public static final int CURSOR_AT_OR_AFTER = 1;

     /**
     * Option for getTextRunCursor to compute the valid cursor before
     * offset or the start of the context, whichever is greater.
     * @hide
     */
    public static final int CURSOR_BEFORE = 2;

   /**
     * Option for getTextRunCursor to compute the valid cursor at or before
     * offset or the start of the context, whichever is greater.
     * @hide
     */
    public static final int CURSOR_AT_OR_BEFORE = 3;

    /**
     * Option for getTextRunCursor to return offset if the cursor at offset
     * is valid, or -1 if it isn't.
     * @hide
     */
    public static final int CURSOR_AT = 4;

    /**
     * Maximum cursor option value.
     */
    private static final int CURSOR_OPT_MAX_VALUE = CURSOR_AT;

    /**
     * The Style specifies if the primitive being drawn is filled, stroked, or
     * both (in the same color). The default is FILL.
     */
    public enum Style {
        /**
         * Geometry and text drawn with this style will be filled, ignoring all
         * stroke-related settings in the paint.
         */
        FILL            (0),
        /**
         * Geometry and text drawn with this style will be stroked, respecting
         * the stroke-related fields on the paint.
         */
        STROKE          (1),
        /**
         * Geometry and text drawn with this style will be both filled and
         * stroked at the same time, respecting the stroke-related fields on
         * the paint. This mode can give unexpected results if the geometry
         * is oriented counter-clockwise. This restriction does not apply to
         * either FILL or STROKE.
         */
        FILL_AND_STROKE (2);
        
        Style(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * The Cap specifies the treatment for the beginning and ending of
     * stroked lines and paths. The default is BUTT.
     */
    public enum Cap {
        /**
         * The stroke ends with the path, and does not project beyond it.
         */
        BUTT    (0),
        /**
         * The stroke projects out as a semicircle, with the center at the
         * end of the path.
         */
        ROUND   (1),
        /**
         * The stroke projects out as a square, with the center at the end
         * of the path.
         */
        SQUARE  (2);
        
        private Cap(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * The Join specifies the treatment where lines and curve segments
     * join on a stroked path. The default is MITER.
     */
    public enum Join {
        /**
         * The outer edges of a join meet at a sharp angle
         */
        MITER   (0),
        /**
         * The outer edges of a join meet in a circular arc.
         */
        ROUND   (1),
        /**
         * The outer edges of a join meet with a straight line
         */
        BEVEL   (2);
        
        private Join(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Align specifies how drawText aligns its text relative to the
     * [x,y] coordinates. The default is LEFT.
     */
    public enum Align {
        /**
         * The text is drawn to the right of the x,y origin
         */
        LEFT    (0),
        /**
         * The text is drawn centered horizontally on the x,y origin
         */
        CENTER  (1),
        /**
         * The text is drawn to the left of the x,y origin
         */
        RIGHT   (2);
        
        private Align(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Create a new paint with default settings.
     */
    public Paint() {
        this(0);
    }
    
    /**
     * Create a new paint with the specified flags. Use setFlags() to change
     * these after the paint is created.
     *
     * @param flags initial flag bits, as if they were passed via setFlags().
     */
    public Paint(int flags) {
        mNativePaint = native_init();
        setFlags(flags | DEFAULT_PAINT_FLAGS);
        // TODO: Turning off hinting has undesirable side effects, we need to
        //       revisit hinting once we add support for subpixel positioning
        // setHinting(DisplayMetrics.DENSITY_DEVICE >= DisplayMetrics.DENSITY_TV
        //        ? HINTING_OFF : HINTING_ON);
        mCompatScaling = mInvCompatScaling = 1;
        setTextLocale(Locale.getDefault());
    }

    /**
     * Create a new paint, initialized with the attributes in the specified
     * paint parameter.
     *
     * @param paint Existing paint used to initialized the attributes of the
     *              new paint.
     */
    public Paint(Paint paint) {
        mNativePaint = native_initWithPaint(paint.mNativePaint);
        setClassVariablesFrom(paint);
    }

    /** Restores the paint to its default settings. */
    public void reset() {
        native_reset(mNativePaint);
        setFlags(DEFAULT_PAINT_FLAGS);
        // TODO: Turning off hinting has undesirable side effects, we need to
        //       revisit hinting once we add support for subpixel positioning
        // setHinting(DisplayMetrics.DENSITY_DEVICE >= DisplayMetrics.DENSITY_TV
        //        ? HINTING_OFF : HINTING_ON);
        mHasCompatScaling = false;
        mCompatScaling = mInvCompatScaling = 1;
        mBidiFlags = BIDI_DEFAULT_LTR;
        setTextLocale(Locale.getDefault());
    }
    
    /**
     * Copy the fields from src into this paint. This is equivalent to calling
     * get() on all of the src fields, and calling the corresponding set()
     * methods on this.
     */
    public void set(Paint src) {
        if (this != src) {
            // copy over the native settings
            native_set(mNativePaint, src.mNativePaint);
            setClassVariablesFrom(src);
        }
    }

    /**
     * Set all class variables using current values from the given
     * {@link Paint}.
     */
    private void setClassVariablesFrom(Paint paint) {
        mColorFilter = paint.mColorFilter;
        mMaskFilter = paint.mMaskFilter;
        mPathEffect = paint.mPathEffect;
        mRasterizer = paint.mRasterizer;
        mShader = paint.mShader;
        mTypeface = paint.mTypeface;
        mXfermode = paint.mXfermode;

        mHasCompatScaling = paint.mHasCompatScaling;
        mCompatScaling = paint.mCompatScaling;
        mInvCompatScaling = paint.mInvCompatScaling;

        hasShadow = paint.hasShadow;
        shadowDx = paint.shadowDx;
        shadowDy = paint.shadowDy;
        shadowRadius = paint.shadowRadius;
        shadowColor = paint.shadowColor;

        mBidiFlags = paint.mBidiFlags;
        mLocale = paint.mLocale;
    }

    /** @hide */
    public void setCompatibilityScaling(float factor) {
        if (factor == 1.0) {
            mHasCompatScaling = false;
            mCompatScaling = mInvCompatScaling = 1.0f;
        } else {
            mHasCompatScaling = true;
            mCompatScaling = factor;
            mInvCompatScaling = 1.0f/factor;
        }
    }

    /**
     * Return the bidi flags on the paint.
     * 
     * @return the bidi flags on the paint
     * @hide
     */
    public int getBidiFlags() {
        return mBidiFlags;
    }

    /**
     * Set the bidi flags on the paint.
     * @hide
     */
    public void setBidiFlags(int flags) {
        // only flag value is the 3-bit BIDI control setting
        flags &= BIDI_FLAG_MASK;
        if (flags > BIDI_MAX_FLAG_VALUE) {
            throw new IllegalArgumentException("unknown bidi flag: " + flags);
        }
        mBidiFlags = flags;
    }

    /**
     * Return the paint's flags. Use the Flag enum to test flag values.
     * 
     * @return the paint's flags (see enums ending in _Flag for bit masks)
     */
    public native int getFlags();

    /**
     * Set the paint's flags. Use the Flag enum to specific flag values.
     *
     * @param flags The new flag bits for the paint
     */
    public native void setFlags(int flags);

    /**
     * Return the paint's hinting mode.  Returns either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public native int getHinting();

    /**
     * Set the paint's hinting mode.  May be either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public native void setHinting(int mode);

    /**
     * Helper for getFlags(), returning true if ANTI_ALIAS_FLAG bit is set
     * AntiAliasing smooths out the edges of what is being drawn, but is has
     * no impact on the interior of the shape. See setDither() and
     * setFilterBitmap() to affect how colors are treated.
     *
     * @return true if the antialias bit is set in the paint's flags.
     */
    public final boolean isAntiAlias() {
        return (getFlags() & ANTI_ALIAS_FLAG) != 0;
    }
    
    /**
     * Helper for setFlags(), setting or clearing the ANTI_ALIAS_FLAG bit
     * AntiAliasing smooths out the edges of what is being drawn, but is has
     * no impact on the interior of the shape. See setDither() and
     * setFilterBitmap() to affect how colors are treated.
     *
     * @param aa true to set the antialias bit in the flags, false to clear it
     */
    public native void setAntiAlias(boolean aa);
    
    /**
     * Helper for getFlags(), returning true if DITHER_FLAG bit is set
     * Dithering affects how colors that are higher precision than the device
     * are down-sampled. No dithering is generally faster, but higher precision
     * colors are just truncated down (e.g. 8888 -> 565). Dithering tries to
     * distribute the error inherent in this process, to reduce the visual
     * artifacts.
     *
     * @return true if the dithering bit is set in the paint's flags.
     */
    public final boolean isDither() {
        return (getFlags() & DITHER_FLAG) != 0;
    }
    
    /**
     * Helper for setFlags(), setting or clearing the DITHER_FLAG bit
     * Dithering affects how colors that are higher precision than the device
     * are down-sampled. No dithering is generally faster, but higher precision
     * colors are just truncated down (e.g. 8888 -> 565). Dithering tries to
     * distribute the error inherent in this process, to reduce the visual
     * artifacts.
     *
     * @param dither true to set the dithering bit in flags, false to clear it
     */
    public native void setDither(boolean dither);
    
    /**
     * Helper for getFlags(), returning true if LINEAR_TEXT_FLAG bit is set
     *
     * @return true if the lineartext bit is set in the paint's flags
     */
    @Deprecated
    public final boolean isLinearText() {
        return (getFlags() & LINEAR_TEXT_FLAG) != 0;
    }

    /**
     * Helper for setFlags(), setting or clearing the LINEAR_TEXT_FLAG bit
     *
     * @param linearText true to set the linearText bit in the paint's flags,
     *                   false to clear it.
     */
    @Deprecated
    public native void setLinearText(boolean linearText);

    /**
     * Helper for getFlags(), returning true if SUBPIXEL_TEXT_FLAG bit is set
     *
     * @return true if the subpixel bit is set in the paint's flags
     */
    public final boolean isSubpixelText() {
        return (getFlags() & SUBPIXEL_TEXT_FLAG) != 0;
    }
    
    /**
     * Helper for setFlags(), setting or clearing the SUBPIXEL_TEXT_FLAG bit
     *
     * @param subpixelText true to set the subpixelText bit in the paint's
     *                     flags, false to clear it.
     */
    public native void setSubpixelText(boolean subpixelText);
    
    /**
     * Helper for getFlags(), returning true if UNDERLINE_TEXT_FLAG bit is set
     *
     * @return true if the underlineText bit is set in the paint's flags.
     */
    public final boolean isUnderlineText() {
        return (getFlags() & UNDERLINE_TEXT_FLAG) != 0;
    }

    /**
     * Helper for setFlags(), setting or clearing the UNDERLINE_TEXT_FLAG bit
     *
     * @param underlineText true to set the underlineText bit in the paint's
     *                      flags, false to clear it.
     */
    public native void setUnderlineText(boolean underlineText);

    /**
     * Helper for getFlags(), returning true if STRIKE_THRU_TEXT_FLAG bit is set
     *
     * @return true if the strikeThruText bit is set in the paint's flags.
     */
    public final boolean isStrikeThruText() {
        return (getFlags() & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    /**
     * Helper for setFlags(), setting or clearing the STRIKE_THRU_TEXT_FLAG bit
     *
     * @param strikeThruText true to set the strikeThruText bit in the paint's
     *                       flags, false to clear it.
     */
    public native void setStrikeThruText(boolean strikeThruText);

    /**
     * Helper for getFlags(), returning true if FAKE_BOLD_TEXT_FLAG bit is set
     *
     * @return true if the fakeBoldText bit is set in the paint's flags.
     */
    public final boolean isFakeBoldText() {
        return (getFlags() & FAKE_BOLD_TEXT_FLAG) != 0;
    }

    /**
     * Helper for setFlags(), setting or clearing the FAKE_BOLD_TEXT_FLAG bit
     *
     * @param fakeBoldText true to set the fakeBoldText bit in the paint's
     *                     flags, false to clear it.
     */
    public native void setFakeBoldText(boolean fakeBoldText);
    
    /**
     * Whether or not the bitmap filter is activated.
     * Filtering affects the sampling of bitmaps when they are transformed.
     * Filtering does not affect how the colors in the bitmap are converted into
     * device pixels. That is dependent on dithering and xfermodes.
     *
     * @see #setFilterBitmap(boolean) setFilterBitmap()
     */
    public final boolean isFilterBitmap() {
        return (getFlags() & FILTER_BITMAP_FLAG) != 0;
    }
    
    /**
     * Helper for setFlags(), setting or clearing the FILTER_BITMAP_FLAG bit.
     * Filtering affects the sampling of bitmaps when they are transformed.
     * Filtering does not affect how the colors in the bitmap are converted into
     * device pixels. That is dependent on dithering and xfermodes.
     * 
     * @param filter true to set the FILTER_BITMAP_FLAG bit in the paint's
     *               flags, false to clear it.
     */
    public native void setFilterBitmap(boolean filter);

    /**
     * Return the paint's style, used for controlling how primitives'
     * geometries are interpreted (except for drawBitmap, which always assumes
     * FILL_STYLE).
     *
     * @return the paint's style setting (Fill, Stroke, StrokeAndFill)
     */
    public Style getStyle() {
        return sStyleArray[native_getStyle(mNativePaint)];
    }

    /**
     * Set the paint's style, used for controlling how primitives'
     * geometries are interpreted (except for drawBitmap, which always assumes
     * Fill).
     *
     * @param style The new style to set in the paint
     */
    public void setStyle(Style style) {
        native_setStyle(mNativePaint, style.nativeInt);
    }

    /**
     * Return the paint's color. Note that the color is a 32bit value
     * containing alpha as well as r,g,b. This 32bit value is not premultiplied,
     * meaning that its alpha can be any value, regardless of the values of
     * r,g,b. See the Color class for more details.
     *
     * @return the paint's color (and alpha).
     */
    public native int getColor();

    /**
     * Set the paint's color. Note that the color is an int containing alpha
     * as well as r,g,b. This 32bit value is not premultiplied, meaning that
     * its alpha can be any value, regardless of the values of r,g,b.
     * See the Color class for more details.
     *
     * @param color The new color (including alpha) to set in the paint.
     */
    public native void setColor(int color);
    
    /**
     * Helper to getColor() that just returns the color's alpha value. This is
     * the same as calling getColor() >>> 24. It always returns a value between
     * 0 (completely transparent) and 255 (completely opaque).
     *
     * @return the alpha component of the paint's color.
     */
    public native int getAlpha();

    /**
     * Helper to setColor(), that only assigns the color's alpha value,
     * leaving its r,g,b values unchanged. Results are undefined if the alpha
     * value is outside of the range [0..255]
     *
     * @param a set the alpha component [0..255] of the paint's color.
     */
    public native void setAlpha(int a);

    /**
     * Helper to setColor(), that takes a,r,g,b and constructs the color int
     *
     * @param a The new alpha component (0..255) of the paint's color.
     * @param r The new red component (0..255) of the paint's color.
     * @param g The new green component (0..255) of the paint's color.
     * @param b The new blue component (0..255) of the paint's color.
     */
    public void setARGB(int a, int r, int g, int b) {
        setColor((a << 24) | (r << 16) | (g << 8) | b);
    }

    /**
     * Return the width for stroking.
     * <p />
     * A value of 0 strokes in hairline mode.
     * Hairlines always draws a single pixel independent of the canva's matrix.
     *
     * @return the paint's stroke width, used whenever the paint's style is
     *         Stroke or StrokeAndFill.
     */
    public native float getStrokeWidth();

    /**
     * Set the width for stroking.
     * Pass 0 to stroke in hairline mode.
     * Hairlines always draws a single pixel independent of the canva's matrix.
     *
     * @param width set the paint's stroke width, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public native void setStrokeWidth(float width);

    /**
     * Return the paint's stroke miter value. Used to control the behavior
     * of miter joins when the joins angle is sharp.
     *
     * @return the paint's miter limit, used whenever the paint's style is
     *         Stroke or StrokeAndFill.
     */
    public native float getStrokeMiter();

    /**
     * Set the paint's stroke miter value. This is used to control the behavior
     * of miter joins when the joins angle is sharp. This value must be >= 0.
     *
     * @param miter set the miter limit on the paint, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public native void setStrokeMiter(float miter);

    /**
     * Return the paint's Cap, controlling how the start and end of stroked
     * lines and paths are treated.
     *
     * @return the line cap style for the paint, used whenever the paint's
     *         style is Stroke or StrokeAndFill.
     */
    public Cap getStrokeCap() {
        return sCapArray[native_getStrokeCap(mNativePaint)];
    }

    /**
     * Set the paint's Cap.
     *
     * @param cap set the paint's line cap style, used whenever the paint's
     *            style is Stroke or StrokeAndFill.
     */
    public void setStrokeCap(Cap cap) {
        native_setStrokeCap(mNativePaint, cap.nativeInt);
    }

    /**
     * Return the paint's stroke join type.
     *
     * @return the paint's Join.
     */
    public Join getStrokeJoin() {
        return sJoinArray[native_getStrokeJoin(mNativePaint)];
    }

    /**
     * Set the paint's Join.
     *
     * @param join set the paint's Join, used whenever the paint's style is
     *             Stroke or StrokeAndFill.
     */
    public void setStrokeJoin(Join join) {
        native_setStrokeJoin(mNativePaint, join.nativeInt);
    }

    /**
     * Applies any/all effects (patheffect, stroking) to src, returning the
     * result in dst. The result is that drawing src with this paint will be
     * the same as drawing dst with a default paint (at least from the
     * geometric perspective).
     *
     * @param src input path
     * @param dst output path (may be the same as src)
     * @return    true if the path should be filled, or false if it should be
     *                 drawn with a hairline (width == 0)
     */
    public boolean getFillPath(Path src, Path dst) {
        return native_getFillPath(mNativePaint, src.ni(), dst.ni());
    }

    /**
     * Get the paint's shader object.
     *
     * @return the paint's shader (or null)
     */
    public Shader getShader() {
        return mShader;
    }

    /**
     * Set or clear the shader object.
     * <p />
     * Pass null to clear any previous shader.
     * As a convenience, the parameter passed is also returned.
     *
     * @param shader May be null. the new shader to be installed in the paint
     * @return       shader
     */
    public Shader setShader(Shader shader) {
        int shaderNative = 0;
        if (shader != null)
            shaderNative = shader.native_instance;
        native_setShader(mNativePaint, shaderNative);
        mShader = shader;
        return shader;
    }

    /**
     * Get the paint's colorfilter (maybe be null).
     *
     * @return the paint's colorfilter (maybe be null)
     */
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    /**
     * Set or clear the paint's colorfilter, returning the parameter.
     *
     * @param filter May be null. The new filter to be installed in the paint
     * @return       filter
     */
    public ColorFilter setColorFilter(ColorFilter filter) {
        int filterNative = 0;
        if (filter != null)
            filterNative = filter.native_instance;
        native_setColorFilter(mNativePaint, filterNative);
        mColorFilter = filter;
        return filter;
    }

    /**
     * Get the paint's xfermode object.
     *
     * @return the paint's xfermode (or null)
     */
    public Xfermode getXfermode() {
        return mXfermode;
    }

    /**
     * Set or clear the xfermode object.
     * <p />
     * Pass null to clear any previous xfermode.
     * As a convenience, the parameter passed is also returned.
     *
     * @param xfermode May be null. The xfermode to be installed in the paint
     * @return         xfermode
     */
    public Xfermode setXfermode(Xfermode xfermode) {
        int xfermodeNative = 0;
        if (xfermode != null)
            xfermodeNative = xfermode.native_instance;
        native_setXfermode(mNativePaint, xfermodeNative);
        mXfermode = xfermode;
        return xfermode;
    }

    /**
     * Get the paint's patheffect object.
     *
     * @return the paint's patheffect (or null)
     */
    public PathEffect getPathEffect() {
        return mPathEffect;
    }

    /**
     * Set or clear the patheffect object.
     * <p />
     * Pass null to clear any previous patheffect.
     * As a convenience, the parameter passed is also returned.
     *
     * @param effect May be null. The patheffect to be installed in the paint
     * @return       effect
     */
    public PathEffect setPathEffect(PathEffect effect) {
        int effectNative = 0;
        if (effect != null) {
            effectNative = effect.native_instance;
        }
        native_setPathEffect(mNativePaint, effectNative);
        mPathEffect = effect;
        return effect;
    }

    /**
     * Get the paint's maskfilter object.
     *
     * @return the paint's maskfilter (or null)
     */
    public MaskFilter getMaskFilter() {
        return mMaskFilter;
    }

    /**
     * Set or clear the maskfilter object.
     * <p />
     * Pass null to clear any previous maskfilter.
     * As a convenience, the parameter passed is also returned.
     *
     * @param maskfilter May be null. The maskfilter to be installed in the
     *                   paint
     * @return           maskfilter
     */
    public MaskFilter setMaskFilter(MaskFilter maskfilter) {
        int maskfilterNative = 0;
        if (maskfilter != null) {
            maskfilterNative = maskfilter.native_instance;
        }
        native_setMaskFilter(mNativePaint, maskfilterNative);
        mMaskFilter = maskfilter;
        return maskfilter;
    }

    /**
     * Get the paint's typeface object.
     * <p />
     * The typeface object identifies which font to use when drawing or
     * measuring text.
     *
     * @return the paint's typeface (or null)
     */
    public Typeface getTypeface() {
        return mTypeface;
    }

    /**
     * Set or clear the typeface object.
     * <p />
     * Pass null to clear any previous typeface.
     * As a convenience, the parameter passed is also returned.
     *
     * @param typeface May be null. The typeface to be installed in the paint
     * @return         typeface
     */
    public Typeface setTypeface(Typeface typeface) {
        int typefaceNative = 0;
        if (typeface != null) {
            typefaceNative = typeface.native_instance;
        }
        native_setTypeface(mNativePaint, typefaceNative);
        mTypeface = typeface;
        return typeface;
    }
    
    /**
     * Get the paint's rasterizer (or null).
     * <p />
     * The raster controls/modifies how paths/text are turned into alpha masks.
     *
     * @return         the paint's rasterizer (or null)
     */
    public Rasterizer getRasterizer() {
        return mRasterizer;
    }

    /**
     * Set or clear the rasterizer object.
     * <p />
     * Pass null to clear any previous rasterizer.
     * As a convenience, the parameter passed is also returned.
     *
     * @param rasterizer May be null. The new rasterizer to be installed in
     *                   the paint.
     * @return           rasterizer
     */
    public Rasterizer setRasterizer(Rasterizer rasterizer) {
        int rasterizerNative = 0;
        if (rasterizer != null) {
            rasterizerNative = rasterizer.native_instance;
        }
        native_setRasterizer(mNativePaint, rasterizerNative);
        mRasterizer = rasterizer;
        return rasterizer;
    }
    
    /**
     * This draws a shadow layer below the main layer, with the specified
     * offset and color, and blur radius. If radius is 0, then the shadow
     * layer is removed.
     */
    public void setShadowLayer(float radius, float dx, float dy, int color) {
        hasShadow = radius > 0.0f;
        shadowRadius = radius;
        shadowDx = dx;
        shadowDy = dy;
        shadowColor = color;
        nSetShadowLayer(radius, dx, dy, color);
    }
    
    private native void nSetShadowLayer(float radius, float dx, float dy, int color);

    /**
     * Clear the shadow layer.
     */
    public void clearShadowLayer() {
        hasShadow = false;
        nSetShadowLayer(0, 0, 0, 0);
    }

    /**
     * Return the paint's Align value for drawing text. This controls how the
     * text is positioned relative to its origin. LEFT align means that all of
     * the text will be drawn to the right of its origin (i.e. the origin
     * specifieds the LEFT edge of the text) and so on.
     *
     * @return the paint's Align value for drawing text.
     */
    public Align getTextAlign() {
        return sAlignArray[native_getTextAlign(mNativePaint)];
    }

    /**
     * Set the paint's text alignment. This controls how the
     * text is positioned relative to its origin. LEFT align means that all of
     * the text will be drawn to the right of its origin (i.e. the origin
     * specifieds the LEFT edge of the text) and so on.
     *
     * @param align set the paint's Align value for drawing text.
     */
    public void setTextAlign(Align align) {
        native_setTextAlign(mNativePaint, align.nativeInt);
    }

    /**
     * Get the text Locale.
     *
     * @return the paint's Locale used for drawing text, never null.
     * @hide
     */
    public Locale getTextLocale() {
        return mLocale;
    }

    /**
     * Set the text locale.
     *
     * The text locale affects how the text is drawn for some languages.
     *
     * For example, if the locale is {@link Locale#CHINESE} or {@link Locale#CHINA},
     * then the text renderer will prefer to draw text using a Chinese font. Likewise,
     * if the locale is {@link Locale#JAPANESE} or {@link Locale#JAPAN}, then the text
     * renderer will prefer to draw text using a Japanese font.
     *
     * This distinction is important because Chinese and Japanese text both use many
     * of the same Unicode code points but their appearance is subtly different for
     * each language.
     *
     * By default, the text locale is initialized to the system locale (as returned
     * by {@link Locale#getDefault}). This assumes that the text to be rendered will
     * most likely be in the user's preferred language.
     *
     * If the actual language of the text is known, then it can be provided to the
     * text renderer using this method. The text renderer may attempt to guess the
     * language script based on the contents of the text to be drawn independent of
     * the text locale here. Specifying the text locale just helps it do a better
     * job in certain ambiguous cases
     *
     * @param locale the paint's locale value for drawing text, must not be null.
     * @hide
     */
    public void setTextLocale(Locale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale cannot be null");
        }
        if (locale.equals(mLocale)) return;
        mLocale = locale;
        native_setTextLocale(mNativePaint, locale.toString());
    }

    /**
     * Return the paint's text size.
     *
     * @return the paint's text size.
     */
    public native float getTextSize();

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param textSize set the paint's text size.
     */
    public native void setTextSize(float textSize);

    /**
     * Return the paint's horizontal scale factor for text. The default value
     * is 1.0.
     *
     * @return the paint's scale factor in X for drawing/measuring text
     */
    public native float getTextScaleX();

    /**
     * Set the paint's horizontal scale factor for text. The default value
     * is 1.0. Values > 1.0 will stretch the text wider. Values < 1.0 will
     * stretch the text narrower.
     *
     * @param scaleX set the paint's scale in X for drawing/measuring text.
     */
    public native void setTextScaleX(float scaleX);

    /**
     * Return the paint's horizontal skew factor for text. The default value
     * is 0.
     *
     * @return         the paint's skew factor in X for drawing text.
     */
    public native float getTextSkewX();

    /**
     * Set the paint's horizontal skew factor for text. The default value
     * is 0. For approximating oblique text, use values around -0.25.
     *
     * @param skewX set the paint's skew factor in X for drawing text.
     */
    public native void setTextSkewX(float skewX);

    /**
     * Return the distance above (negative) the baseline (ascent) based on the
     * current typeface and text size.
     *
     * @return the distance above (negative) the baseline (ascent) based on the
     *         current typeface and text size.
     */
    public native float ascent();

    /**
     * Return the distance below (positive) the baseline (descent) based on the
     * current typeface and text size.
     *
     * @return the distance below (positive) the baseline (descent) based on
     *         the current typeface and text size.
     */
    public native float descent();

    /**
     * Class that describes the various metrics for a font at a given text size.
     * Remember, Y values increase going down, so those values will be positive,
     * and values that measure distances going up will be negative. This class
     * is returned by getFontMetrics().
     */
    public static class FontMetrics {
        /**
         * The maximum distance above the baseline for the tallest glyph in 
         * the font at a given text size.
         */
        public float   top;
        /**
         * The recommended distance above the baseline for singled spaced text.
         */
        public float   ascent;
        /**
         * The recommended distance below the baseline for singled spaced text.
         */
        public float   descent;
        /**
         * The maximum distance below the baseline for the lowest glyph in 
         * the font at a given text size.
         */
        public float   bottom;
        /**
         * The recommended additional space to add between lines of text.
         */
        public float   leading;
    }
    
    /**
     * Return the font's recommended interline spacing, given the Paint's
     * settings for typeface, textSize, etc. If metrics is not null, return the
     * fontmetric values in it.
     *
     * @param metrics If this object is not null, its fields are filled with
     *                the appropriate values given the paint's text attributes.
     * @return the font's recommended interline spacing.
     */
    public native float getFontMetrics(FontMetrics metrics);
    
    /**
     * Allocates a new FontMetrics object, and then calls getFontMetrics(fm)
     * with it, returning the object.
     */
    public FontMetrics getFontMetrics() {
        FontMetrics fm = new FontMetrics();
        getFontMetrics(fm);
        return fm;
    }
    
    /**
     * Convenience method for callers that want to have FontMetrics values as
     * integers.
     */
    public static class FontMetricsInt {
        public int   top;
        public int   ascent;
        public int   descent;
        public int   bottom;
        public int   leading;
        
        @Override public String toString() {
            return "FontMetricsInt: top=" + top + " ascent=" + ascent +
                    " descent=" + descent + " bottom=" + bottom +
                    " leading=" + leading;
        }
    }

    /**
     * Return the font's interline spacing, given the Paint's settings for
     * typeface, textSize, etc. If metrics is not null, return the fontmetric
     * values in it. Note: all values have been converted to integers from
     * floats, in such a way has to make the answers useful for both spacing
     * and clipping. If you want more control over the rounding, call
     * getFontMetrics().
     *
     * @return the font's interline spacing.
     */
    public native int getFontMetricsInt(FontMetricsInt fmi);

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fm = new FontMetricsInt();
        getFontMetricsInt(fm);
        return fm;
    }
    
    /**
     * Return the recommend line spacing based on the current typeface and
     * text size.
     *
     * @return  recommend line spacing based on the current typeface and
     *          text size.
     */
    public float getFontSpacing() {
        return getFontMetrics(null);
    }

    /**
     * Return the width of the text.
     *
     * @param text  The text to measure. Cannot be null.
     * @param index The index of the first character to start measuring
     * @param count THe number of characters to measure, beginning with start
     * @return      The width of the text
     */
    public float measureText(char[] text, int index, int count) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (text.length == 0 || count == 0) {
            return 0f;
        }
        if (!mHasCompatScaling) {
            return native_measureText(text, index, count);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        float w = native_measureText(text, index, count);
        setTextSize(oldSize);
        return w*mInvCompatScaling;
    }

    private native float native_measureText(char[] text, int index, int count);
    
    /**
     * Return the width of the text.
     *
     * @param text  The text to measure. Cannot be null.
     * @param start The index of the first character to start measuring
     * @param end   1 beyond the index of the last character to measure
     * @return      The width of the text
     */
    public float measureText(String text, int start, int end) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text.length() == 0 || start == end) {
            return 0f;
        }
        if (!mHasCompatScaling) {
            return native_measureText(text, start, end);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        float w = native_measureText(text, start, end);
        setTextSize(oldSize);
        return w*mInvCompatScaling;
    }

    private native float native_measureText(String text, int start, int end);
    
    /**
     * Return the width of the text.
     *
     * @param text  The text to measure. Cannot be null.
     * @return      The width of the text
     */
    public float measureText(String text) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }

        if (text.length() == 0) {
            return 0f;
        }

        if (!mHasCompatScaling) return native_measureText(text);
        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        float w = native_measureText(text);
        setTextSize(oldSize);
        return w*mInvCompatScaling;
    }

    private native float native_measureText(String text);
    
    /**
     * Return the width of the text.
     *
     * @param text  The text to measure
     * @param start The index of the first character to start measuring
     * @param end   1 beyond the index of the last character to measure
     * @return      The width of the text
     */
    public float measureText(CharSequence text, int start, int end) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text.length() == 0 || start == end) {
            return 0f;
        }
        if (text instanceof String) {
            return measureText((String)text, start, end);
        }
        if (text instanceof SpannedString ||
            text instanceof SpannableString) {
            return measureText(text.toString(), start, end);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations)text).measureText(start, end, this);
        }

        char[] buf = TemporaryBuffer.obtain(end - start);
        TextUtils.getChars(text, start, end, buf, 0);
        float result = measureText(buf, 0, end - start);
        TemporaryBuffer.recycle(buf);
        return result;
    }
    
    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure. Cannot be null.
     * @param index The offset into text to begin measuring at
     * @param count The number of maximum number of entries to measure. If count
     *              is negative, then the characters are measured in reverse order.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(count).
     */
    public int breakText(char[] text, int index, int count,
                                float maxWidth, float[] measuredWidth) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (index < 0 || text.length - index < Math.abs(count)) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (text.length == 0 || count == 0) {
            return 0;
        }
        if (!mHasCompatScaling) {
            return native_breakText(text, index, count, maxWidth, measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        int res = native_breakText(text, index, count, maxWidth*mCompatScaling,
                measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

    private native int native_breakText(char[] text, int index, int count,
                                        float maxWidth, float[] measuredWidth);

    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure. Cannot be null.
     * @param start The offset into text to begin measuring at
     * @param end   The end of the text slice to measure.
     * @param measureForwards If true, measure forwards, starting at start.
     *                        Otherwise, measure backwards, starting with end.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(end - start).
     */
    public int breakText(CharSequence text, int start, int end,
                         boolean measureForwards,
                         float maxWidth, float[] measuredWidth) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text.length() == 0 || start == end) {
            return 0;
        }
        if (start == 0 && text instanceof String && end == text.length()) {
            return breakText((String) text, measureForwards, maxWidth,
                             measuredWidth);
        }

        char[] buf = TemporaryBuffer.obtain(end - start);
        int result;

        TextUtils.getChars(text, start, end, buf, 0);

        if (measureForwards) {
            result = breakText(buf, 0, end - start, maxWidth, measuredWidth);
        } else {
            result = breakText(buf, 0, -(end - start), maxWidth, measuredWidth);
        }

        TemporaryBuffer.recycle(buf);
        return result;
    }

    /**
     * Measure the text, stopping early if the measured width exceeds maxWidth.
     * Return the number of chars that were measured, and if measuredWidth is
     * not null, return in it the actual width measured.
     *
     * @param text  The text to measure. Cannot be null.
     * @param measureForwards If true, measure forwards, starting with the
     *                        first character in the string. Otherwise,
     *                        measure backwards, starting with the
     *                        last character in the string.
     * @param maxWidth The maximum width to accumulate.
     * @param measuredWidth Optional. If not null, returns the actual width
     *                     measured.
     * @return The number of chars that were measured. Will always be <=
     *         abs(count).
     */
    public int breakText(String text, boolean measureForwards,
                                float maxWidth, float[] measuredWidth) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }

        if (text.length() == 0) {
            return 0;
        }
        if (!mHasCompatScaling) {
            return native_breakText(text, measureForwards, maxWidth, measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        int res = native_breakText(text, measureForwards, maxWidth*mCompatScaling,
                measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

    private native int native_breakText(String text, boolean measureForwards,
                                        float maxWidth, float[] measuredWidth);

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text     The text to measure. Cannot be null.
     * @param index    The index of the first char to to measure
     * @param count    The number of chars starting with index to measure
     * @param widths   array to receive the advance widths of the characters.
     *                 Must be at least a large as count.
     * @return         the actual number of widths returned.
     */
    public int getTextWidths(char[] text, int index, int count,
                             float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((index | count) < 0 || index + count > text.length
                || count > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (text.length == 0 || count == 0) {
            return 0;
        }
        if (!mHasCompatScaling) {
            return native_getTextWidths(mNativePaint, text, index, count, widths);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        int res = native_getTextWidths(mNativePaint, text, index, count, widths);
        setTextSize(oldSize);
        for (int i=0; i<res; i++) {
            widths[i] *= mInvCompatScaling;
        }
        return res;
    }

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text     The text to measure. Cannot be null.
     * @param start    The index of the first char to to measure
     * @param end      The end of the text slice to measure
     * @param widths   array to receive the advance widths of the characters.
     *                 Must be at least a large as (end - start).
     * @return         the actual number of widths returned.
     */
    public int getTextWidths(CharSequence text, int start, int end,
                             float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }

        if (text.length() == 0 || start == end) {
            return 0;
        }
        if (text instanceof String) {
            return getTextWidths((String) text, start, end, widths);
        }
        if (text instanceof SpannedString ||
            text instanceof SpannableString) {
            return getTextWidths(text.toString(), start, end, widths);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextWidths(start, end,
                                                                 widths, this);
        }

        char[] buf = TemporaryBuffer.obtain(end - start);
        TextUtils.getChars(text, start, end, buf, 0);
        int result = getTextWidths(buf, 0, end - start, widths);
        TemporaryBuffer.recycle(buf);
        return result;
    }

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text   The text to measure. Cannot be null.
     * @param start  The index of the first char to to measure
     * @param end    The end of the text slice to measure
     * @param widths array to receive the advance widths of the characters.
     *               Must be at least a large as the text.
     * @return       the number of unichars in the specified text.
     */
    public int getTextWidths(String text, int start, int end, float[] widths) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > widths.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        
        if (text.length() == 0 || start == end) {
            return 0;
        }
        if (!mHasCompatScaling) {
            return native_getTextWidths(mNativePaint, text, start, end, widths);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        int res = native_getTextWidths(mNativePaint, text, start, end, widths);
        setTextSize(oldSize);
        for (int i=0; i<res; i++) {
            widths[i] *= mInvCompatScaling;
        }
        return res;
    }
    
    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text   The text to measure
     * @param widths array to receive the advance widths of the characters.
     *               Must be at least a large as the text.
     * @return       the number of unichars in the specified text.
     */
    public int getTextWidths(String text, float[] widths) {
        return getTextWidths(text, 0, text.length(), widths);
    }

    /**
     * Return the glyph Ids for the characters in the string.
     *
     * @param text   The text to measure
     * @param start  The index of the first char to to measure
     * @param end    The end of the text slice to measure
     * @param contextStart the index of the first character to use for shaping context,
     * must be <= start
     * @param contextEnd the index past the last character to use for shaping context,
     * must be >= end
     * @param flags the flags to control the advances, either {@link #DIRECTION_LTR}
     * or {@link #DIRECTION_RTL}
     * @param glyphs array to receive the glyph Ids of the characters.
     *               Must be at least a large as the text.
     * @return       the number of glyphs in the returned array
     *
     * @hide
     *
     * Used only for BiDi / RTL Tests
     */
    public int getTextGlyphs(String text, int start, int end, int contextStart, int contextEnd,
            int flags, char[] glyphs) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (flags != DIRECTION_LTR && flags != DIRECTION_RTL) {
            throw new IllegalArgumentException("unknown flags value: " + flags);
        }
        if ((start | end | contextStart | contextEnd | (end - start)
                | (start - contextStart) | (contextEnd - end) | (text.length() - end)
                | (text.length() - contextEnd)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end - start > glyphs.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        return native_getTextGlyphs(mNativePaint, text, start, end, contextStart, contextEnd,
                flags, glyphs);
    }

    /**
     * Convenience overload that takes a char array instead of a
     * String.
     *
     * @see #getTextRunAdvances(String, int, int, int, int, int, float[], int)
     * @hide
     */
    public float getTextRunAdvances(char[] chars, int index, int count,
            int contextIndex, int contextCount, int flags, float[] advances,
            int advancesIndex) {
        return getTextRunAdvances(chars, index, count, contextIndex, contextCount, flags,
                advances, advancesIndex, 0 /* use Harfbuzz*/);
    }

    /**
     * Convenience overload that takes a char array instead of a
     * String.
     *
     * @see #getTextRunAdvances(String, int, int, int, int, int, float[], int, int)
     * @hide
     */
    public float getTextRunAdvances(char[] chars, int index, int count,
            int contextIndex, int contextCount, int flags, float[] advances,
            int advancesIndex, int reserved) {

        if (chars == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (flags != DIRECTION_LTR && flags != DIRECTION_RTL) {
            throw new IllegalArgumentException("unknown flags value: " + flags);
        }
        if ((index | count | contextIndex | contextCount | advancesIndex
                | (index - contextIndex) | (contextCount - count)
                | ((contextIndex + contextCount) - (index + count))
                | (chars.length - (contextIndex + contextCount))
                | (advances == null ? 0 :
                    (advances.length - (advancesIndex + count)))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (chars.length == 0 || count == 0){
            return 0f;
        }
        if (!mHasCompatScaling) {
            return native_getTextRunAdvances(mNativePaint, chars, index, count,
                    contextIndex, contextCount, flags, advances, advancesIndex, reserved);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float res = native_getTextRunAdvances(mNativePaint, chars, index, count,
                contextIndex, contextCount, flags, advances, advancesIndex, reserved);
        setTextSize(oldSize);

        if (advances != null) {
            for (int i = advancesIndex, e = i + count; i < e; i++) {
                advances[i] *= mInvCompatScaling;
            }
        }
        return res * mInvCompatScaling; // assume errors are not significant
    }

    /**
     * Convenience overload that takes a CharSequence instead of a
     * String.
     *
     * @see #getTextRunAdvances(String, int, int, int, int, int, float[], int)
     * @hide
     */
    public float getTextRunAdvances(CharSequence text, int start, int end,
            int contextStart, int contextEnd, int flags, float[] advances,
            int advancesIndex) {
        return getTextRunAdvances(text, start, end, contextStart, contextEnd, flags,
                advances, advancesIndex, 0 /* use Harfbuzz */);
    }

    /**
     * Convenience overload that takes a CharSequence instead of a
     * String.
     *
     * @see #getTextRunAdvances(String, int, int, int, int, int, float[], int)
     * @hide
     */
    public float getTextRunAdvances(CharSequence text, int start, int end,
            int contextStart, int contextEnd, int flags, float[] advances,
            int advancesIndex, int reserved) {

        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((start | end | contextStart | contextEnd | advancesIndex | (end - start)
                | (start - contextStart) | (contextEnd - end)
                | (text.length() - contextEnd)
                | (advances == null ? 0 :
                    (advances.length - advancesIndex - (end - start)))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text instanceof String) {
            return getTextRunAdvances((String) text, start, end,
                    contextStart, contextEnd, flags, advances, advancesIndex, reserved);
        }
        if (text instanceof SpannedString ||
            text instanceof SpannableString) {
            return getTextRunAdvances(text.toString(), start, end,
                    contextStart, contextEnd, flags, advances, advancesIndex, reserved);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextRunAdvances(start, end,
                    contextStart, contextEnd, flags, advances, advancesIndex, this);
        }
        if (text.length() == 0 || end == start) {
            return 0f;
        }

        int contextLen = contextEnd - contextStart;
        int len = end - start;
        char[] buf = TemporaryBuffer.obtain(contextLen);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        float result = getTextRunAdvances(buf, start - contextStart, len,
                0, contextLen, flags, advances, advancesIndex, reserved);
        TemporaryBuffer.recycle(buf);
        return result;
    }

    /**
     * Returns the total advance width for the characters in the run
     * between start and end, and if advances is not null, the advance
     * assigned to each of these characters (java chars).
     *
     * <p>The trailing surrogate in a valid surrogate pair is assigned
     * an advance of 0.  Thus the number of returned advances is
     * always equal to count, not to the number of unicode codepoints
     * represented by the run.
     *
     * <p>In the case of conjuncts or combining marks, the total
     * advance is assigned to the first logical character, and the
     * following characters are assigned an advance of 0.
     *
     * <p>This generates the sum of the advances of glyphs for
     * characters in a reordered cluster as the width of the first
     * logical character in the cluster, and 0 for the widths of all
     * other characters in the cluster.  In effect, such clusters are
     * treated like conjuncts.
     *
     * <p>The shaping bounds limit the amount of context available
     * outside start and end that can be used for shaping analysis.
     * These bounds typically reflect changes in bidi level or font
     * metrics across which shaping does not occur.
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the first character to measure
     * @param end the index past the last character to measure
     * @param contextStart the index of the first character to use for shaping context,
     * must be <= start
     * @param contextEnd the index past the last character to use for shaping context,
     * must be >= end
     * @param flags the flags to control the advances, either {@link #DIRECTION_LTR}
     * or {@link #DIRECTION_RTL}
     * @param advances array to receive the advances, must have room for all advances,
     * can be null if only total advance is needed
     * @param advancesIndex the position in advances at which to put the
     * advance corresponding to the character at start
     * @return the total advance
     *
     * @hide
     */
    public float getTextRunAdvances(String text, int start, int end, int contextStart,
            int contextEnd, int flags, float[] advances, int advancesIndex) {
        return getTextRunAdvances(text, start, end, contextStart, contextEnd, flags,
                advances, advancesIndex, 0 /* use Harfbuzz*/);
    }

    /**
     * Returns the total advance width for the characters in the run
     * between start and end, and if advances is not null, the advance
     * assigned to each of these characters (java chars).
     *
     * <p>The trailing surrogate in a valid surrogate pair is assigned
     * an advance of 0.  Thus the number of returned advances is
     * always equal to count, not to the number of unicode codepoints
     * represented by the run.
     *
     * <p>In the case of conjuncts or combining marks, the total
     * advance is assigned to the first logical character, and the
     * following characters are assigned an advance of 0.
     *
     * <p>This generates the sum of the advances of glyphs for
     * characters in a reordered cluster as the width of the first
     * logical character in the cluster, and 0 for the widths of all
     * other characters in the cluster.  In effect, such clusters are
     * treated like conjuncts.
     *
     * <p>The shaping bounds limit the amount of context available
     * outside start and end that can be used for shaping analysis.
     * These bounds typically reflect changes in bidi level or font
     * metrics across which shaping does not occur.
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the first character to measure
     * @param end the index past the last character to measure
     * @param contextStart the index of the first character to use for shaping context,
     * must be <= start
     * @param contextEnd the index past the last character to use for shaping context,
     * must be >= end
     * @param flags the flags to control the advances, either {@link #DIRECTION_LTR}
     * or {@link #DIRECTION_RTL}
     * @param advances array to receive the advances, must have room for all advances,
     * can be null if only total advance is needed
     * @param advancesIndex the position in advances at which to put the
     * advance corresponding to the character at start
     * @param reserved int reserved value
     * @return the total advance
     *
     * @hide
     */
    public float getTextRunAdvances(String text, int start, int end, int contextStart,
            int contextEnd, int flags, float[] advances, int advancesIndex, int reserved) {

        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if (flags != DIRECTION_LTR && flags != DIRECTION_RTL) {
            throw new IllegalArgumentException("unknown flags value: " + flags);
        }
        if ((start | end | contextStart | contextEnd | advancesIndex | (end - start)
                | (start - contextStart) | (contextEnd - end)
                | (text.length() - contextEnd)
                | (advances == null ? 0 :
                    (advances.length - advancesIndex - (end - start)))) < 0) {
            throw new IndexOutOfBoundsException();
        }

        if (text.length() == 0 || start == end) {
            return 0f;
        }

        if (!mHasCompatScaling) {
            return native_getTextRunAdvances(mNativePaint, text, start, end,
                    contextStart, contextEnd, flags, advances, advancesIndex, reserved);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float totalAdvance = native_getTextRunAdvances(mNativePaint, text, start, end,
                contextStart, contextEnd, flags, advances, advancesIndex, reserved);
        setTextSize(oldSize);

        if (advances != null) {
            for (int i = advancesIndex, e = i + (end - start); i < e; i++) {
                advances[i] *= mInvCompatScaling;
            }
        }
        return totalAdvance * mInvCompatScaling; // assume errors are insignificant
    }

    /**
     * Returns the next cursor position in the run.  This avoids placing the
     * cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering
     * cluster.
     *
     * <p>ContextStart and offset are relative to the start of text.
     * The context is the shaping context for cursor movement, generally
     * the bounds of the metric span enclosing the cursor in the direction of
     * movement.
     *
     * <p>If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid
     * cursor position, this returns -1.  Otherwise this will never return a
     * value before contextStart or after contextStart + contextLength.
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextLength the length of the context
     * @param flags either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(char[] text, int contextStart, int contextLength,
            int flags, int offset, int cursorOpt) {
        int contextEnd = contextStart + contextLength;
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return native_getTextRunCursor(mNativePaint, text,
                contextStart, contextLength, flags, offset, cursorOpt);
    }

    /**
     * Returns the next cursor position in the run.  This avoids placing the
     * cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering
     * cluster.
     *
     * <p>ContextStart, contextEnd, and offset are relative to the start of
     * text.  The context is the shaping context for cursor movement, generally
     * the bounds of the metric span enclosing the cursor in the direction of
     * movement.
     *
     * <p>If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid
     * cursor position, this returns -1.  Otherwise this will never return a
     * value before contextStart or after contextEnd.
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextEnd the end of the context
     * @param flags either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(CharSequence text, int contextStart,
           int contextEnd, int flags, int offset, int cursorOpt) {

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            return getTextRunCursor(text.toString(), contextStart, contextEnd,
                    flags, offset, cursorOpt);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextRunCursor(
                    contextStart, contextEnd, flags, offset, cursorOpt, this);
        }

        int contextLen = contextEnd - contextStart;
        char[] buf = TemporaryBuffer.obtain(contextLen);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        int result = getTextRunCursor(buf, 0, contextLen, flags, offset - contextStart, cursorOpt);
        TemporaryBuffer.recycle(buf);
        return result;
    }

    /**
     * Returns the next cursor position in the run.  This avoids placing the
     * cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering
     * cluster.
     *
     * <p>ContextStart, contextEnd, and offset are relative to the start of
     * text.  The context is the shaping context for cursor movement, generally
     * the bounds of the metric span enclosing the cursor in the direction of
     * movement.
     *
     * <p>If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid
     * cursor position, this returns -1.  Otherwise this will never return a
     * value before contextStart or after contextEnd.
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextEnd the end of the context
     * @param flags either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(String text, int contextStart, int contextEnd,
            int flags, int offset, int cursorOpt) {
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length() - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return native_getTextRunCursor(mNativePaint, text,
                contextStart, contextEnd, flags, offset, cursorOpt);
    }

    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting in
     * the paint.
     *
     * @param text     The text to retrieve the path from
     * @param index    The index of the first character in text
     * @param count    The number of characterss starting with index
     * @param x        The x coordinate of the text's origin
     * @param y        The y coordinate of the text's origin
     * @param path     The path to receive the data describing the text. Must
     *                 be allocated by the caller.
     */
    public void getTextPath(char[] text, int index, int count,
                            float x, float y, Path path) {
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        native_getTextPath(mNativePaint, mBidiFlags, text, index, count, x, y, 
                path.ni());
    }

    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting
     * in the paint.
     *
     * @param text  The text to retrieve the path from
     * @param start The first character in the text
     * @param end   1 past the last charcter in the text
     * @param x     The x coordinate of the text's origin
     * @param y     The y coordinate of the text's origin
     * @param path  The path to receive the data describing the text. Must
     *              be allocated by the caller.
     */
    public void getTextPath(String text, int start, int end,
                            float x, float y, Path path) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        native_getTextPath(mNativePaint, mBidiFlags, text, start, end, x, y, 
                path.ni());
    }
    
    /**
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text  String to measure and return its bounds
     * @param start Index of the first char in the string to measure
     * @param end   1 past the last char in the string measure
     * @param bounds Returns the unioned bounds of all the text. Must be
     *               allocated by the caller.
     */
    public void getTextBounds(String text, int start, int end, Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        nativeGetStringBounds(mNativePaint, text, start, end, bounds);
    }
    
    /**
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text  Array of chars to measure and return their unioned bounds
     * @param index Index of the first char in the array to measure
     * @param count The number of chars, beginning at index, to measure
     * @param bounds Returns the unioned bounds of all the text. Must be
     *               allocated by the caller.
     */
    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        nativeGetCharArrayBounds(mNativePaint, text, index, count, bounds);
    }
    
    @Override
    protected void finalize() throws Throwable {
        try {
            finalizer(mNativePaint);
        } finally {
            super.finalize();
        }
    }

    private static native int native_init();
    private static native int native_initWithPaint(int paint);
    private static native void native_reset(int native_object);
    private static native void native_set(int native_dst, int native_src);
    private static native int native_getStyle(int native_object);
    private static native void native_setStyle(int native_object, int style);
    private static native int native_getStrokeCap(int native_object);
    private static native void native_setStrokeCap(int native_object, int cap);
    private static native int native_getStrokeJoin(int native_object);
    private static native void native_setStrokeJoin(int native_object,
                                                    int join);
    private static native boolean native_getFillPath(int native_object,
                                                     int src, int dst);
    private static native int native_setShader(int native_object, int shader);
    private static native int native_setColorFilter(int native_object,
                                                    int filter);
    private static native int native_setXfermode(int native_object,
                                                 int xfermode);
    private static native int native_setPathEffect(int native_object,
                                                   int effect);
    private static native int native_setMaskFilter(int native_object,
                                                   int maskfilter);
    private static native int native_setTypeface(int native_object,
                                                 int typeface);
    private static native int native_setRasterizer(int native_object,
                                                   int rasterizer);

    private static native int native_getTextAlign(int native_object);
    private static native void native_setTextAlign(int native_object,
                                                   int align);

    private static native void native_setTextLocale(int native_object,
                                                    String locale);

    private static native int native_getTextWidths(int native_object,
                            char[] text, int index, int count, float[] widths);
    private static native int native_getTextWidths(int native_object,
                            String text, int start, int end, float[] widths);

    private static native int native_getTextGlyphs(int native_object,
            String text, int start, int end, int contextStart, int contextEnd,
            int flags, char[] glyphs);

    private static native float native_getTextRunAdvances(int native_object,
            char[] text, int index, int count, int contextIndex, int contextCount,
            int flags, float[] advances, int advancesIndex, int reserved);
    private static native float native_getTextRunAdvances(int native_object,
            String text, int start, int end, int contextStart, int contextEnd,
            int flags, float[] advances, int advancesIndex, int reserved);

    private native int native_getTextRunCursor(int native_object, char[] text,
            int contextStart, int contextLength, int flags, int offset, int cursorOpt);
    private native int native_getTextRunCursor(int native_object, String text,
            int contextStart, int contextEnd, int flags, int offset, int cursorOpt);

    private static native void native_getTextPath(int native_object, int bidiFlags,
                char[] text, int index, int count, float x, float y, int path);
    private static native void native_getTextPath(int native_object, int bidiFlags,
                String text, int start, int end, float x, float y, int path);
    private static native void nativeGetStringBounds(int nativePaint,
                                String text, int start, int end, Rect bounds);
    private static native void nativeGetCharArrayBounds(int nativePaint,
                                char[] text, int index, int count, Rect bounds);
    private static native void finalizer(int nativePaint);
}
