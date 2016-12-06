/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.app.Fragment;
import android.app.FragmentManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;

import com.android.documentsui.model.DocumentInfo;

/**
 * Display document title editor and save button.
 */
public class SaveFragment extends Fragment {
    public static final String TAG = "SaveFragment";

    private DocumentInfo mReplaceTarget;
    private EditText mDisplayName;
    private Button mSave;
    private ProgressBar mProgress;
    private boolean mIgnoreNextEdit;

    private static final String EXTRA_MIME_TYPE = "mime_type";
    private static final String EXTRA_DISPLAY_NAME = "display_name";

    public static void show(FragmentManager fm, String mimeType, String displayName) {
        final Bundle args = new Bundle();
        args.putString(EXTRA_MIME_TYPE, mimeType);
        args.putString(EXTRA_DISPLAY_NAME, displayName);

        final SaveFragment fragment = new SaveFragment();
        fragment.setArguments(args);

        final FragmentTransaction ft = fm.beginTransaction();
        ft.replace(R.id.container_save, fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    public static SaveFragment get(FragmentManager fm) {
        return (SaveFragment) fm.findFragmentByTag(TAG);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        final Context context = inflater.getContext();

        final View view = inflater.inflate(R.layout.fragment_save, container, false);

        final ImageView icon = (ImageView) view.findViewById(android.R.id.icon);
        icon.setImageDrawable(
                IconUtils.loadMimeIcon(context, getArguments().getString(EXTRA_MIME_TYPE)));

        mDisplayName = (EditText) view.findViewById(android.R.id.title);
        mDisplayName.addTextChangedListener(mDisplayNameWatcher);
        mDisplayName.setText(getArguments().getString(EXTRA_DISPLAY_NAME));
        mDisplayName.setOnKeyListener(
                new View.OnKeyListener() {
                    @Override
                    public boolean onKey(View v, int keyCode, KeyEvent event) {
                        // Only handle key-down events. This is simpler, consistent with most other
                        // UIs, and enables the handling of repeated key events from holding down a
                        // key.
                        if (event.getAction() != KeyEvent.ACTION_DOWN) {
                            return false;
                        }

                        // Returning false in this method will bubble the event up to
                        // {@link BaseActivity#onKeyDown}. In order to prevent backspace popping
                        // documents once the textView is empty, we are going to trap it here.
                        if (keyCode == KeyEvent.KEYCODE_DEL
                                && TextUtils.isEmpty(mDisplayName.getText())) {
                            return true;
                        }

                        if (keyCode == KeyEvent.KEYCODE_ENTER && mSave.isEnabled()) {
                            performSave();
                            return true;
                        }
                        return false;
                    }
                });

        mSave = (Button) view.findViewById(android.R.id.button1);
        mSave.setOnClickListener(mSaveListener);
        mSave.setEnabled(false);

        mProgress = (ProgressBar) view.findViewById(android.R.id.progress);

        return view;
    }

    private TextWatcher mDisplayNameWatcher = new TextWatcher() {
        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (mIgnoreNextEdit) {
                mIgnoreNextEdit = false;
            } else {
                mReplaceTarget = null;
            }
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            // ignored
        }

        @Override
        public void afterTextChanged(Editable s) {
            // ignored
        }
    };

    private View.OnClickListener mSaveListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            performSave();
        }

    };

    private void performSave() {
        final DocumentsActivity activity = DocumentsActivity.get(SaveFragment.this);
        if (mReplaceTarget != null) {
            activity.onSaveRequested(mReplaceTarget);
        } else {
            final String mimeType = getArguments().getString(EXTRA_MIME_TYPE);
            final String displayName = mDisplayName.getText().toString();
            activity.onSaveRequested(mimeType, displayName);
        }
    }

    /**
     * Set given document as target for in-place writing if user hits save
     * without changing the filename. Can be set to {@code null} if user
     * navigates outside the target directory.
     */
    public void setReplaceTarget(DocumentInfo replaceTarget) {
        mReplaceTarget = replaceTarget;

        if (mReplaceTarget != null) {
            getArguments().putString(EXTRA_DISPLAY_NAME, replaceTarget.displayName);
            mIgnoreNextEdit = true;
            mDisplayName.setText(replaceTarget.displayName);
        }
    }

    public void prepareForDirectory(DocumentInfo cwd) {
        setSaveEnabled(cwd != null && cwd.isCreateSupported());
    }

    private void setSaveEnabled(boolean enabled) {
        mSave.setEnabled(enabled);
    }

    public void setPending(boolean pending) {
        mSave.setVisibility(pending ? View.INVISIBLE : View.VISIBLE);
        mProgress.setVisibility(pending ? View.VISIBLE : View.GONE);
    }
}
