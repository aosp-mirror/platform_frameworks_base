/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static java.util.Objects.requireNonNull;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.systemui.res.R;

/**
 * Lightweight activity for editing text clipboard contents
 */
public class EditTextActivity extends Activity
        implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "EditTextActivity";

    private EditText mEditText;
    private ClipboardManager mClipboardManager;
    private TextView mAttribution;
    private boolean mSensitive;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.clipboard_edit_text_activity);
        findViewById(R.id.done_button).setOnClickListener((v) -> saveToClipboard());
        mEditText = findViewById(R.id.edit_text);
        mAttribution = findViewById(R.id.attribution);
        mClipboardManager = requireNonNull(getSystemService(ClipboardManager.class));

        findViewById(R.id.editor_root).setOnApplyWindowInsetsListener(
                new View.OnApplyWindowInsetsListener() {
                    @NonNull
                    @Override
                    public WindowInsets onApplyWindowInsets(@NonNull View view,
                            @NonNull WindowInsets windowInsets) {
                        Insets insets = windowInsets.getInsets(
                                WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                        ViewGroup.MarginLayoutParams layoutParams =
                                (ViewGroup.MarginLayoutParams) view.getLayoutParams();
                        layoutParams.leftMargin = insets.left;
                        layoutParams.bottomMargin = insets.bottom;
                        layoutParams.rightMargin = insets.right;
                        layoutParams.topMargin = insets.top;
                        view.setLayoutParams(layoutParams);
                        return WindowInsets.CONSUMED;
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        ClipData clip = mClipboardManager.getPrimaryClip();
        if (clip == null) {
            finish();
            return;
        }
        PackageManager pm = getApplicationContext().getPackageManager();
        try {
            CharSequence label = pm.getApplicationLabel(
                    pm.getApplicationInfo(mClipboardManager.getPrimaryClipSource(),
                            PackageManager.ApplicationInfoFlags.of(0)));
            mAttribution.setText(getResources().getString(R.string.clipboard_edit_source, label));
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package not found: " + mClipboardManager.getPrimaryClipSource(), e);
        }
        mEditText.setText(clip.getItemAt(0).getText().toString());
        mEditText.requestFocus();
        mEditText.setSelection(0);
        mSensitive = clip.getDescription().getExtras() != null
                && clip.getDescription().getExtras()
                .getBoolean(ClipDescription.EXTRA_IS_SENSITIVE);
        mClipboardManager.addPrimaryClipChangedListener(this);
    }

    @Override
    protected void onPause() {
        mClipboardManager.removePrimaryClipChangedListener(this);
        super.onPause();
    }

    @Override // ClipboardManager.OnPrimaryClipChangedListener
    public void onPrimaryClipChanged() {
        hideIme();
        finish();
    }

    private void saveToClipboard() {
        hideIme();
        Editable editedText = mEditText.getText();
        editedText.clearSpans();
        ClipData clip = ClipData.newPlainText("text", editedText);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, mSensitive);
        clip.getDescription().setExtras(extras);
        mClipboardManager.setPrimaryClip(clip);
        finish();
    }

    private void hideIme() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(mEditText.getWindowToken(), 0);
    }
}
