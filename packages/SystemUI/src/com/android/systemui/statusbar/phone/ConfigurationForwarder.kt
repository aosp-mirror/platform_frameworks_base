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

package com.android.systemui.statusbar.phone

import android.content.res.Configuration

/**
 * Used to forward a configuration change to other components.
 *
 * This is commonly used to propagate configs to [ConfigurationController]. Note that there could be
 * different configuration forwarder, for example each display, window or group of classes (e.g.
 * shade window classes).
 */
interface ConfigurationForwarder {
    /** Should be called when a new configuration is received. */
    fun onConfigurationChanged(newConfiguration: Configuration)
}
