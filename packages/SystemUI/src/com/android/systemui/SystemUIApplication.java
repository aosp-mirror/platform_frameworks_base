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

import android.annotation.SuppressLint;
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
import android.os.Process;
import android.os.Trace;
import android.util.Log;
import android.util.TimingsTraceLog;
import android.view.SurfaceControl;
import android.view.ThreadedRenderer;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.systemui.dagger.GlobalRootComponent;
import com.android.systemui.dagger.SysUIComponent;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.process.ProcessWrapper;
import com.android.systemui.res.R;
import com.android.systemui.startable.Dependencies;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.util.NotificationChannels;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import javax.inject.Provider;

/**
 * Application class for SystemUI.
 */
public class SystemUIApplication extends Application implements
        SystemUIAppComponentFactoryBase.ContextInitializer {

    public static final String TAG = "SystemUIService";
    private static final boolean DEBUG = false;

    private BootCompleteCacheImpl mBootCompleteCache;

    /**
     * Hold a reference on the stuff we start.
     */
    private CoreStartable[] mServices;
    private boolean mServicesStarted;
    private SystemUIAppComponentFactoryBase.ContextAvailableCallback mContextAvailableCallback;
    private SysUIComponent mSysUIComponent;
    private SystemUIInitializer mInitializer;
    private ProcessWrapper mProcessWrapper;

    public SystemUIApplication() {
        super();
        Log.v(TAG, "SystemUIApplication constructed.");
        // SysUI may be building without protolog preprocessing in some cases
        ProtoLog.REQUIRE_PROTOLOGTOOL = false;
    }

    @VisibleForTesting
    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
    }

    protected GlobalRootComponent getRootComponent() {
        return mInitializer.getRootComponent();
    }

    @SuppressLint("RegisterReceiverViaContext")
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

        GlobalRootComponent rootComponent = mInitializer.getRootComponent();

        // Enable Looper trace points.
        // This allows us to see Handler callbacks on traces.
        rootComponent.getMainLooper().setTraceTag(Trace.TRACE_TAG_APP);
        mProcessWrapper = rootComponent.getProcessWrapper();

        // Set the application theme that is inherited by all services. Note that setting the
        // application theme in the manifest does only work for activities. Keep this in sync with
        // the theme set there.
        setTheme(R.style.Theme_SystemUI);

        View.setTraceLayoutSteps(
                rootComponent.getSystemPropertiesHelper()
                        .getBoolean("persist.debug.trace_layouts", false));
        View.setTracedRequestLayoutClassClass(
                rootComponent.getSystemPropertiesHelper()
                        .get("persist.debug.trace_request_layout_class", null));

        if (Flags.enableLayoutTracing()) {
            View.setTraceLayoutSteps(true);
        }

        if (mProcessWrapper.isSystemUser()) {
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
     * Makes sure that all the CoreStartables are running. If they are already running, this is a
     * no-op. This is needed to conditionally start all the services, as we only need to have it in
     * the main process.
     * <p>This method must only be called from the main thread.</p>
     */

    public void startSystemUserServicesIfNeeded() {
        if (!mProcessWrapper.isSystemUser()) {
            Log.wtf(TAG, "Tried starting SystemUser services on non-SystemUser");
            return;  // Per-user startables are handled in #startSystemUserServicesIfNeeded.
        }
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
        if (mProcessWrapper.isSystemUser()) {
            return;  // Per-user startables are handled in #startSystemUserServicesIfNeeded.
        }
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
            if ("1".equals(getRootComponent().getSystemPropertiesHelper()
                    .get("sys.boot_completed"))) {
                mBootCompleteCache.setBootComplete();
                if (DEBUG) {
                    Log.v(TAG, "BOOT_COMPLETED was already sent");
                }
            }
        }

        DumpManager dumpManager = mSysUIComponent.createDumpManager();

        Log.v(TAG, "Starting SystemUI services for user " +
                Process.myUserHandle().getIdentifier() + ".");
        TimingsTraceLog log = new TimingsTraceLog("SystemUIBootTiming",
                Trace.TRACE_TAG_APP);
        log.traceBegin(metricsPrefix);

        HashSet<Class<?>> startedStartables = new HashSet<>();

        // Perform a form of topological sort:
        // 1) Iterate through a queue of all non-started startables
        //   If the startable has all of its dependencies met
        //     - start it
        //   Else
        //     - enqueue it for the next iteration
        // 2) If anything was started and the "next" queue is not empty, loop back to 1
        // 3) If we're done looping and there are any non-started startables left, throw an error.
        //
        // This "sort" is not very optimized. We assume that most CoreStartables don't have many
        // dependencies - zero in fact. We assume two or three iterations of this loop will be
        // enough. If that ever changes, it may be worth revisiting.

        log.traceBegin("Topologically start Core Startables");
        boolean startedAny = false;
        ArrayDeque<Map.Entry<Class<?>, Provider<CoreStartable>>> queue;
        ArrayDeque<Map.Entry<Class<?>, Provider<CoreStartable>>> nextQueue =
                new ArrayDeque<>(startables.entrySet());
        int numIterations = 0;

        int serviceIndex = 0;

        do {
            queue = nextQueue;
            nextQueue = new ArrayDeque<>(startables.size());

            while (!queue.isEmpty()) {
                Map.Entry<Class<?>, Provider<CoreStartable>> entry = queue.removeFirst();

                Class<?> cls = entry.getKey();
                Dependencies dep = cls.getAnnotation(Dependencies.class);
                Class<? extends CoreStartable>[] deps = (dep == null ? null : dep.value());
                if (deps == null || startedStartables.containsAll(Arrays.asList(deps))) {
                    String clsName = cls.getName();
                    int i = serviceIndex;  // Copied to make lambda happy.
                    timeInitialization(
                            clsName,
                            () -> mServices[i] = startStartable(clsName, entry.getValue()),
                            log,
                            metricsPrefix);
                    startedStartables.add(cls);
                    startedAny = true;
                    serviceIndex++;
                } else {
                    nextQueue.add(entry);
                }
            }
            numIterations++;
        } while (startedAny && !nextQueue.isEmpty()); // if none were started, stop.

        if (!nextQueue.isEmpty()) { // If some startables were left over, throw an error.
            while (!nextQueue.isEmpty()) {
                Map.Entry<Class<?>, Provider<CoreStartable>> entry = nextQueue.removeFirst();
                Class<?> cls = entry.getKey();
                Dependencies dep = cls.getAnnotation(Dependencies.class);
                Class<? extends CoreStartable>[] deps = (dep == null ? null : dep.value());
                StringJoiner stringJoiner = new StringJoiner(", ");
                for (int i = 0; deps != null && i < deps.length; i++) {
                    if (!startedStartables.contains(deps[i])) {
                        stringJoiner.add(deps[i].getName());
                    }
                }
                Log.e(TAG, "Failed to start " + cls.getName()
                        + ". Missing dependencies: [" + stringJoiner + "]");
            }

            throw new RuntimeException("Failed to start all CoreStartables. Check logcat!");
        }
        Log.i(TAG, "Topological CoreStartables completed in " + numIterations + " iterations");
        log.traceEnd();

        if (vendorComponent != null) {
            timeInitialization(
                    vendorComponent,
                    () -> mServices[mServices.length - 1] =
                            startAdditionalStartable(vendorComponent),
                    log,
                    metricsPrefix);
        }

        for (serviceIndex = 0; serviceIndex < mServices.length; serviceIndex++) {
            final CoreStartable service = mServices[serviceIndex];
            if (mBootCompleteCache.isBootComplete()) {
                notifyBootCompleted(service);
            }

            if (service.isDumpCritical()) {
                dumpManager.registerCriticalDumpable(service);
            } else {
                dumpManager.registerNormalDumpable(service);
            }
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
            startable = (CoreStartable) Class.forName(clsName)
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ClassNotFoundException
                 | IllegalAccessException
                 | InstantiationException
                 | NoSuchMethodException
                 | InvocationTargetException ex) {
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

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        if (mServicesStarted) {
            ConfigurationController configController = mSysUIComponent.getConfigurationController();
            if (Trace.isEnabled()) {
                Trace.traceBegin(
                        Trace.TRACE_TAG_APP,
                        configController.getClass().getSimpleName() + ".onConfigurationChanged()");
            }
            configController.onConfigurationChanged(newConfig);
            Trace.endSection();
        }
    }

    public CoreStartable[] getServices() {
        return mServices;
    }

    @Override
    public void setContextAvailableCallback(
            @NonNull SystemUIAppComponentFactoryBase.ContextAvailableCallback callback) {
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
