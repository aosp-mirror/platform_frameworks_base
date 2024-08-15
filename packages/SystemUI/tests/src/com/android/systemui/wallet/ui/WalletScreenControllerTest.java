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

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletService;
import android.service.quickaccesswallet.WalletCard;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class WalletScreenControllerTest extends SysuiTestCase {

    private static final int CARD_CAROUSEL_WIDTH = 10;
    private static final String CARD_ID_1 = "card_id_1";
    private static final String CARD_ID_2 = "card_id_2";
    private static final CharSequence SHORTCUT_SHORT_LABEL = "View all";
    private static final CharSequence SHORTCUT_LONG_LABEL = "Add a payment method";
    private static final CharSequence SERVICE_LABEL = "Wallet app";
    private final Drawable mWalletLogo = mContext.getDrawable(android.R.drawable.ic_lock_lock);
    private final Intent mWalletIntent = new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET)
            .setComponent(new ComponentName(mContext.getPackageName(), "WalletActivity"));

    private WalletView mWalletView;

    @Mock
    QuickAccessWalletClient mWalletClient;
    @Mock
    ActivityStarter mActivityStarter;
    @Mock
    UserTracker mUserTracker;
    @Mock
    FalsingManager mFalsingManager;
    @Mock
    KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    @Mock
    KeyguardStateController mKeyguardStateController;
    @Mock
    UiEventLogger mUiEventLogger;
    @Captor
    ArgumentCaptor<PendingIntent> mIntentCaptor;
    @Captor
    ArgumentCaptor<QuickAccessWalletClient.OnWalletCardsRetrievedCallback> mCallbackCaptor;
    @Captor
    ArgumentCaptor<List<WalletCardViewInfo>> mPaymentCardDataCaptor;
    private WalletScreenController mController;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mUserTracker.getUserContext()).thenReturn(mContext);
        mWalletView = spy(new WalletView(mContext));
        mWalletView.getCardCarousel().setExpectedViewWidth(CARD_CAROUSEL_WIDTH);
        when(mWalletClient.getLogo()).thenReturn(mWalletLogo);
        when(mWalletClient.getShortcutLongLabel()).thenReturn(SHORTCUT_LONG_LABEL);
        when(mWalletClient.getShortcutShortLabel()).thenReturn(SHORTCUT_SHORT_LABEL);
        when(mWalletClient.getServiceLabel()).thenReturn(SERVICE_LABEL);
        when(mWalletClient.createWalletIntent()).thenReturn(mWalletIntent);
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(false);
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(false);
        mController = new WalletScreenController(
                mContext,
                mWalletView,
                mWalletClient,
                mActivityStarter,
                MoreExecutors.directExecutor(),
                new Handler(mTestableLooper.getLooper()),
                mUserTracker,
                mFalsingManager,
                mKeyguardUpdateMonitor,
                mKeyguardStateController,
                mUiEventLogger);
    }

    @Test
    public void queryCards_deviceLocked_udfpsEnabled_hideUnlockButton() {
        when(mKeyguardUpdateMonitor.isFingerprintDetectionRunning()).thenReturn(true);
        when(mKeyguardUpdateMonitor.isUdfpsEnrolled()).thenReturn(true);
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createWalletCard(mContext)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals(GONE, mWalletView.getActionButton().getVisibility());
    }

    @Test
    public void queryCards_deviceLocked_udfpsNotEnabled_showUnlockButton() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createLockedWalletCard(mContext)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        verify(mUiEventLogger).log(WalletUiEvent.QAW_IMPRESSION);

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals(VISIBLE, mWalletView.getActionButton().getVisibility());
    }

    @Test
    public void queryCards_hasCards_showCarousel_activeCard() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createWalletCard(mContext)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals("Hold to reader", mWalletView.getCardLabel().getText().toString());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());

        verify(mUiEventLogger, times(1)).log(WalletUiEvent.QAW_IMPRESSION);
    }

    @Test
    public void queryCards_hasCards_showCarousel_pendingActivationCard_parseLabel() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createNonActiveWalletCard(mContext)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals("Not set up", mWalletView.getCardLabel().getText().toString());
        assertEquals("Verify now", mWalletView.getActionButton().getText().toString());
        assertEquals(VISIBLE, mWalletView.getActionButton().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
    }

    @Test
    public void queryCards_hasCards_showCarousel_badCard_parseLabel_notCrash() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createCrazyWalletCard(mContext, true)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals("This\nis\ncrazy!!", mWalletView.getCardLabel().getText().toString());
        assertEquals(GONE, mWalletView.getActionButton().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
    }

    @Test
    public void queryCards_hasCards_showCarousel_badCard_noLabel_notCrash() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createCrazyWalletCard(mContext, false)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals("", mWalletView.getCardLabel().getText().toString());
        assertEquals(GONE, mWalletView.getActionButton().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
    }

    @Test
    public void queryCards_hasCards_showCarousel_invalidSelectedIndex_notCrash() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createCrazyWalletCard(mContext, true)), 8);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        QuickAccessWalletClient.OnWalletCardsRetrievedCallback callback =
                mCallbackCaptor.getValue();

        assertEquals(mController, callback);

        callback.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getCardCarousel().getVisibility());
        assertEquals(VISIBLE, mWalletView.getEmptyStateView().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
    }

    @Test
    public void queryCards_noCards_showEmptyState() {
        GetWalletCardsResponse response = new GetWalletCardsResponse(Collections.EMPTY_LIST, 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getCardCarousel().getVisibility());
        assertEquals(VISIBLE, mWalletView.getEmptyStateView().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
        assertTrue(mWalletView.getAppButton().hasOnClickListeners());
    }

    @Test
    public void queryCards_error_showErrorView() {
        String errorMessage = "getWalletCardsError";
        GetWalletCardsError error = new GetWalletCardsError(createIcon(), errorMessage);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onWalletCardRetrievalError(error);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals(GONE, mWalletView.getEmptyStateView().getVisibility());
        assertEquals(VISIBLE, mWalletView.getErrorView().getVisibility());
        assertEquals(errorMessage, mWalletView.getErrorView().getText().toString());
    }

    @Test
    public void onKeyguardFadingAwayChanged_queryCards() {
        mController.onKeyguardFadingAwayChanged();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());
        assertEquals(mController, mCallbackCaptor.getValue());
    }

    @Test
    public void onCardSelected() {
        mController.onCardSelected(createCardViewInfo(createWalletCard(mContext)));

        assertEquals(CARD_ID_1, mController.mSelectedCardId);
    }

    @Test
    public void logOnCardChanged() {
        mController.onCardSelected(createCardViewInfo(createWalletCard(mContext)));
        mController.onCardSelected(createCardViewInfo(createNonActiveWalletCard(mContext)));

        verify(mUiEventLogger, times(1)).log(WalletUiEvent.QAW_CHANGE_CARD);
    }

    @Test
    public void onCardClicked_startIntent() {
        WalletCardViewInfo walletCardViewInfo = createCardViewInfo(createWalletCard(mContext));

        mController.onCardClicked(walletCardViewInfo);

        verify(mActivityStarter).startPendingIntentDismissingKeyguard(mIntentCaptor.capture());

        Intent actualIntent = mIntentCaptor.getValue().getIntent();

        assertEquals(mWalletIntent.getAction(), actualIntent.getAction());
        assertEquals(mWalletIntent.getComponent(), actualIntent.getComponent());

        verify(mUiEventLogger, times(1)).log(WalletUiEvent.QAW_CLICK_CARD);
    }

    @Test
    public void onCardClicked_deviceLocked_logUnlockEvent() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        WalletCardViewInfo walletCardViewInfo = createCardViewInfo(createWalletCard(mContext));

        mController.onCardClicked(walletCardViewInfo);

        verify(mUiEventLogger, times(1))
                .log(WalletUiEvent.QAW_UNLOCK_FROM_CARD_CLICK);
        verify(mUiEventLogger, times(1)).log(WalletUiEvent.QAW_CLICK_CARD);
    }

    @Test
    public void onWalletCardsRetrieved_cardDataEmpty_intentIsNull_hidesWallet() {
        when(mWalletClient.createWalletIntent()).thenReturn(null);
        GetWalletCardsResponse response = new GetWalletCardsResponse(Collections.emptyList(), 0);

        mController.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getVisibility());
    }

    @Test
    public void onWalletCardsRetrieved_cardDataEmpty_logoIsNull_hidesWallet() {
        when(mWalletClient.getLogo()).thenReturn(null);
        GetWalletCardsResponse response = new GetWalletCardsResponse(Collections.emptyList(), 0);

        mController.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getVisibility());
    }

    @Test
    public void onWalletCardsRetrieved_cardDataEmpty_labelIsEmpty_hidesWallet() {
        when(mWalletClient.getShortcutLongLabel()).thenReturn("");
        GetWalletCardsResponse response = new GetWalletCardsResponse(Collections.emptyList(), 0);

        mController.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(GONE, mWalletView.getVisibility());
    }

    @Test
    public void onWalletCardsRetrieved_cardDataAllUnknown_showsAllCards() {
        List<WalletCard> walletCardList = List.of(
                createWalletCardWithType(mContext, WalletCard.CARD_TYPE_UNKNOWN),
                createWalletCardWithType(mContext, WalletCard.CARD_TYPE_UNKNOWN),
                createWalletCardWithType(mContext, WalletCard.CARD_TYPE_UNKNOWN));
        GetWalletCardsResponse response = new GetWalletCardsResponse(walletCardList, 0);
        mController.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        verify(mWalletView).showCardCarousel(mPaymentCardDataCaptor.capture(), anyInt(),
                anyBoolean(),
                anyBoolean());
        List<WalletCardViewInfo> paymentCardData = mPaymentCardDataCaptor.getValue();
        assertEquals(paymentCardData.size(), walletCardList.size());
    }

    @Test
    public void onWalletCardsRetrieved_cardDataDifferentTypes_onlyShowsPayment() {
        List<WalletCard> walletCardList = List.of(createWalletCardWithType(mContext,
                        WalletCard.CARD_TYPE_UNKNOWN),
                createWalletCardWithType(mContext, WalletCard.CARD_TYPE_PAYMENT),
                createWalletCardWithType(mContext, WalletCard.CARD_TYPE_NON_PAYMENT)
        );
        GetWalletCardsResponse response = new GetWalletCardsResponse(walletCardList, 0);
        mController.onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        verify(mWalletView).showCardCarousel(mPaymentCardDataCaptor.capture(), anyInt(),
                anyBoolean(),
                anyBoolean());
        List<WalletCardViewInfo> paymentCardData = mPaymentCardDataCaptor.getValue();
        assertEquals(paymentCardData.size(), 1);
    }

    private WalletCard createNonActiveWalletCard(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID_2, createIcon(), "•••• 5678", pendingIntent)
                .setCardIcon(createIcon())
                .setCardLabel("Not set up\nVerify now")
                .build();
    }

    private WalletCard createLockedWalletCard(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID_2, createIcon(), "•••• 5679", pendingIntent)
                .setCardIcon(createIcon())
                .setCardLabel("Locked\nUnlock to pay")
                .build();
    }

    private WalletCard createWalletCard(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID_1, createIcon(), "•••• 1234", pendingIntent)
                .setCardIcon(createIcon())
                .setCardLabel("Hold to reader")
                .build();
    }

    private WalletCard createWalletCardWithType(Context context, int cardType) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID_1, cardType, createIcon(), "•••• 1234", pendingIntent)
                .setCardIcon(createIcon())
                .setCardLabel("Hold to reader")
                .build();
    }

    private WalletCard createCrazyWalletCard(Context context, boolean hasLabel) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder("BadCard", createIcon(), "•••• 1234", pendingIntent)
                .setCardIcon(null)
                .setCardLabel(hasLabel ? "This\nis\ncrazy!!" : null)
                .build();
    }

    private static Icon createIcon() {
        return Icon.createWithBitmap(Bitmap.createBitmap(70, 44, Bitmap.Config.ARGB_8888));
    }

    private WalletCardViewInfo createCardViewInfo(WalletCard walletCard) {
        return new WalletScreenController.QAWalletCardViewInfo(
                mContext, walletCard);
    }
}
