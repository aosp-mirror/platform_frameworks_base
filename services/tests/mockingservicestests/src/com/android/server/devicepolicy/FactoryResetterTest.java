/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.devicepolicy;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RecoverySystem;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.platform.test.annotations.Presubmit;
import android.service.persistentdata.PersistentDataBlockManager;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

/**
 * Run it as {@code atest FrameworksMockingCoreTests:FactoryResetterTest}
 */
@Presubmit
public final class FactoryResetterTest {

    private static final String TAG = FactoryResetterTest.class.getSimpleName();

    // Fixed parameters
    private static final String REASON = "self-destruct";
    private static final boolean SHUTDOWN = true;
    private static final boolean WIPE_EUICC = true;

    // Parameters under test
    private static final boolean FORCE = true;
    private static final boolean NO_FORCE = false;
    private static final boolean WIPE_ADOPTABLE_STORAGE = true;
    private static final boolean NO_WIPE_ADOPTABLE_STORAGE = false;
    private static final boolean WIPE_FACTORY_RESET_PROTECTION = true;
    private static final boolean NO_WIPE_FACTORY_RESET_PROTECTION = false;

    private MockitoSession mSession;

    private @Mock Context mContext;
    private @Mock StorageManager mSm;
    private @Mock PersistentDataBlockManager mPdbm;
    private @Mock UserManager mUm;

    @Before
    public void startSession() {
        mSession = mockitoSession()
                .initMocks(this)
                .spyStatic(RecoverySystem.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        when(mContext.getSystemService(any(String.class))).thenAnswer((inv) -> {
            Log.d(TAG, "Mocking " + inv);
            String service = (String) inv.getArguments()[0];
            switch (service) {
                case Context.PERSISTENT_DATA_BLOCK_SERVICE:
                    return mPdbm;
                case Context.STORAGE_SERVICE:
                    return mSm;
                case Context.USER_SERVICE:
                    return mUm;
                default:
                    throw new IllegalArgumentException("Not expecting call for " + service);
            }
        });

        doAnswer((inv) -> {
            Log.d(TAG, "Mocking " + inv);
            return null;
        }).when(() -> RecoverySystem.rebootWipeUserData(any(), anyBoolean(), any(),
                anyBoolean(), anyBoolean()));
    }

    @After
    public void finishSession() {
        if (mSession == null) {
            Log.w(TAG, "finishSession(): no session");
            return;
        }
        mSession.finishMocking();
    }

    @Test
    public void testFactoryReset_noMasterClearPermission() throws Exception {
        revokeMasterClearPermission();
        setFactoryResetRestriction(/* allowed= */ true);

        assertThrows(SecurityException.class,
                () -> FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, NO_FORCE,
                        WIPE_EUICC, WIPE_ADOPTABLE_STORAGE, WIPE_FACTORY_RESET_PROTECTION));

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataNotCalled();
    }

    @Test
    public void testFactoryReset_noForceDisallowed()
            throws Exception {
        setFactoryResetRestriction(/* allowed= */ false);

        assertThrows(SecurityException.class,
                () -> FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, NO_FORCE,
                        WIPE_EUICC, WIPE_ADOPTABLE_STORAGE, WIPE_FACTORY_RESET_PROTECTION));

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataNotCalled();
    }

    @Test
    public void testFactoryReset_noForceAllowed() throws Exception {
        setFactoryResetRestriction(/* allowed= */ true);

        FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, NO_FORCE,
                WIPE_EUICC, WIPE_ADOPTABLE_STORAGE, WIPE_FACTORY_RESET_PROTECTION);

        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataCalled(NO_FORCE);
    }

    @Test
    public void testFactoryReset_forceDisallowed() throws Exception {
        setFactoryResetRestriction(/* allowed= */ false);

        FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, FORCE,
                WIPE_EUICC, WIPE_ADOPTABLE_STORAGE, WIPE_FACTORY_RESET_PROTECTION);

        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataCalled(FORCE);
    }

    @Test
    public void testFactoryReset_bothFalse() throws Exception {
        FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, FORCE,
                WIPE_EUICC, NO_WIPE_ADOPTABLE_STORAGE, NO_WIPE_FACTORY_RESET_PROTECTION);

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataCalled(FORCE);
    }

    @Test
    public void testFactoryReset_storageOnly() throws Exception {
        FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, FORCE,
                WIPE_EUICC, WIPE_ADOPTABLE_STORAGE, NO_WIPE_FACTORY_RESET_PROTECTION);

        verifyWipeAdoptableStorageCalled();
        verifyWipeFactoryResetProtectionNotCalled();
        verifyRebootWipeUserDataCalled(FORCE);
    }

    @Test
    public void testFactoryReset_frpOnly() throws Exception {
        FactoryResetter.factoryReset(mContext, SHUTDOWN, REASON, FORCE,
                WIPE_EUICC, NO_WIPE_ADOPTABLE_STORAGE, WIPE_FACTORY_RESET_PROTECTION);

        verifyWipeAdoptableStorageNotCalled();
        verifyWipeFactoryResetProtectionCalled();
        verifyRebootWipeUserDataCalled(FORCE);
    }

    private void revokeMasterClearPermission() {
        when(mContext.checkCallingOrSelfPermission(android.Manifest.permission.MASTER_CLEAR))
                .thenReturn(PackageManager.PERMISSION_DENIED);
    }

    private void setFactoryResetRestriction(boolean allowed) {
        when(mUm.hasUserRestriction(UserManager.DISALLOW_FACTORY_RESET)).thenReturn(!allowed);
    }

    private void verifyRebootWipeUserDataNotCalled() {
        verify(() -> RecoverySystem.rebootWipeUserData(any(), anyBoolean(), any(), anyBoolean(),
                anyBoolean()), never());
    }

    private void verifyRebootWipeUserDataCalled(boolean force) {
        verify(() -> RecoverySystem.rebootWipeUserData(mContext, SHUTDOWN, REASON, force,
                WIPE_EUICC));
    }

    private void verifyWipeAdoptableStorageNotCalled() {
        verify(mSm, never()).wipeAdoptableDisks();
    }

    private void verifyWipeAdoptableStorageCalled() {
        verify(mSm).wipeAdoptableDisks();
    }

    private void verifyWipeFactoryResetProtectionNotCalled() {
        verify(mPdbm, never()).wipe();
    }

    private void verifyWipeFactoryResetProtectionCalled() {
        verify(mPdbm).wipe();
    }
}
