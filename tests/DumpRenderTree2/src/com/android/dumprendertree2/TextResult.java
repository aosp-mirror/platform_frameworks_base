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

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.webkit.WebView;

import name.fraser.neil.plaintext.diff_match_patch;

import java.util.LinkedList;

/**
 * A result object for which the expected output is text. It does not have an image
 * expected result.
 *
 * <p>Created if layoutTestController.dumpAsText() was called.
 */
public class TextResult extends AbstractResult {

    private static final int MSG_DOCUMENT_AS_TEXT = 0;

    private String mExpectedResult;
    private String mExpectedResultPath;
    private String mActualResult;
    private String mRelativePath;
    private boolean mDidTimeOut;
    private ResultCode mResultCode;
    transient private Message mResultObtainedMsg;

    private boolean mDumpChildFramesAsText;

    transient private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_DOCUMENT_AS_TEXT) {
                mActualResult = (String)msg.obj;
                mResultObtainedMsg.sendToTarget();
            }
        }
    };

    public TextResult(String relativePath) {
        mRelativePath = relativePath;
    }

    public void setDumpChildFramesAsText(boolean dumpChildFramesAsText) {
        mDumpChildFramesAsText = dumpChildFramesAsText;
    }

    /**
     * Used to recreate the Result when received by the service.
     *
     * @param bundle
     *      bundle with data used to recreate the result
     */
    public TextResult(Bundle bundle) {
        mExpectedResult = bundle.getString("expectedTextualResult");
        mExpectedResultPath = bundle.getString("expectedTextualResultPath");
        mActualResult = bundle.getString("actualTextualResult");
        setAdditionalTextOutputString(bundle.getString("additionalTextOutputString"));
        mRelativePath = bundle.getString("relativePath");
        mDidTimeOut = bundle.getBoolean("didTimeOut");
    }

    @Override
    public void clearResults() {
        super.clearResults();
        mExpectedResult = null;
        mActualResult = null;
    }

    @Override
    public ResultCode getResultCode() {
        if (mResultCode != null) {
            return mResultCode;
        }

        if (mExpectedResult == null) {
            mResultCode = AbstractResult.ResultCode.NO_EXPECTED_RESULT;
        } else if (!mExpectedResult.equals(mActualResult)) {
            mResultCode = AbstractResult.ResultCode.RESULTS_DIFFER;
        } else {
            mResultCode = AbstractResult.ResultCode.RESULTS_MATCH;
        }
        return mResultCode;
    }

    @Override
    public boolean didCrash() {
        return false;
    }

    @Override
    public boolean didTimeOut() {
        return mDidTimeOut;
    }

    @Override
    public void setDidTimeOut() {
        mDidTimeOut = true;
    }

    @Override
    public byte[] getActualImageResult() {
        return null;
    }

    @Override
    public String getActualTextResult() {
        String additionalTextResultString = getAdditionalTextOutputString();
        if (additionalTextResultString != null) {
            return additionalTextResultString+ mActualResult;
        }

        return mActualResult;
    }

    @Override
    public void setExpectedImageResult(byte[] expectedResult) {
        /** This method is not applicable to this type of result */
    }

    @Override
    public void setExpectedImageResultPath(String relativePath) {
        /** This method is not applicable to this type of result */
    }

    @Override
    public String getExpectedImageResultPath() {
        /** This method is not applicable to this type of result */
        return null;
    }

    @Override
    public void setExpectedTextResultPath(String relativePath) {
        mExpectedResultPath = relativePath;
    }

    @Override
    public String getExpectedTextResultPath() {
        return mExpectedResultPath;
    }

    @Override
    public void setExpectedTextResult(String expectedResult) {
        mExpectedResult = expectedResult;
    }

    @Override
    public String getDiffAsHtml() {
        StringBuilder html = new StringBuilder();
        html.append("<table class=\"visual_diff\">");
        html.append("    <tr class=\"headers\">");
        html.append("        <td colspan=\"2\">Expected result:</td>");
        html.append("        <td class=\"space\"></td>");
        html.append("        <td colspan=\"2\">Actual result:</td>");
        html.append("    </tr>");

        if (mExpectedResult == null || mActualResult == null) {
            appendNullsHtml(html);
        } else {
            appendDiffHtml(html);
        }

        html.append("    <tr class=\"footers\">");
        html.append("        <td colspan=\"2\"></td>");
        html.append("        <td class=\"space\"></td>");
        html.append("        <td colspan=\"2\"></td>");
        html.append("    </tr>");
        html.append("</table>");

        return html.toString();
    }

    private void appendDiffHtml(StringBuilder html) {
        LinkedList<diff_match_patch.Diff> diffs =
                new diff_match_patch().diff_main(mExpectedResult, mActualResult);

        diffs = VisualDiffUtils.splitDiffsOnNewline(diffs);

        LinkedList<String> expectedLines = new LinkedList<String>();
        LinkedList<Integer> expectedLineNums = new LinkedList<Integer>();
        LinkedList<String> actualLines = new LinkedList<String>();
        LinkedList<Integer> actualLineNums = new LinkedList<Integer>();

        VisualDiffUtils.generateExpectedResultLines(diffs, expectedLineNums, expectedLines);
        VisualDiffUtils.generateActualResultLines(diffs, actualLineNums, actualLines);

        html.append(VisualDiffUtils.getHtml(expectedLineNums, expectedLines,
                actualLineNums, actualLines));
    }

    private void appendNullsHtml(StringBuilder html) {
        /** TODO: Create a separate row for each line of not null result */
        html.append("    <tr class=\"results\">");
        html.append("    <td class=\"line_count\">");
        html.append("    </td>");
        html.append("    <td class=\"line\">");
        if (mExpectedResult == null) {
            html.append("Expected result was NULL");
        } else {
            html.append(mExpectedResult.replace("\n", "<br />"));
        }
        html.append("        </td>");
        html.append("        <td class=\"space\"></td>");
        html.append("    <td class=\"line_count\">");
        html.append("    </td>");
        html.append("    <td class=\"line\">");
        if (mActualResult == null) {
            html.append("Actual result was NULL");
        } else {
            html.append(mActualResult.replace("\n", "<br />"));
        }
        html.append("        </td>");
        html.append("    </tr>");
    }

    @Override
    public TestType getType() {
        return TestType.TEXT;
    }

    @Override
    public void obtainActualResults(WebView webview, Message resultObtainedMsg) {
        mResultObtainedMsg = resultObtainedMsg;
        Message msg = mHandler.obtainMessage(MSG_DOCUMENT_AS_TEXT);

        /**
         * arg1 - should dump top frame as text
         * arg2 - should dump child frames as text
         */
        msg.arg1 = 1;
        msg.arg2 = mDumpChildFramesAsText ? 1 : 0;
        webview.documentAsText(msg);
    }

    @Override
    public Bundle getBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("expectedTextualResult", mExpectedResult);
        bundle.putString("expectedTextualResultPath", mExpectedResultPath);
        bundle.putString("actualTextualResult", getActualTextResult());
        bundle.putString("additionalTextOutputString", getAdditionalTextOutputString());
        bundle.putString("relativePath", mRelativePath);
        bundle.putBoolean("didTimeOut", mDidTimeOut);
        bundle.putString("type", getType().name());
        return bundle;
    }

    @Override
    public String getRelativePath() {
        return mRelativePath;
    }
}
