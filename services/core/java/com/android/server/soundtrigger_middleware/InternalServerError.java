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
 * An internal server error.
 * <p>
 * This exception wraps any exception thrown from a service implementation, which is a result of a
 * bug in the server implementation (or any of its dependencies).
 * <p>
 * Specifically, this type is excluded from the set of whitelisted exceptions that binder would
 * tunnel to the client process, since these exceptions are ambiguous regarding whether the client
 * had done something wrong or the server is buggy. For example, a client getting an
 * IllegalArgumentException cannot easily determine whether they had provided illegal arguments to
 * the method they were calling, or whether the method implementation provided illegal arguments to
 * some method it was calling due to a bug.
 *
 * @hide
 */
public class InternalServerError extends RuntimeException {
    public InternalServerError(@NonNull Throwable cause) {
        super(cause);
    }
}
