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

import static android.net.ConnectivityManager.TETHERING_USB;
import static android.net.ConnectivityManager.TETHERING_WIFI;
import static android.net.ConnectivityManager.TETHER_ERROR_ENTITLEMENT_UNKONWN;
import static android.net.ConnectivityManager.TETHER_ERROR_NO_ERROR;
import static android.net.ConnectivityManager.TETHER_ERROR_PROVISION_FAILED;
import static android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.util.SharedLog;
import android.os.Bundle;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.ResultReceiver;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.telephony.CarrierConfigManager;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.test.BroadcastInterceptingContext;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.connectivity.MockableSystemProperties;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
@SmallTest
public final class EntitlementManagerTest {

    private static final int EVENT_EM_UPDATE = 1;
    private static final String[] PROVISIONING_APP_NAME = {"some", "app"};

    @Mock private CarrierConfigManager mCarrierConfigManager;
    @Mock private Context mContext;
    @Mock private MockableSystemProperties mSystemProperties;
    @Mock private Resources mResources;
    @Mock private SharedLog mLog;

    // Like so many Android system APIs, these cannot be mocked because it is marked final.
    // We have to use the real versions.
    private final PersistableBundle mCarrierConfig = new PersistableBundle();
    private final TestLooper mLooper = new TestLooper();
    private Context mMockContext;
    private MockContentResolver mContentResolver;

    private TestStateMachine mSM;
    private WrappedEntitlementManager mEnMgr;

    private class MockContext extends BroadcastInterceptingContext {
        MockContext(Context base) {
            super(base);
        }

        @Override
        public Resources getResources() {
            return mResources;
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }
    }

    public class WrappedEntitlementManager extends EntitlementManager {
        public int fakeEntitlementResult = TETHER_ERROR_ENTITLEMENT_UNKONWN;
        public boolean everRunUiEntitlement = false;

        public WrappedEntitlementManager(Context ctx, StateMachine target,
                SharedLog log, MockableSystemProperties systemProperties) {
            super(ctx, target, log, systemProperties);
        }

        @Override
        protected void runUiTetherProvisioning(int type, ResultReceiver receiver) {
            everRunUiEntitlement = true;
            receiver.send(fakeEntitlementResult, null);
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

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
        when(mLog.forSubComponent(anyString())).thenReturn(mLog);

        mContentResolver = new MockContentResolver();
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        mMockContext = new MockContext(mContext);
        mSM = new TestStateMachine();
        mEnMgr = new WrappedEntitlementManager(mMockContext, mSM, mLog, mSystemProperties);
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
    }

    @After
    public void tearDown() throws Exception {
        if (mSM != null) {
            mSM.quit();
            mSM = null;
        }
    }

    private void setupForRequiredProvisioning() {
        // Produce some acceptable looking provision app setting if requested.
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
                .thenReturn(PROVISIONING_APP_NAME);
        // Don't disable tethering provisioning unless requested.
        when(mSystemProperties.getBoolean(eq(EntitlementManager.DISABLE_PROVISIONING_SYSPROP_KEY),
                anyBoolean())).thenReturn(false);
        // Act like the CarrierConfigManager is present and ready unless told otherwise.
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
                .thenReturn(mCarrierConfigManager);
        when(mCarrierConfigManager.getConfig()).thenReturn(mCarrierConfig);
        mCarrierConfig.putBoolean(CarrierConfigManager.KEY_REQUIRE_ENTITLEMENT_CHECKS_BOOL, true);
    }

    @Test
    public void canRequireProvisioning() {
        setupForRequiredProvisioning();
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigManagerMissing() {
        setupForRequiredProvisioning();
        when(mContext.getSystemService(Context.CARRIER_CONFIG_SERVICE))
            .thenReturn(null);
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
        // Couldn't get the CarrierConfigManager, but still had a declared provisioning app.
        // Therefore provisioning still be required.
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void toleratesCarrierConfigMissing() {
        setupForRequiredProvisioning();
        when(mCarrierConfigManager.getConfig()).thenReturn(null);
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
        // We still have a provisioning app configured, so still require provisioning.
        assertTrue(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void provisioningNotRequiredWhenAppNotFound() {
        setupForRequiredProvisioning();
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(null);
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
        assertFalse(mEnMgr.isTetherProvisioningRequired());
        when(mResources.getStringArray(R.array.config_mobile_hotspot_provision_app))
            .thenReturn(new String[] {"malformedApp"});
        mEnMgr.updateConfiguration(
                new TetheringConfiguration(mMockContext, mLog, INVALID_SUBSCRIPTION_ID));
        assertFalse(mEnMgr.isTetherProvisioningRequired());
    }

    @Test
    public void testGetLastEntitlementCacheValue() throws Exception {
        final CountDownLatch mCallbacklatch = new CountDownLatch(1);
        // 1. Entitlement check is not required.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.everRunUiEntitlement = false;
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, true);
        callbackTimeoutHelper(mCallbacklatch);
        assertFalse(mEnMgr.everRunUiEntitlement);

        setupForRequiredProvisioning();
        mEnMgr.updateConfiguration(new TetheringConfiguration(mMockContext, mLog,
                  INVALID_SUBSCRIPTION_ID));
        // 2. No cache value and don't need to run entitlement check.
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKONWN, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, false);
        callbackTimeoutHelper(mCallbacklatch);
        assertFalse(mEnMgr.everRunUiEntitlement);
        // 3. No cache value and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_PROVISION_FAILED;
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISION_FAILED, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertTrue(mEnMgr.everRunUiEntitlement);
        // 4. Cache value is TETHER_ERROR_PROVISION_FAILED and don't need to run entitlement check.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_PROVISION_FAILED, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, false);
        callbackTimeoutHelper(mCallbacklatch);
        assertFalse(mEnMgr.everRunUiEntitlement);
        // 5. Cache value is TETHER_ERROR_PROVISION_FAILED and ui entitlement check is needed.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, true);
        mLooper.dispatchAll();
        callbackTimeoutHelper(mCallbacklatch);
        assertTrue(mEnMgr.everRunUiEntitlement);
        // 6. Cache value is TETHER_ERROR_NO_ERROR.
        mEnMgr.fakeEntitlementResult = TETHER_ERROR_NO_ERROR;
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_NO_ERROR, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_WIFI, receiver, true);
        callbackTimeoutHelper(mCallbacklatch);
        assertFalse(mEnMgr.everRunUiEntitlement);
        // 7. Test get value for other downstream type.
        mEnMgr.everRunUiEntitlement = false;
        receiver = new ResultReceiver(null) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                assertEquals(TETHER_ERROR_ENTITLEMENT_UNKONWN, resultCode);
                mCallbacklatch.countDown();
            }
        };
        mEnMgr.getLatestTetheringEntitlementValue(TETHERING_USB, receiver, false);
        callbackTimeoutHelper(mCallbacklatch);
        assertFalse(mEnMgr.everRunUiEntitlement);
    }

    void callbackTimeoutHelper(final CountDownLatch latch) throws Exception {
        if (!latch.await(1, TimeUnit.SECONDS)) {
            fail("Timout, fail to recieve callback");
        }
    }
    public class TestStateMachine extends StateMachine {
        public final ArrayList<Message> messages = new ArrayList<>();
        private final State mLoggingState =
                new EntitlementManagerTest.TestStateMachine.LoggingState();

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
