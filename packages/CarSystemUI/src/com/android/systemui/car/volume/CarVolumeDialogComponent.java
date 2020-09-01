/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.volume;

import android.content.Context;

import com.android.systemui.car.CarServiceProvider;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.volume.VolumeDialogComponent;
import com.android.systemui.volume.VolumeDialogControllerImpl;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Allows for adding car specific dialog when the volume dialog is created.
 */
@Singleton
public class CarVolumeDialogComponent extends VolumeDialogComponent {

    private CarVolumeDialogImpl mCarVolumeDialog;

    @Inject
    public CarVolumeDialogComponent(Context context, KeyguardViewMediator keyguardViewMediator,
            VolumeDialogControllerImpl volumeDialogController,
            CarServiceProvider carServiceProvider) {
        super(context, keyguardViewMediator, volumeDialogController);
        mCarVolumeDialog.setCarServiceProvider(carServiceProvider);
    }

    /** This method is called while calling the super constructor. */
    @Override
    protected VolumeDialog createDefault() {
        mCarVolumeDialog = new CarVolumeDialogImpl(mContext);
        return mCarVolumeDialog;
    }
}
