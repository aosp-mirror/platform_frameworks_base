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

package com.android.systemui.classifier;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.BRIGHTLINE_FALSING_MANAGER_ENABLED;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.junit.Assert.assertThat;

import android.os.Handler;
import android.provider.DeviceConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.brightline.BrightLineFalsingManager;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.ProximitySensor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class FalsingManagerProxyTest extends SysuiTestCase {
    @Mock(stubOnly = true)
    PluginManager mPluginManager;
    @Mock(stubOnly = true)
    ProximitySensor mProximitySensor;
    private boolean mDefaultConfigValue;
    private Handler mHandler;
    private TestableLooper mTestableLooper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());
        mDefaultConfigValue = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, false);
        // In case it runs on a device where it's been set to true, set it to false by hand.
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "false", false);
    }

    @After
    public void tearDown() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, mDefaultConfigValue ? "true" : "false", false);
    }

    @Test
    public void test_brightLineFalsingManagerDisabled() {
        FalsingManagerProxy proxy = new FalsingManagerProxy(
                getContext(), mPluginManager, mHandler, mProximitySensor);

        assertThat(proxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));
    }

    @Test
    public void test_brightLineFalsingManagerEnabled() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "true", false);
        FalsingManagerProxy proxy = new FalsingManagerProxy(
                getContext(), mPluginManager, mHandler, mProximitySensor);

        assertThat(proxy.getInternalFalsingManager(), instanceOf(BrightLineFalsingManager.class));
    }

    @Test
    public void test_brightLineFalsingManagerToggled() {
        FalsingManagerProxy proxy = new FalsingManagerProxy(
                getContext(), mPluginManager, mHandler, mProximitySensor);
        assertThat(proxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "true", false);
        mTestableLooper.processAllMessages();
        proxy.setupFalsingManager(getContext());
        assertThat(proxy.getInternalFalsingManager(), instanceOf(BrightLineFalsingManager.class));

        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "false", false);
        mTestableLooper.processAllMessages();
        proxy.setupFalsingManager(getContext());
        assertThat(proxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));
    }
}
