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

package com.android.systemui.qs.tiles.impl.di

import com.android.systemui.qs.tiles.base.interactor.QSTileDataInteractor
import com.android.systemui.qs.tiles.base.interactor.QSTileDataToStateMapper
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import kotlinx.coroutines.CoroutineScope

/**
 * Base QS tile component. It should be used with [QSTileScope] to create a custom tile scoped
 * component. Pass this component to
 * [com.android.systemui.qs.tiles.base.viewmodel.QSTileViewModelFactory.Component].
 */
interface QSTileComponent<T> {

    fun dataInteractor(): QSTileDataInteractor<T>

    fun userActionInteractor(): QSTileUserActionInteractor<T>

    fun dataToStateMapper(): QSTileDataToStateMapper<T>

    /**
     * Use [com.android.systemui.qs.tiles.base.viewmodel.QSTileCoroutineScopeFactory] to create a
     * [CoroutineScope] provided by this method. This enables you to use the same scope the
     * [com.android.systemui.qs.tiles.viewmodel.QSTileViewModel] uses. This scope is cancelled when
     * the view model is destroyed.
     */
    @QSTileScope fun coroutineScope(): CoroutineScope
}
