package com.android.systemui.mediaprojection.appselector

import android.content.ComponentName
import android.os.UserHandle
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
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
    private val callerPackageName = "com.test.caller"
    private val callerComponentName = ComponentName(callerPackageName, "Caller")

    private val hostUserHandle = UserHandle.of(123)
    private val otherUserHandle = UserHandle.of(456)

    private val view: MediaProjectionAppSelectorView = mock()
    private val featureFlags: FeatureFlags = mock()

    private val controller =
        MediaProjectionAppSelectorController(
            taskListProvider,
            view,
            featureFlags,
            hostUserHandle,
            scope,
            appSelectorComponentName,
            callerPackageName
        )

    @Test
    fun initNoRecentTasks_bindsEmptyList() {
        taskListProvider.tasks = emptyList()

        controller.init()

        verify(view).bind(emptyList())
    }

    @Test
    fun initOneRecentTask_bindsList() {
        taskListProvider.tasks = listOf(createRecentTask(taskId = 1))

        controller.init()

        verify(view).bind(listOf(createRecentTask(taskId = 1)))
    }

    @Test
    fun initMultipleRecentTasksWithoutAppSelectorTask_bindsListInTheSameOrder() {
        val tasks =
            listOf(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2),
                createRecentTask(taskId = 3),
            )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1),
                    createRecentTask(taskId = 2),
                    createRecentTask(taskId = 3),
                )
            )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_removeAppSelector() {
        val tasks =
            listOf(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2, topActivityComponent = appSelectorComponentName),
                createRecentTask(taskId = 3),
                createRecentTask(taskId = 4),
            )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1),
                    createRecentTask(taskId = 3),
                    createRecentTask(taskId = 4),
                )
            )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_bindsCallerTasksAtTheEnd() {
        val tasks =
            listOf(
                createRecentTask(taskId = 1),
                createRecentTask(taskId = 2, topActivityComponent = callerComponentName),
                createRecentTask(taskId = 3),
                createRecentTask(taskId = 4),
            )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1),
                    createRecentTask(taskId = 3),
                    createRecentTask(taskId = 4),
                    createRecentTask(taskId = 2, topActivityComponent = callerComponentName),
                )
            )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_enterprisePoliciesDisabled_bindsOnlyTasksWithHostProfile() {
        givenEnterprisePoliciesFeatureFlag(enabled = false)

        val tasks =
            listOf(
                createRecentTask(taskId = 1, userId = hostUserHandle.identifier),
                createRecentTask(taskId = 2, userId = otherUserHandle.identifier),
                createRecentTask(taskId = 3, userId = hostUserHandle.identifier),
                createRecentTask(taskId = 4, userId = otherUserHandle.identifier),
                createRecentTask(taskId = 5, userId = hostUserHandle.identifier),
            )
        taskListProvider.tasks = tasks

        controller.init()

        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1, userId = hostUserHandle.identifier),
                    createRecentTask(taskId = 3, userId = hostUserHandle.identifier),
                    createRecentTask(taskId = 5, userId = hostUserHandle.identifier),
                )
            )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_enterprisePoliciesEnabled_bindsAllTasks() {
        givenEnterprisePoliciesFeatureFlag(enabled = true)

        val tasks =
            listOf(
                createRecentTask(taskId = 1, userId = hostUserHandle.identifier),
                createRecentTask(taskId = 2, userId = otherUserHandle.identifier),
                createRecentTask(taskId = 3, userId = hostUserHandle.identifier),
                createRecentTask(taskId = 4, userId = otherUserHandle.identifier),
                createRecentTask(taskId = 5, userId = hostUserHandle.identifier),
            )
        taskListProvider.tasks = tasks

        controller.init()

        // TODO(b/233348916) should filter depending on the policies
        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1, userId = hostUserHandle.identifier),
                    createRecentTask(taskId = 2, userId = otherUserHandle.identifier),
                    createRecentTask(taskId = 3, userId = hostUserHandle.identifier),
                    createRecentTask(taskId = 4, userId = otherUserHandle.identifier),
                    createRecentTask(taskId = 5, userId = hostUserHandle.identifier),
                )
            )
    }

    private fun givenEnterprisePoliciesFeatureFlag(enabled: Boolean) {
        whenever(featureFlags.isEnabled(Flags.WM_ENABLE_PARTIAL_SCREEN_SHARING_ENTERPRISE_POLICIES))
            .thenReturn(enabled)
    }

    private fun createRecentTask(
        taskId: Int,
        topActivityComponent: ComponentName? = null,
        userId: Int = hostUserHandle.identifier
    ): RecentTask {
        return RecentTask(
            taskId = taskId,
            topActivityComponent = topActivityComponent,
            baseIntentComponent = ComponentName("com", "Test"),
            userId = userId,
            colorBackground = 0
        )
    }

    private class TestRecentTaskListProvider : RecentTaskListProvider {

        var tasks: List<RecentTask> = emptyList()

        override suspend fun loadRecentTasks(): List<RecentTask> = tasks
    }
}
