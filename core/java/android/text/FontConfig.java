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

import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Font configuration descriptions for System fonts.
 */
public final class FontConfig implements Parcelable {
    private final List<Family> mFamilies = new ArrayList<>();
    private final List<Alias> mAliases = new ArrayList<>();

    public FontConfig() {
    }

    public FontConfig(FontConfig config) {
        for (int i = 0; i < config.mFamilies.size(); i++) {
            mFamilies.add(new Family(config.mFamilies.get(i)));
        }
        mAliases.addAll(config.mAliases);
    }

    /**
     * Returns the ordered list of families included in the system fonts.
     */
    public List<Family> getFamilies() {
        return mFamilies;
    }

    /**
     * Returns the list of aliases defined for the font families in the system fonts.
     */
    public List<Alias> getAliases() {
        return mAliases;
    }

    /**
     * @hide
     */
    public FontConfig(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel out, int flag) {
        out.writeInt(mFamilies.size());
        for (int i = 0; i < mFamilies.size(); i++) {
            mFamilies.get(i).writeToParcel(out, flag);
        }
        out.writeInt(mAliases.size());
        for (int i = 0; i < mAliases.size(); i++) {
            mAliases.get(i).writeToParcel(out, flag);
        }
    }

    /**
     * @hide
     */
    public void readFromParcel(Parcel in) {
        int size = in.readInt();
        for (int i = 0; i < size; i++) {
            mFamilies.add(new Family(in));
        }
        size = in.readInt();
        for (int i = 0; i < size; i++) {
            mAliases.add(new Alias(in));
        }
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
        private String mFontName;
        private final int mTtcIndex;
        private final List<Axis> mAxes;
        private final int mWeight;
        private final boolean mIsItalic;
        private ParcelFileDescriptor mFd;
        private final int mResourceId;

        /**
         * @hide
         */
        public Font(String fontName, int ttcIndex, List<Axis> axes, int weight, boolean isItalic,
                int resourceId) {
            mFontName = fontName;
            mTtcIndex = ttcIndex;
            mAxes = axes;
            mWeight = weight;
            mIsItalic = isItalic;
            mFd = null;
            mResourceId = resourceId;
        }

        public Font(String fontName, int ttcIndex, List<Axis> axes, int weight, boolean isItalic) {
            this(fontName, ttcIndex, axes, weight, isItalic, 0);
        }

        public Font(Font origin) {
            mFontName = origin.mFontName;
            mTtcIndex = origin.mTtcIndex;
            mAxes = new ArrayList<>(origin.mAxes);
            mWeight = origin.mWeight;
            mIsItalic = origin.mIsItalic;
            if (origin.mFd != null) {
                try {
                    mFd = origin.mFd.dup();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            mResourceId = origin.mResourceId;
        }

        /**
         * Returns the name associated by the system to this font.
         */
        public String getFontName() {
            return mFontName;
        }

        /**
         * @hide
         */
        public void setFontName(String fontName) {
            mFontName = fontName;
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
        public List<Axis> getAxes() {
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
        public ParcelFileDescriptor getFd() {
            return mFd;
        }

        /**
         * @hide
         */
        public void setFd(ParcelFileDescriptor fd) {
            mFd = fd;
        }

        /**
         * @hide
         */
        public int getResourceId() {
            return mResourceId;
        }

        /**
         * @hide
         */
        public Font(Parcel in) {
            mFontName = in.readString();
            mTtcIndex = in.readInt();
            final int numAxes = in.readInt();
            mAxes = new ArrayList<>();
            for (int i = 0; i < numAxes; i++) {
                mAxes.add(new Axis(in));
            }
            mWeight = in.readInt();
            mIsItalic = in.readInt() == 1;
            if (in.readInt() == 1) { /* has FD */
                mFd = ParcelFileDescriptor.CREATOR.createFromParcel(in);
            } else {
                mFd = null;
            }
            mResourceId = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeString(mFontName);
            out.writeInt(mTtcIndex);
            out.writeInt(mAxes.size());
            for (int i = 0; i < mAxes.size(); i++) {
                mAxes.get(i).writeToParcel(out, flag);
            }
            out.writeInt(mWeight);
            out.writeInt(mIsItalic ? 1 : 0);
            out.writeInt(mFd == null ? 0 : 1);
            if (mFd != null) {
                mFd.writeToParcel(out, flag);
            }
            out.writeInt(mResourceId);
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
        private final String mName;
        private final String mToName;
        private final int mWeight;

        public Alias(String name, String toName, int weight) {
            this.mName = name;
            this.mToName = toName;
            this.mWeight = weight;
        }

        /**
         * Returns the new name for the alias.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the existing name to which this alias points to.
         */
        public String getToName() {
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
        private final String mName;
        private final List<Font> mFonts;
        private final String mLanguage;
        private final String mVariant;
        private final String mProviderAuthority;
        private final String mProviderPackage;
        private final String mQuery;

        public Family(String name, List<Font> fonts, String language, String variant) {
            mName = name;
            mFonts = fonts;
            mLanguage = language;
            mVariant = variant;
            mProviderAuthority = null;
            mProviderPackage = null;
            mQuery = null;
        }

        /**
         * @hide
         */
        public Family(String providerAuthority, String providerPackage, String query) {
            mName = null;
            mFonts = null;
            mLanguage = null;
            mVariant = null;
            mProviderAuthority = providerAuthority;
            mProviderPackage = providerPackage;
            mQuery = query;
        }

        public Family(Family origin) {
            mName = origin.mName;
            mLanguage = origin.mLanguage;
            mVariant = origin.mVariant;
            mFonts = new ArrayList<>();
            for (int i = 0; i < origin.mFonts.size(); i++) {
                mFonts.add(new Font(origin.mFonts.get(i)));
            }
            mProviderAuthority = origin.mProviderAuthority;
            mProviderPackage = origin.mProviderPackage;
            mQuery = origin.mQuery;
        }

        /**
         * Returns the name given by the system to this font family.
         */
        public String getName() {
            return mName;
        }

        /**
         * Returns the list of fonts included in this family.
         */
        public List<Font> getFonts() {
            return mFonts;
        }

        /**
         * Returns the language for this family. May be null.
         */
        public String getLanguage() {
            return mLanguage;
        }

        /**
         * Returns the font variant for this family, e.g. "elegant" or "compact". May be null.
         */
        public String getVariant() {
            return mVariant;
        }

        /**
         * @hide
         */
        public String getProviderAuthority() {
            return mProviderAuthority;
        }

        /**
         * @hide
         */
        public String getProviderPackage() {
            return mProviderPackage;
        }

        /**
         * @hide
         */
        public String getQuery() {
            return mQuery;
        }

        /**
         * @hide
         */
        public Family(Parcel in) {
            mName = in.readString();
            final int size = in.readInt();
            mFonts = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                mFonts.add(new Font(in));
            }
            mLanguage = in.readString();
            mVariant = in.readString();
            if (in.readInt() == 1) {
                mProviderAuthority = in.readString();
            } else {
                mProviderAuthority = null;
            }
            if (in.readInt() == 1) {
                mProviderPackage = in.readString();
            } else {
                mProviderPackage = null;
            }
            if (in.readInt() == 1) {
                mQuery = in.readString();
            } else {
                mQuery = null;
            }
        }

        @Override
        public void writeToParcel(Parcel out, int flag) {
            out.writeString(mName);
            out.writeInt(mFonts.size());
            for (int i = 0; i < mFonts.size(); i++) {
                mFonts.get(i).writeToParcel(out, flag);
            }
            out.writeString(mLanguage);
            out.writeString(mVariant);
            out.writeInt(mProviderAuthority == null ? 0 : 1);
            if (mProviderAuthority != null) {
                out.writeString(mProviderAuthority);
            }
            out.writeInt(mProviderPackage == null ? 0 : 1);
            if (mProviderPackage != null) {
                out.writeString(mProviderPackage);
            }
            out.writeInt(mQuery == null ? 0 : 1);
            if (mQuery != null) {
                out.writeString(mQuery);
            }
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
