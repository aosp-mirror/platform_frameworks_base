/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.communal.view.viewmodel

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.domain.interactor.accessibilityInteractor
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.AuthenticationResult
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.common.ui.domain.interactor.configurationInteractor
import com.android.systemui.communal.ui.viewmodel.CommunalLockIconViewModel
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceEntrySourceInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import com.android.systemui.keyguard.ui.viewmodel.DeviceEntryIconViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.backgroundScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.statusbar.phone.statusBarKeyguardViewManager
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalLockIconViewModelTest(flags: FlagsParameterization) : SysuiTestCase() {
    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val Kosmos.underTest by
        Kosmos.Fixture {
            CommunalLockIconViewModel(
                context = context,
                configurationInteractor = configurationInteractor,
                deviceEntryInteractor = deviceEntryInteractor,
                keyguardInteractor = keyguardInteractor,
                keyguardViewController = { statusBarKeyguardViewManager },
                deviceEntrySourceInteractor = deviceEntrySourceInteractor,
                accessibilityInteractor = accessibilityInteractor,
            )
        }

    @Test
    fun isLongPressEnabled_unlocked() =
        kosmos.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            setLockscreenDismissible()
            assertThat(isLongPressEnabled).isTrue()
        }

    @Test
    fun isLongPressEnabled_lock() =
        kosmos.runTest {
            val isLongPressEnabled by collectLastValue(underTest.isLongPressEnabled)
            if (!SceneContainerFlag.isEnabled) {
                fakeKeyguardRepository.setKeyguardDismissible(false)
            }
            assertThat(isLongPressEnabled).isFalse()
        }

    @Test
    fun iconType_locked() =
        kosmos.runTest {
            val viewAttributes by collectLastValue(underTest.viewAttributes)
            if (!SceneContainerFlag.isEnabled) {
                fakeKeyguardRepository.setKeyguardDismissible(false)
            }
            assertThat(viewAttributes?.type).isEqualTo(DeviceEntryIconView.IconType.LOCK)
        }

    @Test
    fun iconType_unlocked() =
        kosmos.runTest {
            val viewAttributes by collectLastValue(underTest.viewAttributes)
            setLockscreenDismissible()
            assertThat(viewAttributes?.type).isEqualTo(DeviceEntryIconView.IconType.UNLOCK)
        }

    private suspend fun Kosmos.setLockscreenDismissible() {
        if (SceneContainerFlag.isEnabled) {
            // Need to set up a collection for the authentication to be propagated.
            backgroundScope.launch { kosmos.deviceUnlockedInteractor.deviceUnlockStatus.collect {} }
            assertThat(
                    kosmos.authenticationInteractor.authenticate(
                        FakeAuthenticationRepository.DEFAULT_PIN
                    )
                )
                .isEqualTo(AuthenticationResult.SUCCEEDED)
        } else {
            fakeKeyguardRepository.setKeyguardDismissible(true)
        }
        testScope.advanceTimeBy(
            DeviceEntryIconViewModel.UNLOCKED_DELAY_MS * 2
        ) // wait for unlocked delay
    }
}
