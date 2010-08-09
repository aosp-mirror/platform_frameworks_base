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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * A class that collects information about tests that ran and can create HTML
 * files with summaries and easy navigation.
 */
public class Summarizer {

    private static final String LOG_TAG = "Summarizer";

    private static final String CSS =
            "<style type=\"text/css\">" +
            "* {" +
            "       font-family: Verdana;" +
            "       border: 0;" +
            "       margin: 0;" +
            "       padding: 0;}" +
            "body {" +
            "       margin: 10px;}" +
            "h1 {" +
            "       font-size: 24px;" +
            "       margin: 4px 0 4px 0;}" +
            "h2 {" +
            "       font-size:18px;" +
            "       text-transform: uppercase;" +
            "       margin: 20px 0 3px 0;}" +
            "h3, h3 a {" +
            "       font-size: 14px;" +
            "       color: black;" +
            "       text-decoration: none;" +
            "       margin-bottom: 4px;}" +
            "h3 a span.path {" +
            "       text-decoration: underline;}" +
            "h3 span.tri {" +
            "       text-decoration: none;" +
            "       float: left;" +
            "       width: 20px;}" +
            "h3 span.sqr {" +
            "       text-decoration: none;" +
            "       color: #8ee100;" +
            "       float: left;" +
            "       width: 20px;}" +
            "h3 img {" +
            "       width: 8px;" +
            "       margin-right: 4px;}" +
            "div.diff {" +
            "       margin-bottom: 25px;}" +
            "div.diff a {" +
            "       font-size: 12px;" +
            "       color: #888;}" +
            "table.visual_diff {" +
            "       border-bottom: 0px solid;" +
            "       border-collapse: collapse;" +
            "       width: 100%;" +
            "       margin-bottom: 2px;}" +
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
            "       margin-top:4px;}" +
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
            "       color: orange;}" +
            "table.summary {" +
            "       border: 1px solid black;" +
            "       margin-top: 20px;}" +
            "table.summary td {" +
            "       padding: 3px;}" +
            "span.listItem {" +
            "       font-size: 11px;" +
            "       font-weight: normal;" +
            "       text-transform: uppercase;" +
            "       padding: 3px;" +
            "       -webkit-border-radius: 4px;}" +
            "span." + AbstractResult.ResultCode.PASS.name() + "{" +
            "       background-color: #8ee100;" +
            "       color: black;}" +
            "span." + AbstractResult.ResultCode.FAIL_RESULT_DIFFERS.name() + "{" +
            "       background-color: #ccc;" +
            "       color: black;}" +
            "span." + AbstractResult.ResultCode.FAIL_NO_EXPECTED_RESULT.name() + "{" +
            "       background-color: #a700e4;" +
            "       color: #fff;}" +
            "span." + AbstractResult.ResultCode.FAIL_TIMED_OUT.name() + "{" +
            "       background-color: #f3cb00;" +
            "       color: black;}" +
            "span." + AbstractResult.ResultCode.FAIL_CRASHED.name() + "{" +
            "       background-color: #c30000;" +
            "       color: #fff;}" +
            "span.noLtc {" +
            "       background-color: #944000;" +
            "       color: #fff;}" +
            "span.noEventSender {" +
            "       background-color: #815600;" +
            "       color: #fff;}" +
            "</style>";

    private static final String SCRIPT =
            "<script type=\"text/javascript\">" +
            "    function toggleDisplay(id) {" +
            "        element = document.getElementById(id);" +
            "        triangle = document.getElementById('tri.' + id);" +
            "        if (element.style.display == 'none') {" +
            "            element.style.display = 'inline';" +
            "            triangle.innerHTML = '&#x25bc; ';" +
            "        } else {" +
            "            element.style.display = 'none';" +
            "            triangle.innerHTML = '&#x25b6; ';" +
            "        }" +
            "    }" +
            "</script>";

    /** TODO: Make it a setting */
    private static final String HTML_SUMMARY_RELATIVE_PATH = "summary.html";
    private static final String TXT_SUMMARY_RELATIVE_PATH = "summary.txt";

    private int mCrashedTestsCount = 0;
    private List<AbstractResult> mFailedNotIgnoredTests = new ArrayList<AbstractResult>();
    private List<AbstractResult> mIgnoredTests = new ArrayList<AbstractResult>();
    private List<String> mPassedNotIgnoredTests = new ArrayList<String>();

    private FileFilter mFileFilter;
    private String mResultsRootDirPath;

    private String mTitleString;

    public Summarizer(FileFilter fileFilter, String resultsRootDirPath) {
        mFileFilter = fileFilter;
        mResultsRootDirPath = resultsRootDirPath;
    }

    public void appendTest(AbstractResult result) {
        String relativePath = result.getRelativePath();

        if (result.getResultCode() == AbstractResult.ResultCode.FAIL_CRASHED) {
            mCrashedTestsCount++;
        }

        if (mFileFilter.isIgnoreRes(relativePath)) {
            mIgnoredTests.add(result);
        } else if (result.getResultCode() == AbstractResult.ResultCode.PASS) {
            mPassedNotIgnoredTests.add(relativePath);
        } else {
            mFailedNotIgnoredTests.add(result);
        }
    }

    public void summarize() {
        createHtmlSummary();
        createTxtSummary();
    }

    private void createTxtSummary() {
        StringBuilder txt = new StringBuilder();

        txt.append(getTitleString() + "\n");
        if (mCrashedTestsCount > 0) {
            txt.append("CRASHED (total among all tests): " + mCrashedTestsCount + "\n");
            txt.append("-------------");
        }
        txt.append("FAILED:  " + mFailedNotIgnoredTests.size() + "\n");
        txt.append("IGNORED: " + mIgnoredTests.size() + "\n");
        txt.append("PASSED:  " + mPassedNotIgnoredTests.size() + "\n");

        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, TXT_SUMMARY_RELATIVE_PATH),
                txt.toString().getBytes(), false);
    }

    private void createHtmlSummary() {
        StringBuilder html = new StringBuilder();

        html.append("<html><head>");
        html.append(CSS);
        html.append(SCRIPT);
        html.append("</head><body>");

        createTopSummaryTable(html);

        createResultsListWithDiff(html, "Failed", mFailedNotIgnoredTests);

        createResultsListWithDiff(html, "Ignored", mIgnoredTests);

        createResultsListNoDiff(html, "Passed", mPassedNotIgnoredTests);

        html.append("</body></html>");

        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_SUMMARY_RELATIVE_PATH),
                html.toString().getBytes(), false);
    }

    private String getTitleString() {
        if (mTitleString == null) {
            int total = mFailedNotIgnoredTests.size() +
                    mPassedNotIgnoredTests.size() +
                    mIgnoredTests.size();
            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
            mTitleString = " - total of " + total + " tests - " + dateFormat.format(new Date());
        }

        return mTitleString;
    }

    private void createTopSummaryTable(StringBuilder html) {
        html.append("<h1>" + getTitleString() + "</h1>");

        html.append("<table class=\"summary\">");
        createSummaryTableRow(html, "CRASHED", mCrashedTestsCount);
        createSummaryTableRow(html, "FAILED", mFailedNotIgnoredTests.size());
        createSummaryTableRow(html, "IGNORED", mIgnoredTests.size());
        createSummaryTableRow(html, "PASSED", mPassedNotIgnoredTests.size());
        html.append("</table>");
    }

    private void createSummaryTableRow(StringBuilder html, String caption, int size) {
        html.append("<tr>");
        html.append("    <td>" + caption + "</td>");
        html.append("    <td>" + size + "</td>");
        html.append("</tr>");
    }

    private void createResultsListWithDiff(StringBuilder html, String title,
            List<AbstractResult> resultsList) {
        String relativePath;
        String id = "";
        AbstractResult.ResultCode resultCode;

        Collections.sort(resultsList);
        html.append("<h2>" + title + " [" + resultsList.size() + "]</h2>");
        for (AbstractResult result : resultsList) {
            relativePath = result.getRelativePath();
            resultCode = result.getResultCode();

            html.append("<h3>");

            if (resultCode == AbstractResult.ResultCode.PASS) {
                html.append("<span class=\"sqr\">&#x25a0; </span>");
                html.append("<span class=\"path\">" + relativePath + "</span>");
            } else {
                /**
                 * Technically, two different paths could end up being the same, because
                 * ':' is a valid  character in a path. However, it is probably not going
                 * to cause any problems in this case
                 */
                id = relativePath.replace(File.separator, ":");
                html.append("<a href=\"#\" onClick=\"toggleDisplay('" + id + "');");
                html.append("return false;\">");
                html.append("<span class=\"tri\" id=\"tri." + id + "\">&#x25b6; </span>");
                html.append("<span class=\"path\">" + relativePath + "</span>");
                html.append("</a>");
            }

            html.append(" <span class=\"listItem " + resultCode.name() + "\">");
            html.append(resultCode.toString());
            html.append("</span>");

            /** Detect missing LTC function */
            String additionalTextOutputString = result.getAdditionalTextOutputString();
            if (additionalTextOutputString != null &&
                    additionalTextOutputString.contains("com.android.dumprendertree") &&
                    additionalTextOutputString.contains("has no method")) {
                if (additionalTextOutputString.contains("LayoutTestController")) {
                    html.append(" <span class=\"listItem noLtc\">LTC function missing</span>");
                }
                if (additionalTextOutputString.contains("EventSender")) {
                    html.append(" <span class=\"listItem noEventSender\">");
                    html.append("ES function missing</span>");
                }
            }

            html.append("</h3>");

            if (resultCode != AbstractResult.ResultCode.PASS) {
                html.append("<div class=\"diff\" style=\"display: none;\" id=\"" + id + "\">");
                html.append(result.getDiffAsHtml());
                html.append("<a href=\"#\" onClick=\"toggleDisplay('" + id + "');");
                html.append("return false;\">Hide</a>");
                html.append("</div>");
            }

            html.append("<div class=\"space\"></div>");
        }
    }

    private void createResultsListNoDiff(StringBuilder html, String title,
            List<String> resultsList) {
        Collections.sort(resultsList);
        html.append("<h2>Passed [" + resultsList.size() + "]</h2>");
        for (String result : resultsList) {
            html.append("<h3>");
            html.append("<span class=\"sqr\">&#x25a0; </span>");
            html.append("<span class=\"path\">" + result + "</span>");
            html.append("</h3>");
            html.append("<div class=\"space\"></div>");
        }
    }
}