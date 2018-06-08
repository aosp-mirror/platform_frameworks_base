/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.Arrays;

import javax.security.auth.Destroyable;

/**
 * Stores pending recovery sessions in memory. We do not write these to disk, as it contains hashes
 * of the user's lock screen.
 *
 * @hide
 */
public class RecoverySessionStorage implements Destroyable {

    private final SparseArray<ArrayList<Entry>> mSessionsByUid = new SparseArray<>();

    /**
     * Returns the session for the given user with the given id.
     *
     * @param uid The uid of the recovery agent who created the session.
     * @param sessionId The unique identifier for the session.
     * @return The session info.
     *
     * @hide
     */
    @Nullable
    public Entry get(int uid, String sessionId) {
        ArrayList<Entry> userEntries = mSessionsByUid.get(uid);
        if (userEntries == null) {
            return null;
        }
        for (Entry entry : userEntries) {
            if (sessionId.equals(entry.mSessionId)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Adds a pending session for the given user.
     *
     * @param uid The uid of the recovery agent who created the session.
     * @param entry The session info.
     *
     * @hide
     */
    public void add(int uid, Entry entry) {
        if (mSessionsByUid.get(uid) == null) {
            mSessionsByUid.put(uid, new ArrayList<>());
        }
        mSessionsByUid.get(uid).add(entry);
    }

    /**
     * Deletes the session with {@code sessionId} created by app with {@code uid}.
     */
    public void remove(int uid, String sessionId) {
        if (mSessionsByUid.get(uid) == null) {
            return;
        }
        mSessionsByUid.get(uid).removeIf(session -> session.mSessionId.equals(sessionId));
    }

    /**
     * Removes all sessions associated with the given recovery agent uid.
     *
     * @param uid The uid of the recovery agent whose sessions to remove.
     *
     * @hide
     */
    public void remove(int uid) {
        ArrayList<Entry> entries = mSessionsByUid.get(uid);
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            entry.destroy();
        }
        mSessionsByUid.remove(uid);
    }

    /**
     * Returns the total count of pending sessions.
     *
     * @hide
     */
    public int size() {
        int size = 0;
        int numberOfUsers = mSessionsByUid.size();
        for (int i = 0; i < numberOfUsers; i++) {
            ArrayList<Entry> entries = mSessionsByUid.valueAt(i);
            size += entries.size();
        }
        return size;
    }

    /**
     * Wipes the memory of any sensitive information (i.e., lock screen hashes and key claimants).
     *
     * @hide
     */
    @Override
    public void destroy() {
        int numberOfUids = mSessionsByUid.size();
        for (int i = 0; i < numberOfUids; i++) {
            ArrayList<Entry> entries = mSessionsByUid.valueAt(i);
            for (Entry entry : entries) {
                entry.destroy();
            }
        }
    }

    /**
     * Information about a recovery session.
     *
     * @hide
     */
    public static class Entry implements Destroyable {
        private final byte[] mLskfHash;
        private final byte[] mKeyClaimant;
        private final byte[] mVaultParams;
        private final String mSessionId;

        /**
         * @hide
         */
        public Entry(String sessionId, byte[] lskfHash, byte[] keyClaimant, byte[] vaultParams) {
            mLskfHash = lskfHash;
            mSessionId = sessionId;
            mKeyClaimant = keyClaimant;
            mVaultParams = vaultParams;
        }

        /**
         * Returns the hash of the lock screen associated with the recovery attempt.
         *
         * @hide
         */
        public byte[] getLskfHash() {
            return mLskfHash;
        }

        /**
         * Returns the key generated for this recovery attempt (used to decrypt data returned by
         * the server).
         *
         * @hide
         */
        public byte[] getKeyClaimant() {
            return mKeyClaimant;
        }

        /**
         * Returns the vault params associated with the session.
         *
         * @hide
         */
        public byte[] getVaultParams() {
            return mVaultParams;
        }

        /**
         * Overwrites the memory for the lskf hash and key claimant.
         *
         * @hide
         */
        @Override
        public void destroy() {
            Arrays.fill(mLskfHash, (byte) 0);
            Arrays.fill(mKeyClaimant, (byte) 0);
        }
    }
}
