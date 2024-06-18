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

package com.android.systemui.screenshot

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.UserHandle
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.screenshot.ScreenshotController.SaveImageInBackgroundData
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@SmallTest
class SaveImageInBackgroundTaskTest : SysuiTestCase() {
    private val imageExporter = mock<ImageExporter>()
    private val smartActions = mock<ScreenshotSmartActions>()
    private val smartActionsProvider = mock<ScreenshotNotificationSmartActionsProvider>()
    private val saveImageData = SaveImageInBackgroundData()
    private val testScreenshotId: String = "testScreenshotId"
    private val testBitmap = mock<Bitmap>()
    private val testUser = UserHandle.getUserHandleForUid(0)
    private val testIcon = mock<Icon>()
    private val testImageTime = 1234.toLong()
    private val flags = FakeFeatureFlags()

    private val smartActionsUriFuture = mock<CompletableFuture<List<Notification.Action>>>()
    private val smartActionsFuture = mock<CompletableFuture<List<Notification.Action>>>()

    private val testUri: Uri = Uri.parse("testUri")
    private val intent =
        Intent(Intent.ACTION_SEND)
            .setComponent(
                ComponentName.unflattenFromString(
                    "com.google.android.test/com.google.android.test.TestActivity"
                )
            )
    private val immutablePendingIntent =
        PendingIntent.getBroadcast(
            mContext,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    private val mutablePendingIntent =
        PendingIntent.getBroadcast(
            mContext,
            0,
            intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    private val saveImageTask =
        SaveImageInBackgroundTask(
            mContext,
            flags,
            imageExporter,
            smartActions,
            saveImageData,
            smartActionsProvider,
        )

    @Before
    fun setup() {
        whenever(
                smartActions.getSmartActionsFuture(
                    eq(testScreenshotId),
                    any(Uri::class.java),
                    eq(testBitmap),
                    eq(smartActionsProvider),
                    any(ScreenshotSmartActionType::class.java),
                    any(Boolean::class.java),
                    eq(testUser)
                )
            )
            .thenReturn(smartActionsUriFuture)
        whenever(
                smartActions.getSmartActionsFuture(
                    eq(testScreenshotId),
                    eq(null),
                    eq(testBitmap),
                    eq(smartActionsProvider),
                    any(ScreenshotSmartActionType::class.java),
                    any(Boolean::class.java),
                    eq(testUser)
                )
            )
            .thenReturn(smartActionsFuture)
    }

    @Test
    fun testQueryQuickShare_noAction() {
        whenever(
                smartActions.getSmartActions(
                    eq(testScreenshotId),
                    eq(smartActionsFuture),
                    any(Int::class.java),
                    eq(smartActionsProvider),
                    eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
                )
            )
            .thenReturn(ArrayList<Notification.Action>())

        val quickShareAction =
            saveImageTask.queryQuickShareAction(testScreenshotId, testBitmap, testUser, testUri)

        assertNull(quickShareAction)
    }

    @Test
    fun testQueryQuickShare_withActions() {
        val actions = ArrayList<Notification.Action>()
        actions.add(constructAction("Action One", mutablePendingIntent))
        actions.add(constructAction("Action Two", mutablePendingIntent))
        whenever(
                smartActions.getSmartActions(
                    eq(testScreenshotId),
                    eq(smartActionsUriFuture),
                    any(Int::class.java),
                    eq(smartActionsProvider),
                    eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
                )
            )
            .thenReturn(actions)

        val quickShareAction =
            saveImageTask.queryQuickShareAction(testScreenshotId, testBitmap, testUser, testUri)!!

        assertEquals("Action One", quickShareAction.title)
        assertEquals(mutablePendingIntent, quickShareAction.actionIntent)
    }

    @Test
    fun testCreateQuickShareAction_originalWasNull_returnsNull() {
        val quickShareAction =
            saveImageTask.createQuickShareAction(
                null,
                testScreenshotId,
                testUri,
                testImageTime,
                testBitmap,
                testUser
            )

        assertNull(quickShareAction)
    }

    @Test
    fun testCreateQuickShareAction_immutableIntentDifferentAction_returnsNull() {
        val actions = ArrayList<Notification.Action>()
        actions.add(constructAction("New Test Action", immutablePendingIntent))
        whenever(
                smartActions.getSmartActions(
                    eq(testScreenshotId),
                    eq(smartActionsUriFuture),
                    any(Int::class.java),
                    eq(smartActionsProvider),
                    eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
                )
            )
            .thenReturn(actions)
        val origAction = constructAction("Old Test Action", immutablePendingIntent)

        val quickShareAction =
            saveImageTask.createQuickShareAction(
                origAction,
                testScreenshotId,
                testUri,
                testImageTime,
                testBitmap,
                testUser,
            )

        assertNull(quickShareAction)
    }

    @Test
    fun testCreateQuickShareAction_mutableIntent_returnsSafeIntent() {
        val actions = ArrayList<Notification.Action>()
        val action = constructAction("Action One", mutablePendingIntent)
        actions.add(action)
        whenever(
                smartActions.getSmartActions(
                    eq(testScreenshotId),
                    eq(smartActionsUriFuture),
                    any(Int::class.java),
                    eq(smartActionsProvider),
                    eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
                )
            )
            .thenReturn(actions)

        val quickShareAction =
            saveImageTask.createQuickShareAction(
                constructAction("Test Action", mutablePendingIntent),
                testScreenshotId,
                testUri,
                testImageTime,
                testBitmap,
                testUser
            )
        val quickSharePendingIntent =
            quickShareAction.actionIntent.intent.extras!!.getParcelable(
                ScreenshotController.EXTRA_ACTION_INTENT,
                PendingIntent::class.java
            )

        assertEquals("Test Action", quickShareAction.title)
        assertEquals(mutablePendingIntent, quickSharePendingIntent)
    }

    @Test
    fun testCreateQuickShareAction_immutableIntent_returnsSafeIntent() {
        val actions = ArrayList<Notification.Action>()
        val action = constructAction("Test Action", immutablePendingIntent)
        actions.add(action)
        whenever(
                smartActions.getSmartActions(
                    eq(testScreenshotId),
                    eq(smartActionsUriFuture),
                    any(Int::class.java),
                    eq(smartActionsProvider),
                    eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
                )
            )
            .thenReturn(actions)

        val quickShareAction =
            saveImageTask.createQuickShareAction(
                constructAction("Test Action", immutablePendingIntent),
                testScreenshotId,
                testUri,
                testImageTime,
                testBitmap,
                testUser,
            )!!

        assertEquals("Test Action", quickShareAction.title)
        assertEquals(
            immutablePendingIntent,
            quickShareAction.actionIntent.intent.extras!!.getParcelable(
                ScreenshotController.EXTRA_ACTION_INTENT,
                PendingIntent::class.java
            )
        )
    }

    private fun constructAction(title: String, intent: PendingIntent): Notification.Action {
        return Notification.Action.Builder(testIcon, title, intent).build()
    }
}
