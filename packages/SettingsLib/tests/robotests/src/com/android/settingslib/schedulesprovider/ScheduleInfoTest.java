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

import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class ScheduleInfoTest {
    private static final String TEST_TITLE = "Night Light";
    private static final String TEST_SUMMARY = "Night Light summary";
    private static final String TEST_EMPTY_SUMMARY = "";

    @Test
    public void builder_usedValidArguments_isValid() {
        final Intent intent = createTestIntent();
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, intent);

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isTrue();
    }

    @Test
    public void builder_useEmptySummary_isInvalid() {
        final Intent intent = createTestIntent();
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_EMPTY_SUMMARY, intent);

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isFalse();
    }

    @Test
    public void builder_intentIsNull_isInvalid() {
        final ScheduleInfo info = new ScheduleInfo.Builder()
                .setTitle(TEST_TITLE)
                .setSummary(TEST_SUMMARY)
                .build();

        assertThat(info).isNotNull();
        assertThat(info.isValid()).isFalse();
    }

    @Test
    public void getTitle_setValidTitle_shouldReturnSameCorrectTitle() {
        final Intent intent = createTestIntent();
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, intent);

        assertThat(info.getTitle()).isEqualTo(TEST_TITLE);
    }

    @Test
    public void getSummary_setValidSummary_shouldReturnSameCorrectSummary() {
        final Intent intent = createTestIntent();
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, intent);

        assertThat(info.getSummary()).isEqualTo(TEST_SUMMARY);
    }

    @Test
    public void getIntent_setValidIntent_shouldReturnSameCorrectIntent() {
        final Intent intent = createTestIntent();
        final ScheduleInfo info = createTestScheduleInfo(TEST_TITLE, TEST_SUMMARY, intent);

        assertThat(info.getIntent()).isEqualTo(intent);
    }

    private static Intent createTestIntent() {
        return new Intent("android.settings.NIGHT_DISPLAY_SETTINGS").addCategory(
                Intent.CATEGORY_DEFAULT);
    }

    private static ScheduleInfo createTestScheduleInfo(String title, String summary,
            Intent intent) {
        return new ScheduleInfo.Builder()
                .setTitle(title)
                .setSummary(summary)
                .setIntent(intent)
                .build();
    }
}
