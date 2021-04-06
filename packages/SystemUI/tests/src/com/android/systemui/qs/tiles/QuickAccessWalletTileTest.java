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

import static android.content.pm.PackageManager.FEATURE_NFC_HOST_CARD_EMULATION;
import static android.provider.Settings.Secure.NFC_PAYMENT_DEFAULT_COMPONENT;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.service.quickaccesswallet.QuickAccessWalletService;
import android.service.quickaccesswallet.WalletCard;
import android.service.quicksettings.Tile;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSTileHost;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.settings.SecureSettings;

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
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QuickAccessWalletTileTest extends SysuiTestCase {

    private static final String CARD_ID = "card_id";
    private static final Icon CARD_IMAGE =
            Icon.createWithBitmap(Bitmap.createBitmap(70, 50, Bitmap.Config.ARGB_8888));

    private final Intent mWalletIntent = new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET)
            .setComponent(new ComponentName(mContext.getPackageName(), "WalletActivity"));

    @Mock
    private QSTileHost mHost;
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private QSLogger mQSLogger;
    private UiEventLogger mUiEventLogger = new UiEventLoggerFake();
    @Mock
    private QuickAccessWalletClient mQuickAccessWalletClient;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private FeatureFlags mFeatureFlags;
    @Captor
    ArgumentCaptor<GetWalletCardsRequest> mRequestCaptor;
    @Captor
    ArgumentCaptor<QuickAccessWalletClient.OnWalletCardsRetrievedCallback> mCallbackCaptor;

    private TestableLooper mTestableLooper;
    private QuickAccessWalletTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUiEventLogger()).thenReturn(mUiEventLogger);
        when(mFeatureFlags.isQuickAccessWalletEnabled()).thenReturn(true);
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(true);

        mTile = new QuickAccessWalletTile(
                mHost,
                mTestableLooper.getLooper(),
                new Handler(mTestableLooper.getLooper()),
                new FalsingManagerFake(),
                mMetricsLogger,
                mStatusBarStateController,
                mActivityStarter,
                mQSLogger,
                mQuickAccessWalletClient,
                mKeyguardStateController,
                mPackageManager,
                mSecureSettings,
                MoreExecutors.directExecutor(),
                mFeatureFlags);
    }

    @Test
    public void testNewTile() {
        assertFalse(mTile.newTileState().handlesLongClick);
    }

    @Test
    public void testIsAvailable_featureFlagIsOff() {
        when(mFeatureFlags.isQuickAccessWalletEnabled()).thenReturn(false);
        assertFalse(mTile.isAvailable());
    }

    @Test
    public void testIsAvailable_qawServiceNotAvailable() {
        when(mQuickAccessWalletClient.isWalletServiceAvailable()).thenReturn(false);
        assertFalse(mTile.isAvailable());
    }

    @Test
    public void testIsAvailable_qawServiceAvailable() {
        when(mPackageManager.hasSystemFeature(FEATURE_NFC_HOST_CARD_EMULATION)).thenReturn(true);
        when(mPackageManager.hasSystemFeature("org.chromium.arc")).thenReturn(false);
        when(mSecureSettings.getString(NFC_PAYMENT_DEFAULT_COMPONENT)).thenReturn("Component");

        assertTrue(mTile.isAvailable());
    }

    @Test
    public void testHandleClick_openGPay() {
        Intent intent = new Intent("WalletIntent");
        when(mQuickAccessWalletClient.createWalletIntent()).thenReturn(intent);
        mTile.handleClick();

        verify(mActivityStarter, times(1))
                .postStartActivityDismissingKeyguard(eq(intent), anyInt());
    }

    @Test
    public void testHandleUpdateState_updateLabelAndIcon() {
        QSTile.State state = new QSTile.State();
        QSTile.Icon icon = QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_wallet);
        when(mQuickAccessWalletClient.getServiceLabel()).thenReturn("QuickAccessWallet");

        mTile.handleUpdateState(state, null);

        assertEquals("QuickAccessWallet", state.label.toString());
        assertTrue(state.label.toString().contentEquals(state.contentDescription));
        assertEquals(icon, state.icon);
    }

    @Test
    public void testHandleUpdateState_hasCard_deviceLocked_tileInactive() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        QSTile.State state = new QSTile.State();
        setUpWalletCard(/* hasCard= */ true);

        mTile.handleUpdateState(state, null);

        assertEquals(Tile.STATE_INACTIVE, state.state);
        assertEquals(
                mContext.getString(R.string.wallet_secondary_label_device_locked),
                state.secondaryLabel);
        assertNotNull(state.stateDescription);
        assertNull(state.sideViewDrawable);
    }

    @Test
    public void testHandleUpdateState_hasCard_deviceUnlocked_tileActive() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        QSTile.State state = new QSTile.State();
        setUpWalletCard(/* hasCard= */ true);

        mTile.handleUpdateState(state, null);

        assertEquals(Tile.STATE_ACTIVE, state.state);
        assertEquals(
                mContext.getString(R.string.wallet_secondary_label_active),
                state.secondaryLabel);
        assertNotNull(state.stateDescription);
        assertNotNull(state.sideViewDrawable);
    }


    @Test
    public void testHandleUpdateState_noCard_tileInactive() {
        QSTile.State state = new QSTile.State();
        setUpWalletCard(/* hasCard= */ false);

        mTile.handleUpdateState(state, null);

        assertEquals(Tile.STATE_INACTIVE, state.state);
        assertEquals(
                mContext.getString(R.string.wallet_secondary_label_no_card),
                state.secondaryLabel);
        assertNotNull(state.stateDescription);
        assertNull(state.sideViewDrawable);
    }

    @Test
    public void testHandleUpdateState_qawFeatureUnavailable_tileUnavailable() {
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(false);
        QSTile.State state = new QSTile.State();

        mTile.handleUpdateState(state, null);

        assertEquals(Tile.STATE_UNAVAILABLE, state.state);
        assertNull(state.stateDescription);
        assertNull(state.sideViewDrawable);
    }

    @Test
    public void testHandleSetListening_queryCards() {
        mTile.handleSetListening(true);

        verify(mQuickAccessWalletClient)
                .getWalletCards(any(), mRequestCaptor.capture(), mCallbackCaptor.capture());

        GetWalletCardsRequest request = mRequestCaptor.getValue();
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width),
                request.getCardWidthPx());
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height),
                request.getCardHeightPx());
        assertEquals(2, request.getMaxCards());
        assertThat(mCallbackCaptor.getValue()).isInstanceOf(
                QuickAccessWalletClient.OnWalletCardsRetrievedCallback.class);
    }

    @Test
    public void testQueryCards_hasCards_updateSideViewDrawable() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        setUpWalletCard(/* hasCard= */ true);

        assertNotNull(mTile.getState().sideViewDrawable);
    }

    @Test
    public void testState_queryCards_hasCards_then_noCards() {
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        GetWalletCardsResponse responseWithCards =
                new GetWalletCardsResponse(
                        Collections.singletonList(createWalletCard(mContext)), 0);
        GetWalletCardsResponse responseWithoutCards =
                new GetWalletCardsResponse(Collections.EMPTY_LIST, 0);

        mTile.handleSetListening(true);

        verify(mQuickAccessWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        // query wallet cards, has cards
        mCallbackCaptor.getValue().onWalletCardsRetrieved(responseWithCards);
        mTestableLooper.processAllMessages();

        assertNotNull(mTile.getState().sideViewDrawable);

        mTile.handleSetListening(true);

        verify(mQuickAccessWalletClient, times(2))
                .getWalletCards(any(), any(), mCallbackCaptor.capture());

        // query wallet cards, has no cards
        mCallbackCaptor.getValue().onWalletCardsRetrieved(responseWithoutCards);
        mTestableLooper.processAllMessages();

        assertNull(mTile.getState().sideViewDrawable);
    }

    @Test
    public void testQueryCards_noCards_notUpdateSideViewDrawable() {
        setUpWalletCard(/* hasCard= */ false);

        assertNull(mTile.getState().sideViewDrawable);
    }

    @Test
    public void testQueryCards_error_notUpdateSideViewDrawable() {
        String errorMessage = "getWalletCardsError";
        GetWalletCardsError error = new GetWalletCardsError(CARD_IMAGE, errorMessage);

        mTile.handleSetListening(true);

        verify(mQuickAccessWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onWalletCardRetrievalError(error);
        mTestableLooper.processAllMessages();

        assertNull(mTile.getState().sideViewDrawable);
    }

    @Test
    public void testHandleSetListening_notListening_notQueryCards() {
        mTile.handleSetListening(false);

        verifyZeroInteractions(mQuickAccessWalletClient);
    }

    private void setUpWalletCard(boolean hasCard) {
        GetWalletCardsResponse response =
                new GetWalletCardsResponse(
                        hasCard
                                ? Collections.singletonList(createWalletCard(mContext))
                                : Collections.EMPTY_LIST, 0);

        mTile.handleSetListening(true);

        verify(mQuickAccessWalletClient).getWalletCards(any(), any(), mCallbackCaptor.capture());

        mCallbackCaptor.getValue().onWalletCardsRetrieved(response);
        mTestableLooper.processAllMessages();
    }

    private WalletCard createWalletCard(Context context) {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, mWalletIntent, PendingIntent.FLAG_IMMUTABLE);
        return new WalletCard.Builder(CARD_ID, CARD_IMAGE, "description", pendingIntent).build();
    }
}
