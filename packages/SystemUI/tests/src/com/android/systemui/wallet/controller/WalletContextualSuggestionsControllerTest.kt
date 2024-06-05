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

package com.android.systemui.wallet.controller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.quickaccesswallet.GetWalletCardsResponse
import android.service.quickaccesswallet.QuickAccessWalletClient
import android.service.quickaccesswallet.WalletCard
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class WalletContextualSuggestionsControllerTest : SysuiTestCase() {

    @Mock private lateinit var walletController: QuickAccessWalletController
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var mockContext: Context
    @Captor private lateinit var broadcastReceiver: ArgumentCaptor<BroadcastReceiver>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(broadcastDispatcher.broadcastFlow(any(), nullable(), anyInt(), nullable()))
            .thenCallRealMethod()
        whenever(
                broadcastDispatcher.broadcastFlow<Unit>(
                    any(),
                    nullable(),
                    anyInt(),
                    nullable(),
                    any()
                )
            )
            .thenCallRealMethod()

        whenever(featureFlags.isEnabled(eq(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)))
            .thenReturn(true)

        whenever(CARD_1.cardId).thenReturn(ID_1)
        whenever(CARD_1.cardType).thenReturn(WalletCard.CARD_TYPE_NON_PAYMENT)
        whenever(CARD_2.cardId).thenReturn(ID_2)
        whenever(CARD_2.cardType).thenReturn(WalletCard.CARD_TYPE_NON_PAYMENT)
        whenever(CARD_3.cardId).thenReturn(ID_3)
        whenever(CARD_3.cardType).thenReturn(WalletCard.CARD_TYPE_NON_PAYMENT)
        whenever(PAYMENT_CARD.cardId).thenReturn(PAYMENT_ID)
        whenever(PAYMENT_CARD.cardType).thenReturn(WalletCard.CARD_TYPE_PAYMENT)
    }

    @Test
    fun state_hasWalletCardsCallbacksCalled() = runTest {
        setUpWalletClient(listOf(CARD_1, CARD_2, PAYMENT_CARD))
        val controller = createWalletContextualSuggestionsController(backgroundScope)
        var latest1 = emptyList<WalletCard>()
        var latest2 = emptyList<WalletCard>()
        val callback1: (List<WalletCard>) -> Unit = { latest1 = it }
        val callback2: (List<WalletCard>) -> Unit = { latest2 = it }

        runCurrent()
        controller.registerWalletCardsReceivedCallback(callback1)
        controller.registerWalletCardsReceivedCallback(callback2)
        controller.unregisterWalletCardsReceivedCallback(callback2)
        runCurrent()
        verifyBroadcastReceiverRegistered()
        turnScreenOn()
        runCurrent()

        assertThat(latest1).containsExactly(CARD_1, CARD_2)
        assertThat(latest2).isEmpty()
    }

    @Test
    fun state_noWalletCards_setSuggestionCards() = runTest {
        setUpWalletClient(emptyList())
        val controller = createWalletContextualSuggestionsController(backgroundScope)
        val latest =
            collectLastValue(
                controller.contextualSuggestionCards,
            )

        runCurrent()
        verifyBroadcastReceiverRegistered()
        turnScreenOn()
        controller.setSuggestionCardIds(setOf(ID_1, ID_2))

        assertThat(latest()).isEmpty()
    }

    @Test
    fun state_hasWalletCards_setAndUpdateSuggestionCards() = runTest {
        setUpWalletClient(listOf(CARD_1, CARD_2, PAYMENT_CARD))
        val controller = createWalletContextualSuggestionsController(backgroundScope)
        val latest =
            collectLastValue(
                controller.contextualSuggestionCards,
            )

        runCurrent()
        verifyBroadcastReceiverRegistered()
        turnScreenOn()

        controller.setSuggestionCardIds(setOf(ID_1, ID_2))
        assertThat(latest()).containsExactly(CARD_1, CARD_2)
        controller.setSuggestionCardIds(emptySet())
        assertThat(latest()).isEmpty()
    }

    @Test
    fun state_walletCardsError() = runTest {
        setUpWalletClient(shouldFail = true)
        val controller = createWalletContextualSuggestionsController(backgroundScope)
        val latest =
            collectLastValue(
                controller.contextualSuggestionCards,
            )

        runCurrent()
        verifyBroadcastReceiverRegistered()
        controller.setSuggestionCardIds(setOf(ID_1, ID_2))

        assertThat(latest()).isEmpty()
    }

    @Test
    fun state_hasWalletCards_receivedContextualCards_featureDisabled() = runTest {
        whenever(featureFlags.isEnabled(eq(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)))
            .thenReturn(false)
        setUpWalletClient(listOf(CARD_1, CARD_2, PAYMENT_CARD))
        val controller = createWalletContextualSuggestionsController(backgroundScope)
        val latest =
            collectLastValue(
                controller.contextualSuggestionCards,
            )

        runCurrent()
        verify(broadcastDispatcher, never()).broadcastFlow(any(), nullable(), anyInt(), nullable())
        controller.setSuggestionCardIds(setOf(ID_1, ID_2))

        assertThat(latest()).isEmpty()
    }

    private fun createWalletContextualSuggestionsController(
        scope: CoroutineScope
    ): WalletContextualSuggestionsController {
        return WalletContextualSuggestionsController(
            scope,
            walletController,
            broadcastDispatcher,
            featureFlags
        )
    }

    private fun verifyBroadcastReceiverRegistered() {
        verify(broadcastDispatcher)
            .registerReceiver(
                capture(broadcastReceiver),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable()
            )
    }

    private fun turnScreenOn() {
        broadcastReceiver.value.onReceive(mockContext, Intent(Intent.ACTION_SCREEN_ON))
    }

    private fun setUpWalletClient(
        cards: List<WalletCard> = emptyList(),
        shouldFail: Boolean = false
    ) {
        whenever(walletController.queryWalletCards(any(), anyInt())).thenAnswer { invocation ->
            with(
                invocation.arguments[0] as QuickAccessWalletClient.OnWalletCardsRetrievedCallback
            ) {
                if (shouldFail) {
                    onWalletCardRetrievalError(mock())
                } else {
                    onWalletCardsRetrieved(GetWalletCardsResponse(cards, 0))
                }
            }
        }
    }

    companion object {
        private const val ID_1: String = "123"
        private val CARD_1: WalletCard = mock()
        private const val ID_2: String = "456"
        private val CARD_2: WalletCard = mock()
        private const val ID_3: String = "789"
        private val CARD_3: WalletCard = mock()
        private const val PAYMENT_ID: String = "payment"
        private val PAYMENT_CARD: WalletCard = mock()
    }
}
