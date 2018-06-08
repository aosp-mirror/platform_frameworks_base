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
import android.os.Bundle;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.setupwizardlib.util.WizardManagerHelper;

/**
 * Activity that gives a user the choice to download the SIM app or defer until a later time
 *
 * Will finish with result {@link #DEFER_RESULT} on defer button press or {@link #DOWNLOAD_RESULT}
 * if the download button is pressed
 *
 * Can display the carrier app name if its passed into the intent with key
 * {@link #BUNDLE_KEY_CARRIER_NAME}
 */
public class InstallCarrierAppActivity extends Activity implements View.OnClickListener {
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
                WizardManagerHelper.getThemeRes(
                        SystemProperties.get("setupwizard.theme"),
                        R.style.SuwThemeGlif_Light
                )
        );

        super.onCreate(icicle);
        setContentView(R.layout.install_carrier_app_activity);

        Button notNowButton = findViewById(R.id.skip_button);
        notNowButton.setOnClickListener(this);

        Button downloadButton = findViewById(R.id.download_button);
        downloadButton.setOnClickListener(this);

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
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.skip_button:
                finish(DEFER_RESULT);
                break;
            case R.id.download_button:
                finish(DOWNLOAD_RESULT);
                break;
        }
    }

    private void finish(int resultCode) {
        setResult(resultCode);
        finish();
    }
}
