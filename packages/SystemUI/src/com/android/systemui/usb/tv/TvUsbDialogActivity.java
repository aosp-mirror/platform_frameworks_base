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

package com.android.systemui.usb.tv;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.tv.TvBottomSheetActivity;
import com.android.systemui.usb.UsbDialogHelper;

abstract class TvUsbDialogActivity extends TvBottomSheetActivity implements View.OnClickListener {
    private static final String TAG = TvUsbDialogActivity.class.getSimpleName();
    UsbDialogHelper mDialogHelper;

    @Override
    public final void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addPrivateFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);
        try {
            mDialogHelper = new UsbDialogHelper(getApplicationContext(), getIntent());
        } catch (IllegalStateException e) {
            Log.e(TAG, "unable to initialize", e);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDialogHelper.registerUsbDisconnectedReceiver(this);
    }

    @Override
    protected void onPause() {
        if (mDialogHelper != null) {
            mDialogHelper.unregisterUsbDisconnectedReceiver(this);
        }
        super.onPause();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.bottom_sheet_positive_button) {
            onConfirm();
        } else {
            finish();
        }
    }

    /**
     * Called when the ok button is clicked.
     */
    abstract void onConfirm();

    void initUI(CharSequence title, CharSequence text) {
        TextView titleTextView = findViewById(R.id.bottom_sheet_title);
        TextView contentTextView = findViewById(R.id.bottom_sheet_body);
        ImageView icon = findViewById(R.id.bottom_sheet_icon);
        ImageView secondIcon = findViewById(R.id.bottom_sheet_second_icon);
        Button okButton = findViewById(R.id.bottom_sheet_positive_button);
        Button cancelButton = findViewById(R.id.bottom_sheet_negative_button);

        titleTextView.setText(title);
        contentTextView.setText(text);
        icon.setImageResource(com.android.internal.R.drawable.ic_usb_48dp);
        secondIcon.setVisibility(View.GONE);
        okButton.setText(android.R.string.ok);
        okButton.setOnClickListener(this);

        cancelButton.setText(android.R.string.cancel);
        cancelButton.setOnClickListener(this);
        cancelButton.requestFocus();
    }
}
