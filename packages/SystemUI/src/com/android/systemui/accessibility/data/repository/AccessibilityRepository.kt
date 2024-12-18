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

package com.android.systemui.accessibility.data.repository

import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener
import com.android.app.tracing.FlowTracing.tracedAwaitClose
import com.android.app.tracing.FlowTracing.tracedConflatedCallbackFlow
import dagger.Module
import dagger.Provides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Exposes accessibility-related state. */
interface AccessibilityRepository {
    /** @see [AccessibilityManager.isTouchExplorationEnabled] */
    val isTouchExplorationEnabled: Flow<Boolean>
    /** @see [AccessibilityManager.isEnabled] */
    val isEnabled: Flow<Boolean>

    companion object {
        operator fun invoke(a11yManager: AccessibilityManager): AccessibilityRepository =
            AccessibilityRepositoryImpl(a11yManager)
    }
}

private const val TAG = "AccessibilityRepository"

private class AccessibilityRepositoryImpl(
    manager: AccessibilityManager,
) : AccessibilityRepository {
    override val isTouchExplorationEnabled: Flow<Boolean> =
        tracedConflatedCallbackFlow(TAG) {
                val listener = TouchExplorationStateChangeListener(::trySend)
                manager.addTouchExplorationStateChangeListener(listener)
                trySend(manager.isTouchExplorationEnabled)
                tracedAwaitClose(TAG) {
                    manager.removeTouchExplorationStateChangeListener(listener)
                }
            }
            .distinctUntilChanged()

    override val isEnabled: Flow<Boolean> =
        tracedConflatedCallbackFlow(TAG) {
                val listener = AccessibilityManager.AccessibilityStateChangeListener(::trySend)
                manager.addAccessibilityStateChangeListener(listener)
                trySend(manager.isEnabled)
                tracedAwaitClose(TAG) { manager.removeAccessibilityStateChangeListener(listener) }
            }
            .distinctUntilChanged()
}

@Module
object AccessibilityRepositoryModule {
    @Provides fun provideRepo(manager: AccessibilityManager) = AccessibilityRepository(manager)
}
