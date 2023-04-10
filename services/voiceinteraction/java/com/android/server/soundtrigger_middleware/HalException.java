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
 * This exception represents a non-zero status code returned by a HAL invocation.
 * Depending on the operation that threw the error, the integrity of the HAL implementation and the
 * client's tolerance to error, this error may or may not be recoverable. The HAL itself is expected
 * to retain the state it had prior to the invocation (so, unless the error is a result of a HAL
 * bug, normal operation may resume).
 * <p>
 * The reason why this is a RuntimeException, even though the HAL interface allows returning them
 * is because we expect none of them to actually occur as part of correct usage of the HAL.
 *
 * @hide
 */
public class HalException extends RuntimeException {
    public final int errorCode;

    public HalException(int errorCode, @NonNull String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public HalException(int errorCode) {
        this.errorCode = errorCode;
    }

    @Override
    public @NonNull String toString() {
        return super.toString() + " (code " + errorCode + ")";
    }
}
