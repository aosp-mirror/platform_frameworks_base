/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.graphics.text;

import static com.android.text.flags.Flags.FLAG_NEW_FONTS_FALLBACK_XML;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.fonts.Font;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

import java.util.ArrayList;
import java.util.Objects;

/**
 * Text shaping result object for single style text.
 *
 * You can get text shaping result by
 * {@link TextRunShaper#shapeTextRun(char[], int, int, int, int, float, float, boolean, Paint)} and
 * {@link TextRunShaper#shapeTextRun(CharSequence, int, int, int, int, float, float, boolean,
 * Paint)}.
 *
 * @see TextRunShaper#shapeTextRun(char[], int, int, int, int, float, float, boolean, Paint)
 * @see TextRunShaper#shapeTextRun(CharSequence, int, int, int, int, float, float, boolean, Paint)
 */
public final class PositionedGlyphs {
    private static final NativeAllocationRegistry REGISTRY =
            NativeAllocationRegistry.createMalloced(
                    Typeface.class.getClassLoader(), nReleaseFunc());

    private final long mLayoutPtr;
    private final float mXOffset;
    private final float mYOffset;
    private final ArrayList<Font> mFonts;

    /**
     * Returns the total amount of advance consumed by this positioned glyphs.
     *
     * The advance is an amount of width consumed by the glyph. The total amount of advance is
     * a total amount of advance consumed by this series of glyphs. In other words, if another
     * glyph is placed next to this series of  glyphs, it's X offset should be shifted this amount
     * of width.
     *
     * @return total amount of advance
     */
    public float getAdvance() {
        return nGetTotalAdvance(mLayoutPtr);
    }

    /**
     * Effective ascent value of this positioned glyphs.
     *
     * If two or more font files are used in this series of glyphs, the effective ascent will be
     * the minimum ascent value across the all font files.
     *
     * @return effective ascent value
     */
    public float getAscent() {
        return nGetAscent(mLayoutPtr);
    }

    /**
     * Effective descent value of this positioned glyphs.
     *
     * If two or more font files are used in this series of glyphs, the effective descent will be
     * the maximum descent value across the all font files.
     *
     * @return effective descent value
     */
    public float getDescent() {
        return nGetDescent(mLayoutPtr);
    }

    /**
     * Returns the amount of X offset added to glyph position.
     *
     * @return The X offset added to glyph position.
     */
    public float getOffsetX() {
        return mXOffset;
    }

    /**
     * Returns the amount of Y offset added to glyph position.
     *
     * @return The Y offset added to glyph position.
     */
    public float getOffsetY() {
        return mYOffset;
    }

    /**
     * Returns the number of glyphs stored.
     *
     * @return the number of glyphs
     */
    @IntRange(from = 0)
    public int glyphCount() {
        return nGetGlyphCount(mLayoutPtr);
    }

    /**
     * Returns the font object used for drawing the glyph at the given index.
     *
     * @param index the glyph index
     * @return the font object used for drawing the glyph at the given index
     */
    @NonNull
    public Font getFont(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return mFonts.get(index);
    }

    /**
     * Returns the glyph ID used for drawing the glyph at the given index.
     *
     * @param index the glyph index
     * @return An glyph ID of the font.
     */
    @IntRange(from = 0)
    public int getGlyphId(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return nGetGlyphId(mLayoutPtr, index);
    }

    /**
     * Returns the x coordinate of the glyph position at the given index.
     *
     * @param index the glyph index
     * @return A X offset in pixels
     */
    public float getGlyphX(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return nGetX(mLayoutPtr, index) + mXOffset;
    }

    /**
     * Returns the y coordinate of the glyph position at the given index.
     *
     * @param index the glyph index
     * @return A Y offset in pixels.
     */
    public float getGlyphY(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return nGetY(mLayoutPtr, index) + mYOffset;
    }

    /**
     * Returns true if the fake bold option used for drawing, otherwise false.
     *
     * @param index the glyph index
     * @return true if the fake bold option is on, otherwise off.
     */
    @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
    public boolean getFakeBold(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return nGetFakeBold(mLayoutPtr, index);
    }

    /**
     * Returns true if the fake italic option used for drawing, otherwise false.
     *
     * @param index the glyph index
     * @return true if the fake italic option is on, otherwise off.
     */
    @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
    public boolean getFakeItalic(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        return nGetFakeItalic(mLayoutPtr, index);
    }

    /**
     * A special value returned by {@link #getWeightOverride(int)} and
     * {@link #getItalicOverride(int)} that indicates no font variation setting is overridden.
     */
    @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
    public static final float NO_OVERRIDE = Float.MIN_VALUE;

    /**
     * Returns overridden weight value if the font is variable font and `wght` value is overridden
     * for drawing. Otherwise returns {@link #NO_OVERRIDE}.
     *
     * @param index the glyph index
     * @return overridden weight value or {@link #NO_OVERRIDE}.
     */
    @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
    public float getWeightOverride(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        float value = nGetWeightOverride(mLayoutPtr, index);
        if (value == -1) {
            return NO_OVERRIDE;
        } else {
            return value;
        }
    }

    /**
     * Returns overridden italic value if the font is variable font and `ital` value is overridden
     * for drawing. Otherwise returns {@link #NO_OVERRIDE}.
     *
     * @param index the glyph index
     * @return overridden weight value or {@link #NO_OVERRIDE}.
     */
    @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
    public float getItalicOverride(@IntRange(from = 0) int index) {
        Preconditions.checkArgumentInRange(index, 0, glyphCount() - 1, "index");
        float value = nGetItalicOverride(mLayoutPtr, index);
        if (value == -1) {
            return NO_OVERRIDE;
        } else {
            return value;
        }
    }

    /**
     * Create single style layout from native result.
     *
     * @hide
     *
     * @param layoutPtr the address of native layout object.
     */
    public PositionedGlyphs(long layoutPtr, float xOffset, float yOffset) {
        mLayoutPtr = layoutPtr;
        int glyphCount = nGetGlyphCount(layoutPtr);
        mFonts = new ArrayList<>(glyphCount);
        mXOffset = xOffset;
        mYOffset = yOffset;

        long prevPtr = 0;
        Font prevFont = null;
        for (int i = 0; i < glyphCount; ++i) {
            long ptr = nGetFont(layoutPtr, i);
            if (prevPtr != ptr) {
                prevPtr = ptr;
                prevFont = new Font(ptr);
            }
            mFonts.add(prevFont);
        }

        REGISTRY.registerNativeAllocation(this, layoutPtr);
    }

    @CriticalNative
    private static native int nGetGlyphCount(long minikinLayout);
    @CriticalNative
    private static native float nGetTotalAdvance(long minikinLayout);
    @CriticalNative
    private static native float nGetAscent(long minikinLayout);
    @CriticalNative
    private static native float nGetDescent(long minikinLayout);
    @CriticalNative
    private static native int nGetGlyphId(long minikinLayout, int i);
    @CriticalNative
    private static native float nGetX(long minikinLayout, int i);
    @CriticalNative
    private static native float nGetY(long minikinLayout, int i);
    @CriticalNative
    private static native long nGetFont(long minikinLayout, int i);
    @CriticalNative
    private static native long nReleaseFunc();
    @CriticalNative
    private static native boolean nGetFakeBold(long minikinLayout, int i);
    @CriticalNative
    private static native boolean nGetFakeItalic(long minikinLayout, int i);
    @CriticalNative
    private static native float nGetWeightOverride(long minikinLayout, int i);
    @CriticalNative
    private static native float nGetItalicOverride(long minikinLayout, int i);

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PositionedGlyphs)) return false;
        PositionedGlyphs that = (PositionedGlyphs) o;

        if (mXOffset != that.mXOffset || mYOffset != that.mYOffset) return false;
        if (glyphCount() != that.glyphCount()) return false;

        for (int i = 0; i < glyphCount(); ++i) {
            if (getGlyphId(i) != that.getGlyphId(i)) return false;
            if (getGlyphX(i) != that.getGlyphX(i)) return false;
            if (getGlyphY(i) != that.getGlyphY(i)) return false;
            if (!getFont(i).equals(that.getFont(i))) return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(mXOffset, mYOffset);
        for (int i = 0; i < glyphCount(); ++i) {
            hashCode = Objects.hash(hashCode,
                    getGlyphId(i), getGlyphX(i), getGlyphY(i), getFont(i));
        }
        return hashCode;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < glyphCount(); ++i) {
            if (i != 0) {
                sb.append(", ");
            }
            sb.append("[ ID = " + getGlyphId(i) + ","
                    + " pos = (" + getGlyphX(i) + "," + getGlyphY(i) + ")"
                    + " font = " + getFont(i) + " ]");
        }
        sb.append("]");
        return "PositionedGlyphs{"
                + "glyphs = " + sb.toString()
                + ", mXOffset=" + mXOffset
                + ", mYOffset=" + mYOffset
                + '}';
    }
}
