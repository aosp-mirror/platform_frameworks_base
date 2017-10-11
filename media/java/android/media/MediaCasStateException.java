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
import android.os.ServiceSpecificException;

import static android.media.MediaCasException.*;

/**
 * Base class for MediaCas runtime exceptions
 */
public class MediaCasStateException extends IllegalStateException {
    private final int mErrorCode;
    private final String mDiagnosticInfo;

    /** @hide */
    public MediaCasStateException(int err, @Nullable String msg, @Nullable String diagnosticInfo) {
        super(msg);
        mErrorCode = err;
        mDiagnosticInfo = diagnosticInfo;
    }

    static void throwExceptions(ServiceSpecificException e) {
        String diagnosticInfo = "";
        switch (e.errorCode) {
        case ERROR_DRM_UNKNOWN:
            diagnosticInfo = "General CAS error";
            break;
        case ERROR_DRM_NO_LICENSE:
            diagnosticInfo = "No license";
            break;
        case ERROR_DRM_LICENSE_EXPIRED:
            diagnosticInfo = "License expired";
            break;
        case ERROR_DRM_SESSION_NOT_OPENED:
            diagnosticInfo = "Session not opened";
            break;
        case ERROR_DRM_DECRYPT_UNIT_NOT_INITIALIZED:
            diagnosticInfo = "Not initialized";
            break;
        case ERROR_DRM_DECRYPT:
            diagnosticInfo = "Decrypt error";
            break;
        case ERROR_DRM_CANNOT_HANDLE:
            diagnosticInfo = "Unsupported scheme or data format";
            break;
        case ERROR_DRM_TAMPER_DETECTED:
            diagnosticInfo = "Tamper detected";
            break;
        default:
            diagnosticInfo = "Unknown CAS state exception";
            break;
        }
        throw new MediaCasStateException(e.errorCode, e.getMessage(),
                String.format("%s (err=%d)", diagnosticInfo, e.errorCode));
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
