/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.pip.tv.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.android.systemui.pip.tv.PipControlsView;
import com.android.systemui.pip.tv.PipControlsViewController;
import com.android.systemui.statusbar.phone.dagger.StatusBarComponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Scope;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Component for injecting into Pip related classes.
 */
@Subcomponent
public interface TvPipComponent {
    /**
     * Builder for {@link StatusBarComponent}.
     */
    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        TvPipComponent.Builder pipControlsView(PipControlsView pipControlsView);
        TvPipComponent build();
    }

    /**
     * Scope annotation for singleton items within the PipComponent.
     */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface PipScope {}

    /**
     * Creates a StatusBarWindowViewController.
     */
    @TvPipComponent.PipScope
    PipControlsViewController getPipControlsViewController();
}
