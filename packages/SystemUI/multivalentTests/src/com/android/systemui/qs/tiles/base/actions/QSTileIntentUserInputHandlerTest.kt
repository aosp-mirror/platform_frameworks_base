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

package com.android.systemui.qs.tiles.base.actions

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.content.pm.ResolveInfo
import android.os.UserHandle
import android.platform.test.annotations.EnabledOnRavenwood
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@EnabledOnRavenwood
@RunWith(AndroidJUnit4::class)
class QSTileIntentUserInputHandlerTest : SysuiTestCase() {
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var activityStarter: ActivityStarter

    lateinit var underTest: QSTileIntentUserInputHandler

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest = QSTileIntentUserInputHandlerImpl(activityStarter, packageManager, user)
    }

    @Test
    fun testPassesIntentToStarter() {
        val intent = Intent("test.ACTION")

        underTest.handle(null, intent)

        verify(activityStarter).postStartActivityDismissingKeyguard(eq(intent), eq(0), any())
    }

    @Test
    fun testPassesActivityPendingIntentToStarterAsPendingIntent() {
        val pendingIntent = mock<PendingIntent> { whenever(isActivity).thenReturn(true) }

        underTest.handle(null, pendingIntent, true)

        verify(activityStarter).postStartActivityDismissingKeyguard(eq(pendingIntent), any())
    }

    @Test
    fun testPassesActivityPendingIntentToStarterAsPendingIntentWhenNotRequestingActivityStart() {
        val pendingIntent = mock<PendingIntent> { whenever(isActivity).thenReturn(true) }

        underTest.handle(null, pendingIntent, false)

        verify(activityStarter).postStartActivityDismissingKeyguard(eq(pendingIntent), any())
    }

    @Test
    fun testPassNonActivityPendingIntentAndRequestStartingActivity_findsIntentAndStarts() {
        val pendingIntent =
            mock<PendingIntent> {
                whenever(isActivity).thenReturn(false)
                whenever(creatorPackage).thenReturn(ORIGINAL_PACKAGE)
            }
        setUpQueryResult(listOf(createActivityInfo(testResolvedComponent, exported = true)))

        underTest.handle(null, pendingIntent, true)

        val expectedIntent =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(null)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                .setComponent(testResolvedComponent)

        verify(activityStarter)
            .postStartActivityDismissingKeyguard(
                argThat(IntentMatcher(expectedIntent)),
                eq(0),
                any()
            )
    }

    @Test
    fun testPassNonActivityPendingIntentAndDoNotRequestStartingActivity_doesNotStartActivity() {
        val pendingIntent = mock<PendingIntent> { whenever(isActivity).thenReturn(false) }

        underTest.handle(null, pendingIntent, false)

        verify(activityStarter, never())
            .postStartActivityDismissingKeyguard(any(Intent::class.java), eq(0), any())
    }

    private fun createActivityInfo(
        componentName: ComponentName,
        exported: Boolean = false,
    ): ActivityInfo {
        return ActivityInfo().apply {
            packageName = componentName.packageName
            name = componentName.className
            this.exported = exported
        }
    }

    private fun setUpQueryResult(infos: List<ActivityInfo>) {
        `when`(
                packageManager.queryIntentActivitiesAsUser(
                    any(Intent::class.java),
                    any(ResolveInfoFlags::class.java),
                    eq(user.identifier)
                )
            )
            .thenReturn(infos.map { ResolveInfo().apply { activityInfo = it } })
    }

    private class IntentMatcher(intent: Intent) : ArgumentMatcher<Intent> {
        private val expectedIntent = intent
        override fun matches(argument: Intent?): Boolean {
            return argument?.action.equals(expectedIntent.action) &&
                argument?.`package`.equals(expectedIntent.`package`) &&
                argument?.component?.equals(expectedIntent.component)!! &&
                argument?.categories?.equals(expectedIntent.categories)!! &&
                argument?.flags?.equals(expectedIntent.flags)!!
        }
    }

    companion object {
        private const val ORIGINAL_PACKAGE = "original_pkg"
        private const val TEST_PACKAGE = "test_pkg"
        private const val TEST_COMPONENT_CLASS_NAME = "test_component_class_name"
        private val testResolvedComponent = ComponentName(TEST_PACKAGE, TEST_COMPONENT_CLASS_NAME)
        private val user = UserHandle.of(0)
    }
}
