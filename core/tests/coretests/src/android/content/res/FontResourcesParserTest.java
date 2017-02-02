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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Instrumentation;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.text.FontConfig;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;

/**
 * Tests for {@link FontResourcesParser}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class FontResourcesParserTest {

    private Instrumentation mInstrumentation;
    private Resources mResources;

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mResources = mInstrumentation.getContext().getResources();
    }

    @Test
    public void testParse() throws XmlPullParserException, IOException {
        XmlResourceParser parser = mResources.getXml(R.font.samplexmlfont);

        FontConfig result = FontResourcesParser.parse(parser, mResources);

        assertNotNull(result);
        List<FontConfig.Family> families = result.getFamilies();
        assertEquals(1, families.size());
        List<FontConfig.Font> fonts = families.get(0).getFonts();
        assertEquals(4, fonts.size());
        FontConfig.Font font1 = fonts.get(0);
        assertEquals(400, font1.getWeight());
        assertEquals(false, font1.isItalic());
        assertEquals("res/font/samplefont.ttf", font1.getFontName());
        FontConfig.Font font2 = fonts.get(1);
        assertEquals(400, font2.getWeight());
        assertEquals(true, font2.isItalic());
        assertEquals("res/font/samplefont2.ttf", font2.getFontName());
        FontConfig.Font font3 = fonts.get(2);
        assertEquals(800, font3.getWeight());
        assertEquals(false, font3.isItalic());
        assertEquals("res/font/samplefont3.ttf", font3.getFontName());
        FontConfig.Font font4 = fonts.get(3);
        assertEquals(800, font4.getWeight());
        assertEquals(true, font4.isItalic());
        assertEquals("res/font/samplefont4.ttf", font4.getFontName());
    }
}
