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
            @NonNull ArrayMap<String, ArrayList<FontFamily>> fallbackMap,
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
                xmlFamily.getName(), defaultFonts, languageTags, variant, cache);

        // Insert family into fallback map.
        for (int i = 0; i < fallbackMap.size(); i++) {
            String name = fallbackMap.keyAt(i);
            final ArrayList<FontConfig.Font> fallback = specificFallbackFonts.get(name);
            if (fallback == null) {
                String familyName = xmlFamily.getName();
                if (defaultFamily != null
                        // do not add myself to the fallback chain.
                        && (familyName == null || !familyName.equals(name))) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                }
            } else {
                final FontFamily family = createFontFamily(
                        xmlFamily.getName(), fallback, languageTags, variant, cache);
                if (family != null) {
                    fallbackMap.valueAt(i).add(family);
                } else if (defaultFamily != null) {
                    fallbackMap.valueAt(i).add(defaultFamily);
                } else {
                    // There is no valid for for default fallback. Ignore.
                }
            }
        }
    }

    private static @Nullable FontFamily createFontFamily(@NonNull String familyName,
            @NonNull List<FontConfig.Font> fonts,
            @NonNull String languageTags,
            @FontConfig.FontFamily.Variant int variant,
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
        return b == null ? null : b.build(languageTags, variant, false /* isCustomFallback */);
    }

    private static void appendNamedFamily(@NonNull FontConfig.FontFamily xmlFamily,
            @NonNull ArrayMap<String, ByteBuffer> bufferCache,
            @NonNull ArrayMap<String, ArrayList<FontFamily>> fallbackListMap) {
        final String familyName = xmlFamily.getName();
        final FontFamily family = createFontFamily(
                familyName, xmlFamily.getFontList(),
                xmlFamily.getLocaleList().toLanguageTags(), xmlFamily.getVariant(),
                bufferCache);
        if (family == null) {
            return;
        }
        final ArrayList<FontFamily> fallback = new ArrayList<>();
        fallback.add(family);
        fallbackListMap.put(familyName, fallback);
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
            return new FontConfig(Collections.emptyList(), Collections.emptyList(), 0, 0);
        } catch (XmlPullParserException e) {
            Log.e(TAG, "Failed to parse the system font configuration.", e);
            return new FontConfig(Collections.emptyList(), Collections.emptyList(), 0, 0);
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

    /** @hide */
    @VisibleForTesting
    public static Map<String, FontFamily[]> buildSystemFallback(FontConfig fontConfig,
            ArrayMap<String, ByteBuffer> outBufferCache) {
        final Map<String, FontFamily[]> fallbackMap = new ArrayMap<>();
        final List<FontConfig.FontFamily> xmlFamilies = fontConfig.getFontFamilies();

        final ArrayMap<String, ArrayList<FontFamily>> fallbackListMap = new ArrayMap<>();
        // First traverse families which have a 'name' attribute to create fallback map.
        for (final FontConfig.FontFamily xmlFamily : xmlFamilies) {
            final String familyName = xmlFamily.getName();
            if (familyName == null) {
                continue;
            }
            appendNamedFamily(xmlFamily, outBufferCache, fallbackListMap);
        }

        // Then, add fallback fonts to the each fallback map.
        for (int i = 0; i < xmlFamilies.size(); i++) {
            final FontConfig.FontFamily xmlFamily = xmlFamilies.get(i);
            // The first family (usually the sans-serif family) is always placed immediately
            // after the primary family in the fallback.
            if (i == 0 || xmlFamily.getName() == null) {
                pushFamilyToFallback(xmlFamily, fallbackListMap, outBufferCache);
            }
        }

        // Build the font map and fallback map.
        for (int i = 0; i < fallbackListMap.size(); i++) {
            final String fallbackName = fallbackListMap.keyAt(i);
            final List<FontFamily> familyList = fallbackListMap.valueAt(i);
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
