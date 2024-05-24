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
import android.icu.util.ULocale;
import android.os.Build;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.File;
import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
    private final @NonNull List<NamedFamilyList> mNamedFamilyLists;
    private final @NonNull List<Customization.LocaleFallback> mLocaleFallbackCustomizations;
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
            @NonNull List<NamedFamilyList> namedFamilyLists,
            @NonNull List<Customization.LocaleFallback> localeFallbackCustomizations,
            long lastModifiedTimeMillis, @IntRange(from = 0) int configVersion) {
        mFamilies = families;
        mAliases = aliases;
        mNamedFamilyLists = namedFamilyLists;
        mLocaleFallbackCustomizations = localeFallbackCustomizations;
        mLastModifiedTimeMillis = lastModifiedTimeMillis;
        mConfigVersion = configVersion;
    }

    /**
     * @hide Keep this constructor for reoborectric.
     */
    public FontConfig(@NonNull List<FontFamily> families, @NonNull List<Alias> aliases,
            long lastModifiedTimeMillis, @IntRange(from = 0) int configVersion) {
        this(families, aliases, Collections.emptyList(), Collections.emptyList(),
                lastModifiedTimeMillis, configVersion);
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

    public @NonNull List<NamedFamilyList> getNamedFamilyLists() {
        return mNamedFamilyLists;
    }

    /**
     * Returns a locale fallback customizations.
     *
     * This field is used for creating the system fallback in the system server. This field is
     * always empty in the application process.
     *
     * @hide
     */
    public @NonNull List<Customization.LocaleFallback> getLocaleFallbackCustomizations() {
        return mLocaleFallbackCustomizations;
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
        dest.writeTypedList(mFamilies, flags);
        dest.writeTypedList(mAliases, flags);
        dest.writeTypedList(mNamedFamilyLists, flags);
        dest.writeLong(mLastModifiedTimeMillis);
        dest.writeInt(mConfigVersion);
    }

    public static final @NonNull Creator<FontConfig> CREATOR = new Creator<FontConfig>() {
        @Override
        public FontConfig createFromParcel(Parcel source) {
            final List<FontFamily> families = new ArrayList<>();
            source.readTypedList(families, FontFamily.CREATOR);
            final List<Alias> aliases = new ArrayList<>();
            source.readTypedList(aliases, Alias.CREATOR);
            final List<NamedFamilyList> familyLists = new ArrayList<>();
            source.readTypedList(familyLists, NamedFamilyList.CREATOR);
            long lastModifiedDate = source.readLong();
            int configVersion = source.readInt();
            return new FontConfig(families, aliases, familyLists,
                    Collections.emptyList(),  // Don't need to pass customization to API caller.
                    lastModifiedDate, configVersion);
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
        private final @VarTypeAxes int mVarTypeAxes;

        /** @hide */
        @Retention(SOURCE)
        @IntDef(prefix = { "VAR_TYPE_AXES_" }, value = {
                VAR_TYPE_AXES_NONE,
                VAR_TYPE_AXES_WGHT,
                VAR_TYPE_AXES_ITAL,
        })
        public @interface VarTypeAxes {}

        /** @hide */
        public static final int VAR_TYPE_AXES_NONE = 0;
        /** @hide */
        public static final int VAR_TYPE_AXES_WGHT = 1;
        /** @hide */
        public static final int VAR_TYPE_AXES_ITAL = 2;

        /**
         * Construct a Font instance.
         *
         * @hide Only system server can create this instance and passed via IPC.
         */
        public Font(@NonNull File file, @Nullable File originalFile, @NonNull String postScriptName,
                @NonNull FontStyle style, @IntRange(from = 0) int index,
                @NonNull String fontVariationSettings, @Nullable String fontFamilyName,
                @VarTypeAxes int varTypeAxes) {
            mFile = file;
            mOriginalFile = originalFile;
            mPostScriptName = postScriptName;
            mStyle = style;
            mIndex = index;
            mFontVariationSettings = fontVariationSettings;
            mFontFamilyName = fontFamilyName;
            mVarTypeAxes = varTypeAxes;
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
            dest.writeInt(mVarTypeAxes);
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
                int varTypeAxes = source.readInt();

                return new Font(path, originalPath, postScriptName, new FontStyle(weight, slant),
                        index, varSettings, fallback, varTypeAxes);
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
         * Returns the list of supported axes tags for variable family type resolution.
         *
         * @hide
         */
        public @VarTypeAxes int getVarTypeAxes() {
            return mVarTypeAxes;
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
                    && Objects.equals(mFontFamilyName, font.mFontFamilyName)
                    && mVarTypeAxes == font.mVarTypeAxes;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFile, mOriginalFile, mStyle, mIndex, mFontVariationSettings,
                    mFontFamilyName, mVarTypeAxes);
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
                    + ", mVarTypeAxes='" + mVarTypeAxes + '\''
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
        public FontFamily(@NonNull List<Font> fonts, @NonNull LocaleList localeList,
                @Variant int variant) {
            mFonts = fonts;
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
         *
         * @deprecated From API 34, this function always returns null. All font families which have
         *             name attribute will be reported as a {@link NamedFamilyList}.
         */
        @Deprecated
        public @Nullable String getName() {
            return null;
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
            dest.writeTypedList(mFonts, flags);
            dest.writeString8(mLocaleList.toLanguageTags());
            dest.writeInt(mVariant);
        }

        public static final @NonNull Creator<FontFamily> CREATOR = new Creator<FontFamily>() {

            @Override
            public FontFamily createFromParcel(Parcel source) {
                List<Font> fonts = new ArrayList<>();
                source.readTypedList(fonts, Font.CREATOR);
                String langTags = source.readString8();
                int variant = source.readInt();

                return new FontFamily(fonts, LocaleList.forLanguageTags(langTags), variant);
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
                    && Objects.equals(mLocaleList, that.mLocaleList);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFonts, mLocaleList, mVariant);
        }

        @Override
        public String toString() {
            return "FontFamily{"
                    + "mFonts=" + mFonts
                    + ", mLocaleList=" + mLocaleList
                    + ", mVariant=" + mVariant
                    + '}';
        }
    }

    /**
     * Represents list of font family in the system font configuration.
     *
     * In the fonts_customization.xml, it can define the list of FontFamily as a named family. The
     * list of FontFamily is treated as a fallback list when drawing.
     *
     * @see android.graphics.fonts.FontFamily
     */
    public static final class NamedFamilyList implements Parcelable {
        private final List<FontFamily> mFamilies;
        private final String mName;

        /** @hide */
        public NamedFamilyList(@NonNull List<FontFamily> families, @NonNull String name) {
            mFamilies = families;
            mName = name;
        }

        /** @hide */
        public NamedFamilyList(@NonNull FontFamily family) {
            mFamilies = new ArrayList<>();
            mFamilies.add(family);
            mName = family.getName();
        }

        /**
         * A list of font families.
         *
         * @return a list of font families.
         */
        public @NonNull List<FontFamily> getFamilies() {
            return mFamilies;
        }

        /**
         * Returns the name of the {@link FontFamily}.
         *
         * This name is used to create a new {@code Fallback List}.
         *
         * For example, if the {@link FontFamily} has the name "serif", then the system will create
         * a “serif” {@code Fallback List} and it can be used by creating a Typeface via
         * {@code Typeface.create("serif", Typeface.NORMAL);}
         */
        public @NonNull String getName() {
            return mName;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
            dest.writeTypedList(mFamilies, flags);
            dest.writeString8(mName);
        }

        public static final @NonNull Creator<NamedFamilyList> CREATOR = new Creator<>() {

            @Override
            public NamedFamilyList createFromParcel(Parcel source) {
                final List<FontFamily> families = new ArrayList<>();
                source.readTypedList(families, FontFamily.CREATOR);
                String name = source.readString8();
                return new NamedFamilyList(families, name);
            }

            @Override
            public NamedFamilyList[] newArray(int size) {
                return new NamedFamilyList[size];
            }
        };

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NamedFamilyList that = (NamedFamilyList) o;
            return Objects.equals(mFamilies, that.mFamilies) && Objects.equals(mName,
                    that.mName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mFamilies, mName);
        }

        @Override
        public String toString() {
            return "NamedFamilyList{"
                    + "mFamilies=" + mFamilies
                    + ", mName='" + mName + '\''
                    + '}';
        }
    }

    /** @hide */
    public static class Customization {
        private Customization() {}  // Singleton

        /**
         * A class that represents customization of locale fallback
         *
         * This class represents a vendor customization of new-locale-family.
         *
         * <pre>
         * <family customizationType="new-locale-family" operation="prepend" lang="ja-JP">
         *     <font weight="400" style="normal">MyAlternativeFont.ttf
         *         <axis tag="wght" stylevalue="400"/>
         *     </font>
         * </family>
         * </pre>
         *
         * The operation can be one of prepend, replace or append. The operation prepend means that
         * the new font family is inserted just before the original font family. The original font
         * family is still in the fallback. The operation replace means that the original font
         * family is replaced with new font family. The original font family is removed from the
         * fallback. The operation append means that the new font family is inserted just after the
         * original font family. The original font family is still in the fallback.
         *
         * The lang attribute is a BCP47 compliant language tag. The font fallback mainly uses ISO
         * 15924 script code for matching. If the script code is missing, most likely script code
         * will be used.
         */
        public static class LocaleFallback {
            private final Locale mLocale;
            private final int mOperation;
            private final FontFamily mFamily;
            private final String mScript;

            public static final int OPERATION_PREPEND = 0;
            public static final int OPERATION_APPEND = 1;
            public static final int OPERATION_REPLACE = 2;

            /** @hide */
            @Retention(SOURCE)
            @IntDef(prefix = { "OPERATION_" }, value = {
                    OPERATION_PREPEND,
                    OPERATION_APPEND,
                    OPERATION_REPLACE
            })
            public @interface Operation {}


            public LocaleFallback(@NonNull Locale locale, @Operation int operation,
                    @NonNull FontFamily family) {
                mLocale = locale;
                mOperation = operation;
                mFamily = family;
                mScript = resolveScript(locale);
            }

            /**
             * A customization target locale.
             * @return a locale
             */
            public @NonNull Locale getLocale() {
                return mLocale;
            }

            /**
             * An operation to be applied to the original font family.
             *
             * The operation can be one of {@link #OPERATION_PREPEND}, {@link #OPERATION_REPLACE} or
             * {@link #OPERATION_APPEND}.
             *
             * The operation prepend ({@link #OPERATION_PREPEND}) means that the new font family is
             * inserted just before the original font family. The original font family is still in
             * the fallback.
             *
             * The operation replace ({@link #OPERATION_REPLACE}) means that the original font
             * family is replaced with new font family. The original font family is removed from the
             * fallback.
             *
             * The operation append ({@link #OPERATION_APPEND}) means that the new font family is
             * inserted just after the original font family. The original font family is still in
             * the fallback.
             *
             * @return an operation.
             */
            public @Operation int getOperation() {
                return mOperation;
            }

            /**
             * Returns a family to be inserted or replaced to the fallback.
             *
             * @return a family
             */
            public @NonNull FontFamily getFamily() {
                return mFamily;
            }

            /**
             * Returns a script of the locale. If the script is missing in the given locale, the
             * most likely locale is returned.
             */
            public @NonNull String getScript() {
                return mScript;
            }

            @Override
            public String toString() {
                return "LocaleFallback{"
                        + "mLocale=" + mLocale
                        + ", mOperation=" + mOperation
                        + ", mFamily=" + mFamily
                        + '}';
            }
        }
    }

    /** @hide */
    public static String resolveScript(Locale locale) {
        String script = locale.getScript();
        if (script != null && !script.isEmpty()) {
            return script;
        }
        return ULocale.addLikelySubtags(ULocale.forLocale(locale)).getScript();
    }
}
