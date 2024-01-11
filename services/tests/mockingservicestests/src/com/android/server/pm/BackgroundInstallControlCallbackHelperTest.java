/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.pm;

import static com.android.server.pm.BackgroundInstallControlCallbackHelper.FLAGGED_PACKAGE_NAME_KEY;
import static com.android.server.pm.BackgroundInstallControlCallbackHelper.FLAGGED_USER_ID_KEY;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;

/** Unit tests for {@link BackgroundInstallControlCallbackHelperTest} */
@Presubmit
@RunWith(JUnit4.class)
public class BackgroundInstallControlCallbackHelperTest {

    private final IRemoteCallback mCallback =
            spy(
                    new IRemoteCallback.Stub() {
                        @Override
                        public void sendResult(Bundle extras) {}
                    });

    private BackgroundInstallControlCallbackHelper mCallbackHelper;

    @Before
    public void setup() {
        mCallbackHelper = new BackgroundInstallControlCallbackHelper();
    }

    @Test
    public void registerBackgroundInstallControlCallback_registers_successfully() {
        mCallbackHelper.registerBackgroundInstallCallback(mCallback);

        synchronized (mCallbackHelper.mCallbacks) {
            assertEquals(1, mCallbackHelper.mCallbacks.getRegisteredCallbackCount());
            assertEquals(mCallback, mCallbackHelper.mCallbacks.getRegisteredCallbackItem(0));
        }
    }

    @Test
    public void unregisterBackgroundInstallControlCallback_unregisters_successfully() {
        synchronized (mCallbackHelper.mCallbacks) {
            mCallbackHelper.mCallbacks.register(mCallback);
        }

        mCallbackHelper.unregisterBackgroundInstallCallback(mCallback);

        synchronized (mCallbackHelper.mCallbacks) {
            assertEquals(0, mCallbackHelper.mCallbacks.getRegisteredCallbackCount());
        }
    }

    @Test
    public void notifyAllCallbacks_broadcastsToCallbacks()
            throws RemoteException {
        String testPackageName = "testname";
        int testUserId = 1;
        mCallbackHelper.registerBackgroundInstallCallback(mCallback);

        mCallbackHelper.notifyAllCallbacks(testUserId, testPackageName);

        ArgumentCaptor<Bundle> bundleCaptor = ArgumentCaptor.forClass(Bundle.class);
        verify(mCallback, after(1000).times(1)).sendResult(bundleCaptor.capture());
        Bundle receivedBundle = bundleCaptor.getValue();
        assertEquals(testPackageName, receivedBundle.getString(FLAGGED_PACKAGE_NAME_KEY));
        assertEquals(testUserId, receivedBundle.getInt(FLAGGED_USER_ID_KEY));
    }
}
