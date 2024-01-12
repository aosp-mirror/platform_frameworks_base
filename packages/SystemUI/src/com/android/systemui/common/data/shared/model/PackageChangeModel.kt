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

package com.android.systemui.common.data.shared.model

import android.content.Intent

/** Represents changes to an installed package. */
sealed interface PackageChangeModel {
    val packageName: String
    val packageUid: Int

    /** Empty change, provided for convenience when a sensible default value is needed. */
    data object Empty : PackageChangeModel {
        override val packageName: String
            get() = ""
        override val packageUid: Int
            get() = 0
    }

    /**
     * An existing application package was uninstalled.
     *
     * Equivalent to receiving the [Intent.ACTION_PACKAGE_REMOVED] broadcast with
     * [Intent.EXTRA_REPLACING] set to false.
     */
    data class Uninstalled(override val packageName: String, override val packageUid: Int) :
        PackageChangeModel

    /**
     * A new version of an existing application is going to be installed.
     *
     * Equivalent to receiving the [Intent.ACTION_PACKAGE_REMOVED] broadcast with
     * [Intent.EXTRA_REPLACING] set to true.
     */
    data class UpdateStarted(override val packageName: String, override val packageUid: Int) :
        PackageChangeModel

    /**
     * A new version of an existing application package has been installed, replacing the old
     * version.
     *
     * Equivalent to receiving the [Intent.ACTION_PACKAGE_ADDED] broadcast with
     * [Intent.EXTRA_REPLACING] set to true.
     */
    data class UpdateFinished(override val packageName: String, override val packageUid: Int) :
        PackageChangeModel

    /**
     * A new application package has been installed.
     *
     * Equivalent to receiving the [Intent.ACTION_PACKAGE_ADDED] broadcast with
     * [Intent.EXTRA_REPLACING] set to false.
     */
    data class Installed(override val packageName: String, override val packageUid: Int) :
        PackageChangeModel

    /**
     * An existing application package has been changed (for example, a component has been enabled
     * or disabled).
     *
     * Equivalent to receiving the [Intent.ACTION_PACKAGE_CHANGED] broadcast.
     */
    data class Changed(override val packageName: String, override val packageUid: Int) :
        PackageChangeModel
}
