/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.security.advancedprotection;

import android.security.advancedprotection.AdvancedProtectionFeature;
import android.security.advancedprotection.IAdvancedProtectionCallback;

/**
 * Binder interface for apps to communicate with system server implementations of
 * AdvancedProtectionService.
 * @hide
 */
interface IAdvancedProtectionService {
    @EnforcePermission("QUERY_ADVANCED_PROTECTION_MODE")
    boolean isAdvancedProtectionEnabled();
    @EnforcePermission("QUERY_ADVANCED_PROTECTION_MODE")
    void registerAdvancedProtectionCallback(IAdvancedProtectionCallback callback);
    @EnforcePermission("QUERY_ADVANCED_PROTECTION_MODE")
    void unregisterAdvancedProtectionCallback(IAdvancedProtectionCallback callback);
    @EnforcePermission("MANAGE_ADVANCED_PROTECTION_MODE")
    void setAdvancedProtectionEnabled(boolean enabled);
    @EnforcePermission("MANAGE_ADVANCED_PROTECTION_MODE")
    List<AdvancedProtectionFeature> getAdvancedProtectionFeatures();
}