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

package com.android.systemui.display.data.repository

import android.view.Display
import com.android.systemui.CoreStartable
import com.android.systemui.coroutines.newTracingContext
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import com.android.app.tracing.coroutines.launchTraced as launch

/**
 * Provides per display instances of [CoroutineScope]. These will remain active as long as the
 * display is connected, and automatically cancelled when the display is removed.
 */
interface DisplayScopeRepository {
    fun scopeForDisplay(displayId: Int): CoroutineScope
}

@SysUISingleton
class DisplayScopeRepositoryImpl
@Inject
constructor(
    @Background private val backgroundApplicationScope: CoroutineScope,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    private val displayRepository: DisplayRepository,
) : DisplayScopeRepository, CoreStartable {

    private val perDisplayScopes = ConcurrentHashMap<Int, CoroutineScope>()

    override fun scopeForDisplay(displayId: Int): CoroutineScope {
        return perDisplayScopes.computeIfAbsent(displayId) { createScopeForDisplay(displayId) }
    }

    override fun start() {
        StatusBarConnectedDisplays.assertInNewMode()
        backgroundApplicationScope.launch {
            displayRepository.displayRemovalEvent.collect { displayId ->
                val scope = perDisplayScopes.remove(displayId)
                scope?.cancel("Display $displayId has been removed.")
            }
        }
    }

    private fun createScopeForDisplay(displayId: Int): CoroutineScope {
        return if (displayId == Display.DEFAULT_DISPLAY) {
            // The default display is connected all the time, therefore we can optimise by reusing
            // the application scope, and don't need to create a new scope.
            backgroundApplicationScope
        } else {
            CoroutineScope(
                backgroundDispatcher + newTracingContext("DisplayScope$displayId")
            )
        }
    }
}
