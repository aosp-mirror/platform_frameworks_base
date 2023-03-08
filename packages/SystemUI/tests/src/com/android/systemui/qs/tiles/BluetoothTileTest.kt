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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.statusbar.policy.BluetoothController
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
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
    private lateinit var qsHost: QSHost
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

    @After
    fun tearDown() {
        tile.destroy()
        testableLooper.processAllMessages()
    }

    @Test
    fun testRestrictionChecked() {
        tile.refreshState()
        testableLooper.processAllMessages()

        assertThat(tile.restrictionChecked).isEqualTo(UserManager.DISALLOW_BLUETOOTH)
    }

    @Test
    fun testIcon_whenDisabled_isOffState() {
        val state = QSTile.BooleanState()
        disableBluetooth()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
                .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_bluetooth_icon_off))
    }

    @Test
    fun testIcon_whenDisconnected_isOffState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothDisconnected()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
                .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_bluetooth_icon_off))
    }

    @Test
    fun testIcon_whenConnected_isOnState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothConnected()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
                .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_bluetooth_icon_on))
    }

    @Test
    fun testIcon_whenConnecting_isSearchState() {
        val state = QSTile.BooleanState()
        enableBluetooth()
        setBluetoothConnecting()

        tile.handleUpdateState(state, /* arg= */ null)

        assertThat(state.icon)
                .isEqualTo(QSTileImpl.ResourceIcon.get(R.drawable.qs_bluetooth_icon_search))
    }

    private class FakeBluetoothTile(
        qsHost: QSHost,
        backgroundLooper: Looper,
        mainHandler: Handler,
        falsingManager: FalsingManager,
        metricsLogger: MetricsLogger,
        statusBarStateController: StatusBarStateController,
        activityStarter: ActivityStarter,
        qsLogger: QSLogger,
        bluetoothController: BluetoothController
    ) : BluetoothTile(
        qsHost,
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

    fun enableBluetooth() {
        `when`(bluetoothController.isBluetoothEnabled).thenReturn(true)
    }

    fun disableBluetooth() {
        `when`(bluetoothController.isBluetoothEnabled).thenReturn(false)
    }

    fun setBluetoothDisconnected() {
        `when`(bluetoothController.isBluetoothConnecting).thenReturn(false)
        `when`(bluetoothController.isBluetoothConnected).thenReturn(false)
    }

    fun setBluetoothConnected() {
        `when`(bluetoothController.isBluetoothConnecting).thenReturn(false)
        `when`(bluetoothController.isBluetoothConnected).thenReturn(true)
    }

    fun setBluetoothConnecting() {
        `when`(bluetoothController.isBluetoothConnected).thenReturn(false)
        `when`(bluetoothController.isBluetoothConnecting).thenReturn(true)
    }
}
