package com.android.systemui.mediaprojection.appselector

import android.content.ComponentName
import android.os.UserHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger
import com.android.systemui.mediaprojection.appselector.data.RecentTask
import com.android.systemui.mediaprojection.appselector.data.RecentTaskListProvider
import com.android.systemui.mediaprojection.appselector.data.RecentTaskThumbnailLoader
import com.android.systemui.mediaprojection.devicepolicy.ScreenCaptureDevicePolicyResolver
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaProjectionAppSelectorControllerTest : SysuiTestCase() {

    private val taskListProvider = TestRecentTaskListProvider()
    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val appSelectorComponentName = ComponentName("com.test", "AppSelector")
    private val callerPackageName = "com.test.caller"
    private val callerComponentName = ComponentName(callerPackageName, "Caller")

    private val personalUserHandle = UserHandle.of(123)
    private val workUserHandle = UserHandle.of(456)

    private val view: MediaProjectionAppSelectorView = mock()
    private val policyResolver: ScreenCaptureDevicePolicyResolver = mock()
    private val logger = mock<MediaProjectionMetricsLogger>()

    private val thumbnailLoader = FakeThumbnailLoader()

    private fun createController(isFirstStart: Boolean = true, hostUid: Int = 123) =
        MediaProjectionAppSelectorController(
            taskListProvider,
            view,
            policyResolver,
            personalUserHandle,
            scope,
            appSelectorComponentName,
            callerPackageName,
            thumbnailLoader,
            isFirstStart,
            logger,
            hostUid,
        )

    @Before
    fun setup() {
        givenCaptureAllowed(isAllow = true)
    }

    @Test
    fun initNoRecentTasks_bindsEmptyList() {
        taskListProvider.tasks = emptyList()

        createController().init()

        verify(view).bind(emptyList())
    }

    @Test
    fun initOneRecentTask_bindsList() {
        taskListProvider.tasks = listOf(createRecentTask(taskId = 1))

        createController().init()

        verify(view).bind(listOf(createRecentTask(taskId = 1)))
    }

    @Test
    fun init_refreshesThumbnailsOfForegroundTasks() = runTest {
        val tasks =
            listOf(
                createRecentTask(taskId = 1, isForegroundTask = false),
                createRecentTask(taskId = 2, isForegroundTask = true),
                createRecentTask(taskId = 3, isForegroundTask = true),
                createRecentTask(taskId = 4, isForegroundTask = false),
            )
        taskListProvider.tasks = tasks

        createController().init()

        assertThat(thumbnailLoader.capturedTaskIds).containsExactly(2, 3)
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

        createController().init()

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

        createController().init()

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

        createController().init()

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
    fun initRecentTasksWithAppSelectorTasks_withEnterprisePolicies_bindsAllTasks() {
        val tasks =
            listOf(
                createRecentTask(taskId = 1, userId = personalUserHandle.identifier),
                createRecentTask(taskId = 2, userId = workUserHandle.identifier),
                createRecentTask(taskId = 3, userId = personalUserHandle.identifier),
                createRecentTask(taskId = 4, userId = workUserHandle.identifier),
                createRecentTask(taskId = 5, userId = personalUserHandle.identifier),
            )
        taskListProvider.tasks = tasks

        createController().init()

        verify(view)
            .bind(
                listOf(
                    createRecentTask(taskId = 1, userId = personalUserHandle.identifier),
                    createRecentTask(taskId = 2, userId = workUserHandle.identifier),
                    createRecentTask(taskId = 3, userId = personalUserHandle.identifier),
                    createRecentTask(taskId = 4, userId = workUserHandle.identifier),
                    createRecentTask(taskId = 5, userId = personalUserHandle.identifier),
                )
            )
    }

    @Test
    fun initRecentTasksWithAppSelectorTasks_withEnterprisePolicies_blocksAllTasks() {
        val tasks =
            listOf(
                createRecentTask(taskId = 1, userId = personalUserHandle.identifier),
                createRecentTask(taskId = 2, userId = workUserHandle.identifier),
                createRecentTask(taskId = 3, userId = personalUserHandle.identifier),
                createRecentTask(taskId = 4, userId = workUserHandle.identifier),
                createRecentTask(taskId = 5, userId = personalUserHandle.identifier),
            )
        taskListProvider.tasks = tasks

        givenCaptureAllowed(isAllow = false)
        createController().init()

        verify(view).bind(emptyList())
    }

    @Test
    fun init_firstStart_logsAppSelectorDisplayed() {
        val hostUid = 123456789
        val controller = createController(isFirstStart = true, hostUid)

        controller.init()

        verify(logger).notifyAppSelectorDisplayed(hostUid)
    }

    @Test
    fun init_notFirstStart_doesNotLogAppSelectorDisplayed() {
        val hostUid = 123456789
        val controller = createController(isFirstStart = false, hostUid)

        controller.init()

        verify(logger, never()).notifyAppSelectorDisplayed(hostUid)
    }

    @Test
    fun onSelectorDismissed_logsProjectionRequestCancelled() {
        val hostUid = 123

        createController(hostUid = hostUid).onSelectorDismissed()

        verify(logger).notifyProjectionRequestCancelled(hostUid)
    }

    private fun givenCaptureAllowed(isAllow: Boolean) {
        whenever(policyResolver.isScreenCaptureAllowed(any(), any())).thenReturn(isAllow)
    }

    private fun createRecentTask(
        taskId: Int,
        topActivityComponent: ComponentName? = null,
        userId: Int = personalUserHandle.identifier,
        isForegroundTask: Boolean = false
    ): RecentTask {
        return RecentTask(
            taskId = taskId,
            displayId = 0,
            topActivityComponent = topActivityComponent,
            baseIntentComponent = ComponentName("com", "Test"),
            userId = userId,
            colorBackground = 0,
            isForegroundTask = isForegroundTask,
            userType = RecentTask.UserType.STANDARD,
            splitBounds = null,
        )
    }

    private class TestRecentTaskListProvider : RecentTaskListProvider {

        var tasks: List<RecentTask> = emptyList()

        override suspend fun loadRecentTasks(): List<RecentTask> = tasks
    }

    private class FakeThumbnailLoader : RecentTaskThumbnailLoader {

        val capturedTaskIds = mutableListOf<Int>()

        override suspend fun loadThumbnail(taskId: Int): ThumbnailData? {
            return null
        }

        override suspend fun captureThumbnail(taskId: Int): ThumbnailData? {
            capturedTaskIds += taskId
            return null
        }
    }
}
