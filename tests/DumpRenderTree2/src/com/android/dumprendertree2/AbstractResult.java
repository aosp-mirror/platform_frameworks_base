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
import android.os.Message;
import android.webkit.WebView;

/**
 * A class that represent a result of the test. It is responsible for returning the result's
 * raw data and generating its own diff in HTML format.
 */
public abstract class AbstractResult {

    public enum TestType {
        TEXT,
        PIXEL
    }

    public enum ResultCode {
        PASS("Passed"),
        FAIL_RESULT_DIFFERS("Failed: different results"),
        FAIL_NO_EXPECTED_RESULT("Failed: no expected result"),
        FAIL_TIMED_OUT("Failed: timed out"),
        FAIL_CRASHED("Failed: crashed");

        private String mTitle;

        private ResultCode(String title) {
            mTitle = title;
        }

        @Override
        public String toString() {
            return mTitle;
        }
    }

    /**
     * Makes the result object obtain the results of the test from the webview
     * and store them in the format that suits itself bests. This method is asynchronous.
     * The message passed as a parameter is a message that should be sent to its target
     * when the result finishes obtaining the result.
     *
     * @param webview
     * @param resultObtainedMsg
     */
    public abstract void obtainActualResults(WebView webview, Message resultObtainedMsg);

    public abstract void setExpectedImageResult(byte[] expectedResult);

    public abstract void setExpectedTextResult(String expectedResult);

    /**
     * Returns result's image data that can be written to the disk. It can be null
     * if there is an error of some sort or for example the test times out.
     *
     * <p> Some tests will not provide data (like text tests)
     *
     * @return
     *      results image data
     */
    public abstract byte[] getActualImageResult();

    /**
     * Returns result's text data. It can be null
     * if there is an error of some sort or for example the test times out.
     *
     * @return
     *      results text data
     */
    public abstract String getActualTextResult();

    /**
     * Returns the code of this result.
     *
     * @return
     *      the code of this result
     */
    public abstract ResultCode getResultCode();

    /**
     * Return the type of the result data.
     *
     * @return
     *      the type of the result data.
     */
    public abstract TestType getType();

    /**
     * Returns a piece of HTML code that presents a visual diff between a result and
     * the expected result.
     *
     * @return
     *      a piece of HTML code with a visual diff between the result and the expected result
     */
    public abstract String getDiffAsHtml();

    public abstract Bundle getBundle();
}