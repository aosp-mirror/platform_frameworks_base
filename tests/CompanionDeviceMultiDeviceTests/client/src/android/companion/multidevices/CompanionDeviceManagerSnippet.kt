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

package android.companion.multidevices

import android.app.Instrumentation
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionException
import android.companion.cts.common.CompanionActivity
import android.companion.multidevices.CallbackUtils.AssociationCallback
import android.companion.multidevices.CallbackUtils.SystemDataTransferCallback
import android.companion.multidevices.bluetooth.BluetoothConnector
import android.companion.multidevices.bluetooth.BluetoothController
import android.companion.cts.uicommon.CompanionDeviceManagerUi
import android.content.Context
import android.os.Handler
import android.os.HandlerExecutor
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.android.mobly.snippet.Snippet
import com.google.android.mobly.snippet.event.EventCache
import com.google.android.mobly.snippet.rpc.Rpc
import java.util.concurrent.Executor
import java.util.regex.Pattern

/**
 * Snippet class that exposes Android APIs in CompanionDeviceManager.
 */
class CompanionDeviceManagerSnippet : Snippet {
    private val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()!!
    private val context: Context = instrumentation.targetContext

    private val btAdapter: BluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val companionDeviceManager: CompanionDeviceManager by lazy {
        context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as CompanionDeviceManager
    }
    private val btConnector: BluetoothConnector by lazy {
        BluetoothConnector(btAdapter, companionDeviceManager)
    }

    private val uiDevice by lazy { UiDevice.getInstance(instrumentation) }
    private val confirmationUi by lazy { CompanionDeviceManagerUi(uiDevice) }
    private val btController by lazy { BluetoothController(context, btAdapter, uiDevice) }

    private val eventCache = EventCache.getInstance()
    private val handlerThread = HandlerThread("Snippet-Aware")
    private val handler: Handler
    private val executor: Executor

    init {
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        executor = HandlerExecutor(handler)
    }

    /**
     * Make device discoverable to other devices via BLE and return device name.
     */
    @Rpc(description = "Start advertising device to be discoverable.")
    fun becomeDiscoverable(): String {
        btController.becomeDiscoverable()
        return btAdapter.name
    }

    /**
     * Associate with a nearby device with given name and return newly-created association ID.
     */
    @Rpc(description = "Start device association flow.")
    @Throws(Exception::class)
    fun associate(deviceName: String): Int {
        val filter = BluetoothDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(deviceName))
            .build()
        val request = AssociationRequest.Builder()
            .setSingleDevice(true)
            .addDeviceFilter(filter)
            .build()
        val callback = AssociationCallback()
        companionDeviceManager.associate(request, callback, handler)
        val pendingConfirmation = callback.waitForPendingIntent()
            ?: throw CompanionException("Association is pending but intent sender is null.")
        CompanionActivity.launchAndWait(context)
        CompanionActivity.startIntentSender(pendingConfirmation)
        confirmationUi.waitUntilVisible()
        confirmationUi.waitUntilPositiveButtonIsEnabledAndClick()
        confirmationUi.waitUntilGone()

        val (_, result) = CompanionActivity.waitForActivityResult()
        if (result == null) {
            throw CompanionException("Association result can't be null.")
        }

        val association = checkNotNull(result.getParcelableExtra(
            CompanionDeviceManager.EXTRA_ASSOCIATION,
            AssociationInfo::class.java
        ))
        val remoteDevice = association.associatedDevice?.getBluetoothDevice()!!

        // Register associated device
        btConnector.registerDevice(association.id, remoteDevice)

        return association.id
    }

    /**
     * Disassociate an association with given ID.
     */
    @Rpc(description = "Disassociate device.")
    @Throws(Exception::class)
    fun disassociate(associationId: Int) {
        companionDeviceManager.disassociate(associationId)
    }

    /**
     * Consent to system data transfer and carry it out using Bluetooth socket.
     */
    @Rpc(description = "Start permissions sync.")
    fun startPermissionsSync(associationId: Int) {
        val pendingIntent = checkNotNull(companionDeviceManager
            .buildPermissionTransferUserConsentIntent(associationId))
        CompanionActivity.launchAndWait(context)
        CompanionActivity.startIntentSender(pendingIntent)
        confirmationUi.waitUntilSystemDataTransferConfirmationVisible()
        confirmationUi.clickPositiveButton()
        confirmationUi.waitUntilGone()

        CompanionActivity.waitForActivityResult()

        val callback = SystemDataTransferCallback()
        companionDeviceManager.startSystemDataTransfer(associationId, executor, callback)
        callback.waitForCompletion()
    }

    @Rpc(description = "Attach transport to the BT client socket.")
    fun attachClientSocket(id: Int) {
        btConnector.attachClientSocket(id)
    }

    @Rpc(description = "Attach transport to the BT server socket.")
    fun attachServerSocket(id: Int) {
        btConnector.attachServerSocket(id)
    }

    @Rpc(description = "Close all open sockets.")
    fun closeAllSockets() {
        // Close all open sockets
        btConnector.closeAllSockets()
    }

    @Rpc(description = "Disassociate all associations.")
    fun disassociateAll() {
        companionDeviceManager.myAssociations.forEach {
            Log.d(TAG, "Disassociating id=${it.id}.")
            companionDeviceManager.disassociate(it.id)
        }
    }

    companion object {
        private const val TAG = "CDM_CompanionDeviceManagerSnippet"
    }
}
