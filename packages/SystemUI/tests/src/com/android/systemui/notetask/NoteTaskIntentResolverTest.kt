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

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.test.suitebuilder.annotation.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.systemui.SysuiTestCase
import com.android.systemui.notetask.NoteTaskIntentResolver.Companion.ACTION_CREATE_NOTE
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

    private lateinit var resolver: NoteTaskIntentResolver

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        resolver = NoteTaskIntentResolver(packageManager)
    }

    private fun createResolveInfo(
        activityInfo: ActivityInfo? = createActivityInfo(),
    ): ResolveInfo {
        return ResolveInfo().apply { this.activityInfo = activityInfo }
    }

    private fun createActivityInfo(
        packageName: String = "PackageName",
        name: String? = "ActivityName",
        exported: Boolean = true,
        enabled: Boolean = true,
        showWhenLocked: Boolean = true,
        turnScreenOn: Boolean = true,
    ): ActivityInfo {
        return ActivityInfo().apply {
            this.name = name
            this.exported = exported
            this.enabled = enabled
            if (showWhenLocked) {
                flags = flags or ActivityInfo.FLAG_SHOW_WHEN_LOCKED
            }
            if (turnScreenOn) {
                flags = flags or ActivityInfo.FLAG_TURN_SCREEN_ON
            }
            this.applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
        }
    }

    private fun givenQueryIntentActivities(block: () -> List<ResolveInfo>) {
        whenever(packageManager.queryIntentActivities(any(), any<ResolveInfoFlags>()))
            .thenReturn(block())
    }

    private fun givenResolveActivity(block: () -> ResolveInfo?) {
        whenever(packageManager.resolveActivity(any(), any<ResolveInfoFlags>())).thenReturn(block())
    }

    @Test
    fun resolveIntent_shouldReturnNotesIntent() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity { createResolveInfo(activityInfo = createActivityInfo()) }

        val actual = resolver.resolveIntent()

        val expected =
            Intent(ACTION_CREATE_NOTE)
                .setPackage("PackageName")
                .setComponent(ComponentName("PackageName", "ActivityName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Compares the string representation of both intents, as they are different instances.
        assertThat(actual.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun resolveIntent_activityInfoEnabledIsFalse_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity {
            createResolveInfo(activityInfo = createActivityInfo(enabled = false))
        }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoExportedIsFalse_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity {
            createResolveInfo(activityInfo = createActivityInfo(exported = false))
        }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoShowWhenLockedIsFalse_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity {
            createResolveInfo(activityInfo = createActivityInfo(showWhenLocked = false))
        }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoTurnScreenOnIsFalse_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity {
            createResolveInfo(activityInfo = createActivityInfo(turnScreenOn = false))
        }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoNameIsBlank_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity { createResolveInfo(activityInfo = createActivityInfo(name = "")) }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoNameIsNull_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity { createResolveInfo(activityInfo = createActivityInfo(name = null)) }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityInfoIsNull_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity { createResolveInfo(activityInfo = null) }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_resolveActivityIsNull_shouldReturnNull() {
        givenQueryIntentActivities { listOf(createResolveInfo()) }
        givenResolveActivity { null }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_packageNameIsBlank_shouldReturnNull() {
        givenQueryIntentActivities {
            listOf(createResolveInfo(createActivityInfo(packageName = "")))
        }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }

    @Test
    fun resolveIntent_activityNotFoundForAction_shouldReturnNull() {
        givenQueryIntentActivities { emptyList() }

        val actual = resolver.resolveIntent()

        assertThat(actual).isNull()
    }
}
