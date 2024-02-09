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
import android.service.quickaccesswallet.WalletCard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.wallet.controller.QuickAccessWalletController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class QuickAccessWalletKeyguardQuickAffordanceConfigTest : SysuiTestCase() {

    @Mock private lateinit var walletController: QuickAccessWalletController
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope

    private lateinit var underTest: QuickAccessWalletKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)

        underTest =
            QuickAccessWalletKeyguardQuickAffordanceConfig(
                context,
                testDispatcher,
                walletController,
                activityStarter,
            )
    }

    @Test
    fun affordance_keyguardShowing_hasWalletCard_visibleModel() =
        testScope.runTest {
            setUpState()

            val latest by collectLastValue(underTest.lockScreenState)

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
        }

    @Test
    fun affordance_keyguardShowing_hasNonPaymentCard_modelIsNone() =
        testScope.runTest {
            setUpState(cardType = WalletCard.CARD_TYPE_NON_PAYMENT)

            val latest by collectLastValue(underTest.lockScreenState)

            assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun affordance_keyguardShowing_hasPaymentCard_visibleModel() =
        testScope.runTest {
            setUpState(cardType = WalletCard.CARD_TYPE_PAYMENT)

            val latest by collectLastValue(underTest.lockScreenState)

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
        }

    @Test
    fun affordance_walletFeatureNotEnabled_modelIsNone() =
        testScope.runTest {
            setUpState(isWalletFeatureAvailable = false)

            val latest by collectLastValue(underTest.lockScreenState)

            assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun affordance_queryNotSuccessful_modelIsNone() =
        testScope.runTest {
            setUpState(isWalletQuerySuccessful = false)

            val latest by collectLastValue(underTest.lockScreenState)

            assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun affordance_noSelectedCard_modelIsNone() =
        testScope.runTest {
            setUpState(hasSelectedCard = false)

            val latest by collectLastValue(underTest.lockScreenState)

            assertThat(latest).isEqualTo(KeyguardQuickAffordanceConfig.LockScreenState.Hidden)
        }

    @Test
    fun onQuickAffordanceTriggered() {
        val animationController: ActivityTransitionAnimator.Controller = mock()
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
    fun getPickerScreenState_default() =
        testScope.runTest {
            setUpState()

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.Default())
        }

    @Test
    fun getPickerScreenState_unavailable() =
        testScope.runTest {
            setUpState(
                isWalletServiceAvailable = false,
            )

            assertThat(underTest.getPickerScreenState())
                .isEqualTo(KeyguardQuickAffordanceConfig.PickerScreenState.UnavailableOnDevice)
        }

    @Test
    fun getPickerScreenState_disabledWhenTheFeatureIsNotEnabled() =
        testScope.runTest {
            setUpState(
                isWalletFeatureAvailable = false,
            )

            assertThat(underTest.getPickerScreenState())
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Disabled::class.java)
        }

    @Test
    fun getPickerScreenState_disabledWhenThereIsNoCard() =
        testScope.runTest {
            setUpState(
                hasSelectedCard = false,
            )

            assertThat(underTest.getPickerScreenState())
                .isInstanceOf(KeyguardQuickAffordanceConfig.PickerScreenState.Disabled::class.java)
        }

    private fun setUpState(
        isWalletFeatureAvailable: Boolean = true,
        isWalletServiceAvailable: Boolean = true,
        isWalletQuerySuccessful: Boolean = true,
        hasSelectedCard: Boolean = true,
        cardType: Int = WalletCard.CARD_TYPE_UNKNOWN
    ) {
        val walletClient: QuickAccessWalletClient = mock()
        whenever(walletClient.tileIcon).thenReturn(ICON)
        whenever(walletClient.isWalletServiceAvailable).thenReturn(isWalletServiceAvailable)
        whenever(walletClient.isWalletFeatureAvailable).thenReturn(isWalletFeatureAvailable)

        whenever(walletController.walletClient).thenReturn(walletClient)

        whenever(walletController.queryWalletCards(any())).thenAnswer { invocation ->
            with(
                invocation.arguments[0] as QuickAccessWalletClient.OnWalletCardsRetrievedCallback
            ) {
                if (isWalletQuerySuccessful) {
                    onWalletCardsRetrieved(
                        if (hasSelectedCard) {
                            GetWalletCardsResponse(
                                listOf(
                                    WalletCard.Builder(
                                            /*cardId= */ CARD_ID,
                                            /*cardType= */ cardType,
                                            /*cardImage= */ mock(),
                                            /*contentDescription=  */ CARD_DESCRIPTION,
                                            /*pendingIntent= */ mock()
                                        )
                                        .build()
                                ),
                                0
                            )
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
        private const val CARD_ID: String = "Id"
        private const val CARD_DESCRIPTION: String = "Description"
    }
}
