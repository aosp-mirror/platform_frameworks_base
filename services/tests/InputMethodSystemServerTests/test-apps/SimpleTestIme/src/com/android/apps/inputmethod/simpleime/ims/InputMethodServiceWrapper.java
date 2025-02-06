/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.apps.inputmethod.simpleime.ims;

import android.content.res.Configuration;
import android.inputmethodservice.InputMethodService;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import java.util.concurrent.CountDownLatch;

/** Wrapper of {@link InputMethodService} to expose interfaces for testing purpose. */
public class InputMethodServiceWrapper extends InputMethodService {

    private static final String TAG = "InputMethodServiceWrapper";

    /** Last created instance of this wrapper. */
    private static InputMethodServiceWrapper sInstance;

    private boolean mInputViewStarted;

    /**
     * @see #setCountDownLatchForTesting
     */
    private CountDownLatch mCountDownLatchForTesting;

    /** Gets the last created instance of this wrapper, if available. */
    public static InputMethodServiceWrapper getInstance() {
        return sInstance;
    }

    public boolean getCurrentInputViewStarted() {
        return mInputViewStarted;
    }

    /**
     * Sets the latch used to wait for the IME to start showing ({@link #onStartInputView},
     * start hiding ({@link #onFinishInputView}) or receive a configuration change
     * ({@link #onConfigurationChanged}).
     *
     * @param countDownLatchForTesting the latch to wait on.
     */
    public void setCountDownLatchForTesting(CountDownLatch countDownLatchForTesting) {
        mCountDownLatchForTesting = countDownLatchForTesting;
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "onCreate()");
        super.onCreate();
        sInstance = this;
    }

    @Override
    public void onStartInput(EditorInfo info, boolean restarting) {
        Log.i(TAG, "onStartInput() editor=" + dumpEditorInfo(info) + ", restarting=" + restarting);
        super.onStartInput(info, restarting);
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        Log.i(TAG, "onStartInputView() editor=" + dumpEditorInfo(info)
                + ", restarting=" + restarting);
        super.onStartInputView(info, restarting);
        mInputViewStarted = true;
        if (mCountDownLatchForTesting != null) {
            mCountDownLatchForTesting.countDown();
        }
    }

    @Override
    public void onFinishInput() {
        Log.i(TAG, "onFinishInput()");
        super.onFinishInput();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        Log.i(TAG, "onFinishInputView()");
        super.onFinishInputView(finishingInput);
        mInputViewStarted = false;

        if (mCountDownLatchForTesting != null) {
            mCountDownLatchForTesting.countDown();
        }
    }

    @Override
    public void requestHideSelf(int flags) {
        Log.i(TAG, "requestHideSelf() " + flags);
        super.requestHideSelf(flags);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        Log.i(TAG, "onConfigurationChanged() " + newConfig);
        super.onConfigurationChanged(newConfig);

        if (mCountDownLatchForTesting != null) {
            mCountDownLatchForTesting.countDown();
        }
    }

    private String dumpEditorInfo(EditorInfo info) {
        if (info == null) {
            return "null";
        }
        return "EditorInfo{packageName=" + info.packageName
                + " fieldId=" + info.fieldId
                + " hintText=" + info.hintText
                + " privateImeOptions=" + info.privateImeOptions
                + "}";
    }
}
