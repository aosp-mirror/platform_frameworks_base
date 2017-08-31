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

import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Log.TerribleFailure;
import android.util.Log.TerribleFailureHandler;
import android.widget.Toast;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.PluginInstanceManager.PluginContextWrapper;
import com.android.systemui.plugins.PluginInstanceManager.PluginInfo;
import com.android.systemui.plugins.annotations.ProvidesInterface;

import dalvik.system.PathClassLoader;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;

/**
 * @see Plugin
 */
public class PluginManagerImpl extends BroadcastReceiver implements PluginManager {

    static final String DISABLE_PLUGIN = "com.android.systemui.action.DISABLE_PLUGIN";

    private static PluginManager sInstance;

    private final ArrayMap<PluginListener<?>, PluginInstanceManager> mPluginMap
            = new ArrayMap<>();
    private final Map<String, ClassLoader> mClassLoaders = new ArrayMap<>();
    private final ArraySet<String> mOneShotPackages = new ArraySet<>();
    private final Context mContext;
    private final PluginInstanceManagerFactory mFactory;
    private final boolean isDebuggable;
    private final PluginPrefs mPluginPrefs;
    private ClassLoaderFilter mParentClassLoader;
    private boolean mListening;
    private boolean mHasOneShot;
    private Looper mLooper;
    private boolean mWtfsSet;

    public PluginManagerImpl(Context context) {
        this(context, new PluginInstanceManagerFactory(),
                Build.IS_DEBUGGABLE, Thread.getUncaughtExceptionPreHandler());
    }

    @VisibleForTesting
    PluginManagerImpl(Context context, PluginInstanceManagerFactory factory, boolean debuggable,
            UncaughtExceptionHandler defaultHandler) {
        mContext = context;
        mFactory = factory;
        mLooper = Dependency.get(Dependency.BG_LOOPER);
        isDebuggable = debuggable;
        mPluginPrefs = new PluginPrefs(mContext);

        PluginExceptionHandler uncaughtExceptionHandler = new PluginExceptionHandler(
                defaultHandler);
        Thread.setUncaughtExceptionPreHandler(uncaughtExceptionHandler);
        if (isDebuggable) {
            new Handler(mLooper).post(() -> {
                // Plugin dependencies that don't have another good home can go here, but
                // dependencies that have better places to init can happen elsewhere.
                Dependency.get(PluginDependencyProvider.class)
                        .allowPluginDependency(ActivityStarter.class);
            });
        }
    }

    public <T extends Plugin> T getOneShotPlugin(Class<T> cls) {
        ProvidesInterface info = cls.getDeclaredAnnotation(ProvidesInterface.class);
        if (info == null) {
            throw new RuntimeException(cls + " doesn't provide an interface");
        }
        if (TextUtils.isEmpty(info.action())) {
            throw new RuntimeException(cls + " doesn't provide an action");
        }
        return getOneShotPlugin(info.action(), cls);
    }

    public <T extends Plugin> T getOneShotPlugin(String action, Class<?> cls) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return null;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new RuntimeException("Must be called from UI thread");
        }
        PluginInstanceManager<T> p = mFactory.createPluginInstanceManager(mContext, action, null,
                false, mLooper, cls, this);
        mPluginPrefs.addAction(action);
        PluginInfo<T> info = p.getPlugin();
        if (info != null) {
            mOneShotPackages.add(info.mPackage);
            mHasOneShot = true;
            startListening();
            return info.mPlugin;
        }
        return null;
    }

    public <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<?> cls) {
        addPluginListener(listener, cls, false);
    }

    public <T extends Plugin> void addPluginListener(PluginListener<T> listener, Class<?> cls,
            boolean allowMultiple) {
        addPluginListener(PluginManager.getAction(cls), listener, cls, allowMultiple);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            Class<?> cls) {
        addPluginListener(action, listener, cls, false);
    }

    public <T extends Plugin> void addPluginListener(String action, PluginListener<T> listener,
            Class cls, boolean allowMultiple) {
        if (!isDebuggable) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            return;
        }
        mPluginPrefs.addAction(action);
        PluginInstanceManager p = mFactory.createPluginInstanceManager(mContext, action, listener,
                allowMultiple, mLooper, cls, this);
        p.loadAll();
        mPluginMap.put(listener, p);
        startListening();
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
        if (mListening) return;
        mListening = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(PLUGIN_CHANGED);
        filter.addAction(DISABLE_PLUGIN);
        filter.addDataScheme("package");
        mContext.registerReceiver(this, filter);
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiver(this, filter);
    }

    private void stopListening() {
        // Never stop listening if a one-shot is present.
        if (!mListening || mHasOneShot) return;
        mListening = false;
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            for (PluginInstanceManager manager : mPluginMap.values()) {
                manager.loadAll();
            }
        } else if (DISABLE_PLUGIN.equals(intent.getAction())) {
            Uri uri = intent.getData();
            ComponentName component = ComponentName.unflattenFromString(
                    uri.toString().substring(10));
            mContext.getPackageManager().setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            mContext.getSystemService(NotificationManager.class).cancel(component.getClassName(),
                    SystemMessage.NOTE_PLUGIN);
        } else {
            Uri data = intent.getData();
            String pkg = data.getEncodedSchemeSpecificPart();
            if (mOneShotPackages.contains(pkg)) {
                int icon = mContext.getResources().getIdentifier("tuner", "drawable",
                        mContext.getPackageName());
                int color = Resources.getSystem().getIdentifier(
                        "system_notification_accent_color", "color", "android");
                String label = pkg;
                try {
                    PackageManager pm = mContext.getPackageManager();
                    label = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
                } catch (NameNotFoundException e) {
                }
                // Localization not required as this will never ever appear in a user build.
                final Notification.Builder nb =
                        new Notification.Builder(mContext, NOTIFICATION_CHANNEL_ID)
                                .setSmallIcon(icon)
                                .setWhen(0)
                                .setShowWhen(false)
                                .setPriority(Notification.PRIORITY_MAX)
                                .setVisibility(Notification.VISIBILITY_PUBLIC)
                                .setColor(mContext.getColor(color))
                                .setContentTitle("Plugin \"" + label + "\" has updated")
                                .setContentText("Restart SysUI for changes to take effect.");
                Intent i = new Intent("com.android.systemui.action.RESTART").setData(
                            Uri.parse("package://" + pkg));
                PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i, 0);
                nb.addAction(new Action.Builder(null, "Restart SysUI", pi).build());
                mContext.getSystemService(NotificationManager.class).notifyAsUser(pkg,
                        SystemMessage.NOTE_PLUGIN, nb.build(), UserHandle.ALL);
            }
            if (clearClassLoader(pkg)) {
                Toast.makeText(mContext, "Reloading " + pkg, Toast.LENGTH_LONG).show();
            }
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

    private boolean clearClassLoader(String pkg) {
        return mClassLoaders.remove(pkg) != null;
    }

    ClassLoader getParentClassLoader() {
        if (mParentClassLoader == null) {
            // Lazily load this so it doesn't have any effect on devices without plugins.
            mParentClassLoader = new ClassLoaderFilter(getClass().getClassLoader(),
                    "com.android.systemui.plugin");
        }
        return mParentClassLoader;
    }

    public Context getContext(ApplicationInfo info, String pkg) throws NameNotFoundException {
        ClassLoader classLoader = getClassLoader(info.sourceDir, pkg);
        return new PluginContextWrapper(mContext.createApplicationContext(info, 0), classLoader);
    }

    public <T> boolean dependsOn(Plugin p, Class<T> cls) {
        for (int i = 0; i < mPluginMap.size(); i++) {
            if (mPluginMap.valueAt(i).dependsOn(p, cls)) {
                return true;
            }
        }
        return false;
    }

    public void handleWtfs() {
        if (!mWtfsSet) {
            mWtfsSet = true;
            Log.setWtfHandler((tag, what, system) -> {
                throw new CrashWhilePluginActiveException(what);
            });
        }
    }

    @VisibleForTesting
    public static class PluginInstanceManagerFactory {
        public <T extends Plugin> PluginInstanceManager createPluginInstanceManager(Context context,
                String action, PluginListener<T> listener, boolean allowMultiple, Looper looper,
                Class<?> cls, PluginManagerImpl manager) {
            return new PluginInstanceManager(context, action, listener, allowMultiple, looper,
                    new VersionInfo().addClass(cls), manager);
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
                    disabledAny |= manager.disableAll();
                }
            }
            if (disabledAny) {
                throwable = new CrashWhilePluginActiveException(throwable);
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

    private class CrashWhilePluginActiveException extends RuntimeException {
        public CrashWhilePluginActiveException(Throwable throwable) {
            super(throwable);
        }
    }
}
