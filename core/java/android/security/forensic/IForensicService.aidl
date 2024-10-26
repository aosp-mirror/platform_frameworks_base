/*
 * Copyright 2024 The Android Open Source Project
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

package android.security.forensic;

import android.security.forensic.IForensicServiceCommandCallback;
import android.security.forensic.IForensicServiceStateCallback;

/**
 * Binder interface to communicate with ForensicService.
 * @hide
 */
interface IForensicService {
    @EnforcePermission("READ_FORENSIC_STATE")
    void addStateCallback(IForensicServiceStateCallback callback);
    @EnforcePermission("READ_FORENSIC_STATE")
    void removeStateCallback(IForensicServiceStateCallback callback);
    @EnforcePermission("MANAGE_FORENSIC_STATE")
    void enable(IForensicServiceCommandCallback callback);
    @EnforcePermission("MANAGE_FORENSIC_STATE")
    void disable(IForensicServiceCommandCallback callback);
}
