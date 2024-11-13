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

package com.android.systemui.deviceconfig.data.repository

import android.provider.DeviceConfig
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.DeviceConfigProxy
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn

@SysUISingleton
class DeviceConfigRepository
@Inject
constructor(
    @Background private val backgroundExecutor: Executor,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val dataSource: DeviceConfigProxy,
) {

    fun property(namespace: String, name: String, default: Boolean): Flow<Boolean> {
        return conflatedCallbackFlow {
                val listener = { properties: DeviceConfig.Properties ->
                    if (properties.keyset.contains(name)) {
                        trySend(properties.getBoolean(name, default))
                    }
                }

                dataSource.addOnPropertiesChangedListener(
                    namespace,
                    backgroundExecutor,
                    listener,
                )
                trySend(dataSource.getBoolean(namespace, name, default))

                awaitClose { dataSource.removeOnPropertiesChangedListener(listener) }
            }
            .flowOn(backgroundDispatcher)
    }
}
