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

package com.android.systemui.plugins;

import static com.android.systemui.util.concurrency.GlobalConcurrencyModule.PRE_HANDLER;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.plugins.PluginEnabler;
import com.android.systemui.shared.plugins.PluginInitializer;
import com.android.systemui.shared.plugins.PluginInstanceManager;
import com.android.systemui.shared.plugins.PluginManager;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.shared.plugins.PluginPrefs;
import com.android.systemui.util.concurrency.GlobalConcurrencyModule;
import com.android.systemui.util.concurrency.ThreadFactory;

import java.util.Optional;
import java.util.concurrent.Executor;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;

/**
 * Dagger Module for code related to plugins.
 *
 * Covers code both in com.android.systemui.plugins and code in
 * com.android.systemui.shared.plugins.
 */
@Module(includes = {GlobalConcurrencyModule.class})
public abstract class PluginsModule {
    public static final String PLUGIN_THREAD = "plugin_thread";
    public static final String PLUGIN_DEBUG = "plugin_debug";
    public static final String PLUGIN_PRIVILEGED = "plugin_privileged";

    @Provides
    @Named(PLUGIN_DEBUG)
    static boolean providesPluginDebug() {
        return Build.IS_DEBUGGABLE;
    }

    @Binds
    abstract PluginEnabler bindsPluginEnablerImpl(PluginEnablerImpl impl);

    @Binds
    abstract PluginInitializer bindsPluginInitializerImpl(PluginInitializerImpl impl);

    @Provides
    @Singleton
    static PluginInstanceManager.Factory providePluginInstanceManagerFactory(Context context,
            PackageManager packageManager, @Main Executor mainExecutor,
            @Named(PLUGIN_THREAD) Looper pluginLooper, PluginInitializer initializer) {
        return new PluginInstanceManager.Factory(
                context, packageManager, mainExecutor, pluginLooper, initializer);
    }

    @Provides
    @Singleton
    @Named(PLUGIN_THREAD)
    static Looper providesPluginLooper(ThreadFactory threadFactory) {
        return threadFactory.buildLooperOnNewThread("plugin");
    }

    @Provides
    static PluginManager providesPluginManager(
            Context context,
            PluginInstanceManager.Factory instanceManagerFactory,
            @Named(PLUGIN_DEBUG) boolean debug,
            @Named(PRE_HANDLER)
                    Optional<Thread.UncaughtExceptionHandler> uncaughtExceptionHandlerOptional,
            PluginEnabler pluginEnabler,
            PluginPrefs pluginPrefs,
            @Named(PLUGIN_PRIVILEGED) String[] privilegedPlugins) {
        return new PluginManagerImpl(context, instanceManagerFactory, debug,
                uncaughtExceptionHandlerOptional, pluginEnabler, pluginPrefs,
                privilegedPlugins);
    }

    @Provides
    static PluginPrefs providesPluginPrefs(Context context) {
        return new PluginPrefs(context);
    }

    @Provides
    @Named(PLUGIN_PRIVILEGED)
    static String[] providesPrivilegedPlugins(PluginInitializer initializer, Context context) {
        return initializer.getPrivilegedPlugins(context);
    }
}
