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

package com.android.settingslib.license;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class LicenseHtmlGeneratorFromXmlTest {
    private static final String VALILD_XML_STRING =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<licenses>\n"
            + "<file-name contentId=\"0\">/file0</file-name>\n"
            + "<file-name contentId=\"0\">/file1</file-name>\n"
            + "<file-content contentId=\"0\"><![CDATA[license content #0]]></file-content>\n"
            + "</licenses>";

    private static final String INVALILD_XML_STRING =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<licenses2>\n"
            + "<file-name contentId=\"0\">/file0</file-name>\n"
            + "<file-name contentId=\"0\">/file1</file-name>\n"
            + "<file-content contentId=\"0\"><![CDATA[license content #0]]></file-content>\n"
            + "</licenses2>";

    private static final String HTML_HEAD_STRING =
            "<html><head>\n"
            + "<style type=\"text/css\">\n"
            + "body { padding: 0; font-family: sans-serif; }\n"
            + ".same-license { background-color: #eeeeee;\n"
            + "                border-top: 20px solid white;\n"
            + "                padding: 10px; }\n"
            + ".label { font-weight: bold; }\n"
            + ".file-list { margin-left: 1em; color: blue; }\n"
            + "</style>\n"
            + "</head>"
            + "<body topmargin=\"0\" leftmargin=\"0\" rightmargin=\"0\" bottommargin=\"0\">\n"
            + "<div class=\"toc\">\n"
            + "<ul>\n";

    private static final String HTML_CUSTOM_HEADING = "Custom heading";

    private static final String HTML_BODY_STRING =
            "<li><a href=\"#id0\">/file0</a></li>\n"
            + "<li><a href=\"#id0\">/file1</a></li>\n"
            + "</ul>\n"
            + "</div><!-- table of contents -->\n"
            + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\">\n"
            + "<tr id=\"id0\"><td class=\"same-license\">\n"
            + "<div class=\"label\">Notices for file(s):</div>\n"
            + "<div class=\"file-list\">\n"
            + "/file0 <br/>\n"
            + "/file1 <br/>\n"
            + "</div><!-- file-list -->\n"
            + "<pre class=\"license-text\">\n"
            + "license content #0\n"
            + "</pre><!-- license-text -->\n"
            + "</td></tr><!-- same-license -->\n"
            + "</table></body></html>\n";

    private static final String EXPECTED_HTML_STRING = HTML_HEAD_STRING + HTML_BODY_STRING;

    private static final String EXPECTED_HTML_STRING_WITH_CUSTOM_HEADING =
            HTML_HEAD_STRING + HTML_CUSTOM_HEADING + "\n" + HTML_BODY_STRING;

    @Test
    public void testParseValidXmlStream() throws XmlPullParserException, IOException {
        Map<String, String> fileNameToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        LicenseHtmlGeneratorFromXml.parse(
                new InputStreamReader(new ByteArrayInputStream(VALILD_XML_STRING.getBytes())),
                fileNameToContentIdMap, contentIdToFileContentMap);
        assertThat(fileNameToContentIdMap.size()).isEqualTo(2);
        assertThat(fileNameToContentIdMap.get("/file0")).isEqualTo("0");
        assertThat(fileNameToContentIdMap.get("/file1")).isEqualTo("0");
        assertThat(contentIdToFileContentMap.size()).isEqualTo(1);
        assertThat(contentIdToFileContentMap.get("0")).isEqualTo("license content #0");
    }

    @Test(expected = XmlPullParserException.class)
    public void testParseInvalidXmlStream() throws XmlPullParserException, IOException {
        Map<String, String> fileNameToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        LicenseHtmlGeneratorFromXml.parse(
                new InputStreamReader(new ByteArrayInputStream(INVALILD_XML_STRING.getBytes())),
                fileNameToContentIdMap, contentIdToFileContentMap);
    }

    @Test
    public void testGenerateHtml() {
        Map<String, String> fileNameToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        fileNameToContentIdMap.put("/file0", "0");
        fileNameToContentIdMap.put("/file1", "0");
        contentIdToFileContentMap.put("0", "license content #0");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                fileNameToContentIdMap, contentIdToFileContentMap, new PrintWriter(output), "");
        assertThat(output.toString()).isEqualTo(EXPECTED_HTML_STRING);
    }

    @Test
    public void testGenerateHtmlWithCustomHeading() {
        Map<String, String> fileNameToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        fileNameToContentIdMap.put("/file0", "0");
        fileNameToContentIdMap.put("/file1", "0");
        contentIdToFileContentMap.put("0", "license content #0");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                fileNameToContentIdMap, contentIdToFileContentMap, new PrintWriter(output),
                HTML_CUSTOM_HEADING);
        assertThat(output.toString()).isEqualTo(EXPECTED_HTML_STRING_WITH_CUSTOM_HEADING);
    }
}
