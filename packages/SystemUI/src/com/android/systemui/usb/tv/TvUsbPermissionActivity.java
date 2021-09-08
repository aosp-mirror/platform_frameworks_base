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

import com.android.systemui.R;

/**
 * Dialog shown when a package requests access to a USB device or accessory on TVs.
 */
public class TvUsbPermissionActivity extends TvUsbDialogActivity {
    private static final String TAG = TvUsbPermissionActivity.class.getSimpleName();

    private boolean mPermissionGranted = false;

    @Override
    public void onResume() {
        super.onResume();
        final int strId;
        if (mDialogHelper.isUsbDevice()) {
            boolean useRecordWarning = mDialogHelper.deviceHasAudioCapture()
                    && !mDialogHelper.packageHasAudioRecordingPermission();
            strId = useRecordWarning
                    ? R.string.usb_device_permission_prompt_warn
                    : R.string.usb_device_permission_prompt;
        } else {
            // UsbAccessory case
            strId = R.string.usb_accessory_permission_prompt;
        }
        CharSequence text = getString(strId, mDialogHelper.getAppName(),
                mDialogHelper.getDeviceDescription());
        initUI(mDialogHelper.getAppName(), text);
    }

    @Override
    protected void onPause() {
        if (isFinishing()) {
            mDialogHelper.sendPermissionDialogResponse(mPermissionGranted);
        }
        super.onPause();
    }

    @Override
    void onConfirm() {
        mDialogHelper.grantUidAccessPermission();
        mPermissionGranted = true;
        finish();
    }
}
