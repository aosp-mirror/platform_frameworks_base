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

package com.android.server.connectivity.tethering;

import static android.net.TetheringManager.TETHERING_BLUETOOTH;
import static android.net.TetheringManager.TETHERING_USB;
import static android.net.TetheringManager.TETHERING_WIFI;
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
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.Bundle;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.telephony.CarrierConfigManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.networkstack.tethering.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class EntitlementManagerTest {

    private static final int EVENT_EM_UPDATE = 1;
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};
    private static final String PROVISIONING_NO_UI_APP_NAME = "no_ui_app";

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

    private TestStateMachine mSM;
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

        public WrappedEntitlementManager(Context ctx, StateMachine target,
                SharedLog log, int what) {
            super(ctx, target, log, what);
        }

        public void reset() {
            fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKNOWN;
            uiProvisionCount = 0;
            silentProvisionCount = 0;
        }

        @Override
        protected void runUiTetherProvisioning(int type, int subId, ResultReceiver receiver) {
            uiProvisionCount++;
            receiver.send(fakeEntitlementResult, null);
        }

        @Override
        protected void runSilentTetherProvisioning(int type, int subId) {
            silentProvisionCount++;
            addDownstreamMapping(type, fakeEntitlementResult);
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
        doReturn(false).when(
                () -> DeviceConfig.getBoolean(eq(NAMESPACE_CONNECTIVITY),
                eq(TetheringConfiguration.TETHER_ENABLE_LEGACY_DHCP_SERVER), anyBoolean()));

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
        mSM = new TestStateMachine();
        mEnMgr = new WrappedEntitlementManager(mMockContext, mSM, mLog, EVENT_EM_UPDATE);
        mEnMgr.setOnUiEntitlementFailedListener(mEntitlementFailedListener);
        mConfig = new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID);
        mEnMgr.setTetheringConfigurationFetcher(() -> {
            return mConfig;
        });
    }

    @After
    public void tearDown() throws Exception {
        if (mSM != null) {
            mSM.quit();
            mSM = null;
        }
        mMockingSession.finishMocking();
    }

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        when(mResources.getString(R.string.config_mobile_hotspot_provision_app_no_ui))
                .thenReturn(PROVISIONING_NO_UI_APP_NAME);
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
        final CountDownLatch mCallbacklatch = new CountDownLatch(1);
        // 1. Entitlement check is not required.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();

        setupForRequiredProvisioning();
        // 2. No cache value and don't need to run entitlement check.
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKNOWN, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, false);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 3. No cache value and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISIONING_FAILED, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(1, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 4. Cache value is TETHER_ERROR_PROVISIONING_FAILED and don't need to run entitlement
        // check.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISIONING_FAILED, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, false);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 5. Cache value is TETHER_ERROR_PROVISIONING_FAILED and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(1, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 6. Cache value is TETHER_ERROR_NO_ERROR.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
        // 7. Test get value for other downstream type.
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKNOWN, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.requestLatestTetheringEntitlementResult(TETHERING_USB, receiver, false);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertEquals(0, mEnMgr.uiProvisionCount);
        mEnMgr.reset();
    }

    void callbackTimeoutHelper(final CountDownLatch latch) throws Exception {
        if (!latch.await(1, TimeUnit.SECONDS)) {
            fail("Timout, fail to receive callback");
        }
    }

    @Test
    public void verifyPermissionResult() {
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAllNotApproved() {
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void verifyPermissionIfAnyApproved() {
        setupForRequiredProvisioning();
        mEnMgr.notifyUpstream(true);
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, true);
        mLooper.dispatchAll();
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mLooper.dispatchAll();
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.stopProvisioningIfNeeded(TETHERING_WIFI);
        mLooper.dispatchAll();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());

    }

    @Test
    public void verifyPermissionWhenProvisioningNotStarted() {
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        setupForRequiredProvisioning();
        assertFalse(mEnMgr.isCellularUpstreamPermitted());
    }

    @Test
    public void testRunTetherProvisioning() {
        setupForRequiredProvisioning();
        // 1. start ui provisioning, upstream is mobile
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_USB, true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();
        // 2. start no-ui provisioning
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.startProvisioningIfNeeded(TETHERING_WIFI, false);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(1, mEnMgr.silentProvisionCount);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();
        // 3. tear down mobile, then start ui provisioning
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.startProvisioningIfNeeded(TETHERING_BLUETOOTH, true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        mEnMgr.reset();
        // 4. switch upstream back to mobile
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(1, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        assertTrue(mEnMgr.isCellularUpstreamPermitted());
        mEnMgr.reset();
        // 5. tear down mobile, then switch SIM
        mEnMgr.notifyUpstream(false);
        mLooper.dispatchAll();
        mEnMgr.reevaluateSimCardProvisioning(mConfig);
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(0, mEnMgr.silentProvisionCount);
        mEnMgr.reset();
        // 6. switch upstream back to mobile again
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISIONING_FAILED;
        mEnMgr.notifyUpstream(true);
        mLooper.dispatchAll();
        assertEquals(0, mEnMgr.uiProvisionCount);
        assertEquals(3, mEnMgr.silentProvisionCount);
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

    public class TestStateMachine extends StateMachine {
        public final ArrayList<Message> messages = new ArrayList<>();
        private final State
                mLoggingState = new EntitlementManagerTest.TestStateMachine.LoggingState();

        class LoggingState extends State {
            @Override public void enter() {
                messages.clear();
            }

            @Override public void exit() {
                messages.clear();
            }

            @Override public boolean processMessage(Message msg) {
                messages.add(msg);
                return false;
            }
        }

        public TestStateMachine() {
            super("EntitlementManagerTest.TestStateMachine", mLooper.getLooper());
            addState(mLoggingState);
            setInitialState(mLoggingState);
            super.start();
        }
    }
}
