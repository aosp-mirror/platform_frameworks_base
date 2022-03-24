package com.android.systemui.qs.tiles

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSTileHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.policy.BluetoothController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
class BluetoothTileTest : SysuiTestCase() {

    @Mock
    private lateinit var mockContext: Context
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var qsHost: QSTileHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    private val falsingManager = FalsingManagerFake()
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var bluetoothController: BluetoothController

    private val uiEventLogger = UiEventLoggerFake()
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: FakeBluetoothTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        Mockito.`when`(qsHost.context).thenReturn(mockContext)
        Mockito.`when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        tile = FakeBluetoothTile(
            qsHost,
            testableLooper.looper,
            Handler(testableLooper.looper),
            falsingManager,
            metricsLogger,
            statusBarStateController,
            activityStarter,
            qsLogger,
            bluetoothController
        )

        tile.initialize()
        testableLooper.processAllMessages()
    }

    @Test
    fun testRestrictionChecked() {
        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.restrictionChecked).isEqualTo(UserManager.DISALLOW_BLUETOOTH)
    }

    private class FakeBluetoothTile(
        qsTileHost: QSTileHost,
        backgroundLooper: Looper,
        mainHandler: Handler,
        falsingManager: FalsingManager,
        metricsLogger: MetricsLogger,
        statusBarStateController: StatusBarStateController,
        activityStarter: ActivityStarter,
        qsLogger: QSLogger,
        bluetoothController: BluetoothController
    ) : BluetoothTile(
        qsTileHost,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
        bluetoothController
    ) {
        var restrictionChecked: String? = null

        override fun checkIfRestrictionEnforcedByAdminOnly(
            state: QSTile.State?,
            userRestriction: String?
        ) {
            restrictionChecked = userRestriction
        }
    }
}