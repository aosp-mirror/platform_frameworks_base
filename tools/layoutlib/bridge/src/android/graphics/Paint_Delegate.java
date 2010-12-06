/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.layoutlib.bridge.impl.DelegateManager;

import android.graphics.Paint.FontMetrics;
import android.graphics.Paint.FontMetricsInt;
import android.text.TextUtils;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Delegate implementing the native methods of android.graphics.Paint
 *
 * Through the layoutlib_create tool, the original native methods of Paint have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Paint class.
 *
 * @see DelegateManager
 *
 */
public class Paint_Delegate {

    /**
     * Class associating a {@link Font} and it's {@link java.awt.FontMetrics}.
     */
    /*package*/ static final class FontInfo {
        Font mFont;
        java.awt.FontMetrics mMetrics;
    }

    // ---- delegate manager ----
    private static final DelegateManager<Paint_Delegate> sManager =
            new DelegateManager<Paint_Delegate>();

    // ---- delegate helper data ----
    private List<FontInfo> mFonts;
    private final FontRenderContext mFontContext = new FontRenderContext(
            new AffineTransform(), true, true);

    // ---- delegate data ----
    private int mFlags;
    private int mColor;
    private int mStyle;
    private int mCap;
    private int mJoin;
    private int mTextAlign;
    private int mTypeface;
    private float mStrokeWidth;
    private float mStrokeMiter;
    private float mTextSize;
    private float mTextScaleX;
    private float mTextSkewX;

    private int mXfermode;
    private int mColorFilter;
    private int mShader;
    private int mPathEffect;
    private int mMaskFilter;


    // ---- Public Helper methods ----

    public static Paint_Delegate getDelegate(int native_paint) {
        return sManager.getDelegate(native_paint);
    }

    /**
     * Returns the list of {@link Font} objects. The first item is the main font, the rest
     * are fall backs for characters not present in the main font.
     */
    public List<FontInfo> getFonts() {
        return mFonts;
    }

    public boolean isAntiAliased() {
        return (mFlags & Paint.ANTI_ALIAS_FLAG) != 0;
    }

    public boolean isFilterBitmap() {
        return (mFlags & Paint.FILTER_BITMAP_FLAG) != 0;
    }

    public int getStyle() {
        return mStyle;
    }

    public int getColor() {
        return mColor;
    }

    public int getTextAlign() {
        return mTextAlign;
    }

    public float getStrokeWidth() {
        return mStrokeWidth;
    }

    public float getStrokeMiter() {
        return mStrokeMiter;
    }

    public int getJavaCap() {
        switch (Paint.sCapArray[mCap]) {
            case BUTT:
                return BasicStroke.CAP_BUTT;
            case ROUND:
                return BasicStroke.CAP_ROUND;
            default:
            case SQUARE:
                return BasicStroke.CAP_SQUARE;
        }
    }

    public int getJavaJoin() {
        switch (Paint.sJoinArray[mJoin]) {
            default:
            case MITER:
                return BasicStroke.JOIN_MITER;
            case ROUND:
                return BasicStroke.JOIN_ROUND;
            case BEVEL:
                return BasicStroke.JOIN_BEVEL;
        }
    }

    public int getXfermode() {
        return mXfermode;
    }

    public int getColorFilter() {
        return mColorFilter;
    }

    public int getShader() {
        return mShader;
    }

    public int getPathEffect() {
        return mPathEffect;
    }

    public int getMaskFilter() {
        return mMaskFilter;
    }

    // ---- native methods ----

    /*package*/ static int getFlags(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mFlags;
    }

    /*package*/ static void setFlags(Paint thisPaint, int flags) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mFlags = flags;
    }

    /*package*/ static void setFilterBitmap(Paint thisPaint, boolean filter) {
        setFlag(thisPaint, Paint.FILTER_BITMAP_FLAG, filter);
    }

    /*package*/ static void setAntiAlias(Paint thisPaint, boolean aa) {
        setFlag(thisPaint, Paint.ANTI_ALIAS_FLAG, aa);
    }

    /*package*/ static void setSubpixelText(Paint thisPaint, boolean subpixelText) {
        setFlag(thisPaint, Paint.SUBPIXEL_TEXT_FLAG, subpixelText);
    }

    /*package*/ static void setUnderlineText(Paint thisPaint, boolean underlineText) {
        setFlag(thisPaint, Paint.UNDERLINE_TEXT_FLAG, underlineText);
    }

    /*package*/ static void setStrikeThruText(Paint thisPaint, boolean strikeThruText) {
        setFlag(thisPaint, Paint.STRIKE_THRU_TEXT_FLAG, strikeThruText);
    }

    /*package*/ static void setFakeBoldText(Paint thisPaint, boolean fakeBoldText) {
        setFlag(thisPaint, Paint.FAKE_BOLD_TEXT_FLAG, fakeBoldText);
    }

    /*package*/ static void setDither(Paint thisPaint, boolean dither) {
        setFlag(thisPaint, Paint.DITHER_FLAG, dither);
    }

    /*package*/ static void setLinearText(Paint thisPaint, boolean linearText) {
        setFlag(thisPaint, Paint.LINEAR_TEXT_FLAG, linearText);
    }

    /*package*/ static int getColor(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mColor;
    }

    /*package*/ static void setColor(Paint thisPaint, int color) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mColor = color;
    }

    /*package*/ static int getAlpha(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mColor >>> 24;
    }

    /*package*/ static void setAlpha(Paint thisPaint, int a) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mColor = (a << 24) | (delegate.mColor & 0x00FFFFFF);
    }

    /*package*/ static float getStrokeWidth(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 1.f;
        }

        return delegate.mStrokeWidth;
    }

    /*package*/ static void setStrokeWidth(Paint thisPaint, float width) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mStrokeWidth = width;
    }

    /*package*/ static float getStrokeMiter(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 1.f;
        }

        return delegate.mStrokeMiter;
    }

    /*package*/ static void setStrokeMiter(Paint thisPaint, float miter) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mStrokeMiter = miter;
    }

    /*package*/ static void nSetShadowLayer(Paint thisPaint, float radius, float dx, float dy,
            int color) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static float getTextSize(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 1.f;
        }

        return delegate.mTextSize;
    }

    /*package*/ static void setTextSize(Paint thisPaint, float textSize) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mTextSize = textSize;
        delegate.updateFontObject();
    }

    /*package*/ static float getTextScaleX(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 1.f;
        }

        return delegate.mTextScaleX;
    }

    /*package*/ static void setTextScaleX(Paint thisPaint, float scaleX) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mTextScaleX = scaleX;
        delegate.updateFontObject();
    }

    /*package*/ static float getTextSkewX(Paint thisPaint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 1.f;
        }

        return delegate.mTextSkewX;
    }

    /*package*/ static void setTextSkewX(Paint thisPaint, float skewX) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mTextSkewX = skewX;
        delegate.updateFontObject();
    }

    /*package*/ static float ascent(Paint thisPaint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static float descent(Paint thisPaint) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static float getFontMetrics(Paint thisPaint, FontMetrics metrics) {
        // get the delegate
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        if (delegate.mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = delegate.mFonts.get(0).mMetrics;
            if (metrics != null) {
                // Android expects negative ascent so we invert the value from Java.
                metrics.top = - javaMetrics.getMaxAscent();
                metrics.ascent = - javaMetrics.getAscent();
                metrics.descent = javaMetrics.getDescent();
                metrics.bottom = javaMetrics.getMaxDescent();
                metrics.leading = javaMetrics.getLeading();
            }

            return javaMetrics.getHeight();
        }

        return 0;
    }

    /*package*/ static int getFontMetricsInt(Paint thisPaint, FontMetricsInt fmi) {
        // get the delegate
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        if (delegate.mFonts.size() > 0) {
            java.awt.FontMetrics javaMetrics = delegate.mFonts.get(0).mMetrics;
            if (fmi != null) {
                // Android expects negative ascent so we invert the value from Java.
                fmi.top = - javaMetrics.getMaxAscent();
                fmi.ascent = - javaMetrics.getAscent();
                fmi.descent = javaMetrics.getDescent();
                fmi.bottom = javaMetrics.getMaxDescent();
                fmi.leading = javaMetrics.getLeading();
            }

            return javaMetrics.getHeight();
        }

        return 0;
    }

    /*package*/ static float native_measureText(Paint thisPaint, char[] text, int index,
            int count) {
        // WARNING: the logic in this method is similar to Canvas.drawText.
        // Any change to this method should be reflected in Canvas.drawText

        // get the delegate
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.measureText(text, index, count);
    }

    /*package*/ static float native_measureText(Paint thisPaint, String text, int start, int end) {
        return native_measureText(thisPaint, text.toCharArray(), start, end - start);
    }

    /*package*/ static float native_measureText(Paint thisPaint, String text) {
        return native_measureText(thisPaint, text.toCharArray(), 0, text.length());
    }

    /*package*/ static int native_breakText(Paint thisPaint, char[] text, int index, int count,
            float maxWidth, float[] measuredWidth) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_breakText(Paint thisPaint, String text, boolean measureForwards,
            float maxWidth, float[] measuredWidth) {
        // FIXME
        throw new UnsupportedOperationException();
    }


    /*package*/ static int native_init() {
        Paint_Delegate newDelegate = new Paint_Delegate();
        return sManager.addDelegate(newDelegate);
    }

    /*package*/ static int native_initWithPaint(int paint) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(paint);
        if (delegate == null) {
            assert false;
            return 0;
        }

        Paint_Delegate newDelegate = new Paint_Delegate(delegate);
        return sManager.addDelegate(newDelegate);
    }

    /*package*/ static void native_reset(int native_object) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.reset();
    }

    /*package*/ static void native_set(int native_dst, int native_src) {
        // get the delegate from the native int.
        Paint_Delegate delegate_dst = sManager.getDelegate(native_dst);
        if (delegate_dst == null) {
            assert false;
            return;
        }

        // get the delegate from the native int.
        Paint_Delegate delegate_src = sManager.getDelegate(native_src);
        if (delegate_src == null) {
            assert false;
            return;
        }

        delegate_dst.set(delegate_src);
    }

    /*package*/ static int native_getStyle(int native_object) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mStyle;
    }

    /*package*/ static void native_setStyle(int native_object, int style) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mStyle = style;
    }

    /*package*/ static int native_getStrokeCap(int native_object) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mCap;
    }

    /*package*/ static void native_setStrokeCap(int native_object, int cap) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mCap = cap;
    }

    /*package*/ static int native_getStrokeJoin(int native_object) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mJoin;
    }

    /*package*/ static void native_setStrokeJoin(int native_object, int join) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mJoin = join;
    }

    /*package*/ static boolean native_getFillPath(int native_object, int src, int dst) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_setShader(int native_object, int shader) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return shader;
        }

        return delegate.mShader = shader;
    }

    /*package*/ static int native_setColorFilter(int native_object, int filter) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return filter;
        }

        return delegate.mColorFilter = filter;
    }

    /*package*/ static int native_setXfermode(int native_object, int xfermode) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return xfermode;
        }

        return delegate.mXfermode = xfermode;
    }

    /*package*/ static int native_setPathEffect(int native_object, int effect) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return effect;
        }

        return delegate.mPathEffect = effect;
    }

    /*package*/ static int native_setMaskFilter(int native_object, int maskfilter) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return maskfilter;
        }

        return delegate.mMaskFilter = maskfilter;
    }

    /*package*/ static int native_setTypeface(int native_object, int typeface) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0;
        }

        delegate.mTypeface = typeface;
        delegate.updateFontObject();
        return delegate.mTypeface;
    }

    /*package*/ static int native_setRasterizer(int native_object, int rasterizer) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_getTextAlign(int native_object) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0;
        }

        return delegate.mTextAlign;
    }

    /*package*/ static void native_setTextAlign(int native_object, int align) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return;
        }

        delegate.mTextAlign = align;
    }

    /*package*/ static float native_getFontMetrics(int native_paint, FontMetrics metrics) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_getTextWidths(int native_object, char[] text, int index,
            int count, float[] widths) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_getTextWidths(int native_object, String text, int start,
            int end, float[] widths) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static float native_getTextRunAdvances(int native_object,
            char[] text, int index, int count, int contextIndex, int contextCount,
            int flags, float[] advances, int advancesIndex) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(native_object);
        if (delegate == null) {
            assert false;
            return 0.f;
        }

        if (delegate.mFonts.size() > 0) {
            // FIXME: handle multi-char characters (see measureText)
            float totalAdvance = 0;
            for (int i = 0; i < count; i++) {
                char c = text[i + index];
                boolean found = false;
                for (FontInfo info : delegate.mFonts) {
                    if (info.mFont.canDisplay(c)) {
                        float adv = info.mMetrics.charWidth(c);
                        totalAdvance += adv;
                        if (advances != null) {
                            advances[i] = adv;
                        }

                        found = true;
                        break;
                    }
                }

                if (found == false) {
                    // no advance for this char.
                    if (advances != null) {
                        advances[i] = 0.f;
                    }
                }
            }

            return totalAdvance;
        }

        return 0;

    }

    /*package*/ static float native_getTextRunAdvances(int native_object,
            String text, int start, int end, int contextStart, int contextEnd,
            int flags, float[] advances, int advancesIndex) {
        // FIXME: support contextStart, contextEnd and direction flag
        int count = end - start;
        char[] buffer = TemporaryBuffer.obtain(count);
        TextUtils.getChars(text, start, end, buffer, 0);

        return native_getTextRunAdvances(native_object, buffer, 0, count, contextStart,
                contextEnd - contextStart, flags, advances, advancesIndex);
    }

    /*package*/ static int native_getTextRunCursor(Paint thisPaint, int native_object, char[] text,
            int contextStart, int contextLength, int flags, int offset, int cursorOpt) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static int native_getTextRunCursor(Paint thisPaint, int native_object, String text,
            int contextStart, int contextEnd, int flags, int offset, int cursorOpt) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_getTextPath(int native_object, int bidiFlags,
                char[] text, int index, int count, float x, float y, int path) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void native_getTextPath(int native_object, int bidiFlags,
            String text, int start, int end, float x, float y, int path) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeGetStringBounds(int nativePaint, String text, int start,
            int end, Rect bounds) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void nativeGetCharArrayBounds(int nativePaint, char[] text, int index,
            int count, Rect bounds) {
        // FIXME
        throw new UnsupportedOperationException();
    }

    /*package*/ static void finalizer(int nativePaint) {
        sManager.removeDelegate(nativePaint);
    }

    // ---- Private delegate/helper methods ----

    private Paint_Delegate() {
        reset();
    }

    private Paint_Delegate(Paint_Delegate paint) {
        set(paint);
    }

    private void set(Paint_Delegate paint) {
        mFlags = paint.mFlags;
        mColor = paint.mColor;
        mStyle = paint.mStyle;
        mCap = paint.mCap;
        mJoin = paint.mJoin;
        mTextAlign = paint.mTextAlign;
        mTypeface = paint.mTypeface;
        mStrokeWidth = paint.mStrokeWidth;
        mStrokeMiter = paint.mStrokeMiter;
        mTextSize = paint.mTextSize;
        mTextScaleX = paint.mTextScaleX;
        mTextSkewX = paint.mTextSkewX;
        mXfermode = paint.mXfermode;
        mColorFilter = paint.mColorFilter;
        mShader = paint.mShader;
        mPathEffect = paint.mPathEffect;
        mMaskFilter = paint.mMaskFilter;
        updateFontObject();
    }

    private void reset() {
        mFlags = Paint.DEFAULT_PAINT_FLAGS;
        mColor = 0;
        mStyle = 0;
        mCap = 0;
        mJoin = 0;
        mTextAlign = 0;
        mTypeface = Typeface.sDefaults[0].native_instance;
        mStrokeWidth = 1.f;
        mStrokeMiter = 2.f;
        mTextSize = 20.f;
        mTextScaleX = 1.f;
        mTextSkewX = 0.f;
        mXfermode = 0;
        mColorFilter = 0;
        mShader = 0;
        mPathEffect = 0;
        mMaskFilter = 0;
        updateFontObject();
    }

    /**
     * Update the {@link Font} object from the typeface, text size and scaling
     */
    private void updateFontObject() {
        if (mTypeface != 0) {
            // Get the fonts from the TypeFace object.
            List<Font> fonts = Typeface_Delegate.getFonts(mTypeface);

            // create new font objects as well as FontMetrics, based on the current text size
            // and skew info.
            ArrayList<FontInfo> infoList = new ArrayList<FontInfo>(fonts.size());
            for (Font font : fonts) {
                FontInfo info = new FontInfo();
                info.mFont = font.deriveFont(mTextSize);
                if (mTextScaleX != 1.0 || mTextSkewX != 0) {
                    // TODO: support skew
                    info.mFont = info.mFont.deriveFont(new AffineTransform(
                            mTextScaleX, mTextSkewX, 0, 0, 1, 0));
                }
                info.mMetrics = Toolkit.getDefaultToolkit().getFontMetrics(info.mFont);

                infoList.add(info);
            }

            mFonts = Collections.unmodifiableList(infoList);
        }
    }

    /*package*/ float measureText(char[] text, int index, int count) {
        if (mFonts.size() > 0) {
            FontInfo mainFont = mFonts.get(0);
            int i = index;
            int lastIndex = index + count;
            float total = 0f;
            while (i < lastIndex) {
                // always start with the main font.
                int upTo = mainFont.mFont.canDisplayUpTo(text, i, lastIndex);
                if (upTo == -1) {
                    // shortcut to exit
                    return total + mainFont.mMetrics.charsWidth(text, i, lastIndex - i);
                } else if (upTo > 0) {
                    total += mainFont.mMetrics.charsWidth(text, i, upTo - i);
                    i = upTo;
                    // don't call continue at this point. Since it is certain the main font
                    // cannot display the font a index upTo (now ==i), we move on to the
                    // fallback fonts directly.
                }

                // no char supported, attempt to read the next char(s) with the
                // fallback font. In this case we only test the first character
                // and then go back to test with the main font.
                // Special test for 2-char characters.
                boolean foundFont = false;
                for (int f = 1 ; f < mFonts.size() ; f++) {
                    FontInfo fontInfo = mFonts.get(f);

                    // need to check that the font can display the character. We test
                    // differently if the char is a high surrogate.
                    int charCount = Character.isHighSurrogate(text[i]) ? 2 : 1;
                    upTo = fontInfo.mFont.canDisplayUpTo(text, i, i + charCount);
                    if (upTo == -1) {
                        total += fontInfo.mMetrics.charsWidth(text, i, charCount);
                        i += charCount;
                        foundFont = true;
                        break;

                    }
                }

                // in case no font can display the char, measure it with the main font.
                if (foundFont == false) {
                    int size = Character.isHighSurrogate(text[i]) ? 2 : 1;
                    total += mainFont.mMetrics.charsWidth(text, i, size);
                    i += size;
                }
            }
        }

        return 0;

    }

    private static void setFlag(Paint thisPaint, int flagMask, boolean flagValue) {
        // get the delegate from the native int.
        Paint_Delegate delegate = sManager.getDelegate(thisPaint.mNativePaint);
        if (delegate == null) {
            assert false;
            return;
        }

        if (flagValue) {
            delegate.mFlags |= flagMask;
        } else {
            delegate.mFlags &= ~flagMask;
        }
    }
}
