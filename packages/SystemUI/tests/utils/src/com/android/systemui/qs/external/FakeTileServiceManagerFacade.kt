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

package com.android.systemui.qs.external

import android.service.quicksettings.IQSTileService
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

// TODO(b/299909989) Make a fake instead
class FakeTileServiceManagerFacade(
    private val iQSTileService: IQSTileService,
    val tileServiceManager: TileServiceManager = mock {},
) {

    private var hasPendingBind: Boolean = false

    var isBound: Boolean = false
        private set

    init {
        with(tileServiceManager) {
            whenever(tileService).thenReturn(iQSTileService)
            whenever(setBindRequested(any())).then {
                val isRequested: Boolean = it.getArgument(0)
                hasPendingBind = isRequested
                if (!isRequested) {
                    isBound = false
                }
                Unit
            }
            whenever(clearPendingBind()).then {
                hasPendingBind = false
                Unit
            }
            whenever(hasPendingBind()).then { hasPendingBind }
        }
    }

    fun processPendingBind() {
        if (hasPendingBind) {
            isBound = true
        }
    }
}
