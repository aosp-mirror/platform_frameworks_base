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

import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Dumpable;
import android.util.DumpableContainer;
import android.util.Log;
import android.util.TimingsTraceLog;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.View;

import com.android.internal.protolog.common.ProtoLog;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.NotificationChannels;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

import javax.inject.Provider;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application implements
        SystemUIAppComponentFactory.ContextInitializer, DumpableContainer {

    public static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    private BootCompleteCacheImpl mBootCompleteCache;
    private DumpManager mDumpManager;

    /**
     * Map of dumpables added externally.
     */
    private final ArrayMap<String, Dumpable> mDumpables = new ArrayMap<>();

    /**
     * Hold a reference on the stuff we start.
     */
    private CoreStartable[] mServices;
    private boolean mServicesStarted;
    private SystemUIAppComponentFactory.ContextAvailableCallback mContextAvailableCallback;
    private SysUIComponent mSysUIComponent;
    private SystemUIInitializer mInitializer;

    public SystemUIApplication() {
        super();
        Log.v(TAG, "SystemUIApplication constructed.");
        // SysUI may be building without protolog preprocessing in some cases
        ProtoLog.REQUIRE_PROTOLOGTOOL = false;
    }

    protected GlobalRootComponent getRootComponent() {
        return mInitializer.getRootComponent();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.v(TAG, "SystemUIApplication created.");
        // This line is used to setup Dagger's dependency injection and should be kept at the
        // top of this method.
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin("DependencyInjection");
        mInitializer = mContextAvailableCallback.onContextAvailable(this);
        mSysUIComponent = mInitializer.getSysUIComponent();
        mBootCompleteCache = mSysUIComponent.provideBootCacheImpl();
        log.traceEnd();

        // Enable Looper trace points.
        // This allows us to see Handler callbacks on traces.
        Looper.getMainLooper().setTraceTag(Trace.TRACE_TAG_APP);

        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.Theme_SystemUI);

        View.setTraceLayoutSteps(
                SystemProperties.getBoolean("persist.debug.trace_layouts", false));
        View.setTracedRequestLayoutClassClass(
                SystemProperties.get("persist.debug.trace_request_layout_class", null));

        if (Process.myUserHandle().equals(UserHandle.SYSTEM)) {
            IntentFilter bootCompletedFilter = new
                    IntentFilter(Intent.ACTION_LOCKED_BOOT_COMPLETED);
            bootCompletedFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);

            // If SF GPU context priority is set to realtime, then SysUI should run at high.
            // The priority is defaulted at medium.
            int sfPriority = SurfaceControl.getGPUContextPriority();
            Log.i(TAG, "Found SurfaceFlinger's GPU Priority: " + sfPriority);
            if (sfPriority == ThreadedRenderer.EGL_CONTEXT_PRIORITY_REALTIME_NV) {
                Log.i(TAG, "Setting SysUI's GPU Context priority to: "
                        + ThreadedRenderer.EGL_CONTEXT_PRIORITY_HIGH_IMG);
                ThreadedRenderer.setContextPriority(
                        ThreadedRenderer.EGL_CONTEXT_PRIORITY_HIGH_IMG);
            }

            // Enable binder tracing on system server for calls originating from SysUI
            try {
                ActivityManager.getService().enableBinderTracing();
            } catch (RemoteException e) {
                Log.e(TAG, "Unable to enable binder tracing", e);
            }

            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (mBootCompleteCache.isBootComplete()) return;

                    if (DEBUG) Log.v(TAG, "BOOT_COMPLETED received");
                    unregisterReceiver(this);
                    mBootCompleteCache.setBootComplete();
                    if (mServicesStarted) {
                        final int N = mServices.length;
                        for (int i = 0; i < N; i++) {
                            notifyBootCompleted(mServices[i]);
                        }
                    }
                }
            }, bootCompletedFilter);

            IntentFilter localeChangedFilter = new IntentFilter(Intent.ACTION_LOCALE_CHANGED);
            registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (Intent.ACTION_LOCALE_CHANGED.equals(intent.getAction())) {
                        if (!mBootCompleteCache.isBootComplete()) return;
                        // Update names of SystemUi notification channels
                        NotificationChannels.createAll(context);
                    }
                }
            }, localeChangedFilter);
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
            startSecondaryUserServicesIfNeeded();
        }
    }

    /**
     * Makes sure that all the SystemUI services are running. If they are already running, this is a
     * no-op. This is needed to conditinally start all the services, as we only need to have it in
     * the main process.
     * <p>This method must only be called from the main thread.</p>
     */

    public void startServicesIfNeeded() {
        final String vendorComponent = mInitializer.getVendorComponent(getResources());

        // Sort the startables so that we get a deterministic ordering.
        // TODO: make #start idempotent and require users of CoreStartable to call it.
        Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
                Comparator.comparing(Class::getName));
        sortedStartables.putAll(mSysUIComponent.getStartables());
        sortedStartables.putAll(mSysUIComponent.getPerUserStartables());
        startServicesIfNeeded(
                sortedStartables, "StartServices", vendorComponent);
    }

    /**
     * Ensures that all the Secondary user SystemUI services are running. If they are already
     * running, this is a no-op. This is needed to conditionally start all the services, as we only
     * need to have it in the main process.
     * <p>This method must only be called from the main thread.</p>
     */
    void startSecondaryUserServicesIfNeeded() {
        // Sort the startables so that we get a deterministic ordering.
        Map<Class<?>, Provider<CoreStartable>> sortedStartables = new TreeMap<>(
                Comparator.comparing(Class::getName));
        sortedStartables.putAll(mSysUIComponent.getPerUserStartables());
        startServicesIfNeeded(
                sortedStartables, "StartSecondaryServices", null);
    }

    private void startServicesIfNeeded(
            Map<Class<?>, Provider<CoreStartable>> startables,
            String metricsPrefix,
            String vendorComponent) {
        if (mServicesStarted) {
            return;
        }
        mServices = new CoreStartable[startables.size() + (vendorComponent == null ? 0 : 1)];

        if (!mBootCompleteCache.isBootComplete()) {
            // check to see if maybe it was already completed long before we began
            // see ActivityManagerService.finishBooting()
            if ("1".equals(SystemProperties.get("sys.boot_completed"))) {
                mBootCompleteCache.setBootComplete();
                if (DEBUG) {
                    Log.v(TAG, "BOOT_COMPLETED was already sent");
                }
            }
        }

        mDumpManager = mSysUIComponent.createDumpManager();

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin(metricsPrefix);

        int i = 0;
        for (Map.Entry<Class<?>, Provider<CoreStartable>> entry : startables.entrySet()) {
            String clsName = entry.getKey().getName();
            int j = i;  // Copied to make lambda happy.
            timeInitialization(
                    clsName,
                    () -> mServices[j] = startStartable(clsName, entry.getValue()),
                    log,
                    metricsPrefix);
            i++;
        }

        if (vendorComponent != null) {
            timeInitialization(
                    vendorComponent,
                    () -> mServices[mServices.length - 1] =
                            startAdditionalStartable(vendorComponent),
                    log,
                    metricsPrefix);
        }

        for (i = 0; i < mServices.length; i++) {
            if (mBootCompleteCache.isBootComplete()) {
                notifyBootCompleted(mServices[i]);
            }

            mDumpManager.registerDumpable(mServices[i].getClass().getName(), mServices[i]);
        }
        mSysUIComponent.getInitController().executePostInitTasks();
        log.traceEnd();

        mServicesStarted = true;
    }

    private static void notifyBootCompleted(CoreStartable coreStartable) {
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP,
                    coreStartable.getClass().getSimpleName() + ".onBootCompleted()");
        }
        coreStartable.onBootCompleted();
        Trace.endSection();
    }

    private static void timeInitialization(String clsName, Runnable init, TimingsTraceLog log,
            String metricsPrefix) {
        long ti = System.currentTimeMillis();
        log.traceBegin(metricsPrefix + " " + clsName);
        init.run();
        log.traceEnd();

        // Warn if initialization of component takes too long
        ti = System.currentTimeMillis() - ti;
        if (ti > 1000) {
            Log.w(TAG, "Initialization of " + clsName + " took " + ti + " ms");
        }
    }

    private static CoreStartable startAdditionalStartable(String clsName) {
        CoreStartable startable;
        if (DEBUG) Log.d(TAG, "loading: " + clsName);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, clsName + ".newInstance()");
        }
        try {
            startable = (CoreStartable) Class.forName(clsName).newInstance();
        } catch (ClassNotFoundException
                | IllegalAccessException
                | InstantiationException ex) {
            throw new RuntimeException(ex);
        } finally {
            Trace.endSection();
        }

        return startStartable(startable);
    }

    private static CoreStartable startStartable(String clsName, Provider<CoreStartable> provider) {
        if (DEBUG) Log.d(TAG, "loading: " + clsName);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, "Provider<" + clsName + ">.get()");
        }
        CoreStartable startable = provider.get();
        Trace.endSection();
        return startStartable(startable);
    }

    private static CoreStartable startStartable(CoreStartable startable) {
        if (DEBUG) Log.d(TAG, "running: " + startable);
        if (Trace.isEnabled()) {
            Trace.traceBegin(
                    Trace.TRACE_TAG_APP, startable.getClass().getSimpleName() + ".start()");
        }
        startable.start();
        Trace.endSection();

        return startable;
    }

    // TODO(b/217567642): add unit tests? There doesn't seem to be a SystemUiApplicationTest...
    @Override
    public boolean addDumpable(Dumpable dumpable) {
        String name = dumpable.getDumpableName();
        if (mDumpables.containsKey(name)) {
            // This is normal because SystemUIApplication is an application context that is shared
            // among multiple components
            if (DEBUG) {
                Log.d(TAG, "addDumpable(): ignoring " + dumpable + " as there is already a dumpable"
                        + " with that name (" + name + "): " + mDumpables.get(name));
            }
            return false;
        }
        if (DEBUG) Log.d(TAG, "addDumpable(): adding '" + name + "' = " + dumpable);
        mDumpables.put(name, dumpable);

        // TODO(b/217567642): replace com.android.systemui.dump.Dumpable by
        // com.android.util.Dumpable and get rid of the intermediate lambda
        mDumpManager.registerDumpable(dumpable.getDumpableName(), dumpable::dump);
        return true;
    }

    // TODO(b/217567642): implement
    @Override
    public boolean removeDumpable(Dumpable dumpable) {
        Log.w(TAG, "removeDumpable(" + dumpable + "): not implemented");

        return false;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mServicesStarted) {
            ConfigurationController configController = mSysUIComponent.getConfigurationController();
            if (Trace.isEnabled()) {
                Trace.traceBegin(
                        Trace.TRACE_TAG_APP,
                        configController.getClass().getSimpleName() + ".onConfigurationChanged()");
            }
            configController.onConfigurationChanged(newConfig);
            Trace.endSection();
            int len = mServices.length;
            for (int i = 0; i < len; i++) {
                if (mServices[i] != null) {
                    if (Trace.isEnabled()) {
                        Trace.traceBegin(
                                Trace.TRACE_TAG_APP,
                                mServices[i].getClass().getSimpleName()
                                        + ".onConfigurationChanged()");
                    }
                    mServices[i].onConfigurationChanged(newConfig);
                    Trace.endSection();
                }
            }
        }
    }

    public CoreStartable[] getServices() {
        return mServices;
    }

    @Override
    public void setContextAvailableCallback(
            SystemUIAppComponentFactory.ContextAvailableCallback callback) {
        mContextAvailableCallback = callback;
    }

    /** Update a notifications application name. */
    public static void overrideNotificationAppName(Context context, Notification.Builder n,
            boolean system) {
        final Bundle extras = new Bundle();
        String appName = system
                ? context.getString(com.android.internal.R.string.notification_app_name_system)
                : context.getString(com.android.internal.R.string.notification_app_name_settings);
        extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, appName);

        n.addExtras(extras);
    }
}
