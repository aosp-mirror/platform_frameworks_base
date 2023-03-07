/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.net;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.NetworkTemplate;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class DataUsageUtilsTest {

    private static final int SUB_ID = 1;
    private static final int SUB_ID_2 = 2;
    private static final String SUBSCRIBER_ID = "Test Subscriber";
    private static final String SUBSCRIBER_ID_2 = "Test Subscriber 2";

    @Mock
    private TelephonyManager mTelephonyManager;
    @Mock
    private SubscriptionManager mSubscriptionManager;
    @Mock
    private SubscriptionInfo mInfo1;
    @Mock
    private SubscriptionInfo mInfo2;
    @Mock
    private ParcelUuid mParcelUuid;
    private Context mContext;
    private List<SubscriptionInfo> mInfos;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mContext = spy(RuntimeEnvironment.application);
        when(mContext.getSystemService(TelephonyManager.class)).thenReturn(mTelephonyManager);
        when(mContext.getSystemService(SubscriptionManager.class)).thenReturn(mSubscriptionManager);
        when(mTelephonyManager.getSubscriberId(SUB_ID)).thenReturn(SUBSCRIBER_ID);
        when(mTelephonyManager.getSubscriberId(SUB_ID_2)).thenReturn(SUBSCRIBER_ID_2);
        when(mTelephonyManager.createForSubscriptionId(anyInt())).thenReturn(mTelephonyManager);
        when(mSubscriptionManager.isActiveSubscriptionId(anyInt())).thenReturn(true);
    }

    @Test
    @Ignore
    public void getMobileTemplate_infoNull_returnMobileAll() {
        when(mSubscriptionManager.isActiveSubscriptionId(SUB_ID)).thenReturn(false);

        final NetworkTemplate networkTemplate = DataUsageUtils.getMobileTemplate(mContext, SUB_ID);
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID)).isTrue();
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID_2)).isFalse();
    }

    @Test
    @Ignore
    public void getMobileTemplate_groupUuidNull_returnMobileAll() {
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(mInfo1);
        when(mInfo1.getGroupUuid()).thenReturn(null);
        when(mTelephonyManager.getMergedImsisFromGroup())
                .thenReturn(new String[] {SUBSCRIBER_ID});

        final NetworkTemplate networkTemplate = DataUsageUtils.getMobileTemplate(mContext, SUB_ID);
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID)).isTrue();
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID_2)).isFalse();
    }

    @Test
    @Ignore
    public void getMobileTemplate_groupUuidExist_returnMobileMerged() {
        when(mSubscriptionManager.getActiveSubscriptionInfo(SUB_ID)).thenReturn(mInfo1);
        when(mInfo1.getGroupUuid()).thenReturn(mParcelUuid);
        when(mTelephonyManager.getMergedImsisFromGroup())
                .thenReturn(new String[] {SUBSCRIBER_ID, SUBSCRIBER_ID_2});

        final NetworkTemplate networkTemplate = DataUsageUtils.getMobileTemplate(mContext, SUB_ID);
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID)).isTrue();
        assertThat(networkTemplate.getSubscriberIds().contains(SUBSCRIBER_ID_2)).isTrue();
    }
}
