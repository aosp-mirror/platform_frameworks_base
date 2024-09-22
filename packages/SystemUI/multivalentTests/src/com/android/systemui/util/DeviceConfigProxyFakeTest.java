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

package com.android.systemui.util;

import static android.provider.DeviceConfig.Properties;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig.OnPropertiesChangedListener;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DeviceConfigProxyFakeTest extends SysuiTestCase {
    private static final String NAMESPACE = "foobar";

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private DeviceConfigProxyFake mDeviceConfigProxyFake;

    @Before
    public void setup() {
        mDeviceConfigProxyFake = new DeviceConfigProxyFake();
    }

    @Test
    public void testOnPropertiesChanged() {
        TestableListener onPropertiesChangedListener = new TestableListener();
        String key = "foo";
        String value = "bar";

        mDeviceConfigProxyFake.addOnPropertiesChangedListener(
                NAMESPACE, mFakeExecutor, onPropertiesChangedListener);

        mDeviceConfigProxyFake.setProperty(NAMESPACE, key, value, false);
        mFakeExecutor.runAllReady();
        assertThat(onPropertiesChangedListener.mProperties).isNotNull();
        assertThat(onPropertiesChangedListener.mProperties.getKeyset().size()).isEqualTo(1);
        assertThat(onPropertiesChangedListener.mProperties.getString(key, "")).isEqualTo(value);
    }

    @Test
    public void testOnMultiplePropertiesChanged() {
        TestableListener onPropertiesChangedListener = new TestableListener();
        String keyA = "foo";
        String valueA = "bar";
        String keyB = "bada";
        String valueB = "boom";

        mDeviceConfigProxyFake.addOnPropertiesChangedListener(
                NAMESPACE, mFakeExecutor, onPropertiesChangedListener);
        mDeviceConfigProxyFake.setProperty(NAMESPACE, keyA, valueA, false);
        mFakeExecutor.runAllReady();
        assertThat(onPropertiesChangedListener.mProperties).isNotNull();
        assertThat(onPropertiesChangedListener.mProperties.getKeyset().size()).isEqualTo(1);
        assertThat(onPropertiesChangedListener.mProperties.getString(keyA, "")).isEqualTo(valueA);

        mDeviceConfigProxyFake.setProperty(NAMESPACE, keyB, valueB, false);
        mFakeExecutor.runAllReady();
        assertThat(onPropertiesChangedListener.mProperties).isNotNull();
        assertThat(onPropertiesChangedListener.mProperties.getKeyset().size()).isEqualTo(1);
        assertThat(onPropertiesChangedListener.mProperties.getString(keyB, "")).isEqualTo(valueB);
    }

    private static class TestableListener implements OnPropertiesChangedListener {
        Properties mProperties;

        TestableListener() {
        }
        @Override
        public void onPropertiesChanged(@NonNull Properties properties) {
            mProperties = properties;
        }
    }
}
