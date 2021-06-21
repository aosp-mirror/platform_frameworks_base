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
package com.android.server.hdmi;

import static com.google.common.truth.Truth.assertThat;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Locale;

/**
 * Tests for static methods of {@link HdmiControlService} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class HdmiControlServiceStaticTest {

    @Test
    public void localToMenuLanguage_english() {
        assertThat(HdmiControlService.localeToMenuLanguage(Locale.ENGLISH)).isEqualTo("eng");
    }

    @Test
    public void localToMenuLanguage_german() {
        assertThat(HdmiControlService.localeToMenuLanguage(Locale.GERMAN)).isEqualTo("ger");
    }

    @Test
    public void localToMenuLanguage_taiwan() {
        assertThat(HdmiControlService.localeToMenuLanguage(Locale.TAIWAN)).isEqualTo("chi");
    }

    @Test
    public void localToMenuLanguage_macau() {
        assertThat(HdmiControlService.localeToMenuLanguage(new Locale("zh", "MO"))).isEqualTo(
                "chi");
    }

    @Test
    public void localToMenuLanguage_hongkong() {
        assertThat(HdmiControlService.localeToMenuLanguage(new Locale("zh", "HK"))).isEqualTo(
                "chi");
    }

    @Test
    public void localToMenuLanguage_chinese() {
        assertThat(HdmiControlService.localeToMenuLanguage(Locale.CHINESE)).isEqualTo("zho");
    }

}
