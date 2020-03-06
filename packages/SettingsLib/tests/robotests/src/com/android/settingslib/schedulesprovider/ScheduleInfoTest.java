/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.settingslib.schedulesprovider;

import static com.google.common.truth.Truth.assertThat;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class ScheduleInfoTest {
    private static final String TEST_TITLE = "Night Light";
    private static final String TEST_SUMMARY = "Night Light summary";
    private static final String TEST_EMPTY_SUMMARY = "";

    private final Context mContext = RuntimeEnvironment.application;

    @Test
    public void builder_usedValidArguments_isValid() {
        final PendingIntent pendingIntent = createTestPendingIntent(mContext);
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, pendingIntent);

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isTrue();
    }

    @Test
    public void builder_useEmptySummary_isInvalid() {
        final PendingIntent pendingIntent = createTestPendingIntent(mContext);
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_EMPTY_SUMMARY,
                pendingIntent);

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isFalse();
    }

    @Test
    public void builder_pendingIntentIsNull_isInvalid() {
        final ScheduleInfo info = new ScheduleInfo.Builder()
                .setTitle(TEST_TITLE)
                .setSummary(TEST_SUMMARY)
                .build();

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isFalse();
    }

    @Test
    public void getTitle_setValidTitle_shouldReturnSameCorrectTitle() {
        final PendingIntent pendingIntent = createTestPendingIntent(mContext);
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, pendingIntent);

        assertThat(info.getTitle()).isEqualTo(TEST_TITLE);
    }

    @Test
    public void getSummary_setValidSummary_shouldReturnSameCorrectSummary() {
        final PendingIntent pendingIntent = createTestPendingIntent(mContext);
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, pendingIntent);

        assertThat(info.getSummary()).isEqualTo(TEST_SUMMARY);
    }

    @Test
    public void getPendingIntent_setValidPendingIntent_shouldReturnSameCorrectIntent() {
        final PendingIntent pendingIntent = createTestPendingIntent(mContext);
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, pendingIntent);

        assertThat(info.getPendingIntent()).isEqualTo(pendingIntent);
    }

    private static PendingIntent createTestPendingIntent(Context context) {
        final Intent intent = new Intent("android.settings.NIGHT_DISPLAY_SETTINGS").addCategory(
                Intent.CATEGORY_DEFAULT);
        return PendingIntent.getActivity(context, 0 /* requestCode */, intent, 0 /* flags */);
    }

    private static ScheduleInfo createTestScheduleInfo(String title, String summary,
            PendingIntent pendingIntent) {
        return new ScheduleInfo.Builder()
                .setTitle(title)
                .setSummary(summary)
                .setPendingIntent(pendingIntent)
                .build();
    }
}
