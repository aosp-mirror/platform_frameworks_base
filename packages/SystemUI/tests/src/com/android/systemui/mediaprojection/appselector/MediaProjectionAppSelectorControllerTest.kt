package com.android.systemui.mediaprojection.appselector

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.util.mockito.mock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidTestingRunner::class)
@SmallTest
class MediaProjectionAppSelectorControllerTest : SysuiTestCase() {

    private val taskListProvider = TestRecentTaskListProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val appSelectorComponentName = ComponentName("com.test", "AppSelector")

    private val view: MediaProjectionAppSelectorView = mock()

    private val controller = MediaProjectionAppSelectorController(
        taskListProvider,
        view,
        scope,
        appSelectorComponentName
    )

    @Test
    fun initNoRecentTasks_bindsEmptyList() {
        taskListProvider.tasks = emptyList()

        controller.init()

        verify(view).bind(emptyList())
    }

    @Test
    fun initOneRecentTask_bindsList() {
        taskListProvider.tasks = listOf(
            createRecentTask(taskId = 1)
        )

        controller.init()

        verify(view).bind(
            listOf(
                createRecentTask(taskId = 1)
            )
        )
    }

    @Test
    fun initMultipleRecentTasksWithoutAppSelectorTask_bindsListInTheSameOrder() {
        val tasks = listOf(
            createRecentTask(taskId = 1),
            createRecentTask(taskId = 2),
            createRecentTask(taskId = 3),
        )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view).bind(
            listOf(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2),
                createRecentTask(taskId = 3),
            )
        )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_bindsAppSelectorTasksAtTheEnd() {
        val tasks = listOf(
            createRecentTask(taskId = 1),
            createRecentTask(taskId = 2, topActivityComponent = appSelectorComponentName),
            createRecentTask(taskId = 3),
            createRecentTask(taskId = 4, topActivityComponent = appSelectorComponentName),
            createRecentTask(taskId = 5),
        )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view).bind(
            listOf(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 3),
                createRecentTask(taskId = 5),
                createRecentTask(taskId = 2, topActivityComponent = appSelectorComponentName),
                createRecentTask(taskId = 4, topActivityComponent = appSelectorComponentName),
            )
        )
    }

    private fun createRecentTask(
        taskId: Int,
        topActivityComponent: ComponentName? = null
    ): RecentTask {
        return RecentTask(
            taskId = taskId,
            topActivityComponent = topActivityComponent,
            baseIntentComponent = ComponentName("com", "Test"),
            userId = 0,
            colorBackground = 0
        )
    }

    private class TestRecentTaskListProvider : RecentTaskListProvider {

        var tasks: List<RecentTask> = emptyList()

        override suspend fun loadRecentTasks(): List<RecentTask> = tasks

    }
}
