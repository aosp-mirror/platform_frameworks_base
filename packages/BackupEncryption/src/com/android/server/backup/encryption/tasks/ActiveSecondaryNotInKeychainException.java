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

package com.android.server.backup.encryption.tasks;

/**
 * Error thrown when the server's active secondary key does not exist in the user's recoverable
 * keychain. This means the backup data cannot be decrypted, and should be wiped.
 */
public class ActiveSecondaryNotInKeychainException extends Exception {
    public ActiveSecondaryNotInKeychainException(String message) {
        super(message);
    }
}
