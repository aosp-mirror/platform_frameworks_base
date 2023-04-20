/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.LetterboxConfigurationDeviceConfig.sKeyToDefaultValueMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.SmallTest;

import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Map;

/**
 * Test class for {@link LetterboxConfigurationDeviceConfig}.
 *
 * atest WmTests:LetterboxConfigurationDeviceConfigTests
 */
@SmallTest
@Presubmit
public class LetterboxConfigurationDeviceConfigTests {

    private LetterboxConfigurationDeviceConfig mDeviceConfig;

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Before
    public void setUp() {
        mDeviceConfig = new LetterboxConfigurationDeviceConfig(/* executor */ Runnable::run);
    }

    @Test
    public void testGetFlag_flagIsActive_flagChanges() throws Throwable {
        for (Map.Entry<String, Boolean> entry : sKeyToDefaultValueMap.entrySet()) {
            testGetFlagForKey_flagIsActive_flagChanges(entry.getKey(), entry.getValue());
        }
    }

    private void testGetFlagForKey_flagIsActive_flagChanges(final String key, boolean defaultValue)
            throws InterruptedException {
        mDeviceConfig.updateFlagActiveStatus(/* isActive */ true, key);

        assertEquals("Unexpected default value for " + key,
                mDeviceConfig.getFlag(key), defaultValue);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, key,
                /* value */ Boolean.TRUE.toString(), /* makeDefault */ false);

        assertTrue("Flag " + key + "is not true after change", mDeviceConfig.getFlag(key));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, key,
                /* value */ Boolean.FALSE.toString(), /* makeDefault */ false);

        assertFalse("Flag " + key + "is not false after change", mDeviceConfig.getFlag(key));
    }

    @Test
    public void testGetFlag_flagIsNotActive_alwaysReturnDefaultValue() throws Throwable {
        for (Map.Entry<String, Boolean> entry : sKeyToDefaultValueMap.entrySet()) {
            testGetFlagForKey_flagIsNotActive_alwaysReturnDefaultValue(
                    entry.getKey(), entry.getValue());
        }
    }

    private void testGetFlagForKey_flagIsNotActive_alwaysReturnDefaultValue(final String key,
            boolean defaultValue) throws InterruptedException {
        assertEquals("Unexpected default value for " + key,
                mDeviceConfig.getFlag(key), defaultValue);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, key,
                /* value */ Boolean.TRUE.toString(), /* makeDefault */ false);

        assertEquals("Flag " + key + "is not set to default after change",
                mDeviceConfig.getFlag(key), defaultValue);

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER, key,
                /* value */ Boolean.FALSE.toString(), /* makeDefault */ false);

        assertEquals("Flag " + key + "is not set to default after change",
                mDeviceConfig.getFlag(key), defaultValue);
    }

}
