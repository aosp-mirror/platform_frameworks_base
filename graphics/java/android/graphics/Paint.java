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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Size;
import android.os.LocaleList;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.Locale;

import libcore.util.NativeAllocationRegistry;

/**
 * The Paint class holds the style and color information about how to draw
 * geometries, text and bitmaps.
 */
public class Paint {

    private long mNativePaint;
    private long mNativeShader = 0;

    // The approximate size of a native paint object.
    private static final long NATIVE_PAINT_SIZE = 98;

    // Use a Holder to allow static initialization of Paint in the boot image.
    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry = new NativeAllocationRegistry(
                Paint.class.getClassLoader(), nGetNativeFinalizer(), NATIVE_PAINT_SIZE);
    }

    /**
     * @hide
     */
    public long mNativeTypeface;

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

    private LocaleList  mLocales;
    private String      mFontFeatureSettings;

    private static final Object sCacheLock = new Object();

    /**
     * Cache for the Minikin language list ID.
     *
     * A map from a string representation of the LocaleList to Minikin's language list ID.
     */
    @GuardedBy("sCacheLock")
    private static final HashMap<String, Integer> sMinikinLangListIdCache = new HashMap<>();

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

    /**
     * Paint flag that enables antialiasing when drawing.
     *
     * <p>Enabling this flag will cause all draw operations that support
     * antialiasing to use it.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int ANTI_ALIAS_FLAG     = 0x01;
    /**
     * Paint flag that enables bilinear sampling on scaled bitmaps.
     *
     * <p>If cleared, scaled bitmaps will be drawn with nearest neighbor
     * sampling, likely resulting in artifacts. This should generally be on
     * when drawing bitmaps, unless performance-bound (rendering to software
     * canvas) or preferring pixelation artifacts to blurriness when scaling
     * significantly.</p>
     *
     * <p>If bitmaps are scaled for device density at creation time (as
     * resource bitmaps often are) the filtering will already have been
     * done.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int FILTER_BITMAP_FLAG  = 0x02;
    /**
     * Paint flag that enables dithering when blitting.
     *
     * <p>Enabling this flag applies a dither to any blit operation where the
     * target's colour space is more constrained than the source.
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int DITHER_FLAG         = 0x04;
    /**
     * Paint flag that applies an underline decoration to drawn text.
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int UNDERLINE_TEXT_FLAG = 0x08;
    /**
     * Paint flag that applies a strike-through decoration to drawn text.
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int STRIKE_THRU_TEXT_FLAG = 0x10;
    /**
     * Paint flag that applies a synthetic bolding effect to drawn text.
     *
     * <p>Enabling this flag will cause text draw operations to apply a
     * simulated bold effect when drawing a {@link Typeface} that is not
     * already bold.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int FAKE_BOLD_TEXT_FLAG = 0x20;
    /**
     * Paint flag that enables smooth linear scaling of text.
     *
     * <p>Enabling this flag does not actually scale text, but rather adjusts
     * text draw operations to deal gracefully with smooth adjustment of scale.
     * When this flag is enabled, font hinting is disabled to prevent shape
     * deformation between scale factors, and glyph caching is disabled due to
     * the large number of glyph images that will be generated.</p>
     *
     * <p>{@link #SUBPIXEL_TEXT_FLAG} should be used in conjunction with this
     * flag to prevent glyph positions from snapping to whole pixel values as
     * scale factor is adjusted.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int LINEAR_TEXT_FLAG    = 0x40;
    /**
     * Paint flag that enables subpixel positioning of text.
     *
     * <p>Enabling this flag causes glyph advances to be computed with subpixel
     * accuracy.</p>
     *
     * <p>This can be used with {@link #LINEAR_TEXT_FLAG} to prevent text from
     * jittering during smooth scale transitions.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int SUBPIXEL_TEXT_FLAG  = 0x80;
    /** Legacy Paint flag, no longer used. */
    public static final int DEV_KERN_TEXT_FLAG  = 0x100;
    /** @hide bit mask for the flag enabling subpixel glyph rendering for text */
    public static final int LCD_RENDER_TEXT_FLAG = 0x200;
    /**
     * Paint flag that enables the use of bitmap fonts when drawing text.
     *
     * <p>Disabling this flag will prevent text draw operations from using
     * embedded bitmap strikes in fonts, causing fonts with both scalable
     * outlines and bitmap strikes to draw only the scalable outlines, and
     * fonts with only bitmap strikes to not draw at all.</p>
     *
     * @see #Paint(int)
     * @see #setFlags(int)
     */
    public static final int EMBEDDED_BITMAP_TEXT_FLAG = 0x400;
    /** @hide bit mask for the flag forcing freetype's autohinter on for text */
    public static final int AUTO_HINTING_TEXT_FLAG = 0x800;
    /** @hide bit mask for the flag enabling vertical rendering for text */
    public static final int VERTICAL_TEXT_FLAG = 0x1000;

    // These flags are always set on a new/reset paint, even if flags 0 is passed.
    static final int HIDDEN_DEFAULT_PAINT_FLAGS = DEV_KERN_TEXT_FLAG | EMBEDDED_BITMAP_TEXT_FLAG;

    /**
     * Font hinter option that disables font hinting.
     *
     * @see #setHinting(int)
     */
    public static final int HINTING_OFF = 0x0;

    /**
     * Font hinter option that enables font hinting.
     *
     * @see #setHinting(int)
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
        mNativePaint = nInit();
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mNativePaint);
        setFlags(flags | HIDDEN_DEFAULT_PAINT_FLAGS);
        // TODO: Turning off hinting has undesirable side effects, we need to
        //       revisit hinting once we add support for subpixel positioning
        // setHinting(DisplayMetrics.DENSITY_DEVICE >= DisplayMetrics.DENSITY_TV
        //        ? HINTING_OFF : HINTING_ON);
        mCompatScaling = mInvCompatScaling = 1;
        setTextLocales(LocaleList.getAdjustedDefault());
    }

    /**
     * Create a new paint, initialized with the attributes in the specified
     * paint parameter.
     *
     * @param paint Existing paint used to initialized the attributes of the
     *              new paint.
     */
    public Paint(Paint paint) {
        mNativePaint = nInitWithPaint(paint.getNativeInstance());
        NoImagePreloadHolder.sRegistry.registerNativeAllocation(this, mNativePaint);
        setClassVariablesFrom(paint);
    }

    /** Restores the paint to its default settings. */
    public void reset() {
        nReset(mNativePaint);
        setFlags(HIDDEN_DEFAULT_PAINT_FLAGS);

        // TODO: Turning off hinting has undesirable side effects, we need to
        //       revisit hinting once we add support for subpixel positioning
        // setHinting(DisplayMetrics.DENSITY_DEVICE >= DisplayMetrics.DENSITY_TV
        //        ? HINTING_OFF : HINTING_ON);

        mColorFilter = null;
        mMaskFilter = null;
        mPathEffect = null;
        mRasterizer = null;
        mShader = null;
        mNativeShader = 0;
        mTypeface = null;
        mNativeTypeface = 0;
        mXfermode = null;

        mHasCompatScaling = false;
        mCompatScaling = 1;
        mInvCompatScaling = 1;

        mBidiFlags = BIDI_DEFAULT_LTR;
        setTextLocales(LocaleList.getAdjustedDefault());
        setElegantTextHeight(false);
        mFontFeatureSettings = null;
    }

    /**
     * Copy the fields from src into this paint. This is equivalent to calling
     * get() on all of the src fields, and calling the corresponding set()
     * methods on this.
     */
    public void set(Paint src) {
        if (this != src) {
            // copy over the native settings
            nSet(mNativePaint, src.mNativePaint);
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
        mNativeShader = paint.mNativeShader;
        mTypeface = paint.mTypeface;
        mNativeTypeface = paint.mNativeTypeface;
        mXfermode = paint.mXfermode;

        mHasCompatScaling = paint.mHasCompatScaling;
        mCompatScaling = paint.mCompatScaling;
        mInvCompatScaling = paint.mInvCompatScaling;

        mBidiFlags = paint.mBidiFlags;
        mLocales = paint.mLocales;
        mFontFeatureSettings = paint.mFontFeatureSettings;
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
     * Return the pointer to the native object while ensuring that any
     * mutable objects that are attached to the paint are also up-to-date.
     *
     * @hide
     */
    public long getNativeInstance() {
        long newNativeShader = mShader == null ? 0 : mShader.getNativeInstance();
        if (newNativeShader != mNativeShader) {
            mNativeShader = newNativeShader;
            nSetShader(mNativePaint, mNativeShader);
        }
        return mNativePaint;
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
    public int getFlags() {
        return nGetFlags(mNativePaint);
    }

    private native int nGetFlags(long paintPtr);

    /**
     * Set the paint's flags. Use the Flag enum to specific flag values.
     *
     * @param flags The new flag bits for the paint
     */
    public void setFlags(int flags) {
        nSetFlags(mNativePaint, flags);
    }

    private native void nSetFlags(long paintPtr, int flags);

    /**
     * Return the paint's hinting mode.  Returns either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public int getHinting() {
        return nGetHinting(mNativePaint);
    }

    private native int nGetHinting(long paintPtr);

    /**
     * Set the paint's hinting mode.  May be either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public void setHinting(int mode) {
        nSetHinting(mNativePaint, mode);
    }

    private native void nSetHinting(long paintPtr, int mode);

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
    public void setAntiAlias(boolean aa) {
        nSetAntiAlias(mNativePaint, aa);
    }

    private native void nSetAntiAlias(long paintPtr, boolean aa);

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
    public void setDither(boolean dither) {
        nSetDither(mNativePaint, dither);
    }

    private native void nSetDither(long paintPtr, boolean dither);

    /**
     * Helper for getFlags(), returning true if LINEAR_TEXT_FLAG bit is set
     *
     * @return true if the lineartext bit is set in the paint's flags
     */
    public final boolean isLinearText() {
        return (getFlags() & LINEAR_TEXT_FLAG) != 0;
    }

    /**
     * Helper for setFlags(), setting or clearing the LINEAR_TEXT_FLAG bit
     *
     * @param linearText true to set the linearText bit in the paint's flags,
     *                   false to clear it.
     */
    public void setLinearText(boolean linearText) {
        nSetLinearText(mNativePaint, linearText);
    }

    private native void nSetLinearText(long paintPtr, boolean linearText);

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
    public void setSubpixelText(boolean subpixelText) {
        nSetSubpixelText(mNativePaint, subpixelText);
    }

    private native void nSetSubpixelText(long paintPtr, boolean subpixelText);

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
    public void setUnderlineText(boolean underlineText) {
        nSetUnderlineText(mNativePaint, underlineText);
    }

    private native void nSetUnderlineText(long paintPtr, boolean underlineText);

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
    public void setStrikeThruText(boolean strikeThruText) {
        nSetStrikeThruText(mNativePaint, strikeThruText);
    }

    private native void nSetStrikeThruText(long paintPtr, boolean strikeThruText);

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
    public void setFakeBoldText(boolean fakeBoldText) {
        nSetFakeBoldText(mNativePaint, fakeBoldText);
    }

    private native void nSetFakeBoldText(long paintPtr, boolean fakeBoldText);

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
    public void setFilterBitmap(boolean filter) {
        nSetFilterBitmap(mNativePaint, filter);
    }

    private native void nSetFilterBitmap(long paintPtr, boolean filter);

    /**
     * Return the paint's style, used for controlling how primitives'
     * geometries are interpreted (except for drawBitmap, which always assumes
     * FILL_STYLE).
     *
     * @return the paint's style setting (Fill, Stroke, StrokeAndFill)
     */
    public Style getStyle() {
        return sStyleArray[nGetStyle(mNativePaint)];
    }

    /**
     * Set the paint's style, used for controlling how primitives'
     * geometries are interpreted (except for drawBitmap, which always assumes
     * Fill).
     *
     * @param style The new style to set in the paint
     */
    public void setStyle(Style style) {
        nSetStyle(mNativePaint, style.nativeInt);
    }

    /**
     * Return the paint's color. Note that the color is a 32bit value
     * containing alpha as well as r,g,b. This 32bit value is not premultiplied,
     * meaning that its alpha can be any value, regardless of the values of
     * r,g,b. See the Color class for more details.
     *
     * @return the paint's color (and alpha).
     */
    @ColorInt
    public int getColor() {
        return nGetColor(mNativePaint);
    }

    private native int nGetColor(long paintPtr);

    /**
     * Set the paint's color. Note that the color is an int containing alpha
     * as well as r,g,b. This 32bit value is not premultiplied, meaning that
     * its alpha can be any value, regardless of the values of r,g,b.
     * See the Color class for more details.
     *
     * @param color The new color (including alpha) to set in the paint.
     */
    public void setColor(@ColorInt int color) {
        nSetColor(mNativePaint, color);
    }

    private native void nSetColor(long paintPtr, @ColorInt int color);

    /**
     * Helper to getColor() that just returns the color's alpha value. This is
     * the same as calling getColor() >>> 24. It always returns a value between
     * 0 (completely transparent) and 255 (completely opaque).
     *
     * @return the alpha component of the paint's color.
     */
    public int getAlpha() {
        return nGetAlpha(mNativePaint);
    }

    private native int nGetAlpha(long paintPtr);

    /**
     * Helper to setColor(), that only assigns the color's alpha value,
     * leaving its r,g,b values unchanged. Results are undefined if the alpha
     * value is outside of the range [0..255]
     *
     * @param a set the alpha component [0..255] of the paint's color.
     */
    public void setAlpha(int a) {
        nSetAlpha(mNativePaint, a);
    }

    private native void nSetAlpha(long paintPtr, int a);

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
    public float getStrokeWidth() {
        return nGetStrokeWidth(mNativePaint);
    }

    private native float nGetStrokeWidth(long paintPtr);

    /**
     * Set the width for stroking.
     * Pass 0 to stroke in hairline mode.
     * Hairlines always draws a single pixel independent of the canva's matrix.
     *
     * @param width set the paint's stroke width, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public void setStrokeWidth(float width) {
        nSetStrokeWidth(mNativePaint, width);
    }

    private native void nSetStrokeWidth(long paintPtr, float width);

    /**
     * Return the paint's stroke miter value. Used to control the behavior
     * of miter joins when the joins angle is sharp.
     *
     * @return the paint's miter limit, used whenever the paint's style is
     *         Stroke or StrokeAndFill.
     */
    public float getStrokeMiter() {
        return nGetStrokeMiter(mNativePaint);
    }

    private native float nGetStrokeMiter(long paintPtr);

    /**
     * Set the paint's stroke miter value. This is used to control the behavior
     * of miter joins when the joins angle is sharp. This value must be >= 0.
     *
     * @param miter set the miter limit on the paint, used whenever the paint's
     *              style is Stroke or StrokeAndFill.
     */
    public void setStrokeMiter(float miter) {
        nSetStrokeMiter(mNativePaint, miter);
    }

    private native void nSetStrokeMiter(long paintPtr, float miter);

    /**
     * Return the paint's Cap, controlling how the start and end of stroked
     * lines and paths are treated.
     *
     * @return the line cap style for the paint, used whenever the paint's
     *         style is Stroke or StrokeAndFill.
     */
    public Cap getStrokeCap() {
        return sCapArray[nGetStrokeCap(mNativePaint)];
    }

    /**
     * Set the paint's Cap.
     *
     * @param cap set the paint's line cap style, used whenever the paint's
     *            style is Stroke or StrokeAndFill.
     */
    public void setStrokeCap(Cap cap) {
        nSetStrokeCap(mNativePaint, cap.nativeInt);
    }

    /**
     * Return the paint's stroke join type.
     *
     * @return the paint's Join.
     */
    public Join getStrokeJoin() {
        return sJoinArray[nGetStrokeJoin(mNativePaint)];
    }

    /**
     * Set the paint's Join.
     *
     * @param join set the paint's Join, used whenever the paint's style is
     *             Stroke or StrokeAndFill.
     */
    public void setStrokeJoin(Join join) {
        nSetStrokeJoin(mNativePaint, join.nativeInt);
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
        return nGetFillPath(mNativePaint, src.readOnlyNI(), dst.mutateNI());
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
        // If mShader changes, cached value of native shader aren't valid, since
        // old shader's pointer may be reused by another shader allocation later
        if (mShader != shader) {
            mNativeShader = -1;
        }
        // Defer setting the shader natively until getNativeInstance() is called
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
        long filterNative = 0;
        if (filter != null)
            filterNative = filter.native_instance;
        nSetColorFilter(mNativePaint, filterNative);
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
        long xfermodeNative = 0;
        if (xfermode != null)
            xfermodeNative = xfermode.native_instance;
        nSetXfermode(mNativePaint, xfermodeNative);
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
        long effectNative = 0;
        if (effect != null) {
            effectNative = effect.native_instance;
        }
        nSetPathEffect(mNativePaint, effectNative);
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
        long maskfilterNative = 0;
        if (maskfilter != null) {
            maskfilterNative = maskfilter.native_instance;
        }
        nSetMaskFilter(mNativePaint, maskfilterNative);
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
        long typefaceNative = 0;
        if (typeface != null) {
            typefaceNative = typeface.native_instance;
        }
        nSetTypeface(mNativePaint, typefaceNative);
        mTypeface = typeface;
        mNativeTypeface = typefaceNative;
        return typeface;
    }

    /**
     * Get the paint's rasterizer (or null).
     * <p />
     * The raster controls/modifies how paths/text are turned into alpha masks.
     *
     * @return         the paint's rasterizer (or null)
     *
     *  @deprecated Rasterizer is not supported by either the HW or PDF backends.
     */
    @Deprecated
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
     *
     *  @deprecated Rasterizer is not supported by either the HW or PDF backends.
     */
    @Deprecated
    public Rasterizer setRasterizer(Rasterizer rasterizer) {
        long rasterizerNative = 0;
        if (rasterizer != null) {
            rasterizerNative = rasterizer.native_instance;
        }
        nSetRasterizer(mNativePaint, rasterizerNative);
        mRasterizer = rasterizer;
        return rasterizer;
    }

    /**
     * This draws a shadow layer below the main layer, with the specified
     * offset and color, and blur radius. If radius is 0, then the shadow
     * layer is removed.
     * <p>
     * Can be used to create a blurred shadow underneath text. Support for use
     * with other drawing operations is constrained to the software rendering
     * pipeline.
     * <p>
     * The alpha of the shadow will be the paint's alpha if the shadow color is
     * opaque, or the alpha from the shadow color if not.
     */
    public void setShadowLayer(float radius, float dx, float dy, int shadowColor) {
      nSetShadowLayer(mNativePaint, radius, dx, dy, shadowColor);
    }

    /**
     * Clear the shadow layer.
     */
    public void clearShadowLayer() {
        setShadowLayer(0, 0, 0, 0);
    }

    /**
     * Checks if the paint has a shadow layer attached
     *
     * @return true if the paint has a shadow layer attached and false otherwise
     * @hide
     */
    public boolean hasShadowLayer() {
        return nHasShadowLayer(mNativePaint);
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
        return sAlignArray[nGetTextAlign(mNativePaint)];
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
        nSetTextAlign(mNativePaint, align.nativeInt);
    }

    /**
     * Get the text's primary Locale. Note that this is not all of the locale-related information
     * Paint has. Use {@link #getTextLocales()} to get the complete list.
     *
     * @return the paint's primary Locale used for drawing text, never null.
     */
    @NonNull
    public Locale getTextLocale() {
        return mLocales.get(0);
    }

    /**
     * Get the text locale list.
     *
     * @return the paint's LocaleList used for drawing text, never null or empty.
     */
    @NonNull @Size(min=1)
    public LocaleList getTextLocales() {
        return mLocales;
    }

    /**
     * Set the text locale list to a one-member list consisting of just the locale.
     *
     * See {@link #setTextLocales(LocaleList)} for how the locale list affects
     * the way the text is drawn for some languages.
     *
     * @param locale the paint's locale value for drawing text, must not be null.
     */
    public void setTextLocale(@NonNull Locale locale) {
        if (locale == null) {
            throw new IllegalArgumentException("locale cannot be null");
        }
        if (mLocales != null && mLocales.size() == 1 && locale.equals(mLocales.get(0))) {
            return;
        }
        mLocales = new LocaleList(locale);
        syncTextLocalesWithMinikin();
    }

    /**
     * Set the text locale list.
     *
     * The text locale list affects how the text is drawn for some languages.
     *
     * For example, if the locale list contains {@link Locale#CHINESE} or {@link Locale#CHINA},
     * then the text renderer will prefer to draw text using a Chinese font. Likewise,
     * if the locale list contains {@link Locale#JAPANESE} or {@link Locale#JAPAN}, then the text
     * renderer will prefer to draw text using a Japanese font. If the locale list contains both,
     * the order those locales appear in the list is considered for deciding the font.
     *
     * This distinction is important because Chinese and Japanese text both use many
     * of the same Unicode code points but their appearance is subtly different for
     * each language.
     *
     * By default, the text locale list is initialized to a one-member list just containing the
     * system locales. This assumes that the text to be rendered will most likely be in the user's
     * preferred language.
     *
     * If the actual language or languages of the text is/are known, then they can be provided to
     * the text renderer using this method. The text renderer may attempt to guess the
     * language script based on the contents of the text to be drawn independent of
     * the text locale here. Specifying the text locales just helps it do a better
     * job in certain ambiguous cases.
     *
     * @param locales the paint's locale list for drawing text, must not be null or empty.
     */
    public void setTextLocales(@NonNull @Size(min=1) LocaleList locales) {
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("locales cannot be null or empty");
        }
        if (locales.equals(mLocales)) return;
        mLocales = locales;
        syncTextLocalesWithMinikin();
    }

    private void syncTextLocalesWithMinikin() {
        final String languageTags = mLocales.toLanguageTags();
        final Integer minikinLangListId;
        synchronized (sCacheLock) {
            minikinLangListId = sMinikinLangListIdCache.get(languageTags);
            if (minikinLangListId == null) {
                final int newID = nSetTextLocales(mNativePaint, languageTags);
                sMinikinLangListIdCache.put(languageTags, newID);
                return;
            }
        }
        nSetTextLocalesByMinikinLangListId(mNativePaint, minikinLangListId.intValue());
    }

    /**
     * Get the elegant metrics flag.
     *
     * @return true if elegant metrics are enabled for text drawing.
     */
    public boolean isElegantTextHeight() {
        return nIsElegantTextHeight(mNativePaint);
    }

    private native boolean nIsElegantTextHeight(long paintPtr);

    /**
     * Set the paint's elegant height metrics flag. This setting selects font
     * variants that have not been compacted to fit Latin-based vertical
     * metrics, and also increases top and bottom bounds to provide more space.
     *
     * @param elegant set the paint's elegant metrics flag for drawing text.
     */
    public void setElegantTextHeight(boolean elegant) {
        nSetElegantTextHeight(mNativePaint, elegant);
    }

    private native void nSetElegantTextHeight(long paintPtr, boolean elegant);

    /**
     * Return the paint's text size.
     *
     * @return the paint's text size.
     */
    public float getTextSize() {
        return nGetTextSize(mNativePaint);
    }

    private native float nGetTextSize(long paintPtr);

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param textSize set the paint's text size.
     */
    public void setTextSize(float textSize) {
        nSetTextSize(mNativePaint, textSize);
    }

    private native void nSetTextSize(long paintPtr, float textSize);

    /**
     * Return the paint's horizontal scale factor for text. The default value
     * is 1.0.
     *
     * @return the paint's scale factor in X for drawing/measuring text
     */
    public float getTextScaleX() {
        return nGetTextScaleX(mNativePaint);
    }

    private native float nGetTextScaleX(long paintPtr);

    /**
     * Set the paint's horizontal scale factor for text. The default value
     * is 1.0. Values > 1.0 will stretch the text wider. Values < 1.0 will
     * stretch the text narrower.
     *
     * @param scaleX set the paint's scale in X for drawing/measuring text.
     */
    public void setTextScaleX(float scaleX) {
        nSetTextScaleX(mNativePaint, scaleX);
    }

    private native void nSetTextScaleX(long paintPtr, float scaleX);

    /**
     * Return the paint's horizontal skew factor for text. The default value
     * is 0.
     *
     * @return         the paint's skew factor in X for drawing text.
     */
    public float getTextSkewX() {
        return nGetTextSkewX(mNativePaint);
    }

    private native float nGetTextSkewX(long paintPtr);

    /**
     * Set the paint's horizontal skew factor for text. The default value
     * is 0. For approximating oblique text, use values around -0.25.
     *
     * @param skewX set the paint's skew factor in X for drawing text.
     */
    public void setTextSkewX(float skewX) {
        nSetTextSkewX(mNativePaint, skewX);
    }

    private native void nSetTextSkewX(long paintPtr, float skewX);

    /**
     * Return the paint's letter-spacing for text. The default value
     * is 0.
     *
     * @return         the paint's letter-spacing for drawing text.
     */
    public float getLetterSpacing() {
        return nGetLetterSpacing(mNativePaint);
    }

    /**
     * Set the paint's letter-spacing for text. The default value
     * is 0.  The value is in 'EM' units.  Typical values for slight
     * expansion will be around 0.05.  Negative values tighten text.
     *
     * @param letterSpacing set the paint's letter-spacing for drawing text.
     */
    public void setLetterSpacing(float letterSpacing) {
        nSetLetterSpacing(mNativePaint, letterSpacing);
    }

    /**
     * Returns the font feature settings. The format is the same as the CSS
     * font-feature-settings attribute:
     * <a href="http://dev.w3.org/csswg/css-fonts/#propdef-font-feature-settings">
     *     http://dev.w3.org/csswg/css-fonts/#propdef-font-feature-settings</a>
     *
     * @return the paint's currently set font feature settings. Default is null.
     *
     * @see #setFontFeatureSettings(String)
     */
    public String getFontFeatureSettings() {
        return mFontFeatureSettings;
    }

    /**
     * Set font feature settings.
     *
     * The format is the same as the CSS font-feature-settings attribute:
     * <a href="http://dev.w3.org/csswg/css-fonts/#propdef-font-feature-settings">
     *     http://dev.w3.org/csswg/css-fonts/#propdef-font-feature-settings</a>
     *
     * @see #getFontFeatureSettings()
     *
     * @param settings the font feature settings string to use, may be null.
     */
    public void setFontFeatureSettings(String settings) {
        if (settings != null && settings.equals("")) {
            settings = null;
        }
        if ((settings == null && mFontFeatureSettings == null)
                || (settings != null && settings.equals(mFontFeatureSettings))) {
            return;
        }
        mFontFeatureSettings = settings;
        nSetFontFeatureSettings(mNativePaint, settings);
    }

    /**
     * Get the current value of hyphen edit.
     *
     * @return the current hyphen edit value
     *
     * @hide
     */
    public int getHyphenEdit() {
        return nGetHyphenEdit(mNativePaint);
    }

    /**
     * Set a hyphen edit on the paint (causes a hyphen to be added to text when
     * measured or drawn).
     *
     * @param hyphen 0 for no edit, 1 for adding a hyphen (other values in future)
     *
     * @hide
     */
    public void setHyphenEdit(int hyphen) {
        nSetHyphenEdit(mNativePaint, hyphen);
    }

    /**
     * Return the distance above (negative) the baseline (ascent) based on the
     * current typeface and text size.
     *
     * @return the distance above (negative) the baseline (ascent) based on the
     *         current typeface and text size.
     */
    public float ascent() {
        return nAscent(mNativePaint, mNativeTypeface);
    }

    private native float nAscent(long paintPtr, long typefacePtr);

    /**
     * Return the distance below (positive) the baseline (descent) based on the
     * current typeface and text size.
     *
     * @return the distance below (positive) the baseline (descent) based on
     *         the current typeface and text size.
     */
    public float descent() {
        return nDescent(mNativePaint, mNativeTypeface);
    }

    private native float nDescent(long paintPtr, long typefacePtr);

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
    public float getFontMetrics(FontMetrics metrics) {
        return nGetFontMetrics(mNativePaint, mNativeTypeface, metrics);
    }

    private native float nGetFontMetrics(long paintPtr,
            long typefacePtr, FontMetrics metrics);

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
    public int getFontMetricsInt(FontMetricsInt fmi) {
        return nGetFontMetricsInt(mNativePaint, mNativeTypeface, fmi);
    }

    private native int nGetFontMetricsInt(long paintPtr,
            long typefacePtr, FontMetricsInt fmi);

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
            return (float) Math.ceil(nGetTextAdvances(mNativePaint, mNativeTypeface, text,
                    index, count, index, count, mBidiFlags, null, 0));
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float w = nGetTextAdvances(mNativePaint, mNativeTypeface, text, index, count, index,
                count, mBidiFlags, null, 0);
        setTextSize(oldSize);
        return (float) Math.ceil(w*mInvCompatScaling);
    }

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
            return (float) Math.ceil(nGetTextAdvances(mNativePaint, mNativeTypeface, text,
                    start, end, start, end, mBidiFlags, null, 0));
        }
        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float w = nGetTextAdvances(mNativePaint, mNativeTypeface, text, start, end, start,
                end, mBidiFlags, null, 0);
        setTextSize(oldSize);
        return (float) Math.ceil(w * mInvCompatScaling);
    }

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
        return measureText(text, 0, text.length());
    }

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
            return nBreakText(mNativePaint, mNativeTypeface, text, index, count, maxWidth,
                    mBidiFlags, measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        int res = nBreakText(mNativePaint, mNativeTypeface, text, index, count,
                maxWidth * mCompatScaling, mBidiFlags, measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

    private static native int nBreakText(long nObject, long nTypeface,
                                               char[] text, int index, int count,
                                               float maxWidth, int bidiFlags, float[] measuredWidth);

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
            return nBreakText(mNativePaint, mNativeTypeface, text, measureForwards,
                    maxWidth, mBidiFlags, measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        int res = nBreakText(mNativePaint, mNativeTypeface, text, measureForwards,
                maxWidth*mCompatScaling, mBidiFlags, measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

    private static native int nBreakText(long nObject, long nTypeface,
                                        String text, boolean measureForwards,
                                        float maxWidth, int bidiFlags, float[] measuredWidth);

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
            nGetTextAdvances(mNativePaint, mNativeTypeface, text, index, count, index, count,
                    mBidiFlags, widths, 0);
            return count;
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        nGetTextAdvances(mNativePaint, mNativeTypeface, text, index, count, index, count,
                mBidiFlags, widths, 0);
        setTextSize(oldSize);
        for (int i = 0; i < count; i++) {
            widths[i] *= mInvCompatScaling;
        }
        return count;
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
     * @return       the number of code units in the specified text.
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
            nGetTextAdvances(mNativePaint, mNativeTypeface, text, start, end, start, end,
                    mBidiFlags, widths, 0);
            return end - start;
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        nGetTextAdvances(mNativePaint, mNativeTypeface, text, start, end, start, end,
                mBidiFlags, widths, 0);
        setTextSize(oldSize);
        for (int i = 0; i < end - start; i++) {
            widths[i] *= mInvCompatScaling;
        }
        return end - start;
    }

    /**
     * Return the advance widths for the characters in the string.
     *
     * @param text   The text to measure
     * @param widths array to receive the advance widths of the characters.
     *               Must be at least a large as the text.
     * @return       the number of code units in the specified text.
     */
    public int getTextWidths(String text, float[] widths) {
        return getTextWidths(text, 0, text.length(), widths);
    }

    /**
     * Convenience overload that takes a char array instead of a
     * String.
     *
     * @see #getTextRunAdvances(String, int, int, int, int, boolean, float[], int)
     * @hide
     */
    public float getTextRunAdvances(char[] chars, int index, int count,
            int contextIndex, int contextCount, boolean isRtl, float[] advances,
            int advancesIndex) {

        if (chars == null) {
            throw new IllegalArgumentException("text cannot be null");
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
            return nGetTextAdvances(mNativePaint, mNativeTypeface, chars, index, count,
                    contextIndex, contextCount, isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances,
                    advancesIndex);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float res = nGetTextAdvances(mNativePaint, mNativeTypeface, chars, index, count,
                contextIndex, contextCount, isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances,
                advancesIndex);
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
     * @see #getTextRunAdvances(String, int, int, int, int, boolean, float[], int)
     * @hide
     */
    public float getTextRunAdvances(CharSequence text, int start, int end,
            int contextStart, int contextEnd, boolean isRtl, float[] advances,
            int advancesIndex) {
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
                    contextStart, contextEnd, isRtl, advances, advancesIndex);
        }
        if (text instanceof SpannedString ||
            text instanceof SpannableString) {
            return getTextRunAdvances(text.toString(), start, end,
                    contextStart, contextEnd, isRtl, advances, advancesIndex);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextRunAdvances(start, end,
                    contextStart, contextEnd, isRtl, advances, advancesIndex, this);
        }
        if (text.length() == 0 || end == start) {
            return 0f;
        }

        int contextLen = contextEnd - contextStart;
        int len = end - start;
        char[] buf = TemporaryBuffer.obtain(contextLen);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        float result = getTextRunAdvances(buf, start - contextStart, len,
                0, contextLen, isRtl, advances, advancesIndex);
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
     * @param isRtl whether the run is in RTL direction
     * @param advances array to receive the advances, must have room for all advances,
     * can be null if only total advance is needed
     * @param advancesIndex the position in advances at which to put the
     * advance corresponding to the character at start
     * @return the total advance
     *
     * @hide
     */
    public float getTextRunAdvances(String text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, float[] advances, int advancesIndex) {
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

        if (text.length() == 0 || start == end) {
            return 0f;
        }

        if (!mHasCompatScaling) {
            return nGetTextAdvances(mNativePaint, mNativeTypeface, text, start, end,
                    contextStart, contextEnd, isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances,
                    advancesIndex);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        float totalAdvance = nGetTextAdvances(mNativePaint, mNativeTypeface, text, start,
                end, contextStart, contextEnd, isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances,
                advancesIndex);
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
     * @param dir either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(char[] text, int contextStart, int contextLength,
            int dir, int offset, int cursorOpt) {
        int contextEnd = contextStart + contextLength;
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return nGetTextRunCursor(mNativePaint, text,
                contextStart, contextLength, dir, offset, cursorOpt);
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
     * @param dir either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(CharSequence text, int contextStart,
           int contextEnd, int dir, int offset, int cursorOpt) {

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            return getTextRunCursor(text.toString(), contextStart, contextEnd,
                    dir, offset, cursorOpt);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextRunCursor(
                    contextStart, contextEnd, dir, offset, cursorOpt, this);
        }

        int contextLen = contextEnd - contextStart;
        char[] buf = TemporaryBuffer.obtain(contextLen);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        int relPos = getTextRunCursor(buf, 0, contextLen, dir, offset - contextStart, cursorOpt);
        TemporaryBuffer.recycle(buf);
        return (relPos == -1) ? -1 : relPos + contextStart;
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
     * @param dir either {@link #DIRECTION_RTL} or {@link #DIRECTION_LTR}
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor, one of {@link #CURSOR_AFTER},
     * {@link #CURSOR_AT_OR_AFTER}, {@link #CURSOR_BEFORE},
     * {@link #CURSOR_AT_OR_BEFORE}, or {@link #CURSOR_AT}
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(String text, int contextStart, int contextEnd,
            int dir, int offset, int cursorOpt) {
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length() - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return nGetTextRunCursor(mNativePaint, text,
                contextStart, contextEnd, dir, offset, cursorOpt);
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
        nGetTextPath(mNativePaint, mNativeTypeface, mBidiFlags, text, index, count, x, y,
                path.mutateNI());
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
        nGetTextPath(mNativePaint, mNativeTypeface, mBidiFlags, text, start, end, x, y,
                path.mutateNI());
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
        nGetStringBounds(mNativePaint, mNativeTypeface, text, start, end, mBidiFlags, bounds);
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
        nGetCharArrayBounds(mNativePaint, mNativeTypeface, text, index, count, mBidiFlags,
            bounds);
    }

    /**
     * Determine whether the typeface set on the paint has a glyph supporting the string. The
     * simplest case is when the string contains a single character, in which this method
     * determines whether the font has the character. In the case of multiple characters, the
     * method returns true if there is a single glyph representing the ligature. For example, if
     * the input is a pair of regional indicator symbols, determine whether there is an emoji flag
     * for the pair.
     *
     * <p>Finally, if the string contains a variation selector, the method only returns true if
     * the fonts contains a glyph specific to that variation.
     *
     * <p>Checking is done on the entire fallback chain, not just the immediate font referenced.
     *
     * @param string the string to test whether there is glyph support
     * @return true if the typeface has a glyph for the string
     */
    public boolean hasGlyph(String string) {
        return nHasGlyph(mNativePaint, mNativeTypeface, mBidiFlags, string);
    }

    /**
     * Measure cursor position within a run of text.
     *
     * <p>The run of text includes the characters from {@code start} to {@code end} in the text. In
     * addition, the range {@code contextStart} to {@code contextEnd} is used as context for the
     * purpose of complex text shaping, such as Arabic text potentially shaped differently based on
     * the text next to it.
     *
     * <p>All text outside the range {@code contextStart..contextEnd} is ignored. The text between
     * {@code start} and {@code end} will be laid out to be measured.
     *
     * <p>The returned width measurement is the advance from {@code start} to {@code offset}. It is
     * generally a positive value, no matter the direction of the run. If {@code offset == end},
     * the return value is simply the width of the whole run from {@code start} to {@code end}.
     *
     * <p>Ligatures are formed for characters in the range {@code start..end} (but not for
     * {@code start..contextStart} or {@code end..contextEnd}). If {@code offset} points to a
     * character in the middle of such a formed ligature, but at a grapheme cluster boundary, the
     * return value will also reflect an advance in the middle of the ligature. See
     * {@link #getOffsetForAdvance} for more discussion of grapheme cluster boundaries.
     *
     * <p>The direction of the run is explicitly specified by {@code isRtl}. Thus, this method is
     * suitable only for runs of a single direction.
     *
     * <p>All indices are relative to the start of {@code text}. Further, {@code 0 <= contextStart
     * <= start <= offset <= end <= contextEnd <= text.length} must hold on entry.
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the start of the range to measure
     * @param end the index + 1 of the end of the range to measure
     * @param contextStart the index of the start of the shaping context
     * @param contextEnd the index + 1 of the end of the shaping context
     * @param isRtl whether the run is in RTL direction
     * @param offset index of caret position
     * @return width measurement between start and offset
     */
    public float getRunAdvance(char[] text, int start, int end, int contextStart, int contextEnd,
            boolean isRtl, int offset) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | offset | end | contextEnd
                | start - contextStart | offset - start | end - offset
                | contextEnd - end | text.length - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end == start) {
            return 0.0f;
        }
        // TODO: take mCompatScaling into account (or eliminate compat scaling)?
        return nGetRunAdvance(mNativePaint, mNativeTypeface, text, start, end,
                contextStart, contextEnd, isRtl, offset);
    }

    /**
     * @see #getRunAdvance(char[], int, int, int, int, boolean, int)
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the start of the range to measure
     * @param end the index + 1 of the end of the range to measure
     * @param contextStart the index of the start of the shaping context
     * @param contextEnd the index + 1 of the end of the shaping context
     * @param isRtl whether the run is in RTL direction
     * @param offset index of caret position
     * @return width measurement between start and offset
     */
    public float getRunAdvance(CharSequence text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, int offset) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | offset | end | contextEnd
                | start - contextStart | offset - start | end - offset
                | contextEnd - end | text.length() - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (end == start) {
            return 0.0f;
        }
        // TODO performance: specialized alternatives to avoid buffer copy, if win is significant
        char[] buf = TemporaryBuffer.obtain(contextEnd - contextStart);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        float result = getRunAdvance(buf, start - contextStart, end - contextStart, 0,
                contextEnd - contextStart, isRtl, offset - contextStart);
        TemporaryBuffer.recycle(buf);
        return result;
    }

    /**
     * Get the character offset within the string whose position is closest to the specified
     * horizontal position.
     *
     * <p>The returned value is generally the value of {@code offset} for which
     * {@link #getRunAdvance} yields a result most closely approximating {@code advance},
     * and which is also on a grapheme cluster boundary. As such, it is the preferred method
     * for positioning a cursor in response to a touch or pointer event. The grapheme cluster
     * boundaries are based on
     * <a href="http://unicode.org/reports/tr29/">Unicode Standard Annex #29</a> but with some
     * tailoring for better user experience.
     *
     * <p>Note that {@code advance} is a (generally positive) width measurement relative to the start
     * of the run. Thus, for RTL runs it the distance from the point to the right edge.
     *
     * <p>All indices are relative to the start of {@code text}. Further, {@code 0 <= contextStart
     * <= start <= end <= contextEnd <= text.length} must hold on entry, and {@code start <= result
     * <= end} will hold on return.
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the start of the range to measure
     * @param end the index + 1 of the end of the range to measure
     * @param contextStart the index of the start of the shaping context
     * @param contextEnd the index + 1 of the end of the range to measure
     * @param isRtl whether the run is in RTL direction
     * @param advance width relative to start of run
     * @return index of offset
     */
    public int getOffsetForAdvance(char[] text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, float advance) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | end | contextEnd
                | start - contextStart | end - start | contextEnd - end
                | text.length - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        // TODO: take mCompatScaling into account (or eliminate compat scaling)?
        return nGetOffsetForAdvance(mNativePaint, mNativeTypeface, text, start, end,
                contextStart, contextEnd, isRtl, advance);
    }

    /**
     * @see #getOffsetForAdvance(char[], int, int, int, int, boolean, float)
     *
     * @param text the text to measure. Cannot be null.
     * @param start the index of the start of the range to measure
     * @param end the index + 1 of the end of the range to measure
     * @param contextStart the index of the start of the shaping context
     * @param contextEnd the index + 1 of the end of the range to measure
     * @param isRtl whether the run is in RTL direction
     * @param advance width relative to start of run
     * @return index of offset
     */
    public int getOffsetForAdvance(CharSequence text, int start, int end, int contextStart,
            int contextEnd, boolean isRtl, float advance) {
        if (text == null) {
            throw new IllegalArgumentException("text cannot be null");
        }
        if ((contextStart | start | end | contextEnd
                | start - contextStart | end - start | contextEnd - end
                | text.length() - contextEnd) < 0) {
            throw new IndexOutOfBoundsException();
        }
        // TODO performance: specialized alternatives to avoid buffer copy, if win is significant
        char[] buf = TemporaryBuffer.obtain(contextEnd - contextStart);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        int result = getOffsetForAdvance(buf, start - contextStart, end - contextStart, 0,
                contextEnd - contextStart, isRtl, advance) + contextStart;
        TemporaryBuffer.recycle(buf);
        return result;
    }

    private static native long nInit();
    private static native long nInitWithPaint(long paint);
    private static native void nReset(long paintPtr);
    private static native void nSet(long paintPtrDest, long paintPtrSrc);
    private static native int nGetStyle(long paintPtr);
    private static native void nSetStyle(long paintPtr, int style);
    private static native int nGetStrokeCap(long paintPtr);
    private static native void nSetStrokeCap(long paintPtr, int cap);
    private static native int nGetStrokeJoin(long paintPtr);
    private static native void nSetStrokeJoin(long paintPtr,
                                                    int join);
    private static native boolean nGetFillPath(long paintPtr,
                                                     long src, long dst);
    private static native long nSetShader(long paintPtr, long shader);
    private static native long nSetColorFilter(long paintPtr,
                                                    long filter);
    private static native long nSetXfermode(long paintPtr,
                                                  long xfermode);
    private static native long nSetPathEffect(long paintPtr,
                                                    long effect);
    private static native long nSetMaskFilter(long paintPtr,
                                                    long maskfilter);
    private static native long nSetTypeface(long paintPtr,
                                                  long typeface);
    private static native long nSetRasterizer(long paintPtr,
                                                   long rasterizer);

    private static native int nGetTextAlign(long paintPtr);
    private static native void nSetTextAlign(long paintPtr,
                                                   int align);

    private static native int nSetTextLocales(long paintPtr, String locales);
    private static native void nSetTextLocalesByMinikinLangListId(long paintPtr,
            int mMinikinLangListId);

    private static native float nGetTextAdvances(long paintPtr, long typefacePtr,
            char[] text, int index, int count, int contextIndex, int contextCount,
            int bidiFlags, float[] advances, int advancesIndex);
    private static native float nGetTextAdvances(long paintPtr, long typefacePtr,
            String text, int start, int end, int contextStart, int contextEnd,
            int bidiFlags, float[] advances, int advancesIndex);

    private native int nGetTextRunCursor(long paintPtr, char[] text,
            int contextStart, int contextLength, int dir, int offset, int cursorOpt);
    private native int nGetTextRunCursor(long paintPtr, String text,
            int contextStart, int contextEnd, int dir, int offset, int cursorOpt);

    private static native void nGetTextPath(long paintPtr, long typefacePtr,
            int bidiFlags, char[] text, int index, int count, float x, float y, long path);
    private static native void nGetTextPath(long paintPtr, long typefacePtr,
            int bidiFlags, String text, int start, int end, float x, float y, long path);
    private static native void nGetStringBounds(long nativePaint, long typefacePtr,
                                String text, int start, int end, int bidiFlags, Rect bounds);
    private static native void nGetCharArrayBounds(long nativePaint, long typefacePtr,
                                char[] text, int index, int count, int bidiFlags, Rect bounds);
    private static native long nGetNativeFinalizer();

    private static native void nSetShadowLayer(long paintPtr,
            float radius, float dx, float dy, int color);
    private static native boolean nHasShadowLayer(long paintPtr);

    private static native float nGetLetterSpacing(long paintPtr);
    private static native void nSetLetterSpacing(long paintPtr,
                                                       float letterSpacing);
    private static native void nSetFontFeatureSettings(long paintPtr,
                                                             String settings);
    private static native int nGetHyphenEdit(long paintPtr);
    private static native void nSetHyphenEdit(long paintPtr, int hyphen);
    private static native boolean nHasGlyph(long paintPtr, long typefacePtr,
            int bidiFlags, String string);
    private static native float nGetRunAdvance(long paintPtr, long typefacePtr,
            char[] text, int start, int end, int contextStart, int contextEnd, boolean isRtl,
            int offset);
    private static native int nGetOffsetForAdvance(long paintPtr,
            long typefacePtr, char[] text, int start, int end, int contextStart, int contextEnd,
            boolean isRtl, float advance);
}
