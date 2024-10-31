package com.android.systemui.mediaprojection.appselector.data

import android.app.WindowConfiguration
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.ColorSpace
import android.graphics.Point
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.view.Surface
import android.window.TaskSnapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
@OptIn(ExperimentalCoroutinesApi::class)
class ActivityTaskManagerThumbnailLoaderTest : SysuiTestCase() {

    private val dispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private val activityManager = mock<ActivityManagerWrapper>()
    private val loader = ActivityTaskManagerThumbnailLoader(dispatcher, activityManager)

    @Test
    fun loadThumbnail_emptyThumbnail_returnsNull() =
        testScope.runTest {
            val taskId = 123
            val isLowResolution = false
            val thumbnailData = ThumbnailData()
            whenever(activityManager.getTaskThumbnail(taskId, isLowResolution))
                .thenReturn(thumbnailData)

            assertThat(loader.loadThumbnail(taskId)).isNull()
        }

    @Test
    fun loadThumbnail_thumbnailAvailable_returnsThumbnailData() =
        testScope.runTest {
            val taskId = 123
            val isLowResolution = false
            val snapshot = createTaskSnapshot()
            val thumbnailData = ThumbnailData.fromSnapshot(snapshot)
            whenever(activityManager.getTaskThumbnail(taskId, isLowResolution))
                .thenReturn(thumbnailData)

            assertThat(loader.loadThumbnail(taskId)).isEqualTo(thumbnailData)
        }

    @Test
    fun captureThumbnail_emptyThumbnail_returnsNull() =
        testScope.runTest {
            val taskId = 321
            val emptyThumbnailData = ThumbnailData()

            whenever(activityManager.takeTaskThumbnail(taskId)).thenReturn(emptyThumbnailData)

            assertThat(loader.captureThumbnail(taskId)).isNull()
        }

    @Test
    fun captureThumbnail_thumbnailAvailable_returnsThumbnailData() =
        testScope.runTest {
            val taskId = 321
            val thumbnailData = ThumbnailData.fromSnapshot(createTaskSnapshot())

            whenever(activityManager.takeTaskThumbnail(taskId)).thenReturn(thumbnailData)

            assertThat(loader.captureThumbnail(taskId)).isEqualTo(thumbnailData)
        }

    private fun createTaskSnapshot() =
        TaskSnapshot(
            /* id= */ 123,
            /* captureTime= */ 0,
            /* topActivityComponent= */ ComponentName("package", "class"),
            /* snapshot= */ HardwareBuffer.create(
                /* width= */ 100,
                /* height= */ 100,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                /* usage= */ HardwareBuffer.USAGE_CPU_READ_OFTEN
            ),
            ColorSpace.get(ColorSpace.Named.SRGB),
            Configuration.ORIENTATION_PORTRAIT,
            Surface.ROTATION_0,
            /* taskSize= */ Point(100, 100),
            /* contentInsets= */ Rect(),
            /* letterboxInsets= */ Rect(),
            /* isLowResolution= */ false,
            /* isRealSnapshot= */ true,
            WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
            /* appearance= */ 0,
            /* isTranslucent= */ false,
            /* hasImeSurface= */ false,
            /* uiMode */ 0
        )
}
