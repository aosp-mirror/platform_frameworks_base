/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore.storage;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class CleanupManagerTest {
    private static final int USER_ID = 10;
    private static final int USER_ID_2 = 20;
    private static final int UID = 1234;
    private static final long USER_SERIAL_NUMBER = 101L;
    private static final long USER_SERIAL_NUMBER_2 = 202L;

    private Context mContext;
    private CleanupManager mManager;

    @Mock private RecoverableKeyStoreDb mDatabase;
    @Mock private RecoverySnapshotStorage mRecoverySnapshotStorage;
    @Mock private UserManager mUserManager;
    @Mock private ApplicationKeyStorage mApplicationKeyStorage;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();
        mManager = new CleanupManager(mContext, mRecoverySnapshotStorage, mDatabase, mUserManager,
                mApplicationKeyStorage);
    }

    @Test
    public void registerRecoveryAgent_unknownUser_storesInDb() throws Exception {
        when(mDatabase.getUserSerialNumbers()).thenReturn(new HashMap<>());
        when(mUserManager.getSerialNumberForUser(eq(UserHandle.of(USER_ID))))
                .thenReturn(USER_SERIAL_NUMBER);
        when(mUserManager.getSerialNumberForUser(eq(UserHandle.of(USER_ID_2))))
                .thenReturn(USER_SERIAL_NUMBER_2);

        mManager.registerRecoveryAgent(USER_ID, UID);
        mManager.registerRecoveryAgent(USER_ID_2, UID);

        verify(mDatabase).setUserSerialNumber(USER_ID, USER_SERIAL_NUMBER);
        verify(mDatabase).setUserSerialNumber(USER_ID_2, USER_SERIAL_NUMBER_2);

    }

    @Test
    public void registerRecoveryAgent_registersSameUser_doesntChangeDb() throws Exception {
        when(mDatabase.getUserSerialNumbers()).thenReturn(new HashMap<>());
        when(mUserManager.getSerialNumberForUser(eq(UserHandle.of(USER_ID))))
                .thenReturn(USER_SERIAL_NUMBER);

        mManager.registerRecoveryAgent(USER_ID, UID);
        mManager.registerRecoveryAgent(USER_ID, UID); // ignored.

        verify(mDatabase, times(1)).setUserSerialNumber(USER_ID, USER_SERIAL_NUMBER);
    }

    @Test
    public void verifyKnownUsers_newSerialNumber_deletesData() throws Exception {
        Map knownSerialNumbers = new HashMap<>();
        knownSerialNumbers.put(USER_ID, USER_SERIAL_NUMBER);
        when(mDatabase.getUserSerialNumbers()).thenReturn(knownSerialNumbers);
        List<Integer> recoveryAgents = new ArrayList<>();
        recoveryAgents.add(UID);
        when(mDatabase.getRecoveryAgents(USER_ID)).thenReturn(recoveryAgents);

        when(mUserManager.getSerialNumberForUser(eq(UserHandle.of(USER_ID))))
                .thenReturn(USER_SERIAL_NUMBER_2); // new value


        mManager.verifyKnownUsers();

        verify(mDatabase).removeUserFromAllTables(USER_ID);
        verify(mDatabase).setUserSerialNumber(USER_ID, USER_SERIAL_NUMBER_2);
        verify(mRecoverySnapshotStorage).remove(UID);
    }
}

