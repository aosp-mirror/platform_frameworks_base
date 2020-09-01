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

import com.android.systemui.BootCompleteCacheImpl;
import com.android.systemui.Dependency;
import com.android.systemui.InitController;
import com.android.systemui.SystemUIAppComponentFactory;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardSliceProvider;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.InjectionInflationController;

import dagger.BindsInstance;
import dagger.Subcomponent;

/**
 * Dagger Subcomponent for Core SysUI.
 */
@SysUISingleton
@Subcomponent(modules = {
        DefaultComponentBinder.class,
        DependencyProvider.class,
        SystemUIBinder.class,
        SystemUIModule.class,
        SystemUIDefaultModule.class})
public interface SysUIComponent {

    /**
     * Builder for a SysUIComponent.
     */
    @Subcomponent.Builder
    interface Builder {
        @BindsInstance
        Builder setStubAPIClass(WMComponent.StubAPIClass stubAPIClass);

        SysUIComponent build();
    }

    /**
     * Provides a BootCompleteCache.
     */
    @SysUISingleton
    BootCompleteCacheImpl provideBootCacheImpl();

    /**
     * Creates a ContextComponentHelper.
     */
    @SysUISingleton
    ConfigurationController getConfigurationController();

    /**
     * Creates a ContextComponentHelper.
     */
    @SysUISingleton
    ContextComponentHelper getContextComponentHelper();

    /**
     * Main dependency providing module.
     */
    @SysUISingleton
    Dependency createDependency();

    /** */
    @SysUISingleton
    DumpManager createDumpManager();

    /**
     * Creates a InitController.
     */
    @SysUISingleton
    InitController getInitController();

    /**
     * ViewInstanceCreator generates all Views that need injection.
     */
    InjectionInflationController.ViewInstanceCreator.Factory createViewInstanceCreatorFactory();

    /**
     * Member injection into the supplied argument.
     */
    void inject(SystemUIAppComponentFactory factory);

    /**
     * Member injection into the supplied argument.
     */
    void inject(KeyguardSliceProvider keyguardSliceProvider);
}
