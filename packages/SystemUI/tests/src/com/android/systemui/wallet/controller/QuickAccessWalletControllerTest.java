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

import static android.service.quickaccesswallet.Flags.FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.PendingIntent;
import android.app.role.RoleManager;
import android.content.Intent;
import android.os.UserHandle;
import android.platform.test.annotations.EnableFlags;
import android.service.quickaccesswallet.GetWalletCardsRequest;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;
import com.android.systemui.util.settings.SecureSettings;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QuickAccessWalletControllerTest extends SysuiTestCase {

    private static final String WALLET_ROLE_HOLDER = "wallet.role.holder";
    @Mock
    private QuickAccessWalletClient mQuickAccessWalletClient;
    @Mock
    private SecureSettings mSecureSettings;
    @Mock
    private QuickAccessWalletClient.OnWalletCardsRetrievedCallback mCardsRetriever;
    @Mock
    private ActivityStarter mActivityStarter;
    @Mock
    private ActivityTransitionAnimator.Controller mAnimationController;
    @Mock
    private RoleManager mRoleManager;
    @Captor
    private ArgumentCaptor<GetWalletCardsRequest> mRequestCaptor;
    @Captor
    private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor
    private ArgumentCaptor<PendingIntent> mPendingIntentCaptor;

    private FakeSystemClock mClock = new FakeSystemClock();
    private QuickAccessWalletController mController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mQuickAccessWalletClient.isWalletServiceAvailable()).thenReturn(true);
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(true);
        when(mQuickAccessWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(true);
        when(mQuickAccessWalletClient.getUser()).thenReturn(UserHandle.of(0));
        mClock.setElapsedRealtime(100L);

        doAnswer(invocation -> {
            QuickAccessWalletClient.WalletPendingIntentCallback callback =
                    (QuickAccessWalletClient.WalletPendingIntentCallback) invocation
                            .getArguments()[1];
            callback.onWalletPendingIntentRetrieved(null);
            return null;
        }).when(mQuickAccessWalletClient).getWalletPendingIntent(any(), any());
        doAnswer(invocation -> {
            QuickAccessWalletClient.GesturePendingIntentCallback callback =
                    (QuickAccessWalletClient.GesturePendingIntentCallback) invocation
                            .getArguments()[1];
            callback.onGesturePendingIntentRetrieved(null);
            return null;
        }).when(mQuickAccessWalletClient).getGestureTargetActivityPendingIntent(any(), any());

        mController = spy(new QuickAccessWalletController(
                mContext,
                MoreExecutors.directExecutor(),
                MoreExecutors.directExecutor(),
                mSecureSettings,
                mQuickAccessWalletClient,
                mClock,
                mRoleManager));
    }

    @Test
    public void walletEnabled() {
        mController.updateWalletPreference();

        assertTrue(mController.isWalletEnabled());
    }

    @Test
    public void walletRoleAvailable_isAvailable() {
        when(mRoleManager.isRoleAvailable(eq(RoleManager.ROLE_WALLET))).thenReturn(true);
        when(mRoleManager.getRoleHolders(eq(RoleManager.ROLE_WALLET)))
                .thenReturn(List.of(WALLET_ROLE_HOLDER));

        assertTrue(mController.isWalletRoleAvailable());
    }

    @Test
    public void walletRoleAvailable_isNotAvailable() {
        when(mRoleManager.isRoleAvailable(eq(RoleManager.ROLE_WALLET))).thenReturn(false);
        when(mRoleManager.getRoleHolders(eq(RoleManager.ROLE_WALLET)))
                .thenReturn(List.of(WALLET_ROLE_HOLDER));

        assertFalse(mController.isWalletRoleAvailable());
    }

    @Test
    public void walletServiceUnavailable_walletNotEnabled() {
        when(mQuickAccessWalletClient.isWalletServiceAvailable()).thenReturn(false);

        mController.updateWalletPreference();

        assertFalse(mController.isWalletEnabled());
    }

    @Test
    public void walletFeatureUnavailable_walletNotEnabled() {
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(false);

        mController.updateWalletPreference();

        assertFalse(mController.isWalletEnabled());
    }

    @Test
    public void walletFeatureWhenLockedUnavailable_walletNotEnabled() {
        when(mQuickAccessWalletClient.isWalletFeatureAvailableWhenDeviceLocked()).thenReturn(false);

        mController.updateWalletPreference();

        assertFalse(mController.isWalletEnabled());
    }

    @Test
    public void getWalletClient_NoRecreation_sameClient() {
        assertSame(mQuickAccessWalletClient, mController.getWalletClient());
    }

    @Test
    public void getWalletClient_reCreateClient_notSameClient() {
        mController.reCreateWalletClient();

        assertNotSame(mQuickAccessWalletClient, mController.getWalletClient());
    }

    @Test
    public void queryWalletCards_avoidStale_recreateClient() {
        // advance current time by 100 seconds, should not recreate the client.
        mClock.setElapsedRealtime(100100L);

        mController.queryWalletCards(mCardsRetriever);

        assertSame(mQuickAccessWalletClient, mController.getWalletClient());

        // advance current time by another 501 seconds, should recreate the client.
        mClock.setElapsedRealtime(601100L);

        mController.queryWalletCards(mCardsRetriever);

        assertNotSame(mQuickAccessWalletClient, mController.getWalletClient());
    }

    @Test
    public void queryWalletCards_walletEnabled_queryCards() {
        mController.queryWalletCards(mCardsRetriever);

        verify(mQuickAccessWalletClient)
                .getWalletCards(
                        eq(MoreExecutors.directExecutor()), mRequestCaptor.capture(),
                        eq(mCardsRetriever));

        GetWalletCardsRequest request = mRequestCaptor.getValue();
        assertEquals(1, mRequestCaptor.getValue().getMaxCards());
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width),
                request.getCardWidthPx());
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height),
                request.getCardHeightPx());
    }

    @Test
    public void queryWalletCards_walletEnabled_queryMultipleCards() {
        mController.queryWalletCards(mCardsRetriever, 5);

        verify(mQuickAccessWalletClient)
                .getWalletCards(
                        eq(MoreExecutors.directExecutor()), mRequestCaptor.capture(),
                        eq(mCardsRetriever));

        GetWalletCardsRequest request = mRequestCaptor.getValue();
        assertEquals(5, mRequestCaptor.getValue().getMaxCards());
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_width),
                request.getCardWidthPx());
        assertEquals(
                mContext.getResources().getDimensionPixelSize(R.dimen.wallet_tile_card_view_height),
                request.getCardHeightPx());
    }

    @Test
    public void queryWalletCards_walletFeatureNotAvailable_noQuery() {
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(false);

        mController.queryWalletCards(mCardsRetriever);

        verify(mQuickAccessWalletClient, never()).getWalletCards(any(), any(), any());
    }

    @Test
    public void getQuickAccessUiIntent_hasCards_noPendingIntent_startsWalletActivity() {
        mController.startQuickAccessUiIntent(mActivityStarter, mAnimationController, true);
        verify(mActivityStarter).startActivity(mIntentCaptor.capture(), eq(true),
                any(ActivityTransitionAnimator.Controller.class), eq(true));
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getAction(), Intent.ACTION_VIEW);
        assertEquals(
                intent.getComponent().getClassName(),
                "com.android.systemui.wallet.ui.WalletActivity");
    }

    @Test
    public void getQuickAccessUiIntent_noCards_noPendingIntent_startsWalletActivity() {
        mController.startQuickAccessUiIntent(mActivityStarter, mAnimationController, false);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(mIntentCaptor.capture(), eq(0),
                any(ActivityTransitionAnimator.Controller.class), eq(null),
                eq(UserHandle.of(0)));
        Intent intent = mIntentCaptor.getValue();
        assertEquals(intent.getAction(), Intent.ACTION_VIEW);
        assertEquals(
                intent.getComponent().getClassName(),
                "com.android.systemui.wallet.ui.WalletActivity");
    }

    @Test
    public void getQuickAccessUiIntent_targetActivityViaPendingIntent_intentComponentIsCorrect() {
        doAnswer(invocation -> {
            QuickAccessWalletClient.WalletPendingIntentCallback callback =
                    (QuickAccessWalletClient.WalletPendingIntentCallback) invocation
                            .getArguments()[1];
            Intent intent = new Intent(Intent.ACTION_VIEW).setClassName(
                    "com.google.android.apps.testapp",
                    "com.google.android.apps.testapp.TestActivity");
            callback.onWalletPendingIntentRetrieved(
                    PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_IMMUTABLE));
            return null;
        }).when(mQuickAccessWalletClient).getWalletPendingIntent(any(), any());
        mController.startQuickAccessUiIntent(mActivityStarter, mAnimationController, true);
        verify(mActivityStarter).postStartActivityDismissingKeyguard(mPendingIntentCaptor.capture(),
                any(ActivityTransitionAnimator.Controller.class));
        PendingIntent pendingIntent = mPendingIntentCaptor.getValue();
        Intent intent = pendingIntent.getIntent();
        assertEquals(intent.getAction(), Intent.ACTION_VIEW);
        assertEquals(
                intent.getComponent().getClassName(),
                "com.google.android.apps.testapp.TestActivity");
    }

    @Test
    @EnableFlags(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void getGestureUiIntent_targetActivityViaPendingIntent_intentComponentIsCorrect() {
        doAnswer(invocation -> {
            QuickAccessWalletClient.GesturePendingIntentCallback callback =
                    (QuickAccessWalletClient.GesturePendingIntentCallback) invocation
                            .getArguments()[1];
            Intent intent = new Intent(Intent.ACTION_VIEW).setClassName(
                    "com.google.android.apps.testapp",
                    "com.google.android.apps.testapp.GestureTestActivity");
            callback.onGesturePendingIntentRetrieved(
                    PendingIntent.getActivity(mContext, 0, intent,
                            PendingIntent.FLAG_IMMUTABLE));
            return null;
        }).when(mQuickAccessWalletClient).getGestureTargetActivityPendingIntent(any(), any());
        mController.startGestureUiIntent(mActivityStarter, mAnimationController);
        verify(mActivityStarter).startPendingIntentMaybeDismissingKeyguard(
                mPendingIntentCaptor.capture(),
                nullable(Runnable.class),
                nullable(ActivityTransitionAnimator.Controller.class));
        PendingIntent pendingIntent = mPendingIntentCaptor.getValue();
        Intent intent = pendingIntent.getIntent();
        assertEquals(intent.getAction(), Intent.ACTION_VIEW);
        assertEquals(
                intent.getComponent().getClassName(),
                "com.google.android.apps.testapp.GestureTestActivity");
    }

    @Test
    @EnableFlags(FLAG_LAUNCH_WALLET_OPTION_ON_POWER_DOUBLE_TAP)
    public void getGestureUiIntent_noPendingIntent_startsQuickAccessUiIntent() {
        mController.startGestureUiIntent(mActivityStarter, mAnimationController);

        verify(mController).startQuickAccessUiIntent(eq(mActivityStarter), eq(mAnimationController),
                anyBoolean());
    }
}
