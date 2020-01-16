/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.volume;

import android.content.Context;

import com.android.systemui.SystemUI;
import com.android.systemui.plugins.VolumeDialog;

/**
 * Allows for adding car specific dialog when the volume dialog is created.
 */
public class CarVolumeDialogComponent extends VolumeDialogComponent {

    public CarVolumeDialogComponent(SystemUI sysui, Context context) {
        super(sysui, context);
    }

    protected VolumeDialog createDefault() {
        CarVolumeDialogImpl carVolumeDialog = new CarVolumeDialogImpl(mContext);
        // Since VolumeUI is initialized when the first Volume Up/Down event is received we need to
        // show the dialog on initialization too.
        carVolumeDialog.show(Events.SHOW_REASON_VOLUME_CHANGED);
        return carVolumeDialog;
    }
}
