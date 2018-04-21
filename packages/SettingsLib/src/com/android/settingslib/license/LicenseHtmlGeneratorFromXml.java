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

import androidx.annotation.VisibleForTesting;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * The utility class that generate a license html file from xml files.
 * All the HTML snippets and logic are copied from build/make/tools/generate-notice-files.py.
 *
 * TODO: Remove duplicate codes once backward support ends.
 */
class LicenseHtmlGeneratorFromXml {
    private static final String TAG = "LicenseHtmlGeneratorFromXml";

    private static final String TAG_ROOT = "licenses";
    private static final String TAG_FILE_NAME = "file-name";
    private static final String TAG_FILE_CONTENT = "file-content";
    private static final String ATTR_CONTENT_ID = "contentId";

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
            + "<ul>";

    private static final String HTML_MIDDLE_STRING =
            "</ul>\n"
            + "</div><!-- table of contents -->\n"
            + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\">";

    private static final String HTML_REAR_STRING =
            "</table></body></html>";

    private final List<File> mXmlFiles;

    /*
     * A map from a file name to a content id (MD5 sum of file content) for its license.
     * For example, "/system/priv-app/TeleService/TeleService.apk" maps to
     * "9645f39e9db895a4aa6e02cb57294595". Here "9645f39e9db895a4aa6e02cb57294595" is a MD5 sum
     * of the content of packages/services/Telephony/MODULE_LICENSE_APACHE2.
     */
    private final Map<String, String> mFileNameToContentIdMap = new HashMap();

    /*
     * A map from a content id (MD5 sum of file content) to a license file content.
     * For example, "9645f39e9db895a4aa6e02cb57294595" maps to the content string of
     * packages/services/Telephony/MODULE_LICENSE_APACHE2. Here "9645f39e9db895a4aa6e02cb57294595"
     * is a MD5 sum of the file content.
     */
    private final Map<String, String> mContentIdToFileContentMap = new HashMap();

    static class ContentIdAndFileNames {
        final String mContentId;
        final List<String> mFileNameList = new ArrayList();

        ContentIdAndFileNames(String contentId) {
            mContentId = contentId;
        }
    }

    private LicenseHtmlGeneratorFromXml(List<File> xmlFiles) {
        mXmlFiles = xmlFiles;
    }

    public static boolean generateHtml(List<File> xmlFiles, File outputFile) {
        LicenseHtmlGeneratorFromXml genertor = new LicenseHtmlGeneratorFromXml(xmlFiles);
        return genertor.generateHtml(outputFile);
    }

    private boolean generateHtml(File outputFile) {
        for (File xmlFile : mXmlFiles) {
            parse(xmlFile);
        }

        if (mFileNameToContentIdMap.isEmpty() || mContentIdToFileContentMap.isEmpty()) {
            return false;
        }

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(outputFile);

            generateHtml(mFileNameToContentIdMap, mContentIdToFileContentMap, writer);

            writer.flush();
            writer.close();
            return true;
        } catch (FileNotFoundException | SecurityException e) {
            Log.e(TAG, "Failed to generate " + outputFile, e);

            if (writer != null) {
                writer.close();
            }
            return false;
        }
    }

    private void parse(File xmlFile) {
        if (xmlFile == null || !xmlFile.exists() || xmlFile.length() == 0) {
            return;
        }

        InputStreamReader in = null;
        try {
            if (xmlFile.getName().endsWith(".gz")) {
                in = new InputStreamReader(new GZIPInputStream(new FileInputStream(xmlFile)));
            } else {
                in = new FileReader(xmlFile);
            }

            parse(in, mFileNameToContentIdMap, mContentIdToFileContentMap);

            in.close();
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to parse " + xmlFile, e);
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ie) {
                    Log.w(TAG, "Failed to close " + xmlFile);
                }
            }
        }
    }

    /*
     * Parses an input stream and fills a map from a file name to a content id for its license
     * and a map from a content id to a license file content.
     *
     * Following xml format is expected from the input stream.
     *
     *     <licenses>
     *     <file-name contentId="content_id_of_license1">file1</file-name>
     *     <file-name contentId="content_id_of_license2">file2</file-name>
     *     ...
     *     <file-content contentId="content_id_of_license1">license1 file contents</file-content>
     *     <file-content contentId="content_id_of_license2">license2 file contents</file-content>
     *     ...
     *     </licenses>
     */
    @VisibleForTesting
    static void parse(InputStreamReader in, Map<String, String> outFileNameToContentIdMap,
            Map<String, String> outContentIdToFileContentMap)
                    throws XmlPullParserException, IOException {
        Map<String, String> fileNameToContentIdMap = new HashMap<String, String>();
        Map<String, String> contentIdToFileContentMap = new HashMap<String, String>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(in);
        parser.nextTag();

        parser.require(XmlPullParser.START_TAG, "", TAG_ROOT);

        int state = parser.getEventType();
        while (state != XmlPullParser.END_DOCUMENT) {
            if (state == XmlPullParser.START_TAG) {
                if (TAG_FILE_NAME.equals(parser.getName())) {
                    String contentId = parser.getAttributeValue("", ATTR_CONTENT_ID);
                    if (!TextUtils.isEmpty(contentId)) {
                        String fileName = readText(parser).trim();
                        if (!TextUtils.isEmpty(fileName)) {
                            fileNameToContentIdMap.put(fileName, contentId);
                        }
                    }
                } else if (TAG_FILE_CONTENT.equals(parser.getName())) {
                    String contentId = parser.getAttributeValue("", ATTR_CONTENT_ID);
                    if (!TextUtils.isEmpty(contentId)
                            && !outContentIdToFileContentMap.containsKey(contentId)
                            && !contentIdToFileContentMap.containsKey(contentId)) {
                        String fileContent = readText(parser);
                        if (!TextUtils.isEmpty(fileContent)) {
                            contentIdToFileContentMap.put(contentId, fileContent);
                        }
                    }
                }
            }

            state = parser.next();
        }
        outFileNameToContentIdMap.putAll(fileNameToContentIdMap);
        outContentIdToFileContentMap.putAll(contentIdToFileContentMap);
    }

    private static String readText(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        StringBuffer result = new StringBuffer();
        int state = parser.next();
        while (state == XmlPullParser.TEXT) {
            result.append(parser.getText());
            state = parser.next();
        }
        return result.toString();
    }

    @VisibleForTesting
    static void generateHtml(Map<String, String> fileNameToContentIdMap,
            Map<String, String> contentIdToFileContentMap, PrintWriter writer) {
        List<String> fileNameList = new ArrayList();
        fileNameList.addAll(fileNameToContentIdMap.keySet());
        Collections.sort(fileNameList);

        writer.println(HTML_HEAD_STRING);

        int count = 0;
        Map<String, Integer> contentIdToOrderMap = new HashMap();
        List<ContentIdAndFileNames> contentIdAndFileNamesList = new ArrayList();

        // Prints all the file list with a link to its license file content.
        for (String fileName : fileNameList) {
            String contentId = fileNameToContentIdMap.get(fileName);
            // Assigns an id to a newly referred license file content.
            if (!contentIdToOrderMap.containsKey(contentId)) {
                contentIdToOrderMap.put(contentId, count);

                // An index in contentIdAndFileNamesList is the order of each element.
                contentIdAndFileNamesList.add(new ContentIdAndFileNames(contentId));
                count++;
            }

            int id = contentIdToOrderMap.get(contentId);
            contentIdAndFileNamesList.get(id).mFileNameList.add(fileName);
            writer.format("<li><a href=\"#id%d\">%s</a></li>\n", id, fileName);
        }

        writer.println(HTML_MIDDLE_STRING);

        count = 0;
        // Prints all contents of the license files in order of id.
        for (ContentIdAndFileNames contentIdAndFileNames : contentIdAndFileNamesList) {
            writer.format("<tr id=\"id%d\"><td class=\"same-license\">\n", count);
            writer.println("<div class=\"label\">Notices for file(s):</div>");
            writer.println("<div class=\"file-list\">");
            for (String fileName : contentIdAndFileNames.mFileNameList) {
                writer.format("%s <br/>\n", fileName);
            }
            writer.println("</div><!-- file-list -->");
            writer.println("<pre class=\"license-text\">");
            writer.println(contentIdToFileContentMap.get(
                    contentIdAndFileNames.mContentId));
            writer.println("</pre><!-- license-text -->");
            writer.println("</td></tr><!-- same-license -->");

            count++;
        }

        writer.println(HTML_REAR_STRING);
    }
}
