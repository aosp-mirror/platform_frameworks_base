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

package com.android.systemui.volume.dialog.ui.binder

import android.content.applicationContext
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.volume.dialog.ringer.volumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.volumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.sliders.ui.volumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.utils.jankListenerFactory
import com.android.systemui.volume.dialog.ui.viewmodel.volumeDialogViewModel
import com.android.systemui.volume.dialog.utils.volumeTracer

val Kosmos.volumeDialogViewBinder by
    Kosmos.Fixture {
        VolumeDialogViewBinder(
            applicationContext.resources,
            volumeDialogViewModel,
            jankListenerFactory,
            volumeTracer,
            volumeDialogRingerViewBinder,
            volumeDialogSlidersViewBinder,
            volumeDialogSettingsButtonViewBinder,
        )
    }
