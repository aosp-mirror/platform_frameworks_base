/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.service.notification;

import android.service.notification.ZenModeConfig.ConfigChangeOrigin;

/**
 * Responsible for making any service calls needed to apply the set of {@link ZenDeviceEffects} that
 * make sense for the current platform.
 * @hide
 */
public interface DeviceEffectsApplier {
    /**
     * Applies the {@link ZenDeviceEffects} to the device.
     *
     * <p>The supplied {@code effects} represents the "consolidated" device effects, i.e. the
     * union of the effects of all the {@link ZenModeConfig.ZenRule} instances that are currently
     * active. If no rules are active (or no active rules specify custom effects) then {@code
     * effects} will be all-default (i.e. {@link ZenDeviceEffects#hasEffects} will return {@code
     * false}.
     *
     * <p>This will be called whenever the set of consolidated effects changes (normally through
     * the activation or deactivation of zen rules).
     *
     * @param effects The effects that should be active and inactive.
     * @param source The origin of the change. Because the application of specific effects can be
     *               disruptive (e.g. lead to Activity recreation), that operation can in some
     *               cases be deferred (e.g. until screen off). However, if the effects are
     *               changing as a result of an explicit user action, then it makes sense to
     *               apply them immediately regardless.
     */
    void apply(ZenDeviceEffects effects, @ConfigChangeOrigin int source);
}
