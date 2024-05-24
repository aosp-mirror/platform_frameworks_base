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

package com.android.systemui.media.nearby

import android.media.INearbyMediaDevicesProvider
import android.media.INearbyMediaDevicesUpdateCallback
import android.os.IBinder
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.CommandQueue
import dagger.Binds
import dagger.Module
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Inject

/**
 * A service that acts as a bridge between (1) external clients that have data on nearby devices
 * that are able to play media and (2) internal clients (like media Output Switcher) that need data
 * on these nearby devices.
 */
@SysUISingleton
class NearbyMediaDevicesManager @Inject constructor(
    private val commandQueue: CommandQueue,
    private val logger: NearbyMediaDevicesLogger
) : CoreStartable {
    private var providers: MutableList<INearbyMediaDevicesProvider> = mutableListOf()
    private var activeCallbacks: MutableList<INearbyMediaDevicesUpdateCallback> = mutableListOf()

    private val commandQueueCallbacks = object : CommandQueue.Callbacks {
        override fun registerNearbyMediaDevicesProvider(newProvider: INearbyMediaDevicesProvider) {
            if (providers.contains(newProvider)) {
                return
            }
            activeCallbacks.forEach {
                newProvider.registerNearbyDevicesCallback(it)
            }
            providers.add(newProvider)
            logger.logProviderRegistered(providers.size)
            newProvider.asBinder().linkToDeath(deathRecipient, /* flags= */ 0)
        }

        override fun unregisterNearbyMediaDevicesProvider(
            newProvider: INearbyMediaDevicesProvider
        ) {
            val isRemoved = providers.remove(newProvider)
            if (isRemoved) {
                logger.logProviderUnregistered(providers.size)
            }
        }
    }

    private val deathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            // Should not be used as binderDied(IBinder who) is overridden.
        }

        override fun binderDied(who: IBinder) {
            binderDiedInternal(who)
        }
    }

    override fun start() {
        commandQueue.addCallback(commandQueueCallbacks)
    }

    /**
     * Registers [callback] to be notified each time a device's range changes or when a new device
     * comes within range.
     *
     * If a new provider is added, previously-registered callbacks will be registered with the
     * new provider.
     */
    fun registerNearbyDevicesCallback(callback: INearbyMediaDevicesUpdateCallback) {
        providers.forEach {
            it.registerNearbyDevicesCallback(callback)
        }
        activeCallbacks.add(callback)
    }

    /**
     * Un-registers [callback]. See [registerNearbyDevicesCallback].
     */
    fun unregisterNearbyDevicesCallback(callback: INearbyMediaDevicesUpdateCallback) {
        activeCallbacks.remove(callback)
        providers.forEach {
            it.unregisterNearbyDevicesCallback(callback)
        }
    }

    private fun binderDiedInternal(who: IBinder) {
        synchronized(providers) {
            for (i in providers.size - 1 downTo 0) {
                if (providers[i].asBinder() == who) {
                    providers.removeAt(i)
                    logger.logProviderBinderDied(providers.size)
                    break
                }
            }
        }
    }

    @Module
    interface StartableModule {
        @Binds
        @IntoMap
        @ClassKey(NearbyMediaDevicesManager::class)
        fun bindsNearbyMediaDevicesManager(impl: NearbyMediaDevicesManager): CoreStartable
    }
}
