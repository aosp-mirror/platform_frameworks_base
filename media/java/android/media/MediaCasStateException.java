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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.cas.V1_2.Status;

/**
 * Base class for MediaCas runtime exceptions
 */
public class MediaCasStateException extends IllegalStateException {
    private final int mErrorCode;
    private final String mDiagnosticInfo;

    private MediaCasStateException(int err, @Nullable String msg, @Nullable String diagnosticInfo) {
        super(msg);
        mErrorCode = err;
        mDiagnosticInfo = diagnosticInfo;
    }

    static void throwExceptionIfNeeded(int err) {
        throwExceptionIfNeeded(err, null /* msg */);
    }

    static void throwExceptionIfNeeded(int err, @Nullable String msg) {
        if (err == Status.OK) {
            return;
        }
        if (err == Status.BAD_VALUE) {
            throw new IllegalArgumentException();
        }

        String diagnosticInfo = "";
        switch (err) {
            case Status.ERROR_CAS_UNKNOWN:
                diagnosticInfo = "General CAS error";
                break;
            case Status.ERROR_CAS_NO_LICENSE:
                diagnosticInfo = "No license";
                break;
            case Status.ERROR_CAS_LICENSE_EXPIRED:
                diagnosticInfo = "License expired";
                break;
            case Status.ERROR_CAS_SESSION_NOT_OPENED:
                diagnosticInfo = "Session not opened";
                break;
            case Status.ERROR_CAS_CANNOT_HANDLE:
                diagnosticInfo = "Unsupported scheme or data format";
                break;
            case Status.ERROR_CAS_INVALID_STATE:
                diagnosticInfo = "Invalid CAS state";
                break;
            case Status.ERROR_CAS_INSUFFICIENT_OUTPUT_PROTECTION:
                diagnosticInfo = "Insufficient output protection";
                break;
            case Status.ERROR_CAS_TAMPER_DETECTED:
                diagnosticInfo = "Tamper detected";
                break;
            case Status.ERROR_CAS_DECRYPT_UNIT_NOT_INITIALIZED:
                diagnosticInfo = "Not initialized";
                break;
            case Status.ERROR_CAS_DECRYPT:
                diagnosticInfo = "Decrypt error";
                break;
            case Status.ERROR_CAS_NEED_ACTIVATION:
                diagnosticInfo = "Need Activation";
                break;
            case Status.ERROR_CAS_NEED_PAIRING:
                diagnosticInfo = "Need Pairing";
                break;
            case Status.ERROR_CAS_NO_CARD:
                diagnosticInfo = "No Card";
                break;
            case Status.ERROR_CAS_CARD_MUTE:
                diagnosticInfo = "Card Muted";
                break;
            case Status.ERROR_CAS_CARD_INVALID:
                diagnosticInfo = "Card Invalid";
                break;
            case Status.ERROR_CAS_BLACKOUT:
                diagnosticInfo = "Blackout";
                break;
            case Status.ERROR_CAS_REBOOTING:
                diagnosticInfo = "Rebooting";
                break;
            default:
                diagnosticInfo = "Unknown CAS state exception";
                break;
        }
        throw new MediaCasStateException(err, msg,
                String.format("%s (err=%d)", diagnosticInfo, err));
    }

    /**
     * Retrieve the associated error code
     *
     * @hide
     */
    public int getErrorCode() {
        return mErrorCode;
    }

    /**
     * Retrieve a developer-readable diagnostic information string
     * associated with the exception. Do not show this to end-users,
     * since this string will not be localized or generally comprehensible
     * to end-users.
     */
    @NonNull
    public String getDiagnosticInfo() {
        return mDiagnosticInfo;
    }
}
