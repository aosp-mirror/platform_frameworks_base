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

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskIntentResolver.Companion.ACTION_CREATE_NOTE
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.MockitoAnnotations

/**
 * Tests for [NoteTaskIntentResolver].
 *
 * Build/Install/Run:
 * - atest SystemUITests:NoteTaskIntentResolverTest
 */
@SmallTest
@RunWith(AndroidJUnit4::class)
internal class NoteTaskIntentResolverTest : SysuiTestCase() {

    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var roleManager: RoleManager

    private lateinit var underTest: NoteTaskIntentResolver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest = NoteTaskIntentResolver(context, roleManager)
    }

    @Test
    fun resolveIntent_shouldReturnIntentInStylusMode() {
        val packageName = "com.android.note.app"
        whenever(roleManager.getRoleHoldersAsUser(NoteTaskIntentResolver.ROLE_NOTES, context.user))
            .then { listOf(packageName) }

        val actual = underTest.resolveIntent()

        requireNotNull(actual) { "Intent must not be null" }
        assertThat(actual.action).isEqualTo(ACTION_CREATE_NOTE)
        assertThat(actual.`package`).isEqualTo(packageName)
        val expectedExtra = actual.getExtra(NoteTaskIntentResolver.INTENT_EXTRA_USE_STYLUS_MODE)
        assertThat(expectedExtra).isEqualTo(true)
        val expectedFlag = actual.flags and Intent.FLAG_ACTIVITY_NEW_TASK
        assertThat(expectedFlag).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun resolveIntent_noRoleHolderIsSet_shouldReturnNull() {
        whenever(roleManager.getRoleHoldersAsUser(eq(NoteTaskIntentResolver.ROLE_NOTES), any()))
            .then { listOf<String>() }

        val actual = underTest.resolveIntent()

        assertThat(actual).isNull()
    }
}
