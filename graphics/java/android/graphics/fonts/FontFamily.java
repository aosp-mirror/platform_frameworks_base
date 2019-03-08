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

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.text.FontConfig;

import com.android.internal.util.Preconditions;

import dalvik.annotation.optimization.CriticalNative;

import libcore.util.NativeAllocationRegistry;

import java.util.ArrayList;
import java.util.HashSet;

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
        private static final NativeAllocationRegistry sFamilyRegistory =
                new NativeAllocationRegistry(FontFamily.class.getClassLoader(),
                    nGetReleaseNativeFamily(), 64);

        private final ArrayList<Font> mFonts = new ArrayList<>();
        private final HashSet<Integer> mStyleHashSet = new HashSet<>();

        /**
         * Constructs a builder.
         *
         * @param font a font
         */
        public Builder(@NonNull Font font) {
            Preconditions.checkNotNull(font, "font can not be null");
            mStyleHashSet.add(makeStyleIdentifier(font));
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
            if (!mStyleHashSet.add(makeStyleIdentifier(font))) {
                throw new IllegalArgumentException(font + " has already been added");
            }
            mFonts.add(font);
            return this;
        }

        /**
         * Build the font family
         * @return a font family
         */
        public @NonNull FontFamily build() {
            return build("", FontConfig.Family.VARIANT_DEFAULT, true /* isCustomFallback */);
        }

        /** @hide */
        public @NonNull FontFamily build(@NonNull String langTags, int variant,
                boolean isCustomFallback) {
            final long builderPtr = nInitBuilder();
            for (int i = 0; i < mFonts.size(); ++i) {
                nAddFont(builderPtr, mFonts.get(i).getNativePtr());
            }
            final long ptr = nBuild(builderPtr, langTags, variant, isCustomFallback);
            final FontFamily family = new FontFamily(mFonts, ptr);
            sFamilyRegistory.registerNativeAllocation(family, ptr);
            return family;
        }

        private static int makeStyleIdentifier(@NonNull Font font) {
            return font.getStyle().getWeight() | (font.getStyle().getSlant()  << 16);
        }

        private static native long nInitBuilder();
        @CriticalNative
        private static native void nAddFont(long builderPtr, long fontPtr);
        private static native long nBuild(long builderPtr, String langTags, int variant,
                boolean isCustomFallback);
        @CriticalNative
        private static native long nGetReleaseNativeFamily();
    }

    private final ArrayList<Font> mFonts;
    private final long mNativePtr;

    // Use Builder instead.
    private FontFamily(@NonNull ArrayList<Font> fonts, long ptr) {
        mFonts = fonts;
        mNativePtr = ptr;
    }

    /**
     * Returns a font
     *
     * @param index an index of the font
     * @return a registered font
     */
    public @NonNull Font getFont(@IntRange(from = 0) int index) {
        return mFonts.get(index);
    }

    /**
     * Returns the number of fonts in this FontFamily.
     *
     * @return the number of fonts registered in this family.
     */
    public @IntRange(from = 1) int getSize() {
        return mFonts.size();
    }

    /** @hide */
    public long getNativePtr() {
        return mNativePtr;
    }
}
