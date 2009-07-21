/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.framework.permission.tests;

import junit.framework.TestCase;

import android.os.Binder;
import android.os.IHardwareService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Verify that Hardware apis cannot be called without required permissions.
 */
@SmallTest
public class HardwareServicePermissionTest extends TestCase {

    private IHardwareService mHardwareService;

    @Override
    protected void setUp() throws Exception {
        mHardwareService = IHardwareService.Stub.asInterface(
                ServiceManager.getService("hardware"));
    }

    /**
     * Test that calling {@link android.os.IHardwareService#vibrate(long)} requires permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#VIBRATE}
     * @throws RemoteException
     */
    public void testVibrate() throws RemoteException {
        try {
            mHardwareService.vibrate(2000);
            fail("vibrate did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IHardwareService#vibratePattern(long[],
     * int, android.os.IBinder)} requires permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#VIBRATE}
     * @throws RemoteException
     */
    public void testVibratePattern() throws RemoteException {
        try {
            mHardwareService.vibratePattern(new long[] {0}, 0, new Binder());
            fail("vibratePattern did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IHardwareService#cancelVibrate()} requires permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#VIBRATE}
     * @throws RemoteException
     */
    public void testCancelVibrate() throws RemoteException {
        try {
            mHardwareService.cancelVibrate();
            fail("cancelVibrate did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IHardwareService#setFlashlightEnabled(boolean)}
     * requires permissions.
     * <p>Tests permissions:
     *   {@link android.Manifest.permission#HARDWARE_TEST}
     *   {@link android.Manifest.permission#FLASHLIGHT}
     * @throws RemoteException
     */
    public void testSetFlashlightEnabled() throws RemoteException {
        try {
            mHardwareService.setFlashlightEnabled(true);
            fail("setFlashlightEnabled did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IHardwareService#enableCameraFlash(int)} requires
     * permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#HARDWARE_TEST}
     *   {@link android.Manifest.permission#CAMERA}
     * @throws RemoteException
     */
    public void testEnableCameraFlash() throws RemoteException {
        try {
            mHardwareService.enableCameraFlash(100);
            fail("enableCameraFlash did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }

    /**
     * Test that calling {@link android.os.IHardwareService#setBacklights(int)} requires
     * permissions.
     * <p>Tests permission:
     *   {@link android.Manifest.permission#HARDWARE_TEST}
     * @throws RemoteException
     */
    public void testSetBacklights() throws RemoteException {
        try {
            mHardwareService.setBacklights(0);
            fail("setBacklights did not throw SecurityException as expected");
        } catch (SecurityException e) {
            // expected
        }
    }
}
