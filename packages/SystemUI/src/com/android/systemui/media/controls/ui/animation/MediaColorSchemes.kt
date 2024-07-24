/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.media.controls.ui.animation

import com.android.systemui.monet.ColorScheme

/** Returns the surface color for media controls based on the scheme. */
internal fun surfaceFromScheme(scheme: ColorScheme) = scheme.accent2.s800 // A2-800

/** Returns the primary accent color for media controls based on the scheme. */
internal fun accentPrimaryFromScheme(scheme: ColorScheme) = scheme.accent1.s100 // A1-100

/** Returns the secondary accent color for media controls based on the scheme. */
internal fun accentSecondaryFromScheme(scheme: ColorScheme) = scheme.accent1.s200 // A1-200

/** Returns the primary text color for media controls based on the scheme. */
internal fun textPrimaryFromScheme(scheme: ColorScheme) = scheme.neutral1.s50 // N1-50

/** Returns the inverse of the primary text color for media controls based on the scheme. */
internal fun textPrimaryInverseFromScheme(scheme: ColorScheme) = scheme.neutral1.s900 // N1-900

/** Returns the secondary text color for media controls based on the scheme. */
internal fun textSecondaryFromScheme(scheme: ColorScheme) = scheme.neutral2.s200 // N2-200

/** Returns the tertiary text color for media controls based on the scheme. */
internal fun textTertiaryFromScheme(scheme: ColorScheme) = scheme.neutral2.s400 // N2-400

/** Returns the color for the start of the background gradient based on the scheme. */
internal fun backgroundStartFromScheme(scheme: ColorScheme) = scheme.accent2.s700 // A2-700

/** Returns the color for the end of the background gradient based on the scheme. */
internal fun backgroundEndFromScheme(scheme: ColorScheme) = scheme.accent1.s700 // A1-700
