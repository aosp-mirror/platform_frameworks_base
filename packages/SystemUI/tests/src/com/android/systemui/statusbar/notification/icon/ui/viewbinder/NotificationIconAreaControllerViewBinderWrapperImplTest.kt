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
package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.demomode.DemoModeController
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationListener
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.notification.NotificationWakeUpCoordinator
import com.android.systemui.statusbar.notification.collection.provider.SectionStyleProvider
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerShelfViewModel
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.phone.NotificationIconContainer
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.statusbar.window.StatusBarWindowController
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
class NotificationIconAreaControllerViewBinderWrapperImplTest : SysuiTestCase() {
    @Mock private lateinit var notifListener: NotificationListener
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var wakeUpCoordinator: NotificationWakeUpCoordinator
    @Mock private lateinit var keyguardBypassController: KeyguardBypassController
    @Mock private lateinit var notifMediaManager: NotificationMediaManager
    @Mock private lateinit var dozeParams: DozeParameters
    @Mock private lateinit var sectionStyleProvider: SectionStyleProvider
    @Mock private lateinit var darkIconDispatcher: DarkIconDispatcher
    @Mock private lateinit var statusBarWindowController: StatusBarWindowController
    @Mock private lateinit var screenOffAnimController: ScreenOffAnimationController
    @Mock private lateinit var bubbles: Bubbles
    @Mock private lateinit var demoModeController: DemoModeController
    @Mock private lateinit var aodIcons: NotificationIconContainer
    @Mock private lateinit var featureFlags: FeatureFlags

    private val shelfViewModel = NotificationIconContainerShelfViewModel()
    private val statusBarViewModel = NotificationIconContainerStatusBarViewModel()
    private val aodViewModel = NotificationIconContainerAlwaysOnDisplayViewModel()

    private lateinit var underTest: NotificationIconAreaControllerViewBinderWrapperImpl

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            NotificationIconAreaControllerViewBinderWrapperImpl(
                mContext,
                statusBarStateController,
                wakeUpCoordinator,
                keyguardBypassController,
                notifMediaManager,
                notifListener,
                dozeParams,
                sectionStyleProvider,
                Optional.of(bubbles),
                demoModeController,
                darkIconDispatcher,
                featureFlags,
                statusBarWindowController,
                screenOffAnimController,
                shelfViewModel,
                statusBarViewModel,
                aodViewModel,
            )
    }

    @Test
    fun testNotificationIcons_settingHideIcons() {
        underTest.settingsListener.onStatusBarIconsBehaviorChanged(true)
        assertFalse(underTest.shouldShowLowPriorityIcons())
    }

    @Test
    fun testNotificationIcons_settingShowIcons() {
        underTest.settingsListener.onStatusBarIconsBehaviorChanged(false)
        assertTrue(underTest.shouldShowLowPriorityIcons())
    }

    @Test
    fun testAppearResetsTranslation() {
        underTest.setupAodIcons(aodIcons)
        whenever(dozeParams.shouldControlScreenOff()).thenReturn(false)
        underTest.appearAodIcons()
        verify(aodIcons).translationY = 0f
        verify(aodIcons).alpha = 1.0f
    }
}
