package com.android.systemui.qs.tiles

import android.content.Context
import android.os.Handler
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.FlashlightController
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class FlashlightTileTest : SysuiTestCase() {

    @Mock private lateinit var mockContext: Context

    @Mock private lateinit var qsLogger: QSLogger

    @Mock private lateinit var qsHost: QSTileHost

    @Mock private lateinit var metricsLogger: MetricsLogger

    @Mock private lateinit var statusBarStateController: StatusBarStateController

    @Mock private lateinit var activityStarter: ActivityStarter

    @Mock private lateinit var flashlightController: FlashlightController

    private val uiEventLogger = UiEventLoggerFake()
    private val falsingManager = FalsingManagerFake()
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: FlashlightTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        Mockito.`when`(qsHost.context).thenReturn(mockContext)
        Mockito.`when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        tile =
            FlashlightTile(
                qsHost,
                testableLooper.looper,
                Handler(testableLooper.looper),
                falsingManager,
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                flashlightController
            )
    }

    @Test
    fun testIcon_whenFlashlightEnabled_isOnState() {
        Mockito.`when`(flashlightController.isAvailable).thenReturn(true)
        Mockito.`when`(flashlightController.isEnabled).thenReturn(true)
        val state = QSTile.BooleanState()

        tile.handleUpdateState(state, /* arg= */ null)

        Truth.assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_flashlight_icon_on))
    }

    @Test
    fun testIcon_whenFlashlightDisabled_isOffState() {
        Mockito.`when`(flashlightController.isAvailable).thenReturn(true)
        Mockito.`when`(flashlightController.isEnabled).thenReturn(false)
        val state = QSTile.BooleanState()

        tile.handleUpdateState(state, /* arg= */ null)

        Truth.assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_flashlight_icon_off))
    }

    @Test
    fun testIcon_whenFlashlightUnavailable_isOffState() {
        Mockito.`when`(flashlightController.isAvailable).thenReturn(false)
        val state = QSTile.BooleanState()

        tile.handleUpdateState(state, /* arg= */ null)

        Truth.assertThat(state.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_flashlight_icon_off))
    }
}
