/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.StringParceledListSlice;
import android.net.ConnectivityManager;
import android.security.IKeyChainService;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.systemui.statusbar.policy.SecurityController.SecurityControllerCallback;
import com.android.systemui.SysuiTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class SecurityControllerTest extends SysuiTestCase implements SecurityControllerCallback {
    private final DevicePolicyManager mDevicePolicyManager = mock(DevicePolicyManager.class);
    private final IKeyChainService.Stub mKeyChainService = mock(IKeyChainService.Stub.class);
    private SecurityControllerImpl mSecurityController;
    private CountDownLatch mStateChangedLatch;

    // implementing SecurityControllerCallback
    @Override
    public void onStateChanged() {
        mStateChangedLatch.countDown();
    }

    @Before
    public void setUp() throws Exception {
        mContext.addMockSystemService(Context.DEVICE_POLICY_SERVICE, mDevicePolicyManager);
        mContext.addMockSystemService(Context.CONNECTIVITY_SERVICE, mock(ConnectivityManager.class));

        Intent intent = new Intent(IKeyChainService.class.getName());
        ComponentName comp = intent.resolveSystemService(mContext.getPackageManager(), 0);
        mContext.addMockService(comp, mKeyChainService);

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));
        // Without this line, mKeyChainService gets wrapped in a proxy when Stub.asInterface() is
        // used on it, and the mocking above does not work.
        when(mKeyChainService.queryLocalInterface("android.security.IKeyChainService"))
                .thenReturn(mKeyChainService);

        mSecurityController = new SecurityControllerImpl(mContext);

        // Wait for one or two state changes from the CACertLoader(s) in the constructor of
        // mSecurityController
        mStateChangedLatch = new CountDownLatch(mSecurityController.hasWorkProfile() ? 2 : 1);
        mSecurityController.addCallback(this);
    }

    @After
    public void tearDown() {
        mSecurityController.removeCallback(this);
    }

    @Test
    public void testIsDeviceManaged() {
        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(true);
        assertTrue(mSecurityController.isDeviceManaged());

        when(mDevicePolicyManager.isDeviceManaged()).thenReturn(false);
        assertFalse(mSecurityController.isDeviceManaged());
    }

    @Test
    public void testGetDeviceOwnerOrganizationName() {
        when(mDevicePolicyManager.getDeviceOwnerOrganizationName()).thenReturn("organization");
        assertEquals("organization", mSecurityController.getDeviceOwnerOrganizationName());
    }

    @Test
    @Ignore("Flaky")
    public void testCaCertLoader() throws Exception {
        assertTrue(mStateChangedLatch.await(3, TimeUnit.SECONDS));
        assertFalse(mSecurityController.hasCACertInCurrentUser());

        // With a CA cert

        mStateChangedLatch = new CountDownLatch(1);

        when(mKeyChainService.getUserCaAliases())
                .thenReturn(new StringParceledListSlice(Arrays.asList("One CA Alias")));

        mSecurityController.new CACertLoader()
                           .execute(0);

        assertTrue(mStateChangedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(mSecurityController.hasCACertInCurrentUser());

        // Exception

        mStateChangedLatch = new CountDownLatch(1);

        when(mKeyChainService.getUserCaAliases())
                .thenThrow(new AssertionError("Test AssertionError"))
                .thenReturn(new StringParceledListSlice(new ArrayList<String>()));

        mSecurityController.new CACertLoader()
                           .execute(0);

        assertFalse(mStateChangedLatch.await(3, TimeUnit.SECONDS));
        assertTrue(mSecurityController.hasCACertInCurrentUser());
        // The retry takes 30s
        //assertTrue(mStateChangedLatch.await(31, TimeUnit.SECONDS));
        //assertFalse(mSecurityController.hasCACertInCurrentUser());
    }
}
