/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static com.android.systemui.accessibility.hearingaid.HearingDevicesUiEventLogger.LAUNCH_SOURCE_A11Y;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.android.systemui.Flags;

import javax.inject.Inject;

/**
 * BroadcastReceiver for handling hearing devices dialog intent.
 *
 * <p> This is not exported. Need to call from framework and use SYSTEM user to send the intent.
 */
public class HearingDevicesDialogReceiver extends BroadcastReceiver {
    public static String ACTION = "com.android.systemui.action.LAUNCH_HEARING_DEVICES_DIALOG";

    private final HearingDevicesDialogManager mDialogManager;
    @Inject
    public HearingDevicesDialogReceiver(
            HearingDevicesDialogManager hearingDevicesDialogManager) {
        mDialogManager = hearingDevicesDialogManager;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Flags.hearingAidsQsTileDialog()) {
            return;
        }

        if (ACTION.equals(intent.getAction())) {
            mDialogManager.showDialog(/* expandable= */ null, LAUNCH_SOURCE_A11Y);
        }
    }
}
