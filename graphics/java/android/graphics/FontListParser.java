/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.fonts.FontCustomizationParser;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.os.Build;
import android.os.LocaleList;
import android.text.FontConfig;
import android.text.TextUtils;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parser for font config files.
 * @hide
 */
public class FontListParser {

    // XML constants for FontFamily.
    private static final String ATTR_NAME = "name";
    private static final String ATTR_LANG = "lang";
    private static final String ATTR_VARIANT = "variant";
    private static final String TAG_FONT = "font";
    private static final String VARIANT_COMPACT = "compact";
    private static final String VARIANT_ELEGANT = "elegant";

    // XML constants for Font.
    public static final String ATTR_INDEX = "index";
    public static final String ATTR_WEIGHT = "weight";
    public static final String ATTR_STYLE = "style";
    public static final String ATTR_FALLBACK_FOR = "fallbackFor";
    public static final String STYLE_ITALIC = "italic";
    public static final String STYLE_NORMAL = "normal";
    public static final String TAG_AXIS = "axis";

    // XML constants for FontVariationAxis.
    public static final String ATTR_TAG = "tag";
    public static final String ATTR_STYLEVALUE = "stylevalue";

    /* Parse fallback list (no names) */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static FontConfig parse(InputStream in) throws XmlPullParserException, IOException {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in, null);
        parser.nextTag();
        return readFamilies(parser, "/system/fonts/", new FontCustomizationParser.Result(), null,
                0, 0);
    }

    /**
     * Parses system font config XMLs
     *
     * @param fontsXmlPath location of fonts.xml
     * @param systemFontDir location of system font directory
     * @param oemCustomizationXmlPath location of oem_customization.xml
     * @param productFontDir location of oem customized font directory
     * @param updatableFontMap map of updated font files.
     * @return font configuration
     * @throws IOException
     * @throws XmlPullParserException
     */
    public static FontConfig parse(
            @NonNull String fontsXmlPath,
            @NonNull String systemFontDir,
            @Nullable String oemCustomizationXmlPath,
            @Nullable String productFontDir,
            @Nullable Map<String, File> updatableFontMap,
            long lastModifiedDate,
            int configVersion
    ) throws IOException, XmlPullParserException {
        FontCustomizationParser.Result oemCustomization;
        if (oemCustomizationXmlPath != null) {
            try (InputStream is = new FileInputStream(oemCustomizationXmlPath)) {
                oemCustomization = FontCustomizationParser.parse(is, productFontDir,
                        updatableFontMap);
            } catch (IOException e) {
                // OEM customization may not exists. Ignoring
                oemCustomization = new FontCustomizationParser.Result();
            }
        } else {
            oemCustomization = new FontCustomizationParser.Result();
        }

        try (InputStream is = new FileInputStream(fontsXmlPath)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(is, null);
            parser.nextTag();
            return readFamilies(parser, systemFontDir, oemCustomization, updatableFontMap,
                    lastModifiedDate, configVersion);
        }
    }

    private static FontConfig readFamilies(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @NonNull FontCustomizationParser.Result customization,
            @Nullable Map<String, File> updatableFontMap,
            long lastModifiedDate,
            int configVersion)
            throws XmlPullParserException, IOException {
        List<FontConfig.FontFamily> families = new ArrayList<>();
        List<FontConfig.Alias> aliases = new ArrayList<>(customization.getAdditionalAliases());

        Map<String, FontConfig.FontFamily> oemNamedFamilies =
                customization.getAdditionalNamedFamilies();

        parser.require(XmlPullParser.START_TAG, null, "familyset");
        while (keepReading(parser)) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals("family")) {
                FontConfig.FontFamily family = readFamily(parser, fontDir, updatableFontMap);
                String name = family.getName();
                if (name == null || !oemNamedFamilies.containsKey(name)) {
                    // The OEM customization overrides system named family. Skip if OEM
                    // customization XML defines the same named family.
                    families.add(family);
                }
            } else if (tag.equals("alias")) {
                aliases.add(readAlias(parser));
            } else {
                skip(parser);
            }
        }

        families.addAll(oemNamedFamilies.values());
        return new FontConfig(families, aliases, lastModifiedDate, configVersion);
    }

    private static boolean keepReading(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int next = parser.next();
        return next != XmlPullParser.END_TAG && next != XmlPullParser.END_DOCUMENT;
    }

    /**
     * Read family tag in fonts.xml or oem_customization.xml
     */
    public static FontConfig.FontFamily readFamily(XmlPullParser parser, String fontDir,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {
        final String name = parser.getAttributeValue(null, "name");
        final String lang = parser.getAttributeValue("", "lang");
        final String variant = parser.getAttributeValue(null, "variant");
        final List<FontConfig.Font> fonts = new ArrayList<>();
        while (keepReading(parser)) {
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            final String tag = parser.getName();
            if (tag.equals(TAG_FONT)) {
                fonts.add(readFont(parser, fontDir, updatableFontMap));
            } else {
                skip(parser);
            }
        }
        int intVariant = FontConfig.FontFamily.VARIANT_DEFAULT;
        if (variant != null) {
            if (variant.equals(VARIANT_COMPACT)) {
                intVariant = FontConfig.FontFamily.VARIANT_COMPACT;
            } else if (variant.equals(VARIANT_ELEGANT)) {
                intVariant = FontConfig.FontFamily.VARIANT_ELEGANT;
            }
        }
        return new FontConfig.FontFamily(fonts, name, LocaleList.forLanguageTags(lang), intVariant);
    }

    /**
     * Write a family tag representing {@code fontFamily}. The tag should be started by the caller.
     */
    public static void writeFamily(TypedXmlSerializer out, FontConfig.FontFamily fontFamily)
            throws IOException {
        if (!TextUtils.isEmpty(fontFamily.getName())) {
            out.attribute(null, ATTR_NAME, fontFamily.getName());
        }
        if (!fontFamily.getLocaleList().isEmpty()) {
            out.attribute(null, ATTR_LANG, fontFamily.getLocaleList().toLanguageTags());
        }
        switch (fontFamily.getVariant()) {
            case FontConfig.FontFamily.VARIANT_COMPACT:
                out.attribute(null, ATTR_VARIANT, VARIANT_COMPACT);
                break;
            case FontConfig.FontFamily.VARIANT_ELEGANT:
                out.attribute(null, ATTR_VARIANT, VARIANT_ELEGANT);
                break;
        }
        for (FontConfig.Font font : fontFamily.getFontList()) {
            out.startTag(null, TAG_FONT);
            writeFont(out, font);
            out.endTag(null, TAG_FONT);
        }
    }

    /** Matches leading and trailing XML whitespace. */
    private static final Pattern FILENAME_WHITESPACE_PATTERN =
            Pattern.compile("^[ \\n\\r\\t]+|[ \\n\\r\\t]+$");

    private static FontConfig.Font readFont(
            @NonNull XmlPullParser parser,
            @NonNull String fontDir,
            @Nullable Map<String, File> updatableFontMap)
            throws XmlPullParserException, IOException {

        String indexStr = parser.getAttributeValue(null, ATTR_INDEX);
        int index = indexStr == null ? 0 : Integer.parseInt(indexStr);
        List<FontVariationAxis> axes = new ArrayList<>();
        String weightStr = parser.getAttributeValue(null, ATTR_WEIGHT);
        int weight = weightStr == null ? FontStyle.FONT_WEIGHT_NORMAL : Integer.parseInt(weightStr);
        boolean isItalic = STYLE_ITALIC.equals(parser.getAttributeValue(null, ATTR_STYLE));
        String fallbackFor = parser.getAttributeValue(null, ATTR_FALLBACK_FOR);
        StringBuilder filename = new StringBuilder();
        while (keepReading(parser)) {
            if (parser.getEventType() == XmlPullParser.TEXT) {
                filename.append(parser.getText());
            }
            if (parser.getEventType() != XmlPullParser.START_TAG) continue;
            String tag = parser.getName();
            if (tag.equals(TAG_AXIS)) {
                axes.add(readAxis(parser));
            } else {
                skip(parser);
            }
        }
        String sanitizedName = FILENAME_WHITESPACE_PATTERN.matcher(filename).replaceAll("");
        String updatedName = findUpdatedFontFile(sanitizedName, updatableFontMap);
        String filePath;
        String originalPath;
        if (updatedName != null) {
            filePath = updatedName;
            originalPath = fontDir + sanitizedName;
        } else {
            filePath = fontDir + sanitizedName;
            originalPath = null;
        }

        String varSettings;
        if (axes.isEmpty()) {
            varSettings = "";
        } else {
            varSettings = FontVariationAxis.toFontVariationSettings(
                    axes.toArray(new FontVariationAxis[0]));
        }

        return new FontConfig.Font(new File(filePath),
                originalPath == null ? null : new File(originalPath),
                new FontStyle(
                        weight,
                        isItalic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT
                ),
                index,
                varSettings,
                fallbackFor);
    }

    private static String findUpdatedFontFile(String name,
            @Nullable Map<String, File> updatableFontMap) {
        if (updatableFontMap != null) {
            File updatedFile = updatableFontMap.get(name);
            if (updatedFile != null) {
                return updatedFile.getAbsolutePath();
            }
        }
        return null;
    }

    private static void writeFont(TypedXmlSerializer out, FontConfig.Font font)
            throws IOException {
        if (font.getTtcIndex() != 0) {
            out.attributeInt(null, ATTR_INDEX, font.getTtcIndex());
        }
        if (font.getStyle().getWeight() != FontStyle.FONT_WEIGHT_NORMAL) {
            out.attributeInt(null, ATTR_WEIGHT, font.getStyle().getWeight());
        }
        if (font.getStyle().getSlant() == FontStyle.FONT_SLANT_ITALIC) {
            out.attribute(null, ATTR_STYLE, STYLE_ITALIC);
        } else {
            out.attribute(null, ATTR_STYLE, STYLE_NORMAL);
        }
        if (!TextUtils.isEmpty(font.getFontFamilyName())) {
            out.attribute(null, ATTR_FALLBACK_FOR, font.getFontFamilyName());
        }
        out.text(font.getFile().getName());
        FontVariationAxis[] axes =
                FontVariationAxis.fromFontVariationSettings(font.getFontVariationSettings());
        if (axes != null) {
            for (FontVariationAxis axis : axes) {
                out.startTag(null, TAG_AXIS);
                writeAxis(out, axis);
                out.endTag(null, TAG_AXIS);
            }
        }
    }

    private static FontVariationAxis readAxis(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String tagStr = parser.getAttributeValue(null, ATTR_TAG);
        String styleValueStr = parser.getAttributeValue(null, ATTR_STYLEVALUE);
        skip(parser);  // axis tag is empty, ignore any contents and consume end tag
        return new FontVariationAxis(tagStr, Float.parseFloat(styleValueStr));
    }

    private static void writeAxis(TypedXmlSerializer out, FontVariationAxis axis)
            throws IOException {
        out.attribute(null, ATTR_TAG, axis.getTag());
        out.attributeFloat(null, ATTR_STYLEVALUE, axis.getStyleValue());
    }

    /**
     * Reads alias elements
     */
    public static FontConfig.Alias readAlias(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        String name = parser.getAttributeValue(null, "name");
        String toName = parser.getAttributeValue(null, "to");
        String weightStr = parser.getAttributeValue(null, "weight");
        int weight;
        if (weightStr == null) {
            weight = FontStyle.FONT_WEIGHT_NORMAL;
        } else {
            weight = Integer.parseInt(weightStr);
        }
        skip(parser);  // alias tag is empty, ignore any contents and consume end tag
        return new FontConfig.Alias(name, toName, weight);
    }

    /**
     * Skip until next element
     */
    public static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            switch (parser.next()) {
                case XmlPullParser.START_TAG:
                    depth++;
                    break;
                case XmlPullParser.END_TAG:
                    depth--;
                    break;
                case XmlPullParser.END_DOCUMENT:
                    return;
            }
        }
    }
}
