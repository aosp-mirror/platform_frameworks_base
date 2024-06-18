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

package com.android.systemui.qs.pipeline.domain.interactor

import android.content.Context
import android.view.accessibility.Flags
import com.android.systemui.accessibility.data.repository.AccessibilityQsShortcutsRepository
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.qs.pipeline.domain.model.TileModel
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.util.kotlin.sample
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Observe the tiles in the QS Panel and perform accessibility related actions */
@SysUISingleton
class AccessibilityTilesInteractor
@Inject
constructor(
    private val a11yQsShortcutsRepository: AccessibilityQsShortcutsRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @Application private val scope: CoroutineScope,
) {
    private val initialized = AtomicBoolean(false)

    /** Start collection of signals following the user from [currentTilesInteractor]. */
    fun init(currentTilesInteractor: CurrentTilesInteractor) {
        if (!initialized.compareAndSet(/* expectedValue= */ false, /* newValue= */ true)) {
            return
        }

        if (Flags.a11yQsShortcut()) {
            startObservingTiles(currentTilesInteractor)
        }
    }

    private fun startObservingTiles(currentTilesInteractor: CurrentTilesInteractor) {
        scope.launch(backgroundDispatcher) {
            currentTilesInteractor.currentTiles
                .sample(currentTilesInteractor.userContext) { currentTiles, userContext ->
                    Data(currentTiles.map(TileModel::spec), userContext)
                }
                .collectLatest {
                    a11yQsShortcutsRepository.notifyAccessibilityManagerTilesChanged(
                        it.userContext,
                        it.currentTileSpecs
                    )
                }
        }
    }

    private data class Data(
        val currentTileSpecs: List<TileSpec>,
        val userContext: Context,
    )
}
