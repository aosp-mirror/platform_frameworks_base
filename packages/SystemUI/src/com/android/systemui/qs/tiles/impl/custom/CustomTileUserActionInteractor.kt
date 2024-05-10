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

package com.android.systemui.qs.tiles.impl.custom

import com.android.systemui.qs.tiles.base.interactor.QSTileInput
import com.android.systemui.qs.tiles.base.interactor.QSTileUserActionInteractor
import com.android.systemui.qs.tiles.impl.custom.domain.entity.CustomTileDataModel
import com.android.systemui.qs.tiles.impl.di.QSTileScope
import javax.inject.Inject

@QSTileScope
class CustomTileUserActionInteractor @Inject constructor() :
    QSTileUserActionInteractor<CustomTileDataModel> {

    override suspend fun handleInput(input: QSTileInput<CustomTileDataModel>) {
        TODO("Not yet implemented")
    }
}
