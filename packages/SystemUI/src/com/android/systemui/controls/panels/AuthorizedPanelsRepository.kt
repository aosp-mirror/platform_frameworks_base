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
 *
 */

package com.android.systemui.controls.panels

/**
 * Repository for keeping track of which packages the panel has authorized to show control panels
 * (embedded activity).
 */
interface AuthorizedPanelsRepository {

    /** A set of package names that the user has previously authorized to show panels. */
    fun getAuthorizedPanels(): Set<String>

    /** Preferred applications to query controls suggestions from */
    fun getPreferredPackages(): Set<String>

    /** Adds [packageNames] to the set of packages that the user has authorized to show panels. */
    fun addAuthorizedPanels(packageNames: Set<String>)

    /**
     * Removes [packageNames] from the set of packages that the user has authorized to show panels.
     */
    fun removeAuthorizedPanels(packageNames: Set<String>)
}
