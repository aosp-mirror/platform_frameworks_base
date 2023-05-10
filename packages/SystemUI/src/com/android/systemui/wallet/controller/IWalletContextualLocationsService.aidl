package com.android.systemui.wallet.controller;

import com.android.systemui.wallet.controller.IWalletCardsUpdatedListener;

interface IWalletContextualLocationsService {
  void addWalletCardsUpdatedListener(in IWalletCardsUpdatedListener listener);

  void onWalletContextualLocationsStateUpdated(in List<String> storeLocations);
}