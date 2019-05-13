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

package com.android.server.locksettings;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Implementation of NIST SP800-108
 * "Recommendation for Key Derivation Using Pseudorandom Functions"
 * Hardcoded:
 * [PRF=HMAC_SHA256]
 * [CTRLOCATION=BEFORE_FIXED]
 * [RLEN=32_BITS]
 * L = 256
 * L suffix: 32 bits
 */
class SP800Derive {
    private final byte[] mKeyBytes;

    SP800Derive(byte[] keyBytes) {
        mKeyBytes = keyBytes;
    }

    private Mac getMac() {
        try {
            final Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(mKeyBytes, m.getAlgorithm()));
            return m;
        } catch (InvalidKeyException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void update32(Mac m, int v) {
        m.update(ByteBuffer.allocate(Integer.BYTES).putInt(v).array());
    }

    /**
     *  Generate output from a single, fixed input.
     */
    public byte[] fixedInput(byte[] fixedInput) {
        final Mac m = getMac();
        update32(m, 1); // Hardwired counter value
        m.update(fixedInput);
        return m.doFinal();
    }

    /**
     * Generate output from a label and context. We add a length field at the end of the context to
     * disambiguate it from the length even in the presence of zero bytes.
     */
    public byte[] withContext(byte[] label, byte[] context) {
        final Mac m = getMac();
        // Hardwired counter value: 1
        update32(m, 1); // Hardwired counter value
        m.update(label);
        m.update((byte) 0);
        m.update(context);
        update32(m, context.length * 8); // Disambiguate context
        update32(m, 256); // Hardwired output length
        return m.doFinal();
    }
}
