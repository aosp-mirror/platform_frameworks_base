/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.security.advancedprotection;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.RemoteException;
import android.os.test.FakePermissionEnforcer;
import android.os.test.TestLooper;
import android.provider.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AdvancedProtectionServiceTest {
    private AdvancedProtectionService mService;
    private FakePermissionEnforcer mPermissionEnforcer;
    private Context mContext;

    @Before
    @SuppressLint("VisibleForTests")
    public void setup() throws Settings.SettingNotFoundException {
        mContext = mock(Context.class);
        mPermissionEnforcer = new FakePermissionEnforcer();
        mPermissionEnforcer.grant(Manifest.permission.SET_ADVANCED_PROTECTION_MODE);
        mPermissionEnforcer.grant(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);

        AdvancedProtectionService.AdvancedProtectionStore store =
                new AdvancedProtectionService.AdvancedProtectionStore(mContext) {
                    private boolean mEnabled = false;

                    @Override
                    boolean retrieve() {
                        return mEnabled;
                    }

                    @Override
                    void store(boolean enabled) {
                        this.mEnabled = enabled;
                    }
                };

        mService = new AdvancedProtectionService(mContext, store, new TestLooper().getLooper(),
                mPermissionEnforcer);
    }

    @Test
    public void testEnableProtection() throws RemoteException {
        mService.setAdvancedProtectionEnabled(true);
        assertTrue(mService.isAdvancedProtectionEnabled());
    }

    @Test
    public void testDisableProtection() throws RemoteException {
        mService.setAdvancedProtectionEnabled(false);
        assertFalse(mService.isAdvancedProtectionEnabled());
    }

    @Test
    public void testSetProtection_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.SET_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.setAdvancedProtectionEnabled(true));
    }

    @Test
    public void testGetProtection_withoutPermission() {
        mPermissionEnforcer.revoke(Manifest.permission.QUERY_ADVANCED_PROTECTION_MODE);
        assertThrows(SecurityException.class, () -> mService.isAdvancedProtectionEnabled());
    }
}
