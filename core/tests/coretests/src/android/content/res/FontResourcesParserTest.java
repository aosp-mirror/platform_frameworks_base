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

import static android.content.res.FontResourcesParser.FamilyResourceEntry;
import static android.content.res.FontResourcesParser.FontFamilyFilesResourceEntry;
import static android.content.res.FontResourcesParser.FontFileResourceEntry;
import static android.content.res.FontResourcesParser.ProviderResourceEntry;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.app.Instrumentation;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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
        XmlResourceParser parser = mResources.getXml(R.font.samplexmlfontforparsing);

        FamilyResourceEntry result = FontResourcesParser.parse(parser, mResources);

        assertNotNull(result);
        FontFamilyFilesResourceEntry filesEntry = (FontFamilyFilesResourceEntry) result;
        FontFileResourceEntry[] fileEntries = filesEntry.getEntries();
        assertEquals(4, fileEntries.length);
        FontFileResourceEntry font1 = fileEntries[0];
        assertEquals(400, font1.getWeight());
        assertEquals(0, font1.getItalic());
        assertEquals("'wdth' 0.8", font1.getVariationSettings());
        assertEquals(0, font1.getTtcIndex());
        assertEquals("res/font/samplefont.ttf", font1.getFileName());
        FontFileResourceEntry font2 = fileEntries[1];
        assertEquals(400, font2.getWeight());
        assertEquals(1, font2.getItalic());
        assertEquals(1, font2.getTtcIndex());
        assertEquals("'cntr' 0.5", font2.getVariationSettings());
        assertEquals("res/font/samplefont2.ttf", font2.getFileName());
        FontFileResourceEntry font3 = fileEntries[2];
        assertEquals(800, font3.getWeight());
        assertEquals(0, font3.getItalic());
        assertEquals(2, font3.getTtcIndex());
        assertEquals("'wdth' 500.0, 'wght' 300.0", font3.getVariationSettings());
        assertEquals("res/font/samplefont3.ttf", font3.getFileName());
        FontFileResourceEntry font4 = fileEntries[3];
        assertEquals(800, font4.getWeight());
        assertEquals(1, font4.getItalic());
        assertEquals(0, font4.getTtcIndex());
        assertEquals(null, font4.getVariationSettings());
        assertEquals("res/font/samplefont4.ttf", font4.getFileName());
    }

    @Test
    public void testParseDownloadableFont() throws IOException, XmlPullParserException {
        XmlResourceParser parser = mResources.getXml(R.font.samplexmldownloadedfont);

        FamilyResourceEntry result = FontResourcesParser.parse(parser, mResources);

        assertNotNull(result);
        ProviderResourceEntry providerEntry = (ProviderResourceEntry) result;
        assertEquals("com.example.test.fontprovider.authority", providerEntry.getAuthority());
        assertEquals("com.example.test.fontprovider.package", providerEntry.getPackage());
        assertEquals("MyRequestedFont", providerEntry.getQuery());
    }

    @Test
    public void testParseDownloadableFont_singleCerts() throws IOException, XmlPullParserException {
        XmlResourceParser parser = mResources.getXml(R.font.samplexmldownloadedfontsinglecerts);

        FamilyResourceEntry result = FontResourcesParser.parse(parser, mResources);

        assertNotNull(result);
        assertTrue(result instanceof ProviderResourceEntry);
        ProviderResourceEntry providerResourceEntry = (ProviderResourceEntry) result;
        assertEquals("com.example.test.fontprovider", providerResourceEntry.getAuthority());
        assertEquals("MyRequestedFont", providerResourceEntry.getQuery());
        assertEquals("com.example.test.fontprovider.package", providerResourceEntry.getPackage());
        List<List<String>> certList = providerResourceEntry.getCerts();
        assertNotNull(certList);
        assertEquals(1, certList.size());
        List<String> certs = certList.get(0);
        assertEquals(2, certs.size());
        assertEquals("123456789", certs.get(0));
        assertEquals("987654321", certs.get(1));
    }

    @Test
    public void testParseDownloadableFont_multipleCerts() throws IOException, XmlPullParserException {
        XmlResourceParser parser = mResources.getXml(R.font.samplexmldownloadedfontmulticerts);

        FamilyResourceEntry result = FontResourcesParser.parse(parser, mResources);

        assertNotNull(result);
        assertTrue(result instanceof ProviderResourceEntry);
        ProviderResourceEntry providerResourceEntry = (ProviderResourceEntry) result;
        assertEquals("com.example.test.fontprovider", providerResourceEntry.getAuthority());
        assertEquals("MyRequestedFont", providerResourceEntry.getQuery());
        assertEquals("com.example.test.fontprovider.package", providerResourceEntry.getPackage());
        List<List<String>> certList = providerResourceEntry.getCerts();
        assertNotNull(certList);
        assertEquals(2, certList.size());
        List<String> certs1 = certList.get(0);
        assertEquals(2, certs1.size());
        assertEquals("123456789", certs1.get(0));
        assertEquals("987654321", certs1.get(1));
        List<String> certs2 = certList.get(1);
        assertEquals(2, certs2.size());
        assertEquals("abcdefg", certs2.get(0));
        assertEquals("gfedcba", certs2.get(1));
    }
}
