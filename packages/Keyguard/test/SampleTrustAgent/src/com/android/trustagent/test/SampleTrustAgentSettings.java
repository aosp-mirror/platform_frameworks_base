/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.trustagent.test;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

public class SampleTrustAgentSettings extends Activity implements View.OnClickListener,
        CompoundButton.OnCheckedChangeListener {

    private static final int TRUST_DURATION_MS = 30 * 1000;

    private CheckBox mReportUnlockAttempts;
    private CheckBox mReportDeviceLocked;
    private CheckBox mManagingTrust;
    private TextView mCheckDeviceLockedResult;

    private KeyguardManager mKeyguardManager;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mKeyguardManager = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);

        setContentView(R.layout.sample_trust_agent_settings);

        findViewById(R.id.enable_trust).setOnClickListener(this);
        findViewById(R.id.revoke_trust).setOnClickListener(this);
        findViewById(R.id.crash).setOnClickListener(this);
        findViewById(R.id.check_device_locked).setOnClickListener(this);

        mReportUnlockAttempts = (CheckBox) findViewById(R.id.report_unlock_attempts);
        mReportUnlockAttempts.setOnCheckedChangeListener(this);

        mReportDeviceLocked = (CheckBox) findViewById(R.id.report_device_locked);
        mReportDeviceLocked.setOnCheckedChangeListener(this);

        mManagingTrust = (CheckBox) findViewById(R.id.managing_trust);
        mManagingTrust.setOnCheckedChangeListener(this);

        mCheckDeviceLockedResult = (TextView) findViewById(R.id.check_device_locked_result);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mReportUnlockAttempts.setChecked(SampleTrustAgent.getReportUnlockAttempts(this));
        mManagingTrust.setChecked(SampleTrustAgent.getIsManagingTrust(this));
        updateTrustedState();
    }

    @Override
    public void onClick(View v) {
        int id = v.getId();
        if (id == R.id.enable_trust) {
            SampleTrustAgent.sendGrantTrust(this, "SampleTrustAgent", TRUST_DURATION_MS,
                    false /* initiatedByUser */);
        } else if (id == R.id.revoke_trust) {
            SampleTrustAgent.sendRevokeTrust(this);
        } else if (id == R.id.crash) {
            throw new RuntimeException("crash");
        } else if (id == R.id.check_device_locked) {
            updateTrustedState();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView == mReportUnlockAttempts) {
            SampleTrustAgent.setReportUnlockAttempts(this, isChecked);
        } else if (buttonView == mManagingTrust) {
            SampleTrustAgent.setIsManagingTrust(this, isChecked);
        } else if (buttonView == mReportDeviceLocked) {
            SampleTrustAgent.setReportDeviceLocked(this, isChecked);
        }
    }

    private void updateTrustedState() {
        mCheckDeviceLockedResult.setText(Boolean.toString(
                mKeyguardManager.isDeviceLocked()));
    }
}
