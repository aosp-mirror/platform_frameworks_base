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

import android.annotation.Nullable;
import android.util.SparseArray;

import java.security.KeyPair;

/**
 * Memory based storage for keyPair used to send encrypted credentials from a remote device.
 *
 * @hide
 */
public class RemoteLockscreenValidationSessionStorage {

    private final SparseArray<LockScreenVerificationSession> mSessionsByUserId =
            new SparseArray<>();

    /**
     * Returns session for given user or null.
     *
     * @param userId The user id
     * @return The session info.
     *
     * @hide
     */
    @Nullable
    public LockScreenVerificationSession get(int userId) {
        return mSessionsByUserId.get(userId);
    }

    /**
     * Creates a new session to verify lockscreen credentials guess.
     *
     * Session will be automatically removed after 10 minutes of inactivity.
     * @param userId The user id
     *
     * @hide
     */
    public LockScreenVerificationSession startSession(int userId) {
        if (mSessionsByUserId.get(userId) != null) {
            mSessionsByUserId.remove(userId);
        }
        LockScreenVerificationSession newSession = null;
        // TODO(b/254335492): Schedule a task to remove session.
        mSessionsByUserId.put(userId, newSession);
        return newSession;
    }

    /**
     * Deletes session for a user.
     */
    public void remove(int userId) {
        mSessionsByUserId.remove(userId);
    }

    /**
     * Holder for keypair used by remote lock screen validation.
     *
     * @hide
     */
    public static class LockScreenVerificationSession {
        private final KeyPair mKeyPair;
        private final long mSessionStartTimeMillis;

        /**
         * @hide
         */
        public LockScreenVerificationSession(KeyPair keyPair, long sessionStartTimeMillis) {
            mKeyPair = keyPair;
            mSessionStartTimeMillis = sessionStartTimeMillis;
        }
    }
}
