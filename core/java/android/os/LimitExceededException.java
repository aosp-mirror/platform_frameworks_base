/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;

/** Indicates that the app has exceeded a limit set by the System. */
public class LimitExceededException extends IllegalStateException {

    /**
     * Constructs a new {@code LimitExceededException} with {@code null} as its
     * detail message. The cause is not initialized, and may subsequently be
     * initialized by a call to {@link #initCause}.
     */
    public LimitExceededException() {
        super();
    }

    /**
     * Constructs a new {@code LimitExceededException} with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause}.
     *
     * @param message the detail message which is saved for later retrieval
     *                by the {@link #getMessage()} method.
     */
    public LimitExceededException(@NonNull String message) {
        super(message);
    }
}
