package com.android.systemui.qs.tiles.viewmodel

import android.graphics.drawable.ShapeDrawable
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.MediumTest
import com.android.internal.logging.InstanceId
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.ContentDescription
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.FakeQSTileUserActionInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataRequest
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.StateUpdateTrigger
import com.android.systemui.qs.tiles.base.viewmodel.BaseQSTileViewModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// TODO(b/299909368): Add more tests
@MediumTest
@RoboPilotTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class QSTileViewModelInterfaceComplianceTest : SysuiTestCase() {

    private val fakeQSTileDataInteractor = FakeQSTileDataInteractor<Any>()
    private val fakeQSTileUserActionInteractor = FakeQSTileUserActionInteractor<Any>()

    private val testCoroutineDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testCoroutineDispatcher)

    private lateinit var underTest: QSTileViewModel

    @Before
    fun setup() {
        underTest = createViewModel(testScope)
    }

    @Test
    fun testDoesntListenStateUntilCreated() =
        testScope.runTest {
            assertThat(fakeQSTileDataInteractor.dataRequests).isEmpty()

            underTest.onLifecycle(QSTileLifecycle.ALIVE)
            underTest.onUserIdChanged(1)

            assertThat(fakeQSTileDataInteractor.dataRequests).isEmpty()

            underTest.state.launchIn(backgroundScope)
            runCurrent()

            assertThat(fakeQSTileDataInteractor.dataRequests).isNotEmpty()
            assertThat(fakeQSTileDataInteractor.dataRequests.first())
                .isEqualTo(QSTileDataRequest(1, StateUpdateTrigger.InitialRequest))
        }

    private fun createViewModel(
        scope: TestScope,
        config: QSTileConfig = TEST_QS_TILE_CONFIG,
    ): QSTileViewModel =
        object :
            BaseQSTileViewModel<Any>(
                config,
                fakeQSTileUserActionInteractor,
                fakeQSTileDataInteractor,
                object : QSTileDataToStateMapper<Any> {
                    override fun map(config: QSTileConfig, data: Any): QSTileState =
                        QSTileState.build(Icon.Resource(0, ContentDescription.Resource(0)), "") {}
                },
                testCoroutineDispatcher,
                tileScope = scope.backgroundScope,
            ) {}

    private companion object {

        val TEST_QS_TILE_CONFIG =
            QSTileConfig(
                TileSpec.create("default"),
                Icon.Loaded(ShapeDrawable(), null),
                0,
                InstanceId.fakeInstanceId(0),
            )
    }
}
