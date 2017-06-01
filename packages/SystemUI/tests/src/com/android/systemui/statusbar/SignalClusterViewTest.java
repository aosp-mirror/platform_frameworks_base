/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.telephony.SubscriptionInfo;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.policy.NetworkController.IconState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class SignalClusterViewTest extends SysuiTestCase {

    private SignalClusterView mSignalCluster;

    @Before
    public void setup() {
        mSignalCluster = (SignalClusterView) LayoutInflater.from(mContext)
                .inflate(R.layout.signal_cluster_view, null);
    }

    @Test
    public void testNonDefaultSim() {
        SubscriptionInfo first = mock(SubscriptionInfo.class);
        SubscriptionInfo second = mock(SubscriptionInfo.class);
        when(first.getSubscriptionId()).thenReturn(0);
        when(second.getSubscriptionId()).thenReturn(1);
        mSignalCluster.setSubs(Arrays.asList(first, second));
        mSignalCluster.setQsSignalCluster();
        mSignalCluster.setMobileDataIndicators(new IconState(true, 0, 0, ""), null, 0, 0,
                false, false, "", "", false, 1, false);
    }

}