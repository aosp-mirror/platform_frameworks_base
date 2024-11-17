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

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.view.WindowManager.TRANSIT_CLOSE
import androidx.test.filters.SmallTest
import com.android.window.flags.Flags
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.util.TransitionObserverInputBuilder
import com.android.wm.shell.util.executeTransitionObserverTest
import java.util.function.Consumer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.anyOrNull
import org.mockito.verification.VerificationMode

/**
 * Tests for [LetterboxTransitionObserver].
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:LetterboxTransitionObserverTest
 */
@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxTransitionObserverTest : ShellTestCase() {

    @get:Rule
    val setFlagsRule: SetFlagsRule = SetFlagsRule()

    @Test
    @DisableFlags(Flags.FLAG_APP_COMPAT_REFACTORING)
    fun `when initialized and flag disabled the observer is not registered`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()
                r.checkObservableIsRegistered(expected = false)
            }
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_APP_COMPAT_REFACTORING)
    fun `when initialized and flag enabled the observer is registered`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()
                r.checkObservableIsRegistered(expected = true)
            }
        }
    }

    @Test
    fun `LetterboxController not used without TaskInfos in Change`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()

                inputBuilder {
                    buildTransitionInfo()
                    addChange(createChange())
                    addChange(createChange())
                    addChange(createChange())
                }

                validateOutput {
                    r.creationEventDetected(expected = false)
                    r.visibilityEventDetected(expected = false)
                    r.destroyEventDetected(expected = false)
                    r.boundsEventDetected(expected = false)
                }
            }
        }
    }

    @Test
    fun `When a topActivity is letterboxed surfaces creation is requested`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()

                inputBuilder {
                    buildTransitionInfo()
                    r.createTopActivityChange(inputBuilder = this, isLetterboxed = true)
                }

                validateOutput {
                    r.creationEventDetected(expected = true)
                    r.visibilityEventDetected(expected = true, visible = true)
                    r.destroyEventDetected(expected = false)
                    r.boundsEventDetected(expected = true)
                }
            }
        }
    }

    @Test
    fun `When a topActivity is not letterboxed visibility is updated`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()

                inputBuilder {
                    buildTransitionInfo()
                    r.createTopActivityChange(inputBuilder = this, isLetterboxed = false)
                }

                validateOutput {
                    r.creationEventDetected(expected = false)
                    r.visibilityEventDetected(expected = true, visible = false)
                    r.destroyEventDetected(expected = false)
                    r.boundsEventDetected(expected = false)
                }
            }
        }
    }

    @Test
    fun `When closing change letterbox surface destroy is triggered`() {
        runTestScenario { r ->
            executeTransitionObserverTest(observerFactory = r.observerFactory) {
                r.invokeShellInit()

                inputBuilder {
                    buildTransitionInfo()
                    r.createClosingChange(inputBuilder = this)
                }

                validateOutput {
                    r.destroyEventDetected(expected = true)
                    r.creationEventDetected(expected = false)
                    r.visibilityEventDetected(expected = false, visible = false)
                    r.boundsEventDetected(expected = false)
                }
            }
        }
    }

    /**
     * Runs a test scenario providing a Robot.
     */
    fun runTestScenario(consumer: Consumer<LetterboxTransitionObserverRobotTest>) {
        val robot = LetterboxTransitionObserverRobotTest()
        consumer.accept(robot)
    }

    class LetterboxTransitionObserverRobotTest {

        companion object {
            @JvmStatic
            private val DISPLAY_ID = 1

            @JvmStatic
            private val TASK_ID = 20
        }

        private val executor: ShellExecutor
        private val shellInit: ShellInit
        private val transitions: Transitions
        private val letterboxController: LetterboxController
        private val letterboxObserver: LetterboxTransitionObserver

        val observerFactory: () -> LetterboxTransitionObserver

        init {
            executor = Mockito.mock(ShellExecutor::class.java)
            shellInit = ShellInit(executor)
            transitions = Mockito.mock(Transitions::class.java)
            letterboxController = Mockito.mock(LetterboxController::class.java)
            letterboxObserver =
                LetterboxTransitionObserver(shellInit, transitions, letterboxController)
            observerFactory = { letterboxObserver }
        }

        fun invokeShellInit() = shellInit.init()

        fun observer() = letterboxObserver

        fun checkObservableIsRegistered(expected: Boolean) {
            Mockito.verify(transitions, expected.asMode()).registerObserver(observer())
        }

        fun creationEventDetected(
            expected: Boolean,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID
        ) {
            Mockito.verify(letterboxController, expected.asMode()).createLetterboxSurface(
                toLetterboxKeyMatcher(displayId, taskId),
                anyOrNull(),
                anyOrNull()
            )
        }

        fun visibilityEventDetected(
            expected: Boolean,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID,
            visible: Boolean? = null
        ) {
            Mockito.verify(letterboxController, expected.asMode()).updateLetterboxSurfaceVisibility(
                toLetterboxKeyMatcher(displayId, taskId),
                anyOrNull(),
                visible.asMatcher()
            )
        }

        fun destroyEventDetected(
            expected: Boolean,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID
        ) {
            Mockito.verify(letterboxController, expected.asMode()).destroyLetterboxSurface(
                toLetterboxKeyMatcher(displayId, taskId),
                anyOrNull()
            )
        }

        fun boundsEventDetected(
            expected: Boolean,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID
        ) {
            Mockito.verify(letterboxController, expected.asMode()).updateLetterboxSurfaceBounds(
                toLetterboxKeyMatcher(displayId, taskId),
                anyOrNull(),
                anyOrNull()
            )
        }

        fun createTopActivityChange(
            inputBuilder: TransitionObserverInputBuilder,
            isLetterboxed: Boolean = true,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID
        ) {
            inputBuilder.addChange(changeTaskInfo = inputBuilder.createTaskInfo().apply {
                appCompatTaskInfo.isTopActivityLetterboxed = isLetterboxed
                this.taskId = taskId
                this.displayId = displayId
            })
        }

        fun createClosingChange(
            inputBuilder: TransitionObserverInputBuilder,
            displayId: Int = DISPLAY_ID,
            taskId: Int = TASK_ID
        ) {
            inputBuilder.addChange(changeTaskInfo = inputBuilder.createTaskInfo().apply {
                this.taskId = taskId
                this.displayId = displayId
            }, changeMode = TRANSIT_CLOSE)
        }

        private fun Boolean.asMode(): VerificationMode = if (this) times(1) else never()

        private fun Boolean?.asMatcher(): Boolean =
            if (this != null) eq(this) else any()

        private fun toLetterboxKeyMatcher(displayId: Int, taskId: Int): LetterboxKey {
            if (displayId < 0 || taskId < 0) {
                return any()
            } else {
                return eq(LetterboxKey(displayId, taskId))
            }
        }
    }
}
