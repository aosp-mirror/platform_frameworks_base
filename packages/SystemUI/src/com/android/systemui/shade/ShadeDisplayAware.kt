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

package com.android.systemui.shade

import javax.inject.Qualifier

/**
 * Qualifies classes that provide display-specific info for shade window components.
 *
 * The Shade window can be moved between displays with different characteristics (e.g., density,
 * size). This annotation ensures that components within the shade window use the correct context
 * and resources for the display they are currently on.
 *
 * Classes annotated with `@ShadeDisplayAware` (e.g., 'Context`, `Resources`, `LayoutInflater`,
 * `ConfigurationController`) will be dynamically updated to reflect the current display's
 * configuration. This ensures consistent rendering even when the shade window is moved to an
 * external display.
 */
@Qualifier @Retention(AnnotationRetention.RUNTIME) annotation class ShadeDisplayAware
