/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.controls.controller

import android.content.Context
import android.os.Handler
import android.os.UserHandle
import com.android.internal.content.PackageMonitor
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import java.util.concurrent.atomic.AtomicBoolean

/** [PackageMonitor] that tracks when [packageName] has finished updating for user [user]. */
class PackageUpdateMonitor
@AssistedInject
constructor(
    @Assisted private val user: UserHandle,
    @Assisted private val packageName: String,
    @Assisted private val callback: Runnable,
    @Background private val bgHandler: Handler,
    @Application private val context: Context,
) : PackageMonitor() {

    private val monitoring = AtomicBoolean(false)

    @AssistedFactory
    fun interface Factory {
        /**
         * Create a [PackageUpdateMonitor] for a given [user] and [packageName]. It will run
         * [callback] every time the package finishes updating.
         */
        fun create(user: UserHandle, packageName: String, callback: Runnable): PackageUpdateMonitor
    }

    /** Start monitoring for package updates. No-op if already monitoring. */
    fun startMonitoring() {
        if (monitoring.compareAndSet(/* expected */ false, /* new */ true)) {
            register(context, user, bgHandler)
        }
    }

    /** Stop monitoring for package updates. No-op if not monitoring. */
    fun stopMonitoring() {
        if (monitoring.compareAndSet(/* expected */ true, /* new */ false)) {
            unregister()
        }
    }

    /**
     * If the package and the user match the ones for this [PackageUpdateMonitor], it will run
     * [callback].
     */
    override fun onPackageUpdateFinished(packageName: String?, uid: Int) {
        super.onPackageUpdateFinished(packageName, uid)
        if (packageName == this.packageName && UserHandle.getUserHandleForUid(uid) == user) {
            callback.run()
        }
    }
}
