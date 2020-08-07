/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.simappdialog;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.sysprop.SetupWizardProperties;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import com.google.android.setupcompat.template.FooterBarMixin;
import com.google.android.setupcompat.template.FooterButton;
import com.google.android.setupdesign.GlifLayout;
import com.google.android.setupdesign.util.ThemeResolver;

/**
 * Activity that gives a user the choice to download the SIM app or defer until a later time
 *
 * Will finish with result {@link #DEFER_RESULT} on defer button press or {@link #DOWNLOAD_RESULT}
 * if the download button is pressed
 *
 * Can display the carrier app name if its passed into the intent with key
 * {@link #BUNDLE_KEY_CARRIER_NAME}
 */
public class InstallCarrierAppActivity extends Activity {
    /**
     * Key for the carrier app name that will be displayed as the app to download.  If unset, a
     * default description will be used
     */
    public static final String BUNDLE_KEY_CARRIER_NAME = "carrier_name";
    /** Result code when the defer button is pressed */
    public static final int DEFER_RESULT = 1;
    /** Result code when the download button is pressed */
    public static final int DOWNLOAD_RESULT = 2;

    @Override
    protected void onCreate(Bundle icicle) {
        // Setup theme for aosp/pixel
        setTheme(
                new ThemeResolver.Builder()
                        .setDefaultTheme(R.style.SudThemeGlifV3_Light)
                        .build()
                        .resolve(SetupWizardProperties.theme().orElse(""),
                                /* suppressDayNight= */ false));

        super.onCreate(icicle);
        setContentView(R.layout.install_carrier_app_activity);

        GlifLayout layout = findViewById(R.id.setup_wizard_layout);
        FooterBarMixin mixin = layout.getMixin(FooterBarMixin.class);
        mixin.setSecondaryButton(
                new FooterButton.Builder(this)
                        .setText(R.string.install_carrier_app_defer_action)
                        .setListener(this::onSkipButtonClick)
                        .setButtonType(FooterButton.ButtonType.SKIP)
                        .setTheme(R.style.SudGlifButton_Secondary)
                        .build());

        mixin.setPrimaryButton(
                new FooterButton.Builder(this)
                        .setText(R.string.install_carrier_app_download_action)
                        .setListener(this::onDownloadButtonClick)
                        .setButtonType(FooterButton.ButtonType.OTHER)
                        .setTheme(R.style.SudGlifButton_Primary)
                        .build());


        // Show/hide illo depending on whether one was provided in a resource overlay
        boolean showIllo = getResources().getBoolean(R.bool.show_sim_app_dialog_illo);
        View illoContainer = findViewById(R.id.illo_container);
        illoContainer.setVisibility(showIllo ? View.VISIBLE : View.GONE);

        // Include carrier name in description text if its present in the intent
        Intent intent = getIntent();
        if (intent != null) {
            String carrierName = intent.getStringExtra(BUNDLE_KEY_CARRIER_NAME);
            if (!TextUtils.isEmpty(carrierName)) {
                TextView subtitle = findViewById(R.id.install_carrier_app_description);
                subtitle.setText(getString(R.string.install_carrier_app_description, carrierName));
            }
        }
    }

    @Override
    protected void onApplyThemeResource(Resources.Theme theme, int resid, boolean first) {
        theme.applyStyle(R.style.SetupWizardPartnerResource, true);
        super.onApplyThemeResource(theme, resid, first);
    }

    protected void onSkipButtonClick(View view) {
        finish(DEFER_RESULT);
    }

    protected void onDownloadButtonClick(View view) {
        finish(DOWNLOAD_RESULT);
    }

    private void finish(int resultCode) {
        setResult(resultCode);
        finish();
    }
}
