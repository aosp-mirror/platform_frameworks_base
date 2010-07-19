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

import android.os.Handler;

/**
 * A class that represents a single layout test. It is responsible for running the test,
 * checking its result and creating an AbstractResult object.
 */
public class LayoutTest {

    private String mRelativePath;
    private Handler mCallbackHandler;
    private AbstractResult mResult;

    public LayoutTest(String relativePath, Handler callbackHandler) {
        mRelativePath = relativePath;
        mCallbackHandler = callbackHandler;
    }

    public void run() {
        /** TODO: This is just a stub! */
        mCallbackHandler.obtainMessage(LayoutTestsRunnerThread.MSG_TEST_FINISHED).sendToTarget();
    }

    public AbstractResult getResult() {
        return mResult;
    }

    public String getRelativePath() {
        return mRelativePath;
    }
}