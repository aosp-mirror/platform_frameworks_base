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

package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaHeaderView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class KeyguardMediaControllerTest : SysuiTestCase() {

    @Mock
    private lateinit var mediaHost: MediaHost
    @Mock
    private lateinit var bypassController: KeyguardBypassController
    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager
    @JvmField @Rule
    val mockito = MockitoJUnit.rule()

    private val mediaHeaderView: MediaHeaderView = MediaHeaderView(context, null)
    private val hostView = UniqueObjectHostView(context)
    private lateinit var keyguardMediaController: KeyguardMediaController

    @Before
    fun setup() {
        // default state is positive, media should show up
        whenever(mediaHost.visible).thenReturn(true)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(notificationLockscreenUserManager.shouldShowLockscreenNotifications())
                .thenReturn(true)
        whenever(mediaHost.hostView).thenReturn(hostView)

        keyguardMediaController = KeyguardMediaController(
            mediaHost,
            bypassController,
            statusBarStateController,
            notificationLockscreenUserManager,
            featureFlags,
            context,
            configurationController
        )
        keyguardMediaController.attachSinglePaneContainer(mediaHeaderView)
    }

    @Test
    fun testHiddenWhenHostIsHidden() {
        whenever(mediaHost.visible).thenReturn(false)

        keyguardMediaController.refreshMediaPosition()

        assertThat(mediaHeaderView.visibility).isEqualTo(GONE)
    }

    @Test
    fun testVisibleOnKeyguardOrFullScreenUserSwitcher() {
        testStateVisibility(StatusBarState.SHADE, GONE)
        testStateVisibility(StatusBarState.SHADE_LOCKED, GONE)
        testStateVisibility(StatusBarState.FULLSCREEN_USER_SWITCHER, VISIBLE)
        testStateVisibility(StatusBarState.KEYGUARD, VISIBLE)
    }

    private fun testStateVisibility(state: Int, visibility: Int) {
        whenever(statusBarStateController.state).thenReturn(state)
        keyguardMediaController.refreshMediaPosition()
        assertThat(mediaHeaderView.visibility).isEqualTo(visibility)
    }

    @Test
    fun testHiddenOnKeyguard_whenNotificationsAreHidden() {
        whenever(notificationLockscreenUserManager.shouldShowLockscreenNotifications())
                .thenReturn(false)

        keyguardMediaController.refreshMediaPosition()

        assertThat(mediaHeaderView.visibility).isEqualTo(GONE)
    }

    @Test
    fun testActivatesSplitShadeContainerInSplitShadeMode() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        assertThat(splitShadeContainer.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun testActivatesSinglePaneContainerInSinglePaneMode() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)

        assertThat(splitShadeContainer.visibility).isEqualTo(GONE)
        assertThat(mediaHeaderView.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun testAttachedToSplitShade() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        assertTrue("HostView wasn't attached to the split pane container",
            splitShadeContainer.childCount == 1)
    }

    @Test
    fun testAttachedToSinglePane() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)

        assertTrue("HostView wasn't attached to the single pane container",
            mediaHeaderView.childCount == 1)
    }
}
