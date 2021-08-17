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

package com.android.systemui.shared.plugins;

import android.app.LoadedApk;
import android.app.Notification;
import android.app.Notification.Action;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.net.Uri;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.LayoutInflater;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginFragment;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.VersionInfo.InvalidVersionException;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

public class PluginInstanceManager<T extends Plugin> {

    private static final boolean DEBUG = false;

    private static final String TAG = "PluginInstanceManager";
    public static final String PLUGIN_PERMISSION = "com.android.systemui.permission.PLUGIN";

    private final Context mContext;
    private final PluginListener<T> mListener;
    private final String mAction;
    private final boolean mAllowMultiple;
    private final VersionInfo mVersion;
    private final NotificationManager mNotificationManager;
    private final PluginEnabler mPluginEnabler;
    private final InstanceFactory<T> mInstanceFactory;
    private final ArraySet<String> mPrivilegedPlugins = new ArraySet<>();
    private final Map<String, ClassLoader> mClassLoaders = new ArrayMap<>();

    @VisibleForTesting
    private final ArrayList<PluginInfo<T>> mPlugins = new ArrayList<>();
    private final boolean mIsDebuggable;
    private final PackageManager mPm;
    private final PluginInitializer mInitializer;
    private final Executor mMainExecutor;
    private final Executor mBgExecutor;

    private PluginManagerImpl.ClassLoaderFilter mParentClassLoader;

    private PluginInstanceManager(Context context, PackageManager pm, String action,
            PluginListener<T> listener, boolean allowMultiple, Executor mainExecutor,
            Executor bgExecutor, VersionInfo version, boolean debuggable,
            PluginInitializer initializer, NotificationManager notificationManager,
            PluginEnabler pluginEnabler, List<String> privilegedPlugins,
            InstanceFactory<T> instanceFactory) {
        mInitializer = initializer;
        mMainExecutor = mainExecutor;
        mBgExecutor = bgExecutor;
        mContext = context;
        mPm = pm;
        mAction = action;
        mListener = listener;
        mAllowMultiple = allowMultiple;
        mVersion = version;
        mNotificationManager = notificationManager;
        mPluginEnabler = pluginEnabler;
        mInstanceFactory = instanceFactory;
        mPrivilegedPlugins.addAll(privilegedPlugins);
        mIsDebuggable = debuggable;
    }

    public void loadAll() {
        if (DEBUG) Log.d(TAG, "startListening");
        mBgExecutor.execute(this::queryAll);
    }

    public void destroy() {
        if (DEBUG) Log.d(TAG, "stopListening");
        ArrayList<PluginInfo<T>> plugins = new ArrayList<>(mPlugins);
        for (PluginInfo<T> pluginInfo : plugins) {
            mMainExecutor.execute(() -> onPluginDisconnected(pluginInfo.mPlugin));
        }
    }

    public void onPackageRemoved(String pkg) {
        mBgExecutor.execute(() -> removePkg(pkg));
    }

    public void onPackageChange(String pkg) {
        mBgExecutor.execute(() -> removePkg(pkg));
        mBgExecutor.execute(() -> queryPkg(pkg));
    }

    public boolean checkAndDisable(String className) {
        boolean disableAny = false;
        ArrayList<PluginInfo<T>> plugins = new ArrayList<>(mPlugins);
        for (PluginInfo<T> info : plugins) {
            if (className.startsWith(info.mPackage)) {
                disableAny |= disable(info, PluginEnabler.DISABLED_FROM_EXPLICIT_CRASH);
            }
        }
        return disableAny;
    }

    public boolean disableAll() {
        ArrayList<PluginInfo<T>> plugins = new ArrayList<>(mPlugins);
        boolean disabledAny = false;
        for (int i = 0; i < plugins.size(); i++) {
            disabledAny |= disable(plugins.get(i), PluginEnabler.DISABLED_FROM_SYSTEM_CRASH);
        }
        return disabledAny;
    }

    private boolean isPluginPackagePrivileged(String packageName) {
        for (String componentNameOrPackage : mPrivilegedPlugins) {
            ComponentName componentName = ComponentName.unflattenFromString(componentNameOrPackage);
            if (componentName != null) {
                if (componentName.getPackageName().equals(packageName)) {
                    return true;
                }
            } else if (componentNameOrPackage.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPluginPrivileged(ComponentName pluginName) {
        for (String componentNameOrPackage : mPrivilegedPlugins) {
            ComponentName componentName = ComponentName.unflattenFromString(componentNameOrPackage);
            if (componentName == null) {
                if (componentNameOrPackage.equals(pluginName.getPackageName())) {
                    return true;
                }
            } else {
                if (componentName.equals(pluginName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean disable(PluginInfo<T> info, @PluginEnabler.DisableReason int reason) {
        // Live by the sword, die by the sword.
        // Misbehaving plugins get disabled and won't come back until uninstall/reinstall.

        ComponentName pluginComponent = new ComponentName(info.mPackage, info.mClass);
        // If a plugin is detected in the stack of a crash then this will be called for that
        // plugin, if the plugin causing a crash cannot be identified, they are all disabled
        // assuming one of them must be bad.
        if (isPluginPrivileged(pluginComponent)) {
            // Don't disable whitelisted plugins as they are a part of the OS.
            return false;
        }
        Log.w(TAG, "Disabling plugin " + pluginComponent.flattenToShortString());
        mPluginEnabler.setDisabled(pluginComponent, reason);

        return true;
    }

    <C> boolean dependsOn(Plugin p, Class<C> cls) {
        ArrayList<PluginInfo<T>> plugins = new ArrayList<>(mPlugins);
        for (PluginInfo<T> info : plugins) {
            if (info.mPlugin.getClass().getName().equals(p.getClass().getName())) {
                return info.mVersion != null && info.mVersion.hasClass(cls);
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return String.format("%s@%s (action=%s)",
                getClass().getSimpleName(), hashCode(), mAction);
    }

    private void onPluginConnected(PluginInfo<T> pluginInfo) {
        if (DEBUG) Log.d(TAG, "onPluginConnected");
        PluginPrefs.setHasPlugins(mContext);
        mInitializer.handleWtfs();
        if (!(pluginInfo.mPlugin instanceof PluginFragment)) {
            // Only call onCreate for plugins that aren't fragments, as fragments
            // will get the onCreate as part of the fragment lifecycle.
            pluginInfo.mPlugin.onCreate(mContext, pluginInfo.mPluginContext);
        }
        mListener.onPluginConnected(pluginInfo.mPlugin, pluginInfo.mPluginContext);
    }

    private void onPluginDisconnected(T plugin) {
        if (DEBUG) Log.d(TAG, "onPluginDisconnected");
        mListener.onPluginDisconnected(plugin);
        if (!(plugin instanceof PluginFragment)) {
            // Only call onDestroy for plugins that aren't fragments, as fragments
            // will get the onDestroy as part of the fragment lifecycle.
            plugin.onDestroy();
        }
    }

    private void queryAll() {
        if (DEBUG) Log.d(TAG, "queryAll " + mAction);
        for (int i = mPlugins.size() - 1; i >= 0; i--) {
            PluginInfo<T> pluginInfo = mPlugins.get(i);
            mMainExecutor.execute(() -> onPluginDisconnected(pluginInfo.mPlugin));
        }
        mPlugins.clear();
        handleQueryPlugins(null);
    }

    private void removePkg(String pkg) {
        for (int i = mPlugins.size() - 1; i >= 0; i--) {
            final PluginInfo<T> pluginInfo = mPlugins.get(i);
            if (pluginInfo.mPackage.equals(pkg)) {
                mMainExecutor.execute(() -> onPluginDisconnected(pluginInfo.mPlugin));
                mPlugins.remove(i);
            }
        }
    }

    private void queryPkg(String pkg) {
        if (DEBUG) Log.d(TAG, "queryPkg " + pkg + " " + mAction);
        if (mAllowMultiple || (mPlugins.size() == 0)) {
            handleQueryPlugins(pkg);
        } else {
            if (DEBUG) Log.d(TAG, "Too many of " + mAction);
        }
    }

    private void handleQueryPlugins(String pkgName) {
        // This isn't actually a service and shouldn't ever be started, but is
        // a convenient PM based way to manage our plugins.
        Intent intent = new Intent(mAction);
        if (pkgName != null) {
            intent.setPackage(pkgName);
        }
        List<ResolveInfo> result = mPm.queryIntentServices(intent, 0);
        if (DEBUG) Log.d(TAG, "Found " + result.size() + " plugins");
        if (result.size() > 1 && !mAllowMultiple) {
            // TODO: Show warning.
            Log.w(TAG, "Multiple plugins found for " + mAction);
            if (DEBUG) {
                for (ResolveInfo info : result) {
                    ComponentName name = new ComponentName(info.serviceInfo.packageName,
                            info.serviceInfo.name);
                    Log.w(TAG, "  " + name);
                }
            }
            return;
        }
        for (ResolveInfo info : result) {
            ComponentName name = new ComponentName(info.serviceInfo.packageName,
                    info.serviceInfo.name);
            PluginInfo<T> pluginInfo = handleLoadPlugin(name);
            if (pluginInfo == null) continue;

            // add plugin before sending PLUGIN_CONNECTED message
            mPlugins.add(pluginInfo);
            mMainExecutor.execute(() -> onPluginConnected(pluginInfo));
        }
    }

    protected PluginInfo<T> handleLoadPlugin(ComponentName component) {
        // This was already checked, but do it again here to make extra extra sure, we don't
        // use these on production builds.
        if (!mIsDebuggable && !isPluginPrivileged(component)) {
            // Never ever ever allow these on production builds, they are only for prototyping.
            Log.w(TAG, "Plugin cannot be loaded on production build: " + component);
            return null;
        }
        if (!mPluginEnabler.isEnabled(component)) {
            if (DEBUG) Log.d(TAG, "Plugin is not enabled, aborting load: " + component);
            return null;
        }
        String pkg = component.getPackageName();
        String cls = component.getClassName();
        try {
            ApplicationInfo info = mPm.getApplicationInfo(pkg, 0);
            // TODO: This probably isn't needed given that we don't have IGNORE_SECURITY on
            if (mPm.checkPermission(PLUGIN_PERMISSION, pkg)
                    != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Plugin doesn't have permission: " + pkg);
                return null;
            }
            // Create our own ClassLoader so we can use our own code as the parent.
            ClassLoader classLoader = getClassLoader(info);
            Context pluginContext = new PluginContextWrapper(
                    mContext.createApplicationContext(info, 0), classLoader);
            Class<?> pluginClass = Class.forName(cls, true, classLoader);
            // TODO: Only create the plugin before version check if we need it for
            // legacy version check.
            T plugin = mInstanceFactory.create(pluginClass);
            try {
                VersionInfo version = checkVersion(pluginClass, plugin, mVersion);
                if (DEBUG) Log.d(TAG, "createPlugin");
                return new PluginInfo<>(pkg, cls, plugin, pluginContext, version);
            } catch (InvalidVersionException e) {
                final int icon = Resources.getSystem().getIdentifier(
                        "stat_sys_warning", "drawable", "android");
                final int color = Resources.getSystem().getIdentifier(
                        "system_notification_accent_color", "color", "android");
                final Notification.Builder nb = new Notification.Builder(mContext,
                        PluginManager.NOTIFICATION_CHANNEL_ID)
                                .setStyle(new Notification.BigTextStyle())
                                .setSmallIcon(icon)
                                .setWhen(0)
                                .setShowWhen(false)
                                .setVisibility(Notification.VISIBILITY_PUBLIC)
                                .setColor(mContext.getColor(color));
                String label = cls;
                try {
                    label = mPm.getServiceInfo(component, 0).loadLabel(mPm).toString();
                } catch (NameNotFoundException e2) {
                }
                if (!e.isTooNew()) {
                    // Localization not required as this will never ever appear in a user build.
                    nb.setContentTitle("Plugin \"" + label + "\" is too old")
                            .setContentText("Contact plugin developer to get an updated"
                                    + " version.\n" + e.getMessage());
                } else {
                    // Localization not required as this will never ever appear in a user build.
                    nb.setContentTitle("Plugin \"" + label + "\" is too new")
                            .setContentText("Check to see if an OTA is available.\n"
                                    + e.getMessage());
                }
                Intent i = new Intent(PluginManagerImpl.DISABLE_PLUGIN).setData(
                        Uri.parse("package://" + component.flattenToString()));
                PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, i,
                        PendingIntent.FLAG_IMMUTABLE);
                nb.addAction(new Action.Builder(null, "Disable plugin", pi).build());
                mNotificationManager.notify(SystemMessage.NOTE_PLUGIN, nb.build());
                // TODO: Warn user.
                Log.w(TAG, "Plugin has invalid interface version " + plugin.getVersion()
                        + ", expected " + mVersion);
                return null;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Couldn't load plugin: " + pkg, e);
            return null;
        }
    }

    private VersionInfo checkVersion(Class<?> pluginClass, T plugin, VersionInfo version)
            throws InvalidVersionException {
        VersionInfo pv = new VersionInfo().addClass(pluginClass);
        if (pv.hasVersionInfo()) {
            version.checkVersion(pv);
        } else {
            int fallbackVersion = plugin.getVersion();
            if (fallbackVersion != version.getDefaultVersion()) {
                throw new InvalidVersionException("Invalid legacy version", false);
            }
            return null;
        }
        return pv;
    }

    /** Returns class loader specific for the given plugin. */
    public ClassLoader getClassLoader(ApplicationInfo appInfo) {
        if (!mIsDebuggable && !isPluginPackagePrivileged(appInfo.packageName)) {
            Log.w(TAG, "Cannot get class loader for non-privileged plugin. Src:"
                    + appInfo.sourceDir + ", pkg: " + appInfo.packageName);
            return null;
        }
        if (mClassLoaders.containsKey(appInfo.packageName)) {
            return mClassLoaders.get(appInfo.packageName);
        }

        List<String> zipPaths = new ArrayList<>();
        List<String> libPaths = new ArrayList<>();
        LoadedApk.makePaths(null, true, appInfo, zipPaths, libPaths);
        ClassLoader classLoader = new PathClassLoader(
                TextUtils.join(File.pathSeparator, zipPaths),
                TextUtils.join(File.pathSeparator, libPaths),
                getParentClassLoader());
        mClassLoaders.put(appInfo.packageName, classLoader);
        return classLoader;
    }

    private ClassLoader getParentClassLoader() {
        if (mParentClassLoader == null) {
            // Lazily load this so it doesn't have any effect on devices without plugins.
            mParentClassLoader = new PluginManagerImpl.ClassLoaderFilter(
                    getClass().getClassLoader(), "com.android.systemui.plugin");
        }
        return mParentClassLoader;
    }

    /**
     * Construct a {@link PluginInstanceManager}
     */
    public static class Factory {
        private final Context mContext;
        private final PackageManager mPackageManager;
        private final Executor mMainExecutor;
        private final Executor mBgExecutor;
        private final PluginInitializer mInitializer;
        private final NotificationManager mNotificationManager;
        private final PluginEnabler mPluginEnabler;
        private final List<String> mPrivilegedPlugins;
        private InstanceFactory<?> mInstanceFactory;

        public Factory(Context context, PackageManager packageManager,
                Executor mainExecutor, Executor bgExecutor, PluginInitializer initializer,
                NotificationManager notificationManager, PluginEnabler pluginEnabler,
                List<String> privilegedPlugins) {
            mContext = context;
            mPackageManager = packageManager;
            mMainExecutor = mainExecutor;
            mBgExecutor = bgExecutor;
            mInitializer = initializer;
            mNotificationManager = notificationManager;
            mPluginEnabler = pluginEnabler;
            mPrivilegedPlugins = privilegedPlugins;

            mInstanceFactory = new InstanceFactory<>();
        }

        @VisibleForTesting
        <T extends Plugin> Factory setInstanceFactory(InstanceFactory<T> instanceFactory) {
            mInstanceFactory = instanceFactory;
            return this;
        }

        <T extends Plugin> PluginInstanceManager<T> create(
                String action, PluginListener<T> listener, boolean allowMultiple,
                VersionInfo version, boolean debuggable) {
            return new PluginInstanceManager<T>(mContext, mPackageManager, action, listener,
                    allowMultiple, mMainExecutor, mBgExecutor, version, debuggable,
                    mInitializer, mNotificationManager, mPluginEnabler,
                    mPrivilegedPlugins, (InstanceFactory<T>) mInstanceFactory);
        }
    }

    public static class PluginContextWrapper extends ContextWrapper {
        private final ClassLoader mClassLoader;
        private LayoutInflater mInflater;

        public PluginContextWrapper(Context base, ClassLoader classLoader) {
            super(base);
            mClassLoader = classLoader;
        }

        @Override
        public ClassLoader getClassLoader() {
            return mClassLoader;
        }

        @Override
        public Object getSystemService(String name) {
            if (LAYOUT_INFLATER_SERVICE.equals(name)) {
                if (mInflater == null) {
                    mInflater = LayoutInflater.from(getBaseContext()).cloneInContext(this);
                }
                return mInflater;
            }
            return getBaseContext().getSystemService(name);
        }
    }

    static class PluginInfo<T extends Plugin> {
        private final Context mPluginContext;
        private final VersionInfo mVersion;
        private final String mClass;
        T mPlugin;
        String mPackage;

        public PluginInfo(String pkg, String cls, T plugin, Context pluginContext,
                VersionInfo info) {
            mPlugin = plugin;
            mClass = cls;
            mPackage = pkg;
            mPluginContext = pluginContext;
            mVersion = info;
        }
    }

    static class InstanceFactory<T extends Plugin> {
        T create(Class cls) throws IllegalAccessException, InstantiationException {
            return (T) cls.newInstance();
        }
    }
}
