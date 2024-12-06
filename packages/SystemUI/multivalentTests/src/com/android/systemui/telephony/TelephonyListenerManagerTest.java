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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.telephony.TelephonyCallback.ActiveDataSubscriptionIdListener;
import android.telephony.TelephonyCallback.CallStateListener;
import android.telephony.TelephonyCallback.ServiceStateListener;
import android.telephony.TelephonyManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class TelephonyListenerManagerTest extends SysuiTestCase {

    @Mock
    private TelephonyManager mTelephonyManager;
    private FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());
    @Mock
    private TelephonyCallback mTelephonyCallback;

    TelephonyListenerManager mTelephonyListenerManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTelephonyListenerManager = new TelephonyListenerManager(
                mTelephonyManager, mExecutor, mTelephonyCallback);
    }

    @Test
    public void testAddListenerRegisters_ActiveDataSubscriptionIdListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});

        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
    }

    @Test
    public void testAddListenerRegisters_CallStateListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        mTelephonyListenerManager.addCallStateListener(state -> {});
        mTelephonyListenerManager.addCallStateListener(state -> {});
        mTelephonyListenerManager.addCallStateListener(state -> {});
        mTelephonyListenerManager.addCallStateListener(state -> {});

        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
    }

    @Test
    public void testAddListenerRegisters_ServiceStateListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});

        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
    }

    @Test
    public void testAddListenerRegisters_mixed() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        mTelephonyListenerManager.addCallStateListener(state -> {});
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        mTelephonyListenerManager.addCallStateListener(state -> {});
        mTelephonyListenerManager.addServiceStateListener(serviceState -> {});

        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
    }

    @Test
    public void testRemoveListenerUnregisters_ActiveDataSubscriptionIdListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        ActiveDataSubscriptionIdListener mListener = subId -> { };

        // Need to add one to actually register
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mListener);
        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
        reset(mTelephonyManager);

        when(mTelephonyCallback.hasAnyListeners()).thenReturn(false);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListener);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListener);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListener);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListener);
        verify(mTelephonyManager, times(1))
                .unregisterTelephonyCallback(mTelephonyCallback);
    }

    @Test
    public void testRemoveListenerUnregisters_CallStateListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        CallStateListener mListener = state -> { };

        // Need to add one to actually register
        mTelephonyListenerManager.addCallStateListener(mListener);
        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
        reset(mTelephonyManager);

        when(mTelephonyCallback.hasAnyListeners()).thenReturn(false);
        mTelephonyListenerManager.removeCallStateListener(mListener);
        mTelephonyListenerManager.removeCallStateListener(mListener);
        mTelephonyListenerManager.removeCallStateListener(mListener);
        mTelephonyListenerManager.removeCallStateListener(mListener);
        verify(mTelephonyManager, times(1))
                .unregisterTelephonyCallback(mTelephonyCallback);
    }

    @Test
    public void testRemoveListenerUnregisters_ServiceStateListener() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        ServiceStateListener mListener = serviceState -> { };

        // Need to add one to actually register
        mTelephonyListenerManager.addServiceStateListener(mListener);
        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
        reset(mTelephonyManager);

        when(mTelephonyCallback.hasAnyListeners()).thenReturn(false);
        mTelephonyListenerManager.removeServiceStateListener(mListener);
        mTelephonyListenerManager.removeServiceStateListener(mListener);
        mTelephonyListenerManager.removeServiceStateListener(mListener);
        mTelephonyListenerManager.removeServiceStateListener(mListener);
        verify(mTelephonyManager, times(1))
                .unregisterTelephonyCallback(mTelephonyCallback);
    }

    @Test
    public void testRemoveListenerUnregisters_mixed() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        ActiveDataSubscriptionIdListener mListenerA = subId -> { };
        ServiceStateListener mListenerB = serviceState -> { };
        CallStateListener mListenerC = state -> { };

        // Need to add one to actually register
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(mListenerA);
        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);
        reset(mTelephonyManager);

        when(mTelephonyCallback.hasAnyListeners()).thenReturn(false);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListenerA);
        mTelephonyListenerManager.removeServiceStateListener(mListenerB);
        mTelephonyListenerManager.removeCallStateListener(mListenerC);
        mTelephonyListenerManager.removeActiveDataSubscriptionIdListener(mListenerA);
        mTelephonyListenerManager.removeServiceStateListener(mListenerB);
        mTelephonyListenerManager.removeCallStateListener(mListenerC);
        verify(mTelephonyManager, times(1))
                .unregisterTelephonyCallback(mTelephonyCallback);
    }

    @Test
    public void testAddListener_noDoubleRegister() {
        when(mTelephonyCallback.hasAnyListeners()).thenReturn(true);
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        verify(mTelephonyManager, times(1))
                .registerTelephonyCallback(mExecutor, mTelephonyCallback);

        reset(mTelephonyManager);

        // A second call to add doesn't register another listener.
        mTelephonyListenerManager.addActiveDataSubscriptionIdListener(subId -> {});
        verify(mTelephonyManager, never()).registerTelephonyCallback(mExecutor, mTelephonyCallback);
    }
}
