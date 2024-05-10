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

package com.android.server.permission.access

import android.os.UserHandle

sealed class AccessUri(val scheme: String) {
    override fun equals(other: Any?): Boolean {
        throw NotImplementedError()
    }

    override fun hashCode(): Int {
        throw NotImplementedError()
    }

    override fun toString(): String {
        throw NotImplementedError()
    }
}

data class AppOpUri(val appOpName: String) : AccessUri(SCHEME) {
    override fun toString(): String = "$scheme:///$appOpName"

    companion object {
        const val SCHEME = "app-op"
    }
}

data class PackageUri(val packageName: String, val userId: Int) : AccessUri(SCHEME) {
    override fun toString(): String = "$scheme:///$packageName/$userId"

    companion object {
        const val SCHEME = "package"
    }
}

data class PermissionUri(val permissionName: String) : AccessUri(SCHEME) {
    override fun toString(): String = "$scheme:///$permissionName"

    companion object {
        const val SCHEME = "permission"
    }
}

data class DevicePermissionUri(val permissionName: String, val deviceId: Int) : AccessUri(SCHEME) {
    override fun toString(): String = "$scheme:///$permissionName/$deviceId"

    companion object {
        const val SCHEME = "device-permission"
    }
}

data class UidUri(val uid: Int) : AccessUri(SCHEME) {
    val userId: Int
        get() = UserHandle.getUserId(uid)

    val appId: Int
        get() = UserHandle.getAppId(uid)

    override fun toString(): String = "$scheme:///$uid"

    companion object {
        const val SCHEME = "uid"
    }
}
