/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.common.data.repository

import android.content.Context
import android.os.Handler
import android.os.UserHandle
import com.android.internal.content.PackageMonitor
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.time.SystemClock
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * A wrapper around [PackageMonitor] which exposes package updates as a flow.
 *
 * External clients should use [PackageChangeRepository] instead to ensure only a single callback is
 * registered for all of SystemUI.
 */
class PackageUpdateMonitor
@AssistedInject
constructor(
    @Assisted private val user: UserHandle,
    @Background private val bgDispatcher: CoroutineDispatcher,
    @Background private val bgHandler: Handler,
    @Application private val context: Context,
    @Application private val scope: CoroutineScope,
    private val logger: PackageUpdateLogger,
    private val systemClock: SystemClock,
) : PackageMonitor() {

    @AssistedFactory
    fun interface Factory {
        fun create(user: UserHandle): PackageUpdateMonitor
    }

    var isActive = false
        private set

    private val _packageChanged =
        MutableSharedFlow<PackageChangeModel>(replay = 0, extraBufferCapacity = BUFFER_CAPACITY)
            .apply {
                // Automatically register/unregister as needed, depending on whether
                // there are subscribers to this flow.
                subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .onEach { active ->
                        if (active) {
                            register(context, user, bgHandler)
                        } else if (isActive) {
                            // Avoid calling unregister if we were not previously active, as this
                            // will cause an IllegalStateException.
                            unregister()
                        }
                        isActive = active
                    }
                    .flowOn(bgDispatcher)
                    .launchIn(scope)
            }

    val packageChanged: Flow<PackageChangeModel>
        get() = _packageChanged.onEach(logger::logChange)

    override fun onPackageAdded(packageName: String, uid: Int) {
        super.onPackageAdded(packageName, uid)
        _packageChanged.tryEmit(
            PackageChangeModel.Installed(
                packageName = packageName,
                packageUid = uid,
                timeMillis = systemClock.currentTimeMillis()
            )
        )
    }

    override fun onPackageRemoved(packageName: String, uid: Int) {
        super.onPackageRemoved(packageName, uid)
        _packageChanged.tryEmit(
            PackageChangeModel.Uninstalled(
                packageName = packageName,
                packageUid = uid,
                timeMillis = systemClock.currentTimeMillis()
            )
        )
    }

    override fun onPackageChanged(
        packageName: String,
        uid: Int,
        components: Array<out String>
    ): Boolean {
        super.onPackageChanged(packageName, uid, components)
        _packageChanged.tryEmit(
            PackageChangeModel.Changed(
                packageName = packageName,
                packageUid = uid,
                timeMillis = systemClock.currentTimeMillis()
            )
        )
        return false
    }

    override fun onPackageUpdateStarted(packageName: String, uid: Int) {
        super.onPackageUpdateStarted(packageName, uid)
        _packageChanged.tryEmit(
            PackageChangeModel.UpdateStarted(
                packageName = packageName,
                packageUid = uid,
                timeMillis = systemClock.currentTimeMillis()
            )
        )
    }

    override fun onPackageUpdateFinished(packageName: String, uid: Int) {
        super.onPackageUpdateFinished(packageName, uid)
        _packageChanged.tryEmit(
            PackageChangeModel.UpdateFinished(
                packageName = packageName,
                packageUid = uid,
                timeMillis = systemClock.currentTimeMillis()
            )
        )
    }

    private companion object {
        // This capacity is the number of package changes that we will keep buffered in the shared
        // flow. It is unlikely that at any given time there would be this many changes being
        // processed by consumers, but this is done just in case that many packages are changed at
        // the same time and there is backflow due to consumers processing the changes more slowly
        // than they are being emitted.
        const val BUFFER_CAPACITY = 100
    }
}
