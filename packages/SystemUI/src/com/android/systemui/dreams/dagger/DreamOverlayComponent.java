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

package com.android.systemui.dreams.dagger;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.complication.ComplicationHostViewController;
import com.android.systemui.dreams.DreamOverlayContainerViewController;
import com.android.systemui.dreams.touch.CommunalTouchHandler;
import com.android.systemui.dreams.touch.dagger.CommunalTouchModule;
import com.android.systemui.touch.TouchInsetManager;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;

import javax.inject.Scope;

/**
 * Dagger subcomponent for {@link DreamOverlayModule}.
 */
@Subcomponent(modules = {
        DreamOverlayModule.class,
        CommunalTouchModule.class
})
@DreamOverlayComponent.DreamOverlayScope
public interface DreamOverlayComponent {
    /** Simple factory for {@link DreamOverlayComponent}. */
    @Subcomponent.Factory
    interface Factory {
        DreamOverlayComponent create(
                @BindsInstance LifecycleOwner lifecycleOwner,
                @BindsInstance ComplicationHostViewController complicationHostViewController,
                @BindsInstance TouchInsetManager touchInsetManager);
    }

    /** Scope annotation for singleton items within the {@link DreamOverlayComponent}. */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamOverlayScope {}

    /** Builds a {@link DreamOverlayContainerViewController}. */
    DreamOverlayContainerViewController getDreamOverlayContainerViewController();

    /** Builds communal touch handler */
    CommunalTouchHandler getCommunalTouchHandler();
}
