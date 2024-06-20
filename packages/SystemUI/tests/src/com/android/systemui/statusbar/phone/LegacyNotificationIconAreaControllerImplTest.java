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
package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.DisableFlags;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.DarkIconDispatcher;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationMediaManager;
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator;
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider;
import com.android.systemui.statusbar.notification.shared.NotificationIconContainerRefactor;
import com.android.systemui.statusbar.window.StatusBarWindowController;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@DisableFlags(NotificationIconContainerRefactor.FLAG_NAME)
public class LegacyNotificationIconAreaControllerImplTest extends SysuiTestCase {

    @Mock
    private NotificationListener mListener;
    @Mock
    StatusBarStateController mStatusBarStateController;
    @Mock
    NotificationWakeUpCoordinator mWakeUpCoordinator;
    @Mock
    KeyguardBypassController mKeyguardBypassController;
    @Mock
    NotificationMediaManager mNotificationMediaManager;
    @Mock
    DozeParameters mDozeParameters;
    @Mock
    SectionStyleProvider mSectionStyleProvider;
    @Mock
    DarkIconDispatcher mDarkIconDispatcher;
    @Mock
    StatusBarWindowController mStatusBarWindowController;
    @Mock
    ScreenOffAnimationController mScreenOffAnimationController;
    private LegacyNotificationIconAreaControllerImpl mController;
    @Mock
    private Bubbles mBubbles;
    @Mock private DemoModeController mDemoModeController;
    @Mock
    private NotificationIconContainer mAodIcons;
    @Mock
    private FeatureFlags mFeatureFlags;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mController = new LegacyNotificationIconAreaControllerImpl(
                mContext,
                mStatusBarStateController,
                mWakeUpCoordinator,
                mKeyguardBypassController,
                mNotificationMediaManager,
                mListener,
                mDozeParameters,
                mSectionStyleProvider,
                Optional.of(mBubbles),
                mDemoModeController,
                mDarkIconDispatcher,
                mFeatureFlags,
                mStatusBarWindowController,
                mScreenOffAnimationController);
    }

    @Test
    public void testNotificationIcons_settingHideIcons() {
        mController.mSettingsListener.onStatusBarIconsBehaviorChanged(true);

        assertFalse(mController.shouldShouldLowPriorityIcons());
    }

    @Test
    public void testNotificationIcons_settingShowIcons() {
        mController.mSettingsListener.onStatusBarIconsBehaviorChanged(false);

        assertTrue(mController.shouldShouldLowPriorityIcons());
    }

    @Test
    public void testAppearResetsTranslation() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MIGRATE_CLOCKS_TO_BLUEPRINT);
        mController.setupAodIcons(mAodIcons);
        when(mDozeParameters.shouldControlScreenOff()).thenReturn(false);
        mController.appearAodIcons();
        verify(mAodIcons).setTranslationY(0);
        verify(mAodIcons).setAlpha(1.0f);
    }
}
