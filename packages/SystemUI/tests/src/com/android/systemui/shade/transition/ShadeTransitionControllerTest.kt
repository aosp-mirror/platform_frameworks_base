@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.shade.transition

import android.platform.test.annotations.DisableFlags
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.FakeDeviceEntryRepository
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceUnlockedInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.FakeSceneDataSource
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.shade.STATE_OPENING
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import com.android.systemui.shade.domain.interactor.panelExpansionInteractor
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ShadeTransitionControllerTest : SysuiTestCase() {

    @Mock private lateinit var scrimShadeTransitionController: ScrimShadeTransitionController
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController

    private lateinit var controller: ShadeTransitionController

    private val configurationController = FakeConfigurationController()
    private val shadeExpansionStateManager = ShadeExpansionStateManager()
    private val kosmos = testKosmos()
    private lateinit var testScope: TestScope
    private lateinit var applicationScope: CoroutineScope
    private lateinit var panelExpansionInteractor: PanelExpansionInteractor
    private lateinit var deviceEntryRepository: FakeDeviceEntryRepository
    private lateinit var deviceUnlockedInteractor: DeviceUnlockedInteractor
    private lateinit var sceneInteractor: SceneInteractor
    private lateinit var fakeSceneDataSource: FakeSceneDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        testScope = kosmos.testScope
        applicationScope = kosmos.applicationCoroutineScope
        panelExpansionInteractor = kosmos.panelExpansionInteractor
        deviceEntryRepository = kosmos.fakeDeviceEntryRepository
        deviceUnlockedInteractor = kosmos.deviceUnlockedInteractor
        sceneInteractor = kosmos.sceneInteractor
        fakeSceneDataSource = kosmos.fakeSceneDataSource

        controller =
            ShadeTransitionController(
                applicationScope,
                configurationController,
                shadeExpansionStateManager,
                dumpManager,
                context,
                scrimShadeTransitionController,
                statusBarStateController,
                ResourcesSplitShadeStateController(),
            ) {
                panelExpansionInteractor
            }
    }

    @Test
    @DisableFlags(FLAG_SCENE_CONTAINER)
    fun onPanelStateChanged_forwardsToScrimTransitionController() {
        startLegacyPanelExpansion()

        verify(scrimShadeTransitionController).onPanelStateChanged(STATE_OPENING)
        verify(scrimShadeTransitionController).onPanelExpansionChanged(DEFAULT_EXPANSION_EVENT)
    }

    @Test
    @EnableSceneContainer
    fun sceneChanges_forwardsToScrimTransitionController() =
        testScope.runTest {
            var latestChangeEvent: ShadeExpansionChangeEvent? = null
            whenever(scrimShadeTransitionController.onPanelExpansionChanged(any())).thenAnswer {
                latestChangeEvent = it.arguments[0] as ShadeExpansionChangeEvent
                Unit
            }
            setUnlocked(true)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Gone)
                )
            sceneInteractor.setTransitionState(transitionState)

            changeScene(Scenes.Gone, transitionState)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Gone)

            assertThat(latestChangeEvent)
                .isEqualTo(
                    ShadeExpansionChangeEvent(
                        fraction = 0f,
                        expanded = false,
                        tracking = true,
                        dragDownPxAmount = 0f,
                    )
                )

            changeScene(Scenes.Shade, transitionState) { progress ->
                assertThat(latestChangeEvent)
                    .isEqualTo(
                        ShadeExpansionChangeEvent(
                            fraction = progress,
                            expanded = progress > 0,
                            tracking = true,
                            dragDownPxAmount = 0f,
                        )
                    )
            }
        }

    private fun startLegacyPanelExpansion() {
        shadeExpansionStateManager.onPanelExpansionChanged(
            DEFAULT_EXPANSION_EVENT.fraction,
            DEFAULT_EXPANSION_EVENT.expanded,
            DEFAULT_EXPANSION_EVENT.tracking,
            DEFAULT_EXPANSION_EVENT.dragDownPxAmount,
        )
    }

    private fun TestScope.setUnlocked(isUnlocked: Boolean) {
        val isDeviceUnlocked by collectLastValue(deviceUnlockedInteractor.isDeviceUnlocked)
        deviceEntryRepository.setUnlocked(isUnlocked)
        runCurrent()

        assertThat(isDeviceUnlocked).isEqualTo(isUnlocked)
    }

    private fun TestScope.changeScene(
        toScene: SceneKey,
        transitionState: MutableStateFlow<ObservableTransitionState>,
        assertDuringProgress: ((progress: Float) -> Unit) = {},
    ) {
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        val progressFlow = MutableStateFlow(0f)
        transitionState.value =
            ObservableTransitionState.Transition(
                fromScene = checkNotNull(currentScene),
                toScene = toScene,
                progress = progressFlow,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
            )
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 0.2f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 0.6f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        progressFlow.value = 1f
        runCurrent()
        assertDuringProgress(progressFlow.value)

        transitionState.value = ObservableTransitionState.Idle(toScene)
        fakeSceneDataSource.changeScene(toScene)
        runCurrent()
        assertDuringProgress(progressFlow.value)

        assertThat(currentScene).isEqualTo(toScene)
    }

    companion object {
        private const val DEFAULT_DRAG_DOWN_AMOUNT = 123f
        private val DEFAULT_EXPANSION_EVENT =
            ShadeExpansionChangeEvent(
                fraction = 0.5f,
                expanded = true,
                tracking = true,
                dragDownPxAmount = DEFAULT_DRAG_DOWN_AMOUNT
            )
    }
}
