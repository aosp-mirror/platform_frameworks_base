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

package com.android.keyguard.dagger;

import android.view.Display;

import com.android.keyguard.KeyguardClockSwitchController;
import com.android.keyguard.KeyguardStatusView;
import com.android.keyguard.KeyguardStatusViewController;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Subcomponent for helping work with KeyguardStatusView and its children.
 *
 * TODO: unify this with {@link KeyguardStatusBarViewComponent}
 */
@Subcomponent(modules = {KeyguardStatusViewModule.class, KeyguardDisplayModule.class})
@KeyguardStatusViewScope
public interface KeyguardStatusViewComponent {
    /** Simple factory for {@link KeyguardStatusViewComponent}. */
    @Subcomponent.Factory
    interface Factory {
        /** Creates {@link KeyguardStatusViewComponent} for a given display. */
        KeyguardStatusViewComponent build(
                @BindsInstance KeyguardStatusView presentation,
                @BindsInstance Display display
        );
    }

    /** Builds a {@link com.android.keyguard.KeyguardClockSwitchController}. */
    KeyguardClockSwitchController getKeyguardClockSwitchController();

    /** Builds a {@link com.android.keyguard.KeyguardStatusViewController}. */
    KeyguardStatusViewController getKeyguardStatusViewController();
}
