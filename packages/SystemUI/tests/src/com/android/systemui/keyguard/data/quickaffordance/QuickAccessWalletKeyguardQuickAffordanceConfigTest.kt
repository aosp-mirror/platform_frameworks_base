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
 * limitations under the License.
 *
 */

package com.android.systemui.keyguard.data.quickaffordance

import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.wallet.controller.QuickAccessWalletController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class QuickAccessWalletKeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var walletController: QuickAccessWalletController
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: QuickAccessWalletKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            QuickAccessWalletKeyguardQuickAffordanceConfig(
                context,
                walletController,
                activityStarter,
            )
    }

    @Test
    fun `affordance - keyguard showing - has wallet card - visible model`() = runBlockingTest {
        setUpState()
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        val visibleModel = latest as KeyguardQuickAffordanceConfig.LockScreenState.Visible
        assertThat(visibleModel.icon)
            .isEqualTo(
                Icon.Loaded(
                    drawable = ICON,
                    contentDescription =
                        ContentDescription.Resource(
                            res = R.string.accessibility_wallet_button,
                        ),
                )
            )
        job.cancel()
    }

    @Test
    fun `affordance - wallet not enabled - model is none`() = runBlockingTest {
        setUpState(isWalletEnabled = false)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - query not successful - model is none`() = runBlockingTest {
        setUpState(isWalletQuerySuccessful = false)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - missing icon - model is none`() = runBlockingTest {
        setUpState(hasWalletIcon = false)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - no selected card - model is none`() = runBlockingTest {
        setUpState(hasWalletIcon = false)
        var latest: KeyguardQuickAffordanceConfig.LockScreenState? = null

        val job = underTest.lockScreenState.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)

        job.cancel()
    }

    @Test
    fun onQuickAffordanceTriggered() {
        val animationController: ActivityLaunchAnimator.Controller = mock()
        val expandable: Expandable = mock {
            whenever(this.activityLaunchController()).thenReturn(animationController)
        }

        assertThat(underTest.onTriggered(expandable))
            .isEqualTo(KeyguardQuickAffordanceConfig.OnTriggeredResult.Handled)
        verify(walletController)
            .startQuickAccessUiIntent(
                activityStarter,
                animationController,
                /* hasCard= */ true,
            )
    }

    @Test
    fun `getPickerScreenState - default`() = runTest {
        setUpState()

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
    }

    @Test
    fun `getPickerScreenState - unavailable`() = runTest {
        setUpState(
            isWalletEnabled = false,
        )

        assertThat(underTest.getPickerScreenState())
            .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
    }

    @Test
    fun `getPickerScreenState - disabled when there is no icon`() = runTest {
        setUpState(
            hasWalletIcon = false,
        )

        assertThat(underTest.getPickerScreenState())
            .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Disabled::class.java)
    }

    @Test
    fun `getPickerScreenState - disabled when there is no card`() = runTest {
        setUpState(
            hasSelectedCard = false,
        )

        assertThat(underTest.getPickerScreenState())
            .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Disabled::class.java)
    }

    private fun setUpState(
        isWalletEnabled: Boolean = true,
        isWalletQuerySuccessful: Boolean = true,
        hasWalletIcon: Boolean = true,
        hasSelectedCard: Boolean = true,
    ) {
        whenever(walletController.isWalletEnabled).thenReturn(isWalletEnabled)

        val walletClient: QuickAccessWalletClient = mock()
        val icon: Drawable? =
            if (hasWalletIcon) {
                ICON
            } else {
                null
            }
        whenever(walletClient.tileIcon).thenReturn(icon)
        whenever(walletController.walletClient).thenReturn(walletClient)

        whenever(walletController.queryWalletCards(any())).thenAnswer { invocation ->
            with(
                invocation.arguments[0] as QuickAccessWalletClient.OnWalletCardsRetrievedCallback
            ) {
                if (isWalletQuerySuccessful) {
                    onWalletCardsRetrieved(
                        if (hasSelectedCard) {
                            GetWalletCardsResponse(listOf(mock()), 0)
                        } else {
                            GetWalletCardsResponse(emptyList(), 0)
                        }
                    )
                } else {
                    onWalletCardRetrievalError(mock())
                }
            }
        }
    }

    companion object {
        private val ICON: Drawable = mock()
    }
}
