package com.android.wm.shell.windowdecor

import android.app.ActivityManager
import android.app.WindowConfiguration
import android.graphics.Rect
import android.os.IBinder
import android.testing.AndroidTestingRunner
import android.window.WindowContainerToken
import android.window.WindowContainerTransaction.Change.CHANGE_DRAG_RESIZING
import androidx.test.filters.SmallTest
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.windowdecor.TaskPositioner.CTRL_TYPE_RIGHT
import com.android.wm.shell.windowdecor.TaskPositioner.CTRL_TYPE_TOP
import com.android.wm.shell.windowdecor.TaskPositioner.CTRL_TYPE_UNDEFINED
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.argThat
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

/**
 * Tests for [TaskPositioner].
 *
 * Build/Install/Run:
 * atest WMShellUnitTests:TaskPositionerTest
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
class TaskPositionerTest : ShellTestCase() {

    @Mock
    private lateinit var mockShellTaskOrganizer: ShellTaskOrganizer
    @Mock
    private lateinit var mockWindowDecoration: WindowDecoration<*>
    @Mock
    private lateinit var mockDragStartListener: TaskPositioner.DragStartListener

    @Mock
    private lateinit var taskToken: WindowContainerToken
    @Mock
    private lateinit var taskBinder: IBinder

    @Mock
    private lateinit var mockDisplayController: DisplayController
    @Mock
    private lateinit var mockDisplayLayout: DisplayLayout

    private lateinit var taskPositioner: TaskPositioner

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        taskPositioner = TaskPositioner(
                mockShellTaskOrganizer,
                mockWindowDecoration,
                mockDisplayController,
                mockDragStartListener
        )

        `when`(taskToken.asBinder()).thenReturn(taskBinder)
        `when`(mockDisplayController.getDisplayLayout(DISPLAY_ID)).thenReturn(mockDisplayLayout)
        `when`(mockDisplayLayout.densityDpi()).thenReturn(DENSITY_DPI)

        mockWindowDecoration.mTaskInfo = ActivityManager.RunningTaskInfo().apply {
            taskId = TASK_ID
            token = taskToken
            minWidth = MIN_WIDTH
            minHeight = MIN_HEIGHT
            defaultMinSize = DEFAULT_MIN
            displayId = DISPLAY_ID
            configuration.windowConfiguration.bounds = STARTING_BOUNDS
        }
    }

    @Test
    fun testDragResize_notMove_skipsTransactionOnEnd() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningEnd(
                STARTING_BOUNDS.left.toFloat() + 10,
                STARTING_BOUNDS.top.toFloat() + 10
        )

        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)
            }
        })
    }

    @Test
    fun testDragResize_noEffectiveMove_skipsTransactionOnMoveAndEnd() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningMove(
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningEnd(
                STARTING_BOUNDS.left.toFloat() + 10,
                STARTING_BOUNDS.top.toFloat() + 10
        )

        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)
            }
        })
    }

    @Test
    fun testDragResize_hasEffectiveMove_issuesTransactionOnMoveAndEnd() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_TOP or CTRL_TYPE_RIGHT,
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        taskPositioner.onDragPositioningMove(
                STARTING_BOUNDS.left.toFloat() + 10,
                STARTING_BOUNDS.top.toFloat()
        )
        val rectAfterMove = Rect(STARTING_BOUNDS)
        rectAfterMove.right += 10
        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == rectAfterMove
            }
        })

        taskPositioner.onDragPositioningEnd(
                STARTING_BOUNDS.left.toFloat() + 10,
                STARTING_BOUNDS.top.toFloat() + 10
        )
        val rectAfterEnd = Rect(rectAfterMove)
        rectAfterEnd.top += 10
        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        (change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0 &&
                        change.configuration.windowConfiguration.bounds == rectAfterEnd
            }
        })
    }

    @Test
    fun testDragResize_move_skipsDragResizingFlag() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_UNDEFINED, // Move
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Move the task 10px to the right.
        val newX = STARTING_BOUNDS.left.toFloat() + 10
        val newY = STARTING_BOUNDS.top.toFloat()
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer, never()).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.changeMask and CHANGE_DRAG_RESIZING) != 0) &&
                        change.dragResizing
            }
        })
    }

    @Test
    fun testDragResize_resize_setsDragResizingFlag() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT, // Resize right
                STARTING_BOUNDS.left.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Resize the task by 10px to the right.
        val newX = STARTING_BOUNDS.right.toFloat() + 10
        val newY = STARTING_BOUNDS.top.toFloat()
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.changeMask and CHANGE_DRAG_RESIZING) != 0) &&
                        change.dragResizing
            }
        })
        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.changeMask and CHANGE_DRAG_RESIZING) != 0) &&
                        !change.dragResizing
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsDoesNotChangeHeightWhenLessThanMin() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Resize to width of 95px and height of 5px with min width of 10px
        val newX = STARTING_BOUNDS.right.toFloat() - 5
        val newY = STARTING_BOUNDS.top.toFloat() + 95
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                != 0) && change.configuration.windowConfiguration.bounds.top ==
                        STARTING_BOUNDS.top &&
                        change.configuration.windowConfiguration.bounds.bottom ==
                        STARTING_BOUNDS.bottom
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsDoesNotChangeWidthWhenLessThanMin() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Resize to height of 95px and width of 5px with min width of 10px
        val newX = STARTING_BOUNDS.right.toFloat() - 95
        val newY = STARTING_BOUNDS.top.toFloat() + 5
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                != 0) && change.configuration.windowConfiguration.bounds.right ==
                        STARTING_BOUNDS.right &&
                        change.configuration.windowConfiguration.bounds.left ==
                        STARTING_BOUNDS.left
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsDoesNotChangeHeightWhenNegative() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Resize to height of -5px and width of 95px
        val newX = STARTING_BOUNDS.right.toFloat() - 5
        val newY = STARTING_BOUNDS.top.toFloat() + 105
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                != 0) && change.configuration.windowConfiguration.bounds.top ==
                        STARTING_BOUNDS.top &&
                        change.configuration.windowConfiguration.bounds.bottom ==
                        STARTING_BOUNDS.bottom
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsDoesNotChangeWidthWhenNegative() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Resize to width of -5px and height of 95px
        val newX = STARTING_BOUNDS.right.toFloat() - 105
        val newY = STARTING_BOUNDS.top.toFloat() + 5
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                != 0) && change.configuration.windowConfiguration.bounds.right ==
                        STARTING_BOUNDS.right &&
                        change.configuration.windowConfiguration.bounds.left ==
                        STARTING_BOUNDS.left
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsRunsWhenResizeBoundsValid() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Shrink to height 20px and width 20px with both min height/width equal to 10px
        val newX = STARTING_BOUNDS.right.toFloat() - 80
        val newY = STARTING_BOUNDS.top.toFloat() + 80
        taskPositioner.onDragPositioningMove(
                newX,
                newY
        )

        taskPositioner.onDragPositioningEnd(newX, newY)

        verify(mockShellTaskOrganizer).applyTransaction(argThat { wct ->
            return@argThat wct.changes.any { (token, change) ->
                token == taskBinder &&
                        ((change.windowSetMask and WindowConfiguration.WINDOW_CONFIG_BOUNDS) != 0)
            }
        })
    }

    @Test
    fun testDragResize_resize_setBoundsDoesNotRunWithNegativeHeightAndWidth() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Shrink to height 5px and width 5px with both min height/width equal to 10px
        val newX = STARTING_BOUNDS.right.toFloat() - 95
        val newY = STARTING_BOUNDS.top.toFloat() + 95
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
    fun testDragResize_resize_useDefaultMinWhenMinWidthInvalid() {
        mockWindowDecoration.mTaskInfo.minWidth = -1

        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Shrink to width and height of 3px with invalid minWidth = -1 and defaultMinSize = 5px
        val newX = STARTING_BOUNDS.right.toFloat() - 97
        val newY = STARTING_BOUNDS.top.toFloat() + 97
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
    fun testDragResize_resize_useMinWidthWhenValid() {
        taskPositioner.onDragPositioningStart(
                CTRL_TYPE_RIGHT or CTRL_TYPE_TOP, // Resize right and top
                STARTING_BOUNDS.right.toFloat(),
                STARTING_BOUNDS.top.toFloat()
        )

        // Shrink to width and height of 7px with valid minWidth = 10px and defaultMinSize = 5px
        val newX = STARTING_BOUNDS.right.toFloat() - 93
        val newY = STARTING_BOUNDS.top.toFloat() + 93
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

    companion object {
        private const val TASK_ID = 5
        private const val MIN_WIDTH = 10
        private const val MIN_HEIGHT = 10
        private const val DENSITY_DPI = 20
        private const val DEFAULT_MIN = 40
        private const val DISPLAY_ID = 1
        private val STARTING_BOUNDS = Rect(0, 0, 100, 100)
    }
}
