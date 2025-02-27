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

package com.android.systemui.dreams.homecontrols.dagger

import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dreams.homecontrols.service.HomeControlsRemoteProxy
import com.android.systemui.dreams.homecontrols.shared.IHomeControlsRemoteProxy
import com.android.systemui.dreams.homecontrols.system.HomeControlsRemoteService
import com.android.systemui.util.service.ObservableServiceConnection
import com.android.systemui.util.service.Observer
import com.android.systemui.util.service.PersistentConnectionManager
import com.android.systemui.util.service.dagger.ObservableServiceModule
import dagger.BindsInstance
import dagger.Module
import dagger.Provides
import dagger.Subcomponent
import javax.inject.Named

/**
 * This component is responsible for generating the connection to the home controls remote service
 * which runs in the SYSTEM_USER context and provides the data needed to run the home controls dream
 * in the foreground user context.
 */
@Subcomponent(
    modules =
        [
            ObservableServiceModule::class,
            HomeControlsRemoteServiceComponent.HomeControlsRemoteServiceModule::class,
        ]
)
interface HomeControlsRemoteServiceComponent {
    /** Creates a [HomeControlsRemoteServiceComponent]. */
    @Subcomponent.Factory
    interface Factory {
        fun create(
            @BindsInstance callback: ObservableServiceConnection.Callback<HomeControlsRemoteProxy>
        ): HomeControlsRemoteServiceComponent
    }

    /** A [PersistentConnectionManager] pointing to the home controls remote service. */
    val connectionManager: PersistentConnectionManager<HomeControlsRemoteProxy>

    /** Scoped module providing specific components for the [ObservableServiceConnection]. */
    @Module
    interface HomeControlsRemoteServiceModule {
        companion object {
            @Provides
            @Named(ObservableServiceModule.SERVICE_CONNECTION)
            fun providesConnection(
                connection: ObservableServiceConnection<HomeControlsRemoteProxy>,
                callback: ObservableServiceConnection.Callback<HomeControlsRemoteProxy>,
            ): ObservableServiceConnection<HomeControlsRemoteProxy> {
                connection.addCallback(callback)
                return connection
            }

            /** Provides the wrapper around the home controls remote binder */
            @Provides
            fun providesTransformer(
                factory: HomeControlsRemoteProxy.Factory
            ): ObservableServiceConnection.ServiceTransformer<HomeControlsRemoteProxy> {
                return ObservableServiceConnection.ServiceTransformer { service: IBinder ->
                    factory.create(IHomeControlsRemoteProxy.Stub.asInterface(service))
                }
            }

            /** Provides the intent to connect to [HomeControlsRemoteService] */
            @Provides
            fun providesIntent(@Application context: Context): Intent {
                return Intent(context, HomeControlsRemoteService::class.java)
            }

            /** Provides no-op [Observer] since the remote service is in the same package */
            @Provides
            @Named(ObservableServiceModule.OBSERVER)
            fun providesObserver(): Observer {
                return object : Observer {
                    override fun addCallback(callback: Observer.Callback?) {
                        // no-op, do nothing
                    }

                    override fun removeCallback(callback: Observer.Callback?) {
                        // no-op, do nothing
                    }
                }
            }

            /**
             * Provides a name that will be used by [PersistentConnectionManager] when logging
             * state.
             */
            @Provides
            @Named(ObservableServiceModule.DUMPSYS_NAME)
            fun providesDumpsysName(): String {
                return "HomeControlsRemoteService"
            }
        }
    }
}
