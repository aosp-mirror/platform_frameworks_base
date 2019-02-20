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
package com.android.server.locksettings;

import android.content.Context;
import android.hardware.weaver.V1_0.IWeaver;
import android.os.RemoteException;
import android.os.UserManager;
import android.util.ArrayMap;

import junit.framework.AssertionFailedError;

import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class MockSyntheticPasswordManager extends SyntheticPasswordManager {

    private FakeGateKeeperService mGateKeeper;
    private IWeaver mWeaverService;
    private PasswordSlotManagerTestable mPasswordSlotManager;

    public MockSyntheticPasswordManager(Context context, LockSettingsStorage storage,
            FakeGateKeeperService gatekeeper, UserManager userManager,
            PasswordSlotManager passwordSlotManager) {
        super(context, storage, userManager, passwordSlotManager);
        mGateKeeper = gatekeeper;
    }

    private ArrayMap<String, byte[]> mBlobs = new ArrayMap<>();

    @Override
    protected byte[] decryptSPBlob(String blobKeyName, byte[] blob, byte[] applicationId) {
        if (mBlobs.containsKey(blobKeyName) && !Arrays.equals(mBlobs.get(blobKeyName), blob)) {
            throw new AssertionFailedError("blobKeyName content is overwritten: " + blobKeyName);
        }
        ByteBuffer buffer = ByteBuffer.allocate(blob.length);
        buffer.put(blob, 0, blob.length);
        buffer.flip();
        int len;
        len = buffer.getInt();
        byte[] data = new byte[len];
        buffer.get(data);
        len = buffer.getInt();
        byte[] appId = new byte[len];
        buffer.get(appId);
        long sid = buffer.getLong();
        if (!Arrays.equals(appId, applicationId)) {
            throw new AssertionFailedError("Invalid application id");
        }
        if (sid != 0 && mGateKeeper.getAuthTokenForSid(sid) == null) {
            throw new AssertionFailedError("No valid auth token");
        }
        return data;
    }

    @Override
    protected byte[] createSPBlob(String blobKeyName, byte[] data, byte[] applicationId, long sid) {
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES + data.length + Integer.BYTES
                + applicationId.length + Long.BYTES);
        buffer.putInt(data.length);
        buffer.put(data);
        buffer.putInt(applicationId.length);
        buffer.put(applicationId);
        buffer.putLong(sid);
        byte[] result = buffer.array();
        mBlobs.put(blobKeyName, result);
        return result;
    }

    @Override
    protected void destroySPBlobKey(String keyAlias) {
    }

    @Override
    protected long sidFromPasswordHandle(byte[] handle) {
        return new FakeGateKeeperService.VerifyHandle(handle).sid;
    }

    @Override
    protected byte[] scrypt(String password, byte[] salt, int N, int r, int p, int outLen) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, 10, outLen * 8);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return f.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    protected IWeaver getWeaverService() throws RemoteException {
        return mWeaverService;
    }

    public void enableWeaver() {
        mWeaverService = new MockWeaverService();
        initWeaverService();
    }
}
