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

package android.security.keystore.recovery;

import android.annotation.SystemApi;

import java.security.GeneralSecurityException;

/**
 * An error thrown when something went wrong internally in the recovery service.
 *
 * <p>This is an unexpected error, and indicates a problem with the service itself, rather than the
 * caller having performed some kind of illegal action.
 *
 * @hide
 */
@SystemApi
public class InternalRecoveryServiceException extends GeneralSecurityException {
    public InternalRecoveryServiceException(String msg) {
        super(msg);
    }

    public InternalRecoveryServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
