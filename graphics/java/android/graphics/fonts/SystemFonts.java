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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.FontListParser;
import android.graphics.Typeface;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

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
import java.util.Map;
import java.util.Set;

/**
 * Provides the system font configurations.
 */
public final class SystemFonts {
    private static final String TAG = "SystemFonts";

    private static final String FONTS_XML = "/system/etc/fonts.xml";
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
                defaultFonts, languageTags, variant, false, cache);

        // Insert family into fallback map.
        for (int i = 0; i < fallbackMap.size(); i++) {
            final String name = fallbackMap.keyAt(i);
            final NativeFamilyListSet familyListSet = fallbackMap.valueAt(i);
            if (familyListSet.seenXmlFamilies.contains(xmlFamily)) {
                continue;
            } else {
                familyListSet.seenXmlFamilies.add(xmlFamily);
            }
            final ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(name);
            if (fallback == null) {
                if (defaultFamily != null) {
                    familyListSet.familyList.add(defaultFamily);
                }
            } else {
                final FontFamily family = createFontFamily(fallback, languageTags, variant, false,
                        cache);
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
                isDefaultFallback);
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
                    true, // named family is always default
                    bufferCache);
            if (family == null) {
                return;
            }
            familyListSet.familyList.add(family);
            familyListSet.seenXmlFamilies.add(xmlFamily);
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
        return getSystemFontConfigInternal(FONTS_XML, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR,
                updatableFontMap, lastModifiedDate, configVersion);
    }

    /**
     * Get the system preinstalled FontConfig.
     * @hide
     */
    public static @NonNull FontConfig getSystemPreinstalledFontConfig() {
        return getSystemFontConfigInternal(FONTS_XML, SYSTEM_FONT_DIR, OEM_XML, OEM_FONT_DIR, null,
                0, 0);
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
            return FontListParser.parse(fontsXml, systemFontDir, oemXml, productFontDir,
                                                updatableFontMap, lastModifiedDate, configVersion);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open/read system font configurations.", e);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0, 0);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Failed to parse the system font configuration.", e);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), 0, 0);
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
        public Set<FontConfig.FontFamily> seenXmlFamilies = new ArraySet<>();
    }

    /** @hide */
    @VisibleForTesting
    public static Map<String, FontFamily[]> buildSystemFallback(FontConfig fontConfig,
            ArrayMap<String, ByteBuffer> outBufferCache) {

        final ArrayMap<String, NativeFamilyListSet> fallbackListMap = new ArrayMap<>();

        final List<FontConfig.NamedFamilyList> namedFamilies = fontConfig.getNamedFamilyLists();
        for (int i = 0; i < namedFamilies.size(); ++i) {
            FontConfig.NamedFamilyList namedFamilyList = namedFamilies.get(i);
            appendNamedFamilyList(namedFamilyList, outBufferCache, fallbackListMap);
        }

        // Then, add fallback fonts to the fallback map.
        final List<FontConfig.FontFamily> xmlFamilies = fontConfig.getFontFamilies();
        for (int i = 0; i < xmlFamilies.size(); i++) {
            final FontConfig.FontFamily xmlFamily = xmlFamilies.get(i);
            pushFamilyToFallback(xmlFamily, fallbackListMap, outBufferCache);
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
}
