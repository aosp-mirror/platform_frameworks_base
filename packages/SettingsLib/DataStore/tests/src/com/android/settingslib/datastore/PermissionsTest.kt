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
import android.content.ContextWrapper
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.datastore.Permissions.Companion.EMPTY
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun empty() {
        assertThat(Permissions.allOf()).isSameInstanceAs(EMPTY)
        assertThat(Permissions.anyOf()).isSameInstanceAs(EMPTY)
        assertThat(EMPTY.check(context, 0, 0)).isTrue()
        assertThat((EMPTY and "a").permissions).containsExactly("a")
        assertThat((EMPTY or "a").permissions).containsExactly("a")
    }

    @Test
    fun allOf_op_empty() {
        val allOf = Permissions.allOf("a")
        assertThat(allOf and EMPTY).isSameInstanceAs(allOf)
        assertThat(EMPTY and allOf).isSameInstanceAs(allOf)
        assertThat(EMPTY or allOf).isSameInstanceAs(allOf)
        assertThat(allOf or EMPTY).isSameInstanceAs(allOf)
    }

    @Test
    fun anyOf_op_empty() {
        val anyOf = Permissions.anyOf("a")
        assertThat(anyOf and EMPTY).isSameInstanceAs(anyOf)
        assertThat(EMPTY and anyOf).isSameInstanceAs(anyOf)
        assertThat(EMPTY or anyOf).isSameInstanceAs(anyOf)
        assertThat(anyOf or EMPTY).isSameInstanceAs(anyOf)
    }

    @Test
    fun allOf1_and_allOf1() {
        val allOf = Permissions.allOf("a")
        assertThat(allOf and Permissions.allOf("b")).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun allOf1_or_allOf1() {
        val merged = Permissions.allOf("a") or Permissions.allOf("b")
        assertThat(merged.permissions).containsExactly("a", "b")
        assertThat(merged).isInstanceOf(AnyOfPermissions::class.java)
    }

    @Test
    fun allOf1_and_anyOf1() {
        val allOf = Permissions.allOf("a")
        val anyOf = Permissions.anyOf("b")
        assertThat(allOf and anyOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun allOf1_or_anyOf1() {
        val allOf = Permissions.allOf("a")
        val anyOf = Permissions.anyOf("b")
        assertThat(allOf or anyOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun anyOf1_and_allOf1() {
        val anyOf = Permissions.anyOf("a")
        val allOf = Permissions.allOf("b")
        assertThat(anyOf and allOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun anyOf1_or_allOf1() {
        val anyOf = Permissions.anyOf("a")
        val allOf = Permissions.allOf("b")
        assertThat(anyOf or allOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun anyOf1_and_anyOf1() {
        val merged = Permissions.anyOf("a") and Permissions.anyOf("b")
        assertThat(merged.permissions).containsExactly("a", "b")
        assertThat(merged).isInstanceOf(AllOfPermissions::class.java)
    }

    @Test
    fun anyOf1_or_anyOf1() {
        val anyOf = Permissions.anyOf("a")
        assertThat(anyOf or Permissions.anyOf("b")).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b")
    }

    @Test
    fun allOf1_and_anyOf2() {
        val allOf = Permissions.allOf("a")
        val anyOf = Permissions.anyOf("a", "b")
        assertThat(allOf and anyOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", anyOf)
    }

    @Test
    fun allOf1_or_anyOf2() {
        val allOf = Permissions.allOf("a")
        val anyOf = Permissions.anyOf("a", "b")
        assertThat(allOf and anyOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", anyOf)
    }

    @Test
    fun allOf2_and_anyOf1() {
        val allOf = Permissions.allOf("a", "b")
        val anyOf = Permissions.anyOf("c")
        assertThat(allOf and anyOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b", "c")
    }

    @Test
    fun allOf2_or_anyOf1() {
        val allOf = Permissions.allOf("a", "b")
        val anyOf = Permissions.anyOf("b")
        assertThat(allOf or anyOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("b", allOf)
    }

    @Test
    fun anyOf1_and_allOf2() {
        val anyOf = Permissions.anyOf("c")
        val allOf = Permissions.allOf("a", "b")
        assertThat(anyOf and allOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b", "c")
    }

    @Test
    fun anyOf1_or_allOf2() {
        val anyOf = Permissions.anyOf("a")
        val allOf = Permissions.allOf("a", "b")
        assertThat(anyOf or allOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", allOf)
    }

    @Test
    fun anyOf2_and_allOf1() {
        val anyOf = Permissions.anyOf("a", "b")
        val allOf = Permissions.allOf("a")
        assertThat(anyOf and allOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", anyOf)
    }

    @Test
    fun anyOf2_or_allOf1() {
        val anyOf = Permissions.anyOf("a", "b")
        val allOf = Permissions.allOf("c")
        assertThat(anyOf or allOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b", "c")
    }

    @Test
    fun allOf2_and_allOf2() {
        val allOf = Permissions.allOf("a", "b")
        assertThat(allOf and Permissions.allOf("a", "c")).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b", "c")
    }

    @Test
    fun allOf2_or_allOf2() {
        val allOf1 = Permissions.allOf("a", "b")
        val allOf2 = Permissions.allOf("a", "c")
        assertThat((allOf1 or allOf2).permissions).containsExactly(allOf1, allOf2)
    }

    @Test
    fun allOf2_and_anyOf2() {
        val allOf = Permissions.allOf("a", "b")
        val anyOf = Permissions.anyOf("a", "c")
        assertThat(allOf and anyOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "b", anyOf)
    }

    @Test
    fun allOf2_or_anyOf2() {
        val allOf = Permissions.allOf("a", "b")
        val anyOf = Permissions.anyOf("a", "c")
        assertThat(allOf or anyOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "c", allOf)
    }

    @Test
    fun anyOf2_and_allOf2() {
        val anyOf = Permissions.anyOf("a", "b")
        val allOf = Permissions.allOf("a", "c")
        assertThat(anyOf and allOf).isSameInstanceAs(allOf)
        assertThat(allOf.permissions).containsExactly("a", "c", anyOf)
    }

    @Test
    fun anyOf2_or_allOf2() {
        val anyOf = Permissions.anyOf("a", "b")
        val allOf = Permissions.allOf("a", "c")
        assertThat(anyOf or allOf).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b", allOf)
    }

    @Test
    fun anyOf2_and_anyOf2() {
        val anyOf1 = Permissions.anyOf("a", "b")
        val anyOf2 = Permissions.anyOf("a", "c")
        assertThat((anyOf1 and anyOf2).permissions).containsExactly(anyOf1, anyOf2)
    }

    @Test
    fun anyOf2_or_anyOf2() {
        val anyOf = Permissions.anyOf("a", "b")
        assertThat(anyOf or Permissions.anyOf("a", "c")).isSameInstanceAs(anyOf)
        assertThat(anyOf.permissions).containsExactly("a", "b", "c")
    }

    @Test
    fun check_allOf() {
        val permissions = Permissions.allOf("a", "b")
        assertThat(permissions.check(PermissionsContext(setOf()), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("b")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b")), 0, 0)).isTrue()
    }

    @Test
    fun check_allOf_mixed() {
        val permissions = Permissions.allOf("a", "b") or "c"
        assertThat(permissions.permissions).hasSize(2)
        assertThat(permissions.check(PermissionsContext(setOf()), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("b")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("c")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("a", "c")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("b", "c")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b", "c")), 0, 0)).isTrue()
    }

    @Test
    fun check_anyOf() {
        val permissions = Permissions.anyOf("a", "b")
        assertThat(permissions.check(PermissionsContext(setOf()), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("b")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b")), 0, 0)).isTrue()
    }

    @Test
    fun check_anyOf_mixed() {
        val permissions = Permissions.anyOf("a", "b") and "c"
        assertThat(permissions.permissions).hasSize(2)
        assertThat(permissions.check(PermissionsContext(setOf()), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("b")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("c")), 0, 0)).isFalse()
        assertThat(permissions.check(PermissionsContext(setOf("a", "c")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("b", "c")), 0, 0)).isTrue()
        assertThat(permissions.check(PermissionsContext(setOf("a", "b", "c")), 0, 0)).isTrue()
    }

    @Test
    fun equals() {
        assertThat(Permissions.allOf("a")).isEqualTo(Permissions.allOf("a"))
        assertThat(Permissions.allOf("a")).isEqualTo(Permissions.anyOf("a"))
        assertThat(Permissions.anyOf("a")).isEqualTo(Permissions.allOf("a"))
        assertThat(Permissions.anyOf("a")).isEqualTo(Permissions.anyOf("a"))

        assertThat(Permissions.anyOf("a") and "b").isEqualTo(Permissions.allOf("a", "b"))
        assertThat(Permissions.allOf("a") or "b").isEqualTo(Permissions.anyOf("a", "b"))
        assertThat(Permissions.allOf("a") and "a").isEqualTo(Permissions.allOf("a"))
        assertThat(Permissions.anyOf("a") or "a").isEqualTo(Permissions.anyOf("a"))

        assertThat(Permissions.allOf("a", "c") and Permissions.allOf("c", "b"))
            .isEqualTo(Permissions.allOf("a", "b", "c"))
        assertThat(Permissions.anyOf("a", "c") or Permissions.anyOf("c", "b"))
            .isEqualTo(Permissions.anyOf("a", "b", "c"))

        assertThat(Permissions.allOf("a", "c") and Permissions.allOf("c", "b"))
            .isEqualTo(Permissions.allOf("b", "c") and Permissions.allOf("c", "a"))
        assertThat(Permissions.anyOf("a", "c") or Permissions.anyOf("c", "b"))
            .isEqualTo(Permissions.anyOf("b", "c") or Permissions.anyOf("c", "a"))
    }

    @Test
    fun notEquals() {
        assertThat(Permissions.allOf("a")).isNotEqualTo(Permissions.allOf("a", "b"))
        assertThat(Permissions.anyOf("a")).isNotEqualTo(Permissions.anyOf("a", "b"))
        assertThat(Permissions.allOf("a", "b")).isNotEqualTo(Permissions.anyOf("a", "b"))
    }
}

private class PermissionsContext(private val granted: Set<String>) :
    ContextWrapper(ApplicationProvider.getApplicationContext()) {

    override fun checkPermission(permission: String, pid: Int, uid: Int) =
        if (permission in granted) PERMISSION_GRANTED else PERMISSION_DENIED
}
