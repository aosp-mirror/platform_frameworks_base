/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC;
import static android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT;
import static android.graphics.fonts.FontStyle.FONT_WEIGHT_NORMAL;
import static android.text.FontConfig.FontFamily.VARIANT_COMPACT;
import static android.text.FontConfig.FontFamily.VARIANT_DEFAULT;
import static android.text.FontConfig.FontFamily.VARIANT_ELEGANT;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.fail;

import android.graphics.fonts.FontCustomizationParser;
import android.graphics.fonts.FontStyle;
import android.os.LocaleList;
import android.text.FontConfig;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class FontListParserTest {

    @Test
    public void named() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family name='sans-serif'>"
                + "  <font>test.ttf</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null)),
                "sans-serif", LocaleList.getEmptyLocaleList(), VARIANT_DEFAULT);

        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void fallback() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family lang='en'>"
                + "  <font>test.ttf</font>"
                + "  <font fallbackFor='serif'>test.ttf</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null),
                        new FontConfig.Font(new File("test.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", "serif")),
                null, LocaleList.forLanguageTags("en"), VARIANT_DEFAULT);

        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void compact() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family lang='en' variant='compact'>"
                + "  <font>test.ttf</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null)),
                null, LocaleList.forLanguageTags("en"), VARIANT_COMPACT);

        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void elegant() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family lang='en' variant='elegant'>"
                + "  <font>test.ttf</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null)),
                null, LocaleList.forLanguageTags("en"), VARIANT_ELEGANT);

        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void styles() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family name='sans-serif'>"
                + "  <font style='normal'>normal.ttf</font>"
                + "  <font weight='100'>weight.ttf</font>"
                + "  <font style='italic'>italic.ttf</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("normal.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null),
                        new FontConfig.Font(new File("weight.ttf"), null, "test",
                                new FontStyle(100, FONT_SLANT_UPRIGHT),
                                0, "", null),
                        new FontConfig.Font(new File("italic.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_ITALIC),
                                0, "", null)),
                "sans-serif", LocaleList.getEmptyLocaleList(), VARIANT_DEFAULT);
        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void variable() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family name='sans-serif'>"
                + "  <font>test-VF.ttf"
                + "    <axis tag='wdth' stylevalue='100' />"
                + "    <axis tag='wght' stylevalue='200' />"
                + "  </font>"
                + "  <font>test-VF.ttf"
                + "    <axis tag='wdth' stylevalue='400' />"
                + "    <axis tag='wght' stylevalue='700' />"
                + "  </font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test-VF.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "'wdth' 100.0,'wght' 200.0", null),
                        new FontConfig.Font(new File("test-VF.ttf"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "'wdth' 400.0,'wght' 700.0", null)),
                "sans-serif", LocaleList.getEmptyLocaleList(), VARIANT_DEFAULT);
        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void ttc() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family name='sans-serif'>"
                + "  <font index='0'>test.ttc</font>"
                + "  <font index='1'>test.ttc</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttc"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null),
                        new FontConfig.Font(new File("test.ttc"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                1, "", null)),
                "sans-serif", LocaleList.getEmptyLocaleList(), VARIANT_DEFAULT);
        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void psName() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<family name='sans-serif'>"
                + "  <font index='0' postScriptName='foo'>test.ttc</font>"
                + "  <font index='1'>test.ttc</font>"
                + "</family>";
        FontConfig.FontFamily expected = new FontConfig.FontFamily(
                Arrays.asList(
                        new FontConfig.Font(new File("test.ttc"), null, "foo",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                0, "", null),
                        new FontConfig.Font(new File("test.ttc"), null, "test",
                                new FontStyle(FONT_WEIGHT_NORMAL, FONT_SLANT_UPRIGHT),
                                1, "", null)),
                "sans-serif", LocaleList.getEmptyLocaleList(), VARIANT_DEFAULT);
        FontConfig.FontFamily family = readFamily(xml);
        assertThat(family).isEqualTo(expected);
    }

    @Test
    public void invalidXml_unpaired_family() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font index='0'>test.ttc</font>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void invalidXml_unpaired_font() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font index='0'>test.ttc"
                + "  </family>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void invalidXml_unpaired_axis() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font index='0'>test.ttc"
                + "        <axis tag=\"wght\" styleValue=\"0\" >"
                + "    </font>"
                + "  </family>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void invalidXml_unclosed_family() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'"
                + "    <font index='0'>test.ttc</font>"
                + "  </family>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void invalidXml_unclosed_font() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font index='0'"
                + "  </family>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void invalidXml_unclosed_axis() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font index='0'>test.ttc"
                + "        <axis tag=\"wght\" styleValue=\"0\""
                + "    </font>"
                + "  </family>"
                + "</familyset>";

        try (InputStream is = new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))) {
            FontListParser.parse(is);
            fail();
        } catch (IOException | XmlPullParserException e) {
            // pass
        }
    }

    @Test
    public void alias() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font>test.ttf</font>"
                + "  </family>"
                + "  <family name='custom-family'>"
                + "    <font>missing.ttf</font>"
                + "  </family>"
                + "  <alias name='custom-alias' to='sans-serif'/>"
                + "</familyset>";
        FontConfig config = readFamilies(xml, true /* include non-existing font files */);
        List<FontConfig.Alias> aliases = config.getAliases();
        assertThat(aliases.size()).isEqualTo(1);
        assertThat(aliases.get(0).getName()).isEqualTo("custom-alias");
        assertThat(aliases.get(0).getOriginal()).isEqualTo("sans-serif");
    }

    @Test
    public void dropped_FamilyAlias() throws Exception {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>"
                + "<familyset>"
                + "  <family name='sans-serif'>"
                + "    <font>test.ttf</font>"
                + "  </family>"
                + "  <family name='custom-family'>"
                + "    <font>missing.ttf</font>"
                + "  </family>"
                + "  <alias name='custom-alias' to='custom-family'/>"
                + "</familyset>";
        FontConfig config = readFamilies(xml, false /* exclude not existing file */);
        assertThat(config.getAliases()).isEmpty();
    }

    private FontConfig readFamilies(String xml, boolean allowNonExisting)
            throws IOException, XmlPullParserException {
        ByteArrayInputStream buffer = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8));
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(buffer, "UTF-8");
        parser.nextTag();
        return FontListParser.readFamilies(parser, "", new FontCustomizationParser.Result(), null,
                0L /* last modified date */, 0 /* config version */, allowNonExisting);
    }

    private FontConfig.FontFamily readFamily(String xml)
            throws IOException, XmlPullParserException {
        ByteArrayInputStream buffer = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8));
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(buffer, "UTF-8");
        parser.nextTag();
        return FontListParser.readFamily(parser, "", null, true);
    }
}
