package com.android.systemui.qs.tiles

import android.app.AlarmManager
import android.app.PendingIntent
import android.os.Handler
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.policy.NextAlarmController
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class AlarmTileTest : SysuiTestCase() {

    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var nextAlarmController: NextAlarmController
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Mock
    private lateinit var pendingIntent: PendingIntent
    @Captor
    private lateinit var callbackCaptor: ArgumentCaptor<NextAlarmController.NextAlarmChangeCallback>

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: AlarmTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)

        `when`(qsHost.context).thenReturn(mContext)
        `when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)

        tile = AlarmTile(
            qsHost,
            testableLooper.looper,
            Handler(testableLooper.looper),
            metricsLogger,
            statusBarStateController,
            activityStarter,
            qsLogger,
            featureFlags,
            userTracker,
            nextAlarmController
        )

        verify(nextAlarmController).observe(eq(tile), capture(callbackCaptor))
        tile.refreshState()
        testableLooper.processAllMessages()
    }

    @Test
    fun testNotAvailableFeatureFlag() {
        `when`(featureFlags.isAlarmTileAvailable).thenReturn(false)
        assertThat(tile.isAvailable).isFalse()
    }

    @Test
    fun testAvailableFeatureFlag() {
        `when`(featureFlags.isAlarmTileAvailable).thenReturn(true)
        assertThat(tile.isAvailable).isTrue()
    }

    @Test
    fun testDoesntHandleLongClick() {
        assertThat(tile.state.handlesLongClick).isFalse()
    }

    @Test
    fun testInactiveByDefault() {
        assertThat(tile.state.state).isEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun testInactiveAfterNullNextAlarm() {
        callbackCaptor.value.onNextAlarmChanged(null)

        testableLooper.processAllMessages()
        assertThat(tile.state.state).isEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun testActivityStartedWhenNullNextAlarm() {
        callbackCaptor.value.onNextAlarmChanged(null)
        tile.click()

        testableLooper.processAllMessages()
        verify(activityStarter).postStartActivityDismissingKeyguard(tile.defaultIntent, 0)
    }

    @Test
    fun testActiveAfterNextAlarm() {
        val alarmInfo = AlarmManager.AlarmClockInfo(1L, pendingIntent)
        callbackCaptor.value.onNextAlarmChanged(alarmInfo)

        testableLooper.processAllMessages()
        assertThat(tile.state.state).isEqualTo(Tile.STATE_ACTIVE)
    }

    @Test
    fun testActivityStartedWhenNextAlarm() {
        val alarmInfo = AlarmManager.AlarmClockInfo(1L, pendingIntent)
        callbackCaptor.value.onNextAlarmChanged(alarmInfo)
        tile.click()

        testableLooper.processAllMessages()
        verify(activityStarter).postStartActivityDismissingKeyguard(pendingIntent)
    }
}