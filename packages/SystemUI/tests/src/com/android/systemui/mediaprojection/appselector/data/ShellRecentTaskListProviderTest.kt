package com.android.systemui.mediaprojection.appselector.data

import android.app.ActivityManager.RecentTaskInfo
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.wm.shell.recents.RecentTasks
import com.android.wm.shell.util.GroupedRecentTaskInfo
import com.google.common.truth.Truth.assertThat
import java.util.*
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

        assertThat(result)
            .containsExactly(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2),
                createRecentTask(taskId = 3),
            )
    }

    @Test
    fun loadRecentTasks_groupedTask_returnsUngroupedTasks() {
        givenRecentTasks(createTaskPair(taskId1 = 1, taskId2 = 2))

        val result = runBlocking { recentTaskListProvider.loadRecentTasks() }

        assertThat(result)
            .containsExactly(createRecentTask(taskId = 1), createRecentTask(taskId = 2))
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

        assertThat(result)
            .containsExactly(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2),
                createRecentTask(taskId = 3),
                createRecentTask(taskId = 4),
                createRecentTask(taskId = 5),
                createRecentTask(taskId = 6),
            )
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
            userId = 0,
            topActivityComponent = null,
            baseIntentComponent = null,
            colorBackground = null
        )

    private fun createSingleTask(taskId: Int): GroupedRecentTaskInfo =
        GroupedRecentTaskInfo.forSingleTask(createTaskInfo(taskId))

    private fun createTaskPair(taskId1: Int, taskId2: Int): GroupedRecentTaskInfo =
        GroupedRecentTaskInfo.forSplitTasks(createTaskInfo(taskId1), createTaskInfo(taskId2), null)

    private fun createTaskInfo(taskId: Int) = RecentTaskInfo().apply { this.taskId = taskId }
}
