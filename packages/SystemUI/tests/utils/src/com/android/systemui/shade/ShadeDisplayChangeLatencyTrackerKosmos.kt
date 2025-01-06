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

package com.android.systemui.shade

import com.android.internal.logging.latencyTracker
import com.android.systemui.common.ui.data.repository.configurationRepository
import com.android.systemui.common.ui.view.fakeChoreographerUtils
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.ui.view.mockShadeRootView
import java.util.Optional

val Kosmos.shadeDisplayChangeLatencyTracker by Fixture {
    ShadeDisplayChangeLatencyTracker(
        Optional.of(mockShadeRootView),
        configurationRepository,
        latencyTracker,
        testScope.backgroundScope,
        fakeChoreographerUtils,
    )
}
