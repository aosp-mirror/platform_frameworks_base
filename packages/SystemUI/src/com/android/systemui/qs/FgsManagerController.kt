/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs

import android.app.IActivityManager
import android.app.IForegroundServiceObserver
import android.app.job.IUserVisibleJobObserver
import android.app.job.JobScheduler
import android.app.job.UserVisibleJobSummary
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.UserInfo
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.PowerExemptionManager
import android.os.RemoteException
import android.os.UserHandle
import android.provider.DeviceConfig.NAMESPACE_SYSTEMUI
import android.text.format.DateUtils
import android.util.ArrayMap
import android.util.IndentingPrintWriter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.GuardedBy
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_INFORM_JOB_SCHEDULER_OF_PENDING_APP_STOP
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_FOOTER_DOT
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_SHOW_USER_VISIBLE_JOBS
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.Dumpable
import com.android.systemui.res.R
import com.android.systemui.animation.DialogCuj
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.system.SysUiStatsLog
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.indentIfPossible
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.util.Objects
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A controller for the dealing with services running in the foreground. */
interface FgsManagerController {

    /** The number of packages with a service running in the foreground. */
    val numRunningPackages: Int

    /**
     * Whether there were new changes to the foreground services since the last [shown][showDialog]
     * dialog was dismissed.
     */
    val newChangesSinceDialogWasDismissed: Boolean

    /**
     * Whether we should show a dot to indicate when [newChangesSinceDialogWasDismissed] is true.
     */
    val showFooterDot: StateFlow<Boolean>

    val includesUserVisibleJobs: Boolean

    /**
     * Initialize this controller. This should be called once, before this controller is used for
     * the first time.
     */
    fun init()

    /**
     * Show the foreground services dialog. The dialog will be expanded from [expandable] if
     * it's not `null`.
     */
    fun showDialog(expandable: Expandable?)

    /** Add a [OnNumberOfPackagesChangedListener]. */
    fun addOnNumberOfPackagesChangedListener(listener: OnNumberOfPackagesChangedListener)

    /** Remove a [OnNumberOfPackagesChangedListener]. */
    fun removeOnNumberOfPackagesChangedListener(listener: OnNumberOfPackagesChangedListener)

    /** Add a [OnDialogDismissedListener]. */
    fun addOnDialogDismissedListener(listener: OnDialogDismissedListener)

    /** Remove a [OnDialogDismissedListener]. */
    fun removeOnDialogDismissedListener(listener: OnDialogDismissedListener)

    @VisibleForTesting
    fun visibleButtonsCount(): Int

    interface OnNumberOfPackagesChangedListener {
        /** Called when [numRunningPackages] changed. */
        fun onNumberOfPackagesChanged(numPackages: Int)
    }

    interface OnDialogDismissedListener {
        /** Called when a dialog shown using [showDialog] was dismissed. */
        fun onDialogDismissed()
    }
}

@SysUISingleton
class FgsManagerControllerImpl @Inject constructor(
    private val context: Context,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
    private val systemClock: SystemClock,
    private val activityManager: IActivityManager,
    private val jobScheduler: JobScheduler,
    private val packageManager: PackageManager,
    private val userTracker: UserTracker,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val dialogLaunchAnimator: DialogLaunchAnimator,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val dumpManager: DumpManager
) : Dumpable, FgsManagerController {

    companion object {
        private const val INTERACTION_JANK_TAG = "active_background_apps"
        private const val DEFAULT_TASK_MANAGER_SHOW_FOOTER_DOT = false
        private const val DEFAULT_TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS = true
        private const val DEFAULT_TASK_MANAGER_SHOW_USER_VISIBLE_JOBS = true
        private const val DEFAULT_TASK_MANAGER_INFORM_JOB_SCHEDULER_OF_PENDING_APP_STOP = true
    }

    override var newChangesSinceDialogWasDismissed = false
        private set

    val _showFooterDot = MutableStateFlow(false)
    override val showFooterDot: StateFlow<Boolean> = _showFooterDot.asStateFlow()

    private var showStopBtnForUserAllowlistedApps = false

    private var showUserVisibleJobs = DEFAULT_TASK_MANAGER_SHOW_USER_VISIBLE_JOBS

    private var informJobSchedulerOfPendingAppStop =
        DEFAULT_TASK_MANAGER_INFORM_JOB_SCHEDULER_OF_PENDING_APP_STOP

    override val includesUserVisibleJobs: Boolean
        get() = showUserVisibleJobs

    override val numRunningPackages: Int
        get() {
            synchronized(lock) {
                return getNumVisiblePackagesLocked()
            }
        }

    private val lock = Any()

    @GuardedBy("lock")
    var initialized = false

    @GuardedBy("lock")
    private var lastNumberOfVisiblePackages = 0

    @GuardedBy("lock")
    private var currentProfileIds = mutableSetOf<Int>()

    @GuardedBy("lock")
    private val runningTaskIdentifiers = mutableMapOf<UserPackage, StartTimeAndIdentifiers>()

    @GuardedBy("lock")
    private var dialog: SystemUIDialog? = null

    @GuardedBy("lock")
    private val appListAdapter: AppListAdapter = AppListAdapter()

    /* Only mutate on the background thread */
    private var runningApps: ArrayMap<UserPackage, RunningApp> = ArrayMap()

    private val userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {}

        override fun onProfilesChanged(profiles: List<UserInfo>) {
            synchronized(lock) {
                currentProfileIds.clear()
                currentProfileIds.addAll(profiles.map { it.id })
                lastNumberOfVisiblePackages = 0
                updateNumberOfVisibleRunningPackagesLocked()
            }
        }
    }

    private val foregroundServiceObserver = ForegroundServiceObserver()

    private val userVisibleJobObserver = UserVisibleJobObserver()

    override fun init() {
        synchronized(lock) {
            if (initialized) {
                return
            }

            showUserVisibleJobs = deviceConfigProxy.getBoolean(
                NAMESPACE_SYSTEMUI,
                TASK_MANAGER_SHOW_USER_VISIBLE_JOBS, DEFAULT_TASK_MANAGER_SHOW_USER_VISIBLE_JOBS)

            informJobSchedulerOfPendingAppStop = deviceConfigProxy.getBoolean(
                NAMESPACE_SYSTEMUI,
                TASK_MANAGER_INFORM_JOB_SCHEDULER_OF_PENDING_APP_STOP,
                DEFAULT_TASK_MANAGER_INFORM_JOB_SCHEDULER_OF_PENDING_APP_STOP)

            try {
                activityManager.registerForegroundServiceObserver(foregroundServiceObserver)
                // Clumping FGS and user-visible jobs here and showing a single entry and button
                // for them is the easiest way to get user-visible jobs showing in Task Manager.
                // Ideally, we would have dedicated UI in task manager for the user-visible jobs.
                // TODO(255768978): distinguish jobs from FGS and give users more control
                if (showUserVisibleJobs) {
                    jobScheduler.registerUserVisibleJobObserver(userVisibleJobObserver)
                }
            } catch (e: RemoteException) {
                e.rethrowFromSystemServer()
            }

            userTracker.addCallback(userTrackerCallback, backgroundExecutor)

            currentProfileIds.addAll(userTracker.userProfiles.map { it.id })

            deviceConfigProxy.addOnPropertiesChangedListener(
                NAMESPACE_SYSTEMUI,
                backgroundExecutor
            ) {
                _showFooterDot.value =
                    it.getBoolean(TASK_MANAGER_SHOW_FOOTER_DOT, _showFooterDot.value)
                showStopBtnForUserAllowlistedApps = it.getBoolean(
                    TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS,
                    showStopBtnForUserAllowlistedApps)
                var wasShowingUserVisibleJobs = showUserVisibleJobs
                showUserVisibleJobs = it.getBoolean(
                    TASK_MANAGER_SHOW_USER_VISIBLE_JOBS, showUserVisibleJobs)
                if (showUserVisibleJobs != wasShowingUserVisibleJobs) {
                    onShowUserVisibleJobsFlagChanged()
                }
                informJobSchedulerOfPendingAppStop = it.getBoolean(
                    TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS,
                    informJobSchedulerOfPendingAppStop)
            }
            _showFooterDot.value = deviceConfigProxy.getBoolean(
                NAMESPACE_SYSTEMUI,
                TASK_MANAGER_SHOW_FOOTER_DOT, DEFAULT_TASK_MANAGER_SHOW_FOOTER_DOT
            )
            showStopBtnForUserAllowlistedApps = deviceConfigProxy.getBoolean(
                NAMESPACE_SYSTEMUI,
                TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS,
                DEFAULT_TASK_MANAGER_SHOW_STOP_BUTTON_FOR_USER_ALLOWLISTED_APPS)

            dumpManager.registerDumpable(this)

            broadcastDispatcher.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (intent.action == Intent.ACTION_SHOW_FOREGROUND_SERVICE_MANAGER) {
                            showDialog(null)
                        }
                    }
                },
                IntentFilter(Intent.ACTION_SHOW_FOREGROUND_SERVICE_MANAGER),
                executor = mainExecutor,
                flags = Context.RECEIVER_NOT_EXPORTED
            )

            initialized = true
        }
    }

    @GuardedBy("lock")
    private val onNumberOfPackagesChangedListeners =
        mutableSetOf<FgsManagerController.OnNumberOfPackagesChangedListener>()

    @GuardedBy("lock")
    private val onDialogDismissedListeners =
        mutableSetOf<FgsManagerController.OnDialogDismissedListener>()

    override fun addOnNumberOfPackagesChangedListener(
        listener: FgsManagerController.OnNumberOfPackagesChangedListener
    ) {
        synchronized(lock) {
            onNumberOfPackagesChangedListeners.add(listener)
        }
    }

    override fun removeOnNumberOfPackagesChangedListener(
        listener: FgsManagerController.OnNumberOfPackagesChangedListener
    ) {
        synchronized(lock) {
            onNumberOfPackagesChangedListeners.remove(listener)
        }
    }

    override fun addOnDialogDismissedListener(
        listener: FgsManagerController.OnDialogDismissedListener
    ) {
        synchronized(lock) {
            onDialogDismissedListeners.add(listener)
        }
    }

    override fun removeOnDialogDismissedListener(
        listener: FgsManagerController.OnDialogDismissedListener
    ) {
        synchronized(lock) {
            onDialogDismissedListeners.remove(listener)
        }
    }

    private fun getNumVisiblePackagesLocked(): Int {
        return runningTaskIdentifiers.keys.count {
            it.uiControl != UIControl.HIDE_ENTRY && currentProfileIds.contains(it.userId)
        }
    }

    private fun updateNumberOfVisibleRunningPackagesLocked() {
        val num = getNumVisiblePackagesLocked()
        if (num != lastNumberOfVisiblePackages) {
            lastNumberOfVisiblePackages = num
            newChangesSinceDialogWasDismissed = true
            onNumberOfPackagesChangedListeners.forEach {
                backgroundExecutor.execute {
                    it.onNumberOfPackagesChanged(num)
                }
            }
        }
    }

    override fun visibleButtonsCount(): Int {
        synchronized(lock) {
            return getNumVisibleButtonsLocked()
        }
    }

    private fun getNumVisibleButtonsLocked(): Int {
        return runningTaskIdentifiers.keys.count {
            it.uiControl != UIControl.HIDE_BUTTON && currentProfileIds.contains(it.userId)
        }
    }

    override fun showDialog(expandable: Expandable?) {
        synchronized(lock) {
            if (dialog == null) {
                val dialog = SystemUIDialog(context)
                dialog.setTitle(R.string.fgs_manager_dialog_title)
                dialog.setMessage(R.string.fgs_manager_dialog_message)

                val dialogContext = dialog.context

                val recyclerView = RecyclerView(dialogContext)
                recyclerView.layoutManager = LinearLayoutManager(dialogContext)
                recyclerView.adapter = appListAdapter

                val topSpacing = dialogContext.resources
                    .getDimensionPixelSize(R.dimen.fgs_manager_list_top_spacing)
                dialog.setView(recyclerView, 0, topSpacing, 0, 0)

                this.dialog = dialog

                dialog.setOnDismissListener {
                    newChangesSinceDialogWasDismissed = false
                    synchronized(lock) {
                        this.dialog = null
                        updateAppItemsLocked()
                    }
                    onDialogDismissedListeners.forEach {
                        mainExecutor.execute(it::onDialogDismissed)
                    }
                }

                mainExecutor.execute {
                    val controller =
                        expandable?.dialogLaunchController(
                            DialogCuj(
                                InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                INTERACTION_JANK_TAG,
                            )
                        )
                    if (controller != null) {
                        dialogLaunchAnimator.show(dialog, controller)
                    } else {
                        dialog.show()
                    }
                }

                updateAppItemsLocked(refreshUiControls = true)
            }
        }
    }

    @GuardedBy("lock")
    private fun updateAppItemsLocked(refreshUiControls: Boolean = false) {
        if (dialog == null) {
            backgroundExecutor.execute {
                clearRunningApps()
            }
            return
        }

        val packagesToStartTime = runningTaskIdentifiers.mapValues { it.value.startTime }
        val profileIds = currentProfileIds.toSet()
        backgroundExecutor.execute {
            updateAppItems(packagesToStartTime, profileIds, refreshUiControls)
        }
    }

    /**
     * Must be called on the background thread.
     */
    @WorkerThread
    private fun updateAppItems(
        packages: Map<UserPackage, Long>,
        profileIds: Set<Int>,
        refreshUiControls: Boolean = true
    ) {
        if (refreshUiControls) {
            packages.forEach { (pkg, _) ->
                pkg.updateUiControl()
            }
        }

        val addedPackages = packages.keys.filter {
            profileIds.contains(it.userId) &&
                    it.uiControl != UIControl.HIDE_ENTRY && runningApps[it]?.stopped != true
        }
        val removedPackages = runningApps.keys.filter { it !in packages }

        addedPackages.forEach {
            val ai = packageManager.getApplicationInfoAsUser(it.packageName, 0, it.userId)
            runningApps[it] = RunningApp(
                it.userId, it.packageName,
                packages[it]!!, it.uiControl,
                packageManager.getApplicationLabel(ai),
                packageManager.getUserBadgedIcon(
                    packageManager.getApplicationIcon(ai), UserHandle.of(it.userId)
                )
            )
            logEvent(stopped = false, it.packageName, it.userId, runningApps[it]!!.timeStarted)
        }

        removedPackages.forEach { pkg ->
            val ra = runningApps[pkg]!!
            val ra2 = ra.copy().also {
                it.stopped = true
                it.appLabel = ra.appLabel
                it.icon = ra.icon
            }
            runningApps[pkg] = ra2
        }

        mainExecutor.execute {
            appListAdapter
                .setData(runningApps.values.toList().sortedByDescending { it.timeStarted })
        }
    }

    /**
     * Must be called on the background thread.
     */
    @WorkerThread
    private fun clearRunningApps() {
        runningApps.clear()
    }

    private fun stopPackage(userId: Int, packageName: String, timeStarted: Long) {
        logEvent(stopped = true, packageName, userId, timeStarted)
        val userPackageKey = UserPackage(userId, packageName)
        if (showUserVisibleJobs || informJobSchedulerOfPendingAppStop) {
            // TODO(255768978): allow fine-grained job control
            jobScheduler.notePendingUserRequestedAppStop(packageName, userId, "task manager")
        }
        activityManager.stopAppForUser(packageName, userId)
    }

    private fun onShowUserVisibleJobsFlagChanged() {
        if (showUserVisibleJobs) {
            jobScheduler.registerUserVisibleJobObserver(userVisibleJobObserver)
        } else {
            jobScheduler.unregisterUserVisibleJobObserver(userVisibleJobObserver)

            synchronized(lock) {
                for ((userPackage, startTimeAndIdentifiers) in runningTaskIdentifiers) {
                    if (startTimeAndIdentifiers.hasFgs()) {
                        // The app still has FGS running, so all we need to do is remove
                        // the job summaries
                        startTimeAndIdentifiers.clearJobSummaries()
                    } else {
                        // The app only has user-visible jobs running, so remove it from
                        // the map altogether
                        runningTaskIdentifiers.remove(userPackage)
                    }
                }

                updateNumberOfVisibleRunningPackagesLocked()

                updateAppItemsLocked()
            }
        }
    }

    private fun logEvent(stopped: Boolean, packageName: String, userId: Int, timeStarted: Long) {
        val timeLogged = systemClock.elapsedRealtime()
        val event = if (stopped) {
            SysUiStatsLog.TASK_MANAGER_EVENT_REPORTED__EVENT__STOPPED
        } else {
            SysUiStatsLog.TASK_MANAGER_EVENT_REPORTED__EVENT__VIEWED
        }
        backgroundExecutor.execute {
            val uid = packageManager.getPackageUidAsUser(packageName, userId)
            SysUiStatsLog.write(
                SysUiStatsLog.TASK_MANAGER_EVENT_REPORTED, uid, event,
                timeLogged - timeStarted
            )
        }
    }

    private inner class AppListAdapter : RecyclerView.Adapter<AppItemViewHolder>() {
        private val lock = Any()

        @GuardedBy("lock")
        private var data: List<RunningApp> = listOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder {
            return AppItemViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.fgs_manager_app_item, parent, false)
            )
        }

        override fun onBindViewHolder(holder: AppItemViewHolder, position: Int) {
            var runningApp: RunningApp
            synchronized(lock) {
                runningApp = data[position]
            }
            with(holder) {
                iconView.setImageDrawable(runningApp.icon)
                appLabelView.text = runningApp.appLabel
                durationView.text = DateUtils.formatDuration(
                    max(systemClock.elapsedRealtime() - runningApp.timeStarted, 60000),
                    DateUtils.LENGTH_MEDIUM
                )
                stopButton.setOnClickListener {
                    stopButton.setText(R.string.fgs_manager_app_item_stop_button_stopped_label)
                    stopPackage(runningApp.userId, runningApp.packageName, runningApp.timeStarted)
                }
                if (runningApp.uiControl == UIControl.HIDE_BUTTON) {
                    stopButton.visibility = View.INVISIBLE
                }
                if (runningApp.stopped) {
                    stopButton.isEnabled = false
                    stopButton.setText(R.string.fgs_manager_app_item_stop_button_stopped_label)
                    durationView.visibility = View.GONE
                } else {
                    stopButton.isEnabled = true
                    stopButton.setText(R.string.fgs_manager_app_item_stop_button_label)
                    durationView.visibility = View.VISIBLE
                }
            }
        }

        override fun getItemCount(): Int {
            return data.size
        }

        fun setData(newData: List<RunningApp>) {
            var oldData = data
            data = newData

            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int {
                    return oldData.size
                }

                override fun getNewListSize(): Int {
                    return newData.size
                }

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int):
                    Boolean {
                    return oldData[oldItemPosition] == newData[newItemPosition]
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int):
                    Boolean {
                    return oldData[oldItemPosition].stopped == newData[newItemPosition].stopped
                }
            }).dispatchUpdatesTo(this)
        }
    }

    private inner class ForegroundServiceObserver : IForegroundServiceObserver.Stub() {
        override fun onForegroundStateChanged(
                token: IBinder,
                packageName: String,
                userId: Int,
                isForeground: Boolean
        ) {
            synchronized(lock) {
                val userPackageKey = UserPackage(userId, packageName)
                if (isForeground) {
                    runningTaskIdentifiers
                            .getOrPut(userPackageKey) { StartTimeAndIdentifiers(systemClock) }
                            .addFgsToken(token)
                } else {
                    if (runningTaskIdentifiers[userPackageKey]?.also {
                                it.removeFgsToken(token)
                            }?.isEmpty() == true
                    ) {
                        runningTaskIdentifiers.remove(userPackageKey)
                    }
                }

                updateNumberOfVisibleRunningPackagesLocked()

                updateAppItemsLocked()
            }
        }
    }

    private inner class UserVisibleJobObserver : IUserVisibleJobObserver.Stub() {
        override fun onUserVisibleJobStateChanged(
                summary: UserVisibleJobSummary,
                isRunning: Boolean
        ) {
            synchronized(lock) {
                val userPackageKey = UserPackage(
                        UserHandle.getUserId(summary.callingUid), summary.callingPackageName)
                if (isRunning) {
                    runningTaskIdentifiers
                            .getOrPut(userPackageKey) { StartTimeAndIdentifiers(systemClock) }
                            .addJobSummary(summary)
                } else {
                    if (runningTaskIdentifiers[userPackageKey]?.also {
                                it.removeJobSummary(summary)
                            }?.isEmpty() == true
                    ) {
                        runningTaskIdentifiers.remove(userPackageKey)
                    }
                }

                updateNumberOfVisibleRunningPackagesLocked()

                updateAppItemsLocked()
            }
        }
    }

    private inner class UserPackage(
        val userId: Int,
        val packageName: String
    ) {
        val uid by lazy { packageManager.getPackageUidAsUser(packageName, userId) }
        var backgroundRestrictionExemptionReason = PowerExemptionManager.REASON_DENIED

        private var uiControlInitialized = false
        var uiControl: UIControl = UIControl.NORMAL
            get() {
                if (!uiControlInitialized) {
                    updateUiControl()
                }
                return field
            }
            private set

        fun updateUiControl() {
            backgroundRestrictionExemptionReason =
                activityManager.getBackgroundRestrictionExemptionReason(uid)
            uiControl = when (backgroundRestrictionExemptionReason) {
                PowerExemptionManager.REASON_SYSTEM_UID,
                PowerExemptionManager.REASON_DEVICE_DEMO_MODE -> UIControl.HIDE_ENTRY

                PowerExemptionManager.REASON_SYSTEM_ALLOW_LISTED,
                PowerExemptionManager.REASON_DEVICE_OWNER,
                PowerExemptionManager.REASON_DISALLOW_APPS_CONTROL,
                PowerExemptionManager.REASON_DPO_PROTECTED_APP,
                PowerExemptionManager.REASON_PROFILE_OWNER,
                PowerExemptionManager.REASON_ACTIVE_DEVICE_ADMIN,
                PowerExemptionManager.REASON_PROC_STATE_PERSISTENT,
                PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI,
                PowerExemptionManager.REASON_ROLE_DIALER,
                PowerExemptionManager.REASON_SYSTEM_MODULE,
                PowerExemptionManager.REASON_SYSTEM_EXEMPT_APP_OP -> UIControl.HIDE_BUTTON

                PowerExemptionManager.REASON_ALLOWLISTED_PACKAGE ->
                    if (showStopBtnForUserAllowlistedApps) {
                        UIControl.NORMAL
                    } else {
                        UIControl.HIDE_BUTTON
                    }
                else -> UIControl.NORMAL
            }
            uiControlInitialized = true
        }

        override fun equals(other: Any?): Boolean {
            if (other !is UserPackage) {
                return false
            }
            return other.packageName == packageName && other.userId == userId
        }

        override fun hashCode(): Int = Objects.hash(userId, packageName)

        fun dump(pw: PrintWriter) {
            pw.println("UserPackage: [")
            pw.indentIfPossible {
                pw.println("userId=$userId")
                pw.println("packageName=$packageName")
                pw.println("uiControl=$uiControl (reason=$backgroundRestrictionExemptionReason)")
            }
            pw.println("]")
        }
    }

    private data class StartTimeAndIdentifiers(
        val systemClock: SystemClock
    ) {
        val startTime = systemClock.elapsedRealtime()
        val fgsTokens = mutableSetOf<IBinder>()
        val jobSummaries = mutableSetOf<UserVisibleJobSummary>()

        fun addJobSummary(summary: UserVisibleJobSummary) {
            jobSummaries.add(summary)
        }

        fun clearJobSummaries() {
            jobSummaries.clear()
        }

        fun removeJobSummary(summary: UserVisibleJobSummary) {
            jobSummaries.remove(summary)
        }

        fun addFgsToken(token: IBinder) {
            fgsTokens.add(token)
        }

        fun removeFgsToken(token: IBinder) {
            fgsTokens.remove(token)
        }

        fun hasFgs(): Boolean {
            return !fgsTokens.isEmpty()
        }

        fun hasRunningJobs(): Boolean {
            return !jobSummaries.isEmpty()
        }

        fun isEmpty(): Boolean {
            return fgsTokens.isEmpty() && jobSummaries.isEmpty()
        }

        fun dump(pw: PrintWriter) {
            pw.println("StartTimeAndIdentifiers: [")
            pw.indentIfPossible {
                pw.println(
                    "startTime=$startTime (time running =" +
                        " ${systemClock.elapsedRealtime() - startTime}ms)"
                )
                pw.println("fgs tokens: [")
                pw.indentIfPossible {
                    for (token in fgsTokens) {
                        pw.println("$token")
                    }
                }
                pw.println("job summaries: [")
                pw.indentIfPossible {
                    for (summary in jobSummaries) {
                        pw.println("$summary")
                    }
                }
                pw.println("]")
            }
            pw.println("]")
        }
    }

    private class AppItemViewHolder(parent: View) : RecyclerView.ViewHolder(parent) {
        val appLabelView: TextView = parent.requireViewById(R.id.fgs_manager_app_item_label)
        val durationView: TextView = parent.requireViewById(R.id.fgs_manager_app_item_duration)
        val iconView: ImageView = parent.requireViewById(R.id.fgs_manager_app_item_icon)
        val stopButton: Button = parent.requireViewById(R.id.fgs_manager_app_item_stop_button)
    }

    private data class RunningApp(
        val userId: Int,
        val packageName: String,
        val timeStarted: Long,
        val uiControl: UIControl
    ) {
        constructor(
            userId: Int,
            packageName: String,
            timeStarted: Long,
            uiControl: UIControl,
            appLabel: CharSequence,
            icon: Drawable
        ) : this(userId, packageName, timeStarted, uiControl) {
            this.appLabel = appLabel
            this.icon = icon
        }

        // variables to keep out of the generated equals()
        var appLabel: CharSequence = ""
        var icon: Drawable? = null
        var stopped = false

        fun dump(pw: PrintWriter, systemClock: SystemClock) {
            pw.println("RunningApp: [")
            pw.indentIfPossible {
                pw.println("userId=$userId")
                pw.println("packageName=$packageName")
                pw.println(
                    "timeStarted=$timeStarted (time since start =" +
                        " ${systemClock.elapsedRealtime() - timeStarted}ms)"
                )
                pw.println("uiControl=$uiControl")
                pw.println("appLabel=$appLabel")
                pw.println("icon=$icon")
                pw.println("stopped=$stopped")
            }
            pw.println("]")
        }
    }

    private enum class UIControl {
        NORMAL, HIDE_BUTTON, HIDE_ENTRY
    }

    override fun dump(printwriter: PrintWriter, args: Array<out String>) {
        val pw = IndentingPrintWriter(printwriter)
        synchronized(lock) {
            pw.println("current user profiles = $currentProfileIds")
            pw.println("newChangesSinceDialogWasShown=$newChangesSinceDialogWasDismissed")
            pw.println("Running task identifiers: [")
            pw.indentIfPossible {
                runningTaskIdentifiers.forEach { (userPackage, startTimeAndIdentifiers) ->
                    pw.println("{")
                    pw.indentIfPossible {
                        userPackage.dump(pw)
                        startTimeAndIdentifiers.dump(pw)
                    }
                    pw.println("}")
                }
            }
            pw.println("]")

            pw.println("Loaded package UI info: [")
            pw.indentIfPossible {
                runningApps.forEach { (userPackage, runningApp) ->
                    pw.println("{")
                    pw.indentIfPossible {
                        userPackage.dump(pw)
                        runningApp.dump(pw, systemClock)
                    }
                    pw.println("}")
                }
            }
            pw.println("]")
        }
    }
}
