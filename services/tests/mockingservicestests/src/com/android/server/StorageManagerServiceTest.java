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

package com.android.server;


import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.multiuser.Flags;
import android.os.UserManager;
import android.os.storage.ICeStorageLockEventListener;
import android.os.storage.StorageManagerInternal;
import android.platform.test.flag.junit.SetFlagsRule;

import com.android.modules.utils.testing.ExtendedMockitoRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.CopyOnWriteArrayList;

public class StorageManagerServiceTest {

    private final Context mRealContext = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().getTargetContext();
    private StorageManagerService mStorageManagerService;
    private StorageManagerInternal mStorageManagerInternal;

    private static final int TEST_USER_ID = 1001;

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule = new ExtendedMockitoRule.Builder(this)
            .spyStatic(UserManager.class)
            .build();
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static class TestStorageEventListener implements ICeStorageLockEventListener {

        private int mExpectedUserId;

        private TestStorageEventListener(int userId) {
            mExpectedUserId = userId;
        }

        @Override
        public void onStorageLocked(int userId) {
            assertThat(userId).isEqualTo(mExpectedUserId);
        }
    }


    @Before
    public void setFixtures() {
        // Called when WatchedUserStates is constructed
        doNothing().when(() -> UserManager.invalidateIsUserUnlockedCache());

        mStorageManagerService = new StorageManagerService(mRealContext);
        mStorageManagerInternal = LocalServices.getService(StorageManagerInternal.class);
        assertWithMessage("LocalServices.getService(StorageManagerInternal.class)")
                .that(mStorageManagerInternal).isNotNull();
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(StorageManagerInternal.class);
    }

    @Test
    public void testRegisterLockEventListener() {
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE,
                Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);
        mSetFlagsRule.enableFlags(Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE);
        CopyOnWriteArrayList<ICeStorageLockEventListener> storageLockEventListeners =
                mStorageManagerService.getCeStorageEventCallbacks();
        assertThat(storageLockEventListeners).isNotNull();
        int registeredCallbackCount = storageLockEventListeners.size();
        TestStorageEventListener testStorageEventListener =
                new TestStorageEventListener(TEST_USER_ID);
        mStorageManagerInternal.registerStorageLockEventListener(testStorageEventListener);
        assertNumberOfStorageCallbackReceivers(registeredCallbackCount + 1);

        mStorageManagerInternal.unregisterStorageLockEventListener(testStorageEventListener);
        assertNumberOfStorageCallbackReceivers(registeredCallbackCount);
    }

    @Test
    public void testDispatchCeStorageLockEvent() {
        mSetFlagsRule.enableFlags(android.os.Flags.FLAG_ALLOW_PRIVATE_PROFILE,
                Flags.FLAG_ENABLE_BIOMETRICS_TO_UNLOCK_PRIVATE_SPACE,
                Flags.FLAG_ENABLE_PRIVATE_SPACE_FEATURES);

        assertThat(mStorageManagerService.getCeStorageEventCallbacks()).isNotNull();
        int callbackReceiverSize = mStorageManagerService.getCeStorageEventCallbacks().size();
        TestStorageEventListener testStorageEventListener =
                spy(new TestStorageEventListener(TEST_USER_ID));

        // Add testStorageEventListener to the list of storage callback listeners
        mStorageManagerService.getCeStorageEventCallbacks().add(testStorageEventListener);
        assertNumberOfStorageCallbackReceivers(callbackReceiverSize + 1);

        mStorageManagerService.dispatchCeStorageLockedEvent(TEST_USER_ID);
        verify(testStorageEventListener).onStorageLocked(eq(TEST_USER_ID));

        // Remove testStorageEventListener from the list of storage callback listeners
        mStorageManagerService.getCeStorageEventCallbacks().remove(testStorageEventListener);
        assertNumberOfStorageCallbackReceivers(callbackReceiverSize);
    }

    private void assertNumberOfStorageCallbackReceivers(int callbackReceiverSize) {
        assertThat(mStorageManagerService.getCeStorageEventCallbacks()).isNotNull();
        assertThat(mStorageManagerService.getCeStorageEventCallbacks().size())
                    .isEqualTo(callbackReceiverSize);
    }
}
