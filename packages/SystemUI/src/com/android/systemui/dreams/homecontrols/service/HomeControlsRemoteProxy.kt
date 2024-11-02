/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.dreams.homecontrols.service

import android.content.ComponentName
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dreams.homecontrols.shared.IHomeControlsRemoteProxy
import com.android.systemui.dreams.homecontrols.shared.IOnControlsSettingsChangeListener
import com.android.systemui.dreams.homecontrols.shared.model.HomeControlsComponentInfo
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.kotlin.FlowDumperImpl
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn

/** Class to wrap [IHomeControlsRemoteProxy], which exposes the current user's home controls info */
class HomeControlsRemoteProxy
@AssistedInject
constructor(
    @Background bgScope: CoroutineScope,
    dumpManager: DumpManager,
    @Assisted private val proxy: IHomeControlsRemoteProxy,
) : FlowDumperImpl(dumpManager) {

    private companion object {
        const val TAG = "HomeControlsRemoteProxy"
    }

    val componentInfo: Flow<HomeControlsComponentInfo> =
        conflatedCallbackFlow {
                val listener =
                    object : IOnControlsSettingsChangeListener.Stub() {
                        override fun onControlsSettingsChanged(
                            panelComponent: ComponentName?,
                            allowTrivialControlsOnLockscreen: Boolean,
                        ) {
                            trySendWithFailureLogging(
                                HomeControlsComponentInfo(
                                    panelComponent,
                                    allowTrivialControlsOnLockscreen,
                                ),
                                TAG,
                            )
                        }
                    }
                proxy.registerListenerForCurrentUser(listener)
                awaitClose { proxy.unregisterListenerForCurrentUser(listener) }
            }
            .distinctUntilChanged()
            .stateIn(bgScope, SharingStarted.WhileSubscribed(), null)
            .dumpValue("componentInfo")
            .filterNotNull()

    @AssistedFactory
    interface Factory {
        fun create(proxy: IHomeControlsRemoteProxy): HomeControlsRemoteProxy
    }
}
