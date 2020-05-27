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

package android.os.strictmode;

import android.content.Context;

/**
 * Subclass of {@code Violation} that is used when a process accesses filesystem
 * paths stored in credential protected storage areas while the user is locked.
 * <p>
 * When a user is locked, credential protected storage is unavailable, and files
 * stored in these locations appear to not exist, which can result in subtle app
 * bugs if they assume default behaviors or empty states. Instead, apps should
 * store data needed while a user is locked under device protected storage
 * areas.
 *
 * @see Context#createDeviceProtectedStorageContext()
 */
public final class CredentialProtectedWhileLockedViolation extends Violation {
    /** @hide */
    public CredentialProtectedWhileLockedViolation(String message) {
        super(message);
    }
}
