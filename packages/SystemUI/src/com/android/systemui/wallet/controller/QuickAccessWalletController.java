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
import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.DEFAULT_WALLET_APP_CHANGE;
import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE;

import android.annotation.WorkerThread;
import android.app.PendingIntent;
import android.app.role.OnRoleHoldersChangedListener;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletClientImpl;
import android.service.quickaccesswallet.WalletCard;
import android.util.Log;

import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.SystemClock;
import com.android.systemui.wallet.ui.WalletActivity;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
        DEFAULT_WALLET_APP_CHANGE,
        WALLET_PREFERENCE_CHANGE,
    }

    private static final String TAG = "QAWController";
    private static final long RECREATION_TIME_WINDOW = TimeUnit.MINUTES.toMillis(10L);
    private final Context mContext;
    private final Executor mExecutor;
    private final Executor mBgExecutor;
    private final SecureSettings mSecureSettings;
    private final SystemClock mClock;

    private QuickAccessWalletClient mQuickAccessWalletClient;
    private ContentObserver mWalletPreferenceObserver;
    private RoleManager mRoleManager;
    private OnRoleHoldersChangedListener mDefaultWalletAppObserver;
    private ContentObserver mDefaultPaymentAppObserver;
    private int mWalletPreferenceChangeEvents = 0;
    private int mDefaultPaymentAppChangeEvents = 0;
    private int mDefaultWalletAppChangeEvents = 0;
    private boolean mWalletEnabled = false;
    private long mQawClientCreatedTimeMillis;

    @Inject
    public QuickAccessWalletController(
            Context context,
            @Main Executor executor,
            @Background Executor bgExecutor,
            SecureSettings secureSettings,
            QuickAccessWalletClient quickAccessWalletClient,
            SystemClock clock,
            RoleManager roleManager) {
        mContext = context;
        mExecutor = executor;
        mBgExecutor = bgExecutor;
        mSecureSettings = secureSettings;
        mRoleManager = roleManager;
        mQuickAccessWalletClient = quickAccessWalletClient;
        mClock = clock;
        mQawClientCreatedTimeMillis = mClock.elapsedRealtime();
    }

    public boolean isWalletRoleAvailable() {
        return mRoleManager.isRoleAvailable(RoleManager.ROLE_WALLET);
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
            } else if (event == DEFAULT_WALLET_APP_CHANGE) {
                setupDefaultWalletAppObserver(cardsRetriever);
            }
        }
    }

    /**
     * Unregister wallet change observers per {@link WalletChangeEvent} if needed.
     */
    public void unregisterWalletChangeObservers(WalletChangeEvent... events) {
        for (WalletChangeEvent event : events) {
            if (event == WALLET_PREFERENCE_CHANGE && mWalletPreferenceObserver != null) {
                mWalletPreferenceChangeEvents--;
                if (mWalletPreferenceChangeEvents == 0) {
                    mSecureSettings.unregisterContentObserverSync(mWalletPreferenceObserver);
                }
            } else if (event == DEFAULT_PAYMENT_APP_CHANGE && mDefaultPaymentAppObserver != null) {
                mDefaultPaymentAppChangeEvents--;
                if (mDefaultPaymentAppChangeEvents == 0) {
                    mSecureSettings.unregisterContentObserverSync(mDefaultPaymentAppObserver);
                }
            } else if (event == DEFAULT_WALLET_APP_CHANGE && mDefaultWalletAppObserver != null) {
                mDefaultWalletAppChangeEvents--;
                if (mDefaultWalletAppChangeEvents == 0) {
                    mRoleManager.removeOnRoleHoldersChangedListenerAsUser(mDefaultWalletAppObserver,
                            UserHandle.ALL);
                }
            }
        }
    }

    /**
     * Update the "show wallet" preference.
     * This should not be called on the main thread.
     */
    @WorkerThread
    public void updateWalletPreference() {
        mWalletEnabled = mQuickAccessWalletClient.isWalletServiceAvailable()
                && mQuickAccessWalletClient.isWalletFeatureAvailable()
                && mQuickAccessWalletClient.isWalletFeatureAvailableWhenDeviceLocked();
    }

    /**
     * Query the wallet cards from {@link QuickAccessWalletClient}.
     * This should not be called on the main thread.
     *
     * @param cardsRetriever a callback to retrieve wallet cards.
     * @param maxCards the maximum number of cards requested from the QuickAccessWallet
     */
    @WorkerThread
    public void queryWalletCards(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever, int maxCards) {
        if (mClock.elapsedRealtime() - mQawClientCreatedTimeMillis
                > RECREATION_TIME_WINDOW) {
            Log.i(TAG, "Re-creating the QAW client to avoid stale.");
            reCreateWalletClient();
        }
        if (!mQuickAccessWalletClient.isWalletFeatureAvailable()) {
            Log.d(TAG, "QuickAccessWallet feature is not available.");
            return;
        }
        int cardWidth =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width);
        int cardHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height);
        int iconSizePx = mContext.getResources().getDimensionPixelSize(R.dimen.wallet_icon_size);
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidth, cardHeight, iconSizePx, maxCards);
        mQuickAccessWalletClient.getWalletCards(mBgExecutor, request, cardsRetriever);
    }

    /**
     * Query the wallet cards from {@link QuickAccessWalletClient}.
     * This should not be called on the main thread.
     *
     * @param cardsRetriever a callback to retrieve wallet cards.
     */
    @WorkerThread
    public void queryWalletCards(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever) {
        queryWalletCards(cardsRetriever, /* maxCards= */ 1);
    }


    /**
     * Re-create the {@link QuickAccessWalletClient} of the controller.
     */
    public void reCreateWalletClient() {
        mQuickAccessWalletClient = QuickAccessWalletClient.create(mContext, mBgExecutor);
        mQawClientCreatedTimeMillis = mClock.elapsedRealtime();
    }

    /**
     * Starts the QuickAccessWallet UI: either the app's designated UI, or the built-in Wallet UI.
     *
     * If the service has configured itself so that
     * {@link QuickAccessWalletClient#useTargetActivityForQuickAccess()}
     * is true, or the service isn't providing any cards, use the target activity. Otherwise, use
     * the SysUi {@link WalletActivity}
     *
     * The Wallet target activity is defined as the {@link android.app.PendingIntent} returned by
     * {@link QuickAccessWalletClient#getWalletPendingIntent} if that is not null. If that is null,
     * then the {@link Intent} returned by {@link QuickAccessWalletClient#createWalletIntent()}. If
     * that too is null, then fall back to {@link WalletActivity}.
     *
     * @param activityStarter an {@link ActivityStarter} to launch the Intent or PendingIntent.
     * @param animationController an {@link ActivityTransitionAnimator.Controller} to provide a
     *                            smooth animation for the activity launch.
     * @param hasCard whether the service returns any cards.
     */
    public void startQuickAccessUiIntent(ActivityStarter activityStarter,
            ActivityTransitionAnimator.Controller animationController,
            boolean hasCard) {
        mQuickAccessWalletClient.getWalletPendingIntent(mExecutor,
                walletPendingIntent -> {
                    if (walletPendingIntent != null) {
                        startQuickAccessViaPendingIntent(walletPendingIntent, activityStarter,
                                animationController);
                        return;
                    }
                    Intent intent = null;
                    if (!hasCard) {
                        intent = mQuickAccessWalletClient.createWalletIntent();
                    }
                    if (intent == null) {
                        intent = getSysUiWalletIntent();
                    }
                    startQuickAccessViaIntent(intent, hasCard, activityStarter,
                            animationController);

                });
    }

    /**
     * Starts the {@link android.app.PendingIntent} for a {@link WalletCard}.
     *
     * This should be used to open a selected card from the QuickAccessWallet UI or
     * the settings tile.
     *
     * @param activityStarter an {@link ActivityStarter} to launch the Intent or PendingIntent.
     * @param animationController an {@link ActivityTransitionAnimator.Controller} to provide a
     *                            smooth animation for the activity launch.
     */
    public void startWalletCardPendingIntent(WalletCard card,
            ActivityStarter activityStarter,
            ActivityTransitionAnimator.Controller animationController) {
        activityStarter.postStartActivityDismissingKeyguard(
                card.getPendingIntent(), animationController);
    }

    private Intent getSysUiWalletIntent() {
        return new Intent(mContext, WalletActivity.class)
                .setAction(Intent.ACTION_VIEW);
    }

    private void startQuickAccessViaIntent(Intent intent,
            boolean hasCard,
            ActivityStarter activityStarter,
            ActivityTransitionAnimator.Controller animationController) {
        if (hasCard) {
            activityStarter.startActivity(intent, true /* dismissShade */,
                    animationController, true /* showOverLockscreenWhenLocked */);
        } else {
            activityStarter.postStartActivityDismissingKeyguard(
                    intent,
                    /* delay= */ 0,
                    animationController);
        }
    }

    private void startQuickAccessViaPendingIntent(PendingIntent pendingIntent,
            ActivityStarter activityStarter,
            ActivityTransitionAnimator.Controller animationController) {
        activityStarter.postStartActivityDismissingKeyguard(
                pendingIntent,
                animationController);

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

            mSecureSettings.registerContentObserverForUserSync(
                    Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT,
                    false /* notifyForDescendants */,
                    mDefaultPaymentAppObserver,
                    UserHandle.USER_ALL);
        }
        mDefaultPaymentAppChangeEvents++;
    }

    private void setupDefaultWalletAppObserver(
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback cardsRetriever) {
        if (mDefaultWalletAppObserver == null) {
            mDefaultWalletAppObserver = (roleName, user) -> {
                if (!roleName.equals(RoleManager.ROLE_WALLET)) {
                    return;
                }
                mExecutor.execute(() -> {
                    reCreateWalletClient();
                    updateWalletPreference();
                    queryWalletCards(cardsRetriever);
                });
            };
            mRoleManager.addOnRoleHoldersChangedListenerAsUser(mExecutor,
                    mDefaultWalletAppObserver, UserHandle.ALL);
        }
        mDefaultWalletAppChangeEvents++;
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

            mSecureSettings.registerContentObserverForUserSync(
                    QuickAccessWalletClientImpl.SETTING_KEY,
                    false /* notifyForDescendants */,
                    mWalletPreferenceObserver,
                    UserHandle.USER_ALL);
        }
        mWalletPreferenceChangeEvents++;
    }
}
