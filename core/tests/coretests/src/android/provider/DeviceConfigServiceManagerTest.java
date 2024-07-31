/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.provider;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Class that tests the APIs of DeviceConfigServiceManager.ServiceRegisterer.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class DeviceConfigServiceManagerTest {

    private static final String SERVICE_NAME = "device_config_updatable";
    private DeviceConfigServiceManager.ServiceRegisterer mRegisterer;

    @Before
    public void setUp() {
        mRegisterer = new DeviceConfigServiceManager.ServiceRegisterer(SERVICE_NAME);
    }

    @Test
    public void testGetOrThrow() throws DeviceConfigServiceManager.ServiceNotFoundException {
        if (UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService()) {
            assertThat(mRegisterer.getOrThrow()).isNotNull();
        } else {
            assertThrows(DeviceConfigServiceManager.ServiceNotFoundException.class,
                    mRegisterer::getOrThrow);
        }
    }

    @Test
    public void testGet() {
        assumeTrue(UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService());
        assertThat(mRegisterer.get()).isNotNull();
    }

    @Test
    public void testTryGet() {
        if (UpdatableDeviceConfigServiceReadiness.shouldStartUpdatableService()) {
            assertThat(mRegisterer.tryGet()).isNotNull();
        } else {
            assertThat(mRegisterer.tryGet()).isNull();
        }
    }
}
