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

package com.android.systemui.shade.domain.interactor

import android.content.mockedContext
import android.window.WindowContext
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.ui.view.mockShadeRootView
import com.android.systemui.shade.ShadeWindowLayoutParams
import com.android.systemui.shade.data.repository.fakeShadeDisplaysRepository
import java.util.Optional
import org.mockito.kotlin.mock

val Kosmos.shadeLayoutParams by Kosmos.Fixture { ShadeWindowLayoutParams.create(mockedContext) }

val Kosmos.mockedWindowContext by Kosmos.Fixture { mock<WindowContext>() }
val Kosmos.shadeDisplaysInteractor by
    Kosmos.Fixture {
        ShadeDisplaysInteractor(
            Optional.of(mockShadeRootView),
            fakeShadeDisplaysRepository,
            mockedWindowContext,
            testScope.backgroundScope,
            testScope.backgroundScope.coroutineContext,
        )
    }
