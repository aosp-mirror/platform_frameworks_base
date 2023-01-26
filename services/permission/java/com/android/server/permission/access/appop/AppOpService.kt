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

package com.android.server.permission.access.appop

import android.Manifest
import android.annotation.UserIdInt
import android.app.AppGlobals
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.RemoteException
import android.os.UserHandle
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import com.android.internal.util.ArrayUtils
import com.android.internal.util.function.pooled.PooledLambda
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.appop.OnOpModeChangedListener
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.hasBits
import libcore.util.EmptyArray
import java.io.PrintWriter

class AppOpService(
    private val service: AccessCheckingService
) : AppOpsCheckingServiceInterface {
    private val packagePolicy = service.getSchemePolicy(PackageUri.SCHEME, AppOpUri.SCHEME)
        as PackageAppOpPolicy
    private val uidPolicy = service.getSchemePolicy(UidUri.SCHEME, AppOpUri.SCHEME)
        as UidAppOpPolicy

    private val context = service.context
    private lateinit var handler: Handler
    private lateinit var lock: Any
    private lateinit var switchedOps: IntMap<IntArray>

    fun initialize() {
        // TODO(b/252883039): Wrong handler. Inject main thread handler here.
        handler = Handler(context.mainLooper)
        // TODO(b/252883039): Wrong lock object. Inject AppOpsService here.
        lock = Any()

        switchedOps = IntMap()
        for (switchedCode in 0 until AppOpsManager._NUM_OP) {
            val switchCode = AppOpsManager.opToSwitch(switchedCode)
            switchedOps.put(switchCode,
                ArrayUtils.appendInt(switchedOps.get(switchCode), switchedCode))
        }
    }

    override fun getNonDefaultUidModes(uid: Int): SparseIntArray {
        return opNameMapToOpIntMap(getUidModes(uid))
    }

    override fun getNonDefaultPackageModes(packageName: String, userId: Int): SparseIntArray {
        return opNameMapToOpIntMap(getPackageModes(packageName, userId))
    }

    override fun getUidMode(uid: Int, op: Int): Int {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(uidPolicy) { getAppOpMode(appId, userId, opName) }
        }
    }

    private fun getUidModes(uid: Int): IndexedMap<String, Int>? {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        return service.getState {
            with(uidPolicy) { getAppOpModes(appId, userId) }
        }
    }

    override fun setUidMode(uid: Int, op: Int, mode: Int): Boolean {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        var wasChanged = false
        service.mutateState {
            wasChanged = with(uidPolicy) { setAppOpMode(appId, userId, opName, mode) }
        }
        return wasChanged
    }

    override fun getPackageMode(packageName: String, op: Int, userId: Int): Int {
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(packagePolicy) { getAppOpMode(packageName, userId, opName) }
        }
    }

    private fun getPackageModes(
        packageName: String,
        userId: Int
    ): IndexedMap<String, Int>? =
        service.getState { with(packagePolicy) { getAppOpModes(packageName, userId) } }

    override fun setPackageMode(packageName: String, op: Int, mode: Int, userId: Int) {
        val opName = AppOpsManager.opToPublicName(op)
        service.mutateState {
            with(packagePolicy) { setAppOpMode(packageName, userId, opName, mode) }
        }
    }

    override fun removeUid(uid: Int) {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.mutateState {
            with(uidPolicy) { removeAppOpModes(appId, userId) }
        }
    }

    override fun removePackage(packageName: String, userId: Int): Boolean {
        var wasChanged = false
        service.mutateState {
            wasChanged = with (packagePolicy) { removeAppOpModes(packageName, userId) }
        }
        return wasChanged
    }

    private fun opNameMapToOpIntMap(modes: IndexedMap<String, Int>?): SparseIntArray =
        if (modes == null) {
            SparseIntArray()
        } else {
            val opIntMap = SparseIntArray(modes.size)
            modes.forEachIndexed { _, opName, opMode ->
                opIntMap.put(AppOpsManager.strOpToOp(opName), opMode)
            }
            opIntMap
        }

    override fun areUidModesDefault(uid: Int): Boolean {
        val modes = getUidModes(uid)
        return modes == null || modes.isEmpty()
    }

    override fun arePackageModesDefault(packageName: String, userId: Int): Boolean {
        val modes = service.getState { getPackageModes(packageName, userId) }
        return modes == null || modes.isEmpty()
    }

    override fun clearAllModes() {
        // We don't need to implement this because it's only called in AppOpsService#readState
        // and we have our own persistence.
    }

    // code -> listeners
    private val opModeWatchers = IntMap<IndexedSet<OnOpModeChangedListener>>()

    // packageName -> listeners
    private val packageModeWatchers = IndexedMap<String, IndexedSet<OnOpModeChangedListener>>()

    override fun startWatchingOpModeChanged(changedListener: OnOpModeChangedListener, op: Int) {
        synchronized(lock) {
            opModeWatchers.getOrPut(op) { IndexedSet() } += changedListener
        }
    }

    override fun startWatchingPackageModeChanged(
        changedListener: OnOpModeChangedListener,
        packageName: String
    ) {
        synchronized(lock) {
            packageModeWatchers.getOrPut(packageName) { IndexedSet() } += changedListener
        }
    }

    override fun removeListener(changedListener: OnOpModeChangedListener) {
        synchronized(lock) {
            opModeWatchers.removeAllIndexed { _, _, listeners ->
                listeners -= changedListener
                listeners.isEmpty()
            }
            packageModeWatchers.removeAllIndexed { _, _, listeners ->
                listeners -= changedListener
                listeners.isEmpty()
            }
        }
    }

    override fun getOpModeChangedListeners(op: Int): IndexedSet<OnOpModeChangedListener> {
        synchronized(lock) {
            val listeners = opModeWatchers[op]
            return if (listeners == null) {
                IndexedSet()
            } else {
                IndexedSet(listeners)
            }
        }
    }

    override fun getPackageModeChangedListeners(
        packageName: String
    ): IndexedSet<OnOpModeChangedListener> {
        synchronized(lock) {
            val listeners = packageModeWatchers[packageName]
            return if (listeners == null) {
                IndexedSet()
            } else {
                IndexedSet(listeners)
            }
        }
    }

    override fun notifyWatchersOfChange(op: Int, uid: Int) {
        val listeners = getOpModeChangedListeners(op)
        listeners.forEachIndexed { _, listener ->
            notifyOpChanged(listener, op, uid, null)
        }
    }

    override fun notifyOpChanged(
        changedListener: OnOpModeChangedListener,
        op: Int,
        uid: Int,
        packageName: String?
    ) {
        if (uid != UID_ANY &&
            changedListener.watchingUid >= 0 &&
            changedListener.watchingUid != uid
        ) {
            return
        }

        // See CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE
        val switchedCodes = when (changedListener.watchedOpCode) {
            ALL_OPS -> switchedOps.get(op)
            AppOpsManager.OP_NONE -> intArrayOf(op)
            else -> intArrayOf(changedListener.watchedOpCode)
        }

        for (switchedCode in switchedCodes) {
            // There are features watching for mode changes such as window manager
            // and location manager which are in our process. The callbacks in these
            // features may require permissions our remote caller does not have.
            val identity = Binder.clearCallingIdentity()
            try {
                if (!shouldIgnoreCallback(switchedCode, changedListener)) {
                    changedListener.onOpModeChanged(switchedCode, uid, packageName)
                }
            } catch (e: RemoteException) {
                /* ignore */
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    private fun shouldIgnoreCallback(op: Int, listener: OnOpModeChangedListener): Boolean {
        // If it's a restricted read op, ignore it if watcher doesn't have manage ops permission,
        // as watcher should not use this to signal if the value is changed.
        return AppOpsManager.opRestrictsRead(op) && context.checkPermission(
            Manifest.permission.MANAGE_APPOPS,
            listener.callingPid,
            listener.callingUid
        ) != PackageManager.PERMISSION_GRANTED
    }

    /**
     * Construct a map from each listener (listening to the given op, uid) to all of its associated
     * packageNames (by reverse-indexing opModeWatchers and packageModeWatchers), then invoke
     * notifyOpChanged for each listener.
     */
    override fun notifyOpChangedForAllPkgsInUid(
        op: Int,
        uid: Int,
        onlyForeground: Boolean,
        callbackToIgnore: OnOpModeChangedListener?
    ) {
        val uidPackageNames = getPackagesForUid(uid)
        val callbackSpecs = IndexedMap<OnOpModeChangedListener, IndexedSet<String>>()

        fun associateListenerWithPackageNames(
            listener: OnOpModeChangedListener,
            packageNames: Array<String>
        ) {
            val listenerIsForeground =
                listener.flags.hasBits(AppOpsManager.WATCH_FOREGROUND_CHANGES)
            if (onlyForeground && !listenerIsForeground) {
                return
            }
            val changedPackages = callbackSpecs.getOrPut(listener) { IndexedSet() }
            changedPackages.addAll(packageNames)
        }

        synchronized(lock) {
            // Collect all listeners from opModeWatchers and pckageModeWatchers
            val listeners = opModeWatchers[op]
            listeners?.forEachIndexed { _, listener ->
                associateListenerWithPackageNames(listener, uidPackageNames)
            }
            uidPackageNames.forEachIndexed { _, uidPackageName ->
                val packageListeners = packageModeWatchers[uidPackageName]
                packageListeners?.forEachIndexed { _, listener ->
                    associateListenerWithPackageNames(listener, arrayOf(uidPackageName))
                }
            }
            // Remove ignored listeners
            if (callbackToIgnore != null) {
                callbackSpecs.remove(callbackToIgnore)
            }
        }

        // For each (listener, packageName) pair, invoke notifyOpChanged
        callbackSpecs.forEachIndexed { _, listener, reportedPackageNames ->
            reportedPackageNames.forEachIndexed { _, reportedPackageName ->
                handler.sendMessage(
                    PooledLambda.obtainMessage(
                        AppOpService::notifyOpChanged, this, listener,
                        op, uid, reportedPackageName
                    )
                )
            }
        }
    }

    private fun getPackagesForUid(uid: Int): Array<String> {
        // Very early during boot the package manager is not yet or not yet fully started. At this
        // time there are no packages yet.
        return try {
            AppGlobals.getPackageManager()?.getPackagesForUid(uid) ?: EmptyArray.STRING
        } catch (e: RemoteException) {
            EmptyArray.STRING
        }
    }

    override fun evalForegroundUidOps(
        uid: Int,
        foregroundOps: SparseBooleanArray?
    ): SparseBooleanArray? {
        synchronized(lock) {
            val uidModes = getUidModes(uid)
            return evalForegroundOps(uidModes, foregroundOps)
        }
    }

    override fun evalForegroundPackageOps(
        packageName: String,
        foregroundOps: SparseBooleanArray?,
        @UserIdInt userId: Int
    ): SparseBooleanArray? {
        synchronized(lock) {
            val ops = service.getState { getPackageModes(packageName, userId) }
            return evalForegroundOps(ops, foregroundOps)
        }
    }

    private fun evalForegroundOps(
        ops: IndexedMap<String, Int>?,
        foregroundOps: SparseBooleanArray?
    ): SparseBooleanArray? {
        var foregroundOps = foregroundOps
        ops?.forEachIndexed { _, opName, opMode ->
            if (opMode == AppOpsManager.MODE_FOREGROUND) {
                if (foregroundOps == null) {
                    foregroundOps = SparseBooleanArray()
                }
                evalForegroundWatchers(opName, foregroundOps!!)
            }
        }
        return foregroundOps
    }

    private fun evalForegroundWatchers(opName: String, foregroundOps: SparseBooleanArray) {
        val opCode = AppOpsManager.strOpToOp(opName)
        val listeners = opModeWatchers[opCode]
        val hasForegroundListeners = foregroundOps[opCode] || listeners?.anyIndexed { _, listener ->
            listener.flags.hasBits(AppOpsManager.WATCH_FOREGROUND_CHANGES)
        } ?: false
        foregroundOps.put(opCode, hasForegroundListeners)
    }

    override fun dumpListeners(
        dumpOp: Int,
        dumpUid: Int,
        dumpPackage: String?,
        printWriter: PrintWriter
    ): Boolean {
        var needSep = false
        if (opModeWatchers.size() > 0) {
            var printedHeader = false
            opModeWatchers.forEachIndexed { _, op, modeChangedListenerSet ->
                if (dumpOp >= 0 && dumpOp != op) {
                    return@forEachIndexed // continue
                }
                val opName = AppOpsManager.opToName(op)
                var printedOpHeader = false
                modeChangedListenerSet.forEachIndexed listenerLoop@ { listenerIndex, listener ->
                    with(printWriter) {
                        if (dumpPackage != null &&
                            dumpUid != UserHandle.getAppId(listener.watchingUid)) {
                            return@listenerLoop // continue
                        }
                        needSep = true
                        if (!printedHeader) {
                            println("  Op mode watchers:")
                            printedHeader = true
                        }
                        if (!printedOpHeader) {
                            print("    Op ")
                            print(opName)
                            println(":")
                            printedOpHeader = true
                        }
                        print("      #")
                        print(listenerIndex)
                        print(opName)
                        print(": ")
                        println(listener.toString())
                    }
                }
            }
        }

        if (packageModeWatchers.size > 0 && dumpOp < 0) {
            var printedHeader = false
            packageModeWatchers.forEachIndexed { _, packageName, listeners ->
                with(printWriter) {
                    if (dumpPackage != null && dumpPackage != packageName) {
                        return@forEachIndexed // continue
                    }
                    needSep = true
                    if (!printedHeader) {
                        println("  Package mode watchers:")
                        printedHeader = true
                    }
                    print("    Pkg ")
                    print(packageName)
                    println(":")
                    listeners.forEachIndexed { listenerIndex, listener ->
                        print("      #")
                        print(listenerIndex)
                        print(": ")
                        println(listener.toString())
                    }
                }
            }
        }
        return needSep
    }

    companion object {
        private val LOG_TAG = AppOpService::class.java.simpleName

        // Constant meaning that any UID should be matched when dispatching callbacks
        private const val UID_ANY = -2

        // If watchedOpCode==ALL_OPS, notify for ops affected by the switch-op
        private const val ALL_OPS = -2
    }
}
