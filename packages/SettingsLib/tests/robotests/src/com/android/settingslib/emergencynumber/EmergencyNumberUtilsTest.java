/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.settingslib.emergencynumber;

import static android.telephony.emergency.EmergencyNumber.EMERGENCY_SERVICE_CATEGORY_POLICE;

import static com.android.settingslib.emergencynumber.EmergencyNumberUtils.EMERGENCY_GESTURE_CALL_NUMBER;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.telephony.CarrierConfigManager;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.ArrayMap;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
public class EmergencyNumberUtilsTest {
    private static final String TELEPHONY_EMERGENCY_NUMBER = "1234";
    private static final String USER_OVERRIDE_EMERGENCY_NUMBER = "5678";

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private ContentResolver mContentResolver;
    @Mock
    private CarrierConfigManager mCarrierConfigManager;

    private EmergencyNumberUtils mUtils;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getPackageManager()).thenReturn(mPackageManager);
        when(mContext.getContentResolver()).thenReturn(mContentResolver);
        when(mContext.getSystemService(CarrierConfigManager.class)).thenReturn(
                mCarrierConfigManager);
    }

    @Test
    public void getDefaultPoliceNumber_noTelephony_shouldReturnDefault() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(false);
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(
                EmergencyNumberUtils.FALL_BACK_NUMBER);
    }

    @Test
    public void getDefaultPoliceNumber_hasTelephony_shouldLoadFromTelephony() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);
        mUtils = new EmergencyNumberUtils(mContext);


        assertThat(mUtils.getDefaultPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    @Test
    public void getPoliceNumber_hasUserOverride_shouldLoadFromUserOverride() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);

        Bundle bundle = new Bundle();
        bundle.putString(EMERGENCY_GESTURE_CALL_NUMBER, USER_OVERRIDE_EMERGENCY_NUMBER);
        when(mContentResolver.call(any(Uri.class), anyString(), nullable(String.class), nullable(
                Bundle.class))).thenReturn(bundle);
        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(USER_OVERRIDE_EMERGENCY_NUMBER);
    }

    @Test
    public void getPoliceNumber_noUserOverride_shouldLoadFromTelephony() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        addEmergencyNumberToTelephony(TELEPHONY_EMERGENCY_NUMBER);

        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    @Test
    public void getPoliceNumber_hasCarrierPrefix_shouldRemovePrefix() {
        when(mPackageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)).thenReturn(true);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        final String prefix = "*272";
        final PersistableBundle bundle = new PersistableBundle();
        bundle.putStringArray(CarrierConfigManager.KEY_EMERGENCY_NUMBER_PREFIX_STRING_ARRAY,
                new String[]{prefix});
        when(mCarrierConfigManager.getConfigForSubId(anyInt())).thenReturn(bundle);
        addEmergencyNumberToTelephony(prefix + TELEPHONY_EMERGENCY_NUMBER);

        mUtils = new EmergencyNumberUtils(mContext);

        assertThat(mUtils.getPoliceNumber()).isEqualTo(TELEPHONY_EMERGENCY_NUMBER);
    }

    private void addEmergencyNumberToTelephony(String number) {
        final int subId = SubscriptionManager.getDefaultSubscriptionId();
        EmergencyNumber emergencyNumber = mock(EmergencyNumber.class);
        when(emergencyNumber.isInEmergencyServiceCategories(EMERGENCY_SERVICE_CATEGORY_POLICE))
                .thenReturn(true);
        Map<Integer, List<EmergencyNumber>> numbers = new ArrayMap<>();
        List<EmergencyNumber> numbersForSubId = new ArrayList<>();
        numbersForSubId.add(emergencyNumber);
        numbers.put(subId, numbersForSubId);
        when(mTelephonyManager.getEmergencyNumberList(anyInt())).thenReturn(numbers);
        when(emergencyNumber.getNumber()).thenReturn(number);
    }
}
