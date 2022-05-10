/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.systemui.dreams.complication.dagger;

import com.android.systemui.dreams.complication.Complication;
import com.android.systemui.dreams.complication.ComplicationId;
import com.android.systemui.dreams.complication.ComplicationViewModelProvider;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * The {@link ComplicationViewModelComponent} allows for a
 * {@link com.android.systemui.dreams.complication.ComplicationViewModel} for a particular
 * {@link Complication}. This component binds these instance specific values to allow injection with
 * values provided at the wider scope.
 */
@Subcomponent
public interface ComplicationViewModelComponent {
    /**
     * Factory for generating {@link ComplicationViewModelComponent}.
     */
    @Subcomponent.Factory
    interface Factory {
        ComplicationViewModelComponent create(@BindsInstance Complication complication,
                @BindsInstance ComplicationId id);
    }

    /** */
    ComplicationViewModelProvider getViewModelProvider();
}
