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
 * limitations under the License
 */

package com.android.server.locksettings;

import static com.google.common.truth.Truth.assertThat;

import android.util.ArrayMap;

import java.util.ArrayList;

public class FakeStorageManager {

    private final ArrayMap<Integer, ArrayList<byte[]>> mAuth = new ArrayMap<>();

    public void addUserKeyAuth(int userId, int serialNumber, byte[] secret) {
        getUserAuth(userId).add(secret);
    }

    public void clearUserKeyAuth(int userId, int serialNumber, byte[] secret) {
        ArrayList<byte[]> auths = getUserAuth(userId);
        if (secret == null) {
            return;
        }
        auths.remove(secret);
        auths.add(null);
    }

    public void fixateNewestUserKeyAuth(int userId) {
        ArrayList<byte[]> auths = mAuth.get(userId);
        byte[] latest = auths.get(auths.size() - 1);
        auths.clear();
        auths.add(latest);
    }

    private ArrayList<byte[]> getUserAuth(int userId) {
        if (!mAuth.containsKey(userId)) {
            ArrayList<byte[]> auths = new ArrayList<>();
            auths.add(null);
            mAuth.put(userId, auths);
        }
        return mAuth.get(userId);
    }

    public byte[] getUserUnlockToken(int userId) {
        ArrayList<byte[]> auths = getUserAuth(userId);
        assertThat(auths).hasSize(1);
        return auths.get(0);
    }

    public void unlockUserKey(int userId, byte[] secret) {
        ArrayList<byte[]> auths = getUserAuth(userId);
        assertThat(auths).hasSize(1);
        byte[] auth = auths.get(0);
        assertThat(auth).isEqualTo(secret);
    }
}
