package com.android.systemui.mediaprojection

import android.media.projection.IMediaProjectionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_APP as METRICS_CREATION_SOURCE_APP
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_CAST as METRICS_CREATION_SOURCE_CAST
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER as METRICS_CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER
import com.android.internal.util.FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__CREATION_SOURCE__CREATION_SOURCE_UNKNOWN as METRICS_CREATION_SOURCE_UNKNOWN
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify

@RunWith(AndroidJUnit4::class)
@SmallTest
class MediaProjectionMetricsLoggerTest : SysuiTestCase() {

    private val service = mock<IMediaProjectionManager>()
    private val logger = MediaProjectionMetricsLogger(service)

    @Test
    fun notifyProjectionInitiated_sourceApp_forwardsToServiceWithMetricsValue() {
        val hostUid = 123
        val sessionCreationSource = SessionCreationSource.APP

        logger.notifyProjectionInitiated(hostUid, sessionCreationSource)

        verify(service).notifyPermissionRequestInitiated(hostUid, METRICS_CREATION_SOURCE_APP)
    }

    @Test
    fun notifyProjectionInitiated_sourceCast_forwardsToServiceWithMetricsValue() {
        val hostUid = 123
        val sessionCreationSource = SessionCreationSource.CAST

        logger.notifyProjectionInitiated(hostUid, sessionCreationSource)

        verify(service).notifyPermissionRequestInitiated(hostUid, METRICS_CREATION_SOURCE_CAST)
    }

    @Test
    fun notifyProjectionInitiated_sourceSysUI_forwardsToServiceWithMetricsValue() {
        val hostUid = 123
        val sessionCreationSource = SessionCreationSource.SYSTEM_UI_SCREEN_RECORDER

        logger.notifyProjectionInitiated(hostUid, sessionCreationSource)

        verify(service)
            .notifyPermissionRequestInitiated(
                hostUid,
                METRICS_CREATION_SOURCE_SYSTEM_UI_SCREEN_RECORDER
            )
    }

    @Test
    fun notifyProjectionInitiated_sourceUnknown_forwardsToServiceWithMetricsValue() {
        val hostUid = 123
        val sessionCreationSource = SessionCreationSource.UNKNOWN

        logger.notifyProjectionInitiated(hostUid, sessionCreationSource)

        verify(service).notifyPermissionRequestInitiated(hostUid, METRICS_CREATION_SOURCE_UNKNOWN)
    }

    @Test
    fun notifyPermissionRequestDisplayed_forwardsToService() {
        val hostUid = 987

        logger.notifyPermissionRequestDisplayed(hostUid)

        verify(service).notifyPermissionRequestDisplayed(hostUid)
    }

    @Test
    fun notifyProjectionCancelled_forwardsToServiceWithMetricsValue() {
        val hostUid = 123

        logger.notifyProjectionRequestCancelled(hostUid)

        verify(service).notifyPermissionRequestCancelled(hostUid)
    }

    @Test
    fun notifyAppSelectorDisplayed_forwardsToService() {
        val hostUid = 654

        logger.notifyAppSelectorDisplayed(hostUid)

        verify(service).notifyAppSelectorDisplayed(hostUid)
    }
}
