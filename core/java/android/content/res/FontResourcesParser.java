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
package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for xml type font resources.
 * @hide
 */
public class FontResourcesParser {
    private static final String TAG = "FontResourcesParser";

    // A class represents single entry of font-family in xml file.
    public interface FamilyResourceEntry {}

    // A class represents font provider based font-family element in xml file.
    public static final class ProviderResourceEntry implements FamilyResourceEntry {
        private final @NonNull String mProviderAuthority;
        private final @NonNull String mProviderPackage;
        private final @NonNull String mQuery;
        private final @Nullable List<List<String>> mCerts;

        public ProviderResourceEntry(@NonNull String authority, @NonNull String pkg,
                @NonNull String query, @Nullable List<List<String>> certs) {
            mProviderAuthority = authority;
            mProviderPackage = pkg;
            mQuery = query;
            mCerts = certs;
        }

        public @NonNull String getAuthority() {
            return mProviderAuthority;
        }

        public @NonNull String getPackage() {
            return mProviderPackage;
        }

        public @NonNull String getQuery() {
            return mQuery;
        }

        public @Nullable List<List<String>> getCerts() {
            return mCerts;
        }
    }

    // A class represents font element in xml file which points a file in resource.
    public static final class FontFileResourceEntry {
        private final @NonNull String mFileName;
        private int mWeight;
        private int mItalic;
        private int mTtcIndex;
        private String mVariationSettings;
        private int mResourceId;

        public FontFileResourceEntry(@NonNull String fileName, int weight, int italic,
                @Nullable String variationSettings, int ttcIndex) {
            mFileName = fileName;
            mWeight = weight;
            mItalic = italic;
            mVariationSettings = variationSettings;
            mTtcIndex = ttcIndex;
        }

        public @NonNull String getFileName() {
            return mFileName;
        }

        public int getWeight() {
            return mWeight;
        }

        public int getItalic() {
            return mItalic;
        }

        public @Nullable String getVariationSettings() {
            return mVariationSettings;
        }

        public int getTtcIndex() {
            return mTtcIndex;
        }
    }

    // A class represents file based font-family element in xml file.
    public static final class FontFamilyFilesResourceEntry implements FamilyResourceEntry {
        private final @NonNull FontFileResourceEntry[] mEntries;

        public FontFamilyFilesResourceEntry(@NonNull FontFileResourceEntry[] entries) {
            mEntries = entries;
        }

        public @NonNull FontFileResourceEntry[] getEntries() {
            return mEntries;
        }
    }

    public static @Nullable FamilyResourceEntry parse(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        int type;
        while ((type=parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
            // Empty loop.
        }

        if (type != XmlPullParser.START_TAG) {
            throw new XmlPullParserException("No start tag found");
        }
        return readFamilies(parser, resources);
    }

    private static @Nullable FamilyResourceEntry readFamilies(XmlPullParser parser,
            Resources resources) throws XmlPullParserException, IOException {
        parser.require(XmlPullParser.START_TAG, null, "font-family");
        String tag = parser.getName();
        FamilyResourceEntry result = null;
        if (tag.equals("font-family")) {
            return readFamily(parser, resources);
        } else {
            skip(parser);
            Log.e(TAG, "Failed to find font-family tag");
            return null;
        }
    }

    private static @Nullable FamilyResourceEntry readFamily(XmlPullParser parser,
            Resources resources) throws XmlPullParserException, IOException {
        AttributeSet attrs = Xml.asAttributeSet(parser);
        TypedArray array = resources.obtainAttributes(attrs, R.styleable.FontFamily);
        String authority = array.getString(R.styleable.FontFamily_fontProviderAuthority);
        String providerPackage = array.getString(R.styleable.FontFamily_fontProviderPackage);
        String query = array.getString(R.styleable.FontFamily_fontProviderQuery);
        int certsId = array.getResourceId(R.styleable.FontFamily_fontProviderCerts, 0);
        array.recycle();
        if (authority != null && providerPackage != null && query != null) {
            while (parser.next() != XmlPullParser.END_TAG) {
                skip(parser);
            }
            List<List<String>> certs = null;
            if (certsId != 0) {
                TypedArray typedArray = resources.obtainTypedArray(certsId);
                if (typedArray.length() > 0) {
                    certs = new ArrayList<>();
                    boolean isArrayOfArrays = typedArray.getResourceId(0, 0) != 0;
                    if (isArrayOfArrays) {
                        for (int i = 0; i < typedArray.length(); i++) {
                            int certId = typedArray.getResourceId(i, 0);
                            String[] certsArray = resources.getStringArray(certId);
                            List<String> certsList = Arrays.asList(certsArray);
                            certs.add(certsList);
                        }
                    } else {
                        String[] certsArray = resources.getStringArray(certsId);
                        List<String> certsList = Arrays.asList(certsArray);
                        certs.add(certsList);
                    }
                }
            }
            return new ProviderResourceEntry(authority, providerPackage, query, certs);
        }
        List<FontFileResourceEntry> fonts = new ArrayList<>();
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("font")) {
                final FontFileResourceEntry entry = readFont(parser, resources);
                if (entry != null) {
                    fonts.add(entry);
                }
            } else {
                skip(parser);
            }
        }
        if (fonts.isEmpty()) {
            return null;
        }
        return new FontFamilyFilesResourceEntry(fonts.toArray(
                new FontFileResourceEntry[fonts.size()]));
    }

    private static FontFileResourceEntry readFont(XmlPullParser parser, Resources resources)
            throws XmlPullParserException, IOException {
        AttributeSet attrs = Xml.asAttributeSet(parser);
        TypedArray array = resources.obtainAttributes(attrs, R.styleable.FontFamilyFont);
        int weight = array.getInt(R.styleable.FontFamilyFont_fontWeight,
                Typeface.RESOLVE_BY_FONT_TABLE);
        int italic = array.getInt(R.styleable.FontFamilyFont_fontStyle,
                Typeface.RESOLVE_BY_FONT_TABLE);
        String variationSettings = array.getString(
                R.styleable.FontFamilyFont_fontVariationSettings);
        int ttcIndex = array.getInt(R.styleable.FontFamilyFont_ttcIndex, 0);
        String filename = array.getString(R.styleable.FontFamilyFont_font);
        array.recycle();
        while (parser.next() != XmlPullParser.END_TAG) {
            skip(parser);
        }
        if (filename == null) {
            return null;
        }
        return new FontFileResourceEntry(filename, weight, italic, variationSettings, ttcIndex);
    }

    private static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
            }
        }
    }
}
