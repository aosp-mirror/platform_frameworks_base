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

import android.content.Context;

import com.android.systemui.util.concurrency.ThreadFactory;

import javax.inject.Singleton;

import dagger.BindsInstance;
import dagger.Component;

/**
 * Root component for Dagger injection.
 */
@Singleton
@Component(modules = {
        GlobalModule.class,
        SysUISubcomponentModule.class,
        WMModule.class})
public interface GlobalRootComponent {

    /**
     * Builder for a GlobalRootComponent.
     */
    @Component.Builder
    interface Builder {
        @BindsInstance
        Builder context(Context context);

        GlobalRootComponent build();
    }

    /**
     * Builder for a WMComponent.
     */
    WMComponent.Builder getWMComponentBuilder();

    /**
     * Builder for a SysUIComponent.
     */
    SysUIComponent.Builder getSysUIComponent();

    /**
     * Build a {@link ThreadFactory}.
     */
    ThreadFactory createThreadFactory();
}
