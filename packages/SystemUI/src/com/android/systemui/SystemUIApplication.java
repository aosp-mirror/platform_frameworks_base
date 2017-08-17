/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Process;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.TimingsTraceLog;
import android.util.Log;

import com.android.systemui.globalactions.GlobalActionsComponent;
import com.android.systemui.keyboard.KeyboardUI;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.media.RingtonePlayer;
import com.android.systemui.pip.PipUI;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.OverlayPlugin;
import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.PluginManager;
import com.android.systemui.power.PowerUI;
import com.android.systemui.recents.Recents;
import com.android.systemui.shortcut.ShortcutKeyDispatcher;
import com.android.systemui.stackdivider.Divider;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarWindowManager;
import com.android.systemui.usb.StorageNotification;
import com.android.systemui.util.NotificationChannels;
import com.android.systemui.util.leak.GarbageMonitor;
import com.android.systemui.volume.VolumeUI;

import java.util.HashMap;
import java.util.Map;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application implements SysUiServiceProvider {

    private static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    /**
     * The classes of the stuff to start.
     */
    private final Class<?>[] SERVICES = new Class[] {
            Dependency.class,
            NotificationChannels.class,
            CommandQueue.CommandQueueStart.class,
            KeyguardViewMediator.class,
            Recents.class,
            VolumeUI.class,
            Divider.class,
            SystemBars.class,
            StorageNotification.class,
            PowerUI.class,
            RingtonePlayer.class,
            KeyboardUI.class,
            PipUI.class,
            ShortcutKeyDispatcher.class,
            VendorServices.class,
            GarbageMonitor.Service.class,
            LatencyTester.class,
            GlobalActionsComponent.class,
            RoundedCorners.class,
    };

    /**
     * The classes of the stuff to start for each user.  This is a subset of the services listed
     * above.
     */
    private final Class<?>[] SERVICES_PER_USER = new Class[] {
            Dependency.class,
            NotificationChannels.class,
            Recents.class
    };

    /**
     * Hold a reference on the stuff we start.
     */
    private final SystemUI[] mServices = new SystemUI[SERVICES.length];
    private boolean mServicesStarted;
    private boolean mBootCompleted;
    private final Map<Class<?>, Object> mComponents = new HashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.Theme_SystemUI);

        SystemUIFactory.createFromConfig(this);

        if (Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_BOOT_COMPLETED);
            filter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mBootCompleted) return;

                    if (DEBUG) Log.v(TAG, "BOOT_COMPLETED received");
                    unregisterReceiver(this);
                    mBootCompleted = true;
                    if (mServicesStarted) {
                        final int N = mServices.length;
                        for (int i = 0; i < N; i++) {
                            mServices[i].onBootCompleted();
                        }
                    }
                }
            }, filter);
        } else {
            // We don't need to startServices for sub-process that is doing some tasks.
            // (screenshots, sweetsweetdesserts or tuner ..)
            String processName = ActivityThread.currentProcessName();
            ApplicationInfo info = getApplicationInfo();
            if (processName != null && processName.startsWith(info.processName + ":")) {
                return;
            }
            // For a secondary user, boot-completed will never be called because it has already
            // been broadcasted on startup for the primary SystemUI process.  Instead, for
            // components which require the SystemUI component to be initialized per-user, we
            // start those components now for the current non-system user.
            startServicesIfNeeded(SERVICES_PER_USER);
        }
    }

    /**
     * Makes sure that all the SystemUI services are running. If they are already running, this is a
     * no-op. This is needed to conditinally start all the services, as we only need to have it in
     * the main process.
     * <p>This method must only be called from the main thread.</p>
     */

    public void startServicesIfNeeded() {
        startServicesIfNeeded(SERVICES);
    }

    /**
     * Ensures that all the Secondary user SystemUI services are running. If they are already
     * running, this is a no-op. This is needed to conditinally start all the services, as we only
     * need to have it in the main process.
     * <p>This method must only be called from the main thread.</p>
     */
    void startSecondaryUserServicesIfNeeded() {
        startServicesIfNeeded(SERVICES_PER_USER);
    }

    private void startServicesIfNeeded(Class<?>[] services) {
        if (mServicesStarted) {
            return;
        }

        if (!mBootCompleted) {
            // check to see if maybe it was already completed long before we began
            // see ActivityManagerService.finishBooting()
            if ("1".equals(SystemProperties.get("sys.boot_completed"))) {
                mBootCompleted = true;
                if (DEBUG) Log.v(TAG, "BOOT_COMPLETED was already sent");
            }
        }

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin("StartServices");
        final int N = services.length;
        for (int i = 0; i < N; i++) {
            Class<?> cl = services[i];
            if (DEBUG) Log.d(TAG, "loading: " + cl);
            log.traceBegin("StartServices" + cl.getSimpleName());
            long ti = System.currentTimeMillis();
            try {

                Object newService = SystemUIFactory.getInstance().createInstance(cl);
                mServices[i] = (SystemUI) ((newService == null) ? cl.newInstance() : newService);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            } catch (InstantiationException ex) {
                throw new RuntimeException(ex);
            }

            mServices[i].mContext = this;
            mServices[i].mComponents = mComponents;
            if (DEBUG) Log.d(TAG, "running: " + mServices[i]);
            mServices[i].start();
            log.traceEnd();

            // Warn if initialization of component takes too long
            ti = System.currentTimeMillis() - ti;
            if (ti > 1000) {
                Log.w(TAG, "Initialization of " + cl.getName() + " took " + ti + " ms");
            }
            if (mBootCompleted) {
                mServices[i].onBootCompleted();
            }
        }
        log.traceEnd();
        Dependency.get(PluginManager.class).addPluginListener(
                new PluginListener<OverlayPlugin>() {
                    private ArraySet<OverlayPlugin> mOverlays;

                    @Override
                    public void onPluginConnected(OverlayPlugin plugin, Context pluginContext) {
                        StatusBar statusBar = getComponent(StatusBar.class);
                        if (statusBar != null) {
                            plugin.setup(statusBar.getStatusBarWindow(),
                                    statusBar.getNavigationBarView());
                        }
                        // Lazy init.
                        if (mOverlays == null) mOverlays = new ArraySet<>();
                        if (plugin.holdStatusBarOpen()) {
                            mOverlays.add(plugin);
                            Dependency.get(StatusBarWindowManager.class).setStateListener(b ->
                                    mOverlays.forEach(o -> o.setCollapseDesired(b)));
                            Dependency.get(StatusBarWindowManager.class).setForcePluginOpen(
                                    mOverlays.size() != 0);

                        }
                    }

                    @Override
                    public void onPluginDisconnected(OverlayPlugin plugin) {
                        mOverlays.remove(plugin);
                        Dependency.get(StatusBarWindowManager.class).setForcePluginOpen(
                                mOverlays.size() != 0);
                    }
                }, OverlayPlugin.class, true /* Allow multiple plugins */);

        mServicesStarted = true;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mServicesStarted) {
            int len = mServices.length;
            for (int i = 0; i < len; i++) {
                if (mServices[i] != null) {
                    mServices[i].onConfigurationChanged(newConfig);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getComponent(Class<T> interfaceType) {
        return (T) mComponents.get(interfaceType);
    }

    public SystemUI[] getServices() {
        return mServices;
    }
}
