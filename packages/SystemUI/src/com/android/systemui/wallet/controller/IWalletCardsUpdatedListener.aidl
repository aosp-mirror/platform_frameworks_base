package com.android.systemui.wallet.controller;

import android.service.quickaccesswallet.WalletCard;

interface IWalletCardsUpdatedListener {
  void registerNewWalletCards(in List<WalletCard> cards);
}