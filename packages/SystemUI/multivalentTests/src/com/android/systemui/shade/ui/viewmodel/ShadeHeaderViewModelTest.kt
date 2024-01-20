package com.android.systemui.shade.ui.viewmodel

import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeHeaderViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val sceneInteractor by lazy { kosmos.sceneInteractor }

    private val mobileIconsInteractor = FakeMobileIconsInteractor(FakeMobileMappingsProxy(), mock())
    private val flags = FakeFeatureFlagsClassic().also { it.set(Flags.NEW_NETWORK_SLICE_UI, false) }

    private var mobileIconsViewModel: MobileIconsViewModel =
        MobileIconsViewModel(
            logger = mock(),
            verboseLogger = mock(),
            interactor = mobileIconsInteractor,
            airplaneModeInteractor =
                AirplaneModeInteractor(
                    FakeAirplaneModeRepository(),
                    FakeConnectivityRepository(),
                    FakeMobileConnectionsRepository(),
                ),
            constants = mock(),
            flags,
            scope = testScope.backgroundScope,
        )

    private lateinit var underTest: ShadeHeaderViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        underTest =
            ShadeHeaderViewModel(
                applicationScope = testScope.backgroundScope,
                context = context,
                sceneInteractor = sceneInteractor,
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
    }

    @Test
    fun isTransitioning_idle_false() =
        testScope.runTest {
            val isTransitioning by collectLastValue(underTest.isTransitioning)
            sceneInteractor.setTransitionState(
                MutableStateFlow(ObservableTransitionState.Idle(SceneKey.Shade))
            )

            assertThat(isTransitioning).isFalse()
        }

    @Test
    fun isTransitioning_shadeToQs_true() =
        testScope.runTest {
            val isTransitioning by collectLastValue(underTest.isTransitioning)
            sceneInteractor.setTransitionState(
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Shade,
                        toScene = SceneKey.QuickSettings,
                        progress = MutableStateFlow(0.5f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )

            assertThat(isTransitioning).isTrue()
        }

    @Test
    fun isTransitioning_qsToShade_true() =
        testScope.runTest {
            val isTransitioning by collectLastValue(underTest.isTransitioning)
            sceneInteractor.setTransitionState(
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.QuickSettings,
                        toScene = SceneKey.Shade,
                        progress = MutableStateFlow(0.5f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )

            assertThat(isTransitioning).isTrue()
        }

    @Test
    fun isTransitioning_otherTransition_false() =
        testScope.runTest {
            val isTransitioning by collectLastValue(underTest.isTransitioning)
            sceneInteractor.setTransitionState(
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = SceneKey.Gone,
                        toScene = SceneKey.Shade,
                        progress = MutableStateFlow(0.5f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )

            assertThat(isTransitioning).isFalse()
        }

    @Test
    fun mobileSubIds_update() =
        testScope.runTest {
            val mobileSubIds by collectLastValue(underTest.mobileSubIds)
            mobileIconsInteractor.filteredSubscriptions.value = listOf(SUB_1)

            assertThat(mobileSubIds).isEqualTo(listOf(1))

            mobileIconsInteractor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)

            assertThat(mobileSubIds).isEqualTo(listOf(1, 2))
        }

    companion object {
        private val SUB_1 =
            SubscriptionModel(
                subscriptionId = 1,
                isOpportunistic = false,
                carrierName = "Carrier 1",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val SUB_2 =
            SubscriptionModel(
                subscriptionId = 2,
                isOpportunistic = false,
                carrierName = "Carrier 2",
                profileClass = PROFILE_CLASS_UNSET,
            )
    }
}
