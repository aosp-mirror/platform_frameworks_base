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

package com.android.systemui.mediaprojection

import android.content.pm.PackageManager
import com.android.systemui.util.Utils

/** Various utility methods related to media projection. */
object MediaProjectionUtils {
    /**
     * Returns true iff projecting to the given [packageName] means that we're casting media to a
     * *different* device (as opposed to sharing media to some application on *this* device).
     */
    fun packageHasCastingCapabilities(
        packageManager: PackageManager,
        packageName: String
    ): Boolean {
        // The [isHeadlessRemoteDisplayProvider] check approximates whether a projection is to a
        // different device or the same device, because headless remote display packages are the
        // only kinds of packages that do cast-to-other-device. This isn't exactly perfect,
        // because it means that any projection by those headless remote display packages will be
        // marked as going to a different device, even if that isn't always true. See b/321078669.
        return Utils.isHeadlessRemoteDisplayProvider(packageManager, packageName)
    }
}
