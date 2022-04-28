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

import android.content.Context;

import com.android.settingslib.dream.DreamBackend;
import com.android.systemui.dreams.complication.dagger.RegisteredComplicationsModule;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module providing Dream-related functionality.
 */
@Module(includes = {
            RegisteredComplicationsModule.class,
        },
        subcomponents = {
            DreamOverlayComponent.class,
        })
public interface DreamModule {
    /**
     * Provides an instance of the dream backend.
     */
    @Provides
    static DreamBackend providesDreamBackend(Context context) {
        return DreamBackend.getInstance(context);
    }
}
