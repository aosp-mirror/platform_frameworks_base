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

import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import androidx.annotation.VisibleForTesting;

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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The utility class that generate a license html file from xml files.
 * All the HTML snippets and logic are copied from build/make/tools/generate-notice-files.py.
 *
 * TODO: Remove duplicate codes once backward support ends.
 */
class LicenseHtmlGeneratorFromXml {
    private static final String TAG = "LicenseGeneratorFromXml";

    private static final String TAG_ROOT = "licenses";
    private static final String TAG_FILE_NAME = "file-name";
    private static final String TAG_FILE_CONTENT = "file-content";
    private static final String ATTR_CONTENT_ID = "contentId";
    private static final String ATTR_LIBRARY_NAME = "lib";

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
            + "<div class=\"toc\">";
    private static final String LIBRARY_HEAD_STRING =
            "<strong>Libraries</strong>\n<ul class=\"libraries\">";
    private static final String LIBRARY_TAIL_STRING = "</ul>\n<strong>Files</strong>";

    private static final String FILES_HEAD_STRING = "<ul class=\"files\">";

    private static final String HTML_MIDDLE_STRING =
            "</ul>\n"
            + "</div><!-- table of contents -->\n"
            + "<table cellpadding=\"0\" cellspacing=\"0\" border=\"0\">";

    private static final String HTML_REAR_STRING =
            "</table></body></html>";

    private final List<File> mXmlFiles;

    /*
     * A map from a file name to a library name (may be empty) to a content id (MD5 sum of file
     * content) for its license.
     * For example, "/system/priv-app/TeleService/TeleService.apk" maps to "service/Telephony" to
     * "9645f39e9db895a4aa6e02cb57294595". Here "9645f39e9db895a4aa6e02cb57294595" is a MD5 sum
     * of the content of packages/services/Telephony/MODULE_LICENSE_APACHE2.
     */
    private final Map<String, Map<String, Set<String>>> mFileNameToLibraryToContentIdMap =
            new HashMap();

    /*
     * A map from a content id (MD5 sum of file content) to a license file content.
     * For example, "9645f39e9db895a4aa6e02cb57294595" maps to the content string of
     * packages/services/Telephony/MODULE_LICENSE_APACHE2. Here "9645f39e9db895a4aa6e02cb57294595"
     * is a MD5 sum of the file content.
     */
    private final Map<String, String> mContentIdToFileContentMap = new HashMap();

    static class ContentIdAndFileNames {
        final String mContentId;
        final Map<String, List<String>> mLibraryToFileNameMap = new TreeMap();

        ContentIdAndFileNames(String contentId) {
            mContentId = contentId;
        }
    }

    private LicenseHtmlGeneratorFromXml(List<File> xmlFiles) {
        mXmlFiles = xmlFiles;
    }

    public static boolean generateHtml(List<File> xmlFiles, File outputFile,
            String noticeHeader) {
        LicenseHtmlGeneratorFromXml genertor = new LicenseHtmlGeneratorFromXml(xmlFiles);
        return genertor.generateHtml(outputFile, noticeHeader);
    }

    private boolean generateHtml(File outputFile, String noticeHeader) {
        for (File xmlFile : mXmlFiles) {
            parse(xmlFile);
        }

        if (mFileNameToLibraryToContentIdMap.isEmpty() || mContentIdToFileContentMap.isEmpty()) {
            return false;
        }

        PrintWriter writer = null;
        try {
            writer = new PrintWriter(outputFile);

            generateHtml(mFileNameToLibraryToContentIdMap, mContentIdToFileContentMap, writer,
                noticeHeader);

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

            parse(in, mFileNameToLibraryToContentIdMap, mContentIdToFileContentMap);

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
     *     <file-name contentId="content_id_of_license2" lib="name of library">file2</file-name>
     *     <file-name contentId="content_id_of_license2" lib="another library">file2</file-name>
     *     ...
     *     <file-content contentId="content_id_of_license1">license1 file contents</file-content>
     *     <file-content contentId="content_id_of_license2">license2 file contents</file-content>
     *     ...
     *     </licenses>
     */
    @VisibleForTesting
    static void parse(InputStreamReader in,
            Map<String, Map<String, Set<String>>> outFileNameToLibraryToContentIdMap,
            Map<String, String> outContentIdToFileContentMap)
                    throws XmlPullParserException, IOException {
        Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap =
                new HashMap<String, Map<String, Set<String>>>();
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
                    String libraryName = parser.getAttributeValue("", ATTR_LIBRARY_NAME);
                    if (!TextUtils.isEmpty(contentId)) {
                        String fileName = readText(parser).trim();
                        if (!TextUtils.isEmpty(fileName)) {
                            Map<String, Set<String>> libs =
                                    fileNameToLibraryToContentIdMap.computeIfAbsent(
                                            fileName, k -> new HashMap<>());
                            Set<String> contentIds = libs.computeIfAbsent(
                                            libraryName, k -> new HashSet<>());
                            contentIds.add(contentId);
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
        for (Map.Entry<String, Map<String, Set<String>>> mapEntry :
                fileNameToLibraryToContentIdMap.entrySet()) {
            outFileNameToLibraryToContentIdMap.merge(
                    mapEntry.getKey(), mapEntry.getValue(), (m1, m2) -> {
                        for (Map.Entry<String, Set<String>> entry : m2.entrySet()) {
                            m1.merge(entry.getKey(), entry.getValue(), (s1, s2) -> {
                                s1.addAll(s2);
                                return s1;
                            });
                        }
                        return m1;
                    });
        }
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
    static void generateHtml(Map<String, Map<String, Set<String>>> fileNameToLibraryToContentIdMap,
            Map<String, String> contentIdToFileContentMap, PrintWriter writer,
            String noticeHeader) {
        List<String> fileNameList = new ArrayList();
        fileNameList.addAll(fileNameToLibraryToContentIdMap.keySet());
        Collections.sort(fileNameList);

        SortedMap<String, Set<String>> libraryToContentIdMap = new TreeMap();
        for (Map<String, Set<String>> libraryToContentValue :
                fileNameToLibraryToContentIdMap.values()) {
            for (Map.Entry<String, Set<String>> entry : libraryToContentValue.entrySet()) {
                if (TextUtils.isEmpty(entry.getKey())) {
                    continue;
                }
                libraryToContentIdMap.merge(
                        entry.getKey(), entry.getValue(), (s1, s2) -> {
                            s1.addAll(s2);
                            return s1;
                        });
            }
        }

        writer.println(HTML_HEAD_STRING);

        if (!TextUtils.isEmpty(noticeHeader)) {
            writer.println(noticeHeader);
        }

        int count = 0;
        Map<String, Integer> contentIdToOrderMap = new HashMap();
        List<ContentIdAndFileNames> contentIdAndFileNamesList = new ArrayList();

        if (!libraryToContentIdMap.isEmpty()) {
            writer.println(LIBRARY_HEAD_STRING);
            for (Map.Entry<String, Set<String>> entry: libraryToContentIdMap.entrySet()) {
                String libraryName = entry.getKey();
                for (String contentId : entry.getValue()) {
                    // Assigns an id to a newly referred license file content.
                    if (!contentIdToOrderMap.containsKey(contentId)) {
                        contentIdToOrderMap.put(contentId, count);

                        // An index in contentIdAndFileNamesList is the order of each element.
                        contentIdAndFileNamesList.add(new ContentIdAndFileNames(contentId));
                        count++;
                    }
                    int id = contentIdToOrderMap.get(contentId);
                    writer.format("<li><a href=\"#id%d\">%s</a></li>\n", id, libraryName);
                }
            }
            writer.println(LIBRARY_TAIL_STRING);
        }

        writer.println(FILES_HEAD_STRING);

        // Prints all the file list with a link to its license file content.
        for (String fileName : fileNameList) {
            for (Map.Entry<String, Set<String>> libToContentId :
                    fileNameToLibraryToContentIdMap.get(fileName).entrySet()) {
                String libraryName = libToContentId.getKey();
                if (libraryName == null) {
                    libraryName = "";
                }
                for (String contentId : libToContentId.getValue()) {
                    // Assigns an id to a newly referred license file content.
                    if (!contentIdToOrderMap.containsKey(contentId)) {
                        contentIdToOrderMap.put(contentId, count);

                        // An index in contentIdAndFileNamesList is the order of each element.
                        contentIdAndFileNamesList.add(new ContentIdAndFileNames(contentId));
                        count++;
                    }

                    int id = contentIdToOrderMap.get(contentId);
                    ContentIdAndFileNames elem = contentIdAndFileNamesList.get(id);
                    List<String> files = elem.mLibraryToFileNameMap.computeIfAbsent(
                            libraryName, k -> new ArrayList());
                    files.add(fileName);
                    if (TextUtils.isEmpty(libraryName)) {
                        writer.format("<li><a href=\"#id%d\">%s</a></li>\n", id, fileName);
                    } else {
                        writer.format("<li><a href=\"#id%d\">%s - %s</a></li>\n",
                                id, fileName, libraryName);
                    }
                }
            }
        }

        writer.println(HTML_MIDDLE_STRING);

        // Prints all contents of the license files in order of id.
        for (ContentIdAndFileNames contentIdAndFileNames : contentIdAndFileNamesList) {
            // Assigns an id to a newly referred license file content (should never happen here)
            if (!contentIdToOrderMap.containsKey(contentIdAndFileNames.mContentId)) {
                contentIdToOrderMap.put(contentIdAndFileNames.mContentId, count);
                count++;
            }
            int id = contentIdToOrderMap.get(contentIdAndFileNames.mContentId);
            writer.format("<tr id=\"id%d\"><td class=\"same-license\">\n", id);
            for (Map.Entry<String, List<String>> libraryFiles :
                    contentIdAndFileNames.mLibraryToFileNameMap.entrySet()) {
                String libraryName = libraryFiles.getKey();
                if (TextUtils.isEmpty(libraryName)) {
                    writer.println("<div class=\"label\">Notices for file(s):</div>");
                } else {
                    writer.format("<div class=\"label\"><strong>%s</strong> used by:</div>\n",
                            libraryName);
                }
                writer.println("<div class=\"file-list\">");
                for (String fileName : libraryFiles.getValue()) {
                    writer.format("%s <br/>\n", fileName);
                }
                writer.println("</div><!-- file-list -->");
            }
            writer.println("<pre class=\"license-text\">");
            writer.println(contentIdToFileContentMap.get(
                    contentIdAndFileNames.mContentId));
            writer.println("</pre><!-- license-text -->");
            writer.println("</td></tr><!-- same-license -->");
        }

        writer.println(HTML_REAR_STRING);
    }
}
