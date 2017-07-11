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

import android.os.IBinder;
import android.os.RemoteException;
import android.service.gatekeeper.GateKeeperResponse;
import android.service.gatekeeper.IGateKeeperService;
import android.util.ArrayMap;

import junit.framework.AssertionFailedError;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class FakeGateKeeperService implements IGateKeeperService {
    static class VerifyHandle {
        public byte[] password;
        public long sid;

        public VerifyHandle(byte[] password, long sid) {
            this.password = password;
            this.sid = sid;
        }

        public VerifyHandle(byte[] handle) {
            ByteBuffer buffer = ByteBuffer.allocate(handle.length);
            buffer.put(handle, 0, handle.length);
            buffer.flip();
            int version = buffer.get();
            sid = buffer.getLong();
            password = new byte[buffer.remaining()];
            buffer.get(password);
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + password.length);
            buffer.put((byte)0);
            buffer.putLong(sid);
            buffer.put(password);
            return buffer.array();
        }
    }

    static class AuthToken {
        public long challenge;
        public long sid;

        public AuthToken(long challenge, long sid) {
            this.challenge = challenge;
            this.sid = sid;
        }

        public AuthToken(byte[] handle) {
            ByteBuffer buffer = ByteBuffer.allocate(handle.length);
            buffer.put(handle, 0, handle.length);
            buffer.flip();
            int version = buffer.get();
            challenge = buffer.getLong();
            sid = buffer.getLong();
        }

        public byte[] toBytes() {
            ByteBuffer buffer = ByteBuffer.allocate(1 + Long.BYTES + Long.BYTES);
            buffer.put((byte)0);
            buffer.putLong(challenge);
            buffer.putLong(sid);
            return buffer.array();
        }
    }

    private ArrayMap<Integer, Long> sidMap = new ArrayMap<>();
    private ArrayMap<Integer, AuthToken> authTokenMap = new ArrayMap<>();

    private ArrayMap<Integer, byte[]> handleMap = new ArrayMap<>();

    @Override
    public GateKeeperResponse enroll(int uid, byte[] currentPasswordHandle, byte[] currentPassword,
            byte[] desiredPassword) throws android.os.RemoteException {
        if (currentPasswordHandle != null) {
            VerifyHandle handle = new VerifyHandle(currentPasswordHandle);
            if (Arrays.equals(currentPassword, handle.password)) {
                // Trusted enroll
                VerifyHandle newHandle = new VerifyHandle(desiredPassword, handle.sid);
                refreshSid(uid, handle.sid, false);
                handleMap.put(uid, newHandle.toBytes());
                return GateKeeperResponse.createOkResponse(newHandle.toBytes(), false);
            } else if (currentPassword != null) {
                // current password is provided but does not match handle, this is an error case.
                return null;
            }
            // Fall through: password handle is provided, but no password
        }
        // Untrusted/new enrollment: generate a new SID
        long newSid = new Random().nextLong();
        VerifyHandle newHandle = new VerifyHandle(desiredPassword, newSid);
        refreshSid(uid, newSid, true);
        handleMap.put(uid, newHandle.toBytes());
        return GateKeeperResponse.createOkResponse(newHandle.toBytes(), false);
    }

    @Override
    public GateKeeperResponse verify(int uid, byte[] enrolledPasswordHandle,
            byte[] providedPassword) throws android.os.RemoteException {
        return verifyChallenge(uid, 0, enrolledPasswordHandle, providedPassword);
    }

    @Override
    public GateKeeperResponse verifyChallenge(int uid, long challenge,
            byte[] enrolledPasswordHandle, byte[] providedPassword) throws RemoteException {

        VerifyHandle handle = new VerifyHandle(enrolledPasswordHandle);
        if (Arrays.equals(handle.password, providedPassword)) {
            byte[] knownHandle = handleMap.get(uid);
            if (knownHandle != null) {
                if (!Arrays.equals(knownHandle, enrolledPasswordHandle)) {
                    throw new AssertionFailedError("Got correct but obsolete handle");
                }
            }
            refreshSid(uid, handle.sid, false);
            AuthToken token = new AuthToken(challenge, handle.sid);
            refreshAuthToken(uid, token);
            return GateKeeperResponse.createOkResponse(token.toBytes(), false);
        } else {
            return GateKeeperResponse.createGenericResponse(GateKeeperResponse.RESPONSE_ERROR);
        }
    }

    private void refreshAuthToken(int uid, AuthToken token) {
        authTokenMap.put(uid, token);
    }

    public AuthToken getAuthToken(int uid) {
        return authTokenMap.get(uid);
    }

    public AuthToken getAuthTokenForSid(long sid) {
        for(AuthToken token : authTokenMap.values()) {
            if (token.sid == sid) {
                return token;
            }
        }
        return null;
    }

    public void clearAuthToken(int uid) {
        authTokenMap.remove(uid);
    }

    @Override
    public IBinder asBinder() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearSecureUserId(int userId) throws RemoteException {
        sidMap.remove(userId);
    }

    @Override
    public void reportDeviceSetupComplete() throws RemoteException {
    }

    @Override
    public long getSecureUserId(int userId) throws RemoteException {
        if (sidMap.containsKey(userId)) {
            return sidMap.get(userId);
        } else {
            return 0L;
        }
    }

    private void refreshSid(int uid, long sid, boolean force) {
        if (!sidMap.containsKey(uid) || force) {
            sidMap.put(uid, sid);
        } else{
            if (sidMap.get(uid) != sid) {
                throw new AssertionFailedError("Inconsistent SID");
            }
        }
    }

}
