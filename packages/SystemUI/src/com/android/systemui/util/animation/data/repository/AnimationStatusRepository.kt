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
 * limitations under the License
 */
package com.android.systemui.util.animation.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.unfold.util.ScaleAwareTransitionProgressProvider.Companion.areAnimationsEnabled
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** Utility class that could give information about if animation are enabled in the system */
interface AnimationStatusRepository {
    fun areAnimationsEnabled(): Flow<Boolean>
}

class AnimationStatusRepositoryImpl
@Inject
constructor(
    private val resolver: ContentResolver,
    @Background private val backgroundHandler: Handler,
    @Background private val backgroundDispatcher: CoroutineDispatcher
) : AnimationStatusRepository {

    /**
     * Emits true if animations are enabled in the system, after subscribing it immediately emits
     * the current state
     */
    override fun areAnimationsEnabled(): Flow<Boolean> = conflatedCallbackFlow {
        val initialValue = withContext(backgroundDispatcher) { resolver.areAnimationsEnabled() }
        trySend(initialValue)

        val observer =
            object : ContentObserver(backgroundHandler) {
                override fun onChange(selfChange: Boolean) {
                    val updatedValue = resolver.areAnimationsEnabled()
                    trySend(updatedValue)
                }
            }

        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            /* notifyForDescendants= */ false,
            observer
        )

        awaitClose { resolver.unregisterContentObserver(observer) }
    }
}
