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

/**
 * Filters wallet cards to only those of [WalletCard.CARD_TYPE_PAYMENT], or returns all cards if
 * they are all of [WalletCard.CARD_TYPE_UNKNOWN] (maintaining pre-U behavior). Used by the wallet
 * card carousel, quick settings tile, and lock screen.
 */
fun getPaymentCards(walletCards: List<WalletCard>): List<WalletCard> {
    val atLeastOneKnownCardType = walletCards.any { it.cardType != WalletCard.CARD_TYPE_UNKNOWN }

    return if (atLeastOneKnownCardType) {
        walletCards.filter { it.cardType == WalletCard.CARD_TYPE_PAYMENT }
    } else {
        walletCards
    }
}
