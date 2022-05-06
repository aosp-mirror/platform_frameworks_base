package com.android.systemui.qs

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.MediaHost
import com.android.systemui.media.MediaHostState
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.settings.brightness.BrightnessController
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.tuner.TunerService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QSPanelControllerTest : SysuiTestCase() {

    @Mock private lateinit var qsPanel: QSPanel
    @Mock private lateinit var tunerService: TunerService
    @Mock private lateinit var qsTileHost: QSTileHost
    @Mock private lateinit var qsCustomizerController: QSCustomizerController
    @Mock private lateinit var qsTileRevealControllerFactory: QSTileRevealController.Factory
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var qsLogger: QSLogger
    @Mock private lateinit var brightnessControllerFactory: BrightnessController.Factory
    @Mock private lateinit var brightnessController: BrightnessController
    @Mock private lateinit var brightnessSlider: BrightnessSliderController
    @Mock private lateinit var brightnessSliderFactory: BrightnessSliderController.Factory
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var mediaHost: MediaHost
    @Mock private lateinit var tile: QSTile
    @Mock private lateinit var otherTile: QSTile
    @Mock private lateinit var statusBarKeyguardViewManager: StatusBarKeyguardViewManager

    private lateinit var controller: QSPanelController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(brightnessSliderFactory.create(any(), any())).thenReturn(brightnessSlider)
        whenever(brightnessControllerFactory.create(any())).thenReturn(brightnessController)
        whenever(qsPanel.resources).thenReturn(mContext.orCreateTestableResources.resources)
        whenever(statusBarKeyguardViewManager.bouncerIsInTransit()).thenReturn(false)

        controller = QSPanelController(
            qsPanel,
            tunerService,
            qsTileHost,
            qsCustomizerController,
            /* usingMediaPlayer= */ true,
            mediaHost,
            qsTileRevealControllerFactory,
            dumpManager,
            metricsLogger,
            uiEventLogger,
            qsLogger,
            brightnessControllerFactory,
            brightnessSliderFactory,
            falsingManager,
            statusBarKeyguardViewManager
        )
    }

    @After
    fun tearDown() {
        reset(mediaHost)
    }

    @Test
    fun onInit_setsMediaAsExpanded() {
        controller.onInit()

        verify(mediaHost).expansion = MediaHostState.EXPANDED
    }

    @Test
    fun testSetListeningDoesntRefreshListeningTiles() {
        whenever(qsTileHost.getTiles()).thenReturn(listOf(tile, otherTile))
        controller.setTiles()
        whenever(tile.isListening()).thenReturn(false)
        whenever(otherTile.isListening()).thenReturn(true)
        whenever(qsPanel.isListening).thenReturn(true)

        controller.setListening(true, true)

        verify(tile).refreshState()
        verify(otherTile, Mockito.never()).refreshState()
    }

    @Test
    fun testBouncerIsInTransit() {
        whenever(statusBarKeyguardViewManager.bouncerIsInTransit()).thenReturn(true)
        assertThat(controller.bouncerInTransit()).isEqualTo(true)
        whenever(statusBarKeyguardViewManager.bouncerIsInTransit()).thenReturn(false)
        assertThat(controller.bouncerInTransit()).isEqualTo(false)
    }
}
