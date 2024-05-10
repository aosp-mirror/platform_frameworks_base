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

public class FakeStorageManager {

    private final ArrayMap<Integer, byte[]> mUserSecrets = new ArrayMap<>();

    public void setCeStorageProtection(int userId, byte[] secret) {
        assertThat(mUserSecrets).doesNotContainKey(userId);
        mUserSecrets.put(userId, secret);
    }

    public byte[] getUserUnlockToken(int userId) {
        byte[] secret = mUserSecrets.get(userId);
        assertThat(secret).isNotNull();
        return secret;
    }

    public void unlockCeStorage(int userId, byte[] secret) {
        assertThat(mUserSecrets.get(userId)).isEqualTo(secret);
    }
}
