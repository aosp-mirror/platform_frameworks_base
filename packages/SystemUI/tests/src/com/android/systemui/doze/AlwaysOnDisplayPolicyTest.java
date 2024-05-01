/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.doze;

import static com.google.common.truth.Truth.assertThat;

import android.provider.Settings;
import android.text.format.DateUtils;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class AlwaysOnDisplayPolicyTest extends SysuiTestCase {
    private static final String ALWAYS_ON_DISPLAY_CONSTANTS_VALUE = "prox_screen_off_delay=1000"
            + ",prox_cooldown_trigger=2000"
            + ",prox_cooldown_period=3000"
            + ",screen_brightness_array=1:2:3:4:5"
            + ",dimming_scrim_array=5:4:3:2:1";

    private String mPreviousConfig;

    @Before
    public void setUp() {
        mPreviousConfig = Settings.Global.getString(mContext.getContentResolver(),
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS);
    }

    @After
    public void tearDown() {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS, mPreviousConfig);
    }

    @Test
    public void testPolicy_valueNull_containsDefaultValue() throws Exception {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS, null);

        AlwaysOnDisplayPolicy policy = new AlwaysOnDisplayPolicy(mContext);

        assertThat(policy.proxScreenOffDelayMs).isEqualTo(10 * DateUtils.SECOND_IN_MILLIS);
        assertThat(policy.proxCooldownTriggerMs).isEqualTo(2 * DateUtils.SECOND_IN_MILLIS);
        assertThat(policy.proxCooldownPeriodMs).isEqualTo(5 * DateUtils.SECOND_IN_MILLIS);
        assertThat(policy.screenBrightnessArray).isEqualTo(mContext.getResources().getIntArray(
                R.array.config_doze_brightness_sensor_to_brightness));
        assertThat(policy.dimmingScrimArray).isEqualTo(mContext.getResources().getIntArray(
                R.array.config_doze_brightness_sensor_to_scrim_opacity));
    }

    @Test
    public void testPolicy_valueNotNull_containsValue() throws Exception {
        Settings.Global.putString(mContext.getContentResolver(),
                Settings.Global.ALWAYS_ON_DISPLAY_CONSTANTS, ALWAYS_ON_DISPLAY_CONSTANTS_VALUE);

        AlwaysOnDisplayPolicy policy = new AlwaysOnDisplayPolicy(mContext);

        assertThat(policy.proxScreenOffDelayMs).isEqualTo(1000);
        assertThat(policy.proxCooldownTriggerMs).isEqualTo(2000);
        assertThat(policy.proxCooldownPeriodMs).isEqualTo(3000);
        assertThat(policy.screenBrightnessArray).isEqualTo(new int[]{1, 2, 3, 4, 5});
        assertThat(policy.dimmingScrimArray).isEqualTo(new int[]{5, 4, 3, 2, 1});
    }
}
