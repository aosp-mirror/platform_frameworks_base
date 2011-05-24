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

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.os.Build;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.dumprendertree2.forwarder.ForwarderManager;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            "       margin-top: 4px;" +
            "       margin-bottom: 2px;}" +
            "h3 a span.path {" +
            "       text-decoration: underline;}" +
            "h3 span.tri {" +
            "       text-decoration: none;" +
            "       float: left;" +
            "       width: 20px;}" +
            "h3 span.sqr {" +
            "       text-decoration: none;" +
            "       float: left;" +
            "       width: 20px;}" +
            "h3 span.sqr_pass {" +
            "       color: #8ee100;}" +
            "h3 span.sqr_fail {" +
            "       color: #c30000;}" +
            "span.source {" +
            "       display: block;" +
            "       font-size: 10px;" +
            "       color: #888;" +
            "       margin-left: 20px;" +
            "       margin-bottom: 1px;}" +
            "span.source a {" +
            "       font-size: 10px;" +
            "       color: #888;}" +
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
            "span." + AbstractResult.ResultCode.RESULTS_DIFFER.name() + "{" +
            "       background-color: #ccc;" +
            "       color: black;}" +
            "span." + AbstractResult.ResultCode.NO_EXPECTED_RESULT.name() + "{" +
            "       background-color: #a700e4;" +
            "       color: #fff;}" +
            "span.timed_out {" +
            "       background-color: #f3cb00;" +
            "       color: black;}" +
            "span.crashed {" +
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
    private static final String HTML_DETAILS_RELATIVE_PATH = "details.html";
    private static final String TXT_SUMMARY_RELATIVE_PATH = "summary.txt";

    private static final int RESULTS_PER_DUMP = 500;
    private static final int RESULTS_PER_DB_ACCESS = 50;

    private int mCrashedTestsCount = 0;
    private List<AbstractResult> mUnexpectedFailures = new ArrayList<AbstractResult>();
    private List<AbstractResult> mExpectedFailures = new ArrayList<AbstractResult>();
    private List<AbstractResult> mExpectedPasses = new ArrayList<AbstractResult>();
    private List<AbstractResult> mUnexpectedPasses = new ArrayList<AbstractResult>();

    private Cursor mUnexpectedFailuresCursor;
    private Cursor mExpectedFailuresCursor;
    private Cursor mUnexpectedPassesCursor;
    private Cursor mExpectedPassesCursor;

    private FileFilter mFileFilter;
    private String mResultsRootDirPath;
    private String mTestsRelativePath;
    private Date mDate;

    private int mResultsSinceLastHtmlDump = 0;
    private int mResultsSinceLastDbAccess = 0;

    private SummarizerDBHelper mDbHelper;

    public Summarizer(String resultsRootDirPath, Context context) {
        mFileFilter = new FileFilter();
        mResultsRootDirPath = resultsRootDirPath;

        /**
         * We don't run the database I/O in a separate thread to avoid consumer/producer problem
         * and to simplify code.
         */
        mDbHelper = new SummarizerDBHelper(context);
        mDbHelper.open();
    }

    public static URI getDetailsUri() {
        return new File(ManagerService.RESULTS_ROOT_DIR_PATH + File.separator +
                HTML_DETAILS_RELATIVE_PATH).toURI();
    }

    public void appendTest(AbstractResult result) {
        String relativePath = result.getRelativePath();

        if (result.didCrash()) {
            mCrashedTestsCount++;
        }

        if (result.didPass()) {
            result.clearResults();
            if (mFileFilter.isFail(relativePath)) {
                mUnexpectedPasses.add(result);
            } else {
                mExpectedPasses.add(result);
            }
        } else {
            if (mFileFilter.isFail(relativePath)) {
                mExpectedFailures.add(result);
            } else {
                mUnexpectedFailures.add(result);
            }
        }

        if (++mResultsSinceLastDbAccess == RESULTS_PER_DB_ACCESS) {
            persistLists();
            clearLists();
        }
    }

    private void clearLists() {
        mUnexpectedFailures.clear();
        mExpectedFailures.clear();
        mUnexpectedPasses.clear();
        mExpectedPasses.clear();
    }

    private void persistLists() {
        persistListToTable(mUnexpectedFailures, SummarizerDBHelper.UNEXPECTED_FAILURES_TABLE);
        persistListToTable(mExpectedFailures, SummarizerDBHelper.EXPECTED_FAILURES_TABLE);
        persistListToTable(mUnexpectedPasses, SummarizerDBHelper.UNEXPECTED_PASSES_TABLE);
        persistListToTable(mExpectedPasses, SummarizerDBHelper.EXPECTED_PASSES_TABLE);
        mResultsSinceLastDbAccess = 0;
    }

    private void persistListToTable(List<AbstractResult> results, String table) {
        for (AbstractResult abstractResult : results) {
            mDbHelper.insertAbstractResult(abstractResult, table);
        }
    }

    public void setTestsRelativePath(String testsRelativePath) {
        mTestsRelativePath = testsRelativePath;
    }

    public void summarize(Message onFinishMessage) {
        persistLists();
        clearLists();

        mUnexpectedFailuresCursor =
            mDbHelper.getAbstractResults(SummarizerDBHelper.UNEXPECTED_FAILURES_TABLE);
        mUnexpectedPassesCursor =
            mDbHelper.getAbstractResults(SummarizerDBHelper.UNEXPECTED_PASSES_TABLE);
        mExpectedFailuresCursor =
            mDbHelper.getAbstractResults(SummarizerDBHelper.EXPECTED_FAILURES_TABLE);
        mExpectedPassesCursor =
            mDbHelper.getAbstractResults(SummarizerDBHelper.EXPECTED_PASSES_TABLE);

        String webKitRevision = getWebKitRevision();
        createHtmlDetails(webKitRevision);
        createTxtSummary(webKitRevision);

        clearLists();
        mUnexpectedFailuresCursor.close();
        mUnexpectedPassesCursor.close();
        mExpectedFailuresCursor.close();
        mExpectedPassesCursor.close();

        onFinishMessage.sendToTarget();
    }

    public void reset() {
        mCrashedTestsCount = 0;
        clearLists();
        mDbHelper.reset();
        mDate = new Date();
    }

    private void dumpHtmlToFile(StringBuilder html, boolean append) {
        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, HTML_DETAILS_RELATIVE_PATH),
                html.toString().getBytes(), append);
        html.setLength(0);
        mResultsSinceLastHtmlDump = 0;
    }

    private void createTxtSummary(String webKitRevision) {
        StringBuilder txt = new StringBuilder();

        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        txt.append("Path: " + mTestsRelativePath + "\n");
        txt.append("Date: " + dateFormat.format(mDate) + "\n");
        txt.append("Build fingerprint: " + Build.FINGERPRINT + "\n");
        txt.append("WebKit version: " + getWebKitVersionFromUserAgentString() + "\n");
        txt.append("WebKit revision: " + webKitRevision + "\n");

        txt.append("TOTAL:                     " + getTotalTestCount() + "\n");
        txt.append("CRASHED (among all tests): " + mCrashedTestsCount + "\n");
        txt.append("UNEXPECTED FAILURES:       " + mUnexpectedFailuresCursor.getCount() + "\n");
        txt.append("UNEXPECTED PASSES:         " + mUnexpectedPassesCursor.getCount() + "\n");
        txt.append("EXPECTED FAILURES:         " + mExpectedFailuresCursor.getCount() + "\n");
        txt.append("EXPECTED PASSES:           " + mExpectedPassesCursor.getCount() + "\n");

        FsUtils.writeDataToStorage(new File(mResultsRootDirPath, TXT_SUMMARY_RELATIVE_PATH),
                txt.toString().getBytes(), false);
    }

    private void createHtmlDetails(String webKitRevision) {
        StringBuilder html = new StringBuilder();

        html.append("<html><head>");
        html.append(CSS);
        html.append(SCRIPT);
        html.append("</head><body>");

        createTopSummaryTable(webKitRevision, html);
        dumpHtmlToFile(html, false);

        createResultsList(html, "Unexpected failures", mUnexpectedFailuresCursor);
        createResultsList(html, "Unexpected passes", mUnexpectedPassesCursor);
        createResultsList(html, "Expected failures", mExpectedFailuresCursor);
        createResultsList(html, "Expected passes", mExpectedPassesCursor);

        html.append("</body></html>");
        dumpHtmlToFile(html, true);
    }

    private int getTotalTestCount() {
        return mUnexpectedFailuresCursor.getCount() +
                mUnexpectedPassesCursor.getCount() +
                mExpectedPassesCursor.getCount() +
                mExpectedFailuresCursor.getCount();
    }

    private String getWebKitVersionFromUserAgentString() {
        Resources resources = new Resources(new AssetManager(), new DisplayMetrics(),
                new Configuration());
        String userAgent =
                resources.getString(com.android.internal.R.string.web_user_agent);

        Matcher matcher = Pattern.compile("AppleWebKit/([0-9]+?\\.[0-9])").matcher(userAgent);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private String getWebKitRevision() {
        URL url = null;
        try {
            url = new URL(ForwarderManager.getHostSchemePort(false) + "ThirdPartyProject.prop");
        } catch (MalformedURLException e) {
            assert false;
        }

        String thirdPartyProjectContents = new String(FsUtils.readDataFromUrl(url));
        Matcher matcher = Pattern.compile("^version=([0-9]+)", Pattern.MULTILINE).matcher(
                thirdPartyProjectContents);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "unknown";
    }

    private void createTopSummaryTable(String webKitRevision, StringBuilder html) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
        html.append("<h1>" + "Layout tests' results for: " +
                (mTestsRelativePath.equals("") ? "all tests" : mTestsRelativePath) + "</h1>");
        html.append("<h3>" + "Date: " + dateFormat.format(new Date()) + "</h3>");
        html.append("<h3>" + "Build fingerprint: " + Build.FINGERPRINT + "</h3>");
        html.append("<h3>" + "WebKit version: " + getWebKitVersionFromUserAgentString() + "</h3>");

        html.append("<h3>" + "WebKit revision: ");
        html.append("<a href=\"http://trac.webkit.org/browser/trunk?rev=" + webKitRevision +
                "\" target=\"_blank\"><span class=\"path\">" + webKitRevision + "</span></a>");
        html.append("</h3>");

        html.append("<table class=\"summary\">");
        createSummaryTableRow(html, "TOTAL", getTotalTestCount());
        createSummaryTableRow(html, "CRASHED (among all tests)", mCrashedTestsCount);
        createSummaryTableRow(html, "UNEXPECTED FAILURES", mUnexpectedFailuresCursor.getCount());
        createSummaryTableRow(html, "UNEXPECTED PASSES", mUnexpectedPassesCursor.getCount());
        createSummaryTableRow(html, "EXPECTED FAILURES", mExpectedFailuresCursor.getCount());
        createSummaryTableRow(html, "EXPECTED PASSES", mExpectedPassesCursor.getCount());
        html.append("</table>");
    }

    private void createSummaryTableRow(StringBuilder html, String caption, int size) {
        html.append("<tr>");
        html.append("    <td>" + caption + "</td>");
        html.append("    <td>" + size + "</td>");
        html.append("</tr>");
    }

    private void createResultsList(
            StringBuilder html, String title, Cursor cursor) {
        String relativePath;
        String id = "";
        AbstractResult.ResultCode resultCode;

        html.append("<h2>" + title + " [" + cursor.getCount() + "]</h2>");

        if (!cursor.moveToFirst()) {
            return;
        }

        AbstractResult result;
        do {
            result = SummarizerDBHelper.getAbstractResult(cursor);

            relativePath = result.getRelativePath();
            resultCode = result.getResultCode();

            html.append("<h3>");

            /**
             * Technically, two different paths could end up being the same, because
             * ':' is a valid  character in a path. However, it is probably not going
             * to cause any problems in this case
             */
            id = relativePath.replace(File.separator, ":");

            /** Write the test name */
            if (resultCode == AbstractResult.ResultCode.RESULTS_DIFFER) {
                html.append("<a href=\"#\" onClick=\"toggleDisplay('" + id + "');");
                html.append("return false;\">");
                html.append("<span class=\"tri\" id=\"tri." + id + "\">&#x25b6; </span>");
                html.append("<span class=\"path\">" + relativePath + "</span>");
                html.append("</a>");
            } else {
                html.append("<a href=\"" + getViewSourceUrl(result.getRelativePath()).toString() + "\"");
                html.append(" target=\"_blank\">");
                html.append("<span class=\"sqr sqr_" + (result.didPass() ? "pass" : "fail"));
                html.append("\">&#x25a0; </span>");
                html.append("<span class=\"path\">" + result.getRelativePath() + "</span>");
                html.append("</a>");
            }

            if (!result.didPass()) {
                appendTags(html, result);
            }

            html.append("</h3>");
            appendExpectedResultsSources(result, html);

            if (resultCode == AbstractResult.ResultCode.RESULTS_DIFFER) {
                html.append("<div class=\"diff\" style=\"display: none;\" id=\"" + id + "\">");
                html.append(result.getDiffAsHtml());
                html.append("<a href=\"#\" onClick=\"toggleDisplay('" + id + "');");
                html.append("return false;\">Hide</a>");
                html.append(" | ");
                html.append("<a href=\"" + getViewSourceUrl(relativePath).toString() + "\"");
                html.append(" target=\"_blank\">Show source</a>");
                html.append("</div>");
            }

            html.append("<div class=\"space\"></div>");

            if (++mResultsSinceLastHtmlDump == RESULTS_PER_DUMP) {
                dumpHtmlToFile(html, true);
            }

            cursor.moveToNext();
        } while (!cursor.isAfterLast());
    }

    private void appendTags(StringBuilder html, AbstractResult result) {
        /** Tag tests which crash, time out or where results don't match */
        if (result.didCrash()) {
            html.append(" <span class=\"listItem crashed\">Crashed</span>");
        } else {
            if (result.didTimeOut()) {
                html.append(" <span class=\"listItem timed_out\">Timed out</span>");
            }
            AbstractResult.ResultCode resultCode = result.getResultCode();
            if (resultCode != AbstractResult.ResultCode.RESULTS_MATCH) {
                html.append(" <span class=\"listItem " + resultCode.name() + "\">");
                html.append(resultCode.toString());
                html.append("</span>");
            }
        }

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
    }

    private static final void appendExpectedResultsSources(AbstractResult result,
            StringBuilder html) {
        String textSource = result.getExpectedTextResultPath();
        String imageSource = result.getExpectedImageResultPath();

        if (result.didCrash()) {
            html.append("<span class=\"source\">Did not look for expected results</span>");
            return;
        }

        if (textSource == null) {
            // Show if a text result is missing. We may want to revisit this decision when we add
            // support for image results.
            html.append("<span class=\"source\">Expected textual result missing</span>");
        } else {
            html.append("<span class=\"source\">Expected textual result from: ");
            html.append("<a href=\"" + ForwarderManager.getHostSchemePort(false) + "LayoutTests/" +
                    textSource + "\"");
            html.append(" target=\"_blank\">");
            html.append(textSource + "</a></span>");
        }
        if (imageSource != null) {
            html.append("<span class=\"source\">Expected image result from: ");
            html.append("<a href=\"" + ForwarderManager.getHostSchemePort(false) + "LayoutTests/" +
                    imageSource + "\"");
            html.append(" target=\"_blank\">");
            html.append(imageSource + "</a></span>");
        }
    }

    private static final URL getViewSourceUrl(String relativePath) {
        URL url = null;
        try {
            url = new URL("http", "localhost", ForwarderManager.HTTP_PORT,
                    "/Tools/DumpRenderTree/android/view_source.php?src=" +
                    relativePath);
        } catch (MalformedURLException e) {
            assert false : "relativePath=" + relativePath;
        }
        return url;
    }
}
