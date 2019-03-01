/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static junit.framework.Assert.*;

import static org.mockito.Mockito.*;

import android.hardware.health.V2_0.IHealth;
import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.test.AndroidTestCase;

import androidx.test.filters.SmallTest;

import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;

public class BatteryServiceTest extends AndroidTestCase {

    @Mock IServiceManager mMockedManager;
    @Mock IHealth mMockedHal;
    @Mock IHealth mMockedHal2;

    @Mock BatteryService.HealthServiceWrapper.Callback mCallback;
    @Mock BatteryService.HealthServiceWrapper.IServiceManagerSupplier mManagerSupplier;
    @Mock BatteryService.HealthServiceWrapper.IHealthSupplier mHealthServiceSupplier;
    BatteryService.HealthServiceWrapper mWrapper;

    private static final String HEALTHD = BatteryService.HealthServiceWrapper.INSTANCE_HEALTHD;
    private static final String VENDOR = BatteryService.HealthServiceWrapper.INSTANCE_VENDOR;

    @Override
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Override
    public void tearDown() {
        if (mWrapper != null)
            mWrapper.getHandlerThread().quitSafely();
    }

    public static <T> ArgumentMatcher<T> isOneOf(Collection<T> collection) {
        return new ArgumentMatcher<T>() {
            @Override public boolean matches(T e) {
                return collection.contains(e);
            }
            @Override public String toString() {
                return collection.toString();
            }
        };
    }

    private void initForInstances(String... instanceNamesArr) throws Exception {
        final Collection<String> instanceNames = Arrays.asList(instanceNamesArr);
        doAnswer((invocation) -> {
                // technically, preexisting is ignored by
                // BatteryService.HealthServiceWrapper.Notification, but still call it correctly.
                sendNotification(invocation, true);
                sendNotification(invocation, true);
                sendNotification(invocation, false);
                return null;
            }).when(mMockedManager).registerForNotifications(
                eq(IHealth.kInterfaceName),
                argThat(isOneOf(instanceNames)),
                any(IServiceNotification.class));

        doReturn(mMockedManager).when(mManagerSupplier).get();
        doReturn(mMockedHal)        // init calls this
            .doReturn(mMockedHal)   // notification 1
            .doReturn(mMockedHal)   // notification 2
            .doReturn(mMockedHal2)  // notification 3
            .doThrow(new RuntimeException("Should not call getService for more than 4 times"))
            .when(mHealthServiceSupplier).get(argThat(isOneOf(instanceNames)));

        mWrapper = new BatteryService.HealthServiceWrapper();
    }

    private void waitHandlerThreadFinish() throws Exception {
        for (int i = 0; i < 5; i++) {
            if (!mWrapper.getHandlerThread().getThreadHandler().hasMessagesOrCallbacks()) {
                return;
            }
            Thread.sleep(300);
        }
        assertFalse(mWrapper.getHandlerThread().getThreadHandler().hasMessagesOrCallbacks());
    }

    private static void sendNotification(InvocationOnMock invocation, boolean preexisting)
            throws Exception {
        ((IServiceNotification)invocation.getArguments()[2]).onRegistration(
                IHealth.kInterfaceName,
                (String)invocation.getArguments()[1],
                preexisting);
    }

    @SmallTest
    public void testWrapPreferVendor() throws Exception {
        initForInstances(VENDOR, HEALTHD);
        mWrapper.init(mCallback, mManagerSupplier, mHealthServiceSupplier);
        waitHandlerThreadFinish();
        verify(mCallback, times(1)).onRegistration(same(null), same(mMockedHal), eq(VENDOR));
        verify(mCallback, never()).onRegistration(same(mMockedHal), same(mMockedHal), anyString());
        verify(mCallback, times(1)).onRegistration(same(mMockedHal), same(mMockedHal2), eq(VENDOR));
    }

    @SmallTest
    public void testUseHealthd() throws Exception {
        initForInstances(HEALTHD);
        mWrapper.init(mCallback, mManagerSupplier, mHealthServiceSupplier);
        waitHandlerThreadFinish();
        verify(mCallback, times(1)).onRegistration(same(null), same(mMockedHal), eq(HEALTHD));
        verify(mCallback, never()).onRegistration(same(mMockedHal), same(mMockedHal), anyString());
        verify(mCallback, times(1)).onRegistration(same(mMockedHal), same(mMockedHal2), eq(HEALTHD));
    }

    @SmallTest
    public void testNoService() throws Exception {
        initForInstances("unrelated");
        try {
            mWrapper.init(mCallback, mManagerSupplier, mHealthServiceSupplier);
            fail("Expect NoSuchElementException");
        } catch (NoSuchElementException ex) {
            // expected
        }
    }
}
