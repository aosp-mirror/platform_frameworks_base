/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.timedetector;

import static com.google.common.truth.Truth.assertThat;

import android.app.time.Capabilities;
import android.app.time.TimeCapabilities;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.os.UserHandle;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ConfigurationInternalTest {

    @Test
    public void capabilitiesAndConfig() {
        int userId = 112233;
        ConfigurationInternal configurationInternal = new ConfigurationInternal.Builder(userId)
                .setAutoDetectionEnabled(true)
                .setUserConfigAllowed(true)
                .build();

        TimeCapabilities timeCapabilities = new TimeCapabilities.Builder(UserHandle.of(userId))
                .setConfigureAutoTimeDetectionEnabledCapability(Capabilities.CAPABILITY_POSSESSED)
                .setSuggestTimeManuallyCapability(Capabilities.CAPABILITY_POSSESSED)
                .build();
        TimeConfiguration timeConfiguration = new TimeConfiguration.Builder()
                .setAutoDetectionEnabled(true)
                .build();
        TimeCapabilitiesAndConfig expected =
                new TimeCapabilitiesAndConfig(timeCapabilities, timeConfiguration);

        assertThat(configurationInternal.capabilitiesAndConfig()).isEqualTo(expected);
    }

}
