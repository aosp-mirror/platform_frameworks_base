package com.android.systemui.shade.ui.viewmodel

import android.content.Intent
import android.provider.AlarmClock
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.activityStarter
import com.android.systemui.shade.domain.interactor.privacyChipInteractor
import com.android.systemui.shade.domain.interactor.shadeHeaderClockInteractor
import com.android.systemui.statusbar.pipeline.airplane.data.repository.FakeAirplaneModeRepository
import com.android.systemui.statusbar.pipeline.airplane.domain.interactor.AirplaneModeInteractor
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.FakeMobileIconsInteractor
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconsViewModel
import com.android.systemui.statusbar.pipeline.mobile.util.FakeMobileMappingsProxy
import com.android.systemui.statusbar.pipeline.shared.data.repository.FakeConnectivityRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeHeaderViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

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
                mobileIconsInteractor = mobileIconsInteractor,
                mobileIconsViewModel = mobileIconsViewModel,
                privacyChipInteractor = kosmos.privacyChipInteractor,
                clockInteractor = kosmos.shadeHeaderClockInteractor,
                broadcastDispatcher = fakeBroadcastDispatcher,
            )
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

    @Test
    fun onClockClicked_launchesClock() =
        testScope.runTest {
            val activityStarter = kosmos.activityStarter
            underTest.onClockClicked()

            verify(activityStarter)
                .postStartActivityDismissingKeyguard(
                    argThat(IntentMatcherAction(AlarmClock.ACTION_SHOW_ALARMS)),
                    anyInt(),
                )
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

private class IntentMatcherAction(private val action: String) : ArgumentMatcher<Intent> {
    override fun matches(argument: Intent?): Boolean {
        return argument?.action == action
    }
}
