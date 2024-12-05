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
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import java.util.function.Consumer
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
    }
}
