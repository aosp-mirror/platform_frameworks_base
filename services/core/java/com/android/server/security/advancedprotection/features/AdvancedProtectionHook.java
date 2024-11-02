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

package com.android.server.security.advancedprotection.features;

import android.annotation.NonNull;
import android.content.Context;
import android.security.advancedprotection.AdvancedProtectionFeature;

/** @hide */
public abstract class AdvancedProtectionHook {
    /** Called on boot phase PHASE_SYSTEM_SERVICES_READY */
    public AdvancedProtectionHook(@NonNull Context context, boolean enabled) {}
    /** The feature this hook provides */
    @NonNull
    public abstract AdvancedProtectionFeature getFeature();
    /** Whether this feature is relevant on this device. If false, onAdvancedProtectionChanged will
     * not be called, and the feature will not be displayed in the onboarding UX. */
    public abstract boolean isAvailable();
    /** Called whenever advanced protection state changes */
    public void onAdvancedProtectionChanged(boolean enabled) {}
}
