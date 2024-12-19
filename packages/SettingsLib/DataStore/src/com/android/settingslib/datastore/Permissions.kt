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

package com.android.settingslib.datastore

import android.content.Context
import android.content.pm.PackageManager.PERMISSION_GRANTED

/**
 * Class to manage permissions, which supports a combination of AND / OR.
 *
 * Samples:
 * - `Permissions.EMPTY`: no permission is required
 * - `Permissions.allOf(p1, p2) or p3 or Permissions.allOf(p4, p5)`
 * - `Permissions.anyOf(p1, p2) and p3 and Permissions.anyOf(p4, p5)`
 * - `Permissions.allOf(p1, p2) or (Permissions.allOf(p3, p4) and p5)`: ALWAYS add `()` explicitly
 *   when and/or operators are used at the same time.
 */
sealed class Permissions(vararg permissions: Any) {
    internal val permissions = mutableSetOf(*permissions)

    val size: Int
        get() = permissions.size

    override fun hashCode() = permissions.hashCode()

    override fun equals(other: Any?) =
        other is Permissions &&
            permissions == other.permissions &&
            (permissions.size == 1 || javaClass == other.javaClass)

    abstract fun check(context: Context, pid: Int, uid: Int): Boolean

    internal fun addForAnd(permission: Any): Permissions =
        when {
            // ensure empty permissions will never been modified
            permissions.isEmpty() -> (permission as? Permissions) ?: AllOfPermissions(permission)
            permission is Permissions && permission.permissions.isEmpty() -> this
            this is AllOfPermissions -> apply { and(permission) }
            permission is AllOfPermissions -> permission.also { it.and(this) }
            // anyOf(p1) and p2 => allOf(p1, p2)
            permissions.size == 1 && this is AnyOfPermissions && permission is String ->
                AllOfPermissions(permissions.first(), permission)
            // anyOf(p1) and anyOf(p2) => allOf(p1, p2)
            permissions.size == 1 &&
                permission is AnyOfPermissions &&
                permission.permissions.size == 1 ->
                AllOfPermissions(permissions.first(), permission.permissions.first())
            else -> AllOfPermissions(this, permission)
        }

    internal fun addForOr(permission: Any): Permissions =
        when {
            // ensure empty permissions will never been modified
            permissions.isEmpty() -> (permission as? Permissions) ?: AnyOfPermissions(permission)
            permission is Permissions && permission.permissions.isEmpty() -> this
            this is AnyOfPermissions -> apply { or(permission) }
            permission is AnyOfPermissions -> permission.also { it.or(this) }
            // allOf(p1) or p2 => anyOf(p1, p2)
            permissions.size == 1 && this is AllOfPermissions && permission is String ->
                AnyOfPermissions(permissions.first(), permission)
            // allOf(p1) or allOf(p2) => anyOf(p1, p2)
            permissions.size == 1 &&
                permission is AllOfPermissions &&
                permission.permissions.size == 1 ->
                AnyOfPermissions(permissions.first(), permission.permissions.first())
            else -> AnyOfPermissions(this, permission)
        }

    protected fun Any.check(context: Context, pid: Int, uid: Int) =
        when (this) {
            is String -> context.checkPermission(this, pid, uid) == PERMISSION_GRANTED
            else -> (this as Permissions).check(context, pid, uid)
        }

    fun forEach(action: (Any) -> Unit) {
        for (permission in permissions) action(permission)
    }

    companion object {
        /** Returns [Permissions] that requires all of the permissions. */
        fun allOf(vararg permissions: String): Permissions =
            if (permissions.isEmpty()) EMPTY else AllOfPermissions(*permissions)

        /** Returns [Permissions] that requires any of the permissions. */
        fun anyOf(vararg permissions: String): Permissions =
            if (permissions.isEmpty()) EMPTY else AnyOfPermissions(*permissions)

        /** No permission required. */
        val EMPTY: Permissions = AllOfPermissions()
    }
}

class AllOfPermissions internal constructor(vararg permissions: Any) : Permissions(*permissions) {

    override fun toString() = permissions.joinToString(prefix = "allOf(", postfix = ")")

    override fun check(context: Context, pid: Int, uid: Int): Boolean {
        // use for-loop explicitly instead of "all" extension for empty permissions
        for (permission in permissions) {
            if (!permission.check(context, pid, uid)) return false
        }
        return true
    }

    internal fun and(permission: Any) {
        when {
            // in-place merge to reduce the hierarchy
            permission is AllOfPermissions -> permissions.addAll(permission.permissions)
            // allOf(...) and anyOf(p) => allOf(..., p)
            permission is AnyOfPermissions && permission.permissions.size == 1 ->
                permissions.add(permission.permissions.first())

            else -> permissions.add(permission)
        }
    }
}

class AnyOfPermissions internal constructor(vararg permissions: Any) : Permissions(*permissions) {

    override fun toString() = permissions.joinToString(prefix = "anyOf(", postfix = ")")

    override fun check(context: Context, pid: Int, uid: Int): Boolean {
        // use for-loop explicitly instead of "any" extension for empty permissions
        for (permission in permissions) {
            if (permission.check(context, pid, uid)) return true
        }
        return permissions.isEmpty()
    }

    internal fun or(permission: Any) {
        when {
            // in-place merge to reduce the hierarchy
            permission is AnyOfPermissions -> permissions.addAll(permission.permissions)
            // anyOf(...) or allOf(p) => anyOf(..., p)
            permission is AllOfPermissions && permission.permissions.size == 1 ->
                permissions.add(permission.permissions.first())
            else -> permissions.add(permission)
        }
    }
}

infix fun Permissions.and(permission: String): Permissions = addForAnd(permission)

infix fun Permissions.and(permissions: Permissions): Permissions = addForAnd(permissions)

infix fun Permissions.or(permission: String): Permissions = addForOr(permission)

infix fun Permissions.or(permissions: Permissions): Permissions = addForOr(permissions)
