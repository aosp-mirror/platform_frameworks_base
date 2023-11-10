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

package com.android.systemui.media.controls.ui

import android.provider.Settings
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.FrameLayout
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.stack.MediaContainerView
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.util.animation.UniqueObjectHostView
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.utils.os.FakeHandler
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class KeyguardMediaControllerTest : SysuiTestCase() {

    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var configurationController: ConfigurationController

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    private val mediaContainerView: MediaContainerView = MediaContainerView(context, null)
    private val hostView = UniqueObjectHostView(context)
    private val settings = FakeSettings()
    private lateinit var keyguardMediaController: KeyguardMediaController
    private lateinit var testableLooper: TestableLooper
    private lateinit var fakeHandler: FakeHandler
    private lateinit var statusBarStateListener: StatusBarStateController.StateListener

    @Before
    fun setup() {
        doAnswer {
                statusBarStateListener = it.arguments[0] as StatusBarStateController.StateListener
                return@doAnswer Unit
            }
            .whenever(statusBarStateController)
            .addCallback(any(StatusBarStateController.StateListener::class.java))
        // default state is positive, media should show up
        whenever(mediaHost.visible).thenReturn(true)
        whenever(statusBarStateController.state).thenReturn(StatusBarState.KEYGUARD)
        whenever(mediaHost.hostView).thenReturn(hostView)
        hostView.layoutParams = FrameLayout.LayoutParams(100, 100)
        testableLooper = TestableLooper.get(this)
        fakeHandler = FakeHandler(testableLooper.looper)
        keyguardMediaController =
            KeyguardMediaController(
                mediaHost,
                bypassController,
                statusBarStateController,
                context,
                settings,
                fakeHandler,
                configurationController,
                ResourcesSplitShadeStateController(),
                mock<DumpManager>()
            )
        keyguardMediaController.attachSinglePaneContainer(mediaContainerView)
        keyguardMediaController.useSplitShade = false
    }

    @Test
    fun testHiddenWhenHostIsHidden() {
        whenever(mediaHost.visible).thenReturn(false)

        keyguardMediaController.refreshMediaPosition()

        assertThat(mediaContainerView.visibility).isEqualTo(GONE)
    }

    @Test
    fun testVisibleOnKeyguardOrFullScreenUserSwitcher() {
        testStateVisibility(StatusBarState.SHADE, GONE)
        testStateVisibility(StatusBarState.SHADE_LOCKED, GONE)
        testStateVisibility(StatusBarState.KEYGUARD, VISIBLE)
    }

    private fun testStateVisibility(state: Int, visibility: Int) {
        whenever(statusBarStateController.state).thenReturn(state)
        keyguardMediaController.refreshMediaPosition()
        assertThat(mediaContainerView.visibility).isEqualTo(visibility)
    }

    @Test
    fun testHiddenOnKeyguard_whenMediaOnLockScreenDisabled() {
        settings.putInt(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, 0)

        keyguardMediaController.refreshMediaPosition()

        assertThat(mediaContainerView.visibility).isEqualTo(GONE)
    }

    @Test
    fun testAvailableOnKeyguard_whenMediaOnLockScreenEnabled() {
        settings.putInt(Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN, 1)

        keyguardMediaController.refreshMediaPosition()

        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
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
        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun testAttachedToSplitShade() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        assertTrue(
            "HostView wasn't attached to the split pane container",
            splitShadeContainer.childCount == 1
        )
    }

    @Test
    fun testAttachedToSinglePane() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)

        assertTrue(
            "HostView wasn't attached to the single pane container",
            mediaContainerView.childCount == 1
        )
    }

    @Test
    fun testMediaHost_expandedPlayer() {
        verify(mediaHost).expansion = MediaHostState.EXPANDED
    }

    @Test
    fun dozing_inSplitShade_mediaIsHidden() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        setDozing()

        assertThat(splitShadeContainer.visibility).isEqualTo(GONE)
    }

    @Test
    fun dozeWakeUpAnimationWaiting_inSplitShade_mediaIsHidden() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = true

        keyguardMediaController.isDozeWakeUpAnimationWaiting = true

        assertThat(splitShadeContainer.visibility).isEqualTo(GONE)
    }

    @Test
    fun dozing_inSingleShade_mediaIsVisible() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = false

        setDozing()

        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
    }

    @Test
    fun dozeWakeUpAnimationWaiting_inSingleShade_mediaIsVisible() {
        val splitShadeContainer = FrameLayout(context)
        keyguardMediaController.attachSplitShadeContainer(splitShadeContainer)
        keyguardMediaController.useSplitShade = false

        keyguardMediaController.isDozeWakeUpAnimationWaiting = true

        assertThat(mediaContainerView.visibility).isEqualTo(VISIBLE)
    }

    private fun setDozing() {
        whenever(statusBarStateController.isDozing).thenReturn(true)
        statusBarStateListener.onDozingChanged(true)
    }
}
