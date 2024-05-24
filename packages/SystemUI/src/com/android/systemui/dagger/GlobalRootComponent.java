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
import android.os.Looper;

import com.android.systemui.dagger.qualifiers.InstrumentationTest;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.SystemPropertiesHelper;
import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.util.InitializationChecker;

import dagger.BindsInstance;

/**
 * Base root component for Dagger injection.
 *
 * This class is not actually annotated as a Dagger component, since it is not used directly as one.
 * Doing so generates unnecessary code bloat.
 *
 * See {@link ReferenceGlobalRootComponent} for the one actually used by AOSP.
 */
public interface GlobalRootComponent {

    /**
     * Builder for a GlobalRootComponent.
     */
    interface Builder {
        @BindsInstance
        Builder context(Context context);
        @BindsInstance
        Builder instrumentationTest(@InstrumentationTest boolean test);
        GlobalRootComponent build();
    }

    /**
     * Builder for a {@link WMComponent}, which makes it a subcomponent of this class.
     */
    WMComponent.Builder getWMComponentBuilder();

    /**
     * Builder for a {@link ReferenceSysUIComponent}, which makes it a subcomponent of this class.
     */
    SysUIComponent.Builder getSysUIComponent();

    /**
     * Returns an {@link InitializationChecker}.
     */
    InitializationChecker getInitializationChecker();

    /**
     * Returns the main looper for this process.
     */
    @Main
    Looper getMainLooper();

    /**
     * Returns a {@link SystemPropertiesHelper}.
     */
    SystemPropertiesHelper getSystemPropertiesHelper();

    /**
     * Returns a {@link ProcessWrapper}
     */
    ProcessWrapper getProcessWrapper();
}
