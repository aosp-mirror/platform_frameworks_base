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
 * A dummy class representing test that crashed.
 *
 * TODO: All the methods regarding expected results need implementing.
 */
public class CrashedDummyResult extends AbstractResult {
    String mRelativePath;

    public CrashedDummyResult(String relativePath) {
        mRelativePath = relativePath;
    }

    @Override
    public byte[] getActualImageResult() {
        return null;
    }

    @Override
    public String getActualTextResult() {
        return null;
    }

    @Override
    public Bundle getBundle() {
        /** TODO:  */
        return null;
    }

    @Override
    public String getDiffAsHtml() {
        /** TODO: Probably show at least expected results */
        return "Ooops, I crashed...";
    }

    @Override
    public String getRelativePath() {
        return mRelativePath;
    }

    @Override
    public ResultCode getResultCode() {
        return ResultCode.NO_ACTUAL_RESULT;
    }

    @Override
    public boolean didCrash() {
        return true;
    }

    @Override
    public boolean didTimeOut() {
        return false;
    }

    @Override
    public void setDidTimeOut() {
        /** This method is not applicable for this type of result */
        assert false;
    }

    @Override
    public TestType getType() {
        return null;
    }

    @Override
    public void obtainActualResults(WebView webview, Message resultObtainedMsg) {
        /** This method is not applicable for this type of result */
        assert false;
    }

    @Override
    public void setExpectedImageResult(byte[] expectedResult) {
        /** TODO */
    }

    @Override
    public void setExpectedTextResult(String expectedResult) {
        /** TODO */
    }

    @Override
    public String getExpectedImageResultPath() {
        /** TODO */
        return null;
    }

    @Override
    public String getExpectedTextResultPath() {
        /** TODO */
        return null;
    }

    @Override
    public void setExpectedImageResultPath(String relativePath) {
        /** TODO */
    }

    @Override
    public void setExpectedTextResultPath(String relativePath) {
        /** TODO */
    }
}
