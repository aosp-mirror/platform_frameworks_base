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

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import com.android.systemui.res.R;
import com.android.systemui.dagger.PluginModule;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.plugins.PluginActionManager;
import com.android.systemui.shared.plugins.PluginEnabler;
import com.android.systemui.shared.plugins.PluginInstance;
import com.android.systemui.shared.plugins.PluginManagerImpl;
import com.android.systemui.shared.plugins.PluginPrefs;
import com.android.systemui.shared.system.UncaughtExceptionPreHandlerManager;
import com.android.systemui.util.concurrency.GlobalConcurrencyModule;
import com.android.systemui.util.concurrency.ThreadFactory;

import java.util.Arrays;
import java.util.List;
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

    @Provides
    @Singleton
    static PluginInstance.Factory providesPluginInstanceFactory(
            @Named(PLUGIN_PRIVILEGED) List<String> privilegedPlugins,
            @Named(PLUGIN_DEBUG) boolean isDebug) {
        return new PluginInstance.Factory(
                PluginModule.class.getClassLoader(),
                new PluginInstance.InstanceFactory<>(),
                new PluginInstance.VersionCheckerImpl(),
                privilegedPlugins,
                isDebug);
    }

    @Provides
    @Singleton
    static PluginActionManager.Factory providePluginInstanceManagerFactory(Context context,
            PackageManager packageManager, @Main Executor mainExecutor,
            @Named(PLUGIN_THREAD) Executor pluginExecutor,
            NotificationManager notificationManager, PluginEnabler pluginEnabler,
            @Named(PLUGIN_PRIVILEGED) List<String> privilegedPlugins,
            PluginInstance.Factory pluginInstanceFactory) {
        return new PluginActionManager.Factory(
                context, packageManager, mainExecutor, pluginExecutor,
                notificationManager, pluginEnabler, privilegedPlugins, pluginInstanceFactory);
    }

    @Provides
    @Singleton
    @Named(PLUGIN_THREAD)
    static Executor providesPluginExecutor(ThreadFactory threadFactory) {
        return threadFactory.buildExecutorOnNewThread("plugin");
    }

    @Provides
    @Singleton
    static PluginManager providesPluginManager(
            Context context,
            PluginActionManager.Factory instanceManagerFactory,
            @Named(PLUGIN_DEBUG) boolean debug,
            UncaughtExceptionPreHandlerManager preHandlerManager,
            PluginEnabler pluginEnabler,
            PluginPrefs pluginPrefs,
            @Named(PLUGIN_PRIVILEGED) List<String> privilegedPlugins) {
        return new PluginManagerImpl(context, instanceManagerFactory, debug,
                preHandlerManager, pluginEnabler, pluginPrefs,
                privilegedPlugins);
    }

    @Provides
    static PluginPrefs providesPluginPrefs(Context context) {
        return new PluginPrefs(context);
    }

    @Provides
    @Named(PLUGIN_PRIVILEGED)
    static List<String> providesPrivilegedPlugins(Context context) {
        return Arrays.asList(context.getResources().getStringArray(R.array.config_pluginAllowlist));
    }
}
