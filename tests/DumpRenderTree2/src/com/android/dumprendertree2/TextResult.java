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

/**
 * A result object for which the expected output is text. It does not have an image
 * expected result.
 *
 * <p>Created if layoutTestController.dumpAsText() was called.
 */
public class TextResult extends AbstractResult {

    private static final int MSG_DOCUMENT_AS_TEXT = 0;

    private String mExpectedResult;
    private String mActualResult;
    private String mRelativePath;
    private ResultCode mResultCode;
    private Message mResultObtainedMsg;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_DOCUMENT_AS_TEXT) {
                mActualResult = (String) msg.obj;
                mResultObtainedMsg.sendToTarget();
            }
        }
    };

    public TextResult(String relativePath) {
        mRelativePath = relativePath;
    }

    /**
     * Used to recreate the Result when received by the service.
     *
     * @param bundle
     *      bundle with data used to recreate the result
     */
    public TextResult(Bundle bundle) {
        mExpectedResult = bundle.getString("expectedTextualResult");
        mActualResult = bundle.getString("actualTextualResult");
        mRelativePath = bundle.getString("relativePath");
        String resultCode = bundle.getString("resultCode");
        if (resultCode != null) {
            mResultCode = ResultCode.valueOf(resultCode);
        }
    }

    @Override
    public ResultCode getResultCode() {
        if (mResultCode != null) {
            return mResultCode;
        }

        if (mExpectedResult == null) {
            mResultCode = AbstractResult.ResultCode.FAIL_NO_EXPECTED_RESULT;
        } else if (!mExpectedResult.equals(mActualResult)) {
            mResultCode = AbstractResult.ResultCode.FAIL_RESULT_DIFFERS;
        } else {
            mResultCode = AbstractResult.ResultCode.PASS;
        }

        return mResultCode;
    }

    @Override
    public byte[] getActualImageResult() {
        return null;
    }

    @Override
    public String getActualTextResult() {
        return mActualResult;
    }

    @Override
    public void setExpectedImageResult(byte[] expectedResult) {
        /** This method is not applicable to this type of result */
    }

    @Override
    public void setExpectedTextResult(String expectedResult) {
        mExpectedResult = expectedResult;
    }

    @Override
    public String getDiffAsHtml() {
        /** TODO: just a stub
         * Probably needs rethinking anyway - just one table would be much better
         * This will require some changes in Summarizer in CSS as well */
        StringBuilder html = new StringBuilder();
        html.append("<h3>");
        html.append(mRelativePath);
        html.append("</h3>");
        html.append("<table class=\"visual_diff\">");

        html.append("<tr class=\"headers\">");
        html.append("<td colspan=\"2\">Expected result:</td>");
        html.append("<td class=\"space\"></td>");
        html.append("<td colspan=\"2\">Actual result:</td>");
        html.append("</tr>");

        html.append("<tr class=\"results\">");
        html.append("<td class=\"line_count\">1:</td>");
        html.append("<td class=\"line\">");
        if (mExpectedResult == null) {
            html.append("NULL");
        } else {
            html.append(mExpectedResult.replace("\n", "<br />"));
        }
        html.append("</td>");
        html.append("<td class=\"space\"></td>");
        html.append("<td class=\"line_count\">1:</td>");
        html.append("<td class=\"line\">");
        if (mActualResult == null) {
            html.append("NULL");
        } else {
            html.append(mActualResult.replace("\n", "<br />"));
        }
        html.append("</td>");
        html.append("</tr>");

        html.append("<tr class=\"footers\">");
        html.append("<td colspan=\"2\"></td>");
        html.append("<td class=\"space\"></td>");
        html.append("<td colspan=\"2\"></td>");
        html.append("</tr>");

        html.append("</table>");

        return html.toString();
    }

    @Override
    public TestType getType() {
        return TestType.TEXT;
    }

    @Override
    public void obtainActualResults(WebView webview, Message resultObtainedMsg) {
        mResultObtainedMsg = resultObtainedMsg;
        Message msg = mHandler.obtainMessage(MSG_DOCUMENT_AS_TEXT);

        /** TODO: mDumpTopFrameAsText and mDumpChildFramesAsText */
        msg.arg1 = 1;
        msg.arg2 = 0;
        webview.documentAsText(msg);
    }

    @Override
    public Bundle getBundle() {
        Bundle bundle = new Bundle();
        bundle.putString("expectedTextualResult", mExpectedResult);
        bundle.putString("actualTextualResult", mActualResult);
        bundle.putString("relativePath", mRelativePath);
        if (mResultCode != null) {
            bundle.putString("resultCode", mResultCode.name());
        }
        bundle.putString("type", getType().name());
        return bundle;
    }

    @Override
    public String getRelativePath() {
        return mRelativePath;
    }
}