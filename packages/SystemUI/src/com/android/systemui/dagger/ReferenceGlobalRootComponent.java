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

package com.android.systemui.dagger;

import dagger.Component;

import javax.inject.Singleton;

/**
 * Root component for Dagger injection used in AOSP.
 */
@Singleton
@Component(modules = {GlobalModule.class})
public interface ReferenceGlobalRootComponent extends GlobalRootComponent {

    /**
     * Builder for a ReferenceGlobalRootComponent.
     */
    @Component.Builder
    interface Builder extends GlobalRootComponent.Builder {
        ReferenceGlobalRootComponent build();
    }

    /**
     * Builder for a {@link ReferenceSysUIComponent}, which makes it a subcomponent of this class.
     */
    ReferenceSysUIComponent.Builder getSysUIComponent();
}
