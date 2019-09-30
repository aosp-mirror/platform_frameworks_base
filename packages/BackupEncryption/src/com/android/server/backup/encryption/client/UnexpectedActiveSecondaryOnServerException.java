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

package com.android.server.backup.encryption.client;

/**
 * Error thrown when the user attempts to retrieve a key set from the server, but is asking for keys
 * from an inactive secondary.
 *
 * <p>Although we could just return old keys, there is no good reason to do this. It almost
 * certainly indicates a logic error on the client.
 */
public class UnexpectedActiveSecondaryOnServerException extends Exception {
    public UnexpectedActiveSecondaryOnServerException(String message) {
        super(message);
    }
}
