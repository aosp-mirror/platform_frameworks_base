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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletService;
import android.service.quickaccesswallet.WalletCard;
import android.service.quickaccesswallet.WalletServiceEvent;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class WalletScreenControllerTest extends SysuiTestCase {

    private static final int MAX_CARDS = 10;
    private static final String CARD_ID = "card_id";
    private static final CharSequence SHORTCUT_SHORT_LABEL = "View all";
    private static final CharSequence SHORTCUT_LONG_LABEL = "Add a payment method";
    private static final CharSequence SERVICE_LABEL = "Wallet app";
    private final WalletView mWalletView = new WalletView(mContext);
    private final Drawable mWalletLogo = mContext.getDrawable(android.R.drawable.ic_lock_lock);
    private final Intent mWalletIntent = new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET)
            .setComponent(new ComponentName(mContext.getPackageName(), "WalletActivity"));

    @Mock
    QuickAccessWalletClient mWalletClient;
    @Mock
    ActivityStarter mActivityStarter;
    @Mock
    UserTracker mUserTracker;
    @Captor
    ArgumentCaptor<Intent> mIntentCaptor;
    @Captor
    ArgumentCaptor<GetWalletCardsRequest> mRequestCaptor;
    @Captor
    ArgumentCaptor<QuickAccessWalletClient.OnWalletCardsRetrievedCallback> mCallbackCaptor;
    @Captor
    ArgumentCaptor<QuickAccessWalletClient.WalletServiceEventListener> mListenerCaptor;
    private WalletScreenController mController;
    private TestableLooper mTestableLooper;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);
        when(mUserTracker.getUserContext()).thenReturn(mContext);
        when(mWalletClient.getLogo()).thenReturn(mWalletLogo);
        when(mWalletClient.getShortcutLongLabel()).thenReturn(SHORTCUT_LONG_LABEL);
        when(mWalletClient.getShortcutShortLabel()).thenReturn(SHORTCUT_SHORT_LABEL);
        when(mWalletClient.getServiceLabel()).thenReturn(SERVICE_LABEL);
        when(mWalletClient.createWalletIntent()).thenReturn(mWalletIntent);
        mController = new WalletScreenController(
                mContext,
                mWalletView,
                mWalletClient,
                mActivityStarter,
                MoreExecutors.directExecutor(),
                new Handler(mTestableLooper.getLooper()),
                mUserTracker,
                /* isDeviceLocked= */false);
    }

    @Test
    public void queryCards_hasCards_showCarousel() {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        Collections.singletonList(createWalletCard(mContext)), 0);

        mController.queryWalletCards();
        mTestableLooper.processAllMessages();

        verify(mWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();

        assertEquals(VISIBLE, mWalletView.getCardCarouselContainer().getVisibility());
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

        assertEquals(GONE, mWalletView.getCardCarouselContainer().getVisibility());
        assertEquals(VISIBLE, mWalletView.getEmptyStateView().getVisibility());
        assertEquals(GONE, mWalletView.getErrorView().getVisibility());
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
    public void onWalletServiceEvent_nfcPaymentStart_dismiss() {
        WalletServiceEvent event =
                new WalletServiceEvent(WalletServiceEvent.TYPE_NFC_PAYMENT_STARTED);

        mController.onWalletServiceEvent(event);
        mTestableLooper.processAllMessages();

        assertNull(mController.mSelectedCardId);
        assertTrue(mController.mIsDismissed);
        verify(mWalletClient).notifyWalletDismissed();
    }

    @Test
    public void onWalletServiceEvent_walletCardsUpdate_queryCards() {
        mController.queryWalletCards();

        verify(mWalletClient).addWalletServiceEventListener(mListenerCaptor.capture());

        WalletServiceEvent event =
                new WalletServiceEvent(WalletServiceEvent.TYPE_WALLET_CARDS_UPDATED);

        QuickAccessWalletClient.WalletServiceEventListener listener = mListenerCaptor.getValue();
        listener.onWalletServiceEvent(event);
        mTestableLooper.processAllMessages();

        verify(mWalletClient, times(2))
                .getWalletCards(any(), mRequestCaptor.capture(), mCallbackCaptor.capture());

        GetWalletCardsRequest request = mRequestCaptor.getValue();

        assertEquals(MAX_CARDS, request.getMaxCards());
    }

    @Test
    public void onCardSelected() {
        mController.onCardSelected(createCardViewInfo());

        assertEquals(CARD_ID, mController.mSelectedCardId);
    }

    @Test
    public void onCardClicked_startIntent() {
        WalletCardViewInfo walletCardViewInfo = createCardViewInfo();

        mController.onCardClicked(walletCardViewInfo);

        verify(mActivityStarter).startActivity(mIntentCaptor.capture(), eq(true));

        assertEquals(mWalletIntent.getAction(), mIntentCaptor.getValue().getAction());
        assertEquals(mWalletIntent.getComponent(), mIntentCaptor.getValue().getComponent());
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

    private WalletCard createWalletCard(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID, createIcon(), "description", pendingIntent).build();
    }

    private static Icon createIcon() {
        return Icon.createWithBitmap(Bitmap.createBitmap(70, 44, Bitmap.Config.ARGB_8888));
    }

    private WalletCardViewInfo createCardViewInfo() {
        return new WalletScreenController.QAWalletCardViewInfo(
                mContext, createWalletCard(mContext));
    }
}
