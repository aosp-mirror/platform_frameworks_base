package com.android.systemui.controls.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.templates.TemperatureControlTemplate
import android.service.controls.templates.ThumbnailTemplate
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class TemperatureControlBehaviorTest : SysuiTestCase() {

    @Mock lateinit var controlsMetricsLogger: ControlsMetricsLogger
    @Mock lateinit var controlActionCoordinator: ControlActionCoordinator
    @Mock lateinit var controlsController: ControlsController

    private val fakeSystemClock = FakeSystemClock()
    private val underTest = TemperatureControlBehavior()

    private lateinit var viewHolder: ControlViewHolder

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        viewHolder =
            ControlViewHolder(
                LayoutInflater.from(mContext).inflate(R.layout.controls_base_item, null, false)
                    as ViewGroup,
                controlsController,
                FakeExecutor(fakeSystemClock),
                FakeExecutor(fakeSystemClock),
                controlActionCoordinator,
                controlsMetricsLogger,
                0,
                0,
            )
    }

    @Test
    fun testBehaviourSupportsThumbnailSubBehaviour() {
        val controlWithState =
            ControlWithState(
                ComponentName("test.pkg", "TestClass"),
                ControlInfo(
                    "test_id",
                    "test title",
                    "test subtitle",
                    DeviceTypes.TYPE_AC_UNIT,
                ),
                Control.StatefulBuilder(
                        "",
                        PendingIntent.getActivity(
                            context,
                            0,
                            Intent(),
                            PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setControlTemplate(
                        TemperatureControlTemplate(
                            "test id",
                            ThumbnailTemplate(
                                "test id",
                                false,
                                Icon.createWithContentUri(""),
                                "test description",
                            ),
                            0,
                            0,
                            0
                        )
                    )
                    .setStatus(Control.STATUS_OK)
                    .build()
            )
        viewHolder.bindData(controlWithState, false)
        underTest.initialize(viewHolder)

        underTest.bind(controlWithState, 0) // no crash
    }
}
