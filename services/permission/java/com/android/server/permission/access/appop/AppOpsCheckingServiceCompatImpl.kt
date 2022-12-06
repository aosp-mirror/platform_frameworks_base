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

import android.util.ArraySet
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.appop.OnOpModeChangedListener
import com.android.server.permission.access.AccessCheckingService
import java.io.PrintWriter

class AppOpsCheckingServiceCompatImpl(
    private val accessCheckingService: AccessCheckingService
) : AppOpsCheckingServiceInterface {
    override fun getNonDefaultUidModes(uid: Int): SparseIntArray {
        TODO("Not yet implemented")
    }

    override fun getUidMode(uid: Int, op: Int): Int {
        TODO("Not yet implemented")
    }

    override fun setUidMode(uid: Int, op: Int, mode: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun getPackageMode(packageName: String, op: Int, userId: Int): Int {
        TODO("Not yet implemented")
    }

    override fun setPackageMode(packageName: String, op: Int, mode: Int, userId: Int) {
        TODO("Not yet implemented")
    }

    override fun removePackage(packageName: String, userId: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun removeUid(uid: Int) {
        TODO("Not yet implemented")
    }

    override fun areUidModesDefault(uid: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun arePackageModesDefault(packageName: String, userId: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun clearAllModes() {
        TODO("Not yet implemented")
    }

    override fun startWatchingOpModeChanged(changedListener: OnOpModeChangedListener, op: Int) {
        TODO("Not yet implemented")
    }

    override fun startWatchingPackageModeChanged(
        changedListener: OnOpModeChangedListener,
        packageName: String
    ) {
        TODO("Not yet implemented")
    }

    override fun removeListener(changedListener: OnOpModeChangedListener) {
        TODO("Not yet implemented")
    }

    override fun getOpModeChangedListeners(op: Int): ArraySet<OnOpModeChangedListener> {
        TODO("Not yet implemented")
    }

    override fun getPackageModeChangedListeners(
        packageName: String
    ): ArraySet<OnOpModeChangedListener> {
        TODO("Not yet implemented")
    }

    override fun notifyWatchersOfChange(op: Int, uid: Int) {
        TODO("Not yet implemented")
    }

    override fun notifyOpChanged(
        changedListener: OnOpModeChangedListener,
        op: Int,
        uid: Int,
        packageName: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun notifyOpChangedForAllPkgsInUid(
        op: Int,
        uid: Int,
        onlyForeground: Boolean,
        callbackToIgnore: OnOpModeChangedListener?
    ) {
        TODO("Not yet implemented")
    }

    override fun evalForegroundUidOps(
        uid: Int,
        foregroundOps: SparseBooleanArray?
    ): SparseBooleanArray {
        TODO("Not yet implemented")
    }

    override fun evalForegroundPackageOps(
        packageName: String,
        foregroundOps: SparseBooleanArray?,
        userId: Int
    ): SparseBooleanArray {
        TODO("Not yet implemented")
    }

    override fun dumpListeners(
        dumpOp: Int,
        dumpUid: Int,
        dumpPackage: String?,
        printWriter: PrintWriter
    ): Boolean {
        TODO("Not yet implemented")
    }

    companion object {
        private val LOG_TAG = AppOpsCheckingServiceCompatImpl::class.java.simpleName
    }
}
