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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.service.quickaccesswallet.QuickAccessWalletClient;
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

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QuickAccessWalletTileTest extends SysuiTestCase {

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

    private TestableLooper mTestableLooper;
    private QuickAccessWalletTile mTile;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        when(mHost.getContext()).thenReturn(mContext);
        when(mHost.getUiEventLogger()).thenReturn(mUiEventLogger);
        when(mFeatureFlags.isQuickAccessWalletEnabled()).thenReturn(true);

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
        QSTile.Icon icon = QSTileImpl.ResourceIcon.get(R.drawable.ic_qs_wallet);
        QSTile.State state = new QSTile.State();
        when(mQuickAccessWalletClient.getServiceLabel()).thenReturn("QuickAccessWallet");

        mTile.handleUpdateState(state, new Object());

        assertEquals("QuickAccessWallet", state.label.toString());
        assertTrue(state.label.toString().contentEquals(state.contentDescription));
        assertEquals(icon, state.icon);
    }

    @Test
    public void testHandleUpdateState_deviceLocked_tileInactive() {
        QSTile.State state = new QSTile.State();
        when(mKeyguardStateController.isUnlocked()).thenReturn(false);
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(true);

        mTile.handleUpdateState(state, new Object());

        assertEquals(Tile.STATE_INACTIVE, state.state);
        assertNull(state.stateDescription);
    }

    @Test
    public void testHandleUpdateState_deviceLocked_tileActive() {
        QSTile.State state = new QSTile.State();
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(true);

        mTile.handleUpdateState(state, new Object());

        assertEquals(Tile.STATE_ACTIVE, state.state);
        assertTrue(state.secondaryLabel.toString().contentEquals(state.stateDescription));
        assertEquals(
                getContext().getString(R.string.wallet_secondary_label),
                state.secondaryLabel.toString());
    }

    @Test
    public void testHandleUpdateState_qawFeatureUnavailable_tileUnavailable() {
        QSTile.State state = new QSTile.State();
        when(mKeyguardStateController.isUnlocked()).thenReturn(true);
        when(mQuickAccessWalletClient.isWalletFeatureAvailable()).thenReturn(false);

        mTile.handleUpdateState(state, new Object());

        assertEquals(Tile.STATE_UNAVAILABLE, state.state);
    }
}
