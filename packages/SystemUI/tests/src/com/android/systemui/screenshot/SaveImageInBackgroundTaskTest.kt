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
import com.android.systemui.screenshot.ScreenshotController.SaveImageInBackgroundData
import com.android.systemui.screenshot.ScreenshotNotificationSmartActionsProvider.ScreenshotSmartActionType
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@SmallTest
class SaveImageInBackgroundTaskTest : SysuiTestCase() {
    private val imageExporter = mock<ImageExporter>()
    private val smartActions = mock<ScreenshotSmartActions>()
    private val saveImageData = SaveImageInBackgroundData()
    private val sharedTransitionSupplier =
        mock<Supplier<ScreenshotController.SavedImageData.ActionTransition>>()
    private val testScreenshotId: String = "testScreenshotId"
    private val testBitmap = mock<Bitmap>()
    private val testUser = UserHandle.getUserHandleForUid(0)
    private val testIcon = mock<Icon>()
    private val testImageTime = 1234.toLong()

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
            imageExporter,
            smartActions,
            saveImageData,
            sharedTransitionSupplier,
            false, // forces a no-op implementation; we're mocking out the behavior anyway
        )

    @Before
    fun setup() {
        Mockito.`when`(
                smartActions.getSmartActionsFuture(
                    Mockito.eq(testScreenshotId),
                    Mockito.any(Uri::class.java),
                    Mockito.eq(testBitmap),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.any(ScreenshotSmartActionType::class.java),
                    Mockito.any(Boolean::class.java),
                    Mockito.eq(testUser)
                )
            )
            .thenReturn(smartActionsUriFuture)
        Mockito.`when`(
                smartActions.getSmartActionsFuture(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(null),
                    Mockito.eq(testBitmap),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.any(ScreenshotSmartActionType::class.java),
                    Mockito.any(Boolean::class.java),
                    Mockito.eq(testUser)
                )
            )
            .thenReturn(smartActionsFuture)
    }

    @Test
    fun testQueryQuickShare_noAction() {
        Mockito.`when`(
                smartActions.getSmartActions(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(smartActionsFuture),
                    Mockito.any(Int::class.java),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
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
        Mockito.`when`(
                smartActions.getSmartActions(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(smartActionsUriFuture),
                    Mockito.any(Int::class.java),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
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
        Mockito.`when`(
                smartActions.getSmartActions(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(smartActionsUriFuture),
                    Mockito.any(Int::class.java),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
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
        Mockito.`when`(
                smartActions.getSmartActions(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(smartActionsUriFuture),
                    Mockito.any(Int::class.java),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
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
        val quickSharePendingIntent : PendingIntent =
            quickShareAction.actionIntent.intent.extras!!.getParcelable(
                ScreenshotController.EXTRA_ACTION_INTENT)!!

        assertEquals("Test Action", quickShareAction.title)
        assertEquals(mutablePendingIntent, quickSharePendingIntent)
    }

    @Test
    fun testCreateQuickShareAction_immutableIntent_returnsSafeIntent() {
        val actions = ArrayList<Notification.Action>()
        val action = constructAction("Test Action", immutablePendingIntent)
        actions.add(action)
        Mockito.`when`(
                smartActions.getSmartActions(
                    Mockito.eq(testScreenshotId),
                    Mockito.eq(smartActionsUriFuture),
                    Mockito.any(Int::class.java),
                    Mockito.any(ScreenshotNotificationSmartActionsProvider::class.java),
                    Mockito.eq(ScreenshotSmartActionType.QUICK_SHARE_ACTION)
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
        val quickSharePendingIntent : PendingIntent =
            quickShareAction.actionIntent.intent.extras!!.getParcelable(
                ScreenshotController.EXTRA_ACTION_INTENT)!!

        assertEquals("Test Action", quickShareAction.title)
        assertEquals(immutablePendingIntent, quickSharePendingIntent)
    }

    private fun constructAction(title: String, intent: PendingIntent): Notification.Action {
        return Notification.Action.Builder(testIcon, title, intent).build()
    }

    inline fun <reified T : Any> mock(apply: T.() -> Unit = {}): T =
        Mockito.mock(T::class.java).apply(apply)
}
