/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.Parcelable;
import android.text.FontConfig;
import android.util.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a font update request. Currently only font install request is supported.
 * @hide
 */
public final class FontUpdateRequest implements Parcelable {

    public static final int TYPE_UPDATE_FONT_FILE = 0;
    public static final int TYPE_UPDATE_FONT_FAMILY = 1;

    @IntDef(prefix = "TYPE_", value = {
            TYPE_UPDATE_FONT_FILE,
            TYPE_UPDATE_FONT_FAMILY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Type {}

    /**
     * Font object used for update.
     *
     * Here is an example of Family/Font XML.
     * <family name="my-sans">
     *   <font name="MySans" weight="400" slant="0" axis="'wght' 400 'ital' 0" index="0" />
     *   <font name="MySans" weight="400" slant="0" axis="'wght' 400 'ital' 1" index="0" />
     *   <font name="MySans" weight="400" slant="0" axis="'wght' 700 'ital' 0" index="0" />
     *   <font name="MySans" weight="400" slant="0" axis="'wght' 700 'ital' 1" index="0" />
     * </family>
     *
     * @see Font#readFromXml(XmlPullParser)
     * @see Font#writeToXml(TypedXmlSerializer, Font)
     * @see Family#readFromXml(XmlPullParser)
     * @see Family#writeFamilyToXml(TypedXmlSerializer, Family)
     */
    public static final class Font implements Parcelable {
        private static final String ATTR_INDEX = "index";
        private static final String ATTR_WEIGHT = "weight";
        private static final String ATTR_SLANT = "slant";
        private static final String ATTR_AXIS = "axis";
        private static final String ATTR_POSTSCRIPT_NAME = "name";

        private final @NonNull String mPostScriptName;
        private final @NonNull FontStyle mFontStyle;
        private final @IntRange(from = 0) int mIndex;
        private final @NonNull String mFontVariationSettings;

        public Font(@NonNull String postScriptName, @NonNull FontStyle fontStyle,
                @IntRange(from = 0) int index, @NonNull String fontVariationSettings) {
            mPostScriptName = postScriptName;
            mFontStyle = fontStyle;
            mIndex = index;
            mFontVariationSettings = fontVariationSettings;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString8(mPostScriptName);
            dest.writeInt(mFontStyle.getWeight());
            dest.writeInt(mFontStyle.getSlant());
            dest.writeInt(mIndex);
            dest.writeString8(mFontVariationSettings);
        }

        public static final @NonNull Creator<Font> CREATOR = new Creator<Font>() {
            @Override
            public Font createFromParcel(Parcel source) {
                String fontName = source.readString8();
                int weight = source.readInt();
                int slant = source.readInt();
                int index = source.readInt();
                String varSettings = source.readString8();
                return new Font(fontName, new FontStyle(weight, slant), index, varSettings);
            }

            @Override
            public Font[] newArray(int size) {
                return new Font[size];
            }
        };

        /**
         * Write {@link Font} instance to XML file.
         *
         * For the XML format, see {@link Font} class comment.
         *
         * @param out output XML serializer
         * @param font a Font instance to be written.
         */
        public static void writeToXml(TypedXmlSerializer out, Font font) throws IOException {
            out.attribute(null, ATTR_POSTSCRIPT_NAME, font.getPostScriptName());
            out.attributeInt(null, ATTR_INDEX, font.getIndex());
            out.attributeInt(null, ATTR_WEIGHT, font.getFontStyle().getWeight());
            out.attributeInt(null, ATTR_SLANT, font.getFontStyle().getSlant());
            out.attribute(null, ATTR_AXIS, font.getFontVariationSettings());
        }

        /**
         * Read {@link Font} instance from &lt;font&gt; element in XML
         *
         * For the XML format, see {@link Font} class comment.
         *
         * @param parser a parser that point &lt;font&gt; element.
         * @return a font instance
         * @throws IOException if font element is invalid.
         */
        public static Font readFromXml(XmlPullParser parser) throws IOException {
            String psName = parser.getAttributeValue(null, ATTR_POSTSCRIPT_NAME);
            if (psName == null) {
                throw new IOException("name attribute is missing in font tag.");
            }
            int index = getAttributeValueInt(parser, ATTR_INDEX, 0);
            int weight = getAttributeValueInt(parser, ATTR_WEIGHT, FontStyle.FONT_WEIGHT_NORMAL);
            int slant = getAttributeValueInt(parser, ATTR_SLANT, FontStyle.FONT_SLANT_UPRIGHT);
            String varSettings = parser.getAttributeValue(null, ATTR_AXIS);
            if (varSettings == null) {
                varSettings = "";
            }
            return new Font(psName, new FontStyle(weight, slant), index, varSettings);
        }

        public @NonNull String getPostScriptName() {
            return mPostScriptName;
        }

        public @NonNull FontStyle getFontStyle() {
            return mFontStyle;
        }

        public @IntRange(from = 0) int getIndex() {
            return mIndex;
        }

        public @NonNull String getFontVariationSettings() {
            return mFontVariationSettings;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Font font = (Font) o;
            return mIndex == font.mIndex
                    && mPostScriptName.equals(font.mPostScriptName)
                    && mFontStyle.equals(font.mFontStyle)
                    && mFontVariationSettings.equals(font.mFontVariationSettings);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mPostScriptName, mFontStyle, mIndex, mFontVariationSettings);
        }

        @Override
        public String toString() {
            return "Font{"
                    + "mPostScriptName='" + mPostScriptName + '\''
                    + ", mFontStyle=" + mFontStyle
                    + ", mIndex=" + mIndex
                    + ", mFontVariationSettings='" + mFontVariationSettings + '\''
                    + '}';
        }
    }

    /**
     * Font Family object used for update request.
     */
    public static final class Family implements Parcelable {
        private static final String TAG_FAMILY = "family";
        private static final String ATTR_NAME = "name";
        private static final String TAG_FONT = "font";

        private final @NonNull String mName;
        private final @NonNull List<Font> mFonts;

        public Family(String name, List<Font> fonts) {
            mName = name;
            mFonts = fonts;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString8(mName);
            dest.writeParcelableList(mFonts, flags);
        }

        public static final @NonNull Creator<Family> CREATOR = new Creator<Family>() {

            @Override
            public Family createFromParcel(Parcel source) {
                String familyName = source.readString8();
                List<Font> fonts = source.readParcelableList(
                        new ArrayList<>(), Font.class.getClassLoader(), android.graphics.fonts.FontUpdateRequest.Font.class);
                return new Family(familyName, fonts);
            }

            @Override
            public Family[] newArray(int size) {
                return new Family[size];
            }
        };

        /**
         * Write {@link Family} instance to XML.
         *
         * For the XML format, see {@link Font} class comment.
         *
         * @param out an output XML serializer
         * @param family a {@link Family} instance to be written
         */
        public static void writeFamilyToXml(@NonNull TypedXmlSerializer out, @NonNull Family family)
                throws IOException {
            out.attribute(null, ATTR_NAME, family.getName());
            List<Font> fonts = family.getFonts();
            for (int i = 0; i < fonts.size(); ++i) {
                Font font = fonts.get(i);
                out.startTag(null, TAG_FONT);
                Font.writeToXml(out, font);
                out.endTag(null, TAG_FONT);
            }
        }

        /**
         * Read a {@link Family} instance from &lt;family&gt; element in XML
         *
         * For the XML format, see {@link Font} class comment.
         *
         * @param parser an XML parser that points &lt;family&gt; element.
         * @return an {@link Family} instance
         */
        public static @NonNull Family readFromXml(@NonNull XmlPullParser parser)
                throws XmlPullParserException, IOException {
            List<Font> fonts = new ArrayList<>();
            if (parser.getEventType() != XmlPullParser.START_TAG
                    || !parser.getName().equals(TAG_FAMILY)) {
                throw new IOException("Unexpected parser state: must be START_TAG with family");
            }
            String name = parser.getAttributeValue(null, ATTR_NAME);
            if (name == null) {
                throw new IOException("name attribute is missing in family tag.");
            }
            int type = 0;
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG && parser.getName().equals(TAG_FONT)) {
                    fonts.add(Font.readFromXml(parser));
                } else if (type == XmlPullParser.END_TAG && parser.getName().equals(TAG_FAMILY)) {
                    break;
                }
            }
            return new Family(name, fonts);
        }

        public @NonNull String getName() {
            return mName;
        }

        public @NonNull List<Font> getFonts() {
            return mFonts;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Family family = (Family) o;
            return mName.equals(family.mName) && mFonts.equals(family.mFonts);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mName, mFonts);
        }

        @Override
        public String toString() {
            return "Family{mName='" + mName + '\'' + ", mFonts=" + mFonts + '}';
        }
    }

    public static final Creator<FontUpdateRequest> CREATOR = new Creator<FontUpdateRequest>() {
        @Override
        public FontUpdateRequest createFromParcel(Parcel in) {
            return new FontUpdateRequest(in);
        }

        @Override
        public FontUpdateRequest[] newArray(int size) {
            return new FontUpdateRequest[size];
        }
    };

    private final @Type int mType;
    // NonNull if mType == TYPE_UPDATE_FONT_FILE.
    @Nullable
    private final ParcelFileDescriptor mFd;
    // NonNull if mType == TYPE_UPDATE_FONT_FILE.
    @Nullable
    private final byte[] mSignature;
    // NonNull if mType == TYPE_UPDATE_FONT_FAMILY.
    @Nullable
    private final Family mFontFamily;

    public FontUpdateRequest(@NonNull ParcelFileDescriptor fd, @NonNull byte[] signature) {
        mType = TYPE_UPDATE_FONT_FILE;
        mFd = fd;
        mSignature = signature;
        mFontFamily = null;
    }

    public FontUpdateRequest(@NonNull Family fontFamily) {
        mType = TYPE_UPDATE_FONT_FAMILY;
        mFd = null;
        mSignature = null;
        mFontFamily = fontFamily;
    }

    public FontUpdateRequest(@NonNull String familyName,
            @NonNull List<FontFamilyUpdateRequest.Font> variations) {
        this(createFontFamily(familyName, variations));
    }

    private static Family createFontFamily(@NonNull String familyName,
            @NonNull List<FontFamilyUpdateRequest.Font> fonts) {
        List<Font> updateFonts = new ArrayList<>(fonts.size());
        for (FontFamilyUpdateRequest.Font font : fonts) {
            updateFonts.add(new Font(
                    font.getPostScriptName(),
                    font.getStyle(),
                    font.getIndex(),
                    FontVariationAxis.toFontVariationSettings(font.getAxes())));
        }
        return new Family(familyName, updateFonts);
    }

    protected FontUpdateRequest(Parcel in) {
        mType = in.readInt();
        mFd = in.readParcelable(ParcelFileDescriptor.class.getClassLoader(), android.os.ParcelFileDescriptor.class);
        mSignature = in.readBlob();
        mFontFamily = in.readParcelable(FontConfig.FontFamily.class.getClassLoader(), android.graphics.fonts.FontUpdateRequest.Family.class);
    }

    public @Type int getType() {
        return mType;
    }

    @Nullable
    public ParcelFileDescriptor getFd() {
        return mFd;
    }

    @Nullable
    public byte[] getSignature() {
        return mSignature;
    }

    @Nullable
    public Family getFontFamily() {
        return mFontFamily;
    }

    @Override
    public int describeContents() {
        return mFd != null ? mFd.describeContents() : 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeParcelable(mFd, flags);
        dest.writeBlob(mSignature);
        dest.writeParcelable(mFontFamily, flags);
    }

    // Utility functions
    private static int getAttributeValueInt(XmlPullParser parser, String name, int defaultValue) {
        try {
            String value = parser.getAttributeValue(null, name);
            if (value == null) {
                return defaultValue;
            }
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
