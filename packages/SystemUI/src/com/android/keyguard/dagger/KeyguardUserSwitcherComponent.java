/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.systemui.statusbar.policy.KeyguardUserSwitcherController;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcherView;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Subcomponent for helping work with KeyguardUserSwitcher and its children.
 */
@Subcomponent(modules = {KeyguardUserSwitcherModule.class})
@KeyguardUserSwitcherScope
public interface KeyguardUserSwitcherComponent {
    /** Simple factory for {@link KeyguardUserSwitcherComponent}. */
    @Subcomponent.Factory
    interface Factory {
        KeyguardUserSwitcherComponent build(
                @BindsInstance KeyguardUserSwitcherView keyguardUserSwitcherView);
    }

    /** Builds a {@link com.android.systemui.statusbar.policy.KeyguardUserSwitcherController}. */
    KeyguardUserSwitcherController getKeyguardUserSwitcherController();
}
