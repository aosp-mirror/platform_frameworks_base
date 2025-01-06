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

package com.android.systemui.qs.external.ui.dialog

import android.content.DialogInterface
import com.android.systemui.qs.external.TileData
import org.mockito.Answers
import org.mockito.kotlin.mock

class FakeTileRequestDialogComposeDelegateFactory : TileRequestDialogComposeDelegate.Factory {
    lateinit var tileData: TileData
    lateinit var clickListener: DialogInterface.OnClickListener

    override fun create(
        tileData: TileData,
        dialogListener: DialogInterface.OnClickListener,
    ): TileRequestDialogComposeDelegate {
        this.tileData = tileData
        this.clickListener = dialogListener
        return mock(defaultAnswer = Answers.RETURNS_MOCKS)
    }
}
