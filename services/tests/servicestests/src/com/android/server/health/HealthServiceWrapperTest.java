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

package com.android.server.health;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.fail;

import static org.mockito.AdditionalMatchers.not;
import static org.mockito.Mockito.*;

import android.hidl.manager.V1_0.IServiceManager;
import android.hidl.manager.V1_0.IServiceNotification;
import android.os.IServiceCallback;
import android.os.RemoteException;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;

import java.util.Arrays;
import java.util.Collection;
import java.util.NoSuchElementException;

@RunWith(AndroidJUnit4.class)
public class HealthServiceWrapperTest {
    @Mock IServiceManager mMockedManager;
    @Mock android.hardware.health.V2_0.IHealth mMockedHal;
    @Mock android.hardware.health.V2_0.IHealth mMockedHal2;

    @Mock HealthServiceWrapperHidl.Callback mCallback;
    @Mock HealthServiceWrapperHidl.IServiceManagerSupplier mManagerSupplier;
    @Mock HealthServiceWrapperHidl.IHealthSupplier mHealthServiceSupplier;

    @Mock android.hardware.health.IHealth.Stub mMockedAidlHal;
    @Mock android.hardware.health.IHealth.Stub mMockedAidlHal2;
    @Mock HealthServiceWrapperAidl.ServiceManagerStub mMockedAidlManager;
    @Mock HealthRegCallbackAidl mRegCallbackAidl;

    HealthServiceWrapper mWrapper;

    private static final String VENDOR = HealthServiceWrapperHidl.INSTANCE_VENDOR;
    private static final String AIDL_SERVICE_NAME = HealthServiceWrapperAidl.SERVICE_NAME;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        // Mocks the conversion between IHealth and IBinder.
        when(mMockedAidlHal.asBinder()).thenCallRealMethod(); // returns mMockedAidlHal
        when(mMockedAidlHal2.asBinder()).thenCallRealMethod(); // returns mMockedAidlHal2
        when(mMockedAidlHal.queryLocalInterface(android.hardware.health.IHealth.DESCRIPTOR))
                .thenReturn(mMockedAidlHal);
        when(mMockedAidlHal2.queryLocalInterface(android.hardware.health.IHealth.DESCRIPTOR))
                .thenReturn(mMockedAidlHal2);
    }

    @After
    public void tearDown() {
        validateMockitoUsage();
        if (mWrapper != null) mWrapper.getHandlerThread().quitSafely();
    }

    public static <T> ArgumentMatcher<T> isOneOf(T[] collection) {
        return isOneOf(Arrays.asList(collection));
    }

    public static <T> ArgumentMatcher<T> isOneOf(Collection<T> collection) {
        return new ArgumentMatcher<T>() {
            @Override
            public boolean matches(T e) {
                return collection.contains(e);
            }

            @Override
            public String toString() {
                return "is one of " + collection.toString();
            }
        };
    }

    /**
     * Set up mock objects to pretend that the given AIDL and HIDL instances exists.
     *
     * <p>Also, when registering service notifications, the mocked service managers immediately
     * sends 3 registration notifications, including 2 referring to the original HAL and 1 referring
     * to the new HAL.
     *
     * @param aidlInstances e.g. {"android.hardware.health.IHealth/default"}
     * @param hidlInstances e.g. {"default", "backup"}
     * @throws Exception
     */
    private void initForInstances(String[] aidlInstances, String[] hidlInstances) throws Exception {
        doAnswer(
                (invocation) -> {
                    sendAidlRegCallback(invocation, mMockedAidlHal);
                    sendAidlRegCallback(invocation, mMockedAidlHal);
                    sendAidlRegCallback(invocation, mMockedAidlHal2);
                    return null;
                })
                .when(mMockedAidlManager)
                .registerForNotifications(
                        argThat(isOneOf(aidlInstances)), any(IServiceCallback.class));
        when(mMockedAidlManager.waitForDeclaredService(argThat(isOneOf(aidlInstances))))
                .thenReturn(mMockedAidlHal)
                .thenThrow(new RuntimeException("waitForDeclaredService called more than once"));
        when(mMockedAidlManager.waitForDeclaredService(not(argThat(isOneOf(aidlInstances)))))
                .thenReturn(null);

        doAnswer(
                (invocation) -> {
                    // technically, preexisting is ignored by
                    // HealthServiceWrapperHidl.Notification, but still call it correctly.
                    sendNotification(invocation, true);
                    sendNotification(invocation, true);
                    sendNotification(invocation, false);
                    return null;
                })
                .when(mMockedManager)
                .registerForNotifications(
                        eq(android.hardware.health.V2_0.IHealth.kInterfaceName),
                        argThat(isOneOf(hidlInstances)),
                        any(IServiceNotification.class));

        doReturn(mMockedManager).when(mManagerSupplier).get();
        doReturn(mMockedHal) // init calls this
                .doReturn(mMockedHal) // notification 1
                .doReturn(mMockedHal) // notification 2
                .doReturn(mMockedHal2) // notification 3
                .doThrow(new RuntimeException("Should not call getService for more than 4 times"))
                .when(mHealthServiceSupplier)
                .get(argThat(isOneOf(hidlInstances)));
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
        ((IServiceNotification) invocation.getArguments()[2])
                .onRegistration(
                        android.hardware.health.V2_0.IHealth.kInterfaceName,
                        (String) invocation.getArguments()[1],
                        preexisting);
    }

    private static void sendAidlRegCallback(
            InvocationOnMock invocation, android.hardware.health.IHealth service) throws Exception {
        ((IServiceCallback) invocation.getArguments()[1])
                .onRegistration((String) invocation.getArguments()[0], service.asBinder());
    }

    private void createWrapper() throws RemoteException {
        mWrapper =
                HealthServiceWrapper.create(
                        mRegCallbackAidl,
                        mMockedAidlManager,
                        mCallback,
                        mManagerSupplier,
                        mHealthServiceSupplier);
    }

    @SmallTest
    @Test
    public void testWrapAidlOnly() throws Exception {
        initForInstances(new String[] {AIDL_SERVICE_NAME}, new String[0]);
        createWrapper();
        waitHandlerThreadFinish();
        verify(mRegCallbackAidl, times(1)).onRegistration(same(null), same(mMockedAidlHal));
        verify(mRegCallbackAidl, never())
                .onRegistration(same(mMockedAidlHal), same(mMockedAidlHal));
        verify(mRegCallbackAidl, times(1))
                .onRegistration(same(mMockedAidlHal), same(mMockedAidlHal2));
        verify(mCallback, never()).onRegistration(any(), any(), anyString());
    }

    @SmallTest
    @Test
    public void testWrapPreferAidl() throws Exception {
        initForInstances(new String[] {AIDL_SERVICE_NAME}, new String[] {VENDOR});
        createWrapper();
        waitHandlerThreadFinish();
        verify(mRegCallbackAidl, times(1)).onRegistration(same(null), same(mMockedAidlHal));
        verify(mRegCallbackAidl, never())
                .onRegistration(same(mMockedAidlHal), same(mMockedAidlHal));
        verify(mRegCallbackAidl, times(1))
                .onRegistration(same(mMockedAidlHal), same(mMockedAidlHal2));
        verify(mCallback, never()).onRegistration(any(), any(), anyString());
    }

    @SmallTest
    @Test
    public void testWrapFallbackHidl() throws Exception {
        initForInstances(new String[0], new String[] {VENDOR});
        createWrapper();
        waitHandlerThreadFinish();
        verify(mRegCallbackAidl, never()).onRegistration(any(), any());
        verify(mCallback, times(1)).onRegistration(same(null), same(mMockedHal), eq(VENDOR));
        verify(mCallback, never()).onRegistration(same(mMockedHal), same(mMockedHal), anyString());
        verify(mCallback, times(1)).onRegistration(same(mMockedHal), same(mMockedHal2), eq(VENDOR));
    }

    @SmallTest
    @Test
    public void testNoService() throws Exception {
        initForInstances(new String[0], new String[] {"unrelated"});
        try {
            createWrapper();
            fail("Expect NoSuchElementException");
        } catch (NoSuchElementException ex) {
            // expected
        }
    }
}
