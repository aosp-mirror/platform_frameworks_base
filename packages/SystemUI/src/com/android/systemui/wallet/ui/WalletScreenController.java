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

package com.android.systemui.wallet.ui;

import static com.android.systemui.wallet.util.WalletCardUtilsKt.getPaymentCards;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.SelectWalletCardRequest;
import android.service.quickaccesswallet.WalletCard;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.res.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Controller for the wallet card carousel screen. */
public class WalletScreenController implements
        WalletCardCarousel.OnSelectionListener,
        QuickAccessWalletClient.OnWalletCardsRetrievedCallback,
        KeyguardStateController.Callback {

    private static final String TAG = "WalletScreenCtrl";
    private static final String PREFS_WALLET_VIEW_HEIGHT = "wallet_view_height";
    private static final int MAX_CARDS = 10;
    private static final long SELECTION_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(30);

    private Context mContext;
    private final QuickAccessWalletClient mWalletClient;
    private final ActivityStarter mActivityStarter;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final Runnable mSelectionRunnable = this::selectCard;
    private final SharedPreferences mPrefs;
    private final WalletView mWalletView;
    private final WalletCardCarousel mCardCarousel;
    private final FalsingManager mFalsingManager;
    private final UiEventLogger mUiEventLogger;


    @VisibleForTesting
    String mSelectedCardId;
    @VisibleForTesting
    boolean mIsDismissed;

    public WalletScreenController(
            Context context,
            WalletView walletView,
            QuickAccessWalletClient walletClient,
            ActivityStarter activityStarter,
            Executor executor,
            Handler handler,
            UserTracker userTracker,
            FalsingManager falsingManager,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController,
            UiEventLogger uiEventLogger) {
        mContext = context;
        mWalletClient = walletClient;
        mActivityStarter = activityStarter;
        mExecutor = executor;
        mHandler = handler;
        mFalsingManager = falsingManager;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
        mUiEventLogger = uiEventLogger;
        mPrefs = userTracker.getUserContext().getSharedPreferences(TAG, Context.MODE_PRIVATE);
        mWalletView = walletView;
        mWalletView.setMinimumHeight(getExpectedMinHeight());
        mWalletView.setLayoutParams(
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mCardCarousel = mWalletView.getCardCarousel();
        if (mCardCarousel != null) {
            mCardCarousel.setSelectionListener(this);
        }
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when cards
     * are retrieved successfully from the service. This is called on {@link #mExecutor}.
     */
    @Override
    public void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response) {
        if (mIsDismissed) {
            return;
        }
        Log.i(TAG, "Successfully retrieved wallet cards.");
        List<WalletCard> walletCards = getPaymentCards(response.getWalletCards());

        List<WalletCardViewInfo> paymentCardData = walletCards.stream().map(
                card -> new QAWalletCardViewInfo(mContext, card)
        ).collect(Collectors.toList());

        // Get on main thread for UI updates.
        mHandler.post(() -> {
            if (mIsDismissed) {
                return;
            }
            if (paymentCardData.isEmpty()) {
                showEmptyStateView();
            } else {
                int selectedIndex = response.getSelectedIndex();
                if (selectedIndex >= paymentCardData.size()) {
                    Log.w(TAG, "Invalid selected card index, showing empty state.");
                    showEmptyStateView();
                } else {
                    boolean isUdfpsEnabled = mKeyguardUpdateMonitor.isUdfpsEnrolled()
                            && mKeyguardUpdateMonitor.isFingerprintDetectionRunning();
                    mWalletView.showCardCarousel(
                            paymentCardData,
                            selectedIndex,
                            !mKeyguardStateController.isUnlocked(),
                            isUdfpsEnabled);
                }
            }
            mUiEventLogger.log(WalletUiEvent.QAW_IMPRESSION);
            removeMinHeightAndRecordHeightOnLayout();
        });
    }

    /**
     * Implements {@link QuickAccessWalletClient.OnWalletCardsRetrievedCallback}. Called when there
     * is an error during card retrieval. This will be run on the {@link #mExecutor}.
     */
    @Override
    public void onWalletCardRetrievalError(@NonNull GetWalletCardsError error) {
        mHandler.post(() -> {
            if (mIsDismissed) {
                return;
            }
            mWalletView.showErrorMessage(error.getMessage());
        });
    }

    @Override
    public void onKeyguardFadingAwayChanged() {
        queryWalletCards();
    }

    @Override
    public void onUnlockedChanged() {
        queryWalletCards();
    }

    @Override
    public void onUncenteredClick(int position) {
        if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return;
        }
        mCardCarousel.smoothScrollToPosition(position);
    }

    @Override
    public void onCardSelected(@NonNull WalletCardViewInfo card) {
        if (mIsDismissed) {
            return;
        }
        if (mSelectedCardId != null && !mSelectedCardId.equals(card.getCardId())) {
            mUiEventLogger.log(WalletUiEvent.QAW_CHANGE_CARD);
        }
        mSelectedCardId = card.getCardId();
        selectCard();
    }

    private void selectCard() {
        mHandler.removeCallbacks(mSelectionRunnable);
        String selectedCardId = mSelectedCardId;
        if (mIsDismissed || selectedCardId == null) {
            return;
        }
        mWalletClient.selectWalletCard(new SelectWalletCardRequest(selectedCardId));
        // Re-selecting the card keeps the connection bound so we continue to get service events
        // even if the user keeps it open for a long time.
        mHandler.postDelayed(mSelectionRunnable, SELECTION_DELAY_MILLIS);
    }


    @Override
    public void onCardClicked(@NonNull WalletCardViewInfo cardInfo) {
        if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return;
        }
        if (!(cardInfo instanceof QAWalletCardViewInfo)
                || ((QAWalletCardViewInfo) cardInfo).mWalletCard == null
                || ((QAWalletCardViewInfo) cardInfo).mWalletCard.getPendingIntent() == null) {
            return;
        }

        if (!mKeyguardStateController.isUnlocked()) {
            mUiEventLogger.log(WalletUiEvent.QAW_UNLOCK_FROM_CARD_CLICK);
        }
        mUiEventLogger.log(WalletUiEvent.QAW_CLICK_CARD);

        mActivityStarter.startPendingIntentDismissingKeyguard(cardInfo.getPendingIntent());
    }

    @Override
    public void queryWalletCards() {
        if (mIsDismissed) {
            return;
        }
        int cardWidthPx = mCardCarousel.getCardWidthPx();
        int cardHeightPx = mCardCarousel.getCardHeightPx();
        if (cardWidthPx == 0 || cardHeightPx == 0) {
            return;
        }

        mWalletView.show();
        mWalletView.hideErrorMessage();
        int iconSizePx =
                mContext
                        .getResources()
                        .getDimensionPixelSize(R.dimen.wallet_screen_header_icon_size);
        GetWalletCardsRequest request =
                new GetWalletCardsRequest(cardWidthPx, cardHeightPx, iconSizePx, MAX_CARDS);
        mWalletClient.getWalletCards(mExecutor, request, this);
    }

    void onDismissed() {
        if (mIsDismissed) {
            return;
        }
        mIsDismissed = true;
        mSelectedCardId = null;
        mHandler.removeCallbacks(mSelectionRunnable);
        mWalletClient.notifyWalletDismissed();
        mWalletView.animateDismissal();
        // clear refs to the Wallet Activity
        mContext = null;
    }

    private void showEmptyStateView() {
        Drawable logo = mWalletClient.getLogo();
        CharSequence logoContentDesc = mWalletClient.getServiceLabel();
        CharSequence label = mWalletClient.getShortcutLongLabel();
        Intent intent = mWalletClient.createWalletIntent();
        if (logo == null
                || TextUtils.isEmpty(logoContentDesc)
                || TextUtils.isEmpty(label)
                || intent == null) {
            Log.w(TAG, "QuickAccessWalletService manifest entry mis-configured");
            // Issue is not likely to be resolved until manifest entries are enabled.
            // Hide wallet feature until then.
            mWalletView.hide();
            mPrefs.edit().putInt(PREFS_WALLET_VIEW_HEIGHT, 0).apply();
        } else {
            mWalletView.showEmptyStateView(
                    logo,
                    logoContentDesc,
                    label,
                    v -> mActivityStarter.startActivity(intent, true));
        }
    }

    private int getExpectedMinHeight() {
        int expectedHeight = mPrefs.getInt(PREFS_WALLET_VIEW_HEIGHT, -1);
        if (expectedHeight == -1) {
            Resources res = mContext.getResources();
            expectedHeight = res.getDimensionPixelSize(R.dimen.min_wallet_empty_height);
        }
        return expectedHeight;
    }

    private void removeMinHeightAndRecordHeightOnLayout() {
        mWalletView.setMinimumHeight(0);
        mWalletView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                mWalletView.removeOnLayoutChangeListener(this);
                mPrefs.edit().putInt(PREFS_WALLET_VIEW_HEIGHT, bottom - top).apply();
            }
        });
    }

    @VisibleForTesting
    static class QAWalletCardViewInfo implements WalletCardViewInfo {

        private final WalletCard mWalletCard;
        private final Drawable mCardDrawable;
        private final Drawable mIconDrawable;

        /**
         * Constructor is called on background executor, so it is safe to load drawables
         * synchronously.
         */
        QAWalletCardViewInfo(Context context, WalletCard walletCard) {
            mWalletCard = walletCard;
            Icon cardImageIcon = mWalletCard.getCardImage();
            if (cardImageIcon.getType() == Icon.TYPE_URI) {
                mCardDrawable = null;
            } else {
                mCardDrawable = mWalletCard.getCardImage().loadDrawable(context);
            }
            Icon icon = mWalletCard.getCardIcon();
            mIconDrawable = icon == null ? null : icon.loadDrawable(context);
        }

        @Override
        public String getCardId() {
            return mWalletCard.getCardId();
        }

        @Override
        public Drawable getCardDrawable() {
            return mCardDrawable;
        }

        @Override
        public CharSequence getContentDescription() {
            return mWalletCard.getContentDescription();
        }

        @Override
        public Drawable getIcon() {
            return mIconDrawable;
        }

        @Override
        public CharSequence getLabel() {
            CharSequence label = mWalletCard.getCardLabel();
            if (label == null) {
                return "";
            }
            return label;
        }

        @Override
        public PendingIntent getPendingIntent() {
            return mWalletCard.getPendingIntent();
        }
    }
}
