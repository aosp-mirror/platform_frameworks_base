/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.systemui.car;

import android.content.Context;
import android.util.ArrayMap;

import com.android.systemui.Dependency.DependencyProvider;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.volume.car.CarVolumeDialogController;

/**
 * Class factory to provide car specific SystemUI components.
 */
public class CarSystemUIFactory extends SystemUIFactory {
    @Override
    public void injectDependencies(ArrayMap<Object, DependencyProvider> providers,
            Context context) {
        super.injectDependencies(providers, context);
        providers.put(VolumeDialogController.class, () -> new CarVolumeDialogController(context));
    }
}
