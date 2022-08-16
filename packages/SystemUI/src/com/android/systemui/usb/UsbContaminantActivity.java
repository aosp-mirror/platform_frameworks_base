/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.usb;

import android.app.Activity;
import android.content.Intent;
import android.hardware.usb.ParcelableUsbPort;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;

/**
 * Activity that alerts the user when contaminant is detected on USB port.
 */
public class UsbContaminantActivity extends Activity implements View.OnClickListener {
    private static final String TAG = "UsbContaminantActivity";

    private UsbPort mUsbPort;
    private TextView mLearnMore;
    private TextView mGotIt;
    private TextView mEnableUsb;
    private TextView mTitle;
    private TextView mMessage;

    @Override
    public void onCreate(Bundle icicle) {
        Window window = getWindow();
        window.addSystemFlags(WindowManager.LayoutParams
                .SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        super.onCreate(icicle);
        setContentView(R.layout.contaminant_dialog);

        Intent intent = getIntent();
        ParcelableUsbPort port = intent.getParcelableExtra(UsbManager.EXTRA_PORT);
        mUsbPort = port.getUsbPort(getSystemService(UsbManager.class));

        mLearnMore = findViewById(R.id.learnMore);
        mEnableUsb = findViewById(R.id.enableUsb);
        mGotIt = findViewById(R.id.gotIt);
        mTitle = findViewById(R.id.title);
        mMessage = findViewById(R.id.message);

        mTitle.setText(getString(R.string.usb_contaminant_title));
        mMessage.setText(getString(R.string.usb_contaminant_message));
        mEnableUsb.setText(getString(R.string.usb_disable_contaminant_detection));
        mGotIt.setText(getString(R.string.got_it));
        mLearnMore.setText(getString(R.string.learn_more));
        if (getResources().getBoolean(
                com.android.internal.R.bool.config_settingsHelpLinksEnabled)) {
            mLearnMore.setVisibility(View.VISIBLE);
        }

        mEnableUsb.setOnClickListener(this);
        mGotIt.setOnClickListener(this);
        mLearnMore.setOnClickListener(this);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
        super.onWindowAttributesChanged(params);
    }

    @Override
    public void onClick(View v) {
        if (v == mEnableUsb) {
            try {
                mUsbPort.enableContaminantDetection(false);
                Toast.makeText(this, R.string.usb_port_enabled,
                    Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Unable to notify Usb service", e);
            }
        } else if (v == mLearnMore) {
            final Intent intent = new Intent();
            intent.setClassName("com.android.settings",
                    "com.android.settings.HelpTrampoline");
            intent.putExtra(Intent.EXTRA_TEXT,
                    "help_url_usb_contaminant_detected");
            startActivity(intent);
        }
        finish();
    }
}
