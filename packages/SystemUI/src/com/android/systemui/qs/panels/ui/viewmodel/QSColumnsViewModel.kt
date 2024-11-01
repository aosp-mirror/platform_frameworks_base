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

package com.android.systemui.qs.panels.ui.viewmodel

import androidx.compose.runtime.State
import com.android.systemui.lifecycle.Activatable
import com.android.systemui.lifecycle.ExclusiveActivatable
import com.android.systemui.lifecycle.Hydrator
import com.android.systemui.qs.panels.domain.interactor.QSColumnsInteractor
import javax.inject.Inject

interface QSColumnsViewModel : Activatable {
    val columns: State<Int>
}

class QSColumnsSizeViewModelImpl @Inject constructor(interactor: QSColumnsInteractor) :
    QSColumnsViewModel, ExclusiveActivatable() {
    private val hydrator = Hydrator("QSColumnsSizeViewModelImpl")

    override val columns =
        hydrator.hydratedStateOf(traceName = "columns", source = interactor.columns)

    override suspend fun onActivated(): Nothing {
        hydrator.activate()
    }
}
