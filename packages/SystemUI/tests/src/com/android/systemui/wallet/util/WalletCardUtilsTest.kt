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

package com.android.systemui.wallet.util

import android.service.quickaccesswallet.WalletCard
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Test class for WalletCardUtils */
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@SmallTest
class WalletCardUtilsTest : SysuiTestCase() {

    private val paymentCard = createWalletCardWithType(WalletCard.CARD_TYPE_PAYMENT)
    private val nonPaymentCard = createWalletCardWithType(WalletCard.CARD_TYPE_NON_PAYMENT)
    private val unknownCard = createWalletCardWithType(WalletCard.CARD_TYPE_UNKNOWN)

    @Test
    fun paymentCards_cardTypesAllUnknown_getsAllCards() {
        val walletCardList =
            mutableListOf(
                createWalletCardWithType(WalletCard.CARD_TYPE_UNKNOWN),
                createWalletCardWithType(WalletCard.CARD_TYPE_UNKNOWN),
                createWalletCardWithType(WalletCard.CARD_TYPE_UNKNOWN)
            )

        assertThat(walletCardList).isEqualTo(getPaymentCards(walletCardList))
    }

    @Test
    fun paymentCards_cardTypesDifferent_onlyGetsPayment() {
        val walletCardList = mutableListOf(paymentCard, nonPaymentCard, unknownCard)

        assertThat(getPaymentCards(walletCardList)).isEqualTo(mutableListOf(paymentCard))
    }

    private fun createWalletCardWithType(cardType: Int): WalletCard {
        return WalletCard.Builder(
                /*cardId= */ CARD_ID,
                /*cardType= */ cardType,
                /*cardImage= */ mock(),
                /*contentDescription=  */ CARD_DESCRIPTION,
                /*pendingIntent= */ mock()
            )
            .build()
    }

    companion object {
        private const val CARD_ID: String = "ID"
        private const val CARD_DESCRIPTION: String = "Description"
    }
}
