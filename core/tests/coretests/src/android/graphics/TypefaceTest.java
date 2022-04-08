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
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Resources;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Test;
import org.junit.runner.RunWith;

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

}
