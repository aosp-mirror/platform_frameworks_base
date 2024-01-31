/*
 * Copyright 2018 The Android Open Source Project
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

import static android.text.FontConfig.Customization.LocaleFallback.OPERATION_APPEND;
import static android.text.FontConfig.Customization.LocaleFallback.OPERATION_PREPEND;
import static android.text.FontConfig.Customization.LocaleFallback.OPERATION_REPLACE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.FontListParser;
import android.graphics.Typeface;
import android.os.LocaleList;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provides the system font configurations.
 */
public final class SystemFonts {
    private static final String TAG = "SystemFonts";

    private static final String FONTS_XML = "/system/etc/font_fallback.xml";
    private static final String LEGACY_FONTS_XML = "/system/etc/fonts.xml";

    /** @hide */
    public static final String SYSTEM_FONT_DIR = "/system/fonts/";
    private static final String OEM_XML = "/product/etc/fonts_customization.xml";
    /** @hide */
    public static final String OEM_FONT_DIR = "/product/fonts/";

    private SystemFonts() {}  // Do not instansiate.

    private static final Object LOCK = new Object();
    private static @GuardedBy("sLock") Set<Font> sAvailableFonts;

    /**
     * Returns all available font files in the system.
     *
     * @return a set of system fonts
     */
    public static @NonNull Set<Font> getAvailableFonts() {
        synchronized (LOCK) {
            if (sAvailableFonts == null) {
                sAvailableFonts = Font.getAvailableFonts();
            }
            return sAvailableFonts;
        }
    }

    /**
     * @hide
     */
    public static void resetAvailableFonts() {
        synchronized (LOCK) {
            sAvailableFonts = null;
        }
    }

    private static @Nullable ByteBuffer mmap(@NonNull String fullPath) {
        try (FileInputStream file = new FileInputStream(fullPath)) {
            final FileChannel fileChannel = file.getChannel();
            final long fontSize = fileChannel.size();
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fontSize);
        } catch (IOException e) {
            return null;
        }
    }

    /** @hide */
    @VisibleForTesting
    public static @FontFamily.Builder.VariableFontFamilyType int resolveVarFamilyType(
            @NonNull FontConfig.FontFamily xmlFamily,
            @Nullable String familyName) {
        int wghtCount = 0;
        int italCount = 0;
        int targetFonts = 0;
        boolean hasItalicFont = false;

        List<FontConfig.Font> fonts = xmlFamily.getFontList();
        for (int i = 0; i < fonts.size(); ++i) {
            FontConfig.Font font = fonts.get(i);

            if (familyName == null) {  // for default family
                if (font.getFontFamilyName() != null) {
                    continue;  // this font is not for the default family.
                }
            } else {  // for the specific family
                if (!familyName.equals(font.getFontFamilyName())) {
                    continue;  // this font is not for given family.
                }
            }

            final int varTypeAxes = font.getVarTypeAxes();
            if (varTypeAxes == 0) {
                // If we see static font, we can immediately return as VAR_TYPE_NONE.
                return FontFamily.Builder.VARIABLE_FONT_FAMILY_TYPE_NONE;
            }

            if ((varTypeAxes & FontConfig.Font.VAR_TYPE_AXES_WGHT) != 0) {
                wghtCount++;
            }

            if ((varTypeAxes & FontConfig.Font.VAR_TYPE_AXES_ITAL) != 0) {
                italCount++;
            }

            if (font.getStyle().getSlant() == FontStyle.FONT_SLANT_ITALIC) {
                hasItalicFont = true;
            }
            targetFonts++;
        }

        if (italCount == 0) {  // No ital font.
            if (targetFonts == 1 && wghtCount == 1) {
                // If there is only single font that has wght, use it for regular style and
                // use synthetic bolding for italic.
                return FontFamily.Builder.VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ONLY;
            } else if (targetFonts == 2 && wghtCount == 2 && hasItalicFont) {
                // If there are two fonts and italic font is available, use them for regular and
                // italic separately. (It is impossible to have two italic fonts. It will end up
                // with Typeface creation failure.)
                return FontFamily.Builder.VARIABLE_FONT_FAMILY_TYPE_TWO_FONTS_WGHT;
            }
        } else if (italCount == 1) {
            // If ital font is included, a single font should support both wght and ital.
            if (wghtCount == 1 && targetFonts == 1) {
                return FontFamily.Builder.VARIABLE_FONT_FAMILY_TYPE_SINGLE_FONT_WGHT_ITAL;
            }
        }
        // Otherwise, unsupported.
        return FontFamily.Builder.VARIABLE_FONT_FAMILY_TYPE_NONE;
    }

    private static void pushFamilyToFallback(@NonNull FontConfig.FontFamily xmlFamily,
            @NonNull ArrayMap<String, NativeFamilyListSet> fallbackMap,
            @NonNull Map<String, ByteBuffer> cache) {
        final String languageTags = xmlFamily.getLocaleList().toLanguageTags();
        final int variant = xmlFamily.getVariant();

        final ArrayList<FontConfig.Font> defaultFonts = new ArrayList<>();
        final ArrayMap<String, ArrayList<FontConfig.Font>> specificFallbackFonts =
                new ArrayMap<>();

        // Collect default fallback and specific fallback fonts.
        for (final FontConfig.Font font : xmlFamily.getFonts()) {
            final String fallbackName = font.getFontFamilyName();
            if (fallbackName == null) {
                defaultFonts.add(font);
            } else {
                ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(fallbackName);
                if (fallback == null) {
                    fallback = new ArrayList<>();
                    specificFallbackFonts.put(fallbackName, fallback);
                }
                fallback.add(font);
            }
        }

        final FontFamily defaultFamily = defaultFonts.isEmpty() ? null : createFontFamily(
                defaultFonts, languageTags, variant, resolveVarFamilyType(xmlFamily, null), false,
                cache);
        // Insert family into fallback map.
        for (int i = 0; i < fallbackMap.size(); i++) {
            final String name = fallbackMap.keyAt(i);
            final NativeFamilyListSet familyListSet = fallbackMap.valueAt(i);
            int identityHash = System.identityHashCode(xmlFamily);
            if (familyListSet.seenXmlFamilies.get(identityHash, -1) != -1) {
                continue;
            } else {
                familyListSet.seenXmlFamilies.append(identityHash, 1);
            }
            final ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(name);
            if (fallback == null) {
                if (defaultFamily != null) {
                    familyListSet.familyList.add(defaultFamily);
                }
            } else {
                final FontFamily family = createFontFamily(fallback, languageTags, variant,
                        resolveVarFamilyType(xmlFamily, name), false, cache);
                if (family != null) {
                    familyListSet.familyList.add(family);
                } else if (defaultFamily != null) {
                    familyListSet.familyList.add(defaultFamily);
                } else {
                    // There is no valid for for default fallback. Ignore.
                }
            }
        }
    }

    private static @Nullable FontFamily createFontFamily(
            @NonNull List<FontConfig.Font> fonts,
            @NonNull String languageTags,
            @FontConfig.FontFamily.Variant int variant,
            int varFamilyType,
            boolean isDefaultFallback,
            @NonNull Map<String, ByteBuffer> cache) {
        if (fonts.size() == 0) {
            return null;
        }

        FontFamily.Builder b = null;
        for (int i = 0; i < fonts.size(); i++) {
            final FontConfig.Font fontConfig = fonts.get(i);
            final String fullPath = fontConfig.getFile().getAbsolutePath();
            ByteBuffer buffer = cache.get(fullPath);
            if (buffer == null) {
                if (cache.containsKey(fullPath)) {
                    continue;  // Already failed to mmap. Skip it.
                }
                buffer = mmap(fullPath);
                cache.put(fullPath, buffer);
                if (buffer == null) {
                    continue;
                }
            }

            final Font font;
            try {
                font = new Font.Builder(buffer, new File(fullPath), languageTags)
                        .setWeight(fontConfig.getStyle().getWeight())
                        .setSlant(fontConfig.getStyle().getSlant())
                        .setTtcIndex(fontConfig.getTtcIndex())
                        .setFontVariationSettings(fontConfig.getFontVariationSettings())
                        .build();
            } catch (IOException e) {
                throw new RuntimeException(e);  // Never reaches here
            }

            if (b == null) {
                b = new FontFamily.Builder(font);
            } else {
                b.addFont(font);
            }
        }
        return b == null ? null : b.build(languageTags, variant, false /* isCustomFallback */,
                isDefaultFallback, varFamilyType);
    }

    private static void appendNamedFamilyList(@NonNull FontConfig.NamedFamilyList namedFamilyList,
            @NonNull ArrayMap<String, ByteBuffer> bufferCache,
            @NonNull ArrayMap<String, NativeFamilyListSet> fallbackListMap) {
        final String familyName = namedFamilyList.getName();
        final NativeFamilyListSet familyListSet = new NativeFamilyListSet();
        final List<FontConfig.FontFamily> xmlFamilies = namedFamilyList.getFamilies();
        for (int i = 0; i < xmlFamilies.size(); ++i) {
            FontConfig.FontFamily xmlFamily = xmlFamilies.get(i);
            final FontFamily family = createFontFamily(
                    xmlFamily.getFontList(),
                    xmlFamily.getLocaleList().toLanguageTags(), xmlFamily.getVariant(),
                    resolveVarFamilyType(xmlFamily,
                            null /* all fonts under named family should be treated as default */),
                    true, // named family is always default
                    bufferCache);
            if (family == null) {
                return;
            }
            familyListSet.familyList.add(family);
            familyListSet.seenXmlFamilies.append(System.identityHashCode(xmlFamily), 1);
        }
        fallbackListMap.put(familyName, familyListSet);
    }

    /**
     * Get the updated FontConfig.
     *
     * @param updatableFontMap a font mapping of updated font files.
     * @hide
     */
    public static @NonNull FontConfig getSystemFontConfig(
            @Nullable Map<String, File> updatableFontMap,
            long lastModifiedDate,
            int configVersion
    ) {
        final String fontsXml;
        if (com.android.text.flags.Flags.newFontsFallbackXml()) {
            fontsXml = FONTS_XML;
        } else {
            fontsXml = LEGACY_FONTS_XML;
        }
        return getSystemFontConfigInternal(fontsXml, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR,
                updatableFontMap, lastModifiedDate, configVersion);
    }

    /**
     * Get the updated FontConfig.
     *
     * @param updatableFontMap a font mapping of updated font files.
     * @hide
     */
    public static @NonNull FontConfig getSystemFontConfigForTesting(
            @NonNull String fontsXml,
            @Nullable Map<String, File> updatableFontMap,
            long lastModifiedDate,
            int configVersion
    ) {
        return getSystemFontConfigInternal(fontsXml, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR,
                updatableFontMap, lastModifiedDate, configVersion);
    }

    /**
     * Get the system preinstalled FontConfig.
     * @hide
     */
    public static @NonNull FontConfig getSystemPreinstalledFontConfig() {
        final String fontsXml;
        if (com.android.text.flags.Flags.newFontsFallbackXml()) {
            fontsXml = FONTS_XML;
        } else {
            fontsXml = LEGACY_FONTS_XML;
        }
        return getSystemFontConfigInternal(fontsXml, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR, null,
                0, 0);
    }

    /**
     * @hide
     */
    public static @NonNull FontConfig getSystemPreinstalledFontConfigFromLegacyXml() {
        return getSystemFontConfigInternal(LEGACY_FONTS_XML, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR,
                null, 0, 0);
    }

    /* package */ static @NonNull FontConfig getSystemFontConfigInternal(
            @NonNull String fontsXml,
            @NonNull String systemFontDir,
            @Nullable String oemXml,
            @Nullable String productFontDir,
            @Nullable Map<String, File> updatableFontMap,
            long lastModifiedDate,
            int configVersion
    ) {
        try {
            Log.i(TAG, "Loading font config from " + fontsXml);
            return FontListParser.parse(fontsXml, systemFontDir, oemXml, productFontDir,
                                                updatableFontMap, lastModifiedDate, configVersion);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open/read system font configurations.", e);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), 0, 0);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Failed to parse the system font configuration.", e);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), 0, 0);
        }
    }

    /**
     * Build the system fallback from FontConfig.
     * @hide
     */
    @VisibleForTesting
    public static Map<String, FontFamily[]> buildSystemFallback(FontConfig fontConfig) {
        return buildSystemFallback(fontConfig, new ArrayMap<>());
    }

    private static final class NativeFamilyListSet {
        public List<FontFamily> familyList = new ArrayList<>();
        public SparseIntArray seenXmlFamilies = new SparseIntArray();
    }

    /** @hide */
    @VisibleForTesting
    public static Map<String, FontFamily[]> buildSystemFallback(FontConfig fontConfig,
            ArrayMap<String, ByteBuffer> outBufferCache) {

        final ArrayMap<String, NativeFamilyListSet> fallbackListMap = new ArrayMap<>();
        final List<FontConfig.Customization.LocaleFallback> localeFallbacks =
                fontConfig.getLocaleFallbackCustomizations();

        final List<FontConfig.NamedFamilyList> namedFamilies = fontConfig.getNamedFamilyLists();
        for (int i = 0; i < namedFamilies.size(); ++i) {
            FontConfig.NamedFamilyList namedFamilyList = namedFamilies.get(i);
            appendNamedFamilyList(namedFamilyList, outBufferCache, fallbackListMap);
        }

        // Then, add fallback fonts to the fallback map.
        final List<FontConfig.Customization.LocaleFallback> customizations = new ArrayList<>();
        final List<FontConfig.FontFamily> xmlFamilies = fontConfig.getFontFamilies();
        final SparseIntArray seenCustomization = new SparseIntArray();
        for (int i = 0; i < xmlFamilies.size(); i++) {
            final FontConfig.FontFamily xmlFamily = xmlFamilies.get(i);

            customizations.clear();
            for (int j = 0; j < localeFallbacks.size(); ++j) {
                if (seenCustomization.get(j, -1) != -1) {
                    continue;  // The customization is already applied.
                }
                FontConfig.Customization.LocaleFallback localeFallback = localeFallbacks.get(j);
                if (scriptMatch(xmlFamily.getLocaleList(), localeFallback.getScript())) {
                    customizations.add(localeFallback);
                    seenCustomization.put(j, 1);
                }
            }

            if (customizations.isEmpty()) {
                pushFamilyToFallback(xmlFamily, fallbackListMap, outBufferCache);
            } else {
                for (int j = 0; j < customizations.size(); ++j) {
                    FontConfig.Customization.LocaleFallback localeFallback = customizations.get(j);
                    if (localeFallback.getOperation() == OPERATION_PREPEND) {
                        pushFamilyToFallback(localeFallback.getFamily(), fallbackListMap,
                                outBufferCache);
                    }
                }
                boolean isReplaced = false;
                for (int j = 0; j < customizations.size(); ++j) {
                    FontConfig.Customization.LocaleFallback localeFallback = customizations.get(j);
                    if (localeFallback.getOperation() == OPERATION_REPLACE) {
                        pushFamilyToFallback(localeFallback.getFamily(), fallbackListMap,
                                outBufferCache);
                        isReplaced = true;
                    }
                }
                if (!isReplaced) {  // If nothing is replaced, push the original one.
                    pushFamilyToFallback(xmlFamily, fallbackListMap, outBufferCache);
                }
                for (int j = 0; j < customizations.size(); ++j) {
                    FontConfig.Customization.LocaleFallback localeFallback = customizations.get(j);
                    if (localeFallback.getOperation() == OPERATION_APPEND) {
                        pushFamilyToFallback(localeFallback.getFamily(), fallbackListMap,
                                outBufferCache);
                    }
                }
            }
        }

        // Build the font map and fallback map.
        final Map<String, FontFamily[]> fallbackMap = new ArrayMap<>();
        for (int i = 0; i < fallbackListMap.size(); i++) {
            final String fallbackName = fallbackListMap.keyAt(i);
            final List<FontFamily> familyList = fallbackListMap.valueAt(i).familyList;
            fallbackMap.put(fallbackName, familyList.toArray(new FontFamily[0]));
        }

        return fallbackMap;
    }

    /**
     * Build the system Typeface mappings from FontConfig and FallbackMap.
     * @hide
     */
    @VisibleForTesting
    public static Map<String, Typeface> buildSystemTypefaces(
            FontConfig fontConfig,
            Map<String, FontFamily[]> fallbackMap) {
        final ArrayMap<String, Typeface> result = new ArrayMap<>();
        Typeface.initSystemDefaultTypefaces(fallbackMap, fontConfig.getAliases(), result);
        return result;
    }

    private static boolean scriptMatch(LocaleList localeList, String targetScript) {
        if (localeList == null || localeList.isEmpty()) {
            return false;
        }
        for (int i = 0; i < localeList.size(); ++i) {
            Locale locale = localeList.get(i);
            if (locale == null) {
                continue;
            }
            String baseScript = FontConfig.resolveScript(locale);
            if (baseScript.equals(targetScript)) {
                return true;
            }

            // Subtag match
            if (targetScript.equals("Bopo") && baseScript.equals("Hanb")) {
                // Hanb is Han with Bopomofo.
                return true;
            } else if (targetScript.equals("Hani")) {
                if (baseScript.equals("Hanb") || baseScript.equals("Hans")
                        || baseScript.equals("Hant") || baseScript.equals("Kore")
                        || baseScript.equals("Jpan")) {
                    // Han id suppoted by Taiwanese, Traditional Chinese, Simplified Chinese, Korean
                    // and Japanese.
                    return true;
                }
            } else if (targetScript.equals("Hira") || targetScript.equals("Hrkt")
                    || targetScript.equals("Kana")) {
                if (baseScript.equals("Jpan") || baseScript.equals("Hrkt")) {
                    // Hiragana, Hiragana-Katakana, Katakana is supported by Japanese and
                    // Hiragana-Katakana script.
                    return true;
                }
            }
        }
        return false;
    }
}
