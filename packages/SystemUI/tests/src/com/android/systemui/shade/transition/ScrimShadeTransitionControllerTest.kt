package com.android.systemui.shade.transition

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.FakeSceneDataSource
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.shade.domain.interactor.panelExpansionInteractor
import com.android.systemui.statusbar.phone.ScrimController
import com.android.systemui.testKosmos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ScrimShadeTransitionControllerTest : SysuiTestCase() {

    @Mock private lateinit var scrimController: ScrimController
    @Mock private lateinit var dumpManager: DumpManager

    private val shadeExpansionStateManager = ShadeExpansionStateManager()
    private val kosmos = testKosmos()
    private lateinit var testScope: TestScope
    private lateinit var applicationScope: CoroutineScope
    private lateinit var panelExpansionInteractor: PanelExpansionInteractor
    private lateinit var deviceEntryRepository: FakeDeviceEntryRepository
    private lateinit var deviceUnlockedInteractor: DeviceUnlockedInteractor
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var fakeSceneDataSource: FakeSceneDataSource

    private lateinit var underTest: ScrimShadeTransitionController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context.ensureTestableResources()
        testScope = kosmos.testScope
        applicationScope = kosmos.applicationCoroutineScope
        panelExpansionInteractor = kosmos.panelExpansionInteractor
        deviceEntryRepository = kosmos.fakeDeviceEntryRepository
        deviceUnlockedInteractor = kosmos.deviceUnlockedInteractor
        sceneInteractor = kosmos.sceneInteractor
        fakeSceneDataSource = kosmos.fakeSceneDataSource
        underTest = ScrimShadeTransitionController(
            shadeExpansionStateManager,
            dumpManager,
            scrimController,
            )
        underTest.init()
    }

    @Test
    @DisableSceneContainer
    fun onPanelExpansionChanged_setsFractionEqualToEventFraction() {
        underTest.onPanelExpansionChanged(DEFAULT_EXPANSION_EVENT)

        verify(scrimController).setRawPanelExpansionFraction(DEFAULT_EXPANSION_EVENT.fraction)
    }

    @Test
    @DisableSceneContainer
    fun onPanelStateChanged_forwardsToScrimTransitionController() {
        startLegacyPanelExpansion()

        verify(scrimController).setRawPanelExpansionFraction(DEFAULT_EXPANSION_EVENT.fraction)
    }

    private fun startLegacyPanelExpansion() {
        shadeExpansionStateManager.onPanelExpansionChanged(
            DEFAULT_EXPANSION_EVENT.fraction,
            DEFAULT_EXPANSION_EVENT.expanded,
            DEFAULT_EXPANSION_EVENT.tracking,
        )
    }

    companion object {
        val DEFAULT_EXPANSION_EVENT =
            ShadeExpansionChangeEvent(
                fraction = 0.5f,
                expanded = true,
                tracking = true
            )
    }
}
