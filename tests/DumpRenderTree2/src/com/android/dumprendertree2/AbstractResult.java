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
import android.util.Log;
import android.webkit.WebView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * A class that represent a result of the test. It is responsible for returning the result's
 * raw data and generating its own diff in HTML format.
 */
public abstract class AbstractResult implements Comparable<AbstractResult>, Serializable {

    private static final String LOG_TAG = "AbstractResult";

    public enum TestType {
        TEXT {
            @Override
            public AbstractResult createResult(Bundle bundle) {
                return new TextResult(bundle);
            }
        },
        RENDER_TREE {
            @Override
            public AbstractResult createResult(Bundle bundle) {
                /** TODO: RenderTree tests are not yet supported */
                return null;
            }
        };

        public abstract AbstractResult createResult(Bundle bundle);
    }

    /**
     * A code representing the result of comparing actual and expected results.
     */
    public enum ResultCode implements Serializable {
        RESULTS_MATCH("Results match"),
        RESULTS_DIFFER("Results differ"),
        NO_EXPECTED_RESULT("No expected result"),
        NO_ACTUAL_RESULT("No actual result");

        private String mTitle;

        private ResultCode(String title) {
            mTitle = title;
        }

        @Override
        public String toString() {
            return mTitle;
        }
    }

    String mAdditionalTextOutputString;

    public int compareTo(AbstractResult another) {
        return getRelativePath().compareTo(another.getRelativePath());
    }

    public void setAdditionalTextOutputString(String additionalTextOutputString) {
        mAdditionalTextOutputString = additionalTextOutputString;
    }

    public String getAdditionalTextOutputString() {
        return mAdditionalTextOutputString;
    }

    public byte[] getBytes() {
        ByteArrayOutputStream baos = null;
        ObjectOutputStream oos = null;
        try {
            try {
                baos = new ByteArrayOutputStream();
                oos = new ObjectOutputStream(baos);
                oos.writeObject(this);
            } finally {
                if (baos != null) {
                    baos.close();
                }
                if (oos != null) {
                    oos.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Unable to serialize result: " + getRelativePath(), e);
        }

        return baos == null ? null : baos.toByteArray();
    }

    public static AbstractResult create(byte[] bytes) {
        ByteArrayInputStream bais = null;
        ObjectInputStream ois = null;
        AbstractResult result = null;
        try {
            try {
                bais = new ByteArrayInputStream(bytes);
                ois = new ObjectInputStream(bais);
                result = (AbstractResult)ois.readObject();
            } finally {
                if (bais != null) {
                    bais.close();
                }
                if (ois != null) {
                    ois.close();
                }
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Unable to deserialize result!", e);
        } catch (ClassNotFoundException e) {
            Log.e(LOG_TAG, "Unable to deserialize result!", e);
        }
        return result;
    }

    public void clearResults() {
        mAdditionalTextOutputString = null;
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

    public abstract void setExpectedImageResultPath(String relativePath);

    public abstract String getExpectedImageResultPath();

    public abstract void setExpectedTextResult(String expectedResult);

    public abstract void setExpectedTextResultPath(String relativePath);

    public abstract String getExpectedTextResultPath();

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
     * Returns the status code representing the result of comparing actual and expected results.
     *
     * @return
     *      the status code from comparing actual and expected results
     */
    public abstract ResultCode getResultCode();

    /**
     * Returns whether this test crashed.
     *
     * @return
     *      whether this test crashed
     */
    public abstract boolean didCrash();

    /**
     * Returns whether this test timed out.
     *
     * @return
     *      whether this test timed out
     */
    public abstract boolean didTimeOut();

    /**
     * Sets that this test timed out.
     */
    public abstract void setDidTimeOut();

    /**
     * Returns whether the test passed.
     *
     * @return
     *      whether the test passed
     */
    public boolean didPass() {
        // Tests that crash can't have timed out or have an actual result.
        assert !(didCrash() && didTimeOut());
        assert !(didCrash() && getResultCode() != ResultCode.NO_ACTUAL_RESULT);
        return !didCrash() && !didTimeOut() && getResultCode() == ResultCode.RESULTS_MATCH;
    }

    /**
     * Return the type of the result data.
     *
     * @return
     *      the type of the result data.
     */
    public abstract TestType getType();

    public abstract String getRelativePath();

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
