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

package com.android.settingslib.graph

import com.android.settingslib.datastore.AllOfPermissions
import com.android.settingslib.datastore.AnyOfPermissions
import com.android.settingslib.datastore.Permissions
import com.android.settingslib.datastore.and
import com.android.settingslib.datastore.or
import com.android.settingslib.graph.proto.PermissionProto
import com.android.settingslib.graph.proto.PermissionsProto

inline fun permissionsProto(init: PermissionsProto.Builder.() -> Unit): PermissionsProto =
    PermissionsProto.newBuilder().also(init).build()

inline fun permissionProto(init: PermissionProto.Builder.() -> Unit): PermissionProto =
    PermissionProto.newBuilder().also(init).build()

fun Permissions.toProto(): PermissionsProto = permissionsProto {
    when (this@toProto) {
        is AllOfPermissions -> {
            forEach { addAllOf(it.toPermissionProto()) }
        }
        is AnyOfPermissions -> {
            forEach { addAnyOf(it.toPermissionProto()) }
        }
    }
}

private fun Any.toPermissionProto() =
    when {
        this is Permissions -> permissionProto { permissions = toProto() }
        else -> permissionProto { permission = this@toPermissionProto as String }
    }

fun PermissionsProto.getAllPermissions(): List<String> {
    val permissions = mutableSetOf<String>()
    fun Permissions.collect() {
        forEach {
            when {
                it is String -> permissions.add(it)
                else -> (it as Permissions).collect()
            }
        }
    }
    toPermissions().collect()
    return permissions.toList()
}

fun PermissionsProto.toPermissions(): Permissions {
    var permissions = Permissions.EMPTY
    for (index in 0 until allOfCount) {
        val permission = getAllOf(index).toPermission()
        permissions =
            when (permission) {
                is String -> permissions and permission
                else -> permissions and (permission as Permissions)
            }
    }
    for (index in 0 until anyOfCount) {
        val permission = getAnyOf(index).toPermission()
        permissions =
            when (permission) {
                is String -> permissions or permission
                else -> permissions or (permission as Permissions)
            }
    }
    return permissions
}

private fun PermissionProto.toPermission(): Any =
    when {
        hasPermissions() -> permissions.toPermissions()
        else -> permission
    }
