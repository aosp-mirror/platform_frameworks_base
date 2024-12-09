/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.content.Context
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.SurfaceControl
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.compatui.letterbox.LetterboxUtils.Maps.runOnItem
import com.android.wm.shell.compatui.letterbox.LetterboxUtils.Transactions.moveAndCrop
import java.util.function.Consumer
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify

/**
 * Tests for [LetterboxUtils].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:LetterboxUtilsTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxUtilsTest : ShellTestCase() {

    val firstLetterboxController = mock<LetterboxController>()
    val secondLetterboxController = mock<LetterboxController>()
    val thirdLetterboxController = mock<LetterboxController>()

    private val letterboxControllerBuilder: (LetterboxSurfaceBuilder) -> LetterboxController =
        { _ ->
            firstLetterboxController.append(secondLetterboxController)
                .append(thirdLetterboxController)
        }

    @Test
    fun `Appended LetterboxController invoked creation on all the controllers`() {
        runTestScenario { r ->
            r.sendCreateSurfaceRequest()

            r.verifyCreateSurfaceInvokedWithRequest(target = firstLetterboxController)
            r.verifyCreateSurfaceInvokedWithRequest(target = secondLetterboxController)
            r.verifyCreateSurfaceInvokedWithRequest(target = thirdLetterboxController)
        }
    }

    @Test
    fun `Appended LetterboxController invoked destroy on all the controllers`() {
        runTestScenario { r ->
            r.sendDestroySurfaceRequest()
            r.verifyDestroySurfaceInvokedWithRequest(target = firstLetterboxController)
            r.verifyDestroySurfaceInvokedWithRequest(target = secondLetterboxController)
            r.verifyDestroySurfaceInvokedWithRequest(target = thirdLetterboxController)
        }
    }

    @Test
    fun `Appended LetterboxController invoked update visibility on all the controllers`() {
        runTestScenario { r ->
            r.sendUpdateSurfaceVisibilityRequest(visible = true)
            r.verifyUpdateVisibilitySurfaceInvokedWithRequest(target = firstLetterboxController)
            r.verifyUpdateVisibilitySurfaceInvokedWithRequest(target = secondLetterboxController)
            r.verifyUpdateVisibilitySurfaceInvokedWithRequest(target = thirdLetterboxController)
        }
    }

    @Test
    fun `Appended LetterboxController invoked update bounds on all the controllers`() {
        runTestScenario { r ->
            r.sendUpdateSurfaceBoundsRequest(taskBounds = Rect(), activityBounds = Rect())
            r.verifyUpdateSurfaceBoundsInvokedWithRequest(target = firstLetterboxController)
            r.verifyUpdateSurfaceBoundsInvokedWithRequest(target = secondLetterboxController)
            r.verifyUpdateSurfaceBoundsInvokedWithRequest(target = thirdLetterboxController)
        }
    }

    @Test
    fun `Appended LetterboxController invoked update dump on all the controllers`() {
        runTestScenario { r ->
            r.invokeDump()
            r.verifyDumpInvoked(target = firstLetterboxController)
            r.verifyDumpInvoked(target = secondLetterboxController)
            r.verifyDumpInvoked(target = thirdLetterboxController)
        }
    }

    @Test
    fun `runOnItem executes onFound when an item has been found for a key`() {
        runTestScenario { r ->
            r.initMap(1 to 2, 3 to 4)
            r.runOnItem<Int>(1)
            r.verifyOnItemInvoked(expectedItem = 2)
            r.verifyOnMissingNotInvoked()
        }
    }

    @Test
    fun `runOnItem executes onMissing when an item has not been found for a key`() {
        runTestScenario { r ->
            r.initMap(1 to 2, 3 to 4)
            r.runOnItem<Int>(8)
            r.verifyOnItemNotInvoked()
            r.verifyOnMissingInvoked(expectedKey = 8)
        }
    }

    @Test
    fun `moveAndCrop invoked Move and then Crop`() {
        runTestScenario { r ->
            r.invoke(Rect(1, 2, 51, 62))
            r.verifySetPosition(expectedX = 1f, expectedY = 2f)
            r.verifySetWindowCrop(expectedWidth = 50, expectedHeight = 60)
        }
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    fun runTestScenario(consumer: Consumer<AppendLetterboxControllerRobotTest>) {
        val robot = AppendLetterboxControllerRobotTest(mContext, letterboxControllerBuilder)
        consumer.accept(robot)
    }

    class AppendLetterboxControllerRobotTest(
        ctx: Context,
        builder: (LetterboxSurfaceBuilder) -> LetterboxController
    ) : LetterboxControllerRobotTest(ctx, builder) {

        private var testableMap = mutableMapOf<Int, Int>()
        private var onItemState: Int? = null
        private var onMissingStateKey: Int? = null
        private var onMissingStateMap: MutableMap<Int, Int>? = null

        private val transaction = getTransactionMock()
        private val surface = SurfaceControl()

        fun verifyCreateSurfaceInvokedWithRequest(
            target: LetterboxController,
            times: Int = 1
        ) {
            verify(target, times(times)).createLetterboxSurface(any(), any(), any())
        }

        fun verifyDestroySurfaceInvokedWithRequest(
            target: LetterboxController,
            times: Int = 1
        ) {
            verify(target, times(times)).destroyLetterboxSurface(any(), any())
        }

        fun verifyUpdateVisibilitySurfaceInvokedWithRequest(
            target: LetterboxController,
            times: Int = 1
        ) {
            verify(target, times(times)).updateLetterboxSurfaceVisibility(any(), any(), any())
        }

        fun verifyUpdateSurfaceBoundsInvokedWithRequest(
            target: LetterboxController,
            times: Int = 1
        ) {
            verify(target, times(times)).updateLetterboxSurfaceBounds(any(), any(), any(), any())
        }

        fun verifyDumpInvoked(
            target: LetterboxController,
            times: Int = 1
        ) {
            verify(target, times(times)).dump()
        }

        fun initMap(vararg values: Pair<Int, Int>) = testableMap.putAll(values.toMap())

        fun <T> runOnItem(key: Int) {
            testableMap.runOnItem(key, onFound = { item ->
                onItemState = item
            }, onMissed = { k, m ->
                onMissingStateKey = k
                onMissingStateMap = m
            })
        }

        fun verifyOnItemInvoked(expectedItem: Int) {
            assertEquals(expectedItem, onItemState)
        }

        fun verifyOnItemNotInvoked() {
            assertNull(onItemState)
        }

        fun verifyOnMissingInvoked(expectedKey: Int) {
            assertEquals(expectedKey, onMissingStateKey)
            assertEquals(onMissingStateMap, testableMap)
        }

        fun verifyOnMissingNotInvoked() {
            assertNull(onMissingStateKey)
            assertNull(onMissingStateMap)
        }

        fun invoke(rect: Rect) {
            transaction.moveAndCrop(surface, rect)
        }

        fun verifySetPosition(expectedX: Float, expectedY: Float) {
            verify(transaction).setPosition(surface, expectedX, expectedY)
        }

        fun verifySetWindowCrop(expectedWidth: Int, expectedHeight: Int) {
            verify(transaction).setWindowCrop(surface, expectedWidth, expectedHeight)
        }
    }
}
