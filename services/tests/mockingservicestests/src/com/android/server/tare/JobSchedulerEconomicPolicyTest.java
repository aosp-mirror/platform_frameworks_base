/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.tare;

import static android.app.tare.EconomyManager.arcToCake;
import static android.provider.Settings.Global.TARE_JOB_SCHEDULER_CONSTANTS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.tare.EconomyManager;
import android.content.ContentResolver;
import android.content.Context;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.provider.DeviceConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.mockito.stubbing.Answer;

@RunWith(AndroidJUnit4.class)
public class JobSchedulerEconomicPolicyTest {
    private JobSchedulerEconomicPolicy mEconomicPolicy;
    private DeviceConfig.Properties.Builder mDeviceConfigPropertiesBuilder;
    private final EconomicPolicy.Injector mInjector = new InjectorForTest();

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;
    @Mock
    private InternalResourceService mIrs;

    private static class InjectorForTest extends EconomicPolicy.Injector {
        public String settingsConstant;

        @Nullable
        @Override
        String getSettingsGlobalString(@NonNull ContentResolver resolver, @NonNull String name) {
            return TARE_JOB_SCHEDULER_CONSTANTS.equals(name) ? settingsConstant : null;
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .spyStatic(DeviceConfig.class)
                .startMocking();

        when(mIrs.getContext()).thenReturn(mContext);
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        when(mContext.getContentResolver()).thenReturn(mock(ContentResolver.class));
        // Called by Modifiers.
        when(mContext.getSystemService(BatteryManager.class))
            .thenReturn(mock(BatteryManager.class));
        when(mContext.getSystemService(PowerManager.class))
            .thenReturn(mock(PowerManager.class));
        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUidObserver(any(), anyInt(), anyInt(), any());
        } catch (RemoteException e) {
            fail("registerUidObserver threw exception: " + e.getMessage());
        }

        mDeviceConfigPropertiesBuilder =
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_TARE);
        doAnswer(
                (Answer<DeviceConfig.Properties>) invocationOnMock
                        -> mDeviceConfigPropertiesBuilder.build())
                .when(() -> DeviceConfig.getProperties(
                        eq(DeviceConfig.NAMESPACE_TARE), ArgumentMatchers.<String>any()));

        // Initialize real objects.
        // Capture the listeners.
        mEconomicPolicy = new JobSchedulerEconomicPolicy(mIrs, mInjector);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void setDeviceConfigCakes(String key, long valCakes) {
        mDeviceConfigPropertiesBuilder.setString(key, valCakes + "c");
        mEconomicPolicy.setup(mDeviceConfigPropertiesBuilder.build());
    }

    @Test
    public void testDefaults() {
        assertEquals(EconomyManager.DEFAULT_JS_INITIAL_CONSUMPTION_LIMIT_CAKES,
                mEconomicPolicy.getInitialSatiatedConsumptionLimit());
        assertEquals(EconomyManager.DEFAULT_JS_HARD_CONSUMPTION_LIMIT_CAKES,
                mEconomicPolicy.getHardSatiatedConsumptionLimit());
        final String pkgRestricted = "com.pkg.restricted";
        when(mIrs.isPackageRestricted(anyInt(), eq(pkgRestricted))).thenReturn(true);
        assertEquals(0, mEconomicPolicy.getMaxSatiatedBalance(0, pkgRestricted));
        assertEquals(EconomyManager.DEFAULT_JS_MAX_SATIATED_BALANCE_CAKES,
                mEconomicPolicy.getMaxSatiatedBalance(0, "com.any.other.app"));
        final String pkgExempted = "com.pkg.exempted";
        when(mIrs.isPackageExempted(anyInt(), eq(pkgExempted))).thenReturn(true);
        assertEquals(EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_EXEMPTED_CAKES,
                mEconomicPolicy.getMinSatiatedBalance(0, pkgExempted));
        assertEquals(EconomyManager.DEFAULT_JS_MIN_SATIATED_BALANCE_OTHER_APP_CAKES,
                mEconomicPolicy.getMinSatiatedBalance(0, "com.any.other.app"));
    }

    @Test
    public void testConstantsUpdating_ValidValues() {
        setDeviceConfigCakes(EconomyManager.KEY_JS_INITIAL_CONSUMPTION_LIMIT, arcToCake(5));
        setDeviceConfigCakes(EconomyManager.KEY_JS_HARD_CONSUMPTION_LIMIT, arcToCake(25));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MAX_SATIATED_BALANCE, arcToCake(10));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED, arcToCake(9));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP, arcToCake(7));

        assertEquals(arcToCake(5), mEconomicPolicy.getInitialSatiatedConsumptionLimit());
        assertEquals(arcToCake(25), mEconomicPolicy.getHardSatiatedConsumptionLimit());
        final String pkgRestricted = "com.pkg.restricted";
        when(mIrs.isPackageRestricted(anyInt(), eq(pkgRestricted))).thenReturn(true);
        assertEquals(arcToCake(0), mEconomicPolicy.getMaxSatiatedBalance(0, pkgRestricted));
        assertEquals(arcToCake(10), mEconomicPolicy.getMaxSatiatedBalance(0, "com.any.other.app"));
        final String pkgExempted = "com.pkg.exempted";
        when(mIrs.isPackageExempted(anyInt(), eq(pkgExempted))).thenReturn(true);
        assertEquals(arcToCake(9), mEconomicPolicy.getMinSatiatedBalance(0, pkgExempted));
        assertEquals(arcToCake(7), mEconomicPolicy.getMinSatiatedBalance(0, "com.any.other.app"));
    }

    @Test
    public void testConstantsUpdating_InvalidValues() {
        // Test negatives.
        setDeviceConfigCakes(EconomyManager.KEY_JS_INITIAL_CONSUMPTION_LIMIT, arcToCake(-5));
        setDeviceConfigCakes(EconomyManager.KEY_JS_HARD_CONSUMPTION_LIMIT, arcToCake(-5));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MAX_SATIATED_BALANCE, arcToCake(-1));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED, arcToCake(-2));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP, arcToCake(-3));

        assertEquals(arcToCake(1), mEconomicPolicy.getInitialSatiatedConsumptionLimit());
        assertEquals(arcToCake(1), mEconomicPolicy.getHardSatiatedConsumptionLimit());
        final String pkgRestricted = "com.pkg.restricted";
        when(mIrs.isPackageRestricted(anyInt(), eq(pkgRestricted))).thenReturn(true);
        assertEquals(arcToCake(0), mEconomicPolicy.getMaxSatiatedBalance(0, pkgRestricted));
        assertEquals(arcToCake(1), mEconomicPolicy.getMaxSatiatedBalance(0, "com.any.other.app"));
        final String pkgExempted = "com.pkg.exempted";
        when(mIrs.isPackageExempted(anyInt(), eq(pkgExempted))).thenReturn(true);
        assertEquals(arcToCake(0), mEconomicPolicy.getMinSatiatedBalance(0, pkgExempted));
        assertEquals(arcToCake(0), mEconomicPolicy.getMinSatiatedBalance(0, "com.any.other.app"));

        // Test min+max reversed.
        setDeviceConfigCakes(EconomyManager.KEY_JS_INITIAL_CONSUMPTION_LIMIT, arcToCake(5));
        setDeviceConfigCakes(EconomyManager.KEY_JS_HARD_CONSUMPTION_LIMIT, arcToCake(3));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MAX_SATIATED_BALANCE, arcToCake(10));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_EXEMPTED, arcToCake(11));
        setDeviceConfigCakes(EconomyManager.KEY_JS_MIN_SATIATED_BALANCE_OTHER_APP, arcToCake(13));

        assertEquals(arcToCake(5), mEconomicPolicy.getInitialSatiatedConsumptionLimit());
        assertEquals(arcToCake(5), mEconomicPolicy.getHardSatiatedConsumptionLimit());
        assertEquals(arcToCake(0), mEconomicPolicy.getMaxSatiatedBalance(0, pkgRestricted));
        assertEquals(arcToCake(13), mEconomicPolicy.getMaxSatiatedBalance(0, "com.any.other.app"));
        assertEquals(arcToCake(13), mEconomicPolicy.getMinSatiatedBalance(0, pkgExempted));
        assertEquals(arcToCake(13), mEconomicPolicy.getMinSatiatedBalance(0, "com.any.other.app"));
    }
}
