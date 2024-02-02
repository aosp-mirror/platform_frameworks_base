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
package com.android.systemui.dreams.homecontrols

import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.panels.authorizedPanelsRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.dreams.homecontrols.domain.interactor.HomeControlsComponentInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.mock

val Kosmos.homeControlsComponentInteractor by
    Kosmos.Fixture {
        HomeControlsComponentInteractor(
            selectedComponentRepository = selectedComponentRepository,
            controlsComponent,
            authorizedPanelsRepository = authorizedPanelsRepository,
            userRepository = fakeUserRepository,
            bgScope = applicationCoroutineScope,
        )
    }

val Kosmos.controlsComponent by Kosmos.Fixture<ControlsComponent> { mock() }
val Kosmos.controlsListingController by Kosmos.Fixture<ControlsListingController> { mock() }
