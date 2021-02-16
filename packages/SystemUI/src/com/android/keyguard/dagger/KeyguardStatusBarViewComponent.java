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

import com.android.keyguard.KeyguardStatusViewController;
import com.android.systemui.statusbar.phone.KeyguardStatusBarView;
import com.android.systemui.statusbar.phone.KeyguardStatusBarViewController;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Subcomponent for helping work with KeyguardStatusView and its children.
 *
 * TODO: unify this with {@link KeyguardStatusViewComponent}
 */
@Subcomponent(modules = {KeyguardStatusBarViewModule.class})
@KeyguardStatusBarViewScope
public interface KeyguardStatusBarViewComponent {
    /** Simple factory for {@link KeyguardStatusBarViewComponent}. */
    @Subcomponent.Factory
    interface Factory {
        KeyguardStatusBarViewComponent build(@BindsInstance KeyguardStatusBarView view);
    }

    /** Builds a {@link KeyguardStatusViewController}. */
    KeyguardStatusBarViewController getKeyguardStatusBarViewController();
}
