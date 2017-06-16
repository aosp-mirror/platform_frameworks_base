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

import android.os.IProgressListener;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Pair;


import junit.framework.AssertionFailedError;

import java.util.ArrayList;
import java.util.Arrays;

public class FakeStorageManager {

    private ArrayMap<Integer, ArrayList<Pair<byte[], byte[]>>> mAuth = new ArrayMap<>();
    private boolean mIgnoreBadUnlock;

    public void addUserKeyAuth(int userId, int serialNumber, byte[] token, byte[] secret) {
        getUserAuth(userId).add(new Pair<>(token, secret));
    }

    public void fixateNewestUserKeyAuth(int userId) {
        ArrayList<Pair<byte[], byte[]>> auths = mAuth.get(userId);
        Pair<byte[], byte[]> latest = auths.get(auths.size() - 1);
        auths.clear();
        auths.add(latest);
    }

    private ArrayList<Pair<byte[], byte[]>> getUserAuth(int userId) {
        if (!mAuth.containsKey(userId)) {
            ArrayList<Pair<byte[], byte[]>> auths = new ArrayList<Pair<byte[], byte[]>>();
            auths.add(new Pair(null, null));
            mAuth.put(userId,  auths);
        }
        return mAuth.get(userId);
    }

    public byte[] getUserUnlockToken(int userId) {
        ArrayList<Pair<byte[], byte[]>> auths = getUserAuth(userId);
        if (auths.size() != 1) {
            throw new AssertionFailedError("More than one secret exists");
        }
        return auths.get(0).second;
    }

    public void unlockUser(int userId, byte[] secret, IProgressListener listener)
            throws RemoteException {
        listener.onStarted(userId, null);
        listener.onFinished(userId, null);
        ArrayList<Pair<byte[], byte[]>> auths = getUserAuth(userId);
        if (secret != null) {
            if (auths.size() > 1) {
                throw new AssertionFailedError("More than one secret exists");
            }
            Pair<byte[], byte[]> auth = auths.get(0);
            if ((!mIgnoreBadUnlock) && auth.second != null && !Arrays.equals(secret, auth.second)) {
                throw new AssertionFailedError("Invalid secret to unlock user");
            }
        } else {
            if (auths != null && auths.size() > 0) {
                throw new AssertionFailedError("Cannot unlock encrypted user with empty token");
            }
        }
    }

    public void setIgnoreBadUnlock(boolean ignore) {
        mIgnoreBadUnlock = ignore;
    }
}
