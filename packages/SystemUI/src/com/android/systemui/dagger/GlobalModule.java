/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.util.DisplayMetrics;
import android.view.Display;

import com.android.systemui.dagger.qualifiers.Application;
import com.android.systemui.plugins.PluginsModule;
import com.android.systemui.unfold.UnfoldTransitionModule;
import com.android.systemui.util.concurrency.GlobalConcurrencyModule;
import com.android.systemui.util.kotlin.GlobalCoroutinesModule;

import dagger.Module;
import dagger.Provides;

/**
 * Supplies globally scoped instances that should be available in all versions of SystemUI
 *
 * Providers in this module will be accessible to both WMComponent and SysUIComponent scoped
 * classes. They are in here because they are either needed globally or are inherently universal
 * to the application.
 *
 * Note that just because a class might be used by both WM and SysUI does not necessarily mean that
 * it should go into this module. If WM and SysUI might need the class for different purposes
 * or different semantics, it may make sense to ask them to supply their own. Something like
 * threading and concurrency provide a good example. Both components need
 * Threads/Handlers/Executors, but they need separate instances of them in many cases.
 *
 * Please use discretion when adding things to the global scope.
 */
@Module(includes = {
        AndroidInternalsModule.class,
        FrameworkServicesModule.class,
        GlobalConcurrencyModule.class,
        GlobalCoroutinesModule.class,
        UnfoldTransitionModule.class,
        PluginsModule.class,
})
public class GlobalModule {
    /**
     * TODO(b/229228871): This should be the default. No undecorated context should be available.
     */
    @Provides
    @Application
    public Context provideApplicationContext(Context context) {
        return context.getApplicationContext();
    }

    /**
     * @deprecated Deprecdated because {@link Display#getMetrics} is deprecated.
     */
    @Provides
    @Deprecated
    public DisplayMetrics provideDisplayMetrics(Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        context.getDisplay().getMetrics(displayMetrics);
        return displayMetrics;
    }
}
