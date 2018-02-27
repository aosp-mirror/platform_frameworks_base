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

package android.security.keystore;

/**
 * @deprecated Use {@link android.security.keystore.recovery.RecoverySession}.
 * @hide
 */
public class RecoveryClaim {

    private final RecoverySession mRecoverySession;
    private final byte[] mClaimBytes;

    RecoveryClaim(RecoverySession recoverySession, byte[] claimBytes) {
        mRecoverySession = recoverySession;
        mClaimBytes = claimBytes;
    }

    /**
     * Returns the session associated with the recovery attempt. This is used to match the symmetric
     * key, which remains internal to the framework, for decrypting the claim response.
     *
     * @return The session data.
     */
    public RecoverySession getRecoverySession() {
        return mRecoverySession;
    }

    /**
     * Returns the encrypted claim's bytes.
     *
     * <p>This should be sent by the recovery agent to the remote secure hardware, which will use
     * it to decrypt the keychain, before sending it re-encrypted with the session's symmetric key
     * to the device.
     */
    public byte[] getClaimBytes() {
        return mClaimBytes;
    }
}
