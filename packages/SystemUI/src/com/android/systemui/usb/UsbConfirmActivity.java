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

import android.content.res.Resources;
import android.os.Bundle;

import javax.inject.Inject;

/**
 * Dialog shown to confirm the package to start when a USB device or accessory is attached and there
 * is only one package that claims to handle this USB device or accessory.
 */
public class UsbConfirmActivity extends UsbDialogActivity {

    private UsbAudioWarningDialogMessage mUsbConfirmMessageHandler;

    @Inject
    public UsbConfirmActivity(UsbAudioWarningDialogMessage usbAudioWarningDialogMessage) {
        mUsbConfirmMessageHandler = usbAudioWarningDialogMessage;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mUsbConfirmMessageHandler.init(UsbAudioWarningDialogMessage.TYPE_CONFIRM, mDialogHelper);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Only show the "always use" checkbox if there is no USB/Record warning
        final boolean useRecordWarning = mDialogHelper.isUsbDevice()
                && (mDialogHelper.deviceHasAudioCapture()
                && !mDialogHelper.packageHasAudioRecordingPermission());

        final int titleId = mUsbConfirmMessageHandler.getPromptTitleId();
        final String title = getString(titleId, mDialogHelper.getAppName(),
                mDialogHelper.getDeviceDescription());
        final int messageId = mUsbConfirmMessageHandler.getMessageId();
        String message = (messageId != Resources.ID_NULL)
                ? getString(messageId, mDialogHelper.getAppName(),
                mDialogHelper.getDeviceDescription()) : null;
        setAlertParams(title, message);
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
