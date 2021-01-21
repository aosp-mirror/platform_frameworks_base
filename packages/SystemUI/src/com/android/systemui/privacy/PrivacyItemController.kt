/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.privacy

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.UserInfo
import android.os.UserHandle
import android.provider.DeviceConfig
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.config.sysui.SystemUiDeviceConfigFlags
import com.android.systemui.Dumpable
import com.android.systemui.appops.AppOpItem
import com.android.systemui.appops.AppOpsController
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.privacy.logging.PrivacyLogger
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.FileDescriptor
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.Executor
import javax.inject.Inject

@SysUISingleton
class PrivacyItemController @Inject constructor(
    private val appOpsController: AppOpsController,
    @Main uiExecutor: DelayableExecutor,
    @Background private val bgExecutor: DelayableExecutor,
    private val deviceConfigProxy: DeviceConfigProxy,
    private val userTracker: UserTracker,
    private val logger: PrivacyLogger,
    dumpManager: DumpManager
) : Dumpable {

    @VisibleForTesting
    internal companion object {
        val OPS_MIC_CAMERA = intArrayOf(AppOpsManager.OP_CAMERA,
                AppOpsManager.OP_PHONE_CALL_CAMERA, AppOpsManager.OP_RECORD_AUDIO,
                AppOpsManager.OP_PHONE_CALL_MICROPHONE)
        val OPS_LOCATION = intArrayOf(
                AppOpsManager.OP_COARSE_LOCATION,
                AppOpsManager.OP_FINE_LOCATION)
        val OPS = OPS_MIC_CAMERA + OPS_LOCATION
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_SWITCHED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        const val TAG = "PrivacyItemController"
        private const val ALL_INDICATORS =
                SystemUiDeviceConfigFlags.PROPERTY_PERMISSIONS_HUB_ENABLED
        private const val MIC_CAMERA = SystemUiDeviceConfigFlags.PROPERTY_MIC_CAMERA_ENABLED
        private const val LOCATION = SystemUiDeviceConfigFlags.PROPERTY_LOCATION_INDICATORS_ENABLED
        private const val DEFAULT_ALL_INDICATORS = false
        private const val DEFAULT_MIC_CAMERA = true
        private const val DEFAULT_LOCATION = false
        const val TIME_TO_HOLD_INDICATORS = 5000L
    }

    @VisibleForTesting
    internal var privacyList = emptyList<PrivacyItem>()
        @Synchronized get() = field.toList() // Returns a shallow copy of the list
        @Synchronized set

    private fun isAllIndicatorsEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                ALL_INDICATORS, DEFAULT_ALL_INDICATORS)
    }

    // TODO(b/168209929) Remove hardcode
    private fun isMicCameraEnabled(): Boolean {
        return true
    }

    private fun isLocationEnabled(): Boolean {
        return deviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                LOCATION, DEFAULT_LOCATION)
    }

    private var currentUserIds = emptyList<Int>()
    private var listening = false
    private val callbacks = mutableListOf<WeakReference<Callback>>()
    private val internalUiExecutor = MyExecutor(uiExecutor)
    private var holdingIndicators = false
    private var holdIndicatorsCancelled: Runnable? = null

    private val notifyChanges = Runnable {
        val list = privacyList
        callbacks.forEach { it.get()?.onPrivacyItemsChanged(list) }
    }

    private val updateListAndNotifyChanges = Runnable {
        updatePrivacyList()
        uiExecutor.execute(notifyChanges)
    }

    private val stopHoldingAndNotifyChanges = Runnable {
        updatePrivacyList(true)
        uiExecutor.execute(notifyChanges)
    }

    var allIndicatorsAvailable = isAllIndicatorsEnabled()
        private set
    var micCameraAvailable = isMicCameraEnabled()
        private set
    var locationAvailable = isLocationEnabled()

    private val devicePropertiesChangedListener =
            object : DeviceConfig.OnPropertiesChangedListener {
        override fun onPropertiesChanged(properties: DeviceConfig.Properties) {
            if (DeviceConfig.NAMESPACE_PRIVACY.equals(properties.getNamespace()) &&
                    (properties.keyset.contains(ALL_INDICATORS) ||
                            properties.keyset.contains(MIC_CAMERA) ||
                            properties.keyset.contains(LOCATION))) {

                // Running on the ui executor so can iterate on callbacks
                if (properties.keyset.contains(ALL_INDICATORS)) {
                    allIndicatorsAvailable = properties.getBoolean(ALL_INDICATORS,
                            DEFAULT_ALL_INDICATORS)
                    callbacks.forEach { it.get()?.onFlagAllChanged(allIndicatorsAvailable) }
                }
                // TODO(b/168209929) Uncomment
//                if (properties.keyset.contains(MIC_CAMERA)) {
//                    micCameraAvailable = properties.getBoolean(MIC_CAMERA, DEFAULT_MIC_CAMERA)
//                    callbacks.forEach { it.get()?.onFlagMicCameraChanged(micCameraAvailable) }
//                }
                if (properties.keyset.contains(LOCATION)) {
                    locationAvailable = properties.getBoolean(LOCATION, DEFAULT_LOCATION)
                    callbacks.forEach { it.get()?.onFlagLocationChanged(locationAvailable) }
                }
                internalUiExecutor.updateListeningState()
            }
        }
    }

    private val cb = object : AppOpsController.Callback {
        override fun onActiveStateChanged(
            code: Int,
            uid: Int,
            packageName: String,
            active: Boolean
        ) {
            // Check if we care about this code right now
            if (!allIndicatorsAvailable &&
                    (code in OPS_LOCATION && !locationAvailable)) {
                return
            }
            val userId = UserHandle.getUserId(uid)
            if (userId in currentUserIds) {
                logger.logUpdatedItemFromAppOps(code, uid, packageName, active)
                update(false)
            }
        }
    }

    @VisibleForTesting
    internal var userTrackerCallback = object : UserTracker.Callback {
        override fun onUserChanged(newUser: Int, userContext: Context) {
            update(true)
        }

        override fun onProfilesChanged(profiles: List<UserInfo>) {
            update(true)
        }
    }

    init {
        deviceConfigProxy.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_PRIVACY,
                uiExecutor,
                devicePropertiesChangedListener)
        dumpManager.registerDumpable(TAG, this)
    }

    private fun unregisterListener() {
        userTracker.removeCallback(userTrackerCallback)
    }

    private fun registerReceiver() {
        userTracker.addCallback(userTrackerCallback, bgExecutor)
    }

    private fun setHoldTimer() {
        holdIndicatorsCancelled?.run()
        holdingIndicators = true
        holdIndicatorsCancelled = bgExecutor.executeDelayed({
            stopHoldingAndNotifyChanges.run()
        }, TIME_TO_HOLD_INDICATORS)
    }

    private fun update(updateUsers: Boolean) {
        bgExecutor.execute {
            if (updateUsers) {
                currentUserIds = userTracker.userProfiles.map { it.id }
                logger.logCurrentProfilesChanged(currentUserIds)
            }
            updateListAndNotifyChanges.run()
        }
    }

    /**
     * Updates listening status based on whether there are callbacks and the indicators are enabled.
     *
     * Always listen to all OPS so we don't have to figure out what we should be listening to. We
     * still have to filter anyway. Updates are filtered in the callback.
     *
     * This is only called from private (add/remove)Callback and from the config listener, all in
     * main thread.
     */
    private fun setListeningState() {
        val listen = !callbacks.isEmpty() and
                (allIndicatorsAvailable || micCameraAvailable || locationAvailable)
        if (listening == listen) return
        listening = listen
        if (listening) {
            appOpsController.addCallback(OPS, cb)
            registerReceiver()
            update(true)
        } else {
            appOpsController.removeCallback(OPS, cb)
            unregisterListener()
            // Make sure that we remove all indicators and notify listeners if we are not
            // listening anymore due to indicators being disabled
            update(false)
        }
    }

    private fun addCallback(callback: WeakReference<Callback>) {
        callbacks.add(callback)
        if (callbacks.isNotEmpty() && !listening) {
            internalUiExecutor.updateListeningState()
        }
        // Notify this callback if we didn't set to listening
        else if (listening) {
            internalUiExecutor.execute(NotifyChangesToCallback(callback.get(), privacyList))
        }
    }

    private fun removeCallback(callback: WeakReference<Callback>) {
        // Removes also if the callback is null
        callbacks.removeIf { it.get()?.equals(callback.get()) ?: true }
        if (callbacks.isEmpty()) {
            internalUiExecutor.updateListeningState()
        }
    }

    fun addCallback(callback: Callback) {
        addCallback(WeakReference(callback))
    }

    fun removeCallback(callback: Callback) {
        removeCallback(WeakReference(callback))
    }

    private fun updatePrivacyList(stopHolding: Boolean = false) {
        if (!listening) {
            privacyList = emptyList()
            if (holdingIndicators) {
                holdIndicatorsCancelled?.run()
                logger.cancelIndicatorsHold()
                holdingIndicators = false
            }
            return
        }
        val list = appOpsController.getActiveAppOpsForUser(UserHandle.USER_ALL).filter {
            UserHandle.getUserId(it.uid) in currentUserIds ||
                    it.code == AppOpsManager.OP_PHONE_CALL_MICROPHONE ||
                    it.code == AppOpsManager.OP_PHONE_CALL_CAMERA
        }.mapNotNull { toPrivacyItem(it) }.distinct()
        processNewList(list, stopHolding)
    }

    /**
     * The controller will only go from indicators to no indicators (and notify its listeners), if
     * [TIME_TO_HOLD_INDICATORS] has passed since it received an empty list from [AppOpsController].
     *
     * If holding the last list (in the [TIME_TO_HOLD_INDICATORS] period) and a new non-empty list
     * is retrieved from [AppOpsController], it will stop holding and notify about the new list.
     */
    private fun processNewList(list: List<PrivacyItem>, stopHolding: Boolean) {
        if (list.isNotEmpty()) {
            // The new elements is not empty, so regardless of whether we are holding or not, we
            // clear the holding flag and cancel the delayed runnable.
            if (holdingIndicators) {
                holdIndicatorsCancelled?.run()
                logger.cancelIndicatorsHold()
                holdingIndicators = false
            }
            logger.logUpdatedPrivacyItemsList(
                    list.joinToString(separator = ", ", transform = PrivacyItem::toLog))
            privacyList = list
        } else if (holdingIndicators && stopHolding) {
            // We are holding indicators, received an empty list and were told to stop holding.
            logger.finishIndicatorsHold()
            logger.logUpdatedPrivacyItemsList("")
            holdingIndicators = false
            privacyList = list
        } else if (holdingIndicators && !stopHolding) {
            // Empty list while we are holding. Ignore
        } else if (!holdingIndicators && privacyList.isNotEmpty()) {
            // We are not holding, we were showing some indicators but now we should show nothing.
            // Start holding.
            logger.startIndicatorsHold(TIME_TO_HOLD_INDICATORS)
            setHoldTimer()
        }
        // Else. We are not holding, we were not showing anything and the new list is empty. Ignore.
    }

    private fun toPrivacyItem(appOpItem: AppOpItem): PrivacyItem? {
        val type: PrivacyType = when (appOpItem.code) {
            AppOpsManager.OP_PHONE_CALL_CAMERA,
            AppOpsManager.OP_CAMERA -> PrivacyType.TYPE_CAMERA
            AppOpsManager.OP_COARSE_LOCATION,
            AppOpsManager.OP_FINE_LOCATION -> PrivacyType.TYPE_LOCATION
            AppOpsManager.OP_PHONE_CALL_MICROPHONE,
            AppOpsManager.OP_RECORD_AUDIO -> PrivacyType.TYPE_MICROPHONE
            else -> return null
        }
        if (type == PrivacyType.TYPE_LOCATION && (!allIndicatorsAvailable && !locationAvailable)) {
            return null
        }
        val app = PrivacyApplication(appOpItem.packageName, appOpItem.uid)
        return PrivacyItem(type, app)
    }

    interface Callback {
        fun onPrivacyItemsChanged(privacyItems: List<PrivacyItem>)

        @JvmDefault
        fun onFlagAllChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagMicCameraChanged(flag: Boolean) {}

        @JvmDefault
        fun onFlagLocationChanged(flag: Boolean) {}
    }

    private class NotifyChangesToCallback(
        private val callback: Callback?,
        private val list: List<PrivacyItem>
    ) : Runnable {
        override fun run() {
            callback?.onPrivacyItemsChanged(list)
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>) {
        pw.println("PrivacyItemController state:")
        pw.println("  Listening: $listening")
        pw.println("  Current user ids: $currentUserIds")
        pw.println("  Privacy Items:")
        privacyList.forEach {
            pw.print("    ")
            pw.println(it.toString())
        }
        pw.println("  Callbacks:")
        callbacks.forEach {
            it.get()?.let {
                pw.print("    ")
                pw.println(it.toString())
            }
        }
    }

    private inner class MyExecutor(
        private val delegate: DelayableExecutor
    ) : Executor {

        private var listeningCanceller: Runnable? = null

        override fun execute(command: Runnable) {
            delegate.execute(command)
        }

        fun updateListeningState() {
            listeningCanceller?.run()
            listeningCanceller = delegate.executeDelayed({ setListeningState() }, 0L)
        }
    }
}