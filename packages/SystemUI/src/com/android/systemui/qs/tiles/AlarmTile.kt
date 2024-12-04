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
import android.widget.Button
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.internal.logging.MetricsLogger
import com.android.systemui.animation.Expandable
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.qs.QSTile
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.NextAlarmController
import java.util.Locale
import javax.inject.Inject

class AlarmTile
@Inject
constructor(
    host: QSHost,
    uiEventLogger: QsEventLogger,
    @Background backgroundLooper: Looper,
    @Main mainHandler: Handler,
    falsingManager: FalsingManager,
    metricsLogger: MetricsLogger,
    statusBarStateController: StatusBarStateController,
    activityStarter: ActivityStarter,
    qsLogger: QSLogger,
    private val userTracker: UserTracker,
    nextAlarmController: NextAlarmController,
) :
    QSTileImpl<QSTile.State>(
        host,
        uiEventLogger,
        backgroundLooper,
        mainHandler,
        falsingManager,
        metricsLogger,
        statusBarStateController,
        activityStarter,
        qsLogger,
    ) {

    private var lastAlarmInfo: AlarmManager.AlarmClockInfo? = null
    private var icon: QSTile.Icon? = null
    @VisibleForTesting internal val defaultIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
    private val callback =
        NextAlarmController.NextAlarmChangeCallback { nextAlarm ->
            lastAlarmInfo = nextAlarm
            refreshState()
        }

    init {
        nextAlarmController.observe(this, callback)
    }

    override fun newTileState(): QSTile.State {
        return QSTile.State().apply {
            handlesLongClick = false
            expandedAccessibilityClassName = Button::class.java.name
        }
    }

    override fun handleClick(expandable: Expandable?) {
        val animationController =
            expandable?.activityTransitionController(
                InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE
            )
        val pendingIntent = lastAlarmInfo?.showIntent
        if (pendingIntent != null) {
            mActivityStarter.postStartActivityDismissingKeyguard(pendingIntent, animationController)
        } else {
            mActivityStarter.postStartActivityDismissingKeyguard(
                defaultIntent,
                0,
                animationController,
            )
        }
    }

    override fun handleUpdateState(state: QSTile.State, arg: Any?) {
        if (icon == null) {
            icon = maybeLoadResourceIcon(R.drawable.ic_alarm)
        }
        state.icon = icon
        state.label = tileLabel
        lastAlarmInfo?.let {
            state.secondaryLabel = formatNextAlarm(it)
            state.state = Tile.STATE_ACTIVE
        }
            ?: run {
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

    companion object {
        const val TILE_SPEC = "alarm"
    }
}
