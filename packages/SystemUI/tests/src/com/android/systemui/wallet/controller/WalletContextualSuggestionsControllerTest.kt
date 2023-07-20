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
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.util.ArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class WalletContextualSuggestionsControllerTest : SysuiTestCase() {

    @Mock private lateinit var walletController: QuickAccessWalletController
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var mockContext: Context
    @Captor private lateinit var broadcastReceiver: ArgumentCaptor<BroadcastReceiver>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(
                broadcastDispatcher.broadcastFlow<List<String>?>(
                    any(),
                    isNull(),
                    any(),
                    any(),
                    any()
                )
            )
            .thenCallRealMethod()

        whenever(featureFlags.isEnabled(eq(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)))
            .thenReturn(true)

        whenever(CARD_1.cardId).thenReturn(ID_1)
        whenever(CARD_2.cardId).thenReturn(ID_2)
        whenever(CARD_3.cardId).thenReturn(ID_3)
    }

    @Test
    fun `state - has wallet cards - received contextual cards`() = runTest {
        setUpWalletClient(listOf(CARD_1, CARD_2))
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verifyRegistered()
        broadcastReceiver.value.onReceive(
            mockContext,
            createContextualCardsIntent(listOf(ID_1, ID_2))
        )

        assertThat(latest()).containsExactly(CARD_1, CARD_2)
    }

    @Test
    fun `state - no wallet cards - received contextual cards`() = runTest {
        setUpWalletClient(emptyList())
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verifyRegistered()
        broadcastReceiver.value.onReceive(
            mockContext,
            createContextualCardsIntent(listOf(ID_1, ID_2))
        )

        assertThat(latest()).isEmpty()
    }

    @Test
    fun `state - has wallet cards - no contextual cards`() = runTest {
        setUpWalletClient(listOf(CARD_1, CARD_2))
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verifyRegistered()
        broadcastReceiver.value.onReceive(mockContext, createContextualCardsIntent(emptyList()))

        assertThat(latest()).isEmpty()
    }

    @Test
    fun `state - wallet cards error`() = runTest {
        setUpWalletClient(shouldFail = true)
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verifyRegistered()
        broadcastReceiver.value.onReceive(
            mockContext,
            createContextualCardsIntent(listOf(ID_1, ID_2))
        )

        assertThat(latest()).isEmpty()
    }

    @Test
    fun `state - no contextual cards extra`() = runTest {
        setUpWalletClient(listOf(CARD_1, CARD_2))
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verifyRegistered()
        broadcastReceiver.value.onReceive(mockContext, Intent(INTENT_NAME))

        assertThat(latest()).isEmpty()
    }

    @Test
    fun `state - has wallet cards - received contextual cards - feature disabled`() = runTest {
        whenever(featureFlags.isEnabled(eq(Flags.ENABLE_WALLET_CONTEXTUAL_LOYALTY_CARDS)))
            .thenReturn(false)
        setUpWalletClient(listOf(CARD_1, CARD_2))
        val latest =
            collectLastValue(
                createWalletContextualSuggestionsController(backgroundScope)
                    .contextualSuggestionCards,
            )

        runCurrent()
        verify(broadcastDispatcher, never()).broadcastFlow(any(), isNull(), any(), any())
        assertThat(latest()).isNull()
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

    private fun verifyRegistered() {
        verify(broadcastDispatcher)
            .registerReceiver(capture(broadcastReceiver), any(), isNull(), isNull(), any(), any())
    }

    private fun createContextualCardsIntent(
        ids: List<String> = emptyList(),
    ): Intent {
        val intent = Intent(INTENT_NAME)
        intent.putStringArrayListExtra("cardIds", ArrayList(ids))
        return intent
    }

    private fun setUpWalletClient(
        cards: List<WalletCard> = emptyList(),
        shouldFail: Boolean = false
    ) {
        whenever(walletController.queryWalletCards(any())).thenAnswer { invocation ->
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
        private val INTENT_NAME: String = "WalletSuggestionsIntent"
    }
}
