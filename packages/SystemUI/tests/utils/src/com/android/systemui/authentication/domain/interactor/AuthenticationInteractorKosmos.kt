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

package com.android.systemui.authentication.domain.interactor

import com.android.systemui.authentication.data.repository.authenticationRepository
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.user.domain.interactor.selectedUserInteractor

val Kosmos.authenticationInteractor by
    Kosmos.Fixture {
        AuthenticationInteractor(
            applicationScope = applicationCoroutineScope,
            backgroundDispatcher = testDispatcher,
            repository = authenticationRepository,
            selectedUserInteractor = selectedUserInteractor,
            tableLogBuffer = logcatTableLogBuffer(this, "sceneFrameworkTableLogBuffer"),
        )
    }
