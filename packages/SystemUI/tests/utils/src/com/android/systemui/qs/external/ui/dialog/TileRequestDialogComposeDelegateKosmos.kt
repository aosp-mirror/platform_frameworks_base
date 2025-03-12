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
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.qs.external.TileData
import com.android.systemui.qs.external.ui.viewmodel.tileRequestDialogViewModelFactory
import com.android.systemui.statusbar.phone.systemUIDialogFactory

var Kosmos.tileRequestDialogComposeDelegateFactory by
    Kosmos.Fixture<TileRequestDialogComposeDelegate.Factory> {
        object : TileRequestDialogComposeDelegate.Factory {
            override fun create(
                tiledata: TileData,
                dialogListener: DialogInterface.OnClickListener,
            ): TileRequestDialogComposeDelegate {
                return TileRequestDialogComposeDelegate(
                    systemUIDialogFactory,
                    tileRequestDialogViewModelFactory,
                    tiledata,
                    dialogListener,
                )
            }
        }
    }

val TileRequestDialogComposeDelegate.Factory.fake: FakeTileRequestDialogComposeDelegateFactory
    get() = this as FakeTileRequestDialogComposeDelegateFactory
