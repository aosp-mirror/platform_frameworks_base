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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static junit.framework.Assert.fail;

import android.Manifest;
import android.content.Context;
import android.os.Binder;
import android.os.CombinedVibration;
import android.os.IVibratorManagerService;
import android.os.IVibratorStateListener;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.platform.test.annotations.Presubmit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;


/**
 * Verify that Hardware apis cannot be called without required permissions.
 */
@Presubmit
@RunWith(JUnit4.class)
public class VibratorManagerServicePermissionTest {

    private static final String PACKAGE_NAME = "com.android.framework.permission.tests";
    private static final int DISPLAY_ID = 1;
    private static final CombinedVibration EFFECT =
            CombinedVibration.createParallel(
                    VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
    private static final VibrationAttributes ATTRS = new VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_ALARM)
            .build();

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    private IVibratorManagerService mVibratorService;

    @Before
    public void setUp() throws Exception {
        mVibratorService = IVibratorManagerService.Stub.asInterface(
                ServiceManager.getService(Context.VIBRATOR_MANAGER_SERVICE));
    }

    @After
    public void cleanUp() {
        getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }

    @Test
    public void testIsVibratingFails() throws RemoteException {
        expectSecurityException("ACCESS_VIBRATOR_STATE");
        mVibratorService.isVibrating(1);
    }

    @Test
    public void testRegisterVibratorStateListenerFails() throws RemoteException {
        expectSecurityException("ACCESS_VIBRATOR_STATE");
        IVibratorStateListener listener = new IVibratorStateListener.Stub() {
            @Override
            public void onVibrating(boolean vibrating) {
                fail("Listener callback was not expected.");
            }
        };
        mVibratorService.registerVibratorStateListener(1, listener);
    }

    @Test
    public void testUnregisterVibratorStateListenerFails() throws RemoteException {
        expectSecurityException("ACCESS_VIBRATOR_STATE");
        mVibratorService.unregisterVibratorStateListener(1, null);
    }

    @Test
    public void testSetAlwaysOnEffectFails() throws RemoteException {
        expectSecurityException("VIBRATE_ALWAYS_ON");
        mVibratorService.setAlwaysOnEffect(Process.myUid(), PACKAGE_NAME, 1, EFFECT, ATTRS);
    }

    @Test
    public void testVibrateWithoutPermissionFails() throws RemoteException {
        expectSecurityException("VIBRATE");
        mVibratorService.vibrate(Process.myUid(), DISPLAY_ID, PACKAGE_NAME, EFFECT, ATTRS,
                "testVibrate",
                new Binder());
    }

    @Test
    public void testVibrateWithVibratePermissionAndSameProcessUidIsAllowed()
            throws RemoteException {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.VIBRATE);
        mVibratorService.vibrate(Process.myUid(), DISPLAY_ID, PACKAGE_NAME, EFFECT, ATTRS,
                "testVibrate",
                new Binder());
    }

    @Test
    public void testVibrateWithVibratePermissionAndDifferentUidsFails() throws RemoteException {
        expectSecurityException("UPDATE_APP_OPS_STATS");
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.VIBRATE);
        mVibratorService.vibrate(Process.SYSTEM_UID, DISPLAY_ID, "android", EFFECT, ATTRS,
                "testVibrate",
                new Binder());
    }

    @Test
    public void testVibrateWithAllPermissionsAndDifferentUidsIsAllowed() throws RemoteException {
        getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                Manifest.permission.VIBRATE,
                Manifest.permission.UPDATE_APP_OPS_STATS);
        mVibratorService.vibrate(Process.SYSTEM_UID, DISPLAY_ID, "android", EFFECT, ATTRS,
                "testVibrate",
                new Binder());
    }

    @Test
    public void testCancelVibrateFails() throws RemoteException {
        expectSecurityException("VIBRATE");
        mVibratorService.cancelVibrate(/* usageFilter= */ -1, new Binder());
    }

    private void expectSecurityException(String expectedPermission) {
        exceptionRule.expect(SecurityException.class);
        exceptionRule.expectMessage("permission." + expectedPermission);
    }
}
