/*
 *  Copyright (C) 2022 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.android.systemui.keyguard.domain.quickaffordance

import android.graphics.drawable.Drawable
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.wallet.controller.QuickAccessWalletController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

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
                walletController,
                activityStarter,
            )
    }

    @Test
    fun `affordance - keyguard showing - has wallet card - visible model`() = runBlockingTest {
        setUpState()
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

        val visibleModel = latest as KeyguardQuickAffordanceConfig.State.Visible
        assertThat(visibleModel.icon).isEqualTo(ContainedDrawable.WithDrawable(ICON))
        assertThat(visibleModel.contentDescriptionResourceId).isNotNull()
        job.cancel()
    }

    @Test
    fun `affordance - wallet not enabled - model is none`() = runBlockingTest {
        setUpState(isWalletEnabled = false)
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.State.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - query not successful - model is none`() = runBlockingTest {
        setUpState(isWalletQuerySuccessful = false)
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.State.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - missing icon - model is none`() = runBlockingTest {
        setUpState(hasWalletIcon = false)
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.State.Hidden)

        job.cancel()
    }

    @Test
    fun `affordance - no selected card - model is none`() = runBlockingTest {
        setUpState(hasWalletIcon = false)
        var latest: KeyguardQuickAffordanceConfig.State? = null

        val job = underTest.state.onEach { latest = it }.launchIn(this)

        assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.State.Hidden)

        job.cancel()
    }

    @Test
    fun onQuickAffordanceClicked() {
        val animationController: ActivityLaunchAnimator.Controller = mock()

        assertThat(underTest.onQuickAffordanceClicked(animationController))
            .isEqualTo(KeyguardQuickAffordanceConfig.OnClickedResult.Handled)
        verify(walletController)
            .startQuickAccessUiIntent(
                activityStarter,
                animationController,
                /* hasCard= */ true,
            )
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
