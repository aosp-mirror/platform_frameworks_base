package com.android.systemui.qs

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.media.MediaHost
import com.android.systemui.media.MediaHostState
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.settings.brightness.BrightnessController
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.tuner.TunerService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QSPanelControllerTest : SysuiTestCase() {

    @Mock private lateinit var qsPanel: QSPanel
    @Mock private lateinit var qsFgsManagerFooter: QSFgsManagerFooter
    @Mock private lateinit var qsSecurityFooter: QSSecurityFooter
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
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var mediaHost: MediaHost

    private lateinit var controller: QSPanelController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(brightnessSliderFactory.create(any(), any())).thenReturn(brightnessSlider)
        whenever(brightnessControllerFactory.create(any())).thenReturn(brightnessController)
        whenever(qsPanel.resources).thenReturn(mContext.orCreateTestableResources.resources)

        controller = QSPanelController(
            qsPanel,
            qsFgsManagerFooter,
            qsSecurityFooter,
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
            featureFlags
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

    private fun setSplitShadeEnabled(enabled: Boolean) {
        mContext.orCreateTestableResources
            .addOverride(R.bool.config_use_split_notification_shade, enabled)
    }
}
