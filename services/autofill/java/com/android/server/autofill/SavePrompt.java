/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill;

import android.content.Context;
import android.graphics.Color;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;
import android.widget.TextView;
import android.view.View;

/**
 * Autofill Save Prompt
 */
final class SavePrompt extends RelativeLayout {
    public interface OnSaveListener {
        void onSaveClick();

        void onCancelClick();
    }

    private final TextView mTextView;
    private final TextView mNoButton;
    private final TextView mYesButton;
    private final OnSaveListener mListener;

    SavePrompt(Context context, OnSaveListener listener) {
        super(context);
        mListener = listener;
        setBackgroundColor(Color.YELLOW);

        // TODO(b/33197203): move layout to XML
        mTextView = new TextView(context);
        final LayoutParams textParams = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        textParams.setMargins(50, 25, 50, 0);
        mTextView.setLayoutParams(textParams);
        // TODO(b/33197203): use R.string once final wording is done
        mTextView.setText("Save for autofill?");
        mTextView.setId(View.generateViewId());

        mNoButton = new TextView(context);
        // TODO(b/33197203): use R.string once final wording is done
        mNoButton.setText("No thanks");
        mNoButton.setBackgroundColor(Color.TRANSPARENT);
        mNoButton.setAllCaps(true);
        mNoButton.setOnClickListener((v) -> {
            mListener.onCancelClick();
        });

        mYesButton = new TextView(context);
        // TODO(b/33197203): use R.string once final wording is done
        mYesButton.setText("Save");
        mYesButton.setBackgroundColor(Color.TRANSPARENT);
        mYesButton.setId(View.generateViewId());
        mYesButton.setAllCaps(true);
        mYesButton.setOnClickListener((v) -> {
            mListener.onSaveClick();
        });

        addView(mTextView);
        addView(mNoButton);
        addView(mYesButton);

        final LayoutParams yesLayoutParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        yesLayoutParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        yesLayoutParams.addRule(RelativeLayout.BELOW, mTextView.getId());
        yesLayoutParams.setMargins(25, 25, 50, 25);
        mYesButton.setLayoutParams(yesLayoutParams);
        final LayoutParams noLayoutParams = new LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        noLayoutParams.addRule(RelativeLayout.LEFT_OF, mYesButton.getId());
        noLayoutParams.addRule(RelativeLayout.BELOW, mTextView.getId());
        noLayoutParams.setMargins(50, 25, 25, 25);
        mNoButton.setLayoutParams(noLayoutParams);
    }
}
