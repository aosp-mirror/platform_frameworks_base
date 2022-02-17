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

package com.android.companiondevicemanager;

import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import static java.util.Objects.requireNonNull;

import android.app.Activity;
import android.companion.SystemDataTransferRequest;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.Html;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

/**
 * This activity manages the UI of companion device data transfer.
 */
public class CompanionDeviceDataTransferActivity extends Activity {

    private static final String LOG_TAG = CompanionDeviceDataTransferActivity.class.getSimpleName();

    // UI -> SystemDataTransferProcessor
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED = 0;
    private static final int RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED = 1;
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_REQUEST = "system_data_transfer_request";
    private static final String EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER =
            "system_data_transfer_result_receiver";

    private SystemDataTransferRequest mRequest;
    private ResultReceiver mCdmServiceReceiver;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(LOG_TAG, "Creating UI for data transfer confirmation.");

        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        setContentView(R.layout.data_transfer_confirmation);

        TextView titleView = findViewById(R.id.title);
        TextView summaryView = findViewById(R.id.summary);
        ListView listView = findViewById(R.id.device_list);
        listView.setVisibility(View.GONE);
        Button allowButton = findViewById(R.id.btn_positive);
        Button disallowButton = findViewById(R.id.btn_negative);

        final Intent intent = getIntent();
        mRequest = intent.getParcelableExtra(EXTRA_SYSTEM_DATA_TRANSFER_REQUEST);
        mCdmServiceReceiver = intent.getParcelableExtra(EXTRA_SYSTEM_DATA_TRANSFER_RESULT_RECEIVER);

        requireNonNull(mRequest);
        requireNonNull(mCdmServiceReceiver);

        if (mRequest.isPermissionSyncAllPackages()
                || !mRequest.getPermissionSyncPackages().isEmpty()) {
            titleView.setText(Html.fromHtml(getString(
                    R.string.permission_sync_confirmation_title), 0));
            summaryView.setText(getString(R.string.permission_sync_summary));
            allowButton.setOnClickListener(v -> allow());
            disallowButton.setOnClickListener(v -> disallow());
        }
    }

    private void allow() {
        Log.i(LOG_TAG, "allow()");

        sendDataToReceiver(RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED);

        setResultAndFinish(RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED);
    }

    private void disallow() {
        Log.i(LOG_TAG, "disallow()");

        sendDataToReceiver(RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED);

        setResultAndFinish(RESULT_CODE_SYSTEM_DATA_TRANSFER_DISALLOWED);
    }

    private void sendDataToReceiver(int cdmResultCode) {
        Bundle data = new Bundle();
        data.putParcelable(EXTRA_SYSTEM_DATA_TRANSFER_REQUEST, mRequest);
        mCdmServiceReceiver.send(cdmResultCode, data);
    }

    private void setResultAndFinish(int cdmResultCode) {
        setResult(cdmResultCode == RESULT_CODE_SYSTEM_DATA_TRANSFER_ALLOWED
                ? RESULT_OK : RESULT_CANCELED);
        finish();
    }
}
