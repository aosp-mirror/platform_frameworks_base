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

package com.android.systemui.statusbar

import android.app.WallpaperManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper.RunWithLooper
import android.view.Choreographer
import android.view.View
import android.view.ViewRootImpl
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.BiometricUnlockController
import com.android.systemui.statusbar.phone.NotificationShadeWindowController
import com.android.systemui.statusbar.policy.KeyguardStateController
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.any
import org.mockito.Mockito.anyFloat
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class NotificationShadeDepthControllerTest : SysuiTestCase() {

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var blurUtils: BlurUtils
    @Mock private lateinit var biometricUnlockController: BiometricUnlockController
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var choreographer: Choreographer
    @Mock private lateinit var wallpaperManager: WallpaperManager
    @Mock private lateinit var notificationShadeWindowController: NotificationShadeWindowController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var root: View
    @Mock private lateinit var viewRootImpl: ViewRootImpl
    @Mock private lateinit var shadeSpring: NotificationShadeDepthController.DepthAnimation
    @Mock private lateinit var globalActionsSpring: NotificationShadeDepthController.DepthAnimation
    @JvmField @Rule val mockitoRule = MockitoJUnit.rule()

    private lateinit var statusBarStateListener: StatusBarStateController.StateListener
    private var statusBarState = StatusBarState.SHADE
    private val maxBlur = 150
    private lateinit var notificationShadeDepthController: NotificationShadeDepthController

    @Before
    fun setup() {
        `when`(root.viewRootImpl).thenReturn(viewRootImpl)
        `when`(statusBarStateController.state).then { statusBarState }
        `when`(blurUtils.blurRadiusOfRatio(anyFloat())).then { answer ->
            (answer.arguments[0] as Float * maxBlur).toInt()
        }
        notificationShadeDepthController = NotificationShadeDepthController(
                statusBarStateController, blurUtils, biometricUnlockController,
                keyguardStateController, choreographer, wallpaperManager,
                notificationShadeWindowController, dumpManager)
        notificationShadeDepthController.shadeSpring = shadeSpring
        notificationShadeDepthController.globalActionsSpring = globalActionsSpring
        notificationShadeDepthController.root = root

        val captor = ArgumentCaptor.forClass(StatusBarStateController.StateListener::class.java)
        verify(statusBarStateController).addCallback(captor.capture())
        statusBarStateListener = captor.value
    }

    @Test
    fun setupListeners() {
        verify(dumpManager).registerDumpable(anyString(), safeEq(notificationShadeDepthController))
    }

    @Test
    fun onPanelExpansionChanged_apliesBlur_ifShade() {
        notificationShadeDepthController.onPanelExpansionChanged(1f /* expansion */,
                false /* tracking */)
        verify(shadeSpring).animateTo(eq(maxBlur), any())
    }

    @Test
    fun onStateChanged_reevalutesBlurs_ifSameRadiusAndNewState() {
        onPanelExpansionChanged_apliesBlur_ifShade()
        clearInvocations(shadeSpring)

        statusBarState = StatusBarState.KEYGUARD
        statusBarStateListener.onStateChanged(statusBarState)
        verify(shadeSpring).animateTo(eq(0), any())
    }

    @Test
    fun updateGlobalDialogVisibility_appliesBlur() {
        notificationShadeDepthController.updateGlobalDialogVisibility(0.5f, root)
        verify(globalActionsSpring).animateTo(eq(maxBlur / 2), safeEq(root))
    }

    private fun <T : Any> safeEq(value: T): T {
        return eq(value) ?: value
    }
}