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

package com.android.server.locksettings.recoverablekeystore.storage;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemClock;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.security.SecureBox;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class RemoteLockscreenValidationSessionStorageTest {
    private static final int USER_ID = 0;
    private static final int USER_ID_2 = 2;

    RemoteLockscreenValidationSessionStorage mStorage;

    @Before
    public void setUp() {
        mStorage = new RemoteLockscreenValidationSessionStorage();
    }

    @Test
    public void get_noStoredSessions_returnsNull() {
        assertThat(mStorage.get(USER_ID)).isNull();
    }

    @Test
    public void startSession() {
        mStorage.startSession(USER_ID);

        assertThat(mStorage.get(USER_ID)).isNotNull();
        assertThat(mStorage.get(USER_ID_2)).isNull();
    }

    @Test
    public void finishSession_removesSessionFromStorage() {
        mStorage.startSession(USER_ID);

        mStorage.finishSession(USER_ID);

        assertThat(mStorage.get(USER_ID)).isNull();
    }

    @Test
    public void getLockscreenValidationCleanupTask() throws Exception {
        long time11MinutesAgo = SystemClock.elapsedRealtime() - 11 * 60 * 1000;
        long time2MinutesAgo = SystemClock.elapsedRealtime() - 2 * 60 * 1000;
        mStorage.mSessionsByUserId.put(
                USER_ID, mStorage.new LockscreenVerificationSession(
                SecureBox.genKeyPair(), time11MinutesAgo));
        mStorage.mSessionsByUserId.put(
                USER_ID_2, mStorage.new LockscreenVerificationSession(
                SecureBox.genKeyPair(), time2MinutesAgo));

        mStorage.getLockscreenValidationCleanupTask().run();

        assertThat(mStorage.get(USER_ID)).isNull();
        assertThat(mStorage.get(USER_ID_2)).isNotNull();
    }
}
