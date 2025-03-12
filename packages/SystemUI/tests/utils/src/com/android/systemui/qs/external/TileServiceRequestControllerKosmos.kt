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

package com.android.systemui.qs.external

import com.android.app.iUriGrantsManager
import com.android.internal.logging.uiEventLoggerFake
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.external.ui.dialog.tileRequestDialogComposeDelegateFactory
import com.android.systemui.qs.instanceIdSequenceFake
import com.android.systemui.qs.qsHostAdapter
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.commandline.commandRegistry
import org.mockito.kotlin.mock

val Kosmos.tileServiceRequestControllerBuilder by
    Kosmos.Fixture {
        TileServiceRequestController.Builder(
            commandQueue,
            commandRegistry,
            iUriGrantsManager,
            tileRequestDialogComposeDelegateFactory,
        )
    }

val Kosmos.tileServiceRequestController by
    Kosmos.Fixture {
        TileServiceRequestController(
            qsHostAdapter,
            commandQueue,
            commandRegistry,
            TileRequestDialogEventLogger(uiEventLoggerFake, instanceIdSequenceFake),
            iUriGrantsManager,
            tileRequestDialogComposeDelegateFactory,
            { mock() },
        )
    }
