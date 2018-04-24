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
import android.view.View;

import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.ViewMediatorCallback;
import com.android.systemui.Dependency.DependencyProvider;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.statusbar.NotificationEntryManager;
import com.android.systemui.statusbar.car.CarFacetButtonController;
import com.android.systemui.statusbar.car.CarStatusBar;
import com.android.systemui.statusbar.car.CarStatusBarKeyguardViewManager;
import com.android.systemui.statusbar.car.hvac.HvacController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;

/**
 * Class factory to provide car specific SystemUI components.
 */
public class CarSystemUIFactory extends SystemUIFactory {

    public StatusBarKeyguardViewManager createStatusBarKeyguardViewManager(Context context,
            ViewMediatorCallback viewMediatorCallback, LockPatternUtils lockPatternUtils) {
        return new CarStatusBarKeyguardViewManager(context, viewMediatorCallback, lockPatternUtils);
    }

    @Override
    public void injectDependencies(ArrayMap<Object, DependencyProvider> providers,
            Context context) {
        super.injectDependencies(providers, context);
        providers.put(NotificationEntryManager.class,
                () -> new CarNotificationEntryManager(context));
        providers.put(CarFacetButtonController.class, () -> new CarFacetButtonController(context));
        providers.put(HvacController.class, () -> new HvacController(context));
    }
}
