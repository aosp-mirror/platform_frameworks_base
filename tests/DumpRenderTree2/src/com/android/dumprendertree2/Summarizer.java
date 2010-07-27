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
            "* {" +
            "       font-family: Verdana;" +
            "       border: 0;" +
            "       margin: 0;" +
            "       padding: 0;}" +
            "body {" +
            "       margin: 10px;}" +
            "a {" +
            "       font-size: 12px;" +
            "       color: black;}" +
            "h1 {" +
            "       font-size: 33px;" +
            "       margin: 4px 0 4px 0;}" +
            "h2 {" +
            "       font-size:22px;" +
            "       margin: 20px 0 3px 0;}" +
            "h3 {" +
            "       font-size: 20px;" +
            "       margin-bottom: 6px;}" +
            "table.visual_diff {" +
            "       border-bottom: 0px solid;" +
            "       border-collapse: collapse;" +
            "       width: 100%;" +
            "       margin-bottom: 3px;}" +
            "table.visual_diff tr.headers td {" +
            "       border-bottom: 1px solid;" +
            "       border-top: 0;" +
            "       padding-bottom: 3px;}" +
            "table.visual_diff tr.results td {" +
            "       border-top: 1px dashed;" +
            "       border-right: 1px solid;" +
            "       font-size: 15px;" +
            "       vertical-align: top;}" +
            "table.visual_diff tr.results td.line_count {" +
            "       background-color:#aaa;" +
            "       min-width:20px;" +
            "       text-align: right;" +
            "       border-right: 1px solid;" +
            "       border-left: 1px solid;" +
            "       padding: 2px 1px 2px 0px;}" +
            "table.visual_diff tr.results td.line {" +
            "       padding: 2px 0px 2px 4px;" +
            "       border-right: 1px solid;" +
            "       width: 49.8%;}" +
            "table.visual_diff tr.footers td {" +
            "       border-top: 1px solid;" +
            "       border-bottom: 0;}" +
            "table.visual_diff tr td.space {" +
            "       border: 0;" +
            "       width: 0.4%}" +
            "div.space {" +
            "       margin-top:30px;}" +
            "span.eql {" +
            "       background-color: #f3f3f3;}" +
            "span.del {" +
            "       background-color: #ff8888; }" +
            "span.ins {" +
            "       background-color: #88ff88; }" +
            "span.fail {" +
            "       color: red;}" +
            "span.pass {" +
            "       color: green;}" +
            "span.time_out {" +
            "       color: orange;}";
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

    public void appendTest(AbstractResult result) {
        String testPath = result.getRelativePath();

        AbstractResult.ResultCode resultCode = result.getResultCode();

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
        html.append("<h1>NOT ignored</h1>");
        appendResultsMap(mResults, html);
        html.append("<h1>Ignored</h1>");
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