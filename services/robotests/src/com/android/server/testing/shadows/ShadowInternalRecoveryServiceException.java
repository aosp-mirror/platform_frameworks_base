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

package com.android.server.testing.shadows;

import android.security.keystore.recovery.InternalRecoveryServiceException;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Shadow {@link InternalRecoveryServiceException}. */
@Implements(InternalRecoveryServiceException.class)
public class ShadowInternalRecoveryServiceException {
    private String mMessage;

    @Implementation
    public void __constructor__(String message) {
        mMessage = message;
    }

    @Implementation
    public void __constructor__(String message, Throwable cause) {
        mMessage = message;
    }

    @Implementation
    public String getMessage() {
        return mMessage;
    }
}
