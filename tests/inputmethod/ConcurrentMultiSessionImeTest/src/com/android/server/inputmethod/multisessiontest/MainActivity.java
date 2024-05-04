/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod.multisessiontest;

import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_REQUEST_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_RESULT_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REPLY_IME_HIDDEN;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REPLY_IME_SHOWN;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_IME_STATUS;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.concurrentuser.ConcurrentUserActivityBase;

/**
 * An {@link Activity} to test multiple concurrent session IME.
 */
public final class MainActivity extends ConcurrentUserActivityBase {
    private static final String TAG = ConcurrentMultiUserTest.class.getSimpleName();
    private static final long WAIT_IME_TIMEOUT_MS = 3000;

    private EditText mEditor;
    private InputMethodManager mImm;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "Create MainActivity as user " + getUserId() + " on display "
                + getDisplayId());
        setContentView(R.layout.main_activity);
        mImm = getSystemService(InputMethodManager.class);
        mEditor = requireViewById(R.id.edit_text);
    }

    @Override
    protected Bundle onBundleReceived(Bundle receivedBundle) {
        final int requestCode = receivedBundle.getInt(KEY_REQUEST_CODE);
        Log.v(TAG, "onBundleReceived() with request code:" + requestCode);
        final Bundle replyBundle = new Bundle();
        switch (requestCode) {
            case REQUEST_IME_STATUS:
                replyBundle.putInt(KEY_RESULT_CODE,
                        isMyImeVisible() ? REPLY_IME_SHOWN : REPLY_IME_HIDDEN);
                break;
            default:
                throw new RuntimeException("Received undefined request code:" + requestCode);
        }
        return replyBundle;
    }

    boolean isMyImeVisible() {
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(mEditor);
        return insets == null ? false : insets.isVisible(WindowInsetsCompat.Type.ime());
    }

    void showMyImeAndWait() {
        Log.v(TAG, "showSoftInput");
        runOnUiThread(() -> {
            // requestFocus() must run on UI thread.
            if (!mEditor.requestFocus()) {
                Log.e(TAG, "Failed to focus on mEditor");
                return;
            }
            if (!mImm.showSoftInput(mEditor, /* flags= */ 0)) {
                Log.e(TAG, String.format("Failed to show my IME as user %d, "
                                + "mEditor:focused=%b,hasWindowFocus=%b", getUserId(),
                        mEditor.isFocused(), mEditor.hasWindowFocus()));
            }
        });
        PollingCheck.waitFor(WAIT_IME_TIMEOUT_MS, () -> isMyImeVisible(),
                String.format("My IME (user %d) didn't show up", getUserId()));
    }
}
