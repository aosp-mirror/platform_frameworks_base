package com.android.systemui.qs.tiles

import android.app.AlarmManager
import android.app.AlarmManager.AlarmClockInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.service.quicksettings.Tile
import android.text.TextUtils
import android.text.format.DateFormat
import androidx.annotation.VisibleForTesting
import com.android.internal.logging.MetricsLogger
import com.android.systemui.R
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.policy.NextAlarmController
import java.util.Locale
import javax.inject.Inject

class AlarmTile @Inject constructor(
    host: QSHost,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val featureFlags: FeatureFlags,
    private val userTracker: UserTracker,
    nextAlarmController: NextAlarmController
) : QSTileImpl<QSTile.State>(
    host,
    backgroundLooper,
    mainHandler,
    metricsLogger,
    statusBarStateController,
    activityStarter,
    qsLogger
) {

    private var lastAlarmInfo: AlarmManager.AlarmClockInfo? = null
    private val icon = ResourceIcon.get(R.drawable.ic_alarm)
    @VisibleForTesting
    internal val defaultIntent = Intent(AlarmClock.ACTION_SET_ALARM)
    private val callback = NextAlarmController.NextAlarmChangeCallback { nextAlarm ->
        lastAlarmInfo = nextAlarm
        refreshState()
    }

    init {
        nextAlarmController.observe(this, callback)
    }

    override fun isAvailable(): Boolean {
        return featureFlags.isAlarmTileAvailable
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State().apply {
            handlesLongClick = false
        }
    }

    private fun startDefaultSetAlarm() {
        mActivityStarter.postStartActivityDismissingKeyguard(defaultIntent, 0)
    }

    override fun handleClick() {
        lastAlarmInfo?.showIntent?.let {
                mActivityStarter.postStartActivityDismissingKeyguard(it)
        } ?: startDefaultSetAlarm()
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        state.icon = icon
        state.label = tileLabel
        lastAlarmInfo?.let {
            state.secondaryLabel = formatNextAlarm(it)
            state.state = Tile.STATE_ACTIVE
        } ?: run {
            state.secondaryLabel = mContext.getString(R.string.qs_alarm_tile_no_alarm)
            state.state = Tile.STATE_INACTIVE
        }
        state.contentDescription = TextUtils.concat(state.label, ", ", state.secondaryLabel)
    }

    override fun getTileLabel(): CharSequence {
        return mContext.getString(R.string.status_bar_alarm)
    }

    private fun formatNextAlarm(info: AlarmClockInfo): String {
        val skeleton = if (use24HourFormat()) "EHm" else "Ehma"
        val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton)
        return DateFormat.format(pattern, info.triggerTime).toString()
    }

    private fun use24HourFormat(): Boolean {
        return DateFormat.is24HourFormat(mContext, userTracker.userId)
    }

    override fun getMetricsCategory(): Int {
        return 0
    }

    override fun getLongClickIntent(): Intent? {
        return null
    }
}