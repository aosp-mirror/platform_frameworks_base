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

package com.android.systemui.statusbar.phone;


import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import androidx.test.filters.SmallTest;

import com.android.keyguard.CarrierTextController;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class KeyguardStatusBarViewControllerTest extends SysuiTestCase {
    @Mock
    private KeyguardStatusBarView mKeyguardStatusBarView;
    @Mock
    private CarrierTextController mCarrierTextController;
    @Mock
    private ConfigurationController mConfigurationController;

    private KeyguardStatusBarViewController mController;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mController = new KeyguardStatusBarViewController(
                mKeyguardStatusBarView,
                mCarrierTextController,
                mConfigurationController
        );
    }

    @Test
    public void onViewAttached_callbacksRegistered() {
        mController.onViewAttached();

        verify(mConfigurationController).addCallback(any());
    }

    @Test
    public void onViewDetached_callbacksUnregistered() {
        mController.onViewDetached();

        verify(mConfigurationController).removeCallback(any());
    }
}
