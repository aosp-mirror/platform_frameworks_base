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
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;

import com.android.internal.annotations.VisibleForTesting;

import dalvik.system.PathClassLoader;

import java.util.ArrayList;
import java.util.List;

public class PluginInstanceManager<T extends Plugin> extends BroadcastReceiver {

    private static final boolean DEBUG = false;

    private static final String TAG = "PluginInstanceManager";
    private static final String PLUGIN_PERMISSION = "com.android.systemui.permission.PLUGIN";

    private final Context mContext;
    private final PluginListener<T> mListener;
    private final String mAction;
    private final boolean mAllowMultiple;
    private final int mVersion;

    @VisibleForTesting
    final MainHandler mMainHandler;
    @VisibleForTesting
    final PluginHandler mPluginHandler;
    private final boolean isDebuggable;
    private final PackageManager mPm;
    private final ClassLoaderFactory mClassLoaderFactory;

    PluginInstanceManager(Context context, String action, PluginListener<T> listener,
            boolean allowMultiple, Looper looper, int version) {
        this(context, context.getPackageManager(), action, listener, allowMultiple, looper, version,
                Build.IS_DEBUGGABLE, new ClassLoaderFactory());
    }

    @VisibleForTesting
    PluginInstanceManager(Context context, PackageManager pm, String action,
            PluginListener<T> listener, boolean allowMultiple, Looper looper, int version,
            boolean debuggable, ClassLoaderFactory classLoaderFactory) {
        mMainHandler = new MainHandler(Looper.getMainLooper());
        mPluginHandler = new PluginHandler(looper);
        mContext = context;
        mPm = pm;
        mAction = action;
        mListener = listener;
        mAllowMultiple = allowMultiple;
        mVersion = version;
        isDebuggable = debuggable;
        mClassLoaderFactory = classLoaderFactory;
    }

    public void startListening() {
        if (DEBUG) Log.d(TAG, "startListening");
        mPluginHandler.sendEmptyMessage(PluginHandler.QUERY_ALL);
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addDataScheme("package");
        mContext.registerReceiver(this, filter);
        filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        mContext.registerReceiver(this, filter);
    }

    public void stopListening() {
        if (DEBUG) Log.d(TAG, "stopListening");
        ArrayList<PluginInfo> plugins = new ArrayList<>(mPluginHandler.mPlugins);
        for (PluginInfo plugin : plugins) {
            mMainHandler.obtainMessage(MainHandler.PLUGIN_DISCONNECTED,
                    plugin.mPlugin).sendToTarget();
        }
        mContext.unregisterReceiver(this);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) Log.d(TAG, "onReceive " + intent);
        if (Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            mPluginHandler.sendEmptyMessage(PluginHandler.QUERY_ALL);
        } else {
            Uri data = intent.getData();
            String pkgName = data.getEncodedSchemeSpecificPart();
            mPluginHandler.obtainMessage(PluginHandler.REMOVE_PKG, pkgName).sendToTarget();
            if (!Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                mPluginHandler.obtainMessage(PluginHandler.QUERY_PKG, pkgName).sendToTarget();
            }
        }
    }

    public boolean checkAndDisable(String className) {
        boolean disableAny = false;
        ArrayList<PluginInfo> plugins = new ArrayList<>(mPluginHandler.mPlugins);
        for (PluginInfo info : plugins) {
            if (className.startsWith(info.mPackage)) {
                disable(info);
                disableAny = true;
            }
        }
        return disableAny;
    }

    public void disableAll() {
        ArrayList<PluginInfo> plugins = new ArrayList<>(mPluginHandler.mPlugins);
        plugins.forEach(this::disable);
    }

    private void disable(PluginInfo info) {
        // Live by the sword, die by the sword.
        // Misbehaving plugins get disabled and won't come back until uninstall/reinstall.

        // If a plugin is detected in the stack of a crash then this will be called for that
        // plugin, if the plugin causing a crash cannot be identified, they are all disabled
        // assuming one of them must be bad.
        Log.w(TAG, "Disabling plugin " + info.mPackage + "/" + info.mClass);
        mPm.setComponentEnabledSetting(
                new ComponentName(info.mPackage, info.mClass),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private class MainHandler extends Handler {
        private static final int PLUGIN_CONNECTED = 1;
        private static final int PLUGIN_DISCONNECTED = 2;

        public MainHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case PLUGIN_CONNECTED:
                    if (DEBUG) Log.d(TAG, "onPluginConnected");
                    PluginInfo<T> info = (PluginInfo<T>) msg.obj;
                    info.mPlugin.onCreate(mContext, info.mPluginContext);
                    mListener.onPluginConnected(info.mPlugin);
                    break;
                case PLUGIN_DISCONNECTED:
                    if (DEBUG) Log.d(TAG, "onPluginDisconnected");
                    mListener.onPluginDisconnected((T) msg.obj);
                    ((T) msg.obj).onDestroy();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    static class ClassLoaderFactory {
        public ClassLoader createClassLoader(String path, ClassLoader base) {
            return new PathClassLoader(path, base);
        }
    }

    private class PluginHandler extends Handler {
        private static final int QUERY_ALL = 1;
        private static final int QUERY_PKG = 2;
        private static final int REMOVE_PKG = 3;

        private final ArrayList<PluginInfo<T>> mPlugins = new ArrayList<>();

        public PluginHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case QUERY_ALL:
                    if (DEBUG) Log.d(TAG, "queryAll " + mAction);
                    for (int i = mPlugins.size() - 1; i >= 0; i--) {
                        PluginInfo<T> plugin = mPlugins.get(i);
                        mListener.onPluginDisconnected(plugin.mPlugin);
                        plugin.mPlugin.onDestroy();
                    }
                    mPlugins.clear();
                    handleQueryPlugins(null);
                    break;
                case REMOVE_PKG:
                    String pkg = (String) msg.obj;
                    for (int i = mPlugins.size() - 1; i >= 0; i--) {
                        final PluginInfo<T> plugin = mPlugins.get(i);
                        if (plugin.mPackage.equals(pkg)) {
                            mMainHandler.obtainMessage(MainHandler.PLUGIN_DISCONNECTED,
                                    plugin.mPlugin).sendToTarget();
                            mPlugins.remove(i);
                        }
                    }
                    break;
                case QUERY_PKG:
                    String p = (String) msg.obj;
                    if (DEBUG) Log.d(TAG, "queryPkg " + p + " " + mAction);
                    if (mAllowMultiple || (mPlugins.size() == 0)) {
                        handleQueryPlugins(p);
                    } else {
                        if (DEBUG) Log.d(TAG, "Too many of " + mAction);
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }

        private void handleQueryPlugins(String pkgName) {
            // This isn't actually a service and shouldn't ever be started, but is
            // a convenient PM based way to manage our plugins.
            Intent intent = new Intent(mAction);
            if (pkgName != null) {
                intent.setPackage(pkgName);
            }
            List<ResolveInfo> result =
                    mPm.queryIntentServices(intent, 0);
            if (DEBUG) Log.d(TAG, "Found " + result.size() + " plugins");
            if (result.size() > 1 && !mAllowMultiple) {
                // TODO: Show warning.
                Log.w(TAG, "Multiple plugins found for " + mAction);
                return;
            }
            for (ResolveInfo info : result) {
                ComponentName name = new ComponentName(info.serviceInfo.packageName,
                        info.serviceInfo.name);
                PluginInfo<T> t = handleLoadPlugin(name);
                if (t == null) continue;
                mMainHandler.obtainMessage(mMainHandler.PLUGIN_CONNECTED, t).sendToTarget();
                mPlugins.add(t);
            }
        }

        protected PluginInfo<T> handleLoadPlugin(ComponentName component) {
            // This was already checked, but do it again here to make extra extra sure, we don't
            // use these on production builds.
            if (!isDebuggable) {
                // Never ever ever allow these on production builds, they are only for prototyping.
                Log.d(TAG, "Somehow hit second debuggable check");
                return null;
            }
            String pkg = component.getPackageName();
            String cls = component.getClassName();
            try {
                PackageManager pm = mPm;
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                // TODO: This probably isn't needed given that we don't have IGNORE_SECURITY on
                if (pm.checkPermission(PLUGIN_PERMISSION, pkg)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Plugin doesn't have permission: " + pkg);
                    return null;
                }
                // Create our own ClassLoader so we can use our own code as the parent.
                ClassLoader classLoader = mClassLoaderFactory.createClassLoader(info.sourceDir,
                        getClass().getClassLoader());
                Context pluginContext = new PluginContextWrapper(
                        mContext.createApplicationContext(info, 0), classLoader);
                Class<?> pluginClass = Class.forName(cls, true, classLoader);
                T plugin = (T) pluginClass.newInstance();
                if (plugin.getVersion() != mVersion) {
                    // TODO: Warn user.
                    Log.w(TAG, "Plugin has invalid interface version " + plugin.getVersion()
                            + ", expected " + mVersion);
                    return null;
                }
                if (DEBUG) Log.d(TAG, "createPlugin");
                return new PluginInfo(pkg, cls, plugin, pluginContext);
            } catch (Exception e) {
                Log.w(TAG, "Couldn't load plugin: " + pkg, e);
                return null;
            }
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

    private static class PluginInfo<T> {
        private final Context mPluginContext;
        private T mPlugin;
        private String mClass;
        private String mPackage;

        public PluginInfo(String pkg, String cls, T plugin, Context pluginContext) {
            mPlugin = plugin;
            mClass = cls;
            mPackage = pkg;
            mPluginContext = pluginContext;
        }
    }
}
