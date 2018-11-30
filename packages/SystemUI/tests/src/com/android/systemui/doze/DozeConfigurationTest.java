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

import static junit.framework.TestCase.assertEquals;

import android.os.UserHandle;
import android.provider.Settings;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class DozeConfigurationTest extends SysuiTestCase {

    private AmbientDisplayConfiguration mDozeConfig;

    @Before
    public void setup() {
        mDozeConfig = new AmbientDisplayConfiguration(mContext);
    }

    @Test
    public void alwaysOn_followsConfigByDefault() throws Exception {
        if (!mDozeConfig.alwaysOnAvailable()) {
            return;
        }

        Settings.Secure.putString(mContext.getContentResolver(), Settings.Secure.DOZE_ALWAYS_ON,
                null);
        boolean defaultValue = mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_dozeAlwaysOnEnabled);
        assertEquals(mDozeConfig.alwaysOnEnabled(UserHandle.USER_CURRENT), defaultValue);
    }
}
