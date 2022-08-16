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

package com.android.systemui.usb;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.content.res.Resources;
import android.util.Log;

import com.android.systemui.R;

import java.lang.annotation.Retention;

import javax.inject.Inject;

/**
 * USB Audio devices warning dialog messages help class.
 */
public class UsbAudioWarningDialogMessage {
    private static final String TAG = "UsbAudioWarningDialogMessage";

    @Retention(SOURCE)
    @IntDef({TYPE_PERMISSION, TYPE_CONFIRM})
    public @interface DialogType {}
    public static final int TYPE_PERMISSION = 0;
    public static final int TYPE_CONFIRM = 1;

    private int mDialogType;
    private UsbDialogHelper mDialogHelper;

    @Inject
    public UsbAudioWarningDialogMessage() {
    }

    /**
     * Initialize USB audio warning dialog message type and helper class.
     * @param type Dialog type for Activity.
     * @param usbDialogHelper Helper class for getting USB permission and confirm dialogs
     */
    public void init(@DialogType int type, UsbDialogHelper usbDialogHelper) {
        mDialogType = type;
        mDialogHelper = usbDialogHelper;
    }

    boolean hasRecordPermission() {
        return mDialogHelper.packageHasAudioRecordingPermission();
    }

    boolean isUsbAudioDevice() {
        return mDialogHelper.isUsbDevice() && (mDialogHelper.deviceHasAudioCapture()
                || (mDialogHelper.deviceHasAudioPlayback()));
    }

    boolean hasAudioPlayback() {
        return mDialogHelper.deviceHasAudioPlayback();
    }

    boolean hasAudioCapture() {
        return mDialogHelper.deviceHasAudioCapture();
    }

    /**
     * According to USB audio warning dialog matrix table to return warning message id.
     * @return string resId for USB audio warning dialog message, otherwise {ID_NULL}.
     * See usb_audio.md for USB audio Permission and Confirmation warning dialog resource
     * string id matrix table.
     */
    public int getMessageId() {
        if (!mDialogHelper.isUsbDevice()) {
            return getUsbAccessoryPromptId();
        }

        if (hasRecordPermission() && isUsbAudioDevice()) {
            // case# 1, 2, 3
            return R.string.usb_audio_device_prompt;
        } else if (!hasRecordPermission() && isUsbAudioDevice() && hasAudioPlayback()
                && !hasAudioCapture()) {
            // case# 5
            return R.string.usb_audio_device_prompt;
        }

        if (!hasRecordPermission() && isUsbAudioDevice() && hasAudioCapture()) {
            // case# 6,7
            return R.string.usb_audio_device_prompt_warn;
        }

        Log.w(TAG, "Only shows title with empty content description!");
        return Resources.ID_NULL;
    }

    /**
     * Gets prompt dialog title.
     * @return string id for USB prompt dialog title.
     */
    public int getPromptTitleId() {
        return (mDialogType == TYPE_PERMISSION)
                ? R.string.usb_audio_device_permission_prompt_title
                : R.string.usb_audio_device_confirm_prompt_title;
    }

    /**
     * Gets USB Accessory prompt message id.
     * @return string id for USB Accessory prompt message.
     */
    public int getUsbAccessoryPromptId() {
        return (mDialogType == TYPE_PERMISSION)
                ? R.string.usb_accessory_permission_prompt : R.string.usb_accessory_confirm_prompt;
    }
}
