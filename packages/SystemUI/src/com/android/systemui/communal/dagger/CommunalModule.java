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

package com.android.systemui.communal.dagger;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.idle.AmbientLightModeMonitor;
import com.android.systemui.idle.LightSensorEventsDebounceAlgorithm;
import com.android.systemui.idle.dagger.IdleViewComponent;

import javax.inject.Named;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing Communal-related functionality.
 */
@Module(subcomponents = {
        CommunalViewComponent.class,
        IdleViewComponent.class,
})
public interface CommunalModule {
    String IDLE_VIEW = "idle_view";

    /** */
    @Provides
    @Named(IDLE_VIEW)
    static View provideIdleView(Context context) {
        FrameLayout view = new FrameLayout(context);
        return view;
    }

    /**
     * Provides LightSensorEventsDebounceAlgorithm as an instance to DebounceAlgorithm interface.
     * @param algorithm the instance of algorithm that is bound to the interface.
     * @return the interface that is bound to.
     */
    @Binds
    AmbientLightModeMonitor.DebounceAlgorithm ambientLightDebounceAlgorithm(
            LightSensorEventsDebounceAlgorithm algorithm);
}
