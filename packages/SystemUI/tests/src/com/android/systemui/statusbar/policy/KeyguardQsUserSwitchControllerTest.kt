/*
 * Copyright (C) 2022 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.policy

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.user.UserSwitchDialogController
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.phone.DozeParameters
import com.android.systemui.statusbar.phone.LockscreenGestureLogger
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class KeyguardQsUserSwitchControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var userSwitcherController: UserSwitcherController

    @Mock
    private lateinit var keyguardStateController: KeyguardStateController

    @Mock
    private lateinit var falsingManager: FalsingManager

    @Mock
    private lateinit var configurationController: ConfigurationController

    @Mock
    private lateinit var statusBarStateController: SysuiStatusBarStateController

    @Mock
    private lateinit var dozeParameters: DozeParameters

    @Mock
    private lateinit var screenOffAnimationController: ScreenOffAnimationController

    @Mock
    private lateinit var userSwitchDialogController: UserSwitchDialogController

    @Mock
    private lateinit var uiEventLogger: UiEventLogger

    private lateinit var view: FrameLayout
    private lateinit var testableLooper: TestableLooper
    private lateinit var keyguardQsUserSwitchController: KeyguardQsUserSwitchController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        view = LayoutInflater.from(context)
                .inflate(R.layout.keyguard_qs_user_switch, null) as FrameLayout

        keyguardQsUserSwitchController = KeyguardQsUserSwitchController(
                view,
                context,
                context.resources,
                userSwitcherController,
                keyguardStateController,
                falsingManager,
                configurationController,
                statusBarStateController,
                dozeParameters,
                screenOffAnimationController,
                userSwitchDialogController,
                uiEventLogger)

        ViewUtils.attachView(view)
        testableLooper.processAllMessages()
        `when`(userSwitcherController.keyguardStateController).thenReturn(keyguardStateController)
        `when`(userSwitcherController.keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
        keyguardQsUserSwitchController.init()
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(view)
    }

    @Test
    fun testUiEventLogged() {
        view.findViewById<View>(R.id.kg_multi_user_avatar)?.performClick()
        verify(uiEventLogger, times(1))
                .log(LockscreenGestureLogger.LockscreenUiEvent.LOCKSCREEN_SWITCH_USER_TAP)
    }

    @Test
    fun testAvatarExistsWhenKeyguardGoingAway() {
        `when`(keyguardStateController.isShowing).thenReturn(false)
        `when`(keyguardStateController.isKeyguardGoingAway).thenReturn(true)
        keyguardQsUserSwitchController.updateKeyguardShowing(true /* forceViewUpdate */)
        assertThat(keyguardQsUserSwitchController.mUserAvatarView.isEmpty).isFalse()
    }

    @Test
    fun testAvatarExistsWhenKeyguardShown() {
        `when`(keyguardStateController.isShowing).thenReturn(true)
        `when`(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
        keyguardQsUserSwitchController.updateKeyguardShowing(true /* forceViewUpdate */)
        assertThat(keyguardQsUserSwitchController.mUserAvatarView.isEmpty).isFalse()
    }

    @Test
    fun testAvatarGoneWhenKeyguardGone() {
        `when`(keyguardStateController.isShowing).thenReturn(false)
        `when`(keyguardStateController.isKeyguardGoingAway).thenReturn(false)
        keyguardQsUserSwitchController.updateKeyguardShowing(true /* forceViewUpdate */)
        assertThat(keyguardQsUserSwitchController.mUserAvatarView.isEmpty).isTrue()
    }
}
