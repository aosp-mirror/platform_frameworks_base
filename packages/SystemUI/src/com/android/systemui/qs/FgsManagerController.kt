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
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.IBinder
import android.os.PowerExemptionManager
import android.os.RemoteException
import android.provider.DeviceConfig.NAMESPACE_SYSTEMUI
import android.text.format.DateUtils
import android.util.ArrayMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.GuardedBy
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_ENABLED
import com.android.systemui.R
import com.android.systemui.animation.DialogLaunchAnimator
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.time.SystemClock
import java.util.Objects
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlin.math.max

class FgsManagerController @Inject constructor(
    private val context: Context,
    @Main private val mainExecutor: Executor,
    @Background private val backgroundExecutor: Executor,
    private val systemClock: SystemClock,
    private val activityManager: IActivityManager,
    private val packageManager: PackageManager,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val dialogLaunchAnimator: DialogLaunchAnimator
) : IForegroundServiceObserver.Stub() {

    companion object {
        private val LOG_TAG = FgsManagerController::class.java.simpleName
    }

    private var isAvailable = false

    private val lock = Any()

    @GuardedBy("lock")
    var initialized = false

    @GuardedBy("lock")
    private val runningServiceTokens = mutableMapOf<UserPackage, StartTimeAndTokens>()

    @GuardedBy("lock")
    private var dialog: SystemUIDialog? = null

    @GuardedBy("lock")
    private val appListAdapter: AppListAdapter = AppListAdapter()

    @GuardedBy("lock")
    private var runningApps: ArrayMap<UserPackage, RunningApp> = ArrayMap()

    interface OnNumberOfPackagesChangedListener {
        fun onNumberOfPackagesChanged(numPackages: Int)
    }

    interface OnDialogDismissedListener {
        fun onDialogDismissed()
    }

    fun init() {
        synchronized(lock) {
            if (initialized) {
                return
            }
            try {
                activityManager.registerForegroundServiceObserver(this)
            } catch (e: RemoteException) {
                e.rethrowFromSystemServer()
            }

            deviceConfigProxy.addOnPropertiesChangedListener(NAMESPACE_SYSTEMUI,
                    backgroundExecutor) {
                isAvailable = it.getBoolean(TASK_MANAGER_ENABLED, isAvailable)
            }

            isAvailable = deviceConfigProxy
                    .getBoolean(NAMESPACE_SYSTEMUI, TASK_MANAGER_ENABLED, true)

            initialized = true
        }
    }

    override fun onForegroundStateChanged(
        token: IBinder,
        packageName: String,
        userId: Int,
        isForeground: Boolean
    ) {
        synchronized(lock) {
            val numPackagesBefore = getNumRunningPackagesLocked()
            val userPackageKey = UserPackage(userId, packageName)
            if (isForeground) {
                runningServiceTokens.getOrPut(userPackageKey, { StartTimeAndTokens(systemClock) })
                        .addToken(token)
            } else {
                if (runningServiceTokens[userPackageKey]?.also {
                            it.removeToken(token) }?.isEmpty() == true) {
                    runningServiceTokens.remove(userPackageKey)
                }
            }

            val numPackagesAfter = getNumRunningPackagesLocked()

            if (numPackagesAfter != numPackagesBefore) {
                onNumberOfPackagesChangedListeners.forEach {
                    backgroundExecutor.execute { it.onNumberOfPackagesChanged(numPackagesAfter) }
                }
            }

            updateAppItemsLocked()
        }
    }

    @GuardedBy("lock")
    val onNumberOfPackagesChangedListeners: MutableSet<OnNumberOfPackagesChangedListener> =
            mutableSetOf()

    @GuardedBy("lock")
    val onDialogDismissedListeners: MutableSet<OnDialogDismissedListener> = mutableSetOf()

    fun addOnNumberOfPackagesChangedListener(listener: OnNumberOfPackagesChangedListener) {
        synchronized(lock) {
            onNumberOfPackagesChangedListeners.add(listener)
        }
    }

    fun removeOnNumberOfPackagesChangedListener(listener: OnNumberOfPackagesChangedListener) {
        synchronized(lock) {
            onNumberOfPackagesChangedListeners.remove(listener)
        }
    }

    fun addOnDialogDismissedListener(listener: OnDialogDismissedListener) {
        synchronized(lock) {
            onDialogDismissedListeners.add(listener)
        }
    }

    fun removeOnDialogDismissedListener(listener: OnDialogDismissedListener) {
        synchronized(lock) {
            onDialogDismissedListeners.remove(listener)
        }
    }

    fun isAvailable(): Boolean {
        return isAvailable
    }

    fun getNumRunningPackages(): Int {
        synchronized(lock) {
            return getNumRunningPackagesLocked()
        }
    }

    private fun getNumRunningPackagesLocked() =
            runningServiceTokens.keys.count { it.uiControl != UIControl.HIDE_ENTRY }

    fun shouldUpdateFooterVisibility() = dialog == null

    fun showDialog(viewLaunchedFrom: View?) {
        synchronized(lock) {
            if (dialog == null) {

                val dialog = SystemUIDialog(context)
                dialog.setTitle(R.string.fgs_manager_dialog_title)

                val dialogContext = dialog.context

                val recyclerView = RecyclerView(dialogContext)
                recyclerView.layoutManager = LinearLayoutManager(dialogContext)
                recyclerView.adapter = appListAdapter

                dialog.setView(recyclerView)

                this.dialog = dialog

                dialog.setOnDismissListener {
                    synchronized(lock) {
                        this.dialog = null
                        updateAppItemsLocked()
                    }
                    onDialogDismissedListeners.forEach {
                        mainExecutor.execute(it::onDialogDismissed)
                    }
                }

                mainExecutor.execute {
                    viewLaunchedFrom
                            ?.let { dialogLaunchAnimator.showFromView(dialog, it) } ?: dialog.show()
                }

                backgroundExecutor.execute {
                    synchronized(lock) {
                        updateAppItemsLocked()
                    }
                }
            }
        }
    }

    @GuardedBy("lock")
    private fun updateAppItemsLocked() {
        if (dialog == null) {
            runningApps.clear()
            return
        }

        val addedPackages = runningServiceTokens.keys.filter {
            it.uiControl != UIControl.HIDE_ENTRY && runningApps[it]?.stopped != true
        }
        val removedPackages = runningApps.keys.filter { !runningServiceTokens.containsKey(it) }

        addedPackages.forEach {
            val ai = packageManager.getApplicationInfoAsUser(it.packageName, 0, it.userId)
            runningApps[it] = RunningApp(it.userId, it.packageName,
                    runningServiceTokens[it]!!.startTime, it.uiControl,
                    ai.loadLabel(packageManager), ai.loadIcon(packageManager))
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

    private fun stopPackage(userId: Int, packageName: String) {
        activityManager.stopAppForUser(packageName, userId)
    }

    private inner class AppListAdapter : RecyclerView.Adapter<AppItemViewHolder>() {
        private val lock = Any()

        @GuardedBy("lock")
        private var data: List<RunningApp> = listOf()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppItemViewHolder {
            return AppItemViewHolder(LayoutInflater.from(parent.context)
                    .inflate(R.layout.fgs_manager_app_item, parent, false))
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
                        DateUtils.LENGTH_MEDIUM)
                stopButton.setOnClickListener {
                    stopButton.setText(R.string.fgs_manager_app_item_stop_button_stopped_label)
                    stopPackage(runningApp.userId, runningApp.packageName)
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

    private inner class UserPackage(
        val userId: Int,
        val packageName: String
    ) {
        val uiControl: UIControl by lazy {
            val uid = packageManager.getPackageUidAsUser(packageName, userId)

            when (activityManager.getBackgroundRestrictionExemptionReason(uid)) {
                PowerExemptionManager.REASON_SYSTEM_UID,
                PowerExemptionManager.REASON_DEVICE_DEMO_MODE -> UIControl.HIDE_ENTRY

                PowerExemptionManager.REASON_DEVICE_OWNER,
                PowerExemptionManager.REASON_PROFILE_OWNER,
                PowerExemptionManager.REASON_PROC_STATE_PERSISTENT,
                PowerExemptionManager.REASON_PROC_STATE_PERSISTENT_UI,
                PowerExemptionManager.REASON_ROLE_DIALER,
                PowerExemptionManager.REASON_SYSTEM_MODULE -> UIControl.HIDE_BUTTON
                else -> UIControl.NORMAL
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other !is UserPackage) {
                return false
            }
            return other.packageName == packageName && other.userId == userId
        }

        override fun hashCode(): Int = Objects.hash(userId, packageName)
    }

    private data class StartTimeAndTokens(
        val systemClock: SystemClock
    ) {
        val startTime = systemClock.elapsedRealtime()
        val tokens = mutableSetOf<IBinder>()

        fun addToken(token: IBinder) {
            tokens.add(token)
        }

        fun removeToken(token: IBinder) {
            tokens.remove(token)
        }

        fun isEmpty(): Boolean {
            return tokens.isEmpty()
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
    }

    private enum class UIControl {
        NORMAL, HIDE_BUTTON, HIDE_ENTRY
    }
}
