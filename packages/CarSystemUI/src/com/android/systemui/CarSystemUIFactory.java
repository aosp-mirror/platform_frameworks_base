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
 * limitations under the License
 */

package com.android.systemui;

import android.content.Context;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.statusbar.car.CarFacetButtonController;
import com.android.systemui.statusbar.car.CarStatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.volume.CarVolumeDialogComponent;
import com.android.systemui.volume.VolumeDialogComponent;

import javax.inject.Singleton;

import dagger.Component;

/**
 * Class factory to provide car specific SystemUI components.
 */
public class CarSystemUIFactory extends SystemUIFactory {

    private CarDependencyComponent mCarDependencyComponent;

    @Override
    protected SystemUIRootComponent buildSystemUIRootComponent(Context context) {
        mCarDependencyComponent = DaggerCarSystemUIFactory_CarDependencyComponent.builder()
                .contextHolder(new ContextHolder(context))
                .build();
        return DaggerCarSystemUIRootComponent.builder()
                .dependencyProvider(new com.android.systemui.DependencyProvider())
                .contextHolder(new ContextHolder(context))
                .build();
    }

    public CarDependencyComponent getCarDependencyComponent() {
        return mCarDependencyComponent;
    }

    public StatusBarKeyguardViewManager createStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        return new CarStatusBarKeyguardViewManager(context, viewMediatorCallback, lockPatternUtils);
    }

    public VolumeDialogComponent createVolumeDialogComponent(SystemUI systemUi, Context context) {
        return new CarVolumeDialogComponent(systemUi, context);
    }

    @Singleton
    @Component(modules = ContextHolder.class)
    public interface CarDependencyComponent {
        CarFacetButtonController getCarFacetButtonController();
    }
}
