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
package com.android.wm.shell.windowdecor

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.graphics.Rect
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.SurfaceControl
import android.window.WindowContainerToken
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_RIGHT
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_TOP
import com.android.wm.shell.windowdecor.DragPositioningCallback.CTRL_TYPE_UNDEFINED
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.argThat
import org.mockito.Mockito.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import java.util.function.Supplier
import org.mockito.Mockito.`when` as whenever

/**
 * Tests for [VeiledResizeTaskPositioner].
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:VeiledResizeTaskPositionerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class VeiledResizeTaskPositionerTest : ShellTestCase() {

    @Mock
    private lateinit var mockShellTaskOrganizer: ShellTaskOrganizer
    @Mock
    private lateinit var mockDesktopWindowDecoration: DesktopModeWindowDecoration
    @Mock
    private lateinit var mockDragStartListener: DragPositioningCallbackUtility.DragStartListener

    @Mock
    private lateinit var taskToken: WindowContainerToken
    @Mock
    private lateinit var taskBinder: IBinder

    @Mock
    private lateinit var mockDisplayController: DisplayController
    @Mock
    private lateinit var mockDisplayLayout: DisplayLayout
    @Mock
    private lateinit var mockDisplay: Display
    @Mock
    private lateinit var mockTransactionFactory: Supplier<SurfaceControl.Transaction>
    @Mock
    private lateinit var mockTransaction: SurfaceControl.Transaction

    private lateinit var taskPositioner: VeiledResizeTaskPositioner

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        taskPositioner =
            VeiledResizeTaskPositioner(
                mockShellTaskOrganizer,
                mockDesktopWindowDecoration,
                mockDisplayController,
                DISALLOWED_AREA_FOR_END_BOUNDS,
                mockDragStartListener,
                mockTransactionFactory
            )

        whenever(taskToken.asBinder()).thenReturn(taskBinder)
        whenever(mockDisplayController.getDisplayLayout(DISPLAY_ID)).thenReturn(mockDisplayLayout)
        whenever(mockDisplayLayout.densityDpi()).thenReturn(DENSITY_DPI)
        whenever(mockDisplayLayout.getStableBounds(any())).thenAnswer { i ->
            (i.arguments.first() as Rect).set(STABLE_BOUNDS)
        }
        `when`(mockTransactionFactory.get()).thenReturn(mockTransaction)

        mockDesktopWindowDecoration.mTaskInfo = ActivityManager.RunningTaskInfo().apply {
            taskId = TASK_ID
            token = taskToken
            minWidth = MIN_WIDTH
            minHeight = MIN_HEIGHT
            defaultMinSize = DEFAULT_MIN
            displayId = DISPLAY_ID
            configuration.windowConfiguration.bounds = STARTING_BOUNDS
        }
        mockDesktopWindowDecoration.mDisplay = mockDisplay
        whenever(mockDisplay.displayId).thenAnswer { DISPLAY_ID }
    }

    @Test
    fun testDragResize_noMove_showsResizeVeil() {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )
        verify(mockDesktopWindowDecoration).showResizeVeil()

        taskPositioner.onDragPositioningEnd(
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )
        verify(mockDesktopWindowDecoration).hideResizeVeil()
    }

    @Test
    fun testDragResize_movesTask_doesNotShowResizeVeil() {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_UNDEFINED,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningMove(
            STARTING_BOUNDS.left.toFloat() + 60,
            STARTING_BOUNDS.top.toFloat() + 100
        )
        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.left += 60
        rectAfterMove.right += 60
        rectAfterMove.top += 100
        rectAfterMove.bottom += 100
        verify(mockTransaction).setPosition(any(), eq(rectAfterMove.left.toFloat()),
                eq(rectAfterMove.top.toFloat()))

        taskPositioner.onDragPositioningEnd(
            STARTING_BOUNDS.left.toFloat() + 70,
            STARTING_BOUNDS.top.toFloat() + 20
        )
        val rectAfterEnd = Rect(STARTING_BOUNDS)
        rectAfterEnd.left += 70
        rectAfterEnd.right += 70
        rectAfterEnd.top += 20
        rectAfterEnd.bottom += 20

        verify(mockDesktopWindowDecoration, never()).createResizeVeil()
        verify(mockDesktopWindowDecoration, never()).hideResizeVeil()
        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == rectAfterEnd
            }
        })
    }

    @Test
    fun testDragResize_resize_boundsUpdateOnEnd() {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_RIGHT or CTRL_TYPE_TOP,
            STARTING_BOUNDS.right.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )
        verify(mockDesktopWindowDecoration).showResizeVeil()

        taskPositioner.onDragPositioningMove(
            STARTING_BOUNDS.right.toFloat() + 10,
            STARTING_BOUNDS.top.toFloat() + 10
        )

        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.right += 10
        rectAfterMove.top += 10
        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == rectAfterMove
            }
        })

        taskPositioner.onDragPositioningEnd(
            STARTING_BOUNDS.right.toFloat() + 20,
            STARTING_BOUNDS.top.toFloat() + 20
        )
        val rectAfterEnd = Rect(rectAfterMove)
        rectAfterEnd.right += 10
        rectAfterEnd.top += 10
        verify(mockDesktopWindowDecoration, times(2)).updateResizeVeil(any())
        verify(mockDesktopWindowDecoration).hideResizeVeil()

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == rectAfterEnd
            }
        })
    }

    @Test
    fun testDragResize_noEffectiveMove_skipsTransactionOnEnd() {
        taskPositioner.onDragPositioningStart(
            CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )
        verify(mockDesktopWindowDecoration).showResizeVeil()

        taskPositioner.onDragPositioningMove(
            STARTING_BOUNDS.left.toFloat(),
            STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningEnd(
            STARTING_BOUNDS.left.toFloat() + 10,
            STARTING_BOUNDS.top.toFloat() + 10
        )
        verify(mockDesktopWindowDecoration).hideResizeVeil()

        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)
            }
        })
    }


    @Test
    fun testDragResize_drag_setBoundsNotRunIfDragEndsInDisallowedEndArea() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_UNDEFINED, // drag
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        val newX = STARTING_BOUNDS.left.toFloat() + 5
        val newY = STARTING_BOUNDS.top.toFloat() + 5
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)
            }
        })
    }

    @Test
    fun testDragResize_resize_resizingTaskReorderedToTopWhenNotFocused() {
        mockDesktopWindowDecoration.mTaskInfo.isFocused = false
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT, // Resize right
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Verify task is reordered to top
        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.hierarchyOps.any { hierarchyOps ->
                hierarchyOps.container == taskBinder && hierarchyOps.toTop }
        })
    }

    @Test
    fun testDragResize_resize_resizingTaskNotReorderedToTopWhenFocused() {
        mockDesktopWindowDecoration.mTaskInfo.isFocused = true
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT, // Resize right
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Verify task is not reordered to top
        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.hierarchyOps.any { hierarchyOps ->
                hierarchyOps.container == taskBinder && hierarchyOps.toTop }
        })
    }

    @Test
    fun testDragResize_drag_draggedTaskNotReorderedToTop() {
        mockDesktopWindowDecoration.mTaskInfo.isFocused = false
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_UNDEFINED, // drag
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Verify task is not reordered to top since task is already brought to top before dragging
        // begins
        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.hierarchyOps.any { hierarchyOps ->
                hierarchyOps.container == taskBinder && hierarchyOps.toTop }
        })
    }

    companion object {
        private const val TASK_ID = 5
        private const val MIN_WIDTH = 10
        private const val MIN_HEIGHT = 10
        private const val DENSITY_DPI = 20
        private const val DEFAULT_MIN = 40
        private const val DISPLAY_ID = 1
        private const val NAVBAR_HEIGHT = 50
        private val DISPLAY_BOUNDS = Rect(0, 0, 2400, 1600)
        private val STARTING_BOUNDS = Rect(0, 0, 100, 100)
        private val DISALLOWED_AREA_FOR_END_BOUNDS = Rect(0, 0, 50, 50)
        private val STABLE_BOUNDS = Rect(
            DISPLAY_BOUNDS.left,
            DISPLAY_BOUNDS.top,
            DISPLAY_BOUNDS.right,
            DISPLAY_BOUNDS.bottom - NAVBAR_HEIGHT
        )
    }
}
