/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics.fonts;

import static com.android.text.flags.Flags.FLAG_NEW_FONTS_FALLBACK_XML;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.text.FontConfig;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Set;

/**
 * A font family class can be used for creating Typeface.
 *
 * <p>
 * A font family is a bundle of fonts for drawing text in various styles.
 * For example, you can bundle regular style font and bold style font into a single font family,
 * then system will select the correct style font from family for drawing.
 *
 * <pre>
 *  FontFamily family = new FontFamily.Builder(new Font.Builder("regular.ttf").build())
 *      .addFont(new Font.Builder("bold.ttf").build()).build();
 *  Typeface typeface = new Typeface.Builder2(family).build();
 *
 *  SpannableStringBuilder ssb = new SpannableStringBuilder("Hello, World.");
 *  ssb.setSpan(new StyleSpan(Typeface.Bold), 6, 12, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
 *
 *  textView.setTypeface(typeface);
 *  textView.setText(ssb);
 * </pre>
 *
 * In this example, "Hello, " is drawn with "regular.ttf", and "World." is drawn with "bold.ttf".
 *
 * If there is no font exactly matches with the text style, the system will select the closest font.
 * </p>
 *
 */
public final class FontFamily {

    private static final String TAG = "FontFamily";

    /**
     * A builder class for creating new FontFamily.
     */
    public static final class Builder {
        private static class NoImagePreloadHolder {
            private static final NativeAllocationRegistry sFamilyRegistry =
                    NativeAllocationRegistry.createMalloced(FontFamily.class.getClassLoader(),
                            nGetReleaseNativeFamily());
        }

        private final ArrayList<Font> mFonts = new ArrayList<>();
        // Most FontFamily only has  regular, bold, italic, bold-italic. Thus 4 should be good for
        // initial capacity.
        private final SparseIntArray mStyles = new SparseIntArray(4);


        /**
         * Constructs a builder.
         *
         * @param font a font
         */
        public Builder(@NonNull Font font) {
            Preconditions.checkNotNull(font, "font can not be null");
            mStyles.append(makeStyleIdentifier(font), 0);
            mFonts.add(font);
        }

        /**
         * Adds different style font to the builder.
         *
         * System will select the font if the text style is closest to the font.
         * If the same style font is already added to the builder, this method will fail with
         * {@link IllegalArgumentException}.
         *
         * Note that system assumes all fonts bundled in FontFamily have the same coverage for the
         * code points. For example, regular style font and bold style font must have the same code
         * point coverage, otherwise some character may be shown as tofu.
         *
         * @param font a font
         * @return this builder
         */
        public @NonNull Builder addFont(@NonNull Font font) {
            Preconditions.checkNotNull(font, "font can not be null");
            int key = makeStyleIdentifier(font);
            if (mStyles.indexOfKey(key) >= 0) {
                throw new IllegalArgumentException(font + " has already been added");
            }
            mStyles.append(key, 0);
            mFonts.add(font);
            return this;
        }

        /**
         * Build a variable font family that automatically adjust the `wght` and `ital` axes value
         * for the requested weight/italic style values.
         *
         * To build a variable font family, added fonts must meet one of following conditions.
         *
         * If two font files are added, both font files must support `wght` axis and one font must
         * support {@link FontStyle#FONT_SLANT_UPRIGHT} and another font must support
         * {@link FontStyle#FONT_SLANT_ITALIC}. If the requested weight value is lower than minimum
         * value of the supported `wght` axis, the minimum supported `wght` value is used. If the
         * requested weight value is larger than maximum value of the supported `wght` axis, the
         * maximum supported `wght` value is used. The weight values of the fonts are ignored.
         *
         * If one font file is added, that font must support the `wght` axis. If that font support
         * `ital` axis, that `ital` value is set to 1 when the italic style is requested. If that
         * font doesn't support `ital` axis, synthetic italic may be used. If the requested
         * weight value is lower than minimum value of the supported `wght` axis, the minimum
         * supported `wght` value is used. If the requested weight value is larger than maximum
         * value of the supported `wght`axis, the maximum supported `wght` value is used. The weight
         * value of the font is ignored.
         *
         * If none of the above conditions are met, the provided font files cannot be used for
         * variable font family and this function returns {@code null}. Even if this function
         * returns {@code null}, you can still use {@link #build()} method for creating FontFamily
         * instance with manually specifying variation settings by using
         * {@link Font.Builder#setFontVariationSettings(String)}.
         *
         * @return A variable font family. null if a variable font cannot be built from the given
         *         fonts.
         */
        @SuppressLint("BuilderSetStyle")
        @FlaggedApi(FLAG_NEW_FONTS_FALLBACK_XML)
        public @Nullable FontFamily buildVariableFamily() {
            int variableFamilyType = analyzeAndResolveVariableType(mFonts);
            if (variableFamilyType == VARIABLE_FONT_FAMILY_TYPE_UNKNOWN) {
                return null;
            }
            return build("", FontConfig.FontFamily.VARIANT_DEFAULT,
                    true /* isCustomFallback */,
                    false /* isDefaultFallback */,
                    variableFamilyType);
        }

        /**
         * Build the font family
         * @return a font family
         */
        public @NonNull FontFamily build() {
            return build("", FontConfig.FontFamily.VARIANT_DEFAULT,
                    true /* isCustomFallback */,
                    false /* isDefaultFallback */,
                    VARIABLE_FONT_FAMILY_TYPE_NONE);
        }

        /** @hide */
        public @NonNull FontFamily build(@NonNull String langTags, int variant,
                boolean isCustomFallback, boolean isDefaultFallback, int variableFamilyType) {

            final long builderPtr = nInitBuilder();
            for (int i = 0; i < mFonts.size(); ++i) {
                nAddFont(builderPtr, mFonts.get(i).getNativePtr());
            }
            final long ptr = nBuild(builderPtr, langTags, variant, isCustomFallback,
                    isDefaultFallback, variableFamilyType);
            final FontFamily family = new FontFamily(ptr);
            NoImagePreloadHolder.sFamilyRegistry.registerNativeAllocation(family, ptr);
            return family;
        }

        private static int makeStyleIdentifier(@NonNull Font font) {
            return font.getStyle().getWeight() | (font.getStyle().getSlant()  << 16);
        }

        /**
         * A special variable font family type that indicates `analyzeAndResolveVariableType` could
         * not be identified the variable font family type.
         *
         * @see #buildVariableFamily()
         * @hide
         */
        public static final int VARIABLE_FONT_FAMILY_TYPE_UNKNOWN = -1;

        /**
         * A variable font family type that indicates no variable font family can be used.
         *
         * The font family is used as bundle of static fonts.
         * @see #buildVariableFamily()
         * @hide
         */
        public static final int VARIABLE_FONT_FAMILY_TYPE_NONE = 0;
        /**
         * A variable font family type that indicates single font file can be used for multiple
         * weight. For the italic style, fake italic may be applied.
         *
         * @see #buildVariableFamily()
         * @hide
         */
        public static final int VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ONLY = 1;
        /**
         * A variable font family type that indicates single font file can be used for multiple
         * weight and italic.
         *
         * @see #buildVariableFamily()
         * @hide
         */
        public static final int VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ITAL = 2;
        /**
         * A variable font family type that indicates two font files are included in the family:
         * one can be used for upright with various weights, the other one can be used for italic
         * with various weights.
         *
         * @see #buildVariableFamily()
         * @hide
         */
        public static final int VARIABLE_FONT_FAMILY_TYPE_TWO_FONTS_WGHT = 3;

        /** @hide */
        @Retention(SOURCE)
        @IntDef(prefix = { "VARIABLE_FONT_FAMILY_TYPE_" }, value = {
                VARIABLE_FONT_FAMILY_TYPE_UNKNOWN,
                VARIABLE_FONT_FAMILY_TYPE_NONE,
                VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ONLY,
                VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ITAL,
                VARIABLE_FONT_FAMILY_TYPE_TWO_FONTS_WGHT
        })
        public @interface VariableFontFamilyType {}

        /**
         * The registered italic axis used for adjusting requested style.
         * https://learn.microsoft.com/en-us/typography/opentype/spec/dvaraxistag_ital
         */
        private static final int TAG_ital = 0x6974616C;  // i(0x69), t(0x74), a(0x61), l(0x6c)

        /**
         * The registered weight axis used for adjusting requested style.
         * https://learn.microsoft.com/en-us/typography/opentype/spec/dvaraxistag_wght
         */
        private static final int TAG_wght = 0x77676874;  // w(0x77), g(0x67), h(0x68), t(0x74)

        /** @hide */
        public static @VariableFontFamilyType int analyzeAndResolveVariableType(
                ArrayList<Font> fonts) {
            if (fonts.size() > 2) {
                return VARIABLE_FONT_FAMILY_TYPE_UNKNOWN;
            }

            if (fonts.size() == 1) {
                Font font = fonts.get(0);
                Set<Integer> supportedAxes =
                        FontFileUtil.getSupportedAxes(font.getBuffer(), font.getTtcIndex());
                if (supportedAxes.contains(TAG_wght)) {
                    if (supportedAxes.contains(TAG_ital)) {
                        return VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ITAL;
                    } else {
                        return VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ONLY;
                    }
                } else {
                    return VARIABLE_FONT_FAMILY_TYPE_UNKNOWN;
                }
            } else {
                for (int i = 0; i < fonts.size(); ++i) {
                    Font font = fonts.get(i);
                    Set<Integer> supportedAxes =
                            FontFileUtil.getSupportedAxes(font.getBuffer(), font.getTtcIndex());
                    if (!supportedAxes.contains(TAG_wght)) {
                        return VARIABLE_FONT_FAMILY_TYPE_UNKNOWN;
                    }
                }
                boolean italic1 = fonts.get(0).getStyle().getSlant() == FontStyle.FONT_SLANT_ITALIC;
                boolean italic2 = fonts.get(1).getStyle().getSlant() == FontStyle.FONT_SLANT_ITALIC;

                if (italic1 == italic2) {
                    return VARIABLE_FONT_FAMILY_TYPE_UNKNOWN;
                } else {
                    if (italic1) {
                        // Swap fonts to make the first font upright, second font italic.
                        Font firstFont = fonts.get(0);
                        fonts.set(0, fonts.get(1));
                        fonts.set(1, firstFont);
                    }
                    return VARIABLE_FONT_FAMILY_TYPE_TWO_FONTS_WGHT;
                }
            }
        }

        private static native long nInitBuilder();
        @CriticalNative
        private static native void nAddFont(long builderPtr, long fontPtr);
        private static native long nBuild(long builderPtr, String langTags, int variant,
                boolean isCustomFallback, boolean isDefaultFallback, int variableFamilyType);
        @CriticalNative
        private static native long nGetReleaseNativeFamily();
    }

    private final long mNativePtr;

    // Use Builder instead.
    /** @hide */
    public FontFamily(long ptr) {
        mNativePtr = ptr;
    }

    /**
     * Returns a BCP-47 compliant language tags associated with this font family.
     * @hide
     * @return a BCP-47 compliant language tag.
     */
    public @Nullable String getLangTags() {
        return nGetLangTags(mNativePtr);
    }

    /**
     * @hide
     * @return a family variant
     */
    public int getVariant() {
        return nGetVariant(mNativePtr);
    }

    /**
     * Returns a font
     *
     * @param index an index of the font
     * @return a registered font
     */
    public @NonNull Font getFont(@IntRange(from = 0) int index) {
        if (index < 0 || getSize() <= index) {
            throw new IndexOutOfBoundsException();
        }
        return new Font(nGetFont(mNativePtr, index));
    }

    /**
     * Returns the number of fonts in this FontFamily.
     *
     * @return the number of fonts registered in this family.
     */
    public @IntRange(from = 1) int getSize() {
        return nGetFontSize(mNativePtr);
    }

    /** @hide */
    public long getNativePtr() {
        return mNativePtr;
    }

    @CriticalNative
    private static native int nGetFontSize(long family);

    @CriticalNative
    private static native long nGetFont(long family, int i);

    @FastNative
    private static native String nGetLangTags(long family);

    @CriticalNative
    private static native int nGetVariant(long family);
}
