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
import android.annotation.ColorLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Px;
import android.annotation.Size;
import android.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.os.LocaleList;
import android.text.GraphicsOperations;
import android.text.SpannableString;
import android.text.SpannedString;
import android.text.TextUtils;

import com.android.internal.annotations.GuardedBy;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

/**
 * The Paint class holds the style and color information about how to draw
 * geometries, text and bitmaps.
 */
public class Paint {

    @UnsupportedAppUsage
    private long mNativePaint;
    private long mNativeShader;
    private long mNativeColorFilter;

    // Use a Holder to allow static initialization of Paint in the boot image.
    private static class NoImagePreloadHolder {
        public static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                Paint.class.getClassLoader(), nGetNativeFinalizer());
    }

    @ColorLong private long mColor;
    private ColorFilter     mColorFilter;
    private MaskFilter      mMaskFilter;
    private PathEffect      mPathEffect;
    private Shader          mShader;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Typeface        mTypeface;
    private Xfermode        mXfermode;

    private boolean         mHasCompatScaling;
    private float           mCompatScaling;
    private float           mInvCompatScaling;

    private LocaleList      mLocales;
    private String          mFontFeatureSettings;
    private String          mFontVariationSettings;

    private float           mShadowLayerRadius;
    private float           mShadowLayerDx;
    private float           mShadowLayerDy;
    @ColorLong private long mShadowLayerColor;

    private static final Object sCacheLock = new Object();

    /**
     * Cache for the Minikin language list ID.
     *
     * A map from a string representation of the LocaleList to Minikin's language list ID.
     */
    @GuardedBy("sCacheLock")
    private static final HashMap<String, Integer> sMinikinLocaleListIdCache = new HashMap<>();

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

    /** @hide */
    @IntDef(prefix = { "CURSOR_" }, value = {
        CURSOR_AFTER, CURSOR_AT_OR_AFTER, CURSOR_BEFORE, CURSOR_AT_OR_BEFORE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CursorOption {}

    /**
     * Option for getTextRunCursor.
     *
     * Compute the valid cursor after offset or the limit of the context, whichever is less.
     */
    public static final int CURSOR_AFTER = 0;

    /**
     * Option for getTextRunCursor.
     *
     * Compute the valid cursor at or after the offset or the limit of the context, whichever is
     * less.
     */
    public static final int CURSOR_AT_OR_AFTER = 1;

     /**
     * Option for getTextRunCursor.
     *
     * Compute the valid cursor before offset or the start of the context, whichever is greater.
     */
    public static final int CURSOR_BEFORE = 2;

   /**
     * Option for getTextRunCursor.
     *
     * Compute the valid cursor at or before offset or the start of the context, whichever is
     * greater.
     */
    public static final int CURSOR_AT_OR_BEFORE = 3;

    /**
     * Option for getTextRunCursor.
     *
     * Return offset if the cursor at offset is valid, or -1 if it isn't.
     */
    public static final int CURSOR_AT = 4;

    /**
     * Maximum cursor option value.
     */
    private static final int CURSOR_OPT_MAX_VALUE = CURSOR_AT;

    /** @hide */
    @IntDef(prefix = { "START_HYPHEN_EDIT_" }, value = {
        START_HYPHEN_EDIT_NO_EDIT,
        START_HYPHEN_EDIT_INSERT_HYPHEN,
        START_HYPHEN_EDIT_INSERT_ZWJ
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StartHyphenEdit {}

    /**
     * An integer representing the starting of the line has no modification for hyphenation.
     */
    public static final int START_HYPHEN_EDIT_NO_EDIT = 0x00;

    /**
     * An integer representing the starting of the line has normal hyphen character (U+002D).
     */
    public static final int START_HYPHEN_EDIT_INSERT_HYPHEN = 0x01;

    /**
     * An integer representing the starting of the line has Zero-Width-Joiner (U+200D).
     */
    public static final int START_HYPHEN_EDIT_INSERT_ZWJ = 0x02;

    /** @hide */
    @IntDef(prefix = { "END_HYPHEN_EDIT_" }, value = {
        END_HYPHEN_EDIT_NO_EDIT,
        END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN,
        END_HYPHEN_EDIT_INSERT_HYPHEN,
        END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN,
        END_HYPHEN_EDIT_INSERT_MAQAF,
        END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN,
        END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EndHyphenEdit {}

    /**
     * An integer representing the end of the line has no modification for hyphenation.
     */
    public static final int END_HYPHEN_EDIT_NO_EDIT = 0x00;

    /**
     * An integer representing the character at the end of the line is replaced with hyphen
     * character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_REPLACE_WITH_HYPHEN = 0x01;

    /**
     * An integer representing the end of the line has normal hyphen character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_INSERT_HYPHEN = 0x02;

    /**
     * An integer representing the end of the line has Armentian hyphen (U+058A).
     */
    public static final int END_HYPHEN_EDIT_INSERT_ARMENIAN_HYPHEN = 0x03;

    /**
     * An integer representing the end of the line has maqaf (Hebrew hyphen, U+05BE).
     */
    public static final int END_HYPHEN_EDIT_INSERT_MAQAF = 0x04;

    /**
     * An integer representing the end of the line has Canadian Syllabics hyphen (U+1400).
     */
    public static final int END_HYPHEN_EDIT_INSERT_UCAS_HYPHEN = 0x05;

    /**
     * An integer representing the end of the line has Zero-Width-Joiner (U+200D) followed by normal
     * hyphen character (U+002D).
     */
    public static final int END_HYPHEN_EDIT_INSERT_ZWJ_AND_HYPHEN = 0x06;

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
        mColor = Color.pack(Color.BLACK);
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

        mColor = Color.pack(Color.BLACK);
        mColorFilter = null;
        mMaskFilter = null;
        mPathEffect = null;
        mShader = null;
        mNativeShader = 0;
        mTypeface = null;
        mXfermode = null;

        mHasCompatScaling = false;
        mCompatScaling = 1;
        mInvCompatScaling = 1;

        mBidiFlags = BIDI_DEFAULT_LTR;
        setTextLocales(LocaleList.getAdjustedDefault());
        setElegantTextHeight(false);
        mFontFeatureSettings = null;
        mFontVariationSettings = null;

        mShadowLayerRadius = 0.0f;
        mShadowLayerDx = 0.0f;
        mShadowLayerDy = 0.0f;
        mShadowLayerColor = Color.pack(0);
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
        mColor = paint.mColor;
        mColorFilter = paint.mColorFilter;
        mMaskFilter = paint.mMaskFilter;
        mPathEffect = paint.mPathEffect;
        mShader = paint.mShader;
        mNativeShader = paint.mNativeShader;
        mTypeface = paint.mTypeface;
        mXfermode = paint.mXfermode;

        mHasCompatScaling = paint.mHasCompatScaling;
        mCompatScaling = paint.mCompatScaling;
        mInvCompatScaling = paint.mInvCompatScaling;

        mBidiFlags = paint.mBidiFlags;
        mLocales = paint.mLocales;
        mFontFeatureSettings = paint.mFontFeatureSettings;
        mFontVariationSettings = paint.mFontVariationSettings;

        mShadowLayerRadius = paint.mShadowLayerRadius;
        mShadowLayerDx = paint.mShadowLayerDx;
        mShadowLayerDy = paint.mShadowLayerDy;
        mShadowLayerColor = paint.mShadowLayerColor;
    }

    /** @hide */
    @UnsupportedAppUsage
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
    @UnsupportedAppUsage
    public long getNativeInstance() {
        long newNativeShader = mShader == null ? 0 : mShader.getNativeInstance();
        if (newNativeShader != mNativeShader) {
            mNativeShader = newNativeShader;
            nSetShader(mNativePaint, mNativeShader);
        }
        long newNativeColorFilter = mColorFilter == null ? 0 : mColorFilter.getNativeInstance();
        if (newNativeColorFilter != mNativeColorFilter) {
            mNativeColorFilter = newNativeColorFilter;
            nSetColorFilter(mNativePaint, mNativeColorFilter);
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

    /**
     * Set the paint's flags. Use the Flag enum to specific flag values.
     *
     * @param flags The new flag bits for the paint
     */
    public void setFlags(int flags) {
        nSetFlags(mNativePaint, flags);
    }

    /**
     * Return the paint's hinting mode.  Returns either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public int getHinting() {
        return nGetHinting(mNativePaint);
    }

    /**
     * Set the paint's hinting mode.  May be either
     * {@link #HINTING_OFF} or {@link #HINTING_ON}.
     */
    public void setHinting(int mode) {
        nSetHinting(mNativePaint, mode);
    }

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

    /**
     * Helper for getFlags(), returning true if UNDERLINE_TEXT_FLAG bit is set
     *
     * @return true if the underlineText bit is set in the paint's flags.
     * @see #getUnderlinePosition()
     * @see #getUnderlineThickness()
     * @see #setUnderlineText(boolean)
     */
    public final boolean isUnderlineText() {
        return (getFlags() & UNDERLINE_TEXT_FLAG) != 0;
    }

    /**
     * Returns the distance from top of the underline to the baseline in pixels.
     *
     * The result is positive for positions that are below the baseline.
     * This method returns where the underline should be drawn independent of if the {@link
     * #UNDERLINE_TEXT_FLAG} bit is set.
     *
     * @return the position of the underline in pixels
     * @see #isUnderlineText()
     * @see #getUnderlineThickness()
     * @see #setUnderlineText(boolean)
     */
    public @Px float getUnderlinePosition() {
        return nGetUnderlinePosition(mNativePaint);
    }

    /**
     * Returns the thickness of the underline in pixels.
     *
     * @return the thickness of the underline in pixels
     * @see #isUnderlineText()
     * @see #getUnderlinePosition()
     * @see #setUnderlineText(boolean)
     */
    public @Px float getUnderlineThickness() {
        return nGetUnderlineThickness(mNativePaint);
    }

    /**
     * Helper for setFlags(), setting or clearing the UNDERLINE_TEXT_FLAG bit
     *
     * @param underlineText true to set the underlineText bit in the paint's
     *                      flags, false to clear it.
     * @see #isUnderlineText()
     * @see #getUnderlinePosition()
     * @see #getUnderlineThickness()
     */
    public void setUnderlineText(boolean underlineText) {
        nSetUnderlineText(mNativePaint, underlineText);
    }

    /**
     * Helper for getFlags(), returning true if STRIKE_THRU_TEXT_FLAG bit is set
     *
     * @return true if the {@link #STRIKE_THRU_TEXT_FLAG} bit is set in the paint's flags.
     * @see #getStrikeThruPosition()
     * @see #getStrikeThruThickness()
     * @see #setStrikeThruText(boolean)
     */
    public final boolean isStrikeThruText() {
        return (getFlags() & STRIKE_THRU_TEXT_FLAG) != 0;
    }

    /**
     * Distance from top of the strike-through line to the baseline in pixels.
     *
     * The result is negative for positions that are above the baseline.
     * This method returns where the strike-through line should be drawn independent of if the
     * {@link #STRIKE_THRU_TEXT_FLAG} bit is set.
     *
     * @return the position of the strike-through line in pixels
     * @see #getStrikeThruThickness()
     * @see #setStrikeThruText(boolean)
     * @see #isStrikeThruText()
     */
    public @Px float getStrikeThruPosition() {
        return nGetStrikeThruPosition(mNativePaint);
    }

    /**
     * Returns the thickness of the strike-through line in pixels.
     *
     * @return the position of the strike-through line in pixels
     * @see #getStrikeThruPosition()
     * @see #setStrikeThruText(boolean)
     * @see #isStrikeThruText()
     */
    public @Px float getStrikeThruThickness() {
        return nGetStrikeThruThickness(mNativePaint);
    }

    /**
     * Helper for setFlags(), setting or clearing the STRIKE_THRU_TEXT_FLAG bit
     *
     * @param strikeThruText true to set the strikeThruText bit in the paint's
     *                       flags, false to clear it.
     * @see #getStrikeThruPosition()
     * @see #getStrikeThruThickness()
     * @see #isStrikeThruText()
     */
    public void setStrikeThruText(boolean strikeThruText) {
        nSetStrikeThruText(mNativePaint, strikeThruText);
    }

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
     * Return the paint's color in sRGB. Note that the color is a 32bit value
     * containing alpha as well as r,g,b. This 32bit value is not premultiplied,
     * meaning that its alpha can be any value, regardless of the values of
     * r,g,b. See the Color class for more details.
     *
     * @return the paint's color (and alpha).
     */
    @ColorInt
    public int getColor() {
        return Color.toArgb(mColor);
    }

    /**
     * Return the paint's color. Note that the color is a long with an encoded
     * {@link ColorSpace} as well as alpha and r,g,b. These values are not
     * premultiplied, meaning that alpha can be any value, regardless of the
     * values of r,g,b. See the {@link Color} class for more details.
     *
     * @see Color for APIs that help manipulate a color long.
     *
     * @return the paint's color (and alpha).
     */
    @ColorLong
    public long getColorLong() {
        return mColor;
    }

    /**
     * Set the paint's color. Note that the color is an int containing alpha
     * as well as r,g,b. This 32bit value is not premultiplied, meaning that
     * its alpha can be any value, regardless of the values of r,g,b.
     * See the Color class for more details.
     *
     * @param color The new color (including alpha) to set in the paint.
     */
    public void setColor(@ColorInt int color) {
        setColor(Color.pack(color));
    }

    /**
     * Set the paint's color with a {@link ColorLong}. Note that the color is
     * a long with an encoded {@link ColorSpace} as well as alpha and r,g,b.
     * These values are not premultiplied, meaning that alpha can be any value,
     * regardless of the values of r,g,b. See the {@link Color} class for more
     * details.
     *
     * @param color The new color (including alpha and {@link ColorSpace})
     *      to set in the paint.
     * @throws IllegalArgumentException if the color space encoded in the long
     *      is invalid or unknown.
     */
    public void setColor(@ColorLong long color) {
        ColorSpace cs = Color.colorSpace(color);
        float r = Color.red(color);
        float g = Color.green(color);
        float b = Color.blue(color);
        float a = Color.alpha(color);

        nSetColor(mNativePaint, cs.getNativeInstance(), r, g, b, a);
        mColor = color;
    }

    /**
     * Helper to getColor() that just returns the color's alpha value. This is
     * the same as calling getColor() >>> 24. It always returns a value between
     * 0 (completely transparent) and 255 (completely opaque).
     *
     * @return the alpha component of the paint's color.
     */
    public int getAlpha() {
        return Math.round(Color.alpha(mColor) * 255.0f);
    }

    /**
     * Helper to setColor(), that only assigns the color's alpha value,
     * leaving its r,g,b values unchanged. Results are undefined if the alpha
     * value is outside of the range [0..255]
     *
     * @param a set the alpha component [0..255] of the paint's color.
     */
    public void setAlpha(int a) {
        // FIXME: No need to unpack this. Instead, just update the alpha bits.
        // b/122959599
        ColorSpace cs = Color.colorSpace(mColor);
        float r = Color.red(mColor);
        float g = Color.green(mColor);
        float b = Color.blue(mColor);
        mColor = Color.pack(r, g, b, a * (1.0f / 255), cs);
        nSetAlpha(mNativePaint, a);
    }

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
            // Release any native references to the old shader content
            nSetShader(mNativePaint, 0);
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
        // If mColorFilter changes, cached value of native shader aren't valid, since
        // old shader's pointer may be reused by another shader allocation later
        if (mColorFilter != filter) {
            mNativeColorFilter = -1;
        }

        // Defer setting the filter natively until getNativeInstance() is called
        mColorFilter = filter;
        return filter;
    }

    /**
     * Get the paint's transfer mode object.
     *
     * @return the paint's transfer mode (or null)
     *
     * @deprecated use {@link #getBlendMode()} instead
     */
    @Deprecated
    public Xfermode getXfermode() {
        return mXfermode;
    }

    /**
     * Get the paint's blend mode object.
     *
     * @return the paint's blend mode (or null)
     */
    @Nullable
    public BlendMode getBlendMode() {
        if (mXfermode == null) {
            return null;
        } else {
            return BlendMode.fromValue(mXfermode.porterDuffMode);
        }
    }

    /**
     * Set or clear the transfer mode object. A transfer mode defines how
     * source pixels (generate by a drawing command) are composited with
     * the destination pixels (content of the render target).
     * <p />
     * Pass null to clear any previous transfer mode.
     * As a convenience, the parameter passed is also returned.
     * <p />
     * {@link PorterDuffXfermode} is the most common transfer mode.
     *
     * @param xfermode May be null. The xfermode to be installed in the paint
     * @return         xfermode
     *
     * @deprecated Use {@link #setBlendMode} to apply a Xfermode directly
     * through usage of {@link BlendMode}
     */
    @Deprecated
    public Xfermode setXfermode(Xfermode xfermode) {
        return installXfermode(xfermode);
    }

    @Nullable
    private Xfermode installXfermode(Xfermode xfermode) {
        int newMode = xfermode != null ? xfermode.porterDuffMode : Xfermode.DEFAULT;
        int curMode = mXfermode != null ? mXfermode.porterDuffMode : Xfermode.DEFAULT;
        if (newMode != curMode) {
            nSetXfermode(mNativePaint, newMode);
        }
        mXfermode = xfermode;
        return xfermode;
    }

    /**
     * Set or clear the blend mode. A blend mode defines how source pixels
     * (generated by a drawing command) are composited with the destination pixels
     * (content of the render target).
     * <p />
     * Pass null to clear any previous blend mode.
     * <p />
     *
     * @see BlendMode
     *
     * @param blendmode May be null. The blend mode to be installed in the paint
     */
    public void setBlendMode(@Nullable BlendMode blendmode) {
        installXfermode(blendmode != null ? blendmode.getXfermode() : null);
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
        final long typefaceNative = typeface == null ? 0 : typeface.native_instance;
        nSetTypeface(mNativePaint, typefaceNative);
        mTypeface = typeface;
        return typeface;
    }

    /**
     * Get the paint's rasterizer (or null).
     * <p />
     * The raster controls/modifies how paths/text are turned into alpha masks.
     *
     * @return         the paint's rasterizer (or null)
     *
     * @deprecated Rasterizer is not supported by either the HW or PDF backends.
     * @removed
     */
    @Deprecated
    public Rasterizer getRasterizer() {
        return null;
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
     * @deprecated Rasterizer is not supported by either the HW or PDF backends.
     * @removed
     */
    @Deprecated
    public Rasterizer setRasterizer(Rasterizer rasterizer) {
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
    public void setShadowLayer(float radius, float dx, float dy, @ColorInt int shadowColor) {
        setShadowLayer(radius, dx, dy, Color.pack(shadowColor));
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
     *
     * @throws IllegalArgumentException if the color space encoded in the long
     *      is invalid or unknown.
     */
    public void setShadowLayer(float radius, float dx, float dy, @ColorLong long shadowColor) {
        ColorSpace cs = Color.colorSpace(shadowColor);
        float r = Color.red(shadowColor);
        float g = Color.green(shadowColor);
        float b = Color.blue(shadowColor);
        float a = Color.alpha(shadowColor);
        nSetShadowLayer(mNativePaint, radius, dx, dy, cs.getNativeInstance(), r, g, b, a);

        mShadowLayerRadius = radius;
        mShadowLayerDx = dx;
        mShadowLayerDy = dy;
        mShadowLayerColor = shadowColor;
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
     * Returns the blur radius of the shadow layer.
     * @see #setShadowLayer(float,float,float,int)
     */
    public float getShadowLayerRadius() {
        return mShadowLayerRadius;
    }

    /**
     * Returns the x offset of the shadow layer.
     * @see #setShadowLayer(float,float,float,int)
     */
    public float getShadowLayerDx() {
        return mShadowLayerDx;
    }

    /**
     * Returns the y offset of the shadow layer.
     * @see #setShadowLayer(float,float,float,int)
     */
    public float getShadowLayerDy() {
        return mShadowLayerDy;
    }

    /**
     * Returns the color of the shadow layer.
     * @see #setShadowLayer(float,float,float,int)
     * @see #setShadowLayer(float,float,float,long)
     */
    public @ColorInt int getShadowLayerColor() {
        return Color.toArgb(mShadowLayerColor);
    }

    /**
     * Returns the color of the shadow layer.
     * @see #setShadowLayer(float,float,float,int)
     * @see #setShadowLayer(float,float,float,long)
     */
    public @ColorLong long getShadowLayerColorLong() {
        return mShadowLayerColor;
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
        final Integer minikinLocaleListId;
        synchronized (sCacheLock) {
            minikinLocaleListId = sMinikinLocaleListIdCache.get(languageTags);
            if (minikinLocaleListId == null) {
                final int newID = nSetTextLocales(mNativePaint, languageTags);
                sMinikinLocaleListIdCache.put(languageTags, newID);
                return;
            }
        }
        nSetTextLocalesByMinikinLocaleListId(mNativePaint, minikinLocaleListId.intValue());
    }

    /**
     * Get the elegant metrics flag.
     *
     * @return true if elegant metrics are enabled for text drawing.
     */
    public boolean isElegantTextHeight() {
        return nIsElegantTextHeight(mNativePaint);
    }

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

    /**
     * Return the paint's text size.
     *
     * @return the paint's text size in pixel units.
     */
    public float getTextSize() {
        return nGetTextSize(mNativePaint);
    }

    /**
     * Set the paint's text size. This value must be > 0
     *
     * @param textSize set the paint's text size in pixel units.
     */
    public void setTextSize(float textSize) {
        nSetTextSize(mNativePaint, textSize);
    }

    /**
     * Return the paint's horizontal scale factor for text. The default value
     * is 1.0.
     *
     * @return the paint's scale factor in X for drawing/measuring text
     */
    public float getTextScaleX() {
        return nGetTextScaleX(mNativePaint);
    }

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

    /**
     * Return the paint's horizontal skew factor for text. The default value
     * is 0.
     *
     * @return         the paint's skew factor in X for drawing text.
     */
    public float getTextSkewX() {
        return nGetTextSkewX(mNativePaint);
    }

    /**
     * Set the paint's horizontal skew factor for text. The default value
     * is 0. For approximating oblique text, use values around -0.25.
     *
     * @param skewX set the paint's skew factor in X for drawing text.
     */
    public void setTextSkewX(float skewX) {
        nSetTextSkewX(mNativePaint, skewX);
    }

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
     * Return the paint's extra word-spacing for text.
     *
     * The default value is 0.
     *
     * @return the paint's extra word-spacing for drawing text in pixels.
     * @see #setWordSpacing(float)
     */
    public @Px float getWordSpacing() {
        return nGetWordSpacing(mNativePaint);
    }

    /**
     * Set the paint's extra word-spacing for text.
     *
     * Increases the white space width between words with the given amount of pixels.
     * The default value is 0.
     *
     * @param wordSpacing set the paint's extra word-spacing for drawing text in pixels.
     * @see #getWordSpacing()
     */
    public void setWordSpacing(@Px float wordSpacing) {
        nSetWordSpacing(mNativePaint, wordSpacing);
    }

    /**
     * Returns the font feature settings. The format is the same as the CSS
     * font-feature-settings attribute:
     * <a href="https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop">
     *     https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop</a>
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
     * <a href="https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop">
     *     https://www.w3.org/TR/css-fonts-3/#font-feature-settings-prop</a>
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
     * Returns the font variation settings.
     *
     * @return the paint's currently set font variation settings. Default is null.
     *
     * @see #setFontVariationSettings(String)
     */
    public String getFontVariationSettings() {
        return mFontVariationSettings;
    }

    /**
     * Sets TrueType or OpenType font variation settings. The settings string is constructed from
     * multiple pairs of axis tag and style values. The axis tag must contain four ASCII characters
     * and must be wrapped with single quotes (U+0027) or double quotes (U+0022). Axis strings that
     * are longer or shorter than four characters, or contain characters outside of U+0020..U+007E
     * are invalid. If a specified axis name is not defined in the font, the settings will be
     * ignored.
     *
     * Examples,
     * <ul>
     * <li>Set font width to 150.
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setFontVariationSettings("'wdth' 150");
     * </code>
     * </pre>
     * </li>
     *
     * <li>Set the font slant to 20 degrees and ask for italic style.
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setFontVariationSettings("'slnt' 20, 'ital' 1");
     * </code>
     * </pre>
     * </li>
     * </ul>
     *
     * @param fontVariationSettings font variation settings. You can pass null or empty string as
     *                              no variation settings.
     *
     * @return true if the given settings is effective to at least one font file underlying this
     *         typeface. This function also returns true for empty settings string. Otherwise
     *         returns false
     *
     * @throws IllegalArgumentException If given string is not a valid font variation settings
     *                                  format
     *
     * @see #getFontVariationSettings()
     * @see FontVariationAxis
     */
    public boolean setFontVariationSettings(String fontVariationSettings) {
        final String settings = TextUtils.nullIfEmpty(fontVariationSettings);
        if (settings == mFontVariationSettings
                || (settings != null && settings.equals(mFontVariationSettings))) {
            return true;
        }

        if (settings == null || settings.length() == 0) {
            mFontVariationSettings = null;
            setTypeface(Typeface.createFromTypefaceWithVariation(mTypeface,
                      Collections.emptyList()));
            return true;
        }

        // The null typeface is valid and it is equivalent to Typeface.DEFAULT.
        // To call isSupportedAxes method, use Typeface.DEFAULT instance.
        Typeface targetTypeface = mTypeface == null ? Typeface.DEFAULT : mTypeface;
        FontVariationAxis[] axes = FontVariationAxis.fromFontVariationSettings(settings);
        final ArrayList<FontVariationAxis> filteredAxes = new ArrayList<FontVariationAxis>();
        for (final FontVariationAxis axis : axes) {
            if (targetTypeface.isSupportedAxes(axis.getOpenTypeTagValue())) {
                filteredAxes.add(axis);
            }
        }
        if (filteredAxes.isEmpty()) {
            return false;
        }
        mFontVariationSettings = settings;
        setTypeface(Typeface.createFromTypefaceWithVariation(targetTypeface, filteredAxes));
        return true;
    }

    /**
     * Get the current value of start hyphen edit.
     *
     * The default value is 0 which is equivalent to {@link #START_HYPHEN_EDIT_NO_EDIT}.
     *
     * @return the current starting hyphen edit value
     * @see #setStartHyphenEdit(int)
     */
    public @StartHyphenEdit int getStartHyphenEdit() {
        return nGetStartHyphenEdit(mNativePaint);
    }

    /**
     * Get the current value of end hyphen edit.
     *
     * The default value is 0 which is equivalent to {@link #END_HYPHEN_EDIT_NO_EDIT}.
     *
     * @return the current starting hyphen edit value
     * @see #setStartHyphenEdit(int)
     */
    public @EndHyphenEdit int getEndHyphenEdit() {
        return nGetEndHyphenEdit(mNativePaint);
    }

    /**
     * Set a start hyphen edit on the paint.
     *
     * By setting start hyphen edit, the measurement and drawing is performed with modifying
     * hyphenation at the start of line. For example, by passing
     * {@link #START_HYPHEN_EDIT_INSERT_HYPHEN} like as follows, HYPHEN(U+2010)
     * character is appended at the start of line.
     *
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setStartHyphenEdit(Paint.START_HYPHEN_EDIT_INSERT_HYPHEN);
     *   paint.measureText("abc", 0, 3);  // Returns the width of "‐abc"
     *   Canvas.drawText("abc", 0, 3, 0f, 0f, paint);  // Draws "‐abc"
     * </code>
     * </pre>
     *
     * The default value is 0 which is equivalent to
     * {@link #START_HYPHEN_EDIT_NO_EDIT}.
     *
     * @param startHyphen a start hyphen edit value.
     * @see #getStartHyphenEdit()
     */
    public void setStartHyphenEdit(@StartHyphenEdit int startHyphen) {
        nSetStartHyphenEdit(mNativePaint, startHyphen);
    }

    /**
     * Set a end hyphen edit on the paint.
     *
     * By setting end hyphen edit, the measurement and drawing is performed with modifying
     * hyphenation at the end of line. For example, by passing
     * {@link #END_HYPHEN_EDIT_INSERT_HYPHEN} like as follows, HYPHEN(U+2010)
     * character is appended at the end of line.
     *
     * <pre>
     * <code>
     *   Paint paint = new Paint();
     *   paint.setEndHyphenEdit(Paint.END_HYPHEN_EDIT_INSERT_HYPHEN);
     *   paint.measureText("abc", 0, 3);  // Returns the width of "abc‐"
     *   Canvas.drawText("abc", 0, 3, 0f, 0f, paint);  // Draws "abc‐"
     * </code>
     * </pre>
     *
     * The default value is 0 which is equivalent to {@link #END_HYPHEN_EDIT_NO_EDIT}.
     *
     * @param endHyphen a end hyphen edit value.
     * @see #getEndHyphenEdit()
     */
    public void setEndHyphenEdit(@EndHyphenEdit int endHyphen) {
        nSetEndHyphenEdit(mNativePaint, endHyphen);
    }

    /**
     * Return the distance above (negative) the baseline (ascent) based on the
     * current typeface and text size.
     *
     * <p>Note that this is the ascent of the main typeface, and actual text rendered may need a
     * larger ascent because fallback fonts may get used in rendering the text.
     *
     * @return the distance above (negative) the baseline (ascent) based on the
     *         current typeface and text size.
     */
    public float ascent() {
        return nAscent(mNativePaint);
    }

    /**
     * Return the distance below (positive) the baseline (descent) based on the
     * current typeface and text size.
     *
     * <p>Note that this is the descent of the main typeface, and actual text rendered may need a
     * larger descent because fallback fonts may get used in rendering the text.
     *
     * @return the distance below (positive) the baseline (descent) based on
     *         the current typeface and text size.
     */
    public float descent() {
        return nDescent(mNativePaint);
    }

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
     * <p>Note that these are the values for the main typeface, and actual text rendered may need a
     * larger set of values because fallback fonts may get used in rendering the text.
     *
     * @param metrics If this object is not null, its fields are filled with
     *                the appropriate values given the paint's text attributes.
     * @return the font's recommended interline spacing.
     */
    public float getFontMetrics(FontMetrics metrics) {
        return nGetFontMetrics(mNativePaint, metrics);
    }

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
        /**
         * The maximum distance above the baseline for the tallest glyph in
         * the font at a given text size.
         */
        public int   top;
        /**
         * The recommended distance above the baseline for singled spaced text.
         */
        public int   ascent;
        /**
         * The recommended distance below the baseline for singled spaced text.
         */
        public int   descent;
        /**
         * The maximum distance below the baseline for the lowest glyph in
         * the font at a given text size.
         */
        public int   bottom;
        /**
         * The recommended additional space to add between lines of text.
         */
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
     * <p>Note that these are the values for the main typeface, and actual text rendered may need a
     * larger set of values because fallback fonts may get used in rendering the text.
     *
     * @return the font's interline spacing.
     */
    public int getFontMetricsInt(FontMetricsInt fmi) {
        return nGetFontMetricsInt(mNativePaint, fmi);
    }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fm = new FontMetricsInt();
        getFontMetricsInt(fm);
        return fm;
    }

    /**
     * Return the recommend line spacing based on the current typeface and
     * text size.
     *
     * <p>Note that this is the value for the main typeface, and actual text rendered may need a
     * larger value because fallback fonts may get used in rendering the text.
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
            return (float) Math.ceil(nGetTextAdvances(mNativePaint, text,
                    index, count, index, count, mBidiFlags, null, 0));
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        final float w = nGetTextAdvances(mNativePaint, text, index, count, index, count,
                mBidiFlags, null, 0);
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
            return (float) Math.ceil(nGetTextAdvances(mNativePaint, text,
                    start, end, start, end, mBidiFlags, null, 0));
        }
        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        final float w = nGetTextAdvances(mNativePaint, text, start, end, start, end, mBidiFlags,
                null, 0);
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
            return nBreakText(mNativePaint, text, index, count, maxWidth, mBidiFlags,
                    measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        final int res = nBreakText(mNativePaint, text, index, count, maxWidth * mCompatScaling,
                mBidiFlags, measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

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
            return nBreakText(mNativePaint, text, measureForwards,
                    maxWidth, mBidiFlags, measuredWidth);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize*mCompatScaling);
        final int res = nBreakText(mNativePaint, text, measureForwards, maxWidth*mCompatScaling,
                mBidiFlags, measuredWidth);
        setTextSize(oldSize);
        if (measuredWidth != null) measuredWidth[0] *= mInvCompatScaling;
        return res;
    }

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
            nGetTextAdvances(mNativePaint, text, index, count, index, count, mBidiFlags, widths, 0);
            return count;
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        nGetTextAdvances(mNativePaint, text, index, count, index, count, mBidiFlags, widths, 0);
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
            nGetTextAdvances(mNativePaint, text, start, end, start, end, mBidiFlags, widths, 0);
            return end - start;
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        nGetTextAdvances(mNativePaint, text, start, end, start, end, mBidiFlags, widths, 0);
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
     * Retrieve the character advances of the text.
     *
     * Returns the total advance width for the characters in the run from {@code index} for
     * {@code count} of chars, and if {@code advances} is not null, the advance assigned to each of
     * these characters (java chars).
     *
     * <p>
     * The trailing surrogate in a valid surrogate pair is assigned an advance of 0.  Thus the
     * number of returned advances is always equal to count, not to the number of unicode codepoints
     * represented by the run.
     * </p>
     *
     * <p>
     * In the case of conjuncts or combining marks, the total advance is assigned to the first
     * logical character, and the following characters are assigned an advance of 0.
     * </p>
     *
     * <p>
     * This generates the sum of the advances of glyphs for characters in a reordered cluster as the
     * width of the first logical character in the cluster, and 0 for the widths of all other
     * characters in the cluster.  In effect, such clusters are treated like conjuncts.
     * </p>
     *
     * <p>
     * The shaping bounds limit the amount of context available outside start and end that can be
     * used for shaping analysis.  These bounds typically reflect changes in bidi level or font
     * metrics across which shaping does not occur.
     * </p>
     *
     * @param chars the text to measure.
     * @param index the index of the first character to measure
     * @param count the number of characters to measure
     * @param contextIndex the index of the first character to use for shaping context.
     *                     Context must cover the measureing target.
     * @param contextCount the number of character to use for shaping context.
     *                     Context must cover the measureing target.
     * @param isRtl whether the run is in RTL direction
     * @param advances array to receive the advances, must have room for all advances.
     *                 This can be null if only total advance is needed
     * @param advancesIndex the position in advances at which to put the advance corresponding to
     *                      the character at start
     * @return the total advance in pixels
     */
    public float getTextRunAdvances(@NonNull char[] chars, @IntRange(from = 0) int index,
            @IntRange(from = 0) int count, @IntRange(from = 0) int contextIndex,
            @IntRange(from = 0) int contextCount, boolean isRtl, @Nullable float[] advances,
            @IntRange(from = 0) int advancesIndex) {
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
            return nGetTextAdvances(mNativePaint, chars, index, count, contextIndex, contextCount,
                    isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances,
                    advancesIndex);
        }

        final float oldSize = getTextSize();
        setTextSize(oldSize * mCompatScaling);
        final float res = nGetTextAdvances(mNativePaint, chars, index, count, contextIndex,
                contextCount, isRtl ? BIDI_FORCE_RTL : BIDI_FORCE_LTR, advances, advancesIndex);
        setTextSize(oldSize);

        if (advances != null) {
            for (int i = advancesIndex, e = i + count; i < e; i++) {
                advances[i] *= mInvCompatScaling;
            }
        }
        return res * mInvCompatScaling; // assume errors are not significant
    }

    /**
     * Returns the next cursor position in the run.
     *
     * This avoids placing the cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering cluster.
     *
     * <p>
     * ContextStart and offset are relative to the start of text.
     * The context is the shaping context for cursor movement, generally the bounds of the metric
     * span enclosing the cursor in the direction of movement.
     *
     * <p>
     * If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid cursor position, this
     * returns -1.  Otherwise this will never return a value before contextStart or after
     * contextStart + contextLength.
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextLength the length of the context
     * @param isRtl true if the paragraph context is RTL, otherwise false
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor
     * @return the offset of the next position, or -1
     */
    public int getTextRunCursor(@NonNull char[] text, @IntRange(from = 0) int contextStart,
            @IntRange(from = 0) int contextLength, boolean isRtl, @IntRange(from = 0) int offset,
            @CursorOption int cursorOpt) {
        int contextEnd = contextStart + contextLength;
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return nGetTextRunCursor(mNativePaint, text, contextStart, contextLength,
                isRtl ? DIRECTION_RTL : DIRECTION_LTR, offset, cursorOpt);
    }

    /**
     * Returns the next cursor position in the run.
     *
     * This avoids placing the cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering cluster.
     *
     * <p>
     * ContextStart, contextEnd, and offset are relative to the start of
     * text.  The context is the shaping context for cursor movement, generally
     * the bounds of the metric span enclosing the cursor in the direction of
     * movement.
     *
     * <p>
     * If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid cursor position, this
     * returns -1.  Otherwise this will never return a value before contextStart or after
     * contextEnd.
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextEnd the end of the context
     * @param isRtl true if the paragraph context is RTL, otherwise false
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor
     * @return the offset of the next position, or -1
     */
    public int getTextRunCursor(@NonNull CharSequence text, @IntRange(from = 0) int contextStart,
            @IntRange(from = 0) int contextEnd, boolean isRtl, @IntRange(from = 0) int offset,
            @CursorOption int cursorOpt) {

        if (text instanceof String || text instanceof SpannedString ||
                text instanceof SpannableString) {
            return getTextRunCursor(text.toString(), contextStart, contextEnd,
                    isRtl, offset, cursorOpt);
        }
        if (text instanceof GraphicsOperations) {
            return ((GraphicsOperations) text).getTextRunCursor(
                    contextStart, contextEnd, isRtl, offset, cursorOpt, this);
        }

        int contextLen = contextEnd - contextStart;
        char[] buf = TemporaryBuffer.obtain(contextLen);
        TextUtils.getChars(text, contextStart, contextEnd, buf, 0);
        int relPos = getTextRunCursor(buf, 0, contextLen, isRtl, offset - contextStart, cursorOpt);
        TemporaryBuffer.recycle(buf);
        return (relPos == -1) ? -1 : relPos + contextStart;
    }

    /**
     * Returns the next cursor position in the run.
     *
     * This avoids placing the cursor between surrogates, between characters that form conjuncts,
     * between base characters and combining marks, or within a reordering cluster.
     *
     * <p>
     * ContextStart, contextEnd, and offset are relative to the start of text.  The context is the
     * shaping context for cursor movement, generally the bounds of the metric span enclosing the
     * cursor in the direction of movement.
     * </p>
     *
     * <p>
     * If cursorOpt is {@link #CURSOR_AT} and the offset is not a valid cursor position, this
     * returns -1.  Otherwise this will never return a value before contextStart or after
     * contextEnd.
     * </p>
     *
     * @param text the text
     * @param contextStart the start of the context
     * @param contextEnd the end of the context
     * @param isRtl true if the paragraph context is RTL, otherwise false.
     * @param offset the cursor position to move from
     * @param cursorOpt how to move the cursor
     * @return the offset of the next position, or -1
     * @hide
     */
    public int getTextRunCursor(@NonNull String text, @IntRange(from = 0) int contextStart,
            @IntRange(from = 0) int contextEnd, boolean isRtl, @IntRange(from = 0) int offset,
            @CursorOption int cursorOpt) {
        if (((contextStart | contextEnd | offset | (contextEnd - contextStart)
                | (offset - contextStart) | (contextEnd - offset)
                | (text.length() - contextEnd) | cursorOpt) < 0)
                || cursorOpt > CURSOR_OPT_MAX_VALUE) {
            throw new IndexOutOfBoundsException();
        }

        return nGetTextRunCursor(mNativePaint, text, contextStart, contextEnd,
                isRtl ? DIRECTION_RTL : DIRECTION_LTR, offset, cursorOpt);
    }

    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting in
     * the paint.
     *
     * @param text the text to retrieve the path from
     * @param index the index of the first character in text
     * @param count the number of characters starting with index
     * @param x the x coordinate of the text's origin
     * @param y the y coordinate of the text's origin
     * @param path the path to receive the data describing the text. Must be allocated by the caller
     */
    public void getTextPath(char[] text, int index, int count,
                            float x, float y, Path path) {
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        nGetTextPath(mNativePaint, mBidiFlags, text, index, count, x, y, path.mutateNI());
    }

    /**
     * Return the path (outline) for the specified text.
     * Note: just like Canvas.drawText, this will respect the Align setting
     * in the paint.
     *
     * @param text the text to retrieve the path from
     * @param start the first character in the text
     * @param end 1 past the last character in the text
     * @param x the x coordinate of the text's origin
     * @param y the y coordinate of the text's origin
     * @param path the path to receive the data describing the text. Must be allocated by the caller
     */
    public void getTextPath(String text, int start, int end,
                            float x, float y, Path path) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        nGetTextPath(mNativePaint, mBidiFlags, text, start, end, x, y, path.mutateNI());
    }

    /**
     * Retrieve the text boundary box and store to bounds.
     *
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text string to measure and return its bounds
     * @param start index of the first char in the string to measure
     * @param end 1 past the last char in the string to measure
     * @param bounds returns the unioned bounds of all the text. Must be allocated by the caller
     */
    public void getTextBounds(String text, int start, int end, Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        nGetStringBounds(mNativePaint, text, start, end, mBidiFlags, bounds);
    }

    /**
     * Retrieve the text boundary box and store to bounds.
     *
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * Note that styles are ignored even if you pass {@link android.text.Spanned} instance.
     * Use {@link android.text.StaticLayout} for measuring bounds of {@link android.text.Spanned}.
     *
     * @param text text to measure and return its bounds
     * @param start index of the first char in the text to measure
     * @param end 1 past the last char in the text to measure
     * @param bounds returns the unioned bounds of all the text. Must be allocated by the caller
     */
    public void getTextBounds(@NonNull CharSequence text, int start, int end,
            @NonNull Rect bounds) {
        if ((start | end | (end - start) | (text.length() - end)) < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        char[] buf = TemporaryBuffer.obtain(end - start);
        TextUtils.getChars(text, start, end, buf, 0);
        getTextBounds(buf, 0, end - start, bounds);
        TemporaryBuffer.recycle(buf);
    }

    /**
     * Return in bounds (allocated by the caller) the smallest rectangle that
     * encloses all of the characters, with an implied origin at (0,0).
     *
     * @param text  array of chars to measure and return their unioned bounds
     * @param index index of the first char in the array to measure
     * @param count the number of chars, beginning at index, to measure
     * @param bounds returns the unioned bounds of all the text. Must be allocated by the caller
     */
    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        if ((index | count) < 0 || index + count > text.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        if (bounds == null) {
            throw new NullPointerException("need bounds Rect");
        }
        nGetCharArrayBounds(mNativePaint, text, index, count, mBidiFlags,
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
        return nHasGlyph(mNativePaint, mBidiFlags, string);
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
        return nGetRunAdvance(mNativePaint, text, start, end, contextStart, contextEnd, isRtl,
                offset);
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
        return nGetOffsetForAdvance(mNativePaint, text, start, end, contextStart, contextEnd,
                isRtl, advance);
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

    /**
     * Returns true of the passed {@link Paint} will have the same effect on text measurement
     *
     * @param other A {@link Paint} object.
     * @return true if the other {@link Paint} has the same effect on text measurement.
     */
    public boolean equalsForTextMeasurement(@NonNull Paint other) {
        return nEqualsForTextMeasurement(mNativePaint, other.mNativePaint);
    }

    // regular JNI
    private static native long nGetNativeFinalizer();
    private static native long nInit();
    private static native long nInitWithPaint(long paint);
    private static native int nBreakText(long nObject, char[] text, int index, int count,
            float maxWidth, int bidiFlags, float[] measuredWidth);
    private static native int nBreakText(long nObject, String text, boolean measureForwards,
            float maxWidth, int bidiFlags, float[] measuredWidth);
    private static native float nGetTextAdvances(long paintPtr, char[] text, int index, int count,
            int contextIndex, int contextCount, int bidiFlags, float[] advances, int advancesIndex);
    private static native float nGetTextAdvances(long paintPtr, String text, int start, int end,
            int contextStart, int contextEnd, int bidiFlags, float[] advances, int advancesIndex);
    private native int nGetTextRunCursor(long paintPtr, char[] text, int contextStart,
            int contextLength, int dir, int offset, int cursorOpt);
    private native int nGetTextRunCursor(long paintPtr, String text, int contextStart,
            int contextEnd, int dir, int offset, int cursorOpt);
    private static native void nGetTextPath(long paintPtr, int bidiFlags, char[] text, int index,
            int count, float x, float y, long path);
    private static native void nGetTextPath(long paintPtr, int bidiFlags, String text, int start,
            int end, float x, float y, long path);
    private static native void nGetStringBounds(long nativePaint, String text, int start, int end,
            int bidiFlags, Rect bounds);
    private static native void nGetCharArrayBounds(long nativePaint, char[] text, int index,
            int count, int bidiFlags, Rect bounds);
    private static native boolean nHasGlyph(long paintPtr, int bidiFlags, String string);
    private static native float nGetRunAdvance(long paintPtr, char[] text, int start, int end,
            int contextStart, int contextEnd, boolean isRtl, int offset);
    private static native int nGetOffsetForAdvance(long paintPtr, char[] text, int start, int end,
            int contextStart, int contextEnd, boolean isRtl, float advance);


    // ---------------- @FastNative ------------------------

    @FastNative
    private static native int nSetTextLocales(long paintPtr, String locales);
    @FastNative
    private static native void nSetFontFeatureSettings(long paintPtr, String settings);
    @FastNative
    private static native float nGetFontMetrics(long paintPtr, FontMetrics metrics);
    @FastNative
    private static native int nGetFontMetricsInt(long paintPtr, FontMetricsInt fmi);


    // ---------------- @CriticalNative ------------------------

    @CriticalNative
    private static native void nReset(long paintPtr);
    @CriticalNative
    private static native void nSet(long paintPtrDest, long paintPtrSrc);
    @CriticalNative
    private static native int nGetStyle(long paintPtr);
    @CriticalNative
    private static native void nSetStyle(long paintPtr, int style);
    @CriticalNative
    private static native int nGetStrokeCap(long paintPtr);
    @CriticalNative
    private static native void nSetStrokeCap(long paintPtr, int cap);
    @CriticalNative
    private static native int nGetStrokeJoin(long paintPtr);
    @CriticalNative
    private static native void nSetStrokeJoin(long paintPtr, int join);
    @CriticalNative
    private static native boolean nGetFillPath(long paintPtr, long src, long dst);
    @CriticalNative
    private static native long nSetShader(long paintPtr, long shader);
    @CriticalNative
    private static native long nSetColorFilter(long paintPtr, long filter);
    @CriticalNative
    private static native void nSetXfermode(long paintPtr, int xfermode);
    @CriticalNative
    private static native long nSetPathEffect(long paintPtr, long effect);
    @CriticalNative
    private static native long nSetMaskFilter(long paintPtr, long maskfilter);
    @CriticalNative
    private static native void nSetTypeface(long paintPtr, long typeface);
    @CriticalNative
    private static native int nGetTextAlign(long paintPtr);
    @CriticalNative
    private static native void nSetTextAlign(long paintPtr, int align);
    @CriticalNative
    private static native void nSetTextLocalesByMinikinLocaleListId(long paintPtr,
            int mMinikinLocaleListId);
    @CriticalNative
    private static native void nSetShadowLayer(long paintPtr,
            float radius, float dx, float dy, long colorSpaceHandle,
            float r, float g, float b, float a);
    @CriticalNative
    private static native boolean nHasShadowLayer(long paintPtr);
    @CriticalNative
    private static native float nGetLetterSpacing(long paintPtr);
    @CriticalNative
    private static native void nSetLetterSpacing(long paintPtr, float letterSpacing);
    @CriticalNative
    private static native float nGetWordSpacing(long paintPtr);
    @CriticalNative
    private static native void nSetWordSpacing(long paintPtr, float wordSpacing);
    @CriticalNative
    private static native int nGetStartHyphenEdit(long paintPtr);
    @CriticalNative
    private static native int nGetEndHyphenEdit(long paintPtr);
    @CriticalNative
    private static native void nSetStartHyphenEdit(long paintPtr, int hyphen);
    @CriticalNative
    private static native void nSetEndHyphenEdit(long paintPtr, int hyphen);
    @CriticalNative
    private static native void nSetStrokeMiter(long paintPtr, float miter);
    @CriticalNative
    private static native float nGetStrokeMiter(long paintPtr);
    @CriticalNative
    private static native void nSetStrokeWidth(long paintPtr, float width);
    @CriticalNative
    private static native float nGetStrokeWidth(long paintPtr);
    @CriticalNative
    private static native void nSetAlpha(long paintPtr, int a);
    @CriticalNative
    private static native void nSetDither(long paintPtr, boolean dither);
    @CriticalNative
    private static native int nGetFlags(long paintPtr);
    @CriticalNative
    private static native void nSetFlags(long paintPtr, int flags);
    @CriticalNative
    private static native int nGetHinting(long paintPtr);
    @CriticalNative
    private static native void nSetHinting(long paintPtr, int mode);
    @CriticalNative
    private static native void nSetAntiAlias(long paintPtr, boolean aa);
    @CriticalNative
    private static native void nSetLinearText(long paintPtr, boolean linearText);
    @CriticalNative
    private static native void nSetSubpixelText(long paintPtr, boolean subpixelText);
    @CriticalNative
    private static native void nSetUnderlineText(long paintPtr, boolean underlineText);
    @CriticalNative
    private static native void nSetFakeBoldText(long paintPtr, boolean fakeBoldText);
    @CriticalNative
    private static native void nSetFilterBitmap(long paintPtr, boolean filter);
    @CriticalNative
    private static native void nSetColor(long paintPtr, long colorSpaceHandle,
            float r, float g, float b, float a);
    @CriticalNative
    private static native void nSetStrikeThruText(long paintPtr, boolean strikeThruText);
    @CriticalNative
    private static native boolean nIsElegantTextHeight(long paintPtr);
    @CriticalNative
    private static native void nSetElegantTextHeight(long paintPtr, boolean elegant);
    @CriticalNative
    private static native float nGetTextSize(long paintPtr);
    @CriticalNative
    private static native float nGetTextScaleX(long paintPtr);
    @CriticalNative
    private static native void nSetTextScaleX(long paintPtr, float scaleX);
    @CriticalNative
    private static native float nGetTextSkewX(long paintPtr);
    @CriticalNative
    private static native void nSetTextSkewX(long paintPtr, float skewX);
    @CriticalNative
    private static native float nAscent(long paintPtr);
    @CriticalNative
    private static native float nDescent(long paintPtr);
    @CriticalNative
    private static native float nGetUnderlinePosition(long paintPtr);
    @CriticalNative
    private static native float nGetUnderlineThickness(long paintPtr);
    @CriticalNative
    private static native float nGetStrikeThruPosition(long paintPtr);
    @CriticalNative
    private static native float nGetStrikeThruThickness(long paintPtr);
    @CriticalNative
    private static native void nSetTextSize(long paintPtr, float textSize);
    @CriticalNative
    private static native boolean nEqualsForTextMeasurement(long leftPaintPtr, long rightPaintPtr);
}
