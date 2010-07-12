/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.util.Log;

import java.io.File;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

/**
 * A class that collects information about tests that ran and can create HTML
 * files with summaries and easy navigation.
 */
public class Summarizer {

    private static final String LOG_TAG = "Summarizer";

    private static final String CSS =
            "body {font-family: Verdana;} a {font-size: 12px; color: black; } h3" +
            "{ font-size: 20px; padding: 0; margin: 0; margin-bottom: 10px; } " +
            ".space { margin-top:30px; } table.diff_both, table.diff_both tr, " +
            "table.diff_both td { border: 0; padding: 0; margin: 0; } " +
            "table.diff_both { width: 600px; } table.diff_both td.dleft, " +
            "table.diff_both td.dright { border: 0; width: 50%; } " +
            "table.diff " + "table.diff_both caption { text-align: left; margin-bottom: 3px;}" +
            "{ border:1px solid black; border-collapse: collapse; width: 100%; } " +
            "table.diff tr {   vertical-align: top; border-bottom: 1px dashed black; " +
            "border-top: 1px dashed black; font-size: 15px; } table.diff td.linecount " +
            "{ border-right: 1px solid; background-color: #aaa; width: 20px; text-align: " +
            "right; padding-right: 1px; padding-top: 2px; padding-bottom: 2px; } " +
            "table.diff td.line { padding-left: 3px; padding-top: 2px; " +
            "padding-bottom: 2px; } span.eql { background-color: #f3f3f3;} " +
            "span.del { background-color: #ff8888; } span.ins { background-color: #88ff88; }";
    private static final String HTML_DIFF_BEGINNING = "<html><head><style type=\"text/css\">" +
            CSS + "</style></head><body>";
    private static final String HTML_DIFF_ENDING = "</body></html>";

    /** TODO: Make it a setting */
    private static final String HTML_DIFF_RELATIVE_PATH = "_diff.html";
    private static final String HTML_DIFF_INDEX_RELATIVE_PATH = "_diff-index.html";

    /** A list containing relatives paths of tests that were skipped */
    private LinkedList<String> mSkippedTestsList = new LinkedList<String>();

    /** Collection of tests grouped according to result. Sets are initialized lazily. */
    private Map<AbstractResult.ResultCode, Set<String>> mResults =
            new EnumMap<AbstractResult.ResultCode, Set<String>>(AbstractResult.ResultCode.class);

    /**
     * Collection of tests for which results are ignored grouped according to result. Sets are
     * initialized lazily.
     */
    private Map<AbstractResult.ResultCode, Set<String>> mResultsIgnored =
            new EnumMap<AbstractResult.ResultCode, Set<String>>(AbstractResult.ResultCode.class);

    private FileFilter mFileFilter;
    private String mResultsRootDirPath;

    public Summarizer(FileFilter fileFilter, String resultsRootDirPath) {
        mFileFilter = fileFilter;
        mResultsRootDirPath = resultsRootDirPath;
        createHtmlDiff();
    }

    private void createHtmlDiff() {
        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_DIFF_RELATIVE_PATH),
                HTML_DIFF_BEGINNING.getBytes(), false);
    }

    private void appendHtmlDiff(String relativePath, String diff) {
        StringBuilder html = new StringBuilder();
        html.append("<label id=\"" + relativePath + "\" />");
        html.append(diff);
        html.append("<a href=\"" + HTML_DIFF_INDEX_RELATIVE_PATH + "\">Back to index</a>");
        html.append("<div class=\"space\"></div>");
        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_DIFF_RELATIVE_PATH),
                html.toString().getBytes(), true);
    }

    private void finalizeHtmlDiff() {
        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_DIFF_RELATIVE_PATH),
                HTML_DIFF_ENDING.getBytes(), true);
    }

    /** TODO: Add settings method, like setIndexSkippedTests(), setIndexTimedOutTests(), etc */

    public void addSkippedTest(String relativePath) {
        mSkippedTestsList.addLast(relativePath);
    }

    public void appendTest(LayoutTest test) {
        String testPath = test.getRelativePath();

        /** Obtain the result */
        AbstractResult result = test.getResult();
        if (result == null) {
            Log.e(LOG_TAG + "::appendTest", testPath + ": result NULL!!");
            return;
        }

        AbstractResult.ResultCode resultCode = result.getCode();

        /** Add the test to correct collection according to its result code */
        if (mFileFilter.isIgnoreRes(testPath)) {
            /** Lazy initialization */
            if (mResultsIgnored.get(resultCode) == null) {
                mResultsIgnored.put(resultCode, new HashSet<String>());
            }

            mResultsIgnored.get(resultCode).add(testPath);
        } else {
            /** Lazy initialization */
            if (mResults.get(resultCode) == null) {
                mResults.put(resultCode, new HashSet<String>());
            }

            mResults.get(resultCode).add(testPath);
        }

        if (resultCode != AbstractResult.ResultCode.PASS) {
            appendHtmlDiff(testPath, result.getDiffAsHtml());
        }
    }

    public void summarize() {
        finalizeHtmlDiff();
        createHtmlDiffIndex();
    }

    private void createHtmlDiffIndex() {
        StringBuilder html = new StringBuilder();
        html.append(HTML_DIFF_BEGINNING);
        Set<String> results;
        html.append("<h1>Tests that were _not_ ignored</h1>");
        appendResultsMap(mResults, html);
        html.append("<h1>Tests that _were_ ignored</h1>");
        appendResultsMap(mResultsIgnored, html);
        html.append(HTML_DIFF_ENDING);
        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_DIFF_INDEX_RELATIVE_PATH),
                html.toString().getBytes(), false);
    }

    private void appendResultsMap(Map<AbstractResult.ResultCode, Set<String>> resultsMap,
            StringBuilder html) {
        Set<String> results;
        for (AbstractResult.ResultCode resultCode : AbstractResult.ResultCode.values()) {
            results = resultsMap.get(resultCode);
            if (results != null) {
                html.append("<h2>");
                html.append(resultCode.toString());
                html.append("</h2");
                for (String relativePath : results) {
                    html.append("<a href=\"");
                    html.append(HTML_DIFF_RELATIVE_PATH);
                    html.append("#");
                    html.append(relativePath);
                    html.append("\">");
                    html.append(relativePath);
                    html.append("</a><br />");
                }
            }
        }
    }
}
