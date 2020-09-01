/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.soundtrigger_middleware;

import android.annotation.NonNull;

/**
 * This exception represents a fault which:
 * <ul>
 * <li>Could not have been anticipated by a caller (i.e. is not a violation of any preconditions).
 * <li>Is guaranteed to not have been caused any meaningful state change in the callee. The caller
 *     may continue operation as if the call has never been made.
 * </ul>
 * <p>
 * Some recoverable faults are permanent and some are transient / circumstantial, the specific error
 * code can provide more information about the possible recovery options.
 * <p>
 * The reason why this is a RuntimeException is to allow it to go through interfaces defined by
 * AIDL, which we have no control over.
 *
 * @hide
 */
public class RecoverableException extends RuntimeException {
    public final int errorCode;

    public RecoverableException(int errorCode, @NonNull String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public RecoverableException(int errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public @NonNull String toString() {
        return super.toString() + " (code " + errorCode + ")";
    }
}
