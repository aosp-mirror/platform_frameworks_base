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
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
public class LicenseHtmlGeneratorFromXmlTest {
    private static final String VALID_OLD_XML_STRING =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<licenses>\n"
            + "<file-name contentId=\"0\">/file0</file-name>\n"
            + "<file-name contentId=\"0\">/file1</file-name>\n"
            + "<file-content contentId=\"0\"><![CDATA[license content #0]]></file-content>\n"
            + "</licenses>";

    private static final String VALID_NEW_XML_STRING =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<licenses>\n"
            + "<file-name contentId=\"0\" lib=\"libA\">/file0</file-name>\n"
            + "<file-name contentId=\"0\" lib=\"libB\">/file1</file-name>\n"
            + "<file-content contentId=\"0\"><![CDATA[license content #0]]></file-content>\n"
            + "</licenses>";

    private static final String INVALID_XML_STRING =
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
            + "<div class=\"toc\">\n";

    private static final String HTML_CUSTOM_HEADING = "Custom heading";

    private static final String HTML_OLD_BODY_STRING =
            "<ul class=\"files\">\n"
            + "<li><a href=\"#id0\">/file0</a></li>\n"
            + "<li><a href=\"#id1\">/file0</a></li>\n"
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
            + "<tr id=\"id1\"><td class=\"same-license\">\n"
            + "<div class=\"label\">Notices for file(s):</div>\n"
            + "<div class=\"file-list\">\n"
            + "/file0 <br/>\n"
            + "</div><!-- file-list -->\n"
            + "<pre class=\"license-text\">\n"
            + "license content #1\n"
            + "</pre><!-- license-text -->\n"
            + "</td></tr><!-- same-license -->\n"
            + "</table></body></html>\n";

    private static final String HTML_NEW_BODY_STRING =
            "<strong>Libraries</strong>\n"
            + "<ul class=\"libraries\">\n"
            + "<li><a href=\"#id0\">libA</a></li>\n"
            + "<li><a href=\"#id1\">libB</a></li>\n"
            + "</ul>\n"
            + "<strong>Files</strong>\n"
            + "<ul class=\"files\">\n"
            + "<li><a href=\"#id0\">/file0 - libA</a></li>\n"
            + "<li><a href=\"#id1\">/file0 - libB</a></li>\n"
            + "<li><a href=\"#id0\">/file1 - libA</a></li>\n"
            + "<li><a href=\"#id0\">/file2 - libC</a></li>\n"
            + "</ul>\n"
            + "</div><!-- table of contents -->\n"
            + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\">\n"
            + "<tr id=\"id0\"><td class=\"same-license\">\n"
            + "<div class=\"label\"><strong>libA</strong> used by:</div>\n"
            + "<div class=\"file-list\">\n"
            + "/file0 <br/>\n"
            + "/file1 <br/>\n"
            + "</div><!-- file-list -->\n"
            + "<div class=\"label\"><strong>libC</strong> used by:</div>\n"
            + "<div class=\"file-list\">\n"
            + "/file2 <br/>\n"
            + "</div><!-- file-list -->\n"
            + "<pre class=\"license-text\">\n"
            + "license content #0\n"
            + "</pre><!-- license-text -->\n"
            + "</td></tr><!-- same-license -->\n"
            + "<tr id=\"id1\"><td class=\"same-license\">\n"
            + "<div class=\"label\"><strong>libB</strong> used by:</div>\n"
            + "<div class=\"file-list\">\n"
            + "/file0 <br/>\n"
            + "</div><!-- file-list -->\n"
            + "<pre class=\"license-text\">\n"
            + "license content #1\n"
            + "</pre><!-- license-text -->\n"
            + "</td></tr><!-- same-license -->\n"
            + "</table></body></html>\n";

    private static final String EXPECTED_OLD_HTML_STRING = HTML_HEAD_STRING + HTML_OLD_BODY_STRING;

    private static final String EXPECTED_NEW_HTML_STRING = HTML_HEAD_STRING + HTML_NEW_BODY_STRING;

    private static final String EXPECTED_OLD_HTML_STRING_WITH_CUSTOM_HEADING =
            HTML_HEAD_STRING + HTML_CUSTOM_HEADING + "\n" + HTML_OLD_BODY_STRING;

    private static final String EXPECTED_NEW_HTML_STRING_WITH_CUSTOM_HEADING =
            HTML_HEAD_STRING + HTML_CUSTOM_HEADING + "\n" + HTML_NEW_BODY_STRING;

    @Test
    public void testParseValidXmlStream() throws XmlPullParserException, IOException {
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        LicenseHtmlGeneratorFromXml.parse(
                new InputStreamReader(new ByteArrayInputStream(VALID_OLD_XML_STRING.getBytes())),
                fileNameToLibraryToContentIdMap, contentIdToFileContentMap);

        assertThat(fileNameToLibraryToContentIdMap).hasSize(2);
        assertThat(fileNameToLibraryToContentIdMap.get("/file0")).hasSize(1);
        assertThat(fileNameToLibraryToContentIdMap.get("/file1")).hasSize(1);
        assertThat(fileNameToLibraryToContentIdMap.get("/file0").get(null)).containsExactly("0");
        assertThat(fileNameToLibraryToContentIdMap.get("/file1").get(null)).containsExactly("0");
        assertThat(contentIdToFileContentMap.size()).isEqualTo(1);
        assertThat(contentIdToFileContentMap.get("0")).isEqualTo("license content #0");
    }

    @Test
    public void testParseNewValidXmlStream() throws XmlPullParserException, IOException {
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        LicenseHtmlGeneratorFromXml.parse(
                new InputStreamReader(new ByteArrayInputStream(VALID_NEW_XML_STRING.getBytes())),
                fileNameToLibraryToContentIdMap, contentIdToFileContentMap);

        assertThat(fileNameToLibraryToContentIdMap).hasSize(2);
        assertThat(fileNameToLibraryToContentIdMap.get("/file0")).hasSize(1);
        assertThat(fileNameToLibraryToContentIdMap.get("/file1")).hasSize(1);
        assertThat(fileNameToLibraryToContentIdMap.get("/file0").get("libA")).containsExactly("0");
        assertThat(fileNameToLibraryToContentIdMap.get("/file1").get("libB")).containsExactly("0");
        assertThat(contentIdToFileContentMap.size()).isEqualTo(1);
        assertThat(contentIdToFileContentMap.get("0")).isEqualTo("license content #0");
    }

    @Test(expected = XmlPullParserException.class)
    public void testParseInvalidXmlStream() throws XmlPullParserException, IOException {
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();

        LicenseHtmlGeneratorFromXml.parse(
                new InputStreamReader(new ByteArrayInputStream(INVALID_XML_STRING.getBytes())),
                fileNameToLibraryToContentIdMap, contentIdToFileContentMap);
    }

    @Test
    public void testGenerateHtml() throws Exception {
        List<File> xmlFiles = new ArrayList<>();
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();
        Map<String, Set<String>> toBoth = new HashMap<>();
        Map<String, Set<String>> toOne = new HashMap<>();

        toBoth.put("", new HashSet<String>(Arrays.asList("0", "1")));
        toOne.put("", new HashSet<String>(Arrays.asList("0")));

        fileNameToLibraryToContentIdMap.put("/file0", toBoth);
        fileNameToLibraryToContentIdMap.put("/file1", toOne);
        contentIdToFileContentMap.put("0", "license content #0");
        contentIdToFileContentMap.put("1", "license content #1");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                xmlFiles, fileNameToLibraryToContentIdMap, contentIdToFileContentMap,
                new PrintWriter(output), "");
        assertThat(output.toString()).isEqualTo(EXPECTED_OLD_HTML_STRING);
    }

    @Test
    public void testGenerateNewHtml() throws Exception {
        List<File> xmlFiles = new ArrayList<>();
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();
        Map<String, Set<String>> toBoth = new HashMap<>();
        Map<String, Set<String>> toOne = new HashMap<>();
        Map<String, Set<String>> toOther = new HashMap<>();

        toBoth.put("libA", new HashSet<String>(Arrays.asList("0")));
        toBoth.put("libB", new HashSet<String>(Arrays.asList("1")));
        toOne.put("libA", new HashSet<String>(Arrays.asList("0")));
        toOther.put("libC", new HashSet<String>(Arrays.asList("0")));

        fileNameToLibraryToContentIdMap.put("/file0", toBoth);
        fileNameToLibraryToContentIdMap.put("/file1", toOne);
        fileNameToLibraryToContentIdMap.put("/file2", toOther);
        contentIdToFileContentMap.put("0", "license content #0");
        contentIdToFileContentMap.put("1", "license content #1");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                xmlFiles, fileNameToLibraryToContentIdMap, contentIdToFileContentMap,
                new PrintWriter(output), "");
        assertThat(output.toString()).isEqualTo(EXPECTED_NEW_HTML_STRING);
    }

    @Test
    public void testGenerateHtmlWithCustomHeading() throws Exception {
        List<File> xmlFiles = new ArrayList<>();
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();
        Map<String, Set<String>> toBoth = new HashMap<>();
        Map<String, Set<String>> toOne = new HashMap<>();

        toBoth.put("", new HashSet<String>(Arrays.asList("0", "1")));
        toOne.put("", new HashSet<String>(Arrays.asList("0", "1")));

        fileNameToLibraryToContentIdMap.put("/file0", toBoth);
        fileNameToLibraryToContentIdMap.put("/file1", toOne);
        contentIdToFileContentMap.put("0", "license content #0");
        contentIdToFileContentMap.put("1", "license content #1");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                xmlFiles, fileNameToLibraryToContentIdMap, contentIdToFileContentMap,
                new PrintWriter(output), HTML_CUSTOM_HEADING);
        assertThat(output.toString()).isEqualTo(EXPECTED_OLD_HTML_STRING_WITH_CUSTOM_HEADING);
    }

    @Test
    public void testGenerateNewHtmlWithCustomHeading() throws Exception {
        List<File> xmlFiles = new ArrayList<>();
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap = new HashMap<>();
        Map<String, String> contentIdToFileContentMap = new HashMap<>();
        Map<String, Set<String>> toBoth = new HashMap<>();
        Map<String, Set<String>> toOne = new HashMap<>();
        Map<String, Set<String>> toOther = new HashMap<>();

        toBoth.put("libA", new HashSet<String>(Arrays.asList("0")));
        toBoth.put("libB", new HashSet<String>(Arrays.asList("1")));
        toOne.put("libA", new HashSet<String>(Arrays.asList("0")));
        toOther.put("libC", new HashSet<String>(Arrays.asList("0")));

        fileNameToLibraryToContentIdMap.put("/file0", toBoth);
        fileNameToLibraryToContentIdMap.put("/file1", toOne);
        fileNameToLibraryToContentIdMap.put("/file2", toOther);
        contentIdToFileContentMap.put("0", "license content #0");
        contentIdToFileContentMap.put("1", "license content #1");

        StringWriter output = new StringWriter();
        LicenseHtmlGeneratorFromXml.generateHtml(
                xmlFiles, fileNameToLibraryToContentIdMap, contentIdToFileContentMap,
                new PrintWriter(output), HTML_CUSTOM_HEADING);
        assertThat(output.toString()).isEqualTo(EXPECTED_NEW_HTML_STRING_WITH_CUSTOM_HEADING);
    }
}
