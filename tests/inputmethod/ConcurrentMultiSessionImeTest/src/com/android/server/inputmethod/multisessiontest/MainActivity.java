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

import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_DISPLAY_ID;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_EDITTEXT_CENTER;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_IME_SHOWN;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.KEY_REQUEST_CODE;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_DISPLAY_ID;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_EDITTEXT_POSITION;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_HIDE_IME;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_IME_STATUS;
import static com.android.server.inputmethod.multisessiontest.TestRequestConstants.REQUEST_SHOW_IME;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.EditText;

import androidx.annotation.WorkerThread;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v(TAG, "Create MainActivity as user "
                + Process.myUserHandle().getIdentifier() + " on display "
                + getDisplay().getDisplayId());
        setContentView(R.layout.main_activity);
        mEditor = requireViewById(R.id.edit_text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.v(TAG, "onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.v(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.v(TAG, "onResume");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        Log.v(TAG, "onWindowFocusChanged " + hasFocus);
    }

    @Override
    @WorkerThread
    protected Bundle onBundleReceived(Bundle receivedBundle) {
        final int requestCode = receivedBundle.getInt(KEY_REQUEST_CODE);
        Log.v(TAG, "onBundleReceived() with request code:" + requestCode);
        final Bundle replyBundle = new Bundle();
        switch (requestCode) {
            case REQUEST_IME_STATUS:
                replyBundle.putBoolean(KEY_IME_SHOWN, isMyImeVisible());
                break;
            case REQUEST_SHOW_IME:
                showMyImeAndWait();
                replyBundle.putBoolean(KEY_IME_SHOWN, isMyImeVisible());
                break;
            case REQUEST_HIDE_IME:
                hideMyImeAndWait();
                replyBundle.putBoolean(KEY_IME_SHOWN, isMyImeVisible());
                break;
            case REQUEST_EDITTEXT_POSITION:
                replyBundle.putFloatArray(KEY_EDITTEXT_CENTER, getEditTextCenter());
                break;
            case REQUEST_DISPLAY_ID:
                replyBundle.putInt(KEY_DISPLAY_ID, getDisplay().getDisplayId());
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

    float[] getEditTextCenter() {
        final float editTextCenterX = mEditor.getX() + 0.5f * mEditor.getWidth();
        final float editTextCenterY = mEditor.getY() + 0.5f * mEditor.getHeight();
        return new float[]{editTextCenterX, editTextCenterY};
    }

    @WorkerThread
    void showMyImeAndWait() {
        runOnUiThread(() -> {
            // View#requestFocus() and WindowInsetsControllerCompat#show() must run on UI thread.
            if (!mEditor.requestFocus()) {
                Log.e(TAG, "Failed to focus on mEditor");
                return;
            }
            // Compared to mImm.showSoftInput(), the call below is the recommended way to show the
            // keyboard because it is guaranteed to be scheduled after the window is focused.
            Log.v(TAG, "showSoftInput");
            WindowCompat.getInsetsController(getWindow(), mEditor).show(
                    WindowInsetsCompat.Type.ime());
        });
        PollingCheck.waitFor(WAIT_IME_TIMEOUT_MS, () -> isMyImeVisible(),
                String.format("%s: My IME (user %d) didn't show up", TAG,
                        Process.myUserHandle().getIdentifier()));
    }

    @WorkerThread
    void hideMyImeAndWait() {
        runOnUiThread(() -> {
            Log.v(TAG, "hideSoftInput");
            // WindowInsetsControllerCompat#hide() must run on UI thread.
            WindowCompat.getInsetsController(getWindow(), mEditor)
                    .hide(WindowInsetsCompat.Type.ime());
        });
        PollingCheck.waitFor(WAIT_IME_TIMEOUT_MS, () -> !isMyImeVisible(),
                String.format("%s: My IME (user %d) is still shown", TAG,
                        Process.myUserHandle().getIdentifier()));
    }
}
