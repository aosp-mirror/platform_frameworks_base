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

package com.android.server.power;

/**
 * @hide Only for use within the system server.
 */
public abstract class LowPowerStandbyControllerInternal {
    /**
     * Adds an application to the Low Power Standby allowlist,
     * exempting it from Low Power Standby restrictions.
     *
     * @param uid UID to add to allowlist.
     */
    public abstract void addToAllowlist(int uid);

    /**
     * Removes an application from the Low Power Standby allowlist.
     *
     * @param uid UID to remove from allowlist.
     */
    public abstract void removeFromAllowlist(int uid);
}
