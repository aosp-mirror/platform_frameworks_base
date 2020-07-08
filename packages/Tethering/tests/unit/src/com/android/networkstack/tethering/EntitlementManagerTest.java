/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.networkstack.tethering;

import static android.net.TetheringConstants.EXTRA_ADD_TETHER_TYPE;
import static android.net.TetheringConstants.EXTRA_PROVISION_CALLBACK;
import static android.net.TetheringConstants.EXTRA_RUN_PROVISION;
import static android.net.TetheringConstants.EXTRA_TETHER_PROVISIONING_RESPONSE;
import static android.net.TetheringConstants.EXTRA_TETHER_SILENT_PROVISIONING_ACTION;
import static android.net.TetheringConstants.EXTRA_TETHER_SUBID;
import static android.net.TetheringConstants.EXTRA_TETHER_UI_PROVISIONING_APP_NAME;
import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_ETHERNET;
import static android.net.TetheringManager.TETHERING_INVALID;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
import static android.net.TetheringManager.TETHERING_WIFI_P2P;
import static android.net.TetheringManager.TETHER_ERROR_ENTITLEMENT_UNKNOWN;
import static android.net.TetheringManager.TETHER_ERROR_NO_ERROR;
import static android.net.TetheringManager.TETHER_ERROR_PROVISIONING_FAILED;
import static android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.test.BroadcastInterceptingContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class EntitlementManagerTest {

    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final String PROVISIONING_NO_UI_APP_NAME = "no_ui_app";
    private static final String PROVISIONING_APP_RESPONSE = "app_response";

    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private Context mContext;
    @Mock private Resources mResources;
    @Mock private SharedLog mLog;
    @Mock private EntitlementManager.OnUiEntitlementFailedListener mEntitlementFailedListener;

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();
    private final TestLooper mLooper = new TestLooper();
    private Context mMockContext;
    private Runnable mPermissionChangeCallback;

    private WrappedEntitlementManager mEnMgr;
    private TetheringConfiguration mConfig;
    private MockitoSession mMockingSession;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Resources getResources() {
            return mResources;
        }
    }

    public class WrappedEntitlementManager extends EntitlementManager {
        public int fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKNOWN;
        public int uiProvisionCount = 0;
        public int silentProvisionCount = 0;

        public WrappedEntitlementManager(Context ctx, Handler h, SharedLog log,
                Runnable callback) {
            super(ctx, h, log, callback);
        }

        public void reset() {
            fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKNOWN;
            uiProvisionCount = 0;
            silentProvisionCount = 0;
        }

        @Override
        protected Intent runUiTetherProvisioning(int type,
                final TetheringConfiguration config, final ResultReceiver receiver) {
            Intent intent = super.runUiTetherProvisioning(type, config, receiver);
            assertUiTetherProvisioningIntent(type, config, receiver, intent);
            uiProvisionCount++;
            receiver.send(fakeEntitlementResult, null);
            return intent;
        }

        private void assertUiTetherProvisioningIntent(int type, final TetheringConfiguration config,
                final ResultReceiver receiver, final Intent intent) {
            assertEquals(Settings.ACTION_TETHER_PROVISIONING_UI, intent.getAction());
            assertEquals(type, intent.getIntExtra(EXTRA_ADD_TETHER_TYPE, TETHERING_INVALID));
            final String[] appName = intent.getStringArrayExtra(
                    EXTRA_TETHER_UI_PROVISIONING_APP_NAME);
            assertEquals(PROVISIONING_APP_NAME.length, appName.length);
            for (int i = 0; i < PROVISIONING_APP_NAME.length; i++) {
                assertEquals(PROVISIONING_APP_NAME[i], appName[i]);
            }
            assertEquals(receiver, intent.getParcelableExtra(EXTRA_PROVISION_CALLBACK));
            assertEquals(config.activeDataSubId,
                    intent.getIntExtra(EXTRA_TETHER_SUBID, INVALID_SUBSCRIPTION_ID));
        }

        @Override
        protected Intent runSilentTetherProvisioning(int type,
                final TetheringConfiguration config) {
            Intent intent = super.runSilentTetherProvisioning(type, config);
            assertSilentTetherProvisioning(type, config, intent);
            silentProvisionCount++;
            addDownstreamMapping(type, fakeEntitlementResult);
            return intent;
        }

        private void assertSilentTetherProvisioning(int type, final TetheringConfiguration config,
                final Intent intent) {
            assertEquals(type, intent.getIntExtra(EXTRA_ADD_TETHER_TYPE, TETHERING_INVALID));
            assertEquals(true, intent.getBooleanExtra(EXTRA_RUN_PROVISION, false));
            assertEquals(PROVISIONING_NO_UI_APP_NAME,
                    intent.getStringExtra(EXTRA_TETHER_SILENT_PROVISIONING_ACTION));
            assertEquals(PROVISIONING_APP_RESPONSE,
                    intent.getStringExtra(EXTRA_TETHER_PROVISIONING_RESPONSE));
            assertTrue(intent.hasExtra(EXTRA_PROVISION_CALLBACK));
            assertEquals(config.activeDataSubId,
                    intent.getIntExtra(EXTRA_TETHER_SUBID, INVALID_SUBSCRIPTION_ID));
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(SystemProperties.class)
                .mockStatic(DeviceConfig.class)
                .strictness(Strictness.WARN)
                .startMocking();
        // Don't disable tethering provisioning unless requested.
        doReturn(false).when(
                () -> SystemProperties.getBoolean(
                eq(EntitlementManager.DISABLE_PROVISIONING_SYSPROP_KEY), anyBoolean()));
        doReturn(null).when(
                () -> DeviceConfig.getProperty(eq(NAMESPACE_CONNECTIVITY), anyString()));

        when(mResources.getStringArray(R.array.config_tether_dhcp_range))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_usb_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_wifi_regexs))
                .thenReturn(new String[0]);
        when(mResources.getStringArray(R.array.config_tether_bluetooth_regexs))
                .thenReturn(new String[0]);
        when(mResources.getIntArray(R.array.config_tether_upstream_types))
                .thenReturn(new int[0]);
        when(mResources.getBoolean(R.bool.config_tether_enable_legacy_dhcp_server)).thenReturn(
                false);
        when(mResources.getString(R.string.config_wifi_tether_enable)).thenReturn("");
        when(mLog.forSubComponent(anyString())).thenReturn(mLog);

        mMockContext = new MockContext(mContext);
        mPermissionChangeCallback = spy(() -> { });
        mEnMgr = new WrappedEntitlementManager(mMockContext, new Handler(mLooper.getLooper()), mLog,
                mPermissionChangeCallback);
        mEnMgr.setOnUiEntitlementFailedListener(mEntitlementFailedListener);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        mEnMgr.setTetheringConfigurationFetcher(() -> {
            return mConfig;
        });
    }

    @After
    public void tearDown() throws Exception {
        mMockingSession.finishMocking();
    }

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        when(mResources.getString(R.string.config_mobile_hotspot_provision_app_no_ui))
                .thenReturn(PROVISIONING_NO_UI_APP_NAME);
        when(mResources.getString(R.string.config_mobile_hotspot_provision_response)).thenReturn(
                PROVISIONING_APP_RESPONSE);
        // Act like the CarrierConfigManager is present and ready unless told otherwise.
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(mCarrierConfig);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL, true);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
    }

    @Test
    public void canRequireProvisioning() {
        setupForRequiredProvisioning();
        assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void toleratesCarrierConfigManagerMissing() {
        setupForRequiredProvisioning();
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
            .thenReturn(null);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        // Couldn't get the CarrierConfigManager, but still had a declared provisioning app.
        // Therefore provisioning still be required.
        assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void toleratesCarrierConfigMissing() {
        setupForRequiredProvisioning();
        when(mCarrierConfigManager.getConfig()).thenReturn(null);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        // We still have a provisioning app configured, so still require provisioning.
        assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void toleratesCarrierConfigNotLoaded() {
        setupForRequiredProvisioning();
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_CARRIER_CONFIG_APPLIED_BOOL, false);
        // We still have a provisioning app configured, so still require provisioning.
        assertTrue(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void provisioningNotRequiredWhenAppNotFound() {
        setupForRequiredProvisioning();
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(null);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(mEnMgr.isTetherProvisioningRequired(mConfig));
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(new String[] {"malformedApp"});
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        assertFalse(mEnMgr.isTetherProvisioningRequired(mConfig));
    }

    @Test
    public void testRequestLastEntitlementCacheValue() throws Exception {
        // 1. Entitlement check is not required.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();

        setupForRequiredProvisioning();
        // 2. No cache value and don't need to run entitlement check.
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKNOWN, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, false);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 3. No cache value and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISIONING_FAILED, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 4. Cache value is TETHER_ERROR_PROVISIONING_FAILED and don't need to run entitlement
        // check.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISIONING_FAILED, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, false);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 5. Cache value is TETHER_ERROR_PROVISIONING_FAILED and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 6. Cache value is TETHER_ERROR_NO_ERROR.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 7. Test get value for other downstream type.
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKNOWN, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_USB, receiver, false);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 8. Test get value for invalid downstream type.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKNOWN, resultCode);
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI_P2P, receiver, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
    }

    private void assertPermissionChangeCallback(InOrder inOrder) {
        inOrder.verify(mPermissionChangeCallback, times(1)).run();
    }

    private void assertNoPermissionChange(InOrder inOrder) {
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void verifyPermissionResult() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);

        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: false -> true
        assertPermissionChangeCallback(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAllNotApproved() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        // Permitted: false -> false
        assertNoPermissionChange(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAnyApproved() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionWhenProvisioningNotStarted() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        assertNoPermissionChange(inOrder);
        setupForRequiredProvisioning();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        assertNoPermissionChange(inOrder);
    }

    @Test
    public void testRunTetherProvisioning() {
        final InOrder inOrder = inOrder(mPermissionChangeCallback);
        setupForRequiredProvisioning();
        // 1. start ui provisioning, upstream is mobile
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();

        // 2. start no-ui provisioning
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, false);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(1, mEnMgr.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();

        // 3. tear down mobile, then start ui provisioning
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mEnMgr.reset();

        // 4. switch upstream back to mobile
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        // Permitted: true -> true
        assertNoPermissionChange(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();

        // 5. tear down mobile, then switch SIM
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.reevaluateSimCardProvisioning(mConfig);
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mEnMgr.reset();

        // 6. switch upstream back to mobile again
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(3, mEnMgr.silentProvisionCount);
        // Permitted: true -> false
        assertPermissionChangeCallback(inOrder);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();

        // 7. start ui provisioning, upstream is mobile, downstream is ethernet
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_ETHERNET, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        // Permitted: false -> true
        assertPermissionChangeCallback(inOrder);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();

        // 8. downstream is invalid
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI_P2P, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        assertNoPermissionChange(inOrder);
        mEnMgr.reset();
    }

    @Test
    public void testCallStopTetheringWhenUiProvisioningFail() {
        setupForRequiredProvisioning();
        verify(mEntitlementFailedListener, times(0)).onUiEntitlementFailed(TETHERING_WIFI);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        verify(mEntitlementFailedListener, times(1)).onUiEntitlementFailed(TETHERING_WIFI);
    }

    @Test
    public void testsetExemptedDownstreamType() throws Exception {
        setupForRequiredProvisioning();
        // Cellular upstream is not permitted when no entitlement result.
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        // If there is exempted downstream and no other non-exempted downstreams, cellular is
        // permitted.
        mEnMgr.setExemptedDownstreamType(TETHERING_WIFI);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        // If second downstream run entitlement check fail, cellular upstream is not permitted.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

        // When second downstream is down, exempted downstream can use cellular upstream.
        assertEquals(1, mEnMgr.uiProvisionCount);
        verify(mEntitlementFailedListener).onUiEntitlementFailed(TETHERING_USB);
        mEnMgr.stopProvisioningIfNeeded(TETHERING_USB);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());

        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }
}
