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

import android.app.Activity;

import com.android.systemui.pip.BasePipManager;
import com.android.systemui.pip.tv.PipManager;
import com.android.systemui.pip.tv.PipMenuActivity;

import dagger.Binds;
import dagger.Module;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;

/**
 * Dagger module for TV Pip.
 */
@Module(subcomponents = {TvPipComponent.class})
public abstract class PipModule {

    /** Binds PipManager as the default BasePipManager. */
    @Binds
    public abstract BasePipManager providePipManager(PipManager pipManager);


    /** Inject into PipMenuActivity. */
    @Binds
    @IntoMap
    @ClassKey(PipMenuActivity.class)
    public abstract Activity providePipMenuActivity(PipMenuActivity activity);
}
