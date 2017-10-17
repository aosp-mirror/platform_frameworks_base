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
import android.os.RemoteException;
import android.support.test.filters.SmallTest;
import android.test.AndroidTestCase;
import android.util.Slog;

import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;

import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;


public class BatteryServiceTest extends AndroidTestCase {

    @Mock IServiceManager mMockedManager;
    @Mock IHealth mMockedHal;

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
                Slog.e("BatteryServiceTest", "health: onRegistration " + invocation.getArguments()[2]);
                ((IServiceNotification)invocation.getArguments()[2]).onRegistration(
                        IHealth.kInterfaceName,
                        (String)invocation.getArguments()[1],
                        true /* preexisting */);
                return null;
            }).when(mMockedManager).registerForNotifications(
                eq(IHealth.kInterfaceName),
                argThat(isOneOf(instanceNames)),
                any(IServiceNotification.class));

        doReturn(mMockedHal).when(mMockedManager)
            .get(eq(IHealth.kInterfaceName), argThat(isOneOf(instanceNames)));

        doReturn(IServiceManager.Transport.HWBINDER).when(mMockedManager)
            .getTransport(eq(IHealth.kInterfaceName), argThat(isOneOf(instanceNames)));

        doReturn(mMockedManager).when(mManagerSupplier).get();
        doReturn(mMockedHal).when(mHealthServiceSupplier)
            .get(argThat(isOneOf(instanceNames)));

        mWrapper = new BatteryService.HealthServiceWrapper();
    }

    @SmallTest
    public void testWrapPreferVendor() throws Exception {
        initForInstances(VENDOR, HEALTHD);
        mWrapper.init(mCallback, mManagerSupplier, mHealthServiceSupplier);
        verify(mCallback).onRegistration(same(mMockedHal), eq(VENDOR));
    }

    @SmallTest
    public void testUseHealthd() throws Exception {
        initForInstances(HEALTHD);
        mWrapper.init(mCallback, mManagerSupplier, mHealthServiceSupplier);
        verify(mCallback).onRegistration(same(mMockedHal), eq(HEALTHD));
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
