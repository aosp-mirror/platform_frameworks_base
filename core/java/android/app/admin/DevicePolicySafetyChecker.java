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
package android.app.admin;

import android.annotation.NonNull;
import android.app.admin.DevicePolicyManager.DevicePolicyOperation;

/**
 * Interface responsible to check if a {@link DevicePolicyManager} API can be safely executed.
 *
 * @hide
 */
public interface DevicePolicySafetyChecker {

    /**
     * Returns whether the given {@code operation} can be safely executed at the moment.
     */
    default boolean isDevicePolicyOperationSafe(@DevicePolicyOperation int operation) {
        return true;
    }

    /**
     * Returns a new exception for when the given {@code operation} cannot be safely executed.
     */
    @NonNull
    default UnsafeStateException newUnsafeStateException(@DevicePolicyOperation int operation) {
        return new UnsafeStateException(operation);
    }
}
