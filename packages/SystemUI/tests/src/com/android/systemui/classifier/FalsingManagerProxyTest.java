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

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.brightline.BrightLineFalsingManager;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.sensors.ProximitySensor;

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
    private Handler mHandler;
    private FalsingManagerProxy mProxy;
    private DeviceConfigProxy mDeviceConfig;
    private TestableLooper mTestableLooper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(KeyguardUpdateMonitor.class);
        mTestableLooper = TestableLooper.get(this);
        mHandler = new Handler(mTestableLooper.getLooper());
        mDeviceConfig = new DeviceConfigProxyFake();
        mDeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "false", false);
    }

    @After
    public void tearDown() {
        if (mProxy != null) {
            mProxy.cleanup();
        }
    }

    @Test
    public void test_brightLineFalsingManagerDisabled() {
        mProxy = new FalsingManagerProxy(getContext(), mPluginManager, mHandler, mProximitySensor,
                mDeviceConfig);
        assertThat(mProxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));
    }

    @Test
    public void test_brightLineFalsingManagerEnabled() throws InterruptedException {
        mDeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "true", false);
        mTestableLooper.processAllMessages();
        mProxy = new FalsingManagerProxy(getContext(), mPluginManager, mHandler, mProximitySensor,
                mDeviceConfig);
        assertThat(mProxy.getInternalFalsingManager(), instanceOf(BrightLineFalsingManager.class));
    }

    @Test
    public void test_brightLineFalsingManagerToggled() throws InterruptedException {
        mProxy = new FalsingManagerProxy(getContext(), mPluginManager, mHandler, mProximitySensor,
                mDeviceConfig);
        assertThat(mProxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));

        mDeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "true", false);
        mTestableLooper.processAllMessages();
        assertThat(mProxy.getInternalFalsingManager(),
                instanceOf(BrightLineFalsingManager.class));

        mDeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                BRIGHTLINE_FALSING_MANAGER_ENABLED, "false", false);
        mTestableLooper.processAllMessages();
        assertThat(mProxy.getInternalFalsingManager(), instanceOf(FalsingManagerImpl.class));
    }
}
