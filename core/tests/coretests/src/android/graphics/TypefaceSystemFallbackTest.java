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

package android.graphics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.fonts.FontCustomizationParser;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.SystemFonts;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.text.FontConfig;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TypefaceSystemFallbackTest {
    private static final String SYSTEM_FONT_DIR = "/system/fonts/";
    private static final String SYSTEM_FONTS_XML = "/system/etc/fonts.xml";

    private static final String[] TEST_FONT_FILES = {
        "a3em.ttf",  // Supports "a","b","c". The width of "a" is 3em,  others are 1em.
        "b3em.ttf",  // Supports "a","b","c". The width of "b" is 3em,  others are 1em.
        "c3em.ttf",  // Supports "a","b","c". The width of "c" is 3em,  others are 1em.
        "all2em.ttf",  // Supports "a,","b","c". All of them have the same width of 2em.
        "fallback.ttf",  // SUpports all small alphabets.
        "fallback_capital.ttf",  // SUpports all capital alphabets.
        "no_coverage.ttf",  // This font doesn't support any characters.
    };
    private static final String TEST_FONTS_XML;
    private static final String TEST_FONT_DIR;
    private static final String TEST_OEM_XML;
    private static final String TEST_OEM_DIR;
    private static final String TEST_UPDATABLE_FONT_DIR;

    private static final float GLYPH_1EM_WIDTH;
    private static final float GLYPH_2EM_WIDTH;
    private static final float GLYPH_3EM_WIDTH;

    static {
        final Context targetCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File cacheDir = new File(targetCtx.getCacheDir(), "TypefaceSystemFallbackTest");
        if (!cacheDir.isDirectory()) {
            cacheDir.mkdirs();
        }
        TEST_FONT_DIR = cacheDir.getAbsolutePath() + "/fonts/";
        TEST_FONTS_XML = new File(cacheDir, "fonts.xml").getAbsolutePath();
        TEST_OEM_DIR = cacheDir.getAbsolutePath() + "/oem_fonts/";
        TEST_OEM_XML = new File(cacheDir, "fonts_customization.xml").getAbsolutePath();
        TEST_UPDATABLE_FONT_DIR = cacheDir.getAbsolutePath() + "/updatable_fonts/";

        new File(TEST_FONT_DIR).mkdirs();
        new File(TEST_OEM_DIR).mkdirs();
        new File(TEST_UPDATABLE_FONT_DIR).mkdirs();

        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        final Paint paint = new Paint();
        paint.setTypeface(new Typeface.Builder(am, "fonts/a3em.ttf").build());
        GLYPH_3EM_WIDTH = paint.measureText("a");
        GLYPH_1EM_WIDTH = paint.measureText("b");

        paint.setTypeface(new Typeface.Builder(am, "fonts/all2em.ttf").build());
        GLYPH_2EM_WIDTH = paint.measureText("a");
    }

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        for (final String fontFile : TEST_FONT_FILES) {
            final String sourceInAsset = "fonts/" + fontFile;
            copyAssetToFile(sourceInAsset, new File(TEST_FONT_DIR, fontFile));
            copyAssetToFile(sourceInAsset, new File(TEST_OEM_DIR, fontFile));
        }
        for (final File fontFile : new File(TEST_UPDATABLE_FONT_DIR).listFiles()) {
            fontFile.delete();
        }
    }

    @After
    public void tearDown() {
        for (final String fontFile : TEST_FONT_FILES) {
            final File outInCache = new File(TEST_FONT_DIR, fontFile);
            outInCache.delete();
            final File outOemInCache = new File(TEST_OEM_DIR, fontFile);
            outOemInCache.delete();
        }
        for (final File fontFile : new File(TEST_UPDATABLE_FONT_DIR).listFiles()) {
            fontFile.delete();
        }
    }

    private static void copyAssetToFile(String sourceInAsset, File out) {
        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        try (InputStream is = am.open(sourceInAsset)) {
            Files.copy(is, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void buildSystemFallback(
            @NonNull String xml,
            @Nullable String oemXml,
            @NonNull ArrayMap<String, Typeface> outFontMap,
            @NonNull ArrayMap<String, FontFamily[]> outFallbackMap) {
        try (FileOutputStream fos = new FileOutputStream(TEST_FONTS_XML)) {
            fos.write(xml.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String oemXmlPath;
        if (oemXml != null) {
            try (FileOutputStream fos = new FileOutputStream(TEST_OEM_XML)) {
                fos.write(oemXml.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            oemXmlPath = TEST_OEM_XML;
        } else {
            oemXmlPath = null;
        }

        Map<String, File> updatableFontMap = new HashMap<>();
        for (File file : new File(TEST_UPDATABLE_FONT_DIR).listFiles()) {
            final String fileName = file.getName();
            final int periodIndex = fileName.lastIndexOf(".");
            final String psName = fileName.substring(0, periodIndex);
            updatableFontMap.put(psName, file);
        }

        FontConfig fontConfig;
        try {
            fontConfig = FontListParser.parse(
                    TEST_FONTS_XML, TEST_FONT_DIR, oemXmlPath, TEST_OEM_DIR, updatableFontMap, 0,
                    0);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException(e);
        }

        Map<String, FontFamily[]> fallbackMap = SystemFonts.buildSystemFallback(fontConfig);
        Map<String, Typeface> typefaceMap = SystemFonts.buildSystemTypefaces(
                fontConfig, fallbackMap);

        outFontMap.clear();
        outFontMap.putAll(typefaceMap);
        outFallbackMap.clear();
        outFallbackMap.putAll(fallbackMap);
    }

    private static FontCustomizationParser.Result readFontCustomization(String oemXml) {
        try (InputStream is = new ByteArrayInputStream(oemXml.getBytes(StandardCharsets.UTF_8))) {
            return FontCustomizationParser.parse(is, TEST_OEM_DIR, null);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testBuildSystemFallback() {
        FontConfig fontConfig;
        try {
            fontConfig = FontListParser.parse(
                    SYSTEM_FONTS_XML, SYSTEM_FONT_DIR, null, TEST_OEM_DIR, null, 0, 0);
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException(e);
        }
        assertFalse(fontConfig.getAliases().isEmpty());
        assertFalse(fontConfig.getFontFamilies().isEmpty());

        Map<String, FontFamily[]> fallbackMap = SystemFonts.buildSystemFallback(fontConfig);
        assertFalse(fallbackMap.isEmpty());

        Map<String, Typeface> typefaceMap = SystemFonts.buildSystemTypefaces(
                fontConfig, fallbackMap);
        assertFalse(typefaceMap.isEmpty());
    }

    @Test
    public void testBuildSystemFallback_NonExistentFontShouldBeIgnored() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "    <font weight='400' style='normal'>NoSuchFont.ttf</font>"
                + "  </family>"
                + "  <family name='NoSuchFont'>"
                + "    <font weight='400' style='normal'>NoSuchFont.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>NoSuchFont.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        assertEquals(1, fontMap.size());
        assertTrue(fontMap.containsKey("sans-serif"));
        assertEquals(1, fallbackMap.size());
        assertTrue(fallbackMap.containsKey("sans-serif"));
    }

    @Test
    public void testBuildSystemFallback_NamedFamily() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "  <family name='test2'>"
                + "    <font weight='400' style='normal'>c3em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>all2em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface sansSerifTypeface = fontMap.get("sans-serif");
        assertNotNull(sansSerifTypeface);
        paint.setTypeface(sansSerifTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface testTypeface = fontMap.get("test");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface test2Typeface = fontMap.get("test2");
        assertNotNull(test2Typeface);
        paint.setTypeface(test2Typeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_defaultFallback() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>all2em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface sansSerifTypeface = fontMap.get("sans-serif");
        assertNotNull(sansSerifTypeface);
        paint.setTypeface(sansSerifTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface testTypeface = fontMap.get("test");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_namedFallbackFamily() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test2'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal' fallbackFor='test'>a3em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal' fallbackFor='test2'>b3em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>all2em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface sansSerifTypeface = fontMap.get("sans-serif");
        assertNotNull(sansSerifTypeface);
        paint.setTypeface(sansSerifTypeface);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface testTypeface = fontMap.get("test");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface test2Typeface = fontMap.get("test2");
        assertNotNull(test2Typeface);
        paint.setTypeface(test2Typeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_namedFallbackFamily2() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test2'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal' fallbackFor='test'>a3em.ttf</font>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>all2em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface sansSerifTypeface = fontMap.get("sans-serif");
        assertNotNull(sansSerifTypeface);
        paint.setTypeface(sansSerifTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface testTypeface = fontMap.get("test");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface test2Typeface = fontMap.get("test2");
        assertNotNull(test2Typeface);
        paint.setTypeface(test2Typeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_ImplicitSansSerifFallback() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='test2'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>all2em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface testTypeface = fontMap.get("test");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        final Typeface test2Typeface = fontMap.get("test2");
        assertNotNull(test2Typeface);
        paint.setTypeface(test2Typeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_ElegantFallback() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family variant='elegant'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <family variant='compact'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface testTypeface = fontMap.get("serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        paint.setElegantTextHeight(true);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        paint.setElegantTextHeight(false);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_ElegantFallback_customFallback() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family variant='elegant'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "    <font weight='400' style='normal' fallbackFor='serif'>b3em.ttf</font>"
                + "  </family>"
                + "  <family variant='compact'>"
                + "    <font weight='400' style='normal'>c3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        paint.setElegantTextHeight(true);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        paint.setElegantTextHeight(false);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        paint.setElegantTextHeight(true);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        paint.setElegantTextHeight(false);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_multiLingualFamilies() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family lang='de'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <family lang='it,fr'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();
        paint.setTypeface(fontMap.get("sans-serif"));

        paint.setTextLocale(Locale.GERMANY);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        paint.setTextLocale(Locale.ITALY);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        paint.setTextLocale(Locale.FRANCE);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_ElegantFallback_customFallback_missingFile() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "    <font weight='400' style='normal' fallbackFor='serif'>NoSuchFont.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback_ElegantFallback_customFallback_missingFile2() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='serif'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal' fallbackFor='serif'>NoSuchFont.ttf</font>"
                + "  </family>"
                + "  <family>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback__Customization_new_named_family() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family' name='google-sans'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</fonts-modification>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, oemXml, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("google-sans");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    private String getFontName(Paint paint, String text) {
        PositionedGlyphs glyphs = TextRunShaper.shapeTextRun(
                text, 0, text.length(), 0, text.length(), 0f, 0f, false, paint);
        assertEquals(1, glyphs.glyphCount());
        return glyphs.getFont(0).getFile().getName();
    }

    @Test
    public void testBuildSystemFallback__Customization_new_named_familyList() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>fallback_capital.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family-list customizationType='new-named-family' name='google-sans'>"
                + "    <family>"
                + "      <font weight='400' style='normal'>b3em.ttf</font>"
                + "    </family>"
                + "    <family>"
                + "      <font weight='400' style='normal'>fallback.ttf</font>"
                + "    </family>"
                + "  </family-list>"
                + "</fonts-modification>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, oemXml, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("google-sans");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals("b3em.ttf", getFontName(paint, "a"));
        assertEquals("fallback.ttf", getFontName(paint, "x"));
        assertEquals("fallback_capital.ttf", getFontName(paint, "A"));
    }

    @Test
    public void testBuildSystemFallback__Customization_new_named_family_override() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family' name='sans-serif'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</fonts-modification>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, oemXml, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback__Customization_additional_alias() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family' name='google-sans'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "    <font weight='700' style='normal'>c3em.ttf</font>"
                + "  </family>"
                + "  <alias name='another-google-sans' to='google-sans' />"
                + "  <alias name='google-sans-bold' to='google-sans' weight='700' />"
                + "</fonts-modification>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, oemXml, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("sans-serif");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("google-sans");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("another-google-sans");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("google-sans-bold");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    @Test
    public void testBuildSystemFallback__Customization_additional_alias_conflict_with_new_name() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='named-family'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "  <alias name='named-alias' to='named-family' />"
                + "</familyset>";
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family' name='named-alias'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</fonts-modification>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, oemXml, fontMap, fallbackMap);

        final Paint paint = new Paint();

        Typeface testTypeface = fontMap.get("named-family");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);

        testTypeface = fontMap.get("named-alias");
        assertNotNull(testTypeface);
        paint.setTypeface(testTypeface);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_3EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_1EM_WIDTH, paint.measureText("c"), 0.0f);
    }

    private static void assertA3emFontIsUsed(Typeface typeface) {
        final Paint paint = new Paint();
        assertNotNull(typeface);
        paint.setTypeface(typeface);
        assertTrue("a3em font must be used", GLYPH_3EM_WIDTH == paint.measureText("a")
                && GLYPH_1EM_WIDTH == paint.measureText("b")
                && GLYPH_1EM_WIDTH == paint.measureText("c"));
    }

    private static void assertB3emFontIsUsed(Typeface typeface) {
        final Paint paint = new Paint();
        assertNotNull(typeface);
        paint.setTypeface(typeface);
        assertTrue("b3em font must be used", GLYPH_1EM_WIDTH == paint.measureText("a")
                && GLYPH_3EM_WIDTH == paint.measureText("b")
                && GLYPH_1EM_WIDTH == paint.measureText("c"));
    }

    private static String getBaseXml(String font, String lang) {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family name='named-family'>"
                + "    <font weight='400' style='normal'>no_coverage.ttf</font>"
                + "  </family>"
                + "  <family lang='%s'>"
                + "    <font weight='400' style='normal'>%s</font>"
                + "  </family>"
                + "</familyset>";
        return String.format(xml, lang, font);
    }

    private static String getCustomizationXml(String font, String op, String lang) {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-locale-family' operation='%s' lang='%s'>"
                + "    <font weight='400' style='normal' fallbackFor='named-family'>%s</font>"
                + "  </family>"
                + "</fonts-modification>";
        return String.format(xml, op, lang, font);
    }

    @Test
    public void testBuildSystemFallback__Customization_locale_prepend() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(
                getBaseXml("a3em.ttf", "ja-JP"),
                getCustomizationXml("b3em.ttf", "prepend", "ja-JP"),
                fontMap, fallbackMap);
        Typeface typeface = fontMap.get("named-family");

        // operation "prepend" places font before the original font, thus b3em is used.
        assertB3emFontIsUsed(typeface);
    }

    @Test
    public void testBuildSystemFallback__Customization_locale_replace() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(
                getBaseXml("a3em.ttf", "ja-JP"),
                getCustomizationXml("b3em.ttf", "replace", "ja-JP"),
                fontMap, fallbackMap);
        Typeface typeface = fontMap.get("named-family");

        // operation "replace" removes the original font, thus b3em font is used.
        assertB3emFontIsUsed(typeface);
    }

    @Test
    public void testBuildSystemFallback__Customization_locale_append() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(
                getBaseXml("a3em.ttf", "ja-JP"),
                getCustomizationXml("b3em.ttf", "append", "ja-JP"),
                fontMap, fallbackMap);
        Typeface typeface = fontMap.get("named-family");

        // operation "append" comes next to the original font, so the original "a3em" is used.
        assertA3emFontIsUsed(typeface);
    }

    @Test
    public void testBuildSystemFallback__Customization_locale_ScriptMismatch() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(
                getBaseXml("a3em.ttf", "ja-JP"),
                getCustomizationXml("b3em.ttf", "replace", "ko-KR"),
                fontMap, fallbackMap);
        Typeface typeface = fontMap.get("named-family");

        // Since the script doesn't match, the customization is ignored.
        assertA3emFontIsUsed(typeface);
    }

    @Test
    public void testBuildSystemFallback__Customization_locale_SubscriptMatch() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(
                getBaseXml("a3em.ttf", "ja-JP"),
                getCustomizationXml("b3em.ttf", "replace", "ko-Hani-KR"),
                fontMap, fallbackMap);
        Typeface typeface = fontMap.get("named-family");

        // Hani script is supported by Japanese, Jpan.
        assertB3emFontIsUsed(typeface);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildSystemFallback__Customization_new_named_family_no_name_exception() {
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</fonts-modification>";
        readFontCustomization(oemXml);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBuildSystemFallback__Customization_new_named_family_dup_name_exception() {
        final String oemXml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<fonts-modification version='1'>"
                + "  <family customizationType='new-named-family' name='google-sans'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "  <family customizationType='new-named-family' name='google-sans'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</fonts-modification>";
        readFontCustomization(oemXml);
    }

    @Test
    public void testBuildSystemFallback_UpdatableFont() {
        final String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='test'>"
                + "    <font weight='400' style='normal'>a3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        // Install all2em.ttf as a3em.ttf
        copyAssetToFile("fonts/all2em.ttf", new File(TEST_UPDATABLE_FONT_DIR, "a3em.ttf"));
        buildSystemFallback(xml, null, fontMap, fallbackMap);

        final Paint paint = new Paint();

        final Typeface sansSerifTypeface = fontMap.get("test");
        assertNotNull(sansSerifTypeface);
        paint.setTypeface(sansSerifTypeface);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("a"), 0.0f);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("b"), 0.0f);
        assertEquals(GLYPH_2EM_WIDTH, paint.measureText("c"), 0.0f);
    }
}
