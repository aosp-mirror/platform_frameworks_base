package com.android.systemui.qs

import android.content.res.Configuration
import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import android.testing.TestableResources
import android.view.ContextThemeWrapper
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.media.controls.ui.view.MediaHost
import com.android.systemui.media.controls.ui.view.MediaHostState
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.qs.customize.QSCustomizerController
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.scene.shared.flag.FakeSceneContainerFlags
import com.android.systemui.settings.brightness.BrightnessController
import com.android.systemui.settings.brightness.BrightnessSliderController
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.tuner.TunerService
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.any
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
class QSPanelControllerTest : SysuiTestCase() {

    @Mock private lateinit var qsPanel: QSPanel
    @Mock private lateinit var tunerService: TunerService
    @Mock private lateinit var qsHost: QSHost
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
    @Mock private lateinit var configuration: Configuration
    @Mock private lateinit var pagedTileLayout: PagedTileLayout

    private val sceneContainerFlags = FakeSceneContainerFlags()

    private lateinit var controller: QSPanelController
    private val testableResources: TestableResources = mContext.orCreateTestableResources

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(brightnessSliderFactory.create(any(), any())).thenReturn(brightnessSlider)
        whenever(brightnessControllerFactory.create(any())).thenReturn(brightnessController)
        setShouldUseSplitShade(false)
        whenever(qsPanel.resources).thenReturn(testableResources.resources)
        whenever(qsPanel.context)
                .thenReturn( ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings))
        whenever(qsPanel.getOrCreateTileLayout()).thenReturn(pagedTileLayout)
        whenever(statusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false)
        whenever(qsPanel.setListening(anyBoolean())).then {
            whenever(qsPanel.isListening).thenReturn(it.getArgument(0))
        }

        controller = QSPanelController(
            qsPanel,
            tunerService,
            qsHost,
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
            statusBarKeyguardViewManager,
            ResourcesSplitShadeStateController(),
            sceneContainerFlags,
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
        whenever(qsHost.getTiles()).thenReturn(listOf(tile, otherTile))
        controller.setTiles()
        whenever(tile.isListening()).thenReturn(false)
        whenever(otherTile.isListening()).thenReturn(true)

        controller.setListening(true, true)

        verify(tile).refreshState()
        verify(otherTile, Mockito.never()).refreshState()
    }

    @Test
    fun testIsBouncerInTransit() {
        whenever(statusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(true)
        assertThat(controller.isBouncerInTransit()).isEqualTo(true)
        whenever(statusBarKeyguardViewManager.isPrimaryBouncerInTransit()).thenReturn(false)
        assertThat(controller.isBouncerInTransit()).isEqualTo(false)
    }

    @Test
    fun configurationChange_onlySplitShadeConfigChanges_tileAreRedistributed() {
        setShouldUseSplitShade(false)
        controller.mOnConfigurationChangedListener.onConfigurationChange(configuration)
        verify(pagedTileLayout, never()).forceTilesRedistribution(any())

        setShouldUseSplitShade(true)
        controller.mOnConfigurationChangedListener.onConfigurationChange(configuration)
        verify(pagedTileLayout).forceTilesRedistribution("Split shade state changed")
    }

    @Test
    fun configurationChange_onlySplitShadeConfigChanges_qsPanelCanBeCollapsed() {
        setShouldUseSplitShade(false)
        controller.mOnConfigurationChangedListener.onConfigurationChange(configuration)
        verify(qsPanel, never()).setCanCollapse(anyBoolean())

        setShouldUseSplitShade(true)
        controller.mOnConfigurationChangedListener.onConfigurationChange(configuration)
        verify(qsPanel).setCanCollapse(false)

        setShouldUseSplitShade(false)
        controller.mOnConfigurationChangedListener.onConfigurationChange(configuration)
        verify(qsPanel).setCanCollapse(true)
    }

    @Test
    fun multipleListeningOnlyCallsBrightnessControllerOnce() {
        controller.setListening(true, true)
        controller.setListening(true, false)
        controller.setListening(true, true)

        verify(brightnessController).registerCallbacks()

        controller.setListening(false, true)
        controller.setListening(false, false)
        controller.setListening(false, true)

        verify(brightnessController).unregisterCallbacks()
    }

    private fun setShouldUseSplitShade(shouldUse: Boolean) {
        testableResources.addOverride(R.bool.config_use_split_notification_shade, shouldUse)
    }
}
