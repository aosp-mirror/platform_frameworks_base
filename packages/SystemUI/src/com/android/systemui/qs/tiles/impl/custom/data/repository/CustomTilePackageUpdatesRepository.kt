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

package com.android.systemui.qs.tiles.impl.custom.data.repository

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.UserHandle
import androidx.annotation.GuardedBy
import com.android.systemui.common.coroutine.ConflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

interface CustomTilePackageUpdatesRepository {

    fun getPackageChangesForUser(user: UserHandle): Flow<Unit>
}

@QSTileScope
class CustomTilePackageUpdatesRepositoryImpl
@Inject
constructor(
    private val tileSpec: TileSpec.CustomTileSpec,
    @Application private val context: Context,
    @QSTileScope private val tileScope: CoroutineScope,
    @Background private val backgroundCoroutineContext: CoroutineContext,
) : CustomTilePackageUpdatesRepository {

    @GuardedBy("perUserCache")
    private val perUserCache: MutableMap<UserHandle, Flow<Unit>> = mutableMapOf()

    override fun getPackageChangesForUser(user: UserHandle): Flow<Unit> =
        synchronized(perUserCache) {
            perUserCache.getOrPut(user) {
                createPackageChangesFlowForUser(user)
                    .onCompletion {
                        // clear cache when nobody listens
                        synchronized(perUserCache) { perUserCache.remove(user) }
                    }
                    .flowOn(backgroundCoroutineContext)
                    .shareIn(tileScope, SharingStarted.WhileSubscribed())
            }
        }

    @SuppressLint(
        "MissingPermission", // android.permission.INTERACT_ACROSS_USERS_FULL
        "UnspecifiedRegisterReceiverFlag",
        "RegisterReceiverViaContext",
    )
    private fun createPackageChangesFlowForUser(user: UserHandle): Flow<Unit> =
        ConflatedCallbackFlow.conflatedCallbackFlow {
                val receiver =
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            launch { send(intent) }
                        }
                    }
                context.registerReceiverAsUser(
                    receiver,
                    user,
                    INTENT_FILTER,
                    /* broadcastPermission = */ null,
                    /* scheduler = */ null,
                )

                awaitClose { context.unregisterReceiver(receiver) }
            }
            .filter { intent ->
                intent ?: return@filter false
                if (intent.action?.let(INTENT_FILTER::matchAction) != true) {
                    return@filter false
                }
                val changedComponentNames =
                    intent.getStringArrayExtra(Intent.EXTRA_CHANGED_COMPONENT_NAME_LIST)
                changedComponentNames?.contains(tileSpec.componentName.packageName) == true
            }
            .map {}

    private companion object {

        val INTENT_FILTER =
            IntentFilter().apply {
                addDataScheme(IntentFilter.SCHEME_PACKAGE)

                addAction(Intent.ACTION_PACKAGE_CHANGED)
            }
    }
}
