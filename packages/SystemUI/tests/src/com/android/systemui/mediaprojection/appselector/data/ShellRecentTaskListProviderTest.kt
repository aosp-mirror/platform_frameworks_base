package com.android.systemui.mediaprojection.appselector.data

import android.app.ActivityManager.RecentTaskInfo
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.common.split.SplitScreenConstants
import com.android.wm.shell.recents.RecentTasks
import com.android.wm.shell.util.GroupedRecentTaskInfo
import com.android.wm.shell.util.SplitBounds
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ShellRecentTaskListProviderTest : SysuiTestCase() {

    private val dispatcher = Dispatchers.Unconfined
    private val recentTasks: RecentTasks = mock()
    private val userTracker: UserTracker = mock()
    private val recentTaskListProvider =
        ShellRecentTaskListProvider(
            dispatcher,
            Runnable::run,
            Optional.of(recentTasks),
            userTracker
        )

    @Test
    fun loadRecentTasks_oneTask_returnsTheSameTask() {
        givenRecentTasks(createSingleTask(taskId = 1))

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result).containsExactly(createRecentTask(taskId = 1))
    }

    @Test
    fun loadRecentTasks_multipleTasks_returnsTheSameTasks() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createSingleTask(taskId = 2),
            createSingleTask(taskId = 3),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.taskId }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun loadRecentTasks_groupedTask_returnsUngroupedTasks() {
        givenRecentTasks(createTaskPair(taskId1 = 1, taskId2 = 2))

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.taskId }).containsExactly(1, 2).inOrder()
    }

    @Test
    fun loadRecentTasks_mixedSingleAndGroupedTask_returnsUngroupedTasks() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createTaskPair(taskId1 = 2, taskId2 = 3),
            createSingleTask(taskId = 4),
            createTaskPair(taskId1 = 5, taskId2 = 6),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.taskId }).containsExactly(1, 2, 3, 4, 5, 6).inOrder()
    }

    @Test
    fun loadRecentTasks_singleTask_returnsTaskAsNotForeground() {
        givenRecentTasks(
            createSingleTask(taskId = 1, isVisible = true),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result[0].isForegroundTask).isFalse()
    }

    @Test
    fun loadRecentTasks_singleTaskPair_returnsTasksAsForeground() {
        givenRecentTasks(
            createTaskPair(taskId1 = 2, taskId2 = 3, isVisible = true),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result[0].isForegroundTask).isTrue()
    }

    @Test
    fun loadRecentTasks_multipleTasks_returnsSecondVisibleTaskAsForegroundTask() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createSingleTask(taskId = 2, isVisible = true),
            createSingleTask(taskId = 3),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask }).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun loadRecentTasks_multipleTasks_returnsSecondInvisibleTaskAsNotForegroundTask() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createSingleTask(taskId = 2, isVisible = false),
            createSingleTask(taskId = 3),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask })
            .containsExactly(false, false, false)
            .inOrder()
    }

    @Test
    fun loadRecentTasks_secondTaskIsGroupedAndVisible_marksBothGroupedTasksAsForeground() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createTaskPair(taskId1 = 2, taskId2 = 3, isVisible = true),
            createSingleTask(taskId = 4),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask })
            .containsExactly(false, true, true, false)
            .inOrder()
    }

    @Test
    fun loadRecentTasks_firstTaskIsGroupedAndVisible_marksBothGroupedTasksAsForeground() {
        givenRecentTasks(
            createTaskPair(taskId1 = 1, taskId2 = 2, isVisible = true),
            createSingleTask(taskId = 3),
            createSingleTask(taskId = 4),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask })
                .containsExactly(true, true, false, false)
                .inOrder()
    }

    @Test
    fun loadRecentTasks_secondTaskIsGroupedAndInvisible_marksBothGroupedTasksAsNotForeground() {
        givenRecentTasks(
            createSingleTask(taskId = 1),
            createTaskPair(taskId1 = 2, taskId2 = 3, isVisible = false),
            createSingleTask(taskId = 4),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask })
            .containsExactly(false, false, false, false)
            .inOrder()
    }

    @Test
    fun loadRecentTasks_firstTaskIsGroupedAndInvisible_marksBothGroupedTasksAsNotForeground() {
        givenRecentTasks(
            createTaskPair(taskId1 = 1, taskId2 = 2, isVisible = false),
            createSingleTask(taskId = 3),
            createSingleTask(taskId = 4),
        )

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result.map { it.isForegroundTask })
                .containsExactly(false, false, false, false)
                .inOrder()
    }

    @Suppress("UNCHECKED_CAST")
    private fun givenRecentTasks(vararg tasks: GroupedRecentTaskInfo) {
        whenever(recentTasks.getRecentTasks(any(), any(), any(), any(), any())).thenAnswer {
            val consumer = it.arguments.last() as Consumer<List<GroupedRecentTaskInfo>>
            consumer.accept(tasks.toList())
        }
    }

    private fun createRecentTask(taskId: Int): RecentTask =
        RecentTask(
            taskId = taskId,
            displayId = 0,
            userId = 0,
            topActivityComponent = null,
            baseIntentComponent = null,
            colorBackground = null,
            isForegroundTask = false,
        )

    private fun createSingleTask(taskId: Int, isVisible: Boolean = false): GroupedRecentTaskInfo =
        GroupedRecentTaskInfo.forSingleTask(createTaskInfo(taskId, isVisible))

    private fun createTaskPair(
        taskId1: Int,
        taskId2: Int,
        isVisible: Boolean = false
    ): GroupedRecentTaskInfo =
        GroupedRecentTaskInfo.forSplitTasks(
            createTaskInfo(taskId1, isVisible),
            createTaskInfo(taskId2, isVisible),
            SplitBounds(Rect(), Rect(), taskId1, taskId2, SplitScreenConstants.SNAP_TO_50_50)
        )

    private fun createTaskInfo(taskId: Int, isVisible: Boolean = false) =
        RecentTaskInfo().apply {
            this.taskId = taskId
            this.isVisible = isVisible
        }
}
