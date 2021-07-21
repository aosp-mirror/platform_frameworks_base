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
import android.app.admin.DevicePolicyManager.OperationSafetyReason;

import com.android.internal.os.IResultReceiver;

/**
 * Interface responsible to check if a {@link DevicePolicyManager} API can be safely executed.
 *
 * @hide
 */
public interface DevicePolicySafetyChecker {

    /**
     * Returns whether the given {@code operation} can be safely executed at the moment.
     */
    @OperationSafetyReason
    int getUnsafeOperationReason(@DevicePolicyOperation int operation);

    /**
     * Return whether it's safe to run operations that can be affected by the given {@code reason}.
     */
    boolean isSafeOperation(@OperationSafetyReason int reason);

    /**
     * Returns a new exception for when the given {@code operation} cannot be safely executed.
     */
    @NonNull
    default UnsafeStateException newUnsafeStateException(@DevicePolicyOperation int operation,
            @OperationSafetyReason int reason) {
        return new UnsafeStateException(operation, reason);
    }

    /**
     * Called when a request was made to factory reset the device, so it can be delayed if it's not
     * safe to proceed.
     *
     * @param callback callback whose {@code send()} method must be called when it's safe to factory
     * reset.
     */
    void onFactoryReset(IResultReceiver callback);
}
