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

package com.android.server.devicestate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

/**
 * Unit tests for the {@link DeviceStatePolicy.Provider}
 * <p/>
 * Build/Install/Run:
 *  <code>atest DeviceStatePolicyProviderTest</code>
 */
@Presubmit
public class DeviceStatePolicyProviderTest {

    @Test
    public void test_emptyPolicyProvider() {
        Assert.assertThat(DeviceStatePolicy.Provider.fromResources(resourcesWithProvider("")),
                Matchers.instanceOf(DeviceStatePolicy.DefaultProvider.class));
    }

    @Test
    public void test_nullPolicyProvider() {
        Assert.assertThat(DeviceStatePolicy.Provider.fromResources(resourcesWithProvider(null)),
                Matchers.instanceOf(DeviceStatePolicy.DefaultProvider.class));
    }

    @Test
    public void test_customPolicyProvider() {
        Assert.assertThat(DeviceStatePolicy.Provider.fromResources(resourcesWithProvider(
                TestProvider.class.getName())),
                Matchers.instanceOf(TestProvider.class));
    }

    @Test
    public void test_badPolicyProvider_notImplementingProviderInterface() {
        assertThrows(IllegalStateException.class, () -> {
            DeviceStatePolicy.Provider.fromResources(resourcesWithProvider(
                    Object.class.getName()));
        });
    }

    @Test
    public void test_badPolicyProvider_doesntExist() {
        assertThrows(IllegalStateException.class, () -> {
            DeviceStatePolicy.Provider.fromResources(resourcesWithProvider(
                    "com.android.devicestate.nonexistent.policy"));
        });
    }

    private static Resources resourcesWithProvider(String provider) {
        final Resources mockResources = mock(Resources.class);
        when(mockResources.getString(
                com.android.internal.R.string.config_deviceSpecificDeviceStatePolicyProvider))
                .thenReturn(provider);
        return mockResources;
    }

    // Stub implementation of DeviceStatePolicy.Provider for testing
    static class TestProvider implements DeviceStatePolicy.Provider {
        @Override
        public DeviceStatePolicy instantiate(Context context) {
            throw new RuntimeException("test stub");
        }
    }
}
