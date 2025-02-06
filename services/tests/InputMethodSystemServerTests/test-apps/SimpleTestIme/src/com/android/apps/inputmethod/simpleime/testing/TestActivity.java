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

package com.android.apps.inputmethod.simpleime.testing;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

/**
 * A special activity for testing purpose.
 *
 * <p>This is used when the instruments package is SimpleTestIme, as the Intent needs to be started
 * in the instruments package. More details see {@link
 * Instrumentation#startActivitySync(Intent)}.</>
 */
public final class TestActivity extends Activity {

    private static final String TAG = "TestActivity";
    private static WeakReference<TestActivity> sLastCreatedInstance = new WeakReference<>(null);

    /**
     * Start a new test activity with an editor and wait for it to begin running before returning.
     *
     * @param instrumentation application instrumentation
     * @return the newly started activity
     */
    @NonNull
    public static TestActivity startSync(@NonNull Instrumentation instrumentation) {
        final var intent = new Intent()
                .setAction(Intent.ACTION_MAIN)
                .setClass(instrumentation.getTargetContext(), TestActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return (TestActivity) instrumentation.startActivitySync(intent);
    }

    private EditText mEditText;

    public EditText getEditText() {
        return mEditText;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        final var rootView = new LinearLayout(this);
        mEditText = new EditText(this);
        mEditText.setHint("editText");
        rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        rootView.setFitsSystemWindows(true);
        setContentView(rootView);
        mEditText.requestFocus();
        sLastCreatedInstance = new WeakReference<>(this);
    }

    /** Get the last created TestActivity instance, if available. */
    @Nullable
    public static TestActivity getLastCreatedInstance() {
        return sLastCreatedInstance.get();
    }

    /** Shows soft keyboard via InputMethodManager. */
    public boolean showImeWithInputMethodManager(int flags) {
        final var imm = getSystemService(InputMethodManager.class);
        final boolean result = imm.showSoftInput(mEditText, flags);
        Log.i(TAG, "showIme() via InputMethodManager, result=" + result);
        return result;
    }

    /** Shows soft keyboard via WindowInsetsController. */
    public void showImeWithWindowInsetsController() {
        final var controller = mEditText.getWindowInsetsController();
        controller.show(WindowInsets.Type.ime());
        Log.i(TAG, "showIme() via WindowInsetsController");
    }

    /** Hides soft keyboard via InputMethodManager. */
    public boolean hideImeWithInputMethodManager(int flags) {
        final var imm = getSystemService(InputMethodManager.class);
        final boolean result = imm.hideSoftInputFromWindow(mEditText.getWindowToken(), flags);
        Log.i(TAG, "hideIme() via InputMethodManager, result=" + result);
        return result;
    }

    /** Hides soft keyboard via WindowInsetsController. */
    public void hideImeWithWindowInsetsController() {
        final var controller = mEditText.getWindowInsetsController();
        controller.hide(WindowInsets.Type.ime());
        Log.i(TAG, "hideIme() via WindowInsetsController");
    }
}
