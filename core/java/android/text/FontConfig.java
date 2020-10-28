/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.text;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontVariationAxis;
import android.net.Uri;

import java.lang.annotation.Retention;


/**
 * Font configuration descriptions for System fonts.
 * @hide
 */
public final class FontConfig {
    private final @NonNull Family[] mFamilies;
    private final @NonNull Alias[] mAliases;

    public FontConfig(@NonNull Family[] families, @NonNull Alias[] aliases) {
        mFamilies = families;
        mAliases = aliases;
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     */
    @UnsupportedAppUsage
    public @NonNull Family[] getFamilies() {
        return mFamilies;
    }

    /**
     * Returns the list of aliases defined for the font families in the system fonts.
     */
    public @NonNull Alias[] getAliases() {
        return mAliases;
    }

    /**
     * Class that holds information about a Font.
     */
    public static final class Font {
        private final @NonNull String mFontName;
        private final int mTtcIndex;
        private final @NonNull FontVariationAxis[] mAxes;
        private final int mWeight;
        private final boolean mIsItalic;
        private Uri mUri;
        private final String mFallbackFor;

        /**
         * @hide
         */
        public Font(@NonNull String fontName, int ttcIndex, @NonNull FontVariationAxis[] axes,
                int weight, boolean isItalic, String fallbackFor) {
            mFontName = fontName;
            mTtcIndex = ttcIndex;
            mAxes = axes;
            mWeight = weight;
            mIsItalic = isItalic;
            mFallbackFor = fallbackFor;
        }

        /**
         * Returns the name associated by the system to this font.
         */
        public @NonNull String getFontName() {
            return mFontName;
        }

        /**
         * Returns the index to be used to access this font when accessing a TTC file.
         */
        @UnsupportedAppUsage
        public int getTtcIndex() {
            return mTtcIndex;
        }

        /**
         * Returns the list of axes associated to this font.
         */
        @UnsupportedAppUsage
        public @NonNull FontVariationAxis[] getAxes() {
            return mAxes;
        }

        /**
         * Returns the weight value for this font.
         */
        @UnsupportedAppUsage
        public int getWeight() {
            return mWeight;
        }

        /**
         * Returns whether this font is italic.
         */
        @UnsupportedAppUsage
        public boolean isItalic() {
            return mIsItalic;
        }

        /**
         * Returns the content uri associated to this font.
         *
         * You can reach to the font contents by calling {@link
         * android.content.ContentResolver#openInputStream}.
         */
        public @Nullable Uri getUri() {
            return mUri;
        }

        public void setUri(@NonNull Uri uri) {
            mUri = uri;
        }

        public String getFallbackFor() {
            return mFallbackFor;
        }
    }

    /**
     * Class that holds information about a Font alias.
     */
    public static final class Alias {
        private final @NonNull String mName;
        private final @NonNull String mToName;
        private final int mWeight;

        public Alias(@NonNull String name, @NonNull String toName, int weight) {
            mName = name;
            mToName = toName;
            mWeight = weight;
        }

        /**
         * Returns the new name for the alias.
         */
        public @NonNull String getName() {
            return mName;
        }

        /**
         * Returns the existing name to which this alias points to.
         */
        public @NonNull String getToName() {
            return mToName;
        }

        /**
         * Returns the weight associated with this alias.
         */
        public int getWeight() {
            return mWeight;
        }
    }

    /**
     * Class that holds information about a Font family.
     */
    public static final class Family {
        private final @NonNull String mName;
        private final @NonNull Font[] mFonts;
        // Comma separated BCP47 complient locale strings
        private final @NonNull String mLanguages;

        /** @hide */
        @Retention(SOURCE)
        @IntDef(prefix = { "VARIANT_" }, value = {
                VARIANT_DEFAULT,
                VARIANT_COMPACT,
                VARIANT_ELEGANT
        })
        public @interface Variant {}

        /**
         * Value for font variant.
         *
         * Indicates the font has no variant attribute.
         */
        public static final int VARIANT_DEFAULT = 0;

        /**
         * Value for font variant.
         *
         * Indicates the font is for compact variant.
         * @see android.graphics.Paint#setElegantTextHeight
         */
        public static final int VARIANT_COMPACT = 1;

        /**
         * Value for font variant.
         *
         * Indiates the font is for elegant variant.
         * @see android.graphics.Paint#setElegantTextHeight
         */
        public static final int VARIANT_ELEGANT = 2;

        // Must be same with Minikin's variant values.
        // See frameworks/minikin/include/minikin/FontFamily.h
        private final @Variant int mVariant;

        public Family(@NonNull String name, @NonNull Font[] fonts, @NonNull String languages,
                @Variant int variant) {
            mName = name;
            mFonts = fonts;
            mLanguages = languages;
            mVariant = variant;
        }

        /**
         * Returns the name given by the system to this font family.
         */
        @UnsupportedAppUsage
        public @Nullable String getName() {
            return mName;
        }

        /**
         * Returns the list of fonts included in this family.
         */
        @UnsupportedAppUsage
        public @Nullable Font[] getFonts() {
            return mFonts;
        }

        /**
         * Returns the comma separated BCP47 complient languages for this family. May be null.
         */
        public @NonNull String getLanguages() {
            return mLanguages;
        }

        /**
         * Returns the font variant for this family, e.g. "elegant" or "compact". May be null.
         */
        @UnsupportedAppUsage
        public @Variant int getVariant() {
            return mVariant;
        }
    }
}
