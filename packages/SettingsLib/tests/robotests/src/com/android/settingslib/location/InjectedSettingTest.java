/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.location;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class InjectedSettingTest {

    private static final String TEST_STRING = "test";

    @Test
    public void buildWithoutPackageName_ShouldReturnNull() {
        assertThat(((new InjectedSetting.Builder())
                .setClassName(TEST_STRING)
                .setTitle(TEST_STRING)
                .setSettingsActivity(TEST_STRING).build())).isNull();
    }

    private InjectedSetting getTestSetting() {
        return new InjectedSetting.Builder()
                .setPackageName(TEST_STRING)
                .setClassName(TEST_STRING)
                .setTitle(TEST_STRING)
                .setSettingsActivity(TEST_STRING).build();
    }

    @Test
    public void testEquals() {
        InjectedSetting setting1 = getTestSetting();
        InjectedSetting setting2 = getTestSetting();
        assertThat(setting1).isEqualTo(setting2);
    }

    @Test
    public void testHashCode() {
        InjectedSetting setting = getTestSetting();
        assertThat(setting.hashCode()).isEqualTo(1225314048);
    }
}
