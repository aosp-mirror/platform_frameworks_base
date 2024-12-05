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

package com.android.systemui.media.controls.ui.view

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.media.controls.domain.pipeline.mediaDataManager
import com.android.systemui.media.controls.ui.controller.mediaCarouselController
import com.android.systemui.media.controls.ui.controller.mediaCarouselControllerLogger
import com.android.systemui.media.controls.ui.controller.mediaHierarchyManager
import com.android.systemui.media.controls.ui.controller.mediaHostStatesManager
import java.util.function.Supplier

private val Kosmos.mediaHostProvider by
    Kosmos.Fixture {
        Supplier<MediaHost> {
            MediaHost(
                MediaHost.MediaHostStateHolder(),
                mediaHierarchyManager,
                mediaDataManager,
                mediaHostStatesManager,
                mediaCarouselController,
                mediaCarouselControllerLogger,
            )
        }
    }

val Kosmos.qqsMediaHost by Kosmos.Fixture { mediaHostProvider.get() }
val Kosmos.qsMediaHost by Kosmos.Fixture { mediaHostProvider.get() }
