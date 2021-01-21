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
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.os.LocaleList;

import java.io.File;
import java.lang.annotation.Retention;
import java.util.List;


/**
 * Font configuration descriptions for System fonts.
 * @hide  // TODO Make this SystemApi.
 */
public final class FontConfig {
    private final @NonNull List<Family> mFamilies;
    private final @NonNull List<Alias> mAliases;

    /**
     * Construct a SystemFontConfig instance.
     *
     * @param families a list of font families.
     * @param aliases a list of aliases.
     *
     * @hide Only system server can create this instance and passed via IPC.
     */
    public FontConfig(@NonNull List<Family> families, @NonNull List<Alias> aliases) {
        mFamilies = families;
        mAliases = aliases;
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     *
     * @return a list of font families.
     */
    public @NonNull List<Family> getFontFamilies() {
        return mFamilies;
    }

    /**
     * Returns the list of aliases defined for the font families in the system fonts.
     *
     * @return a list of font families.
     */
    public @NonNull List<Alias> getAliases() {
        return mAliases;
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     * @deprecated Use getFontFamilies instead.
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @NonNull Family[] getFamilies() {
        return mFamilies.toArray(new Family[0]);
    }

    /**
     * A class represents single font entry in system font configuration.
     */
    public static final class Font {
        private final @NonNull File mFilePath;
        private final @Nullable File mOriginalPath;
        private final @NonNull FontStyle mStyle;
        private final @IntRange(from = 0) int mIndex;
        private final @NonNull String mFontVariationSettings;
        private final @Nullable String mFallback;

        /**
         * Construct a Font instance.
         *
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Font(@NonNull File filePath, @Nullable File originalPath, @NonNull FontStyle style,
                @IntRange(from = 0) int index, @NonNull String fontVariationSettings,
                @Nullable String fallback) {
            mFilePath = filePath;
            mOriginalPath = originalPath;
            mStyle = style;
            mIndex = index;
            mFontVariationSettings = fontVariationSettings;
            mFallback = fallback;
        }

        /**
         * Returns a file to the font file.
         *
         * @return a font file.
         */
        public @NonNull File getFilePath() {
            return mFilePath;
        }

        /**
         * Returns an original font file in the system directory.
         *
         * If the font file is not updated, returns null.
         *
         * @return returns the original font file in the system if the font file is updated. Returns
         *         null if the font file is not updated.
         */
        public @Nullable File getOriginalPath() {
            return mOriginalPath;
        }

        /**
         * Returns a font style.
         *
         * @return a font style.
         */
        public @NonNull FontStyle getStyle() {
            return mStyle;
        }

        /**
         * Returns a font index.
         *
         * @return a font index.
         */
        public @IntRange(from = 0) int getIndex() {
            return mIndex;
        }

        /**
         * Return a font variation settings.
         *
         * @return a font variation settings.
         */
        public @NonNull String getFontVariationSettings() {
            return mFontVariationSettings;
        }

        /**
         * Returns font family name that uses this font as a fallback.
         *
         * If this font is a fallback for the default font family, this is null.
         *
         * @return a font family name.
         */
        public @Nullable String getFallback() {
            return mFallback;
        }

        /**
         * Returns the index to be used to access this font when accessing a TTC file.
         * @deprecated Use getIndex instead.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int getTtcIndex() {
            return mIndex;
        }

        /**
         * Returns the list of axes associated to this font.
         * @deprecated Use getFontVariationSettings
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public @NonNull FontVariationAxis[] getAxes() {
            return FontVariationAxis.fromFontVariationSettings(mFontVariationSettings);
        }

        /**
         * Returns the weight value for this font.
         * @deprecated Use getStyle instead.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public int getWeight() {
            return getStyle().getWeight();
        }

        /**
         * Returns whether this font is italic.
         * @deprecated Use getStyle instead.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public boolean isItalic() {
            return getStyle().getSlant() == FontStyle.FONT_SLANT_ITALIC;
        }
    }

    /**
     * A class represents alias between named font families.
     *
     * In the system font configuration, an font family can be an alias of another font family with
     * different font weight. For example, "sans-serif-medium" can be a medium weight of
     * sans-serif font family.
     */
    public static final class Alias {
        private final @NonNull String mAliasName;
        private final @NonNull String mReferName;
        private final @IntRange(from = 0, to = 1000) int mWeight;

        /**
         * Construct an alias instance.
         *
         * @param aliasName an alias of the named font family.
         * @param referName a referring font family name.
         * @param weight a font weight of the referring font family.
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Alias(@NonNull String aliasName, @NonNull String referName,
                @IntRange(from = 0, to = 1000) int weight) {
            mAliasName = aliasName;
            mReferName = referName;
            mWeight = weight;
        }

        /**
         * An alias of the named font family.
         *
         * @return an alias of the named font family.
         */
        public @NonNull String getAliasName() {
            return mAliasName;
        }

        /**
         * A name of font family referring from {@link #getAliasName()}
         *
         * @return a referring font family name.
         */
        public @NonNull String getReferName() {
            return mReferName;
        }

        /**
         * A font weight of the referring font family.
         *
         * @return a font weight of the referring font family.
         */
        public @IntRange(from = 0, to = 1000) int getWeight() {
            return mWeight;
        }
    }

    /**
     * A class represents single font family entry in system font configuration.
     *
     * <p>
     * A font family is a bundle of fonts for drawing text in various styles.
     * For example, regular style font and bold style font can be bundled into a single font family,
     * then system will select the correct style font from family for drawing.
     */
    public static final class Family {
        private final @NonNull List<Font> mFonts;
        private final @Nullable String mName;
        private final @Nullable LocaleList mLocaleList;
        private final @Variant int mVariant;

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
         * Indicates the font is for elegant variant.
         * @see android.graphics.Paint#setElegantTextHeight
         */
        public static final int VARIANT_ELEGANT = 2;

        /**
         * Construct a family instance.
         *
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Family(@NonNull List<Font> fonts, @Nullable String name,
                @Nullable LocaleList localeList, @Variant int variant) {
            mFonts = fonts;
            mName = name;
            mLocaleList = localeList;
            mVariant = variant;
        }

        /**
         * Returns a list of font files in this family.
         *
         * @return a list of font files.
         */
        public @NonNull List<Font> getFontList() {
            return mFonts;
        }

        /**
         * Returns a family name if this family defines a new fallback.
         *
         * @return non-null if a family name is associated. Otherwise null.
         */
        public @Nullable String getFallbackName() {
            return mName;
        }

        /**
         * Returns a locale list if associated.
         *
         * @return non-null if a locale list is associated. Otherwise null.
         */
        public @NonNull LocaleList getLocaleList() {
            return mLocaleList;
        }

        /**
         * Returns a text height variant.
         *
         * @return text height variant.
         */
        public @Variant int getTextHeightVariant() {
            return mVariant;
        }

        /**
         * Returns a family variant associated.
         *
         * @return a family variant.
         * @deprecated Use getTextHeightVariant instead.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public @Variant int getVariant() {
            return mVariant;
        }

        /**
         * Returns a family name if associated.
         *
         * @return non-null if a family name is associated. Otherwise null.
         * @deprecated Use getFallbackName instead.
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public @Nullable String getName() {
            return mName;
        }

        /**
         * Returns the list of fonts included in this family.
         * @deprecated Use getFontFiles instead
         * @hide
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public @Nullable Font[] getFonts() {
            return mFonts.toArray(new Font[0]);
        }

        /**
         * Returns the comma separated BCP47 compliant languages for this family. May be null.
         * @deprecated Use getLocaleList instead
         * @hide
         */
        @Deprecated
        public @NonNull String getLanguages() {
            return mLocaleList.toLanguageTags();
        }
    }
}
