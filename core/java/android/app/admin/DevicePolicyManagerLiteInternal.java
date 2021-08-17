/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.admin.DevicePolicyManager.OperationSafetyReason;

/**
 * Device policy manager local system service interface for methods that don't require the
 * {@code device_admin} feature.
 *
 * Maintenance note: if you need to expose information from DPMS to lower level services such as
 * PM/UM/AM/etc, then exposing it from DevicePolicyManagerInternal is not safe because it may cause
 * lock order inversion. Consider using {@link DevicePolicyCache} instead.
 *
 * @hide Only for use within the system server.
 */
public interface DevicePolicyManagerLiteInternal {

    /**
     * Notifies the system that an unsafe operation reason has changed.
     *
     * @throws IllegalArgumentException if {@code checker} is not the same as set on
     *         {@code DevicePolicyManagerService.setDevicePolicySafetyChecker()}.
     */
    void notifyUnsafeOperationStateChanged(DevicePolicySafetyChecker checker,
            @OperationSafetyReason int reason, boolean isSafe);
}
