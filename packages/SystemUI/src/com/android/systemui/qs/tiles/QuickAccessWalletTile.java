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

package com.android.systemui.qs.tiles;

import static android.provider.Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.WalletCard;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.wallet.ui.WalletActivity;

import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/** Quick settings tile: Quick access wallet **/
public class QuickAccessWalletTile extends QSTileImpl<QSTile.State> {

    private static final String TAG = "QuickAccessWalletTile";
    private static final String FEATURE_CHROME_OS = "org.chromium.arc";

    private final CharSequence mLabel = mContext.getString(R.string.wallet_title);
    private final WalletCardRetriever mCardRetriever = new WalletCardRetriever();
    private final KeyguardStateController mKeyguardStateController;
    private final PackageManager mPackageManager;
    private final SecureSettings mSecureSettings;
    private final Executor mExecutor;
    private final FeatureFlags mFeatureFlags;

    private QuickAccessWalletClient mQuickAccessWalletClient;
    private ContentObserver mDefaultPaymentAppObserver;
    private WalletCard mSelectedCard;
    @VisibleForTesting Drawable mCardViewDrawable;

    @Inject
    public QuickAccessWalletTile(
            QSHost host,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            KeyguardStateController keyguardStateController,
            PackageManager packageManager,
            SecureSettings secureSettings,
            @Main Executor executor,
            FeatureFlags featureFlags) {
        super(host, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mQuickAccessWalletClient = QuickAccessWalletClient.create(mContext);
        mKeyguardStateController = keyguardStateController;
        mPackageManager = packageManager;
        mSecureSettings = secureSettings;
        mExecutor = executor;
        mFeatureFlags = featureFlags;
    }


    @Override
    public State newTileState() {
        State state = new State();
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (listening) {
            setupDefaultPaymentAppObserver();
            // Re-create wallet client to avoid a client that doesn't have service info is living
            // too long.
            if (!mQuickAccessWalletClient.isWalletServiceAvailable()) {
                reCreateWalletClient();
            }
            queryWalletCards();
        }
    }

    @Override
    protected void handleClick(@Nullable View view) {
        ActivityLaunchAnimator.Controller animationController =
                view == null ? null : ActivityLaunchAnimator.Controller.fromView(view);

        mUiHandler.post(() -> {
            if (mSelectedCard != null) {
                Intent intent = new Intent(mContext, WalletActivity.class)
                        .setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (mKeyguardStateController.isUnlocked()) {
                    mActivityStarter.startActivity(intent, true /* dismissShade */,
                            animationController);
                } else {
                    mHost.collapsePanels();
                    // Do not use ActivityStarter here because the WalletActivity is required to be
                    // started without prompting keyguard when the device is locked.
                    mContext.startActivity(intent);
                }
            } else {
                if (mQuickAccessWalletClient.createWalletIntent() == null) {
                    Log.w(TAG, "Could not get intent of the wallet app.");
                    return;
                }
                mActivityStarter.postStartActivityDismissingKeyguard(
                        mQuickAccessWalletClient.createWalletIntent(), /* delay= */ 0,
                        animationController);
            }
        });
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        CharSequence label = mQuickAccessWalletClient.getServiceLabel();
        state.label = label == null ? mLabel : label;
        state.contentDescription = state.label;
        state.icon = ResourceIcon.get(R.drawable.ic_wallet_lockscreen);
        boolean isDeviceLocked = !mKeyguardStateController.isUnlocked();
        if (mQuickAccessWalletClient.isWalletServiceAvailable()) {
            if (mSelectedCard != null) {
                if (isDeviceLocked) {
                    state.state = Tile.STATE_INACTIVE;
                    state.secondaryLabel =
                            mContext.getString(R.string.wallet_secondary_label_device_locked);
                } else {
                    state.state = Tile.STATE_ACTIVE;
                    state.secondaryLabel = mSelectedCard.getContentDescription();
                }
            } else {
                state.state = Tile.STATE_INACTIVE;
                state.secondaryLabel = mContext.getString(R.string.wallet_secondary_label_no_card);
            }
            state.stateDescription = state.secondaryLabel;
        } else {
            state.state = Tile.STATE_UNAVAILABLE;
            state.secondaryLabel = null;
        }
        state.sideViewCustomDrawable = isDeviceLocked ? null : mCardViewDrawable;
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Override
    public boolean isAvailable() {
        return mFeatureFlags.isQuickAccessWalletEnabled()
                && mPackageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
                && !mPackageManager.hasSystemFeature(FEATURE_CHROME_OS)
                && mSecureSettings.getString(NFC_PAYMENT_DEFAULT_COMPONENT) != null;
    }

    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        CharSequence label = mQuickAccessWalletClient.getServiceLabel();
        return label == null ? mLabel : label;
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        if (mDefaultPaymentAppObserver != null) {
            mSecureSettings.unregisterContentObserver(mDefaultPaymentAppObserver);
        }
        mQuickAccessWalletClient = null;
    }

    @VisibleForTesting
    void overrideQuickAccessWalletClientForTest(QuickAccessWalletClient quickAccessWalletClient) {
        mQuickAccessWalletClient = quickAccessWalletClient;
    }

    @VisibleForTesting
    QuickAccessWalletClient getQuickAccessWalletClient() {
        return mQuickAccessWalletClient;
    }

    private void queryWalletCards() {
        if (!mQuickAccessWalletClient.isWalletFeatureAvailable()) {
            Log.w(TAG, "QAW feature not unavailable, unable to query wallet cards,");
            return;
        }
        int cardWidth =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width);
        int cardHeight =
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height);
        int iconSizePx = mContext.getResources().getDimensionPixelSize(R.dimen.wallet_icon_size);
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidth, cardHeight, iconSizePx, /* maxCards= */ 1);
        mQuickAccessWalletClient.getWalletCards(mExecutor, request, mCardRetriever);
    }

    private void reCreateWalletClient() {
        mQuickAccessWalletClient = QuickAccessWalletClient.create(mContext);
    }

    private void setupDefaultPaymentAppObserver() {
        if (mDefaultPaymentAppObserver == null) {
            mDefaultPaymentAppObserver = new ContentObserver(null /* handler */) {
                @Override
                public void onChange(boolean selfChange) {
                    mExecutor.execute(() -> {
                        reCreateWalletClient();
                        queryWalletCards();
                    });
                }
            };

            mSecureSettings.registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT),
                    false /* notifyForDescendants */,
                    mDefaultPaymentAppObserver);
        }
    }

    private class WalletCardRetriever implements
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback {

        @Override
        public void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response) {
            Log.i(TAG, "Successfully retrieved wallet cards.");
            List<WalletCard> cards = response.getWalletCards();
            if (cards.isEmpty()) {
                Log.d(TAG, "No wallet cards exist.");
                mCardViewDrawable = null;
                mSelectedCard = null;
                refreshState();
                return;
            }
            int selectedIndex = response.getSelectedIndex();
            if (selectedIndex >= cards.size()) {
                Log.w(TAG, "Error retrieving cards: Invalid selected card index.");
                mSelectedCard = null;
                mCardViewDrawable = null;
                return;
            }
            mSelectedCard = cards.get(selectedIndex);
            mCardViewDrawable = mSelectedCard.getCardImage().loadDrawable(mContext);
            refreshState();
        }

        @Override
        public void onWalletCardRetrievalError(@NonNull GetWalletCardsError error) {
            Log.w(TAG, "Error retrieve wallet cards");
            mCardViewDrawable = null;
            mSelectedCard = null;
            refreshState();
        }
    }
}
