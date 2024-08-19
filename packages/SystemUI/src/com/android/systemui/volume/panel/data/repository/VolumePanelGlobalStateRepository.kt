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

package com.android.systemui.volume.panel.data.repository

import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.volume.panel.shared.VolumePanelLogger
import com.android.systemui.volume.panel.shared.model.VolumePanelGlobalState
import java.io.PrintWriter
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "VolumePanelGlobalStateRepository"

@SysUISingleton
class VolumePanelGlobalStateRepository
@Inject
constructor(
    dumpManager: DumpManager,
    private val logger: VolumePanelLogger,
) : Dumpable {

    private val mutableGlobalState =
        MutableStateFlow(
            VolumePanelGlobalState(
                isVisible = false,
            )
        )
    val globalState: StateFlow<VolumePanelGlobalState> = mutableGlobalState.asStateFlow()

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    fun updateVolumePanelState(
        update: (currentState: VolumePanelGlobalState) -> VolumePanelGlobalState
    ) {
        mutableGlobalState.update(update)
        logger.onVolumePanelGlobalStateChanged(mutableGlobalState.value)
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        with(globalState.value) { pw.println("isVisible: $isVisible") }
    }
}
