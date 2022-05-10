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

package com.android.systemui.hdmi;

import android.hardware.hdmi.HdmiControlManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.tv.TvBottomSheetActivity;

import javax.inject.Inject;

/**
 * Confirmation dialog shown when Set Menu Language CEC message was received.
 */
public class HdmiCecSetMenuLanguageActivity extends TvBottomSheetActivity
        implements View.OnClickListener {
    private static final String TAG = HdmiCecSetMenuLanguageActivity.class.getSimpleName();

    private final HdmiCecSetMenuLanguageHelper mHdmiCecSetMenuLanguageHelper;

    @Inject
    public HdmiCecSetMenuLanguageActivity(
            HdmiCecSetMenuLanguageHelper hdmiCecSetMenuLanguageHelper) {
        mHdmiCecSetMenuLanguageHelper = hdmiCecSetMenuLanguageHelper;
    }

    @Override
    public final void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addPrivateFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        String languageTag = getIntent().getStringExtra(HdmiControlManager.EXTRA_LOCALE);
        mHdmiCecSetMenuLanguageHelper.setLocale(languageTag);
        if (mHdmiCecSetMenuLanguageHelper.isLocaleDenylisted()) {
            finish();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        CharSequence title = getString(R.string.hdmi_cec_set_menu_language_title,
                mHdmiCecSetMenuLanguageHelper.getLocale().getDisplayLanguage());
        CharSequence text = getString(R.string.hdmi_cec_set_menu_language_description);
        initUI(title, text);
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.bottom_sheet_positive_button) {
            mHdmiCecSetMenuLanguageHelper.acceptLocale();
        } else {
            mHdmiCecSetMenuLanguageHelper.declineLocale();
        }
        finish();
    }

    void initUI(CharSequence title, CharSequence text) {
        TextView titleTextView = findViewById(R.id.bottom_sheet_title);
        TextView contentTextView = findViewById(R.id.bottom_sheet_body);
        ImageView icon = findViewById(R.id.bottom_sheet_icon);
        ImageView secondIcon = findViewById(R.id.bottom_sheet_second_icon);
        Button okButton = findViewById(R.id.bottom_sheet_positive_button);
        Button cancelButton = findViewById(R.id.bottom_sheet_negative_button);

        titleTextView.setText(title);
        contentTextView.setText(text);
        icon.setImageResource(com.android.internal.R.drawable.ic_settings_language);
        secondIcon.setVisibility(View.GONE);

        okButton.setText(R.string.hdmi_cec_set_menu_language_accept);
        okButton.setOnClickListener(this);

        cancelButton.setText(R.string.hdmi_cec_set_menu_language_decline);
        cancelButton.setOnClickListener(this);
        cancelButton.requestFocus();
    }
}
