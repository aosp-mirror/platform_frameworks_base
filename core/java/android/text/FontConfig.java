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

import android.annotation.CurrentTimeMillisLong;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


/**
 * Font configuration descriptions for System fonts.
 *
 * FontConfig represents the configuration for the fonts installed on the system. It is made of list
 * of font families and aliases.
 *
 * @see FontFamily
 * @see Alias
 * @hide
 */
@SystemApi
@TestApi
public final class FontConfig implements Parcelable {
    private final @NonNull List<FontFamily> mFamilies;
    private final @NonNull List<Alias> mAliases;
    private final long mLastModifiedTimeMillis;
    private final int mConfigVersion;

    /**
     * Construct a FontConfig instance.
     *
     * @param families a list of font families.
     * @param aliases a list of aliases.
     *
     * @hide Only system server can create this instance and passed via IPC.
     */
    public FontConfig(@NonNull List<FontFamily> families, @NonNull List<Alias> aliases,
            long lastModifiedTimeMillis, @IntRange(from = 0) int configVersion) {
        mFamilies = families;
        mAliases = aliases;
        mLastModifiedTimeMillis = lastModifiedTimeMillis;
        mConfigVersion = configVersion;
    }

    /**
     * Returns the ordered list of font families available in the system.
     *
     * @return a list of font families.
     * @see FontFamily
     */
    public @NonNull List<FontFamily> getFontFamilies() {
        return mFamilies;
    }

    /**
     * Returns the list of aliases for mapping font families with other names.
     *
     * @return a list of font families.
     * @see Alias
     */
    public @NonNull List<Alias> getAliases() {
        return mAliases;
    }

    /**
     * Returns the last modified time in milliseconds.
     *
     * This is a value of {@link System#currentTimeMillis()} when the system font configuration was
     * modified last time.
     *
     * If there is no update, this return 0.
     */
    public @CurrentTimeMillisLong long getLastModifiedTimeMillis() {
        return mLastModifiedTimeMillis;
    }

    /**
     * Returns the monotonically increasing config version value.
     *
     * The config version is reset to 0 when the system is restarted.
     */
    public @IntRange(from = 0) int getConfigVersion() {
        return mConfigVersion;
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     * @deprecated Use getFontFamilies instead.
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public @NonNull FontFamily[] getFamilies() {
        return mFamilies.toArray(new FontFamily[0]);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelableList(mFamilies, flags);
        dest.writeParcelableList(mAliases, flags);
        dest.writeLong(mLastModifiedTimeMillis);
        dest.writeInt(mConfigVersion);
    }

    public static final @NonNull Creator<FontConfig> CREATOR = new Creator<FontConfig>() {
        @Override
        public FontConfig createFromParcel(Parcel source) {
            List<FontFamily> families = source.readParcelableList(new ArrayList<>(),
                    FontFamily.class.getClassLoader(), android.text.FontConfig.FontFamily.class);
            List<Alias> aliases = source.readParcelableList(new ArrayList<>(),
                    Alias.class.getClassLoader(), android.text.FontConfig.Alias.class);
            long lastModifiedDate = source.readLong();
            int configVersion = source.readInt();
            return new FontConfig(families, aliases, lastModifiedDate, configVersion);
        }

        @Override
        public FontConfig[] newArray(int size) {
            return new FontConfig[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FontConfig that = (FontConfig) o;
        return mLastModifiedTimeMillis == that.mLastModifiedTimeMillis
                && mConfigVersion == that.mConfigVersion
                && Objects.equals(mFamilies, that.mFamilies)
                && Objects.equals(mAliases, that.mAliases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mFamilies, mAliases, mLastModifiedTimeMillis, mConfigVersion);
    }

    @Override
    public String toString() {
        return "FontConfig{"
                + "mFamilies=" + mFamilies
                + ", mAliases=" + mAliases
                + ", mLastModifiedTimeMillis=" + mLastModifiedTimeMillis
                + ", mConfigVersion=" + mConfigVersion
                + '}';
    }

    /**
     * Represents single font entry in system font configuration.
     *
     * A font is the most primitive unit of drawing character shapes. A font in system configuration
     * is always referring a single OpenType compliant regular file in the file system.
     *
     * @see android.graphics.fonts.Font
     */
    public static final class Font implements Parcelable {
        private final @NonNull File mFile;
        private final @Nullable File mOriginalFile;
        private final @NonNull String mPostScriptName;
        private final @NonNull FontStyle mStyle;
        private final @IntRange(from = 0) int mIndex;
        private final @NonNull String mFontVariationSettings;
        private final @Nullable String mFontFamilyName;

        /**
         * Construct a Font instance.
         *
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Font(@NonNull File file, @Nullable File originalFile, @NonNull String postScriptName,
                @NonNull FontStyle style, @IntRange(from = 0) int index,
                @NonNull String fontVariationSettings, @Nullable String fontFamilyName) {
            mFile = file;
            mOriginalFile = originalFile;
            mPostScriptName = postScriptName;
            mStyle = style;
            mIndex = index;
            mFontVariationSettings = fontVariationSettings;
            mFontFamilyName = fontFamilyName;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mFile.getAbsolutePath());
            dest.writeString8(mOriginalFile == null ? null : mOriginalFile.getAbsolutePath());
            dest.writeString8(mPostScriptName);
            dest.writeInt(mStyle.getWeight());
            dest.writeInt(mStyle.getSlant());
            dest.writeInt(mIndex);
            dest.writeString8(mFontVariationSettings);
            dest.writeString8(mFontFamilyName);
        }

        public static final @NonNull Creator<Font> CREATOR = new Creator<Font>() {

            @Override
            public Font createFromParcel(Parcel source) {
                File path = new File(source.readString8());
                String originalPathStr = source.readString8();
                File originalPath = originalPathStr == null ? null : new File(originalPathStr);
                String postScriptName = source.readString8();
                int weight = source.readInt();
                int slant = source.readInt();
                int index = source.readInt();
                String varSettings = source.readString8();
                String fallback = source.readString8();

                return new Font(path, originalPath, postScriptName, new FontStyle(weight, slant),
                        index, varSettings, fallback);
            }

            @Override
            public Font[] newArray(int size) {
                return new Font[size];
            }
        };

        /**
         * Returns the font file.
         */
        public @NonNull File getFile() {
            return mFile;
        }

        /**
         * Returns the original font file in the system directory.
         *
         * If the font file is not updated, returns null.
         *
         * @return returns the original font file in the system if the font file is updated. Returns
         *         null if the font file is not updated.
         * @hide
         */
        public @Nullable File getOriginalFile() {
            return mOriginalFile;
        }

        /**
         * Returns the font style.
         */
        public @NonNull FontStyle getStyle() {
            return mStyle;
        }


        /**
         * Return a font variation settings.
         */
        public @NonNull String getFontVariationSettings() {
            return mFontVariationSettings;
        }

        /**
         * A {@link Font} can be configured to be in the {@code Fallback List} for a
         * {@link FontFamily}.
         *
         * For example a serif Hebrew [Font] can be defined in the {@code Fallback List} for
         * {@code "serif"} {@link FontFamily}.
         *
         * If the return value is not {@code null}, then the font will be used in the
         * {@code Fallback List} of that {@link FontFamily}.
         *
         * If the return value is {@code null}, then the font will be used in {@code Fallback List}
         * of all {@link FontFamily}s.
         */
        public @Nullable String getFontFamilyName() {
            return mFontFamilyName;
        }

        /**
         * Returns the index to be used to access this font when accessing a TTC file.
         */
        public int getTtcIndex() {
            return mIndex;
        }

        /**
         * Returns the PostScript name of this font.
         */
        public @NonNull String getPostScriptName() {
            return mPostScriptName;
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Font font = (Font) o;
            return mIndex == font.mIndex
                    && Objects.equals(mFile, font.mFile)
                    && Objects.equals(mOriginalFile, font.mOriginalFile)
                    && Objects.equals(mStyle, font.mStyle)
                    && Objects.equals(mFontVariationSettings, font.mFontVariationSettings)
                    && Objects.equals(mFontFamilyName, font.mFontFamilyName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFile, mOriginalFile, mStyle, mIndex, mFontVariationSettings,
                    mFontFamilyName);
        }

        @Override
        public String toString() {
            return "Font{"
                    + "mFile=" + mFile
                    + ", mOriginalFile=" + mOriginalFile
                    + ", mStyle=" + mStyle
                    + ", mIndex=" + mIndex
                    + ", mFontVariationSettings='" + mFontVariationSettings + '\''
                    + ", mFontFamilyName='" + mFontFamilyName + '\''
                    + '}';
        }
    }

    /**
     * Alias provides an alternative name for an existing font family.
     *
     * In the system font configuration, a font family can be an alias of another font family with
     * different font weight. For example, "sans-serif-medium" can be a medium weight of
     * "sans-serif" font family. In this example, {@link #getName()} returns "sans-serif-medium" and
     * {@link #getOriginal()} return "sans-serif". The font family that doesn't have name can not be
     * an original of the alias.
     */
    public static final class Alias implements Parcelable {
        private final @NonNull String mName;
        private final @NonNull String mOriginal;
        private final @IntRange(from = 0, to = 1000) int mWeight;

        /**
         * Construct an alias instance.
         *
         * @param name alias for the font family.
         * @param original original font family name.
         * @param weight font weight of the original font family.
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Alias(@NonNull String name, @NonNull String original,
                @IntRange(from = 0, to = 1000) int weight) {
            mName = name;
            mOriginal = original;
            mWeight = weight;
        }

        /**
         * Alias for the font family
         */
        public @NonNull String getName() {
            return mName;
        }

        /**
         * The name of the original font family.
         */
        public @NonNull String getOriginal() {
            return mOriginal;
        }

        /**
         * A font weight of the referring font family.
         *
         * @return a font weight of the referring font family.
         */
        public @IntRange(from = 0, to = 1000) int getWeight() {
            return mWeight;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString8(mName);
            dest.writeString8(mOriginal);
            dest.writeInt(mWeight);
        }

        public static final @NonNull Creator<Alias> CREATOR = new Creator<Alias>() {

            @Override
            public Alias createFromParcel(Parcel source) {
                String alias = source.readString8();
                String referName = source.readString8();
                int weight = source.readInt();
                return new Alias(alias, referName, weight);
            }

            @Override
            public Alias[] newArray(int size) {
                return new Alias[size];
            }
        };

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Alias alias = (Alias) o;
            return mWeight == alias.mWeight
                    && Objects.equals(mName, alias.mName)
                    && Objects.equals(mOriginal, alias.mOriginal);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mOriginal, mWeight);
        }

        @Override
        public String toString() {
            return "Alias{"
                    + "mName='" + mName + '\''
                    + ", mOriginal='" + mOriginal + '\''
                    + ", mWeight=" + mWeight
                    + '}';
        }
    }

    /**
     * Represents a font family in the system font configuration.
     *
     * A {@link FontFamily} is a list of {@link Font}s for drawing text in various styles such as
     * weight, slant.
     *
     * For example, a {@link FontFamily} can include the regular and bold styles of a {@link Font}.
     *
     * @see android.graphics.fonts.FontFamily
     */
    public static final class FontFamily implements Parcelable {
        private final @NonNull List<Font> mFonts;
        private final @Nullable String mName;
        private final @NonNull LocaleList mLocaleList;
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
        public FontFamily(@NonNull List<Font> fonts, @Nullable String name,
                @NonNull LocaleList localeList, @Variant int variant) {
            mFonts = fonts;
            mName = name;
            mLocaleList = localeList;
            mVariant = variant;
        }

        /**
         * Returns the list of {@link Font}s in this {@link FontFamily}.
         *
         * @return a list of font files.
         */
        public @NonNull List<Font> getFontList() {
            return mFonts;
        }

        /**
         * Returns the name of the {@link FontFamily}.
         *
         * When the name of a {@link FontFamily} is not null, this name is used to create a new
         * {@code Fallback List}, and that {@code Fallback List}. Fallback List is the
         * main building block for a {@link android.graphics.Typeface}.
         *
         * For example, if the {@link FontFamily} has the name "serif", then the system will create
         * a “serif” {@code Fallback List} and it can be used by creating a Typeface via
         * {@code Typeface.create("serif", Typeface.NORMAL);}
         *
         * When the name of a {@link FontFamily} is null, it will be appended to all of the
         * {@code Fallback List}s.
         */
        public @Nullable String getName() {
            return mName;
        }

        /**
         * Returns the locale list if available.
         *
         * The locale list will be used for deciding which font family should be used in fallback
         * list.
         */
        public @NonNull LocaleList getLocaleList() {
            return mLocaleList;
        }

        /**
         * Returns the text height variant.
         */
        public @Variant int getVariant() {
            return mVariant;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeParcelableList(mFonts, flags);
            dest.writeString8(mName);
            dest.writeString8(mLocaleList.toLanguageTags());
            dest.writeInt(mVariant);
        }

        public static final @NonNull Creator<FontFamily> CREATOR = new Creator<FontFamily>() {

            @Override
            public FontFamily createFromParcel(Parcel source) {
                List<Font> fonts = source.readParcelableList(
                        new ArrayList<>(), Font.class.getClassLoader(), android.text.FontConfig.Font.class);
                String name = source.readString8();
                String langTags = source.readString8();
                int variant = source.readInt();

                return new FontFamily(fonts, name, LocaleList.forLanguageTags(langTags), variant);
            }

            @Override
            public FontFamily[] newArray(int size) {
                return new FontFamily[size];
            }
        };

        /**
         * Returns the list of fonts included in this family.
         * @deprecated Use getFontList instead
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FontFamily that = (FontFamily) o;
            return mVariant == that.mVariant
                    && Objects.equals(mFonts, that.mFonts)
                    && Objects.equals(mName, that.mName)
                    && Objects.equals(mLocaleList, that.mLocaleList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFonts, mName, mLocaleList, mVariant);
        }

        @Override
        public String toString() {
            return "FontFamily{"
                    + "mFonts=" + mFonts
                    + ", mName='" + mName + '\''
                    + ", mLocaleList=" + mLocaleList
                    + ", mVariant=" + mVariant
                    + '}';
        }
    }
}
