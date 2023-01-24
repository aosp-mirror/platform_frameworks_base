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
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.locksettings.recoverablekeystore.SecureBox;

import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;

/**
 * Memory based storage for keyPair used to send encrypted credentials from a remote device.
 *
 * @hide
 */
public class RemoteLockscreenValidationSessionStorage {

    private static final long SESSION_TIMEOUT_MILLIS = 10L * DateUtils.MINUTE_IN_MILLIS;
    private static final String TAG = "RemoteLockscreenValidation";

    @VisibleForTesting
    final SparseArray<LockscreenVerificationSession> mSessionsByUserId =
            new SparseArray<>(0);

    /**
     * Returns session for given user or null.
     *
     * @param userId The user id
     * @return The session info.
     *
     * @hide
     */
    @Nullable
    public LockscreenVerificationSession get(int userId) {
        synchronized (mSessionsByUserId) {
            return mSessionsByUserId.get(userId);
        }
    }

    /**
     * Creates a new session to verify credentials guess.
     *
     * Session will be automatically removed after 10 minutes of inactivity.
     * @param userId The user id
     *
     * @hide
     */
    public LockscreenVerificationSession startSession(int userId) {
        synchronized (mSessionsByUserId) {
            if (mSessionsByUserId.get(userId) != null) {
                mSessionsByUserId.delete(userId);
            }

            KeyPair newKeyPair;
            try {
                newKeyPair = SecureBox.genKeyPair();
            } catch (NoSuchAlgorithmException e) {
                // impossible
                throw new RuntimeException(e);
            }
            LockscreenVerificationSession newSession =
                    new LockscreenVerificationSession(newKeyPair, SystemClock.elapsedRealtime());
            mSessionsByUserId.put(userId, newSession);
            return newSession;
        }
    }

    /**
     * Deletes session for a user.
     */
    public void finishSession(int userId) {
        synchronized (mSessionsByUserId) {
            mSessionsByUserId.delete(userId);
        }
    }

    /**
     * Creates a task which deletes expired sessions.
     */
    public Runnable getLockscreenValidationCleanupTask() {
        return new LockscreenValidationCleanupTask();
    }

    /**
     * Holder for KeyPair used by remote lock screen validation.
     *
     * @hide
     */
    public class LockscreenVerificationSession {
        private final KeyPair mKeyPair;
        private final long mElapsedStartTime;

        /**
         * @hide
         */
        LockscreenVerificationSession(KeyPair keyPair, long elapsedStartTime) {
            mKeyPair = keyPair;
            mElapsedStartTime = elapsedStartTime;
        }

        /**
         * Returns SecureBox key pair.
         */
        public KeyPair getKeyPair() {
            return mKeyPair;
        }

        /**
         * Time when the session started.
         */
        private long getElapsedStartTimeMillis() {
            return mElapsedStartTime;
        }
    }

    private class LockscreenValidationCleanupTask implements Runnable {
        @Override
        public void run() {
            try {
                synchronized (mSessionsByUserId) {
                    ArrayList<Integer> keysToRemove = new ArrayList<>();
                    for (int i = 0; i < mSessionsByUserId.size(); i++) {
                        long now = SystemClock.elapsedRealtime();
                        long startTime = mSessionsByUserId.valueAt(i).getElapsedStartTimeMillis();
                        if (now - startTime > SESSION_TIMEOUT_MILLIS) {
                            int userId = mSessionsByUserId.keyAt(i);
                            keysToRemove.add(userId);
                        }
                    }
                    for (Integer userId : keysToRemove) {
                        mSessionsByUserId.delete(userId);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected exception thrown during LockscreenValidationCleanupTask", e);
            }
        }

    }

}
