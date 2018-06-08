/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.locksettings.recoverablekeystore;

import static org.junit.Assert.assertEquals;

import android.security.Scrypt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MockScrypt extends Scrypt {

    @Override
    public byte[] scrypt(byte[] password, byte[] salt, int n, int r, int p, int outLen) {
        assertEquals(32, outLen);

        ByteBuffer byteBuffer = ByteBuffer.allocate(
                password.length + salt.length + Integer.BYTES * 6);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        byteBuffer.putInt(password.length);
        byteBuffer.put(password);
        byteBuffer.putInt(salt.length);
        byteBuffer.put(salt);
        byteBuffer.putInt(n);
        byteBuffer.putInt(r);
        byteBuffer.putInt(p);
        byteBuffer.putInt(outLen);

        try {
            return MessageDigest.getInstance("SHA-256").digest(byteBuffer.array());
        } catch (NoSuchAlgorithmException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }
}
