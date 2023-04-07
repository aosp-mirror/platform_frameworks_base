/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard;

import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.keyguard.logging.KeyguardLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class KeyguardStatusViewControllerTest extends SysuiTestCase {

    @Mock private KeyguardStatusView mKeyguardStatusView;
    @Mock private KeyguardSliceViewController mKeyguardSliceViewController;
    @Mock private KeyguardClockSwitchController mKeyguardClockSwitchController;
    @Mock private KeyguardStateController mKeyguardStateController;
    @Mock private KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock private ConfigurationController mConfigurationController;
    @Mock private DozeParameters mDozeParameters;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private KeyguardLogger mKeyguardLogger;
    @Mock private KeyguardStatusViewController mControllerMock;
    @Mock private FeatureFlags mFeatureFlags;
    @Mock private InteractionJankMonitor mInteractionJankMonitor;

    @Captor
    private ArgumentCaptor<KeyguardUpdateMonitorCallback> mKeyguardUpdateMonitorCallbackCaptor;

    private KeyguardStatusViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mController = new KeyguardStatusViewController(
                mKeyguardStatusView,
                mKeyguardSliceViewController,
                mKeyguardClockSwitchController,
                mKeyguardStateController,
                mKeyguardUpdateMonitor,
                mConfigurationController,
                mDozeParameters,
                mScreenOffAnimationController,
                mKeyguardLogger,
                mFeatureFlags,
                mInteractionJankMonitor);
    }

    @Test
    public void dozeTimeTick_updatesSlice() {
        mController.dozeTimeTick();
        verify(mKeyguardSliceViewController).refresh();
    }

    @Test
    public void dozeTimeTick_updatesClock() {
        mController.dozeTimeTick();
        verify(mKeyguardClockSwitchController).refresh();
    }

    @Test
    public void setTranslationYExcludingMedia_forwardsCallToView() {
        float translationY = 123f;

        mController.setTranslationY(translationY, /* excludeMedia= */true);

        verify(mKeyguardStatusView).setChildrenTranslationY(translationY, /* excludeMedia= */true);
    }

    @Test
    public void onLocaleListChangedNotifiesClockSwitchController() {
        ArgumentCaptor<ConfigurationListener> configurationListenerArgumentCaptor =
                ArgumentCaptor.forClass(ConfigurationListener.class);

        mController.onViewAttached();
        verify(mConfigurationController).addCallback(configurationListenerArgumentCaptor.capture());

        configurationListenerArgumentCaptor.getValue().onLocaleListChanged();
        verify(mKeyguardClockSwitchController).onLocaleListChanged();
    }
}
