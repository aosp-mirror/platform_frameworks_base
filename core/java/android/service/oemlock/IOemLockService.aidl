/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.oemlock;

/**
 * Interface for communication with the OemLockService.
 *
 * @hide
 */
interface IOemLockService {
    @EnforcePermission("MANAGE_CARRIER_OEM_UNLOCK_STATE")
    String getLockName();

    @EnforcePermission("MANAGE_CARRIER_OEM_UNLOCK_STATE")
    void setOemUnlockAllowedByCarrier(boolean allowed, in byte[] signature);
    @EnforcePermission("MANAGE_CARRIER_OEM_UNLOCK_STATE")
    boolean isOemUnlockAllowedByCarrier();

    @EnforcePermission("MANAGE_USER_OEM_UNLOCK_STATE")
    void setOemUnlockAllowedByUser(boolean allowed);
    @EnforcePermission("MANAGE_USER_OEM_UNLOCK_STATE")
    boolean isOemUnlockAllowedByUser();

    @EnforcePermission(anyOf = {"READ_OEM_UNLOCK_STATE", "OEM_UNLOCK_STATE"})
    boolean isOemUnlockAllowed();
    @EnforcePermission(anyOf = {"READ_OEM_UNLOCK_STATE", "OEM_UNLOCK_STATE"})
    boolean isDeviceOemUnlocked();
}
