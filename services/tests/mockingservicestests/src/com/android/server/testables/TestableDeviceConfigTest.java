/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.testables;

import static android.provider.DeviceConfig.OnPropertiesChangedListener;

import static com.google.common.truth.Truth.assertThat;

import android.app.ActivityThread;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;
import android.provider.DeviceConfig.Properties;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Tests that ensure appropriate settings are backed up. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class TestableDeviceConfigTest {
    private static final String sNamespace = "namespace1";
    private static final String sKey = "key1";
    private static final String sValue = "value1";
    private static final long WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS = 2000; // 2 sec

    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mTestableDeviceConfig = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void getProperty_empty() {
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isNull();
    }

    @Test
    public void setAndGetProperty_sameNamespace() {
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(sValue);
    }

    @Test
    public void setAndGetProperty_differentNamespace() {
        String newNamespace = "namespace2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        String result = DeviceConfig.getProperty(newNamespace, sKey);
        assertThat(result).isNull();
    }

    @Test
    public void setAndGetProperty_multipleNamespaces() {
        String newNamespace = "namespace2";
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(newNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(sValue);
        result = DeviceConfig.getProperty(newNamespace, sKey);
        assertThat(result).isEqualTo(newValue);
    }

    @Test
    public void setAndGetProperty_overrideValue() {
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        DeviceConfig.setProperty(sNamespace, sKey, newValue, false);
        String result = DeviceConfig.getProperty(sNamespace, sKey);
        assertThat(result).isEqualTo(newValue);
    }

    @Test
    public void getProperties_empty() {
        String newKey = "key2";
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        Properties properties = DeviceConfig.getProperties(sNamespace);
        assertThat(properties.getString(sKey, null)).isEqualTo(sValue);
        assertThat(properties.getString(newKey, null)).isNull();

        DeviceConfig.setProperty(sNamespace, newKey, newValue, false);
        properties = DeviceConfig.getProperties(sNamespace);
        assertThat(properties.getString(sKey, null)).isEqualTo(sValue);
        assertThat(properties.getString(newKey, null)).isEqualTo(newValue);

    }

    @Test
    public void getProperties() {
        Properties properties = DeviceConfig.getProperties(sNamespace, sKey);
        assertThat(properties.getString(sKey, null)).isNull();

        DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
        properties = DeviceConfig.getProperties(sNamespace, sKey);
        assertThat(properties.getString(sKey, null)).isEqualTo(sValue);

        String newKey = "key2";
        String newValue = "value2";
        DeviceConfig.setProperty(sNamespace, newKey, newValue, false);
        properties = DeviceConfig.getProperties(sNamespace, sKey, newKey);
        assertThat(properties.getString(sKey, null)).isEqualTo(sValue);
        assertThat(properties.getString(newKey, null)).isEqualTo(newValue);

        String unsetKey = "key3";
        properties = DeviceConfig.getProperties(sNamespace, newKey, unsetKey);
        assertThat(properties.getKeyset()).containsExactly(newKey, unsetKey);
        assertThat(properties.getString(newKey, null)).isEqualTo(newValue);
        assertThat(properties.getString(unsetKey, null)).isNull();
    }

    @Test
    public void testListener() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(1);

        OnPropertiesChangedListener changeListener = (properties) -> {
            assertThat(properties.getNamespace()).isEqualTo(sNamespace);
            assertThat(properties.getKeyset().size()).isEqualTo(1);
            assertThat(properties.getKeyset()).contains(sKey);
            assertThat(properties.getString(sKey, "bogus_value")).isEqualTo(sValue);
            assertThat(properties.getString("bogus_key", "bogus_value")).isEqualTo("bogus_value");
            countDownLatch.countDown();
        };
        try {
            DeviceConfig.addOnPropertiesChangedListener(sNamespace,
                    ActivityThread.currentApplication().getMainExecutor(), changeListener);
            DeviceConfig.setProperty(sNamespace, sKey, sValue, false);
            assertThat(countDownLatch.await(
                    WAIT_FOR_PROPERTY_CHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            DeviceConfig.removeOnPropertiesChangedListener(changeListener);
        }
    }

}


