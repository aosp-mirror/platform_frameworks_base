/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access.data

import com.android.server.permission.access.external.AndroidPackage

class Package(
    private val androidPackage: AndroidPackage
) {
    val name: String
        get() = androidPackage.packageName

    val adoptPermissions: List<String>
        get() = androidPackage.adoptPermissions

    val appId: Int
        get() = androidPackage.appId

    val requestedPermissions: List<String>
        get() = androidPackage.requestedPermissions

    override fun equals(other: Any?): Boolean {
        throw NotImplementedError()
    }

    override fun hashCode(): Int {
        throw NotImplementedError()
    }
}
