/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.controller;

import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE;
import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE;

import android.content.Context;
import android.database.ContentObserver;
import android.provider.Settings;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletClientImpl;

import com.android.systemui.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.settings.SecureSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Controller to handle communication between SystemUI and Quick Access Wallet Client.
 */
@SysUISingleton
public class QuickAccessWalletController {

    /**
     * Event for the wallet status change, e.g. the default payment app change and the wallet
     * preference change.
     */
    public enum WalletChangeEvent {
        DEFAULT_PAYMENT_APP_CHANGE,
        WALLET_PREFERENCE_CHANGE,
    }

    private static final String TAG = "QAWController";
    private final Context mContext;
    private final Executor mExecutor;
    private final SecureSettings mSecureSettings;

    private QuickAccessWalletClient mQuickAccessWalletClient;
    private ContentObserver mWalletPreferenceObserver;
    private ContentObserver mDefaultPaymentAppObserver;
    private int mWalletPreferenceChangeEvents = 0;
    private int mDefaultPaymentAppChangeEvents = 0;
    private boolean mWalletEnabled = false;

    @Inject
    public QuickAccessWalletController(
            Context context,
            @Main Executor executor,
            SecureSettings secureSettings,
            QuickAccessWalletClient quickAccessWalletClient) {
        mContext = context;
        mExecutor = executor;
        mSecureSettings = secureSettings;
        mQuickAccessWalletClient = quickAccessWalletClient;
    }

    /**
     * Returns true if the Quick Access Wallet service & feature is available.
     */
    public boolean isWalletEnabled() {
        return mWalletEnabled;
    }

    /**
     * Returns the current instance of {@link QuickAccessWalletClient} in the controller.
     */
    public QuickAccessWalletClient getWalletClient() {
        return mQuickAccessWalletClient;
    }

    /**
     * Setup the wallet change observers per {@link WalletChangeEvent}
     *
     * @param cardsRetriever a callback that retrieves the wallet cards
     * @param events {@link WalletChangeEvent} need to be handled.
     */
    public void setupWalletChangeObservers(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever,
            WalletChangeEvent... events) {
        for (WalletChangeEvent event : events) {
            if (event == WALLET_PREFERENCE_CHANGE) {
                setupWalletPreferenceObserver();
            } else if (event == DEFAULT_PAYMENT_APP_CHANGE) {
                setupDefaultPaymentAppObserver(cardsRetriever);
            }
        }
    }

    /**
     * Unregister wallet change observers per {@link WalletChangeEvent} if needed.
     *
     */
    public void unregisterWalletChangeObservers(WalletChangeEvent... events) {
        for (WalletChangeEvent event : events) {
            if (event == WALLET_PREFERENCE_CHANGE && mWalletPreferenceObserver != null) {
                mWalletPreferenceChangeEvents--;
                if (mWalletPreferenceChangeEvents == 0) {
                    mSecureSettings.unregisterContentObserver(mWalletPreferenceObserver);
                }
            } else if (event == DEFAULT_PAYMENT_APP_CHANGE && mDefaultPaymentAppObserver != null) {
                mDefaultPaymentAppChangeEvents--;
                if (mDefaultPaymentAppChangeEvents == 0) {
                    mSecureSettings.unregisterContentObserver(mDefaultPaymentAppObserver);
                }
            }
        }
    }

    /**
     * Update the "show wallet" preference.
     */
    public void updateWalletPreference() {
        mWalletEnabled = mQuickAccessWalletClient.isWalletServiceAvailable()
                && mQuickAccessWalletClient.isWalletFeatureAvailable()
                && mQuickAccessWalletClient.isWalletFeatureAvailableWhenDeviceLocked();
    }

    /**
     * Query the wallet cards from {@link QuickAccessWalletClient}.
     *
     * @param cardsRetriever a callback to retrieve wallet cards.
     */
    public void queryWalletCards(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever) {
        int cardWidth =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width);
        int cardHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height);
        int iconSizePx = mContext.getResources().getDimensionPixelSize(R.dimen.wallet_icon_size);
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidth, cardHeight, iconSizePx, /* maxCards= */ 1);
        mQuickAccessWalletClient.getWalletCards(mExecutor, request, cardsRetriever);
    }

    /**
     * Re-create the {@link QuickAccessWalletClient} of the controller.
     */
    public void reCreateWalletClient() {
        mQuickAccessWalletClient = QuickAccessWalletClient.create(mContext);
    }

    private void setupDefaultPaymentAppObserver(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever) {
        if (mDefaultPaymentAppObserver == null) {
            mDefaultPaymentAppObserver = new ContentObserver(null /* handler */) {
                @Override
                public void onChange(boolean selfChange) {
                    mExecutor.execute(() -> {
                        reCreateWalletClient();
                        updateWalletPreference();
                        queryWalletCards(cardsRetriever);
                    });
                }
            };

            mSecureSettings.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT),
                    false /* notifyForDescendants */,
                    mDefaultPaymentAppObserver);
        }
        mDefaultPaymentAppChangeEvents++;
    }

    private void setupWalletPreferenceObserver() {
        if (mWalletPreferenceObserver == null) {
            mWalletPreferenceObserver = new ContentObserver(null /* handler */) {
                @Override
                public void onChange(boolean selfChange) {
                    mExecutor.execute(() -> {
                        updateWalletPreference();
                    });
                }
            };

            mSecureSettings.registerContentObserver(
                    Settings.Secure.getUriFor(QuickAccessWalletClientImpl.SETTING_KEY),
                    false /* notifyForDescendants */,
                    mWalletPreferenceObserver);
        }
        mWalletPreferenceChangeEvents++;
    }
}
