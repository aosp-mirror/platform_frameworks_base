/*
 * Copyright (C) 2011 The Android Open Source Project
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

import com.android.systemui.R;

/**
 * Dialog shown to confirm the package to start when a USB device or accessory is attached and there
 * is only one package that claims to handle this USB device or accessory.
 */
public class UsbConfirmActivity extends UsbDialogActivity {

    @Override
    protected void onResume() {
        super.onResume();
        final int strId;
        boolean useRecordWarning = false;
        if (mDialogHelper.isUsbDevice()) {
            useRecordWarning = mDialogHelper.deviceHasAudioCapture()
                    && !mDialogHelper.packageHasAudioRecordingPermission();
            strId = useRecordWarning
                    ? R.string.usb_device_confirm_prompt_warn
                    : R.string.usb_device_confirm_prompt;
        } else {
            // UsbAccessory case
            strId = R.string.usb_accessory_confirm_prompt;
        }
        setAlertParams(strId);
        // Only show the "always use" checkbox if there is no USB/Record warning
        if (!useRecordWarning) {
            addAlwaysUseCheckbox();
        }
        setupAlert();
    }

    @Override
    void onConfirm() {
        mDialogHelper.grantUidAccessPermission();
        if (isAlwaysUseChecked()) {
            mDialogHelper.setDefaultPackage();
        } else {
            mDialogHelper.clearDefaultPackage();
        }
        mDialogHelper.confirmDialogStartActivity();
        finish();
    }
}
