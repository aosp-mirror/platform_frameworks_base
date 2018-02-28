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

import java.security.SecureRandom;

/**
 * @deprecated Use {@link android.security.keystore.recovery.RecoverySession}.
 * @hide
 */
public class RecoverySession implements AutoCloseable {

    private static final int SESSION_ID_LENGTH_BYTES = 16;

    private final String mSessionId;
    private final RecoveryController mRecoveryController;

    private RecoverySession(RecoveryController recoveryController, String sessionId) {
        mRecoveryController = recoveryController;
        mSessionId = sessionId;
    }

    /**
     * A new session, started by {@code recoveryManager}.
     */
    static RecoverySession newInstance(RecoveryController recoveryController) {
        return new RecoverySession(recoveryController, newSessionId());
    }

    /**
     * Returns a new random session ID.
     */
    private static String newSessionId() {
        SecureRandom secureRandom = new SecureRandom();
        byte[] sessionId = new byte[SESSION_ID_LENGTH_BYTES];
        secureRandom.nextBytes(sessionId);
        StringBuilder sb = new StringBuilder();
        for (byte b : sessionId) {
            sb.append(Byte.toHexString(b, /*upperCase=*/ false));
        }
        return sb.toString();
    }

    /**
     * An internal session ID, used by the framework to match recovery claims to snapshot responses.
     */
    String getSessionId() {
        return mSessionId;
    }

    @Override
    public void close() {
        mRecoveryController.closeSession(this);
    }
}
