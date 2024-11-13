/*
 * Copyright (C) 2008 The Android Open Source Project
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.SystemFonts;
import android.os.SharedMemory;
import android.text.FontConfig;
import android.util.ArrayMap;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class TypefaceTest {

    // create array of all std faces
    private final Typeface[] mFaces = new Typeface[] {
        Typeface.create(Typeface.SANS_SERIF, 0),
        Typeface.create(Typeface.SANS_SERIF, 1),
        Typeface.create(Typeface.SERIF, 0),
        Typeface.create(Typeface.SERIF, 1),
        Typeface.create(Typeface.SERIF, 2),
        Typeface.create(Typeface.SERIF, 3),
        Typeface.create(Typeface.MONOSPACE, 0)
    };

    private static final int[] STYLES = {
        Typeface.NORMAL, Typeface.BOLD, Typeface.ITALIC, Typeface.BOLD_ITALIC,
    };

    @SmallTest
    @Test
    public void testBasic() throws Exception {
        assertTrue("basic", Typeface.DEFAULT != null);
        assertTrue("basic", Typeface.DEFAULT_BOLD != null);
        assertTrue("basic", Typeface.SANS_SERIF != null);
        assertTrue("basic", Typeface.SERIF != null);
        assertTrue("basic", Typeface.MONOSPACE != null);
    }

    @SmallTest
    @Test
    public void testDefaults() {
        for (int style : STYLES) {
            String msg = "style = " + style;
            assertNotNull(msg, Typeface.defaultFromStyle(style));
            assertEquals(msg, style, Typeface.defaultFromStyle(style).getStyle());
        }
    }

    @SmallTest
    @Test
    public void testUnique() throws Exception {
        final int n = mFaces.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                assertTrue("unique", mFaces[i] != mFaces[j]);
            }
        }
    }

    @SmallTest
    @Test
    public void testStyles() throws Exception {
        assertTrue("style", mFaces[0].getStyle() == Typeface.NORMAL);
        assertTrue("style", mFaces[1].getStyle() == Typeface.BOLD);
        assertTrue("style", mFaces[2].getStyle() == Typeface.NORMAL);
        assertTrue("style", mFaces[3].getStyle() == Typeface.BOLD);
        assertTrue("style", mFaces[4].getStyle() == Typeface.ITALIC);
        assertTrue("style", mFaces[5].getStyle() == Typeface.BOLD_ITALIC);
        assertTrue("style", mFaces[6].getStyle() == Typeface.NORMAL);
    }

    @MediumTest
    @Test
    public void testUniformY() throws Exception {
        Paint p = new Paint();
        final int n = mFaces.length;
        for (int i = 1; i <= 36; i++) {
            p.setTextSize(i);
            float ascent = 0;
            float descent = 0;
            for (int j = 0; j < n; j++) {
                p.setTypeface(mFaces[j]);
                Paint.FontMetrics fm = p.getFontMetrics();
                if (j == 0) {
                    ascent = fm.ascent;
                    descent = fm.descent;
                } else {
                    assertTrue("fontMetrics", fm.ascent == ascent);
                    assertTrue("fontMetrics", fm.descent == descent);
                }
            }
        }
    }

    @LargeTest
    @Test
    public void testMultithreadCacheStressTest() {
        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Resources res = context.getResources();
        final AssetManager assets = res.getAssets();
        final Typeface[] baseTypefaces = {
            null,
            Typeface.SANS_SERIF,
            Typeface.SERIF,
            Typeface.MONOSPACE,
            res.getFont(R.font.samplefont),
            res.getFont(R.font.samplefont2),
            res.getFont(R.font.samplefont3),
            res.getFont(R.font.samplefont4),
            res.getFont(R.font.samplexmlfont),
            Typeface.createFromAsset(assets, "fonts/a3em.ttf"),
            Typeface.createFromAsset(assets, "fonts/b3em.ttf"),
            Typeface.createFromAsset(assets, "fonts/c3em.ttf"),
            Typeface.createFromAsset(assets, "fonts/all2em.ttf"),
            Typeface.createFromAsset(assets, "fonts/hasGlyphTestFont.ttf"),
            Typeface.createFromAsset(assets, "fonts/samplefont1.ttf"),
            Typeface.createFromAsset(assets, "fonts/no_coverage.ttf"),
        };

        final int loopCount = 10000;

        final Runnable threadedCreater = () -> {
            final Random random = new Random();
            for (int i = 0; i < loopCount; ++i) {
                final Typeface base = baseTypefaces[random.nextInt(baseTypefaces.length)];
                if (random.nextBoolean()) {
                    final int style = random.nextInt(3);
                    final Typeface result = Typeface.create(base, style);
                    assertEquals(style, result.getStyle());
                } else {
                    final int weight = 100 * (random.nextInt(10) + 1);  // [100, 1000]
                    final boolean italic = random.nextBoolean();
                    final Typeface result = Typeface.create(base, weight, italic);
                    assertEquals(italic, result.isItalic());
                    assertEquals(weight, result.getWeight());
                }
            }
        };

        final int threadCount = 4;
        final Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; ++i) {
            threads[i] = new Thread(threadedCreater);
        }

        for (int i = 0; i < threadCount; ++i) {
            threads[i].start();
        }

        for (int i = 0; i < threadCount; ++i) {
            try {
                threads[i].join();
            } catch (InterruptedException e) {
                // ignore
            }
        }

    }

    @SmallTest
    @Test
    public void testSerialize() throws Exception {
        FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
        Map<String, FontFamily[]> fallbackMap = SystemFonts.buildSystemFallback(fontConfig);
        Map<String, Typeface> systemFontMap = SystemFonts.buildSystemTypefaces(fontConfig,
                fallbackMap);
        SharedMemory sharedMemory = Typeface.serializeFontMap(systemFontMap);
        Map<String, Typeface> copiedFontMap = new ArrayMap<>();
        try {
            Typeface.deserializeFontMap(sharedMemory.mapReadOnly().order(ByteOrder.BIG_ENDIAN),
                    copiedFontMap);
            assertEquals(systemFontMap.size(), copiedFontMap.size());
            for (String key : systemFontMap.keySet()) {
                assertTrue(copiedFontMap.containsKey(key));
                Typeface original = systemFontMap.get(key);
                Typeface copied = copiedFontMap.get(key);
                assertEquals(original.getStyle(), copied.getStyle());
                assertEquals(original.getWeight(), copied.getWeight());
                assertEquals(measureText(original, "hello"), measureText(copied, "hello"), 1e-6);
            }
        } finally {
            for (Typeface typeface : copiedFontMap.values()) {
                typeface.releaseNativeObjectForTest();
            }
        }
    }

    @SmallTest
    @Test
    public void testSetSystemFontMap() throws Exception {

        // Typeface.setSystemFontMap mutate the returned map. So copying for the backup.
        HashMap<String, Typeface> backup = new HashMap<>(Typeface.getSystemFontMap());

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Resources res = context.getResources();
        Map<String, Typeface> fontMap = Map.of(
                "sans-serif", Typeface.create(res.getFont(R.font.samplefont), Typeface.NORMAL),
                "serif", Typeface.create(res.getFont(R.font.samplefont2), Typeface.NORMAL),
                "monospace", Typeface.create(res.getFont(R.font.samplefont3), Typeface.NORMAL),
                "sample", Typeface.create(res.getFont(R.font.samplefont4), Typeface.NORMAL),
                "sample-italic", Typeface.create(res.getFont(R.font.samplefont4), Typeface.ITALIC));

        try {
            Typeface.setSystemFontMap(fontMap);

            // Test public static final fields
            assertEquals(fontMap.get("sans-serif"), Typeface.DEFAULT);
            assertEquals(Typeface.BOLD, Typeface.DEFAULT_BOLD.getStyle());
            assertEquals(fontMap.get("sans-serif"), Typeface.SANS_SERIF);
            assertEquals(fontMap.get("serif"), Typeface.SERIF);
            assertEquals(fontMap.get("monospace"), Typeface.MONOSPACE);

            // Test defaults
            assertEquals(fontMap.get("sans-serif"), Typeface.defaultFromStyle(Typeface.NORMAL));
            for (int style : STYLES) {
                String msg = "style = " + style;
                assertNotNull(msg, Typeface.defaultFromStyle(style));
                assertEquals(msg, style, Typeface.defaultFromStyle(style).getStyle());
            }

            // Test create()
            assertEquals(fontMap.get("sample"), Typeface.create("sample", Typeface.NORMAL));
            assertEquals(
                    fontMap.get("sample-italic"),
                    Typeface.create("sample-italic", Typeface.ITALIC));
        } finally {
            // This tests breaks many default font configuration and break the assumption of the
            // subsequent test cases. To recover the original configuration, call the
            // setSystemFontMap function with the original data even if it is a test target.
            // Ideally, this test should be isolated and app should be restart after this test
            // been executed.
            Typeface.setSystemFontMap(backup);
        }
    }

    private static float measureText(Typeface typeface, String text) {
        Paint paint = new Paint();
        paint.setTypeface(typeface);
        return paint.measureText(text);
    }
}
