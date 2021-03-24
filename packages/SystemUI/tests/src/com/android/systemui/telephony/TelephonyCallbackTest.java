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

package com.android.systemui.telephony;

import static com.google.common.truth.Truth.assertThat;

import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import android.telephony.TelephonyCallback.CallStateListener;
import android.telephony.TelephonyCallback.ServiceStateListener;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TelephonyCallbackTest extends SysuiTestCase {

    private TelephonyCallback mTelephonyCallback = new TelephonyCallback();
    
    @Test
    public void testAddListener_ActiveDataSubscriptionIdListener() {
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
        mTelephonyCallback.addActiveDataSubscriptionIdListener(subId -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.addActiveDataSubscriptionIdListener(subId -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
    }

    @Test
    public void testAddListener_CallStateListener() {
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
        mTelephonyCallback.addCallStateListener(state -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.addCallStateListener(state -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
    }

    @Test
    public void testAddListener_ServiceStateListener() {
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
        mTelephonyCallback.addServiceStateListener(serviceState -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.addServiceStateListener(serviceState -> {});
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
    }

    @Test
    public void testRemoveListener_ActiveDataSubscriptionIdListener() {
        ActiveDataSubscriptionIdListener listener = subId -> {};
        mTelephonyCallback.addActiveDataSubscriptionIdListener(listener);
        mTelephonyCallback.addActiveDataSubscriptionIdListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeActiveDataSubscriptionIdListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeActiveDataSubscriptionIdListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
    }

    @Test
    public void testRemoveListener_CallStateListener() {
        CallStateListener listener = state -> {};
        mTelephonyCallback.addCallStateListener(listener);
        mTelephonyCallback.addCallStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeCallStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeCallStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
    }

    @Test
    public void testRemoveListener_ServiceStateListener() {
        ServiceStateListener listener = serviceState -> {};
        mTelephonyCallback.addServiceStateListener(listener);
        mTelephonyCallback.addServiceStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeServiceStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isTrue();
        mTelephonyCallback.removeServiceStateListener(listener);
        assertThat(mTelephonyCallback.hasAnyListeners()).isFalse();
    }
}
