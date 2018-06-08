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

import android.content.Context;
import android.content.res.AssetManager;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArrayMap;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

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
        "no_coverage.ttf",  // This font doesn't support any characters.
    };
    private static final String TEST_FONTS_XML;
    private static final String TEST_FONT_DIR;

    private static final float GLYPH_1EM_WIDTH;
    private static final float GLYPH_2EM_WIDTH;
    private static final float GLYPH_3EM_WIDTH;

    static {
        final Context targetCtx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final File cacheDir = new File(targetCtx.getCacheDir(), "TypefaceSystemFallbackTest");
        if (!cacheDir.isDirectory()) {
            cacheDir.mkdirs();
        }
        TEST_FONT_DIR = cacheDir.getAbsolutePath() + "/";
        TEST_FONTS_XML = new File(cacheDir, "fonts.xml").getAbsolutePath();

        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        final Paint paint = new Paint();
        paint.setTypeface(new Typeface.Builder(am, "fonts/a3em.ttf").build());
        GLYPH_3EM_WIDTH = paint.measureText("a");
        GLYPH_1EM_WIDTH = paint.measureText("b");

        paint.setTypeface(new Typeface.Builder(am, "fonts/all2em.ttf").build());
        GLYPH_2EM_WIDTH = paint.measureText("a");
    }

    @Before
    public void setUp() {
        final AssetManager am =
                InstrumentationRegistry.getInstrumentation().getContext().getAssets();
        for (final String fontFile : TEST_FONT_FILES) {
            final String sourceInAsset = "fonts/" + fontFile;
            final File outInCache = new File(TEST_FONT_DIR, fontFile);
            try (InputStream is = am.open(sourceInAsset)) {
                Files.copy(is, outInCache.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @After
    public void tearDown() {
        for (final String fontFile : TEST_FONT_FILES) {
            final File outInCache = new File(TEST_FONT_DIR, fontFile);
            outInCache.delete();
        }
    }

    private static void buildSystemFallback(String xml,
            ArrayMap<String, Typeface> fontMap, ArrayMap<String, FontFamily[]> fallbackMap) {
        try (FileOutputStream fos = new FileOutputStream(TEST_FONTS_XML)) {
            fos.write(xml.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Typeface.buildSystemFallback(TEST_FONTS_XML, TEST_FONT_DIR, fontMap, fallbackMap);
    }

    @Test
    public void testBuildSystemFallback() {
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        Typeface.buildSystemFallback(SYSTEM_FONTS_XML, SYSTEM_FONT_DIR, fontMap, fallbackMap);

        assertFalse(fontMap.isEmpty());
        assertFalse(fallbackMap.isEmpty());
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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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
                + "  <family lang='it fr'>"
                + "    <font weight='400' style='normal'>b3em.ttf</font>"
                + "  </family>"
                + "</familyset>";
        final ArrayMap<String, Typeface> fontMap = new ArrayMap<>();
        final ArrayMap<String, FontFamily[]> fallbackMap = new ArrayMap<>();

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

        buildSystemFallback(xml, fontMap, fallbackMap);

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

}
