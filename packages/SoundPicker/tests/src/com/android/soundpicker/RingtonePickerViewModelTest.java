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

import static junit.framework.Assert.assertEquals;

import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class RingtonePickerViewModelTest {

    @Test
    public void testDefaultItemUri_withNotificationIntent_returnDefaultNotificationUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withAlarmIntent_returnDefaultAlarmUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(RingtoneManager.TYPE_ALARM);
        assertEquals(Settings.System.DEFAULT_ALARM_ALERT_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withRingtoneIntent_returnDefaultRingtoneUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(RingtoneManager.TYPE_RINGTONE);
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, uri);
    }

    @Test
    public void testDefaultItemUri_withInvalidRingtoneType_returnDefaultRingtoneUri() {
        Uri uri = RingtonePickerViewModel.getDefaultItemUriByType(-1);
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, uri);
    }

    @Test
    public void testTitle_withNotificationRingtoneType_returnRingtoneNotificationTitle() {
        int title = RingtonePickerViewModel.getTitleByType(RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(com.android.internal.R.string.ringtone_picker_title_notification, title);
    }

    @Test
    public void testTitle_withAlarmRingtoneType_returnRingtoneAlarmTitle() {
        int title = RingtonePickerViewModel.getTitleByType(RingtoneManager.TYPE_ALARM);
        assertEquals(com.android.internal.R.string.ringtone_picker_title_alarm, title);
    }

    @Test
    public void testTitle_withInvalidRingtoneType_returnDefaultRingtoneTitle() {
        int title = RingtonePickerViewModel.getTitleByType(-1);
        assertEquals(com.android.internal.R.string.ringtone_picker_title, title);
    }

    @Test
    public void testAddNewItemText_withAlarmType_returnAlarmAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_ALARM);
        assertEquals(R.string.add_alarm_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withNotificationType_returnNotificationAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.add_notification_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withRingtoneType_returnRingtoneAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(
                RingtoneManager.TYPE_RINGTONE);
        assertEquals(R.string.add_ringtone_text, addNewItemTextResId);
    }

    @Test
    public void testAddNewItemText_withInvalidType_returnRingtoneAddItemText() {
        int addNewItemTextResId = RingtonePickerViewModel.getAddNewItemTextByType(-1);
        assertEquals(R.string.add_ringtone_text, addNewItemTextResId);
    }

    @Test
    public void testDefaultItemText_withNotificationType_returnNotificationDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.notification_sound_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withAlarmType_returnAlarmDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_NOTIFICATION);
        assertEquals(R.string.notification_sound_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withRingtoneType_returnRingtoneDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                RingtoneManager.TYPE_RINGTONE);
        assertEquals(R.string.ringtone_default, defaultRingtoneItemText);
    }

    @Test
    public void testDefaultItemText_withInvalidType_returnRingtoneDefaultItemText() {
        int defaultRingtoneItemText = RingtonePickerViewModel.getDefaultRingtoneItemTextByType(-1);
        assertEquals(R.string.ringtone_default, defaultRingtoneItemText);
    }
}
