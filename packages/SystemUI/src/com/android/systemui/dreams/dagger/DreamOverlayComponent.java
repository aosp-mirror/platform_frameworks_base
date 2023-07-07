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

import static com.android.systemui.dreams.dagger.DreamOverlayModule.DREAM_TOUCH_HANDLERS;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import android.annotation.Nullable;

import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.complication.ComplicationHostViewController;
import com.android.systemui.dreams.DreamOverlayContainerViewController;
import com.android.systemui.dreams.touch.DreamOverlayTouchMonitor;
import com.android.systemui.dreams.touch.DreamTouchHandler;
import com.android.systemui.dreams.touch.dagger.DreamTouchModule;
import com.android.systemui.touch.TouchInsetManager;

import dagger.BindsInstance;
import dagger.Subcomponent;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.util.Set;

import javax.inject.Named;
import javax.inject.Scope;

/**
 * Dagger subcomponent for {@link DreamOverlayModule}.
 */
@Subcomponent(modules = {
        DreamTouchModule.class,
        DreamOverlayModule.class,
})
@DreamOverlayComponent.DreamOverlayScope
public interface DreamOverlayComponent {
    /** Simple factory for {@link DreamOverlayComponent}. */
    @Subcomponent.Factory
    interface Factory {
        DreamOverlayComponent create(
                @BindsInstance LifecycleOwner lifecycleOwner,
                @BindsInstance ComplicationHostViewController complicationHostViewController,
                @BindsInstance TouchInsetManager touchInsetManager,
                @BindsInstance @Named(DREAM_TOUCH_HANDLERS) @Nullable
                        Set<DreamTouchHandler> dreamTouchHandlers);
    }

    /** Scope annotation for singleton items within the {@link DreamOverlayComponent}. */
    @Documented
    @Retention(RUNTIME)
    @Scope
    @interface DreamOverlayScope {}

    /** Builds a {@link DreamOverlayContainerViewController}. */
    DreamOverlayContainerViewController getDreamOverlayContainerViewController();

    /** Builds a {@link DreamOverlayTouchMonitor} */
    DreamOverlayTouchMonitor getDreamOverlayTouchMonitor();
}
