/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Build;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.ArrayMap;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.plugins.PluginInstanceManager.PluginContextWrapper;

import dalvik.system.PathClassLoader;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;

/**
 * @see Plugin
 */
public class PluginManager extends BroadcastReceiver {

    public static final String PLUGIN_CHANGED = "com.android.systemui.action.PLUGIN_CHANGED";

    private static PluginManager sInstance;

    private final HandlerThread mBackgroundThread;
    private final ArrayMap<PluginListener<?>, PluginInstanceManager> mPluginMap
            = new ArrayMap<>();
    private final Map<String, ClassLoader> mClassLoaders = new ArrayMap<>();
    private final Context mContext;
    private final PluginInstanceManagerFactory mFactory;
    private final boolean isDebuggable;
    private final PluginPrefs mPluginPrefs;
    private ClassLoaderFilter mParentClassLoader;

    private PluginManager(Context context) {
        this(context, new PluginInstanceManagerFactory(),
                Build.IS_DEBUGGABLE, Thread.getDefaultUncaughtExceptionHandler());
    }

    @VisibleForTesting
    PluginManager(Context context, PluginInstanceManagerFactory factory, boolean debuggable,
            UncaughtExceptionHandler defaultHandler) {
        mContext = context;
        mFactory = factory;
        mBackgroundThread = new HandlerThread("Plugins");
        mBackgroundThread.start();
        isDebuggable = debuggable;
        mPluginPrefs = new PluginPrefs(mContext);

        PluginExceptionHandler uncaughtExceptionHandler = new PluginExceptionHandler(
                defaultHandler);
        Thread.setDefaultUncaughtExceptionHandler(uncaughtExceptionHandler);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            int version) {
        addPluginListener(action, listener, version, false);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            int version, boolean allowMultiple) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return;
        }
        mPluginPrefs.addAction(action);
        PluginInstanceManager p = mFactory.createPluginInstanceManager(mContext, action, listener,
                allowMultiple, mBackgroundThread.getLooper(), version, this);
        p.loadAll();
        mPluginMap.put(listener, p);
        if (mPluginMap.size() == 1) {
            startListening();
        }
    }

    public void removePluginListener(PluginListener<?> listener) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return;
        }
        if (!mPluginMap.containsKey(listener)) return;
        mPluginMap.remove(listener).destroy();
        if (mPluginMap.size() == 0) {
            stopListening();
        }
    }

    private void startListening() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(PLUGIN_CHANGED);
        filter.addDataScheme("package");
        mContext.registerReceiver(this, filter);
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiver(this, filter);
    }

    private void stopListening() {
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            for (PluginInstanceManager manager : mPluginMap.values()) {
                manager.loadAll();
            }
        } else {
            Uri data = intent.getData();
            String pkg = data.getEncodedSchemeSpecificPart();
            clearClassLoader(pkg);
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    manager.onPackageChange(pkg);
                }
            } else {
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    manager.onPackageRemoved(pkg);
                }
            }
        }
    }

    public ClassLoader getClassLoader(String sourceDir, String pkg) {
        if (mClassLoaders.containsKey(pkg)) {
            return mClassLoaders.get(pkg);
        }
        ClassLoader classLoader = new PathClassLoader(sourceDir, getParentClassLoader());
        mClassLoaders.put(pkg, classLoader);
        return classLoader;
    }

    private void clearClassLoader(String pkg) {
        mClassLoaders.remove(pkg);
    }

    ClassLoader getParentClassLoader() {
        if (mParentClassLoader == null) {
            // Lazily load this so it doesn't have any effect on devices without plugins.
            mParentClassLoader = new ClassLoaderFilter(getClass().getClassLoader(),
                    "com.android.systemui.plugin");
        }
        return mParentClassLoader;
    }

    public Context getAllPluginContext(Context context) {
        return new PluginContextWrapper(context,
                new AllPluginClassLoader(context.getClassLoader()));
    }

    public Context getContext(ApplicationInfo info, String pkg) throws NameNotFoundException {
        ClassLoader classLoader = getClassLoader(info.sourceDir, pkg);
        return new PluginContextWrapper(mContext.createApplicationContext(info, 0), classLoader);
    }

    public static PluginManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new PluginManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private class AllPluginClassLoader extends ClassLoader {
        public AllPluginClassLoader(ClassLoader classLoader) {
            super(classLoader);
        }

        @Override
        public Class<?> loadClass(String s) throws ClassNotFoundException {
            try {
                return super.loadClass(s);
            } catch (ClassNotFoundException e) {
                for (ClassLoader classLoader : mClassLoaders.values()) {
                    try {
                        return classLoader.loadClass(s);
                    } catch (ClassNotFoundException e1) {
                        // Will re-throw e if all fail.
                    }
                }
                throw e;
            }
        }
    }

    @VisibleForTesting
    public static class PluginInstanceManagerFactory {
        public <T extends Plugin> PluginInstanceManager createPluginInstanceManager(Context context,
                String action, PluginListener<T> listener, boolean allowMultiple, Looper looper,
                int version, PluginManager manager) {
            return new PluginInstanceManager(context, action, listener, allowMultiple, looper,
                    version, manager);
        }
    }

    // This allows plugins to include any libraries or copied code they want by only including
    // classes from the plugin library.
    private static class ClassLoaderFilter extends ClassLoader {
        private final String mPackage;
        private final ClassLoader mBase;

        public ClassLoaderFilter(ClassLoader base, String pkg) {
            super(ClassLoader.getSystemClassLoader());
            mBase = base;
            mPackage = pkg;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith(mPackage)) super.loadClass(name, resolve);
            return mBase.loadClass(name);
        }
    }

    private class PluginExceptionHandler implements UncaughtExceptionHandler {
        private final UncaughtExceptionHandler mHandler;

        private PluginExceptionHandler(UncaughtExceptionHandler handler) {
            mHandler = handler;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            if (SystemProperties.getBoolean("plugin.debugging", false)) {
                mHandler.uncaughtException(thread, throwable);
                return;
            }
            // Search for and disable plugins that may have been involved in this crash.
            boolean disabledAny = checkStack(throwable);
            if (!disabledAny) {
                // We couldn't find any plugins involved in this crash, just to be safe
                // disable all the plugins, so we can be sure that SysUI is running as
                // best as possible.
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    manager.disableAll();
                }
            }

            // Run the normal exception handler so we can crash and cleanup our state.
            mHandler.uncaughtException(thread, throwable);
        }

        private boolean checkStack(Throwable throwable) {
            if (throwable == null) return false;
            boolean disabledAny = false;
            for (StackTraceElement element : throwable.getStackTrace()) {
                for (PluginInstanceManager manager : mPluginMap.values()) {
                    disabledAny |= manager.checkAndDisable(element.getClassName());
                }
            }
            return disabledAny | checkStack(throwable.getCause());
        }
    }
}
