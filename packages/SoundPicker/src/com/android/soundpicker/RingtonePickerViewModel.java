/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.soundpicker;

import android.annotation.StringRes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * View model for {@link RingtonePickerActivity}.
 */
public final class RingtonePickerViewModel {

    @StringRes
    static int getTitleByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return com.android.internal.R.string.ringtone_picker_title_alarm;
            case RingtoneManager.TYPE_NOTIFICATION:
                return com.android.internal.R.string.ringtone_picker_title_notification;
            default:
                return com.android.internal.R.string.ringtone_picker_title;
        }
    }

    static Uri getDefaultItemUriByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return Settings.System.DEFAULT_ALARM_ALERT_URI;
            case RingtoneManager.TYPE_NOTIFICATION:
                return Settings.System.DEFAULT_NOTIFICATION_URI;
            default:
                return Settings.System.DEFAULT_RINGTONE_URI;
        }
    }

    @StringRes
    static int getAddNewItemTextByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return R.string.add_alarm_text;
            case RingtoneManager.TYPE_NOTIFICATION:
                return R.string.add_notification_text;
            default:
                return R.string.add_ringtone_text;
        }
    }

    @StringRes
    static int getDefaultRingtoneItemTextByType(int ringtoneType) {
        switch (ringtoneType) {
            case RingtoneManager.TYPE_ALARM:
                return R.string.alarm_sound_default;
            case RingtoneManager.TYPE_NOTIFICATION:
                return R.string.notification_sound_default;
            default:
                return R.string.ringtone_default;
        }
    }
}
