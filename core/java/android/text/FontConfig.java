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
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.util.Arrays;


/**
 * Font configuration descriptions for System fonts.
 */
public final class FontConfig implements Parcelable {
    private final @NonNull Family[] mFamilies;
    private final @NonNull Alias[] mAliases;

    public FontConfig(@NonNull Family[] families, @NonNull Alias[] aliases) {
        mFamilies = families;
        mAliases = aliases;
    }

    /**
     * For duplicating file descriptors.
     *
     * Note that this copy constructor can not be usable for deep copy.
     * @hide
     */
    public FontConfig(@NonNull FontConfig config) {
        mFamilies = new Family[config.mFamilies.length];
        for (int i = 0; i < config.mFamilies.length; ++i) {
            mFamilies[i] = new Family(config.mFamilies[i]);
        }
        mAliases = Arrays.copyOf(config.mAliases, config.mAliases.length);
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     */
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
     * @hide
     */
    public FontConfig(Parcel in) {
        mFamilies = in.readTypedArray(Family.CREATOR);
        mAliases = in.readTypedArray(Alias.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel out, int flag) {
        out.writeTypedArray(mFamilies, flag);
        out.writeTypedArray(mAliases, flag);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<FontConfig> CREATOR = new Parcelable.Creator() {
        public FontConfig createFromParcel(Parcel in) {
            return new FontConfig(in);
        }
        public FontConfig[] newArray(int size) {
            return new FontConfig[size];
        }
    };

    /**
     * Class that holds information about a Font axis.
     */
    public static final class Axis implements Parcelable {
        private final int mTag;
        private final float mStyleValue;

        public Axis(int tag, float styleValue) {
            this.mTag = tag;
            this.mStyleValue = styleValue;
        }

        /**
         * Returns the variable font axis tag associated to this axis.
         */
        public int getTag() {
            return mTag;
        }

        /**
         * Returns the style value associated to the given axis for this font.
         */
        public float getStyleValue() {
            return mStyleValue;
        }

        /**
         * @hide
         */
        public Axis(Parcel in) {
            mTag = in.readInt();
            mStyleValue = in.readFloat();
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeInt(mTag);
            out.writeFloat(mStyleValue);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Axis> CREATOR = new Creator<Axis>() {
            @Override
            public Axis createFromParcel(Parcel in) {
                return new Axis(in);
            }

            @Override
            public Axis[] newArray(int size) {
                return new Axis[size];
            }
        };
    }

    /**
     * Class that holds information about a Font.
     */
    public static final class Font implements Parcelable {
        private final @NonNull String mFontName;
        private final int mTtcIndex;
        private final @NonNull Axis[] mAxes;
        private final int mWeight;
        private final boolean mIsItalic;
        private @Nullable ParcelFileDescriptor mFd;

        /**
         * @hide
         */
        public Font(@NonNull String fontName, int ttcIndex, @NonNull Axis[] axes, int weight,
                boolean isItalic) {
            mFontName = fontName;
            mTtcIndex = ttcIndex;
            mAxes = axes;
            mWeight = weight;
            mIsItalic = isItalic;
            mFd = null;
        }

        /**
         * This is for duplicating FileDescriptors.
         *
         * Note that this copy ctor doesn't deep copy the members.
         *
         * @hide
         */
        public Font(Font origin) {
            mFontName = origin.mFontName;
            mTtcIndex = origin.mTtcIndex;
            mAxes = origin.mAxes;
            mWeight = origin.mWeight;
            mIsItalic = origin.mIsItalic;
            if (origin.mFd != null) {
                try {
                    mFd = origin.mFd.dup();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
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
        public int getTtcIndex() {
            return mTtcIndex;
        }

        /**
         * Returns the list of axes associated to this font.
         */
        public @NonNull Axis[] getAxes() {
            return mAxes;
        }

        /**
         * Returns the weight value for this font.
         */
        public int getWeight() {
            return mWeight;
        }

        /**
         * Returns whether this font is italic.
         */
        public boolean isItalic() {
            return mIsItalic;
        }

        /**
         * Returns a file descriptor to access the specified font. This should be closed after use.
         */
        public @Nullable ParcelFileDescriptor getFd() {
            return mFd;
        }

        /**
         * @hide
         */
        public void setFd(@NonNull ParcelFileDescriptor fd) {
            mFd = fd;
        }

        /**
         * @hide
         */
        public Font(Parcel in) {
            mFontName = in.readString();
            mTtcIndex = in.readInt();
            mAxes = in.createTypedArray(Axis.CREATOR);
            mWeight = in.readInt();
            mIsItalic = in.readInt() == 1;
            if (in.readInt() == 1) { /* has FD */
                mFd = ParcelFileDescriptor.CREATOR.createFromParcel(in);
            } else {
                mFd = null;
            }
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeString(mFontName);
            out.writeInt(mTtcIndex);
            out.writeTypedArray(mAxes, flag);
            out.writeInt(mWeight);
            out.writeInt(mIsItalic ? 1 : 0);
            out.writeInt(mFd == null ? 0 : 1);
            if (mFd != null) {
                mFd.writeToParcel(out, flag);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Font> CREATOR = new Creator<Font>() {
            @Override
            public Font createFromParcel(Parcel in) {
                return new Font(in);
            }

            @Override
            public Font[] newArray(int size) {
                return new Font[size];
            }
        };
    }

    /**
     * Class that holds information about a Font alias.
     */
    public static final class Alias implements Parcelable {
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

        /**
         * @hide
         */
        public Alias(Parcel in) {
            mName = in.readString();
            mToName = in.readString();
            mWeight = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeString(mName);
            out.writeString(mToName);
            out.writeInt(mWeight);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Alias> CREATOR = new Creator<Alias>() {
            @Override
            public Alias createFromParcel(Parcel in) {
                return new Alias(in);
            }

            @Override
            public Alias[] newArray(int size) {
                return new Alias[size];
            }
        };
    }

    /**
     * Class that holds information about a Font family.
     */
    public static final class Family implements Parcelable {
        private final @NonNull String mName;
        private final @NonNull Font[] mFonts;
        private final @NonNull String mLanguage;

        /** @hide */
        @Retention(SOURCE)
        @IntDef({VARIANT_DEFAULT, VARIANT_COMPACT, VARIANT_ELEGANT})
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

        public Family(@NonNull String name, @NonNull Font[] fonts, @NonNull String language,
                @Variant int variant) {
            mName = name;
            mFonts = fonts;
            mLanguage = language;
            mVariant = variant;
        }

        /**
         * For duplicating file descriptor underlying Font object.
         *
         * This copy constructor is not for deep copying.
         * @hide
         */
        public Family(Family origin) {
            mName = origin.mName;
            mLanguage = origin.mLanguage;
            mVariant = origin.mVariant;
            mFonts = new Font[origin.mFonts.length];
            for (int i = 0; i < origin.mFonts.length; ++i) {
                mFonts[i] = new Font(origin.mFonts[i]);
            }
        }

        /**
         * Returns the name given by the system to this font family.
         */
        public @Nullable String getName() {
            return mName;
        }

        /**
         * Returns the list of fonts included in this family.
         */
        public @Nullable Font[] getFonts() {
            return mFonts;
        }

        /**
         * Returns the language for this family. May be null.
         */
        public @Nullable String getLanguage() {
            return mLanguage;
        }

        /**
         * Returns the font variant for this family, e.g. "elegant" or "compact". May be null.
         */
        public @Variant int getVariant() {
            return mVariant;
        }

        /**
         * @hide
         */
        public Family(Parcel in) {
            mName = in.readString();
            mFonts = in.readTypedArray(Font.CREATOR);
            mLanguage = in.readString();
            mVariant = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeString(mName);
            out.writeTypedArray(mFonts, flag);
            out.writeString(mLanguage);
            out.writeInt(mVariant);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Family> CREATOR = new Creator<Family>() {
            @Override
            public Family createFromParcel(Parcel in) {
                return new Family(in);
            }

            @Override
            public Family[] newArray(int size) {
                return new Family[size];
            }
        };
    }
}
